package com.killer560.hub.chestvalue;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.ContainerOverlayElement;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.ItemValueCalc;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.pricetooltip.PriceTooltipConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chest Value (killer560, 2026-11-18: "a thing for chests that shows the value of the chest and all items in it"): over a
 * plain item container (island chest, Ender Chest page, Backpack, optionally any other) a panel lists what is inside with
 * each line's value, highest first, and the total on top. A stack is worth what the Price Tooltip's Est. Item Value says
 * ({@link ItemValueCalc}, its Value Mode) times its count. Computed only when a slot's stack, the prices or a setting
 * changed, never per frame. Its place is the HUD element "Chest Value" (Edit HUD), beside the container by default.
 */
public final class ChestValue {

    private static final Logger LOGGER = ModLog.get("killer560smod-chestvalue");
    private static final String ELEMENT_ID = "chest_value_panel";
    private static final int PAD = 4, LINE = 10, NAME_PX = 120;
    private static final int CHESTS = 0, ENDER = 1, BACKPACK = 2, OTHER = 3;

    /** One merged line: the same item (and the same worth) with the stacks added. */
    public record Line(String name, int count, double value) {
    }

    /** What a container is worth: lines sorted and cut, the total of the priced ones, how many were unpriced or cut. */
    public record Result(double total, List<Line> lines, int unpriced, int more) {
    }

    private static final List<String> SAMPLE = List.of("§lChest Value: §r§6125.4k", "§712x §fEnchanted Diamond  §632k",
            "§71x §fHyperion  §690k", "§724x §fOak Log  §6120");
    private static ContainerOverlayElement element;
    private static List<String> lines = List.of();
    private static int panelWidth;
    private static String builtKey = "";
    private static long lastTouchMs;
    private static Result last;
    private static int computes;

    private ChestValue() {
    }

    public static void register() {
        element = ContainerOverlayElement.register(ELEMENT_ID, "Chest Value", 150, 40, 10, 30,
                () -> ChestValueConfig.getInstance().isEnabled(), ChestValue::drawPanel)
                .previewSize(() -> new int[]{panelWidth > 0 ? panelWidth : 150, panelHeight()}).clampOnScreen();
        ScreenEvents.AFTER_INIT.register(ChestValue::onScreenInit);
    }

    private static int panelHeight() {
        return (lines.isEmpty() ? SAMPLE : lines).size() * LINE + PAD * 2;
    }

    /** The container kind for a screen title, or -1 for a menu this never covers. */
    private static int kind(String title) {
        if (title.equals("Chest") || title.equals("Large Chest")) {
            return CHESTS;
        }
        if (StorageOverlayFeature.storageKeyForTitle(title) != null) {
            return title.startsWith("Ender Chest") ? ENDER : BACKPACK;
        }
        return title.equals("Storage") ? -1 : OTHER;
    }

