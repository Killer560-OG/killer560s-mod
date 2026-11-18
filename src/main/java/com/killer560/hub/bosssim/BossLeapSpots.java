package com.killer560.hub.bosssim;

import com.killer560.hub.dungeonclass.DungeonClass;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Where a Spirit Leap / InfiniLeap to a teammate of a class lands in the sim's F7/M7 boss (simleapspots-1117, killer560
 * 2026-11-17: "in maxor depending on the class the person is playing have a few different warps ... if they try to leap to
 * someone else have it say that they lept wrong and try again"). Every spot and look is his, taken in game
 * (killer560s-mod-logs/simleapspots-1117/SPEC.md); a class with no spot in the current stage is a wrong leap (null).
 * Storm splits by floor, P3 by section (the open core counts as S4), Necron has only the Healer (another spot when the
 * leaper plays Mage), and in P5 Tank, Healer and Mage stand under the dragon that is spawning ({@link BossFight#leapDragon}).
 * Server thread only.
 */
public final class BossLeapSpots {

    /** Feet position and look; {@code keepLook}: the leap keeps his own look (P5, no look given). */
    public record Spot(double x, double y, double z, float yaw, float pitch, boolean keepLook) {
    }

    private static Spot s(double x, double y, double z, double yaw, double pitch) {
        return new Spot(x, y, z, (float) yaw, (float) pitch, false);
    }

    /** One stage's spots in DungeonClass order (Mage, Tank, Healer, Archer, Berserk); null = no spot. */
    private static Spot[] row(Spot mage, Spot tank, Spot healer, Spot archer, Spot berserk) {
        return new Spot[]{mage, tank, healer, archer, berserk};
    }

    private static final Spot[] MAXOR = row(null,
            s(95.5, 224.0, 40.5, 51.0, -6.4), s(51.5, 224.0, 40.5, -51.6, -9.7), null, null);
    private static final Spot[] STORM_F7 = row(s(57.298, 169.0, 64.702, -90.7, -10.8),
            s(109.291, 170.0, 94.586, 162.1, -19.6), s(107.899, 120.0, 94.014, -90.2, -0.6),
            s(37.5, 170.0, 89.5, -118.5, -9.5), s(101.084, 115.0625, 50.249, -0.1, -7.2));
    private static final Spot[] STORM_M7 = row(s(87.700, 169.0, 66.291, -100.7, -43.9),
            s(57.299, 169.0, 64.700, -91.1, -14.7), s(87.5, 166.0, 89.5, -154.6, -26.7),
            s(88.5, 169.0, 74.5, -133.8, -41.3), s(100.938, 115.0625, 50.833, 0.8, -7.6));
    private static final Spot[][] GOLDOR = {
            row(s(60.5, 132.0, 139.0, 90.9, 0.8), s(108.032, 113.0, 73.075, 159.4, -12.6),
                    s(107.878, 120.0, 93.917, -89.8, -0.4), s(93.119, 112.0, 92.443, 89.8, 18.4),
                    s(63.5, 127.0, 35.5, -13.1, 1.5)),
            row(s(59.5, 120.0, 125.728, -179.4, 15.6), s(2.437, 109.0, 105.127, -180.0, 0.3),
                    s(69.006, 109.0, 124.391, 167.3, 21.7), s(39.382, 109.0, 140.399, 0.8, 36.4),
                    s(44.979, 109.0, 123.341, -110.7, 26.6)),
            row(s(54.5, 115.0625, 50.5, 179.5, -2.6), s(-0.108, 109.0, 112.268, 88.2, 22.6),
                    s(1.200, 119.0, 93.472, 90.1, 17.9), s(1.148, 109.0, 77.841, -168.5, -8.7),
                    s(18.649, 121.5, 91.300, -1.3, -10.2)),
            row(s(54.367, 115.0, 58.454, 179.9, -0.6), s(41.431, 109.0, 32.975, -177.6, 16.8),
                    s(72.678, 115.0, 45.725, 1.7, 25.9), s(44.105, 121.0, 31.766, -173.0, 27.9),
                    s(67.224, 109.0, 32.907, -178.5, 20.5))};
    private static final Spot NECRON_HEALER = s(54.5, 5.0, 76.5, 179.9, -2.3);
    private static final Spot NECRON_HEALER_FOR_MAGE = s(88.472, 6.0, 95.780, -110.2, 1.7);

    private BossLeapSpots() {
    }

    /** Whether a boss stage with leap spots is running (P1-P5); outside it leaps go to the player as before. */
    public static boolean active() {
        BossFight.Phase p = BossFight.phase();
        return p == BossFight.Phase.P1 || p == BossFight.Phase.P2 || p == BossFight.Phase.P3
                || p == BossFight.Phase.P4 || p == BossFight.Phase.P5;
    }

    /** The stage's name for the wrong-leap line: Maxor, Storm, Goldor S1-S4, Necron, Dragons. */
    public static String stageName() {
        return switch (BossFight.phase()) {
            case P1 -> "Maxor";
            case P2 -> "Storm";
            case P3 -> "Goldor S" + goldorSection();
            case P4 -> "Necron";
            case P5 -> "Dragons";
            default -> "the boss";
        };
    }

    private static int goldorSection() {
        return Math.max(1, Math.min(4, BossFight.section()));
    }

    /** Where a leap to a {@code target}-class teammate lands for a {@code leaper}-class player; null = a wrong leap. */
    public static Spot spot(ServerLevel level, DungeonClass target, DungeonClass leaper) {
        int c = target.ordinal();
        return switch (BossFight.phase()) {
            case P1 -> MAXOR[c];
            case P2 -> (BossFight.master() ? STORM_M7 : STORM_F7)[c];
            case P3 -> GOLDOR[goldorSection() - 1][c];
            case P4 -> !BossFight.master() ? necronF7(level)   // F7: every class goes to /tpto necron (killer560 2026-11-17)
                    : target != DungeonClass.HEALER ? null
                    : leaper == DungeonClass.MAGE ? NECRON_HEALER_FOR_MAGE : NECRON_HEALER;
            case P5 -> target == DungeonClass.ARCHER || target == DungeonClass.BERSERKER ? null : underDragon(level);
            default -> null;
        };
    }

    private static Spot necronF7(ServerLevel level) {
        BossSpots.Landing l = BossSpots.landing(level, "necron");
        return new Spot(l.at().x, l.at().y, l.at().z, l.yaw(), 0f, false);
    }

    private static Spot underDragon(ServerLevel level) {
        BlockPos d = BossFight.leapDragon();
        if (d == null) return null;
        double x = d.getX() + 0.5, z = d.getZ() + 0.5;
        return new Spot(x, BossArena.standY(level, x, z, BossArena.P5_FLOOR, d.getY()), z, 0f, 0f, true);
    }
}
