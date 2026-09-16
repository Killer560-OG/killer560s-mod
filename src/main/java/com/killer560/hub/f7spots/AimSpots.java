package com.killer560.hub.f7spots;

import java.util.List;

/**
 * The only aim spots this mod ships with built-in coordinates for, and the reason each one is trusted.
 * <p>
 * <b>What IS built in</b> - {@link #ARROW_STACK}: NoammAddons' "Dragon Arrow Stack" indicator ("Shows the optimal
 * arrow stack aim position"), local copy {@code C:\Users\Hunter\noammaddonsmod},
 * {@code features/impl/floor7/dragons/WitherDragons.kt}. Its target point is
 * {@code (fixedStackPositions[dragon] ?: dragon.spawnPos).add(0.5, 3.5, 0.5)}, with
 * <pre>
 * fixedStackPositions = { Green -&gt; (27, spawnY, 90), Red -&gt; (28, spawnY, 58), Blue -&gt; (84, spawnY, 97) }
 * </pre>
 * and {@code spawnPos} (Odin {@code WitherDragonsEnum.kt}, already ported in this mod as
 * {@code witherdragons/WitherDragon.java}): Red (27,14,59), Orange (85,14,56), Green (27,14,94), Blue (84,14,94),
 * Purple (56,14,125) - all spawn Y = 14. Orange and Purple have no fixed stack position in NoammAddons, so they
 * fall back to spawn + (0.5, 3.5, 0.5), exactly like NoammAddons does. NoammAddons draws a *lead-corrected*
 * circle (it simulates arrow drop from where you stand); this mod draws the un-led target point itself, so treat
 * it as "the spot the stack is aimed at", not as a crosshair overlay.
 * <p>
 * <b>What IS built in</b> - {@link #DEVONIAN_LB}: Devonian's Last Breath waypoints for the five P5 dragons.
 * Source: {@code devonian-1.29.9.jar} in the 26.1.2 (Dungeons) mods folder and the local checkout
 * {@code C:\Users\Hunter\UsersHunterdevonian} (v1.25.9 - identical values),
 * {@code features/dungeons/m7/M7Dragon.kt}, enum field {@code waypoints}. They are drawn by
 * {@code features/dungeons/m7/DragonBoxes.kt}'s "Dragon Waypoints" switch, whose description is
 * "currently only lb waypoints" and whose search tags are {@code "last"} / {@code "breath"} - that switch is the
 * only place Devonian names them as Last Breath spots.
 * <pre>
 * POWER/Red (27, 0, 56)   FLAME/Orange (82, 0, 56)   APEX/Green (27, 0, 92)
 * ICE/Blue  (82, 0, 96)   SOUL/Purple  (56, 0, 124)
 * </pre>
 * Devonian stores each as a {@code BlockPos} with <b>y = 0</b> and renders it as a vertical beam from y = 0 up to
 * {@code maxY = 17.0} ({@code renderBeam(bp.x, bp.y, bp.z, colour, maxY = 17.0)}, the Double overload, so no +0.5
 * block centring) - it marks an X/Z column, not a single point. This mod's markers are points, so each crosshair
 * goes at the TOP of that column, {@code y = 17.0}, the only other Y Devonian names; X and Z are Devonian's
 * numbers exactly and nothing is interpolated. Each column sits 1-4 blocks from its dragon's spawn point along
 * the first ticks of that dragon's {@code path}, i.e. ahead of where the dragon comes out. Devonian makes no
 * class distinction for them, so all five ship as {@link AimSpot#ANY_CLASS}. Toggled by "Devonian LB Spots",
 * default OFF.
 * <p>
 * <b>What is NOT built in</b>: <i>per-class</i> Last Breath aim spots, P2 Storm spots, P3 terminal spots. Last
 * Breath is a Floor 5 legendary dungeon bow whose ability strips 10% of a target's max Defense per hit (5 stacks
 * / 50% max), and M7 parties "prefire" it at dragons (hypixelskyblock.minecraft.wiki/w/Last_Breath). Beyond
 * Devonian's five class-agnostic dragon columns above, no wiki page, forum thread, or source file in
 * Odin / NoammAddons / Skytils / BloomCore that could be checked publishes fixed x/y/z aim spots per class for
 * the P5 dragons, for P2 Storm, or for Berserker during P3 terminals - so nothing is guessed here. Those lists
 * start EMPTY and killer560 fills them with "Add Aim Spot Here" (or by hand-editing {@code aimSpotList} in
 * {@code killer560smod-f7spots.json}, see {@link F7SpotsConfig}).
 */
