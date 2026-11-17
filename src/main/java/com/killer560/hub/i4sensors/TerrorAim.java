package com.killer560.hub.i4sensors;

import com.killer560.hub.i4sensors.I4SensorsConfig.Weapon;
import com.killer560.hub.roomsim.SimArmor;
import com.killer560.hub.trajectories.Shortbows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Auto i4's Terror Aim (i4terror-1116, killer560 2026-11-16: "auto detect if i am using terror ... and if i have the
 * stacks. if i do then it should aim differently", for the Terminator and the Machine Gun Shortbow).
 *
 * <p>Terror Armor's Hydra Strike (wiki, Terror Armor): at least 2 pieces worn, and at 10 stacks every bow shot fires 2
 * more arrows. Pieces are read by Skyblock id ({@code TERROR_*}, {@code HOT_/BURNING_/FIERY_/INFERNAL_TERROR_*}), so a
 * mask on the head just leaves 3. Stacks, the newest of:
 * <ul>
 *   <li>the dungeon sim: always 10 while Sim Armor is Terror ({@link SimArmor});</li>
 *   <li>the action bar's {@code N⁑} (ASSUMED: the wiki names the stack "Hydra Strike ⁑", but no log of his shows the
 *       glyph; it counts only once a line with it was seen, so a wrong guess changes nothing);</li>
 *   <li>the arrows his own last aimed shot really spawned: base + 2 means 10 stacks, base alone means fewer.</li>
 * </ul>
 * The extra arrows are laid out like the Terminator's side arrows, {@link Shortbows#SPREAD_DEGREES} apart (ASSUMED for
 * the Machine Gun Shortbow's pair: its spread is not documented).
 */
public final class TerrorAim {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autoi4");
    static final int HYDRA_EXTRA_ARROWS = SimArmor.HYDRA_EXTRA_ARROWS;
    private static final int FULL_STACKS = SimArmor.HYDRA_STACKS;
    /** Hypixel redraws the action bar about every second; older than this is no evidence. */
    private static final long BAR_FRESH_MS = 2500L;
    /** A stack lasts at least 4 s without a new one (2 pieces); shots come every few hundred ms. */
    private static final long ARROWS_FRESH_MS = 3000L;
    /** The lit target's lane must sit this far inside its block sideways; a side lane counts from {@link #SIDE_MARGIN}. */
    static final double MUST_MARGIN = 0.25;
    static final double SIDE_MARGIN = 0.1;
    private static final double SCAN_STEP_RAD = Math.toRadians(0.05);

    private static volatile boolean glyphSeen;
    private static volatile boolean barFull;
    private static volatile long barAtMs;
    private static boolean arrowsFull;
    private static long arrowsAtMs;
    private static Boolean lastActive;
    private static int aimsChosen;
    private static int aimsWeighed;

    /** Testkit: how many Terror Aim points were chosen (proof the aim acted). */
    public static int aimsChosenForTest() {
        return aimsChosen;
    }

    /** Testkit: how many shots Terror Aim weighed against the usual aim (it ran, whichever aim won). */
    public static int aimsWeighedForTest() {
        return aimsWeighed;
    }

    private TerrorAim() {
    }

    /** Every action-bar line (PlayerStatsFeature.onActionBar, packet path: never throws). */
    public static void noteActionBar(String text) {
        int glyph = text == null ? -1 : text.indexOf('⁑');
        if (glyph < 0) {
            if (glyphSeen) {
                barFull = false;
                barAtMs = System.currentTimeMillis();
            }
            return;
        }
        int start = glyph;
        while (start > 0 && glyph - start < 2 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        glyphSeen = true;
        barFull = start < glyph && Integer.parseInt(text, start, glyph, 10) >= FULL_STACKS;
        barAtMs = System.currentTimeMillis();
    }

    /** The arrows one of his aimed shots spawned (AutoI4Feature.arrowLeft). */
    static void noteShotArrows(int arrows, Weapon weapon) {
        arrowsFull = arrows >= weapon.baseArrows + HYDRA_EXTRA_ARROWS;
        arrowsAtMs = System.currentTimeMillis();
    }

    /** Terror pieces worn, by Skyblock id. */
    static int pieces(LocalPlayer player) {
        int n = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            String id = I4SensorsFeature.skyblockId(player.getItemBySlot(slot));
            int at = id.indexOf("TERROR_");
            if (at >= 0 && (at == 0 || id.charAt(at - 1) == '_') && (id.endsWith("_HELMET") || id.endsWith("_CHESTPLATE")
                    || id.endsWith("_LEGGINGS") || id.endsWith("_BOOTS"))) {
                n++;
            }
        }
        return n;
    }

    private static boolean fullStacks(Minecraft client) {
        if (com.killer560.hub.roomsim.SimState.canAct(client)) {
            return SimArmor.current() == SimArmor.TERROR;
        }
        long now = System.currentTimeMillis();
        boolean bar = glyphSeen && now - barAtMs <= BAR_FRESH_MS;
        boolean shot = arrowsAtMs > 0 && now - arrowsAtMs <= ARROWS_FRESH_MS;
        if (bar && shot) {
            return barAtMs >= arrowsAtMs ? barFull : arrowsFull;
        }
        return bar ? barFull : shot && arrowsFull;
    }

