package com.killer560.hub.p3nav;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * P3 Nav - F7/M7 Phase 3 (Goldor) navigation aids ported from NoammAddons, both default OFF
 * ({@link P3NavConfig}).
 * <ul>
 * <li><b>Gate Highlight</b> - NoammAddons {@code features/impl/floor7/GateHighlight.kt}. The three P3 section
 * gates as a probe {@link BlockPos} + an {@link AABB}, drawn only while the gate is still standing. "Still
 * standing" is exactly the source's own test (its lines 30-32): the block at the probe position is still
 * {@code CRACKED_STONE_BRICKS} or {@code INFESTED_STONE_BRICKS} - once the gate is broken that block turns to
 * air and the box stops drawing by itself, with no chat line and no timer involved.</li>
 * <li><b>Terminal / Device ESP</b> - NoammAddons {@code features/impl/floor7/TerminalESP.kt}. Highlights the
 * terminals of the section you are in, tagged by section from that source's own coordinate table (its lines
 * 34-39). This port deliberately does NOT copy the source's packet-listener bookkeeping
 * ({@code ClientboundSetEntityDataPacket} into a cached {@code MutableMap<ArmorStand, HitboxInfo>}): the same
 * information is already on the armour stand itself every frame, so completion tracking here is just "is this
 * stand still named <i>Inactive Terminal</i>". Hypixel renames the stand to "Terminal Active" the instant the
 * terminal is finished, so a completed terminal stops being drawn on the very next frame, self-correcting after
 * a lag spike or a mid-fight rejoin, with no second chat listener (this mod's existing terminal completion
 * state - {@code terminals/TerminalSolverFeature} and {@code splittimers/DeviceTimesFeature} - is per-section
 * chat-count based and never per-terminal, so there is nothing there to reuse for "which box to stop
 * drawing").</li>
 * </ul>
 * Devices and levers are the half NoammAddons' TerminalESP does not cover; they are handled by the same scan,
 * off the "Not Activated" hologram Hypixel puts over every un-flicked lever/device (the same stand
 * {@code leveraura/LeverAuraFeature} already keys its S2 section-lever logic off).
 * <p>
 * No detection of its own: the floor comes from {@code DungeonState}, the boss phase and the P3 section from
 * {@link Floor7Tracker} (chat-driven with a position fallback), so this works on p3sim.net wherever those
 * already do. Through Walls is cheat-build only ({@link P3NavConfig#isTerminalThroughWalls()}).
 */
public final class P3NavFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-p3nav");

    /** NoammAddons {@code GateHighlight.kt} lines 22-26, coordinates verbatim. */
    private record Gate(BlockPos probe, AABB box) {
    }

    private static final Map<Integer, Gate> GATES = Map.of(
            1, new Gate(new BlockPos(103, 134, 123), new AABB(95, 114, 122, 106, 134, 124)),
            2, new Gate(new BlockPos(17, 134, 135), new AABB(18, 114, 127, 19, 134, 138)),
            3, new Gate(new BlockPos(5, 134, 49), new AABB(14, 114, 51, 1, 134, 49)));

    /**
     * NoammAddons {@code TerminalESP.kt} lines 34-39, coordinates verbatim - index 0 is section 1. The source
     * matches a stand to a section with {@code entity.distanceToSqr(it) <= 1.5}; same radius here.
     */
    private static final List<List<Vec3>> TERMINALS = List.of(
            List.of(new Vec3(110, 113, 73), new Vec3(110, 119, 79), new Vec3(90, 112, 92), new Vec3(90, 122, 101)),
            List.of(new Vec3(68, 109, 122), new Vec3(59, 119, 123), new Vec3(47, 109, 122), new Vec3(39, 108, 142),
                    new Vec3(40, 124, 123)),
            List.of(new Vec3(-2, 109, 112), new Vec3(-2, 119, 93), new Vec3(18, 123, 93), new Vec3(-2, 109, 77)),
            List.of(new Vec3(41, 109, 30), new Vec3(44, 121, 30), new Vec3(67, 109, 30), new Vec3(72, 114, 47)));

    private static final double TERMINAL_MATCH_DIST_SQ = 1.5;

    /**
     * Section bounds, copied from {@link Floor7Tracker}'s own {@code getStageAt()} x/z ranges (which only ever
     * test the local player) with the P3 y band from its {@code getPhaseAt()} (100 &lt; y &lt;= 155), so an
     * arbitrary armour stand can be placed in a section too. Tested in order, S1 first, same as the source of
     * the ranges - S1/S2 overlap slightly around x 89..111, z 121..122.
     */
    private static final AABB[] SECTION_BOXES = {
            new AABB(89, 100, 30, 113, 155, 122),
            new AABB(19, 100, 121, 111, 155, 145),
            new AABB(-6, 100, 51, 19, 155, 143),
            new AABB(-2, 100, 27, 90, 155, 51)};

    /** Hypixel's hologram over an unfinished terminal; becomes "Terminal Active" on completion. */
    private static final String INACTIVE_TERMINAL = "Inactive Terminal";
    /** Hypixel's hologram over an un-flicked lever/device (see {@code leveraura/LeverAuraFeature}). */
    private static final String NOT_ACTIVATED = "Not Activated";

    /** A marker armour stand has a zero-size bounding box; fall back to a normal stand's size around it. */
    private static final double MARKER_HALF_WIDTH = 0.3;
    private static final double MARKER_HEIGHT = 1.975;

    private P3NavFeature() {
    }

    public static void register() {
        P3NavConfig.getInstance();
        P3NavRenderer.init();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(P3NavFeature::render);
        LOGGER.info("[P3Nav] Registered (Gate Highlight and Terminal/Device ESP both default OFF)");
    }

    private static void render(LevelRenderContext context) {
        if (!SkyblockGate.allows()) {
            return;
        }
        P3NavConfig cfg = P3NavConfig.getInstance();
        boolean wantGate = cfg.isGateHighlightEnabled();
        boolean wantTerminals = cfg.isTerminalEspEnabled();
        boolean wantDevices = cfg.isDeviceEspEnabled();
        if (!wantGate && !wantTerminals && !wantDevices) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !inP3()) {
            return;
        }
        int section = playerSection();
        if (wantGate) {
            renderGate(context, client, cfg, section);
        }
        if (wantTerminals || wantDevices) {
            renderStands(context, client, cfg, section, wantTerminals, wantDevices);
        }
    }

    /**
     * NoammAddons gates its render on {@code LocationUtils.F7Phase != 3} (a chat-driven phase). This mod's
     * equivalent is chat-driven too, with {@code getPhaseAt()} as the p3sim.net-friendly position fallback for
     * a run where Goldor's opening line never arrived.
     */
    private static boolean inP3() {
        return Floor7Tracker.getPhase() == Floor7Tracker.Phase.P3
                || Floor7Tracker.getPhaseAt() == Floor7Tracker.Phase.P3;
    }

    /** NoammAddons {@code LocationUtils.P3Section}: 1-4, or 0 when it can't be resolved. */
    private static int playerSection() {
        Floor7Tracker.Stage at = Floor7Tracker.getStageAt();
        if (at.number >= 1 && at.number <= 4) {
            return at.number;
        }
        Floor7Tracker.Stage tracked = Floor7Tracker.getStage();
        return tracked.number >= 1 && tracked.number <= 4 ? tracked.number : 0;
    }

    private static void renderGate(LevelRenderContext context, Minecraft client, P3NavConfig cfg, int section) {
        Gate gate = GATES.get(section);
        if (gate == null) {
            // Section 4 has no gate (the Core entrance opens instead), and neither does an unresolved section.
            return;
        }
        if (cfg.getGateHideDestroyedRaw() && !gateStanding(client, gate)) {
            return;
        }
        P3NavRenderer.draw(context, gate.box(), cfg.getGateColor(), cfg.getGateStyle(), cfg.getGateLineWidth(), false);
    }

    /** GateHighlight.kt line 32: the gate is up while its probe block is still cracked/infested stone brick. */
    private static boolean gateStanding(Minecraft client, Gate gate) {
        var state = client.level.getBlockState(gate.probe());
        return state.is(Blocks.CRACKED_STONE_BRICKS) || state.is(Blocks.INFESTED_STONE_BRICKS);
    }

    private static void renderStands(LevelRenderContext context, Minecraft client, P3NavConfig cfg, int section,
                                     boolean wantTerminals, boolean wantDevices) {
        boolean onlyCurrent = cfg.getTerminalCurrentSectionOnlyRaw();
        if (onlyCurrent && section == 0) {
            return;
        }
        boolean throughWalls = cfg.isTerminalThroughWalls();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand)) {
                continue;
            }
            Component custom = stand.getCustomName();
            if (custom == null) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(custom.getString());
            if (name == null) {
                continue;
            }
            boolean terminal = INACTIVE_TERMINAL.equals(name);
            boolean device = !terminal && NOT_ACTIVATED.equals(name);
            if (terminal ? !wantTerminals : (!device || !wantDevices)) {
                continue;
            }
            int standSection = terminal ? terminalSection(stand) : sectionAt(stand.position());
            if (standSection == 0 || (onlyCurrent && standSection != section)) {
                continue;
            }
            int color = terminal ? cfg.getTerminalColor() : cfg.getDeviceColor();
            P3NavRenderer.draw(context, boxOf(stand), color, cfg.getTerminalStyle(), cfg.getTerminalLineWidth(),
                    throughWalls);
        }
    }

    /** TerminalESP.kt lines 52-57: a stand within 1.5 (squared) of a listed position belongs to that section. */
    private static int terminalSection(ArmorStand stand) {
        for (int i = 0; i < TERMINALS.size(); i++) {
            for (Vec3 pos : TERMINALS.get(i)) {
                if (stand.distanceToSqr(pos) <= TERMINAL_MATCH_DIST_SQ) {
                    return i + 1;
                }
            }
        }
        // Not in the table (a Hypixel layout tweak, or p3sim.net drifting a stand): fall back to the section box
        // so the terminal is still highlighted rather than silently dropped.
        return sectionAt(stand.position());
    }

    private static int sectionAt(Vec3 pos) {
        for (int i = 0; i < SECTION_BOXES.length; i++) {
            if (SECTION_BOXES[i].contains(pos)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** NoammAddons' {@code Entity.renderBoundingBox}; these stands never move, so no interpolation is needed. */
    private static AABB boxOf(ArmorStand stand) {
        AABB box = stand.getBoundingBox();
        if (box.getXsize() > 0.05 && box.getYsize() > 0.05) {
            return box;
        }
        Vec3 pos = stand.position();
        return new AABB(pos.x - MARKER_HALF_WIDTH, pos.y, pos.z - MARKER_HALF_WIDTH,
                pos.x + MARKER_HALF_WIDTH, pos.y + MARKER_HEIGHT, pos.z + MARKER_HALF_WIDTH);
    }
}
