package com.killer560.hub.spotify;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

/**
 * Spotify Controls (killer560, 2026-10-26: "make the spotify menu have a pause button and a skip button and rewind and
 * stuff. and let me set keybinds for it as well. also have a volume slider").
 *
 * <p>Everything goes to the Spotify desktop app on this PC through Windows' own media controls (the
 * GlobalSystemMediaTransportControls session Spotify publishes, the same one the volume-key flyout drives), sent as one
 * line down {@link SpotifyMediaSession}'s helper process. No Spotify account, password, API key or Premium: the desktop
 * app takes these from Windows on Free too. Volume is Spotify's own slider in the Windows volume mixer (Core Audio),
 * not Spotify's in-app slider, which Windows cannot reach.
 *
 * <p>Seeking (Rewind, Skip Ahead) is relative to Spotify's own position, worked out by the helper from the reading it
 * takes at that moment, and the lyric clock is moved at once ({@link LyricsEngine#nudge}) and then corrected by
 * Spotify's reading of the seek about 130 ms later.
 *
 * <p>Keys work in game with no screen open (never while typing in chat or a sign, never in a menu), on the press, once
 * per press. All unbound by default; set in the Spotify Lyrics tab, Esc unbinds.
 */
public final class SpotifyControls {

    public enum Action {
        PLAY_PAUSE("playPauseKey", "Play/Pause"),
        PREVIOUS("previousKey", "Previous"),
        REWIND("rewindKey", "Rewind"),
        SKIP_AHEAD("skipAheadKey", "Skip Ahead"),
        NEXT("nextKey", "Next"),
        VOLUME_UP("volumeUpKey", "Volume Up"),
        VOLUME_DOWN("volumeDownKey", "Volume Down");

        /** Config key of its key bind. */
        final String key;
        public final String label;

        Action(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** Test hook: where commands go instead of the helper (null = the helper). With it set, nothing reaches Spotify. */
    static volatile Consumer<String> testSink;
    /** Test hook: a key code read as held down, as BlinkTests' keyHeldForTest (-1 = none). */
    static volatile int testHeldKey = -1;
    /** Test hook: what {@link #unavailableReason} answers instead of the live state (null = live; "" = available). */
    static volatile String testReason;

    // Test hooks (read by the testkit).
    private static volatile String lastCommand = "";
    private static volatile long commandCount;
    private static volatile long keyPresses;
    private static volatile String lastKeyAction = "";

    /** The last time a control was used or the Spotify tab was built; the helper is kept up for a while after. */
    private static volatile long lastUseMs;
    private static final long KEEP_ALIVE_MS = 120_000;

    private static boolean[] wasDown = new boolean[Action.values().length];

    private SpotifyControls() {
    }

    public static void register() {
        SpotifyControlsConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SpotifyControls", SpotifyControls::tick));
    }

    private static void tick(Minecraft client) {
        SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
        // With a screen open nothing fires (chat, a sign, a menu), but the held state is still tracked, so the key that
        // closed a screen does not read as a fresh press on the first free tick.
        boolean fire = client.player != null && McCompat.screen(client) == null;
        Action[] all = Action.values();
        for (Action a : all) {
            int code = cfg.getKey(a);
            boolean down = code != KeyUtil.NONE && (code == testHeldKey
                    || (client.getWindow() != null && KeyUtil.isBindDown(client.getWindow(), code)));
            if (fire && down && !wasDown[a.ordinal()]) {
                keyPresses++;
                lastKeyAction = a.name();
                String why = unavailableReason(a);
                if (why != null) {
                    ModChat.send("Spotify", ModChat.text(why));
                } else {
                    perform(a);
                }
            }
            wasDown[a.ordinal()] = down;
        }
    }

    /** Whether the Windows reader should run for the controls: a key is bound, or a control or the tab was just used. */
    static boolean sessionWanted(long nowMs) {
        return SpotifyControlsConfig.getInstance().anyKeyBound() || nowMs - lastUseMs < KEEP_ALIVE_MS;
    }

    /** The Spotify tab was opened: start the reader so the buttons know what Spotify allows, and read the volume. */
    public static void tabOpened() {
        lastUseMs = System.currentTimeMillis();
        if (testSink == null && SpotifyMediaSession.isWindows()) {
            SpotifyLyricsFeature.runOffThread(() -> {
                SpotifyMediaSession m = SpotifyLyricsFeature.media();
                if (m.volume() == -2) {
                    m.send("volq");
                } else {
                    m.ensureRunning();
                }
            });
        }
    }

    /** Does {@code action} now. Any thread; the pipe write happens off the render thread. */
    public static void perform(Action action) {
        SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
        switch (action) {
            case PLAY_PAUSE -> send("toggle");
            case NEXT -> send("next");
            case PREVIOUS -> send("prev");
            case REWIND -> seek(-cfg.getSeekSeconds() * 1000L);
            case SKIP_AHEAD -> seek(cfg.getSeekSeconds() * 1000L);
            case VOLUME_UP -> stepVolume(cfg.getVolumeStep());
            case VOLUME_DOWN -> stepVolume(-cfg.getVolumeStep());
        }
    }

    private static void seek(long deltaMs) {
        send("rel " + deltaMs);
        SpotifyLyricsFeature.nudge(deltaMs);
    }

    private static void stepVolume(int delta) {
        int now = volume();
        if (now >= 0) {
            setVolume(now + delta);
        } else {
            send("volrel " + delta);   // not known here yet: the helper reads Spotify's and moves it
        }
    }

    /** Sets Spotify's mixer volume (0-100) and remembers it. Saving is the caller's (the slider saves on release). */
    public static void setVolume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
        cfg.setVolume(v);
        shownVolume = v;
        shownVolumeAtMs = System.currentTimeMillis();
        send("vol " + v);
    }

