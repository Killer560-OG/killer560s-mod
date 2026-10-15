package com.killer560.hub.pricetooltip;

import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Price Tooltip (killer560, 2026-10-15): appends Skyblocker-style price lines to a Skyblock item's tooltip -
 * "NPC Sell Price", "Bazaar Buy/Sell", "Lowest BIN Price", "3 Day Avg. Price", "Est. Item Value" (with a breakdown
 * while Shift is held), "Obtained" and "Museum (Combat): Donated". Yellow labels, aqua values, a red date.
 *
 * <p>Hooked at the RETURN of {@code Screen#getTooltipFromItem} and {@code AbstractContainerScreen#
 * getTooltipFromContainerItem} (the same two funnels the old Item Value line used), never on the item's own data.
 *
 * <p>Cost per frame: a hovered stack's lines are built once and kept against the identity of its custom-data tag, its
 * count, Shift, the price {@link PriceData#generation()} and the settings, so a frame that hovers the same item is a
 * few reference compares and one list copy - no NBT walking, JSON or I/O. Prices are fetched in the background
 * ({@link PriceData}); nothing here waits on them. Every entry point swallows its own throwables: a tooltip must never
 * fail because of this.
 */
public final class PriceTooltipFeature {

    private static final AtomicLong SETTINGS_STAMP = new AtomicLong();
    private static volatile Boolean shiftOverride = null;

    private record Entry(CompoundTag tag, int count, boolean shift, long gen, long stamp, List<Component> lines) {
    }

    private static final int CACHE_SIZE = 4;
    private static final Entry[] CACHE = new Entry[CACHE_SIZE];
    private static int nextSlot = 0;

    private PriceTooltipFeature() {
    }

    /** Settings or the museum changed: every cached tooltip is rebuilt on its next frame. */
    public static void invalidate() {
        SETTINGS_STAMP.incrementAndGet();
    }

    /**
     * {@code lines} with this feature's lines appended, or null to leave the tooltip as it is (off, not a Skyblock
     * item, nothing known yet, or the lines are already there from the other hook).
     */
    public static List<Component> append(ItemStack stack, List<Component> lines) {
        try {
            if (stack == null || stack.isEmpty() || lines == null || !PriceTooltipConfig.getInstance().isEnabled()) {
                return null;
            }
            CompoundTag tag = ItemNbt.view(stack);
            if (tag == null || !tag.contains("id")) {
                return null;
            }
            PriceData.touch();
            List<Component> mine = linesFor(stack, tag);
            if (mine.isEmpty()) {
                return null;
            }
            // Idempotent: getTooltipFromContainerItem can reach getTooltipFromItem, so both hooks may see one list.
            int at = lines.size() - mine.size();
            if (at >= 0 && lines.get(at) == mine.get(0)) {
                return null;
            }
            List<Component> out = new ArrayList<>(lines.size() + mine.size());
            out.addAll(lines);
            out.addAll(mine);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** This feature's lines for a stack (cached), empty when it has none. */
    public static List<Component> linesFor(ItemStack stack, CompoundTag tag) {
        boolean shift = shiftDown();
        long gen = PriceData.generation();
        long stamp = SETTINGS_STAMP.get();
        int count = stack.getCount();
        synchronized (CACHE) {
            for (Entry e : CACHE) {
                if (e != null && e.tag == tag && e.count == count && e.shift == shift && e.gen == gen && e.stamp == stamp) {
                    return e.lines;
                }
            }
        }
        List<Component> built = build(stack, tag, shift);
        synchronized (CACHE) {
            CACHE[nextSlot] = new Entry(tag, count, shift, gen, stamp, built);
            nextSlot = (nextSlot + 1) % CACHE_SIZE;
        }
        return built;
    }

    private static List<Component> build(ItemStack stack, CompoundTag tag, boolean shift) {
        PriceTooltipConfig cfg = PriceTooltipConfig.getInstance();
        List<Component> out = new ArrayList<>();
        String id = ItemKeys.id(tag);
        if (id == null) {
            return List.of();
        }
        String key = ItemKeys.priceKey(tag, stack.getHoverName().getString());
        PriceData.ItemInfo info = PriceData.item(id);
        int count = Math.max(1, stack.getCount());
        boolean multiply = count > 1 && (cfg.getStackPrice() == PriceTooltipConfig.When.ALWAYS
                || (cfg.getStackPrice() == PriceTooltipConfig.When.SHIFT && shift));
        int mult = multiply ? count : 1;

        if (cfg.isNpcPrice() && info != null && info.npcPrice() != null && info.npcPrice() > 0) {
            out.add(price("NPC Sell Price", info.npcPrice() * mult, mult));
        }
        boolean bazaar = PriceData.isBazaarProduct(key);
        if (cfg.isBazaar() && bazaar) {
            Double buy = PriceData.bazaarBuy(key);
            Double sell = PriceData.bazaarSell(key);
            out.add(Component.literal("§eBazaar Buy/Sell: §b" + coins(buy == null ? 0 : buy * mult) + " / "
                    + coins(sell == null ? 0 : sell * mult) + " Coins" + countSuffix(mult)));
        }
        if (!bazaar) {
            Double lbin = PriceData.lowestBin(key);
            if (cfg.isLowestBin() && lbin != null) {
                out.add(price("Lowest BIN Price", lbin * mult, mult));
            }
            Double avg = PriceData.average(key, cfg.getAverageDays());
            if (cfg.isAverageBin() && avg != null) {
                out.add(price(cfg.getAverageDays() + " Day Avg. Price", avg * mult, mult));
            }
            if (cfg.isEstimatedValue()) {
                ItemValueCalc.Result r = ItemValueCalc.compute(tag, key, cfg.getValueMode());
                if (r.total() > 0) {
                    out.add(Component.literal("§eEst. Item Value: §b" + coins(r.total()) + " Coins"));
                    boolean showParts = cfg.getBreakdown() == PriceTooltipConfig.When.ALWAYS
                            || (cfg.getBreakdown() == PriceTooltipConfig.When.SHIFT && shift);
                    if (showParts && r.parts().size() > 1) {
                        for (ItemValueCalc.Part p : r.parts()) {
                            out.add(Component.literal(" §8- §7" + p.label() + ": §b" + coins(p.value())));
                        }
                    }
                }
            }
        }
        if (cfg.isObtained()) {
            String date = obtainedDate(tag);
            if (date != null) {
                out.add(Component.literal("§eObtained: §c" + date));
            }
        }
        if (cfg.isMuseum() && info != null && info.museumCategory() != null) {
            String label = "§eMuseum (" + title(info.museumCategory()) + ")";
            Boolean donated = MuseumDonations.isDonated(id, info);
            out.add(Component.literal(donated == null ? label : label + ": " + (donated ? "§bDonated" : "§cNot Donated")));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Component price(String label, double value, int mult) {
        return Component.literal("§e" + label + ": §b" + coins(value) + " Coins" + countSuffix(mult));
    }

    private static String countSuffix(int mult) {
        return mult > 1 ? " §8(" + mult + "x)" : "";
    }

    // ------------------------------------------------------------------ formatting

    private static final ThreadLocal<DecimalFormat> EXACT = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0.0", DecimalFormatSymbols.getInstance(Locale.US)));

    /** "5,900,000.0" (Exact) or "5.9M" (Short). */
    public static String coins(double v) {
        if (PriceTooltipConfig.getInstance().getNumberFormat() == PriceTooltipConfig.NumberFormat.SHORT) {
            return shortCoins(v);
        }
        return EXACT.get().format(v);
    }

    static String shortCoins(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) {
            return sign + trim(a / 1e9) + "B";
        }
        if (a >= 1e6) {
            return sign + trim(a / 1e6) + "M";
        }
        if (a >= 1e3) {
            return sign + trim(a / 1e3) + "k";
        }
        return sign + trim(a);
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter LEGACY = DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US);

    /**
     * {@code timestamp} as "April 24, 2026" in this computer's time zone, or null. Real items carry epoch milliseconds
     * as a long (checked on 867 auction items, 2026-10-15); very old ones a string "4/24/20 3:15 PM" in UTC.
     */
    public static String obtainedDate(CompoundTag tag) {
        Tag t = tag.get("timestamp");
        Instant when = null;
        if (t instanceof NumericTag n) {
            long ms = n.longValue();
            if (ms > 0) {
                when = Instant.ofEpochMilli(ms < 100_000_000_000L ? ms * 1000 : ms);
            }
        } else if (t instanceof StringTag s) {
            try {
                when = LocalDateTime.parse(s.value().trim().toUpperCase(Locale.ROOT), LEGACY).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // Unknown shape: no line.
            }
        }
        return when == null ? null : SHOWN.format(when.atZone(ZoneId.systemDefault()));
    }

    private static String title(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String w : upper.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static boolean shiftDown() {
        Boolean o = shiftOverride;
        if (o != null) {
            return o;
        }
        try {
            var window = Minecraft.getInstance().getWindow();
            return KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Testkit: pretend Shift is (true) or is not (false) held; null goes back to the keyboard. */
    public static void setShiftForTest(Boolean down) {
        shiftOverride = down;
    }
}
