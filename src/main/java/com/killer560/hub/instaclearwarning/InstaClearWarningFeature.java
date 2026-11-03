package com.killer560.hub.instaclearwarning;

import com.killer560.hub.autoclear.AutoClearTargets;
import com.killer560.hub.autoclear.KeyRooms;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.creationcooldown.CreationCooldownFeature;
import com.killer560.hub.doorkeys.DungeonKeys;
import com.killer560.hub.dungeonqueue.DungeonQueueConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Insta Clear Warning (to-do Doc 2026-11-01): a room on the way to blood rush - the room in front of a shut wither door
 * (the fairy room's door is a wither door), or of the blood door - that the map shows cleared while nobody got its key
 * and no key lies on the ground. Hypixel drops a room's key at its last starred death, so a room that clears without
 * one (an insta clear) has lost its key and the run cannot reach blood.
 *
 * <p><b>Which rooms.</b> {@link KeyRooms#guards}: for every LOCKED wither door and the locked blood door on the map, the
 * room on its Entrance side, while it is not cleared and the team's keys in hand do not already cover every shut door.
 * Each is watched from the moment it is one.
 *
 * <p><b>When it warns.</b> A watched room that turns cleared on the map with its door still locked gets {@link #GRACE_MS}
 * for a key to show: any key of its kind seen on the ground, the team's key count rising (the sidebar's Keys line, or the
 * "has obtained" chat lines - {@link DungeonKeys}), or the door opening ends the watch quietly. Otherwise it warns, but
 * only while he is within {@link #NEAR_BLOCKS} of the room: farther away the client may simply not have the key's
 * armor stand, so it waits (up to {@link #GIVE_UP_MS}) for him to come close rather than guess.
 *
 * <p><b>What it does</b> (each its own setting): HUD Popup (a movable HUD line and a sound for {@link #POPUP_MS}), Party
 * Message (your line in party chat), Requeue Same Floor ({@code /joininstance} of the floor you are on, once the
 * dungeon creation cooldown is over and Auto Requeue's Requeue Delay has passed). Once per room and one requeue per run.
 * Reads the map, the sidebar, chat and the entities the client has; sends only the party line and the requeue.
 */
public final class InstaClearWarningFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-instaclearwarning");
    private static final String FEATURE = "Insta Clear Warning";

    static final long GRACE_MS = 3_000L;
    static final long GIVE_UP_MS = 60_000L;
    static final long POPUP_MS = 6_000L;
    static final double NEAR_BLOCKS = 48.0;
    private static final int POLL_TICKS = 5;

    /** What the watch needs to know about the run, from the map (or a test's fake). */
    interface Snapshot {
        /** Every room still holding the key to a shut door: name, door cell, blood. */
        List<Guard> guards();

        boolean cleared(String room);

        boolean doorLocked(int door);

        int witherKeys();

        /** 1 held, 0 not, -1 unknown. */
        int bloodKey();

        boolean keyOnGround(boolean blood);

        boolean near(String room);
    }

    record Guard(String room, int door, boolean blood) {
    }

    private enum State { WATCHING, CLEARED, DONE }

    private static final class Watch {
        final String room;
        final int door;
        final boolean blood;
        final int witherAtStart;
        final int bloodAtStart;
        State state = State.WATCHING;
        long clearedAt;
        boolean keySeen;

        Watch(Guard g, int wither, int blood) {
            room = g.room();
            door = g.door();
            this.blood = g.blood();
            witherAtStart = wither;
            bloodAtStart = blood;
        }
    }

    private static final Map<String, Watch> WATCHES = new LinkedHashMap<>();
    private static Object lastLevel;
    private static int lastGeneration = Integer.MIN_VALUE;
    private static int poll = 0;

    private static long popupUntil = 0;
    private static String popupRoom = "";
    private static boolean requeuedThisRun = false;
    private static String pendingFloor = null;
    private static long pendingSince = 0;

    // ---- testkit ----
    private static Snapshot testSnapshot = null;
    private static int warnings = 0;
    private static String lastWarnedRoom = null;
    private static String lastDrawn = null;
    private static String lastRequeue = null;
    private static final List<String> quietEnds = new ArrayList<>();

    private InstaClearWarningFeature() {
    }

    public static void register() {
        InstaClearWarningConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InstaClearWarningFeature", InstaClearWarningFeature::tick));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "insta_clear_warning"),
                (graphics, deltaTracker) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client)
                            || !HUD.isEnabledInSettings()) {
                        return;
                    }
                    com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HUD);
                });
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel || LiveMapFeature.resetGeneration() != lastGeneration) {
            lastLevel = client.level;
            lastGeneration = LiveMapFeature.resetGeneration();
            resetRun();
        }
        InstaClearWarningConfig cfg = InstaClearWarningConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null) {
            pendingFloor = null;
            return;
        }
        tickRequeue(cfg);
        boolean inClear = testSnapshot != null || (DungeonState.isInDungeon() && !LiveMapFeature.isInBoss());
        if (!inClear) {
            return;
        }
        if (++poll < POLL_TICKS) {
            return;
        }
        poll = 0;
        step(testSnapshot != null ? testSnapshot : new MapSnapshot(client), System.currentTimeMillis());
    }

    static void resetRun() {
        WATCHES.clear();
        requeuedThisRun = false;
        pendingFloor = null;
        popupUntil = 0;
    }

    /** One look at the run. Package-visible for the test hooks. */
    static void step(Snapshot s, long now) {
        List<Guard> guards = s.guards();
        Set<String> live = new HashSet<>();
        for (Guard g : guards) {
            live.add(g.room());
            Watch w = WATCHES.get(g.room());
            if (w == null || w.state == State.DONE && !s.cleared(g.room())) {
                WATCHES.put(g.room(), new Watch(g, s.witherKeys(), s.bloodKey()));
            }
        }
        for (Watch w : WATCHES.values()) {
            if (w.state == State.WATCHING && !live.contains(w.room)) {
                if (s.cleared(w.room) && s.doorLocked(w.door)) {
                    w.state = State.CLEARED;
                    w.clearedAt = now;
                    LOGGER.info("[InstaClearWarning] {} (key room for the {} door {}) cleared on the map - waiting for its key",
                            w.room, w.blood ? "blood" : "wither", w.door);
                } else {
                    end(w, "no longer a key room (door opened or keys in hand)");
                    continue;
                }
            }
            if (w.state != State.CLEARED) {
                continue;
            }
            if (s.keyOnGround(w.blood)) {
                w.keySeen = true;
            }
            boolean gotKey = w.blood ? s.bloodKey() == 1 && w.bloodAtStart != 1 : s.witherKeys() > w.witherAtStart;
            if (w.keySeen || gotKey || !s.doorLocked(w.door)) {
                end(w, w.keySeen ? "its key is on the ground" : gotKey ? "its key was picked up" : "its door opened");
                continue;
            }
            long since = now - w.clearedAt;
            if (since < GRACE_MS) {
                continue;
            }
            if (!s.near(w.room)) {
                if (since > GIVE_UP_MS) {
                    end(w, "never close enough to see whether its key dropped");
                }
                continue;
            }
            w.state = State.DONE;
            warn(w.room, w.blood);
        }
    }

    private static void end(Watch w, String why) {
        w.state = State.DONE;
        quietEnds.add(w.room + ": " + why);
        LOGGER.info("[InstaClearWarning] {}: no warning - {}", w.room, why);
    }

    private static void warn(String room, boolean blood) {
        InstaClearWarningConfig cfg = InstaClearWarningConfig.getInstance();
        warnings++;
        lastWarnedRoom = room;
        LOGGER.info("[InstaClearWarning] {} was insta cleared: the {} key is lost", room, blood ? "blood" : "wither");
        if (cfg.isHudPopup()) {
            popupUntil = System.currentTimeMillis() + POPUP_MS;
            popupRoom = room;
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> client.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.5f, 1.0f)));
        }
        if (cfg.isPartyMessage()) {
            String line = chatSafe(cfg.getMessage());
            if (!line.isEmpty()) {
                com.killer560.hub.util.OutgoingChat.command("pc " + line);
            }
        }
        if (cfg.isRequeue() && !requeuedThisRun) {
            String floor = DungeonState.getFloor();
            String id = instanceFor(floor);
            if (id == null) {
                ModChat.send(FEATURE, ModChat.bad("Cannot requeue: unknown floor " + floor));
            } else {
                requeuedThisRun = true;
                pendingFloor = id;
                pendingSince = System.currentTimeMillis();
                ModChat.send(FEATURE, ModChat.text("Requeueing "), ModChat.value(floor),
                        ModChat.text(" when the creation cooldown is over."));
            }
        }
    }

    private static void tickRequeue(InstaClearWarningConfig cfg) {
        if (pendingFloor == null) {
            return;
        }
        if (!cfg.isRequeue() || !DungeonState.isInDungeon() && testSnapshot == null) {
            pendingFloor = null;
            return;
        }
        long delayMs = DungeonQueueConfig.getInstance().getDelaySeconds() * 1000L;
        if (CreationCooldownFeature.remainingMs() > 0 || System.currentTimeMillis() - pendingSince < delayMs) {
            return;
        }
        String cmd = "joininstance " + pendingFloor;
        pendingFloor = null;
        lastRequeue = cmd;
        LOGGER.info("[InstaClearWarning] requeue: /{}", cmd);
        ServerCommands.toServer(cmd);
    }

    /** "F7" -> catacombs_floor_seven, "M3" -> master_catacombs_floor_three, "E" -> the entrance; null when unknown. */
    static String instanceFor(String floor) {
        if (floor == null || floor.isEmpty()) {
            return null;
        }
        String literal = floor.equalsIgnoreCase("E") ? "f0" : floor.toLowerCase(Locale.ROOT);
        for (CommandShortcutsFeature.Shortcut s : CommandShortcutsFeature.Shortcut.values()) {
            if (s.literal.equals(literal) && !s.isKuudra()) {
                return s.instanceId;
            }
        }
        return null;
    }

    /** What may go in a chat packet: no section signs or control characters (a vanilla server kicks for those), trimmed. */
    static String chatSafe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // a color code is the sign AND its letter: "§cText" is "Text", not "cText"
                continue;
            }
            if (c >= ' ' && c != 127) {
                b.append(c);
            }
        }
        String out = b.toString().trim();
        return out.length() > InstaClearWarningConfig.MAX_MESSAGE ? out.substring(0, InstaClearWarningConfig.MAX_MESSAGE) : out;
    }

    /** The map's view of the run. */
    private static final class MapSnapshot implements Snapshot {
        private final Minecraft client;
        private final DungeonLayout layout;

        MapSnapshot(Minecraft client) {
            this.client = client;
            this.layout = DungeonLayout.current();
        }

        @Override
        public List<Guard> guards() {
            List<Guard> out = new ArrayList<>();
            for (KeyRooms.Guard g : KeyRooms.guards(layout)) {
                String name = layout.name(g.room());
                if (name != null && !"Unknown".equals(name)) {
                    out.add(new Guard(name, g.door(), g.blood()));
                }
            }
            return out;
        }

        private int room(String name) {
            for (int r = 0; r < layout.roomCount(); r++) {
                if (name.equals(layout.name(r))) {
                    return r;
                }
            }
            return -1;
        }

        @Override
        public boolean cleared(String name) {
            int r = room(name);
            return r >= 0 && AutoClearTargets.isClearedOnMap(layout, r);
        }

        @Override
        public boolean doorLocked(int door) {
            return layout.isLocked(door);
        }

        @Override
        public int witherKeys() {
            return DungeonKeys.witherKeys();
        }

        @Override
        public int bloodKey() {
            return DungeonKeys.bloodKey();
        }

        @Override
        public boolean keyOnGround(boolean blood) {
            for (DungeonKeys.Dropped d : DungeonKeys.dropped(client)) {
                if (d.blood() == blood) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean near(String name) {
            int r = room(name);
            if (r < 0 || client.player == null) {
                return false;
            }
            double px = client.player.getX();
            double pz = client.player.getZ();
            for (int t : layout.tiles(r)) {
                BlockPos c = DungeonLayout.cellCenter(t);
                double dx = c.getX() + 0.5 - px;
                double dz = c.getZ() + 0.5 - pz;
                if (dx * dx + dz * dz <= NEAR_BLOCKS * NEAR_BLOCKS) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final HudElement HUD = new HudElement() {
        private static final String TITLE = "Blood rush room was insta cleared!";

        @Override
        public String id() {
            return "insta_clear_warning";
        }

        @Override
        public String displayName() {
            return "Insta Clear Warning";
        }

        @Override
        public int defaultX() {
            return 200;
        }

        @Override
        public int defaultY() {
            return 60;
        }

        @Override
        public int width() {
            return 2 + Minecraft.getInstance().font.width(TITLE);
        }

        @Override
        public int height() {
            return 2 + 2 * com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            InstaClearWarningConfig cfg = InstaClearWarningConfig.getInstance();
            return cfg.isEnabledRaw() && cfg.isHudPopup();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
            if (!example && (System.currentTimeMillis() >= popupUntil || !SkyblockGate.allows())) {
                return;
            }
            String room = example ? "Example Room" : popupRoom;
            if (!example) {
                lastDrawn = room;
                HudSeen.markDrawn(id());
            }
            var font = Minecraft.getInstance().font;
            graphics.text(font, "§c§l" + TITLE, x + 1, y + 1, 0xFFFF5555, true);
            graphics.text(font, "§7" + room + " - its key is gone, restart the run", x + 1, y + 1
                    + com.killer560.hub.hud.HudText.ROW, 0xFFAAAAAA, true);
        }
    };

    // ---- test hooks (testkit, reflection) ----

    /** A fake run the testkit drives: guards as "room|door|blood" lines; everything else set through the fields below. */
    public static void testUseFake(boolean on) {
        testSnapshot = on ? FAKE : null;
        FAKE.guards.clear();
        FAKE.cleared.clear();
        FAKE.lockedOpen.clear();
        FAKE.wither = 0;
        FAKE.blood = 0;
        FAKE.keyOnGround = false;
        FAKE.near = true;
        resetRun();
        quietEnds.clear();
    }

    public static void testGuard(String room, int door, boolean blood) {
        FAKE.guards.put(room, new Guard(room, door, blood));
    }

    /** The map flips {@code room} to cleared: it stops being a key room. */
    public static void testClear(String room) {
        FAKE.guards.remove(room);
        FAKE.cleared.add(room);
    }

    public static void testOpenDoor(int door) {
        FAKE.lockedOpen.add(door);
    }

    public static void testKeys(int wither, int blood, boolean onGround, boolean near) {
        FAKE.wither = wither;
        FAKE.blood = blood;
        FAKE.keyOnGround = onGround;
        FAKE.near = near;
    }

    public static int warnings() {
        return warnings;
    }

    public static String lastWarnedRoom() {
        return lastWarnedRoom;
    }

    public static String lastDrawn() {
        return lastDrawn;
    }

    public static String lastRequeue() {
        return lastRequeue;
    }

    public static boolean requeuePending() {
        return pendingFloor != null;
    }

    public static List<String> quietEnds() {
        return new ArrayList<>(quietEnds);
    }

    private static final FakeSnapshot FAKE = new FakeSnapshot();

    private static final class FakeSnapshot implements Snapshot {
        final Map<String, Guard> guards = new LinkedHashMap<>();
        final Set<String> cleared = new HashSet<>();
        final Set<Integer> lockedOpen = new HashSet<>();
        int wither;
        int blood;
        boolean keyOnGround;
        boolean near = true;

        @Override
        public List<Guard> guards() {
            return new ArrayList<>(guards.values());
        }

        @Override
        public boolean cleared(String room) {
            return cleared.contains(room);
        }

        @Override
        public boolean doorLocked(int door) {
            return !lockedOpen.contains(door);
        }

        @Override
        public int witherKeys() {
            return wither;
        }

        @Override
        public int bloodKey() {
            return blood;
        }

        @Override
        public boolean keyOnGround(boolean b) {
            return keyOnGround;
        }

        @Override
        public boolean near(String room) {
            return near;
        }
    }
}