public final class AimSpots {

    // Same dragon colours the Wither Dragons feature uses (Odin's WitherDragonsEnum argb values).
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;
    private static final int GREEN = 0xFF55FF55;
    private static final int BLUE = 0xFF55FFFF;
    private static final int PURPLE = 0xFFAA00AA;

    /** NoammAddons' arrow-stack aim points (see class doc). Toggled by "Arrow Stack Spots", default OFF. */
    public static final List<AimSpot> ARROW_STACK = List.of(
            // fixed stack position (28, 14, 58) + (0.5, 3.5, 0.5)
            new AimSpot(28.5, 17.5, 58.5, "Red Stack", AimSituation.P5_RED, AimSpot.ANY_CLASS, RED),
            // no fixed position - Orange spawn (85, 14, 56) + (0.5, 3.5, 0.5)
            new AimSpot(85.5, 17.5, 56.5, "Orange Stack", AimSituation.P5_ORANGE, AimSpot.ANY_CLASS, ORANGE),
            // fixed stack position (27, 14, 90) + (0.5, 3.5, 0.5)
            new AimSpot(27.5, 17.5, 90.5, "Green Stack", AimSituation.P5_GREEN, AimSpot.ANY_CLASS, GREEN),
            // fixed stack position (84, 14, 97) + (0.5, 3.5, 0.5)
            new AimSpot(84.5, 17.5, 97.5, "Blue Stack", AimSituation.P5_BLUE, AimSpot.ANY_CLASS, BLUE),
            // no fixed position - Purple spawn (56, 14, 125) + (0.5, 3.5, 0.5)
            new AimSpot(56.5, 17.5, 125.5, "Purple Stack", AimSituation.P5_PURPLE, AimSpot.ANY_CLASS, PURPLE));

    /**
     * Devonian's "lb waypoints" ({@code M7Dragon.waypoints}, see class doc). X/Z are Devonian's exact BlockPos
     * values; Y is the top of the beam Devonian draws over them ({@code maxY = 17.0}). Toggled by
     * "Devonian LB Spots", default OFF.
     */
    public static final List<AimSpot> DEVONIAN_LB = List.of(
            // M7Dragon.POWER.waypoints = [BlockPos(27, 0, 56)]
            new AimSpot(27.0, 17.0, 56.0, "Red LB", AimSituation.P5_RED, AimSpot.ANY_CLASS, RED),
            // M7Dragon.FLAME.waypoints = [BlockPos(82, 0, 56)]
            new AimSpot(82.0, 17.0, 56.0, "Orange LB", AimSituation.P5_ORANGE, AimSpot.ANY_CLASS, ORANGE),
            // M7Dragon.APEX.waypoints = [BlockPos(27, 0, 92)]
            new AimSpot(27.0, 17.0, 92.0, "Green LB", AimSituation.P5_GREEN, AimSpot.ANY_CLASS, GREEN),
            // M7Dragon.ICE.waypoints = [BlockPos(82, 0, 96)]
            new AimSpot(82.0, 17.0, 96.0, "Blue LB", AimSituation.P5_BLUE, AimSpot.ANY_CLASS, BLUE),
            // M7Dragon.SOUL.waypoints = [BlockPos(56, 0, 124)]
            new AimSpot(56.0, 17.0, 124.0, "Purple LB", AimSituation.P5_PURPLE, AimSpot.ANY_CLASS, PURPLE));

    private AimSpots() {
    }
}
