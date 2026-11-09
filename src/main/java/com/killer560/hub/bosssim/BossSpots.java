package com.killer560.hub.bosssim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * The named standing spots of the sim's F7 boss, for {@code /tpto} and {@code /goto} (boss-tpto-1109) and for the F7 Boss
 * menu's Section 2, 3 and 4 buttons. The coordinates are Hypixel's, the ones Posmsg ships from killer560's own config
 * (PosmsgConfig presets) and the sim's own start spots (BossFight); a landing goes through {@code SimArrival.place}
 * ({@link BossFight#teleportAll}), the same as every other sim landing.
 */
public final class BossSpots {

    /** {@code section} is the P3 section whose start spot stands in when the room is the plan's (no captured ground). */
    private record Spot(String name, double x, double y, double z, float yaw, int section, boolean needsCapture) {
    }

    /** A resolved landing: feet position and yaw. */
    public record Landing(Vec3 at, float yaw) {
    }

    private static final List<Spot> SPOTS = List.of(
            new Spot("ss", 107.87779359798764, 120.0, 93.91732371769773, -90f, 1, true),   // Posmsg "Simon Says"; the device is east
            new Spot("s1", 100.5, Double.NaN, 40.5, 0f, 1, false),                          // BossFight's section 1 start (ground scan)
            new Spot("hee2", 60.5, 132.0, 139.0, 0f, 2, true),                              // Posmsg "EE2 (High - Lever Device)"
            new Spot("ee3", 2.5, 109.0, 105.5, -90f, 3, true),                              // Posmsg "EE3 (Low)"
            new Spot("outcore", 54.5, 115.0, 50.5, 0f, 4, true),                            // Posmsg "Outcore"
            new Spot("storm", 73.5, BossArena.P2_FLOOR + 1, 53.5, 0f, 0, false),            // centre of Storm's route (73, 53)
            new Spot("maxor", 73.5, BossArena.P1_FLOOR + 1, 14.5, 0f, 0, false),            // BossFight.P1_SPAWN
            new Spot("p5", 54.5, BossArena.P5_FLOOR + 1, 76.5, 180f, 0, false),             // BossFight.P5_SPAWN, Posmsg "P5"
            new Spot("necron", 54.5, 65.0, 76.5, 180f, 0, false));                          // Posmsg "Necron's Platform"

    private BossSpots() {
    }

    /** Every name, in list order. */
    public static List<String> names() {
        return SPOTS.stream().map(Spot::name).toList();
    }

    /** The spot a typed name means: exact (any case), else a unique prefix; null when none or several. */
    public static String resolve(String typed) {
        String want = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return null;
        String hit = null;
        for (Spot s : SPOTS) {
            if (s.name().equals(want)) return s.name();
            if (s.name().startsWith(want)) hit = hit == null ? s.name() : "";
        }
        return hit == null || hit.isEmpty() ? null : hit;
    }

    /** Where {@code name} lands in {@code level}. Server thread (it reads blocks). */
    public static Landing landing(ServerLevel level, String name) {
        Spot s = SPOTS.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
        boolean planOnly = s.needsCapture() && !"capture".equals(BossArena.source());
        if (planOnly || Double.isNaN(s.y())) {
            Vec3 at = BossFight.sectionSpawn(level, Math.max(1, s.section()));
            return new Landing(at, 0f);
        }
        return new Landing(new Vec3(s.x(), s.y(), s.z()), s.yaw());
    }
}
