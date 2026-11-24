package com.killer560.hub.bloodcamp;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonRun;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blood Camp Stats (killer560, 2026-11-01): mobs left, the mob ticks lost to slow kills, the server lag during the camp,
 * a HUD for them and an end-of-run party message.
 * <p>
 * <b>Mob ticks</b>, his rules word for word: "start counting from the second kill goes off to the second the first mob is
 * killed, since for blood camp you have to kill just 1 mob to make it start moving but you have to wait till after that
 * kill message so i dont want it counting to kill all the 4 mobs there just the first one. Then from there on out it
 * should only count every tick a blood mob is alive. If a mob is dead or a mob is about to spawn it shouldnt count." So:
 * Changed 2026-11-04 (killer560: "it will time mobs alive from the tick the kill timer goes off. it should wait till
 * the mob after that spawns to count any alive mobs"):
 * <ol>
 *   <li>Nothing counts until the kill timer goes off (the Kill Popup's KILL, {@link BloodCampMoveTimer#killReached}).</li>
 *   <li>Nothing counts after it either until the next mob spawns: a trip after the last-wave line whose mob lands
 *   after the kill timer ({@link #onTripMobLanded}). The mobs already on the floor do not start the count.</li>
 *   <li>From that spawn a tick counts only while at least one blood mob is alive on the floor (tagged, landed, not dead);
 *   a mob still on its way from the wall is "about to spawn" and does not count.</li>
 *   <li>It stops on "You have proven yourself. You may pass." (the room is cleared).</li>
 * </ol>
 * Ticks are SERVER ticks ({@code util.PerTickServerClock}: one per server ping on Hypixel, client ticks elsewhere), so a server
 * stall adds nothing here: it is reported apart, as the camp's lag (the ticks {@code util.PerTickServerClock} saw the server miss since the Watcher's greeting).
 * <p>
 * It is all read from what the client sees - the Watcher's lines and the blood mobs' own entities - so it works from any
 * room the blood mobs are loaded in, whoever camps. Party Message sends it the moment the camp clears ("You have proven yourself") when he camped himself (was in
 * the Blood Room after the kill timer); Send For Any Class sends it whoever camped.
 */
public final class BloodCampStats {

    /** WAITING_SPAWN: the kill timer went off, no mob has spawned since; ALIVE: counting ticks with a mob alive. */
    private enum Phase { IDLE, WAITING_KILL, WAITING_SPAWN, ALIVE, DONE }

    private static Phase phase = Phase.IDLE;
    private static long ticksLost;
    /** Counting began (a trip's mob landed in view). */
    private static boolean counted;
    /** Server ticks of the camp with the Watcher out of the client world and no blood mob loaded: nothing could be seen. */
    private static long farTicks;
    /** The part of farTicks while counting: the ticks lost may be higher than shown by about this much. */
    private static long blindTicks;
    private static long firstKillTicks = -1L;
    private static long campLagMs;
    private static boolean camped;
    private static boolean sentThisRun;
    /** A same-run rejoin keeps the camp's stats (secrets.DungeonRun). */
    private static final DungeonRun.Tracker RUN = new DungeonRun.Tracker();
    private static long lastClock = com.killer560.hub.util.PerTickServerClock.now();
    /** Testkit: the last party message sent (or that would have been, with Party Message on). */
    private static String lastMessage;

    private BloodCampStats() {
    }

    static void register() {
        com.killer560.hub.util.PerTickServerClock.register();
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) {
                noteAction(Minecraft.getInstance());
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        // One step per server tick of util.PerTickServerClock (not ServerTickClock, which an anticheat's ~15 pings a
        // second already make "ping-driven"), stepped at the end of each client tick.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = com.killer560.hub.util.PerTickServerClock.now();
            long steps = Math.max(0L, Math.min(200L, now - lastClock));
            lastClock = now;
            if (client.player != null && client.options.keyAttack.isDown()) {
                noteAction(client);
            }
            for (long i = 0; i < steps; i++) {
                onServerTick();
            }
            if (phase == Phase.WAITING_KILL || phase == Phase.WAITING_SPAWN || phase == Phase.ALIVE) {
                WatcherBar.tick(client);
            }
        });
    }

    static void onGreeting() {
        reset("greeting");
        phase = Phase.WAITING_KILL;
    }

    private static void reset(String why) {
        if (phase != Phase.IDLE) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] reset ({}): phase={} ticksLost={}", why, phase, ticksLost);
        }
        phase = Phase.IDLE;
        ticksLost = 0L;
        counted = false;
        farTicks = 0L;
        blindTicks = 0L;
        firstKillTicks = -1L;
        campLagMs = 0L;
        camped = false;
        OWN_KILLS.clear();
        sentThisRun = false;
        WatcherBar.reset();
    }

    /** Every server tick. */
    private static void onServerTick() {
        Minecraft client = Minecraft.getInstance();
        if (RUN.newRun(client.level)) {
            reset("new run");
            return;
        }
        if (client.level == null || phase == Phase.IDLE || phase == Phase.DONE) {
            return;
        }
        if (phase == Phase.WAITING_KILL) {
            if (!BloodCampMoveTimer.killReached()) {
                return;
            }
            phase = Phase.WAITING_SPAWN;
            BloodCampFeature.LOGGER.info("[BloodCampStats] kill timer went off: waiting for the next mob to spawn ({} alive"
                    + " now, not counted)", BloodCampFeature.campCounts(client)[0]);
        }
        int[] counts = BloodCampFeature.campCounts(client);
        if (phase == Phase.ALIVE && counts[0] > 0) {
            ticksLost++;
        } else if (counts[0] == 0 && counts[1] == 0 && !BloodCampFeature.watcherInView() && WatcherBar.mobsLeft(client) != 0) {
            // Out of entity range (far away, other room): the mobs and the Watcher are not in the client world, so a tick
            // with a mob alive cannot be told from one without. Never counted as zero (killer560, 2026-11-09).
            farTicks++;
            blindTicks += phase == Phase.ALIVE ? 1 : 0;
        }
        campLagMs = BloodCampMoveTimer.lagMsSinceGreeting();
    }

    /** A tagged blood mob died (seen dead, or removed by the server while dead / close to him). */
    static void onBloodMobDied(int id) {
        noteOwnKill(id);
        if (phase == Phase.ALIVE && firstKillTicks < 0) {
            firstKillTicks = ticksLost;
            BloodCampFeature.LOGGER.info("[BloodCampStats] first blood mob #{} died {} counted ticks after the first spawn"
                    + " since the kill timer", id, ticksLost);
        }
    }

    /** A tracked trip's mob landed (the trip took it). The first one after the last-wave line and after the kill timer
     *  is "the mob after that spawns": the count starts there. */
    static void onTripMobLanded(int mobId, boolean firstSpawnTrip) {
        if (phase != Phase.WAITING_SPAWN || firstSpawnTrip) {
            return;
        }
        phase = Phase.ALIVE;
        counted = true;
        BloodCampFeature.LOGGER.info("[BloodCampStats] first mob since the kill timer spawned (#{}): counting ticks with a"
                + " blood mob alive", mobId);
    }

    /** Testkit: as if a trip's mob landed now. */
    public static void tripMobLandedForTest(int mobId) {
        onTripMobLanded(mobId, false);
    }

    static void onCampCleared() {
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] camp cleared with phase {}: no greeting seen, nothing to report", phase);
            return;
        }
        campLagMs = BloodCampMoveTimer.lagMsSinceGreeting();
        BloodCampFeature.LOGGER.info("[BloodCampStats] camp done: {} mob ticks lost (first kill {}), lag {} ms, camped {}",
                ticksLost, firstKillTicks, campLagMs, camped);
        sendSummary();
        phase = Phase.DONE;
    }

    /** Blood mobs that died with his look on them and a swing or use of his in the 8 ticks before: his kills. */
    private static final java.util.Set<Integer> OWN_KILLS = new java.util.HashSet<>();
    /** His own kills that make a non-Mage a camper (one or two are a leap through the Mage's camp). */
    static final int OWN_KILLS_TO_CAMP = 3;
    private static long lastActionTick = Long.MIN_VALUE;

    /** His left button held (end of every client tick), his item use (UseItemCallback) or a click the mod sent for him, only
     *  while a camp is being counted. */
    static void noteAction(Minecraft client) {
        if (client.level != null && (phase == Phase.WAITING_SPAWN || phase == Phase.ALIVE)) {
            lastActionTick = client.level.getGameTime();
        }
    }

    private static void noteOwnKill(int id) {
        Minecraft client = Minecraft.getInstance();
        if (camped || client.level == null || client.player == null || !(phase == Phase.WAITING_SPAWN || phase == Phase.ALIVE)
                || client.level.getGameTime() - lastActionTick > 8) {
            return;
        }
        net.minecraft.world.entity.Entity e = client.level.getEntity(id);
        if (e != null && BloodCampFeature.aimedAtRoughly(client, e.getBoundingBox())) {
            ownKill(id);
        }
    }

    private static void ownKill(int id) {
        if (OWN_KILLS.add(id) && OWN_KILLS.size() >= OWN_KILLS_TO_CAMP) {
            camped = true;
            BloodCampFeature.LOGGER.info("[BloodCampStats] {} blood mobs killed by him: counted as camping", OWN_KILLS.size());
        }
    }

    /** Testkit: as if he killed blood mob {@code id} (the swing and aim test is {@link #noteOwnKill}). */
    public static void ownKillForTest(int id) {
        ownKill(id);
    }

    /** He is in the Blood Room this tick (from BloodCampFeature): after the kill timer that is camping it, unless his class is
     *  known and is not Mage (an Archer leaping in on the Mage's camp is not camping; Send For Any Class still sends). A
     *  non-Mage who killed {@link #OWN_KILLS_TO_CAMP} blood mobs himself did camp ({@link #noteOwnKill}). */
    static void noteInRoom(boolean inRoom) {
        if (inRoom && !camped && (phase == Phase.WAITING_SPAWN || phase == Phase.ALIVE)) {
            com.killer560.hub.dungeonclass.DungeonClass cls = com.killer560.hub.leapmenu.PartyTracker.selfClass();
            camped = cls == null || cls == com.killer560.hub.dungeonclass.DungeonClass.MAGE;
        }
    }

    static void noteKillTimer(boolean open) {
        // (the kill timer itself is read on the server tick; kept so the feature's tick names the moment it saw it)
    }

    /** The camp cleared: send the message once, if it is wanted and there is something to say. */
    private static void sendSummary() {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        if (sentThisRun || phase == Phase.IDLE) {
            return;
        }
        sentThisRun = true;
        String text = message(cfg.isPartyMessageLag());
        lastMessage = text;
        if (lostUnknown()) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] camp cleared: time lost unknown (out of range {} ticks), no party message ({})",
                    farTicks, text);
            return;
        }
        if (!cfg.isEnabled() || !cfg.isPartyMessage()) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] camp cleared: Party Message off ({})", text);
            return;
        }
        if (!camped && !cfg.isPartyMessageAnyClass()) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] camp cleared: he did not camp and Send For Any Class is off ({})", text);
            return;
        }
        boolean sent = com.killer560.hub.util.OutgoingChat.command("pc " + text);
        partyMessagesSent += sent ? 1 : 0;
        BloodCampFeature.LOGGER.info("[BloodCampStats] camp cleared: party message {}: {}", sent ? "sent" : "not sent", text);
    }

    private static int partyMessagesSent;

    static String message(boolean withLag) {
        String s = lostUnknown() ? "Blood Camp: time lost to slow mob kills unknown (out of range)"
                : "Blood Camp: " + (blindTicks > 0 ? "at least " : "") + String.format(Locale.US, "%d ticks lost to slow mob kills (%.2fs)",
                ticksLost, ticksLost / 20.0) + (blindTicks > 0 ? ", part of the camp out of range" : "");
        if (withLag) {
            s += String.format(Locale.US, ", %.2fs of server lag during the camp", campLagMs / 1000.0);
        }
        return s;
    }

    /** Nothing was counted and the camp was out of view for part of it: the figure cannot be known. */
    public static boolean lostUnknown() {
        return !counted && farTicks > 0;
    }

    /** Ticks counted while a mob could not be seen (the figure is a lower bound when above 0). */
    public static long blindTicks() {
        return blindTicks;
    }

    private static String lostText() {
        return String.format(Locale.US, "%d%s (%.2fs%s)", ticksLost, blindTicks > 0 ? "+" : "", ticksLost / 20.0,
                blindTicks > 0 ? "+" : "");
    }

    public static long ticksLost() {
        return ticksLost;
    }

    public static long firstKillTicks() {
        return firstKillTicks;
    }

    public static String phaseName() {
        return phase.name();
    }

    public static boolean camped() {
        return camped;
    }

    public static long campLagMs() {
        return campLagMs;
    }

    public static String lastMessage() {
        return lastMessage;
    }

    public static int partyMessagesSent() {
        return partyMessagesSent;
    }

    // ---- HUD -------------------------------------------------------------------------------------------------------

    /** The lines the HUD shows now (empty outside a camp). */
    static List<String> lines(boolean preview) {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        List<String> out = new ArrayList<>(3);
        if (preview) {
            if (cfg.isHudMobsLeft()) {
                out.add("Mobs Left: 4");
            }
            if (cfg.isHudMobTicks()) {
                out.add("Mob Ticks: 37 (1.85s)");
            }
            if (cfg.isHudCampLag()) {
                out.add("Camp Lag: 0.40s");
            }
            return out;
        }
        // Gone once the boss is entered (killer560, 2026-11-02: "blood camp hud should go away once boss is entered"):
        // it stayed up for the rest of the run, since nothing but a world change ends a camp. DungeonState's F7/M7 boss
        // phase, and the map's per-floor boss room for the other floors (LiveMapFeature.isInBoss reads both). The stats
        // and the end-of-run party message are untouched.
        if (phase == Phase.IDLE || com.killer560.hub.livemap.LiveMapFeature.isInBoss()) {
            return out;
        }
        Minecraft client = Minecraft.getInstance();
        if (cfg.isHudMobsLeft() && phase != Phase.DONE) {
            out.add("Mobs Left: " + mobsLeftText(client));
        }
        if (cfg.isHudMobTicks()) {
            out.add("Mob Ticks: " + (lostUnknown() ? "unknown (out of range)" : lostText()));
        }
        if (cfg.isHudCampLag()) {
            out.add(String.format(Locale.US, "Camp Lag: %.2fs", campLagMs / 1000.0));
        }
        return out;
    }

    /**
     * Mobs Left (killer560, 2026-11-02): every blood mob the camp still needs - alive AND not spawned yet - read off the
     * Watcher's boss bar ({@link WatcherBar}). "1" only when one mob is left in the whole camp. Without his bar the
     * client cannot see the mobs still to come, so it shows "?" until the Watcher says no more are coming ("That will be
     * enough for now."), then the mobs it counts (alive, and on their way from the wall): never a low number while more
     * are due.
     */
    static String mobsLeftText(Minecraft client) {
        int fromBar = WatcherBar.mobsLeft(client);
        if (fromBar >= 0) {
            return Integer.toString(fromBar);
        }
        if (!BloodCampMoveTimer.isSpawningDone()) {
            return "?";
        }
        int[] counts = BloodCampFeature.campCounts(client);
        return Integer.toString(counts[0] + counts[1]);
    }

    /** Testkit: the Mobs Left value as the HUD shows it. */
    public static String mobsLeftForTest() {
        return mobsLeftText(Minecraft.getInstance());
    }

    /** Testkit: the Watcher's bar progress as the client draws it now (eased), or null with no bar of his. */
    public static Float watcherBarForTest() {
        float p = WatcherBar.progress(Minecraft.getInstance());
        return Float.isNaN(p) ? null : p;
    }

    /** Testkit: the camp size the Watcher's bar was calibrated to (-1 before its first step). */
    public static int watcherBarSizeForTest() {
        return WatcherBar.calibratedForTest();
    }

    /** Testkit: the floor the camp size is read for (null or "" = the run's). */
    public static void setWatcherBarFloorForTest(String floor) {
        WatcherBar.setFloorForTest(floor);
    }

    /** Testkit: blood mobs counted alive now (Mob Ticks counts a tick while this is above 0). */
    public static int aliveForTest() {
        return BloodCampFeature.campCounts(Minecraft.getInstance())[0];
    }

    /** Testkit: {@link #lines}. */
    public static List<String> hudLinesForTest() {
        return lines(false);
    }

    private static final int W = 130;
    private static final int LINE_H = 10;

    static final HudElement HUD = new BasicHud("blood_camp_stats", "Blood Camp Stats", 4, 120) {
        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return LINE_H * 3;
        }

        @Override
        public boolean isEnabledInSettings() {
            BloodCampConfig cfg = BloodCampConfig.getInstance();
            return cfg.isEnabledRaw() && cfg.isStatsHudAny();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean preview = HudVisibility.editorOpen();
            if (!preview && (HudVisibility.hidesHud() || !DungeonState.isInDungeon())) {
                return;
            }
            List<String> lines = lines(preview);
            if (lines.isEmpty()) {
                return;
            }
            HudSeen.markDrawn(id());
            Minecraft client = Minecraft.getInstance();
            int lineY = y;
            for (String line : lines) {
                int colon = line.indexOf(':');
                String label = line.substring(0, colon + 1);
                String value = line.substring(colon + 1);
                graphics.text(client.font, label, x, lineY, ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE)), true);
                graphics.text(client.font, value, x + com.killer560.hub.compat.McRender.textWidth(client.font, label), lineY,
                        0xFFFFFFFF, true);
                lineY += LINE_H;
            }
        }
    };
}
