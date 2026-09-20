package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive Map - port of QUOI's {@code InteractiveMap} module (plus NoammAddons/Odin map UX): keybinds, the HUD peek,
 * clicking the HUD map from chat, per-room "cleared by" tracking and the player marker list the
 * {@link InteractiveMapScreen} draws. Teleport pathing ({@code livemap.autoclear}) and Auto Blood Rush are cheat-gated.
 */
public final class InteractiveMapFeature {

    static final String CHAT = "Interactive Map";
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-interactivemap");

    private static boolean openWasDown = false;
    private static boolean startWasDown = false;
    private static boolean lockedWasDown = false;
    private static boolean bloodRushWasDown = false;
    private static boolean peeking = false;

    /** Per-room (group main tile) last map state, for cleared-by tracking. */
    private static final Map<Integer, Integer> lastRoomState = new HashMap<>();
    /** Room key -> players who were in it when it turned cleared/green. */
    private static final Map<String, List<String>> clearedBy = new HashMap<>();
    private static int seenGeneration = -1;
    private static int clearTick = 0;

    private InteractiveMapFeature() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(InteractiveMapFeature::tick);
        ClearExecutor.register();
        BloodRush.register();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !tryOpenFromHud(event.x(), event.y()));
        });
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        boolean hasWindow = client.getWindow() != null && client.player != null;
        boolean inClear = DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();

        // HUD peek: held key enlarges the HUD map.
        peeking = hasWindow && cfg.isEnabled() && cfg.getPeekKeyCode() >= 0 && client.screen == null
                && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getPeekKeyCode());

        // QUOI open key: press opens (only from no screen); Release closes on release, Repress closes on the next press.
        boolean openDown = hasWindow && cfg.getOpenKeyCode() >= 0 && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getOpenKeyCode());
        boolean mapOpen = client.screen instanceof InteractiveMapScreen;
        if (cfg.isInteractiveMapEnabled() && inClear && !isDead(client)) {
            if (openDown && !openWasDown) {
                if (mapOpen && cfg.isCloseOnRepress()) {
                    client.setScreen(null);
                } else if (client.screen == null) {
                    client.setScreen(new InteractiveMapScreen(true));
                }
            } else if (!openDown && openWasDown && !cfg.isCloseOnRepress() && mapOpen
                    && ((InteractiveMapScreen) client.screen).openedByKey()) {
                client.setScreen(null);
            }
        }
        openWasDown = openDown;
        mapOpen = client.screen instanceof InteractiveMapScreen;

        // QUOI start / locked door keys only act while the map is open.
        boolean startDown = hasWindow && cfg.getStartKeyCode() >= 0 && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getStartKeyCode());
        if (startDown && !startWasDown && mapOpen && inClear && cfg.isPathingEnabled() && !isDead(client)) {
            pathToCurrentRoomStart();
        }
        startWasDown = startDown;
        boolean lockedDown = hasWindow && cfg.getLockedDoorKeyCode() >= 0
                && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getLockedDoorKeyCode());
        if (lockedDown && !lockedWasDown && mapOpen && inClear && cfg.isPathingEnabled() && !isDead(client)) {
            pathToLockedDoor();
        }
        lockedWasDown = lockedDown;

        boolean bloodDown = hasWindow && cfg.getBloodRushKeyCode() >= 0
                && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getBloodRushKeyCode());
        if (bloodDown && !bloodRushWasDown && (client.screen == null || mapOpen) && cfg.isBloodRushEnabled()) {
            BloodRush.toggle();
        }
        bloodRushWasDown = bloodDown;

        trackClears(client);
    }

    private static boolean isDead(Minecraft client) {
        return client.player == null || client.player.isDeadOrDying()
                || PartyTracker.isDead(client.player.getGameProfile().name());
    }

    static void pathToCurrentRoomStart() {
        DungeonLayout layout = DungeonLayout.capture();
        int room = layout.currentRoom();
        if (room < 0) {
            ModChat.send(CHAT, ModChat.bad("Current room is unknown"));
            return;
        }
        AutoClearUtils.pathToRoom(layout, room, layout.tiles(room)[0], 1);
    }

    static void pathToLockedDoor() {
        DungeonLayout layout = DungeonLayout.capture();
        int door = AutoClearUtils.getLockedDoor(layout);
        if (door < 0) {
            ModChat.send(CHAT, ModChat.bad("No locked doors found."));
            return;
        }
        AutoClearUtils.pathToDoor(layout, door, LiveMapConfig.getInstance().isFaceDoorOnArrival());
    }

    static boolean isPeeking() {
        return peeking;
    }

    // ------------------------------------------------------------------------------------------- HUD click

    private static boolean tryOpenFromHud(double mouseX, double mouseY) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        if (!cfg.isOpenFromHudClick() || !cfg.isInteractiveMapEnabled() || !cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return false;
        }
        for (HudElement element : HudElementRegistry.all()) {
            if (!"live_map".equals(element.id())) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            if (mouseX >= pos[0] && mouseY >= pos[1] && mouseX < pos[0] + element.width() * scale
                    && mouseY < pos[1] + element.height() * scale) {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> client.setScreen(new InteractiveMapScreen(false)));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- cleared by

    private static void trackClears(Minecraft client) {
        if (seenGeneration != LiveMapFeature.resetGeneration()) {
            seenGeneration = LiveMapFeature.resetGeneration();
            lastRoomState.clear();
            clearedBy.clear();
        }
        if (++clearTick < 5 || !DungeonState.isInDungeon() || !DungeonMapScanner.isCalibrated()
                || !LiveMapConfig.getInstance().isInteractiveMapEnabled()) {
            return;
        }
        clearTick = 0;
        List<MapPlayer> players = null;
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            int state = DungeonMapScanner.stateAt(group.mainIdx);
            Integer before = lastRoomState.put(group.mainIdx, state);
            if (before == null || before == state) {
                continue;
            }
            boolean wasOpen = before == DungeonMapScanner.STATE_UNDISCOVERED || before == DungeonMapScanner.STATE_DISCOVERED
                    || before == DungeonMapScanner.STATE_UNOPENED;
            boolean nowDone = state == DungeonMapScanner.STATE_CLEARED || state == DungeonMapScanner.STATE_GREEN;
            if (!wasOpen || !nowDone) {
                continue;
            }
            if (players == null) {
                players = playersCached(client);
            }
            Set<Integer> cells = new HashSet<>();
            for (int c : group.cells) {
                cells.add(c);
            }
            List<String> inside = new ArrayList<>();
            for (MapPlayer p : players) {
                int[] cell = LiveMapFeature.gridCellFor(new net.minecraft.world.phys.Vec3(p.worldX(), 70, p.worldZ()));
                if (cells.contains(cell[0] + cell[1] * LiveMapFeature.GRID)) {
                    inside.add(p.name());
                }
            }
            if (!inside.isEmpty()) {
                clearedBy.put(roomKey(group), inside);
                LOGGER.info("[InteractiveMap] {} cleared by {}", roomKey(group), inside);
            }
        }
    }

    static String roomKey(LiveMapFeature.RoomGroup group) {
        return group.entry != null && group.entry.name != null ? group.entry.name : "cell:" + group.mainIdx;
    }

    static List<String> clearedBy(LiveMapFeature.RoomGroup group) {
        return clearedBy.get(roomKey(group));
    }

    // ------------------------------------------------------------------------------------------- players

    /** One marker on the map: world position, heading, and how to draw it. No skin field any more - killer560,
     *  2026-09-20: "i do not want it showing the white heads for mobs", so every marker is always the arrow. */
    record MapPlayer(String name, double worldX, double worldZ, float yaw, boolean self, DungeonClass dungeonClass) {
    }

    private static List<MapPlayer> cachedPlayers = List.of();
    private static int cachedPlayersTick = Integer.MIN_VALUE;

    /** Per-tick cached {@link #players}, for anything that runs per frame. {@link #players} scans the whole party
     *  ({@code level.players()} plus a {@code getName().getString()} per member) and reads the map item's
     *  decorations; the fps report (2026-09-20) caught the HUD map doing that on every frame. Entity positions only
     *  move on a tick, so a per-tick snapshot is the same picture. */
    static List<MapPlayer> playersCached(Minecraft client) {
        int tick = LiveMapFeature.tickCount();
        if (tick != cachedPlayersTick) {
            cachedPlayersTick = tick;
            cachedPlayers = players(client);
        }
        return cachedPlayers;
    }

    /** Self first, then teammates: loaded player entities are exact; the rest come from the map item's markers in
     *  Hypixel's order (QUOI/NoammAddons assign non-self decorations to living teammates in tab order). */
    static List<MapPlayer> players(Minecraft client) {
        List<MapPlayer> out = new ArrayList<>();
        if (client.player == null || client.level == null) {
            return out;
        }
        String selfName = client.player.getGameProfile().name();
        out.add(new MapPlayer(selfName, client.player.getX(), client.player.getZ(), client.player.getYRot(), true,
                PartyTracker.selfClass()));

        Map<String, Player> entities = new HashMap<>();
        for (Player p : LeapMenuFeature.currentPartyMembers()) {
            entities.put(p.getGameProfile().name().toLowerCase(Locale.US), p);
        }
        Set<String> names = new LinkedHashSet<>(PartyTracker.teammates());
        if (names.isEmpty()) {
            for (Player p : entities.values()) {
                names.add(p.getGameProfile().name());
            }
        }
        List<double[]> markers = new ArrayList<>();
        for (double[] m : DungeonMapScanner.playerMarkers(client)) {
            if (m[3] == 0) {
                markers.add(m);
            }
        }
        int markerIdx = 0;
        for (String name : names) {
            if (PartyTracker.isDead(name)) {
                continue;
            }
            double[] marker = markerIdx < markers.size() ? markers.get(markerIdx) : null;
            markerIdx++;
            Player entity = entities.get(name.toLowerCase(Locale.US));
            DungeonClass cls = PartyTracker.classOf(name);
            if (entity != null) {
                out.add(new MapPlayer(name, entity.getX(), entity.getZ(), entity.getYRot(), false, cls));
            } else if (marker != null) {
                out.add(new MapPlayer(name, marker[0], marker[1], (float) marker[2], false, cls));
            }
        }
        return out;
    }
}
