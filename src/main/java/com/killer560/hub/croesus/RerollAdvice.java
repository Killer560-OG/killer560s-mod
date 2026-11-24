package com.killer560.hub.croesus;

import com.killer560.hub.croesus.DungeonChestValuer.ChestType;
import com.killer560.hub.croesus.DungeonChestValuer.ChestValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reroll Advice (idea-rerollev-1124): is a fresh chest of this floor and type worth more, on average, than the one on
 * screen once the Kismet Feather is paid? The average is his OWN claim log ({@link CroesusProfitLog#allClaims}), priced
 * as it was claimed, so it counts essence and every drop the wiki odds table leaves out; the median is shown beside it
 * because one rare drop pulls the average up. It never clicks: Reroll Advice only draws text, Auto Croesus may use the
 * same verdict instead of its fixed Reroll Below. With fewer than {@link #MIN_CLAIMS} claims of that floor and chest it
 * says so and gives no verdict. The log only holds chests he claimed, so a floor where he rerolls a lot is judged on
 * the chests he kept.
 */
public final class RerollAdvice {

    public static final int MIN_CLAIMS = 10;

    /** @param enough false: too few claims, no verdict. */
    public record Advice(boolean enough, boolean reroll, long gain, long mean, long median, int count, long kismet,
                         long current) {
        /** Median says the opposite of the average: a fresh chest usually beats this one by less than the Kismet. */
        public boolean medianKeeps() {
            return reroll && median - kismet - current <= 0;
        }
    }

    private static final Map<String, long[]> CACHE = new HashMap<>();
    private static int cacheRevision = -1;
    private static int lastContainer = Integer.MIN_VALUE;
    private static long lastAtMs = 0L;
    private static List<String> lines = List.of();
    private static volatile String lastDrawn = null;

    private RerollAdvice() {
    }

    /** The verdict for a chest worth {@code current} against sorted past chest values; pure, for the cases. */
    public static Advice advise(long current, long kismet, long[] sortedValues) {
        int n = sortedValues.length;
        if (n < MIN_CLAIMS) {
            return new Advice(false, false, 0, 0, 0, n, kismet, current);
        }
        long sum = 0;
        for (long v : sortedValues) {
            sum += v;
        }
        long mean = sum / n;
        long median = n % 2 == 1 ? sortedValues[n / 2] : (sortedValues[n / 2 - 1] + sortedValues[n / 2]) / 2;
        long gain = mean - kismet - current;
        return new Advice(true, gain > 0, gain, mean, median, n, kismet, current);
    }

    /** The values of his claimed chests of this floor and type, sorted; rebuilt only when the log changed. */
    public static synchronized long[] valuesFor(String floor, ChestType type) {
        int rev = CroesusProfitLog.revision();
        if (rev != cacheRevision) {
            CACHE.clear();
            cacheRevision = rev;
        }
        return CACHE.computeIfAbsent(floor + "|" + type.display, k -> {
            List<Long> out = new ArrayList<>();
            for (CroesusProfitLog.Claim c : CroesusProfitLog.allClaims()) {
                if (c.floor().equals(floor) && c.chest().equals(type.display) && c.value() > 0) {
                    out.add(c.value());
                }
            }
            long[] a = out.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(a);
            return a;
        });
    }

    /** Advice for {@code chest} on {@code floor}, or null when the floor is unknown or the Kismet price is not loaded. */
    public static Advice forChest(String floor, ChestType type, long chestValue) {
        Long kismet = DungeonChestValuer.kismetPrice();
        if (floor == null || floor.equals("Unknown") || kismet == null || type == null) {
            return null;
        }
        return advise(chestValue, kismet, valuesFor(floor, type));
    }

    /** Auto Croesus: reroll this Bedrock chest by the verdict; null when there is no verdict (use Reroll Below). */
    public static Boolean autoVerdict(String floor, ChestValue chest) {
        Advice a = forChest(floor, chest.type(), chest.value());
        return a == null || !a.enough() ? null : a.reroll();
    }

    static List<String> describe(Advice a) {
        if (a == null) {
            return List.of("Reroll: floor or Kismet price unknown");
        }
        if (!a.enough()) {
            return List.of("Reroll: not enough data yet (" + a.count() + "/" + MIN_CLAIMS + " claims)");
        }
        return List.of(
                a.reroll() ? "Reroll: +" + DungeonChestValuer.formatCoins(a.gain()) + " expected"
                        + (a.medianKeeps() ? " (median: keep)" : "") : "Keep: fresh chest "
                        + DungeonChestValuer.formatCoins(-a.gain()) + " short",
                "avg " + DungeonChestValuer.formatCoins(a.mean()) + ", median " + DungeonChestValuer.formatCoins(a.median())
                        + " of " + a.count() + " - Kismet " + DungeonChestValuer.formatCoins(a.kismet()),
                "this chest " + DungeonChestValuer.formatCoins(a.current()));
    }

    /** After the reroll button's item is drawn (CroesusSlotMixin TAIL). Recomputed once a second, not per frame. */
    public static void draw(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (slot.index != DungeonChestValuer.REROLL_BUTTON_SLOT || !CroesusConfig.getInstance().isRerollAdvice()
                || !DungeonChestValuer.isRerollButton(slot.getItem())
                || DungeonChestValuer.rerollAlreadyUsed(slot.getItem())) {
            return;
        }
        ChestType type = ChestType.fromName(ChestProfitFeature.titleOf(screen));
        if (type == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int id = screen.getMenu().containerId;
        if (id != lastContainer || now - lastAtMs > 1000L) {
            lastContainer = id;
            lastAtMs = now;
            lines = !DungeonChestValuer.pricesReady() ? List.of("Reroll: prices not loaded") : describe(
                    forChest(ChestProfitFeature.floorForChestScreen(), type,
                            DungeonChestValuer.fromChestScreen(type, ChestProfitFeature.containerStacks(screen)).value()));
        }
        Minecraft client = Minecraft.getInstance();
        lastDrawn = lines.get(0);
        int y = slot.y + 20;
        for (String line : lines) {
            graphics.text(client.font, line, slot.x + 8 - client.font.width(line) / 2, y,
                    line == lines.get(0) ? (lastDrawn.startsWith("Reroll: +") ? 0xFF55FF55 : 0xFFFFFFFF) : 0xFFAAAAAA, true);
            y += 10;
        }
    }

    /** For the testkit: the first line last drawn (null before any). */
    public static String lastDrawn() {
        return lastDrawn;
    }
}
