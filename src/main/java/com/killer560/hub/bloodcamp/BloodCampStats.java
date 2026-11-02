package com.killer560.hub.bloodcamp;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Blood Camp Stats (killer560, 2026-11-01): mobs left, the mob ticks lost to slow kills, the server lag during the camp,
 * a HUD for them and an end-of-run party message.
 * <p>
 * <b>Mob ticks</b>, his rules word for word: "start counting from the second kill goes off to the second the first mob is
 * killed, since for blood camp you have to kill just 1 mob to make it start moving but you have to wait till after that
 * kill message so i dont want it counting to kill all the 4 mobs there just the first one. Then from there on out it
 * should only count every tick a blood mob is alive. If a mob is dead or a mob is about to spawn it shouldnt count." So:
 * <ol>
 *   <li>Nothing counts until the kill timer goes off (the Kill Popup's KILL, {@link BloodCampMoveTimer#killReached}).</li>
 *   <li>From then every tick counts until the first blood mob dies.</li>
 *   <li>After that a tick counts only while at least one blood mob is alive on the floor (tagged, landed, not dead); a
 *   mob still on its way from the wall is "about to spawn" and does not count.</li>
 *   <li>It stops on "You have proven yourself. You may pass." (the room is cleared).</li>
 * </ol>
 * Ticks are SERVER ticks ({@code util.PerTickServerClock}: one per server ping on Hypixel, client ticks elsewhere), so a server
 * stall adds nothing here: it is reported apart, as the camp's lag (the ticks {@code util.PerTickServerClock} saw the server miss since the Watcher's greeting).
 * <p>
 * It is all read from what the client sees - the Watcher's lines and the blood mobs' own entities - so it works from any
 * room the blood mobs are loaded in, whoever camps. Party Message sends it after the run when he camped himself (was in
 * the Blood Room after the kill timer); Send For Any Class sends it whoever camped.
 */
public final class BloodCampStats {

    private enum Phase { IDLE, WAITING_KILL, FIRST, ALIVE, DONE }

    private static Phase phase = Phase.IDLE;
    private static long ticksLost;
    private static long firstKillTicks = -1L;
    private static long campLagMs;
    private static boolean camped;
    private static boolean sentThisRun;
    private static Object lastLevel;
    private static long lastClock = com.killer560.hub.util.PerTickServerClock.now();
    /** Testkit: the last party message sent (or that would have been, with Party Message on). */
    private static String lastMessage;

    private static final Pattern EXTRA_STATS = Pattern.compile("^\\s*> EXTRA STATS <\\s*$");

    private BloodCampStats() {
    }