    /** The volume to show: one just set here for a moment (the reader takes a beat to see it), else Spotify's own,
     *  else the last one set; -1 when none is known. */
    public static int volume() {
        if (System.currentTimeMillis() - shownVolumeAtMs < 1500) {
            return shownVolume;
        }
        int live = testSink != null ? -2 : SpotifyLyricsFeature.media().volume();
        return live >= 0 ? live : SpotifyControlsConfig.getInstance().getVolume();
    }

    private static volatile int shownVolume = -1;
    private static volatile long shownVolumeAtMs;

    private static void send(String command) {
        lastUseMs = System.currentTimeMillis();
        lastCommand = command;
        commandCount++;
        Consumer<String> sink = testSink;
        if (sink != null) {
            sink.accept(command);
            return;
        }
        SpotifyLyricsFeature.runOffThread(() -> {
            if (!SpotifyLyricsFeature.media().send(command)) {
                SpotifyLyricsFeature.LOGGER.info("Spotify control '{}' could not reach the Windows media reader", command);
            }
        });
    }

    /**
     * Why {@code action} cannot work right now, or null when it can. Shown in the button's hover while it is grayed out,
     * and in chat when its key is pressed.
     */
    public static String unavailableReason(Action action) {
        String t = testReason;
        if (t != null) {
            return t.isEmpty() ? null : t;
        }
        if (!SpotifyMediaSession.isWindows()) {
            return "Spotify controls need Windows and the Spotify desktop app.";
        }
        SpotifyMediaSession m = SpotifyLyricsFeature.media();
        String state = m.state();
        if ("no Spotify session".equals(state)) {
            return "Spotify is not open on this PC. Only the desktop app can be controlled, not the web player or a phone.";
        }
        if ("read failed".equals(state)) {
            return "Windows' media controls did not answer for Spotify.";
        }
        switch (action) {
            case PLAY_PAUSE:
                return m.allows(0) ? null : "Spotify does not allow play/pause right now.";
            case NEXT:
                return m.allows(1) ? null : "Spotify does not allow skipping right now (an ad, most likely).";
            case PREVIOUS:
                return m.allows(2) ? null : "Spotify does not allow going back right now (an ad, most likely).";
            case REWIND:
            case SKIP_AHEAD:
                return m.allows(3) ? null : "Spotify does not allow seeking right now (an ad, most likely).";
            default:
                return m.volume() == -1
                        ? "Spotify has not played any sound yet, so Windows has no volume for it. Play something first."
                        : null;
        }
    }
}