    /** Terror Aim on, 2+ Terror pieces worn and 10 Hydra Strike stacks. Read once per planned shot, never per frame. */
    static boolean active(I4SensorsConfig cfg, Minecraft client, LocalPlayer player) {
        boolean on = cfg.isAutoI4TerrorAim() && player != null && pieces(player) >= 2 && fullStacks(client);
        if (lastActive == null || lastActive != on) {
            lastActive = on;
            LOGGER.info("[AutoI4] Terror Aim {}: {} Terror piece(s), {} arrows a shot", on ? "ACTIVE" : "inactive",
                    player == null ? 0 : pieces(player), arrows(cfg.getAutoI4Weapon(), on));
        }
        return on;
    }

    static int arrows(Weapon weapon, boolean active) {
        return weapon.baseArrows + (active ? HYDRA_EXTRA_ARROWS : 0);
    }

    /** The aim height of a row (0 = y 130), as Auto i4 has always aimed; -1 if {@code y} is no row's. */
    private static int rowOf(double y) {
        int row = (int) Math.round((131 - y) / 2.0);
        return row >= 0 && row <= 2 && Math.abs(y - (131 - 2.0 * row)) <= 0.01 ? row : -1;
    }

    /** DEV_BLOCKS index (row * 3 + col, col 0 = x 68) of the block lane {@code x} lands in, -1 for a gap; margin out. */
    private static int column(double x, double[] margin) {
        for (int col = 0; col < 3; col++) {
            int bx = 68 - 2 * col;
            if (x >= bx && x <= bx + 1) {
                margin[0] = Math.min(x - bx, bx + 1 - x);
                return col;
            }
        }
        return -1;
    }

    /** Bit mask (by DEV_BLOCKS index) of the targets a shot of {@code arrows} at {@code aim} from {@code eye} hits, each
     *  lane at least {@link #SIDE_MARGIN} inside its block sideways. Arrows keep the shot's pitch, so all hit one row. */
    static int hitMask(Vec3 eye, Vec3 aim, int arrows) {
        int row = rowOf(aim.y);
        double dz = aim.z - eye.z;
        if (row < 0 || dz <= 0) {
            return 0;
        }
        double center = Math.atan2(aim.x - eye.x, dz);
        int mask = 0;
        double[] margin = new double[1];
        for (float offset : Shortbows.yawOffsets(arrows)) {
            int col = column(eye.x + dz * Math.tan(center + Math.toRadians(offset)), margin);
            if (col >= 0 && margin[0] >= SIDE_MARGIN) {
                mask |= 1 << (row * 3 + col);
            }
        }
        return mask;
    }

    /**
     * The aim on the target at DEV_BLOCKS {@code index}'s row (the row's usual aim height, z 50) whose fan of
     * {@code arrows} puts one lane at least {@link #MUST_MARGIN} inside that target and covers the most targets of
     * {@code needMask}; ties go to the widest smallest margin. Null if no aim reaches the target that well.
     */
    static Vec3 bestAim(Vec3 eye, int index, int needMask, int arrows) {
        int row = index / 3;
        int mustCol = index % 3;
        double y = 131 - 2.0 * row;
        double dz = 50 - eye.z;
        if (dz <= 0) {
            return null;
        }
        float[] offsets = Shortbows.yawOffsets(arrows);
        double from = Math.atan2(62 - eye.x, dz);
        double to = Math.atan2(71 - eye.x, dz);
        double best = Double.NaN;
        int bestCount = -1;
        double bestMargin = -1;
        double[] margin = new double[1];
        for (double center = from; center <= to; center += SCAN_STEP_RAD) {
            boolean must = false;
            int mask = 0;
            double smallest = Double.MAX_VALUE;
            for (float offset : offsets) {
                int col = column(eye.x + dz * Math.tan(center + Math.toRadians(offset)), margin);
                if (col < 0 || margin[0] < SIDE_MARGIN) {
                    continue;
                }
                if (col == mustCol && margin[0] >= MUST_MARGIN) {
                    must = true;
                }
                mask |= 1 << (row * 3 + col);
                smallest = Math.min(smallest, margin[0]);
            }
            if (!must) {
                continue;
            }
            int count = Integer.bitCount(mask & needMask);
            if (count > bestCount || count == bestCount && smallest > bestMargin) {
                bestCount = count;
                bestMargin = smallest;
                best = center;
            }
        }
        return Double.isNaN(best) ? null : new Vec3(eye.x + dz * Math.tan(best), y, 50);
    }

    /** {@link #bestAim} when its fan covers more of {@code needMask} than the {@code usual} aim's does with the same
     *  arrows, else {@code usual}: the usual aim already gains from the extra arrows, so Terror Aim moves it only for a gain. */
    static Vec3 choose(Vec3 eye, int index, int needMask, int arrows, Vec3 usual) {
        aimsWeighed++;
        Vec3 best = bestAim(eye, index, needMask, arrows);
        if (best == null || Integer.bitCount(hitMask(eye, best, arrows) & needMask)
                <= Integer.bitCount(hitMask(eye, usual, arrows) & needMask)) {
            return usual;
        }
        aimsChosen++;
        return best;
    }
}