    private static boolean shows(ChestValueConfig cfg, int kind) {
        return switch (kind) {
            case CHESTS -> cfg.isChests();
            case ENDER -> cfg.isEnderChest();
            case BACKPACK -> cfg.isBackpacks();
            default -> cfg.isOther();
        };
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container) || !(container.getMenu() instanceof ChestMenu)) {
            return;
        }
        int kind = kind(screen.getTitle().getString());
        if (kind < 0) {
            return;
        }
        builtKey = "";
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            try {
                render(client, container, kind, g);
            } catch (RuntimeException e) {
                LOGGER.error("[ChestValue] render failed", e);
            }
        });
        ScreenEvents.remove(screen).register(s -> {
            lines = List.of();
            last = null;
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, int kind, GuiGraphicsExtractor g) {
        ChestValueConfig cfg = ChestValueConfig.getInstance();
        if (!cfg.isEnabled() || !shows(cfg, kind) || !SkyblockGate.allows()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTouchMs > 10_000L) {
            lastTouchMs = now;
            PriceData.touchFromOtherFeature();
        }
        AbstractContainerMenu menu = screen.getMenu();
        int top = Math.max(0, menu.slots.size() - 36);
        long sig = 7;
        for (int i = 0; i < top; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            sig = sig * 31 + System.identityHashCode(s) * 3L + s.getCount();
        }
        String key = sig + "/" + ChestValueConfig.version() + "/" + PriceData.generation() + "/"
                + PriceTooltipConfig.getInstance().getValueMode() + "/" + System.identityHashCode(menu);
        if (!key.equals(builtKey)) {
            builtKey = key;
            List<ItemStack> stacks = new ArrayList<>(top);
            for (int i = 0; i < top; i++) {
                stacks.add(menu.slots.get(i).getItem());
            }
            last = kind == OTHER && !plain(stacks) ? null : compute(stacks, cfg.getSort(), cfg.getMaxLines());
            lines = last == null ? List.of() : format(last);
            Font font = client.font;
            int w = 0;
            for (String l : lines) {
                w = Math.max(w, font.width(l));
            }
            panelWidth = w + PAD * 2;
        }
        if (lines.isEmpty()) {
            return;
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int x = acc.killer560smod$getLeftPos() - panelWidth - 4;
        if (x < 0) {
            x = acc.killer560smod$getLeftPos() + 176 + 4;
        }
        g.nextStratum();
        element.draw(g, x, acc.killer560smod$getTopPos(), panelWidth, panelHeight(), ChestValue::drawPanel);
    }

    /** A plain "other" container holds only Skyblock items: a button menu (panes, arrows, close) has stacks with no id. */
    private static boolean plain(List<ItemStack> stacks) {
        boolean any = false;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) {
                continue;
            }
            CompoundTag tag = ItemNbt.view(s);
            if (tag == null || ItemKeys.id(tag) == null) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Values the stacks (empty and id-less ones are skipped), merges the same item and sorts; pure, no screen needed. */
    public static Result compute(List<ItemStack> stacks, ChestValueConfig.Sort sort, int maxLines) {
        computes++;
        PriceTooltipConfig.ValueMode mode = PriceTooltipConfig.getInstance().getValueMode();
        record Acc(String name, int[] count, double[] value, boolean priced) {
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        double total = 0;
        int unpriced = 0;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) {
                continue;
            }
            CompoundTag tag = ItemNbt.view(s);
            String id = tag == null ? null : ItemKeys.id(tag);
            if (id == null) {
                continue;
            }
            String name = s.getHoverName().getString();
            double unit = ItemValueCalc.compute(tag, ItemKeys.priceKey(tag, name), mode).total();
            boolean priced = unit > 0;
            Acc a = merged.computeIfAbsent(id + "|" + name + "|" + Math.round(unit) + "|" + priced,
                    k -> new Acc(name, new int[1], new double[1], priced));
            a.count[0] += s.getCount();
            a.value[0] += unit * s.getCount();
            if (priced) {
                total += unit * s.getCount();
            } else {
                unpriced += s.getCount();
            }
        }
        List<Line> all = new ArrayList<>(merged.size());
        for (Acc a : merged.values()) {
            all.add(new Line(a.name, a.count[0], a.priced ? a.value[0] : -1));
        }
        Comparator<Line> byName = Comparator.comparing(l -> l.name().toLowerCase(java.util.Locale.ROOT));
        all.sort(sort == ChestValueConfig.Sort.NAME ? byName : Comparator.comparingDouble(Line::value).reversed().thenComparing(byName));
        int shown = Math.min(maxLines, all.size());
        return new Result(total, List.copyOf(all.subList(0, shown)), unpriced, all.size() - shown);
    }

    private static String coins(double v) {
        return DungeonChestValuer.formatCoins(Math.round(v));
    }

    private static List<String> format(Result r) {
        List<String> out = new ArrayList<>();
        out.add("§lChest Value: §r§6" + coins(r.total()));
        for (Line l : r.lines()) {
            String name = l.name();
            Font font = Minecraft.getInstance().font;
            if (font.width(name) > NAME_PX) {
                name = font.plainSubstrByWidth(name, NAME_PX - font.width("...")) + "...";
            }
            out.add("§7" + l.count() + "x §f" + name + "  " + (l.value() < 0 ? "§8?" : "§6" + coins(l.value())));
        }
        if (r.more() > 0) {
            out.add("§8+" + r.more() + " more");
        }
        if (r.unpriced() > 0) {
            out.add("§8" + r.unpriced() + " unpriced");
        }
        return out;
    }

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String> ls = lines.isEmpty() ? SAMPLE : lines;
        int w = panelWidth > 0 && !lines.isEmpty() ? panelWidth : 150;
        g.fill(x, y, x + w, y + ls.size() * LINE + PAD * 2, 0xC0101010);
        g.outline(x, y, w, ls.size() * LINE + PAD * 2, ThemeAccent.accent(0xFFCC6600));
        int title = ThemeAccent.onDark(ThemeAccent.light(0xFFFFAA00));
        for (int i = 0; i < ls.size(); i++) {
            g.text(font, ls.get(i), x + PAD, y + PAD + i * LINE, i == 0 ? title : 0xFFFFFFFF, true);
        }
    }

    /** Back beside the container (forgets a dragged or Edit HUD spot). */
    public static void resetPosition() {
        com.killer560.hub.hud.HudConfig.getInstance().clearPosition(ELEMENT_ID);
        com.killer560.hub.hud.HudConfig.getInstance().save();
    }

    // Testkit hooks.
    public static Result lastResultForTest() {
        return last;
    }

    public static List<String> linesForTest() {
        return lines;
    }

    public static int computesForTest() {
        return computes;
    }
}