    static void register() {
        com.killer560.hub.util.PerTickServerClock.register();
        // One step per server tick of util.PerTickServerClock (not ServerTickClock, which an anticheat's ~15 pings a
        // second already make "ping-driven"), stepped at the end of each client tick.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = com.killer560.hub.util.PerTickServerClock.now();
            long steps = Math.max(0L, Math.min(200L, now - lastClock));
            lastClock = now;
            for (long i = 0; i < steps; i++) {
                onServerTick();
            }
            if (phase == Phase.WAITING_KILL || phase == Phase.FIRST || phase == Phase.ALIVE) {
                WatcherBar.tick(client);
            }
        });
        ChatObserver.subscribe(BloodCampStats::onChat);
    }

    // ---- inputs ----------------------------------------------------------------------------------------------------

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
        firstKillTicks = -1L;
        campLagMs = 0L;
        camped = false;
        sentThisRun = false;
        WatcherBar.reset();
    }

    /** Every server tick. */
    private static void onServerTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset("world change");
            return;
        }
        if (client.level == null || phase == Phase.IDLE || phase == Phase.DONE) {
            return;
        }
        if (phase == Phase.WAITING_KILL) {
            if (!BloodCampMoveTimer.killReached()) {
                return;
            }
            phase = Phase.FIRST;
            BloodCampFeature.LOGGER.info("[BloodCampStats] kill timer went off: counting until the first blood mob dies");
        }
        if (phase == Phase.FIRST) {
            ticksLost++;
        } else if (BloodCampFeature.campCounts(client)[0] > 0) {
            ticksLost++;
        }
        campLagMs = BloodCampMoveTimer.lagMsSinceGreeting();
    }

    /** A tagged blood mob died (seen dead, or removed by the server while dead / close to him). */
    static void onBloodMobDied(int id) {
        if (phase == Phase.FIRST) {
            phase = Phase.ALIVE;
            firstKillTicks = ticksLost;
            BloodCampFeature.LOGGER.info("[BloodCampStats] first blood mob #{} died {} ticks after the kill timer", id,
                    ticksLost);
        }
    }

    static void onCampCleared() {
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            return;
        }
        campLagMs = BloodCampMoveTimer.lagMsSinceGreeting();
        BloodCampFeature.LOGGER.info("[BloodCampStats] camp done: {} mob ticks lost (first kill {}), lag {} ms, camped {}",
                ticksLost, firstKillTicks, campLagMs, camped);
        phase = Phase.DONE;
    }

    /** He is in the Blood Room this tick (from BloodCampFeature): after the kill timer that is camping it. */
    static void noteInRoom(boolean inRoom) {
        if (inRoom && (phase == Phase.FIRST || phase == Phase.ALIVE)) {
            camped = true;
        }
    }

    static void noteKillTimer(boolean open) {
        // (the kill timer itself is read on the server tick; kept so the feature's tick names the moment it saw it)
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain.length() > 40 || !EXTRA_STATS.matcher(plain).matches()) {
            return;
        }
        endOfRun();
    }

    /** The run's summary arrived: send the message once, if it is wanted and there is something to say. */
    static void endOfRun() {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        if (sentThisRun || phase == Phase.IDLE || phase == Phase.WAITING_KILL) {
            return;
        }
        sentThisRun = true;
        String text = message(cfg.isPartyMessageLag());
        lastMessage = text;
        if (!cfg.isEnabled() || !cfg.isPartyMessage()) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] end of run: Party Message off ({})", text);
            return;
        }
        if (!camped && !cfg.isPartyMessageAnyClass()) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] end of run: he did not camp and Send For Any Class is off ({})", text);
            return;
        }
        boolean sent = com.killer560.hub.util.ServerCommands.toServer("pc " + text);
        partyMessagesSent += sent ? 1 : 0;
        BloodCampFeature.LOGGER.info("[BloodCampStats] end of run: party message {}: {}", sent ? "sent" : "not sent", text);
    }

    private static int partyMessagesSent;

    static String message(boolean withLag) {
        String s = String.format(Locale.US, "Blood Camp: %d ticks lost to slow mob kills (%.2fs)", ticksLost, ticksLost / 20.0);
        if (withLag) {
            s += String.format(Locale.US, ", %.2fs of server lag during the camp", campLagMs / 1000.0);
        }
        return s;
    }

    // ---- testkit ---------------------------------------------------------------------------------------------------

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

    /** Testkit: the end-of-run summary as if "> EXTRA STATS <" arrived. */
    public static void endOfRunForTest() {
        endOfRun();
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
            out.add(String.format(Locale.US, "Mob Ticks: %d (%.2fs)", ticksLost, ticksLost / 20.0));
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

    static final HudElement HUD = new HudElement() {
        @Override
        public String id() {
            return "blood_camp_stats";
        }

        @Override
        public String displayName() {
            return "Blood Camp Stats";
        }

        @Override
        public int defaultX() {
            return 4;
        }

        @Override
        public int defaultY() {
            return 120;
        }

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
                graphics.text(client.font, label, x, lineY, 0xFF000000 | ModChat.ORANGE, true);
                graphics.text(client.font, value, x + com.killer560.hub.compat.McRender.textWidth(client.font, label), lineY,
                        0xFFFFFFFF, true);
                lineY += LINE_H;
            }
        }
    };
}
