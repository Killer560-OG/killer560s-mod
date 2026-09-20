package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Hypixel dungeon "Lower Blaze"/"Higher Blaze" puzzle solver, ported from Odin's own
 * {@code BlazeSolver.kt}. Real blazes are rendered as real ArmorStands whose name text currently reads
 * {@code [LvN] <glyph> Blaze HP/MAXHP❤} - the bit between the level tag and "Blaze" is cosmetic (a
 * private-use font glyph Hypixel has already changed once) and is matched with a wildcard, never
 * literally; Lower Blaze must be solved by killing the HIGHEST-HP blaze first, Higher
 * Blaze the LOWEST-HP first (both real, confirmed Hypixel mechanics, ported directly). Needs no bundled
 * data at all - purely reads real live entity names, so it carries none of the other solvers'
 * cross-mod corner/rotation risk. Re-scans every 5 ticks (matching Odin's own real re-scan interval) so
 * the kill order stays live as blaze HP drops and blazes die; highlights the next 3 targets in distinct
 * colors (red/orange/yellow, everything else white) with optional connecting lines showing the order.
 * Never attacks anything - only highlights.
 */
public final class BlazeSolverFeature {

    // killer560, 2026-09-20: "blaze solver is broken". Hypixel dropped a private-use mob-type glyph
    // (currently U+E07C) between the level tag and "Blaze", so the old pattern's two literal spaces
    // matched nothing at all and the solver found zero blazes. The wildcard mirrors NoammAddons'
    // 3bce7b36 "fix blaze solver regex" and, being `.*` rather than their `.+`, survives the glyph being
    // changed or dropped again too. (The old `Lv+` was a typo - "L" then one-or-more "v".)
    private static final Pattern BLAZE_NAME = Pattern.compile("^\\[Lv\\d+].*Blaze [\\d,]+/([\\d,]+)❤$");
    private static final float[][] COLORS = {
            {1.0f, 0.2f, 0.2f}, // 1st - red
            {1.0f, 0.6f, 0.1f}, // 2nd - orange
            {1.0f, 1.0f, 0.2f}, // 3rd - yellow
    };
    private static final float[] REST_COLOR = {1.0f, 1.0f, 1.0f};

    private static List<Entity> orderedBlazes = new ArrayList<>();
    private static int tickCounter = 0;
    private static RoomEntry lastRoomEntry = null;

    private BlazeSolverFeature() {
    }

    /** Kill order (index 0 = next), alive blazes only - a copy, for AutoPuzzles. Empty outside Lower/Higher Blaze. */
    public static List<Entity> getOrderedBlazes() {
        List<Entity> copy = new ArrayList<>(orderedBlazes);
        copy.removeIf(Entity::isRemoved);
        return copy;
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(BlazeSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BlazeSolverFeature::onWorldRender);
    }

    // [BlazeSolver] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");
    private static String lastLoggedState = null;
    private static final java.util.Set<String> loggedUnmatchedBlazeNames = new java.util.HashSet<>();

    private static void tick(Minecraft client) {
        tickInner(client);
        RoomEntry current = BlazeSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String roomName = current != null ? current.name : null;
        String state = !"Lower Blaze".equals(roomName) && !"Higher Blaze".equals(roomName)
                ? "notInRoom(enabled=" + BlazeSolverConfig.getInstance().isEnabled() + " inBoss=" + LiveMapFeature.isInBoss() + ")"
                : "inRoom=" + roomName + " orderedBlazes=" + orderedBlazes.size(); // count only - names carry live HP
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[BlazeSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!BlazeSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            orderedBlazes = new ArrayList<>();
            lastRoomEntry = null;
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            orderedBlazes = new ArrayList<>();
        }
        String roomName = current != null ? current.name : null;
        boolean isLower = "Lower Blaze".equals(roomName);
        boolean isHigher = "Higher Blaze".equals(roomName);
        if (!isLower && !isHigher) {
            return;
        }
        tickCounter++;
        if (tickCounter % 5 != 0) {
            return;
        }
        rescan(client, isLower);
    }

    private static void rescan(Minecraft client, boolean lowerRoom) {
        if (client.level == null) {
            return;
        }
        List<Entity> found = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }
            String name = plainName(entity);
            Matcher matcher = BLAZE_NAME.matcher(name);
            if (matcher.matches()) {
                found.add(entity);
            } else if (name.contains("Blaze") && loggedUnmatchedBlazeNames.size() < 20
                    && loggedUnmatchedBlazeNames.add(name)) {
                LOGGER.info("[BlazeSolver] ArmorStand name contains 'Blaze' but didn't match BLAZE_NAME: \"{}\"", name);
            }
        }
        Comparator<Entity> byMaxHp = Comparator.comparingLong(BlazeSolverFeature::maxHp);
        found.sort(lowerRoom ? byMaxHp.reversed() : byMaxHp);
        orderedBlazes = found;
    }

    /** Colour codes stripped before matching, the same way {@code AutoPuzzlesFeature} reads entity names -
     *  a nametag that picks up formatting must not stop matching the way the cosmetic glyph did. */
    private static String plainName(Entity entity) {
        String raw = entity.getName().getString();
        String stripped = net.minecraft.ChatFormatting.stripFormatting(raw);
        return stripped != null ? stripped : raw;
    }

    private static long maxHp(Entity entity) {
        Matcher matcher = BLAZE_NAME.matcher(plainName(entity));
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        BlazeSolverConfig cfg = BlazeSolverConfig.getInstance();
        if (!cfg.isEnabled() || orderedBlazes.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || (!"Lower Blaze".equals(current.name) && !"Higher Blaze".equals(current.name))) {
            return;
        }
        orderedBlazes.removeIf(Entity::isRemoved);
        if (orderedBlazes.isEmpty()) {
            return;
        }

        Vec3 previousCenter = null;
        for (int i = 0; i < orderedBlazes.size(); i++) {
            Entity blaze = orderedBlazes.get(i);
            float[] color = i < COLORS.length ? COLORS[i] : REST_COLOR;
            AABB box = blaze.getBoundingBox().inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0);
            SolverEspRender.renderOutlineBox(context, box, color[0], color[1], color[2], 1f, 2f);

            if (cfg.isShowLines() && previousCenter != null && i <= 3) {
                SolverEspRender.renderLineStrip(context, List.of(previousCenter, box.getCenter()),
                        color[0], color[1], color[2], 1f, 2f);
            }
            previousCenter = box.getCenter();
        }
    }
}
