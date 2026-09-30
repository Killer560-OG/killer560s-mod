package com.killer560.hub.spotify;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reads what is playing straight out of the Spotify desktop app that is already running on this PC.
 *
 * <h2>Why this exists</h2>
 *
 * <p>killer560 (2026-09-30): "I want you to create something inside the mod so they no longer need to go to
 * that external website. They should just be able to boot up the mod [...] no external website."
 *
 * <p>The old path went through Last.fm, and it cost the user five steps on three different websites: make a
 * Last.fm account, turn on Spotify scrobbling, register an API application, copy the API key back into the
 * mod, and type in the username. This source needs none of them. Spotify's own desktop window title IS the
 * now-playing line - Windows puts {@code Artist - Title} there while a track plays and drops back to plain
 * {@code Spotify} / {@code Spotify Premium} when it is paused - so if the app is open, the answer is already
 * on this machine. No account, no key, no login, no website, and nothing leaves the PC.
 *
 * <h2>What it cannot do</h2>
 *
 * <p>A window title carries no clock, so there is no playback position: the lyric timer still keys off the
 * moment the title changed, the same way the Last.fm path does. It is much closer to the truth though - the
 * title flips within a frame of the track changing, where Last.fm takes several seconds - so the offset
 * slider that used to need about 5 s of compensation wants roughly zero here.
 *
 * <p>It also cannot see a phone or a web player: this is the desktop app on this PC only. That is what
 * {@link #available()} reports, so the tab can say "Spotify is not running" instead of showing nothing.
 *
 * <h2>How it finds the window</h2>
 *
 * <p>Two halves, deliberately: the JDK's own {@link ProcessHandle} finds which process ids belong to
 * {@code Spotify.exe}, and JNA asks Windows for the titles of top-level windows and which process each one
 * belongs to. Matching on the process id rather than on the window class is what makes it reliable - Spotify
 * is a Chromium app, so its window class {@code Chrome_WidgetWin_1} is also Chrome's, Discord's and every
 * other Electron app's, and matching on that would read a browser tab's title as a song.
 *
 * <p>Every JNA call sits behind {@link Win}, which is only loaded on first use and only on Windows, and any
 * {@link Throwable} from loading it turns the source off rather than breaking the feature. Minecraft ships
 * JNA (oshi needs it) so it is there in practice, but "in practice" is not something to crash a client on.
 */
public final class SpotifyDesktopSource {

    /** Titles Spotify shows when nothing is playing. Lower-cased before comparison. */
    private static final Set<String> IDLE_TITLES = Set.of(
            "spotify", "spotify premium", "spotify free", "spotify - web player");

    /**
     * Titles Spotify shows while an advert plays.
     *
     * <p>Separate from the idle titles on purpose: an advert is not "nothing playing". Treating it as idle
     * left the last lyric line of the previous song sitting in chat for the length of the advert.
     */
    private static final Set<String> AD_TITLES = Set.of("advertisement", "spotify advertisement");

    private volatile String reason = "";

    public String displayName() {
        return "Spotify App";
    }

    public boolean available() {
        if (!Win.supported()) {
            reason = Win.why();
            return false;
        }
        // The cheap answer first: if the window we were already reading is still there, Spotify is plainly
        // running and there is no need to walk the process table for permission to say so.
        if (Win.cachedWindowAlive()) {
            reason = "";
            return true;
        }
        if (spotifyPids().isEmpty()) {
            reason = "Spotify is not running on this PC";
            return false;
        }
        reason = "";
        return true;
    }

    public String unavailableReason() {
        return reason;
    }

    /**
     * @return what Spotify is doing, never null. Never throws; a failure reads as paused.
     *
     *     <p>Two paths, and the fast one does no process work at all. Measured on killer560's PC
     *     (2026-09-30): reading a known window handle takes <b>0.006 ms</b>, enumerating every top-level
     *     window takes 1.4 ms, and walking the process table for Spotify's process ids takes <b>57 ms</b>.
     *     At four polls a second the last of those is the only one that would matter, so while the handle
     *     we are already reading is alive it is trusted outright - it was checked against Spotify's process
     *     ids when it was found, and Windows does not hand a live window's handle to anything else.
     */
    public NowPlaying fetch() {
        try {
            String title = Win.cachedTitle();
            if (title == null) {
                Set<Long> pids = spotifyPids();
                if (pids.isEmpty()) {
                    reason = "Spotify is not running on this PC";
                    return NowPlaying.paused();
                }
                title = Win.titleOfProcess(pids);
            }
            if (title == null || title.isBlank()) {
                // Spotify running but no titled top-level window: minimised to tray on some builds.
                return NowPlaying.paused();
            }
            String lower = title.toLowerCase(Locale.ROOT);
            if (AD_TITLES.contains(lower)) {
                return NowPlaying.ad();
            }
            if (IDLE_TITLES.contains(lower)) {
                return NowPlaying.paused();
            }
            // Spotify writes "Artist - Title". Split on the FIRST separator: a title is far more likely to
            // contain " - " than an artist name is, so splitting on the last one would cut songs in half.
            int dash = title.indexOf(" - ");
            if (dash <= 0 || dash + 3 >= title.length()) {
                return NowPlaying.track("", title.trim());
            }
            return NowPlaying.track(title.substring(0, dash).trim(), title.substring(dash + 3).trim());
        } catch (Throwable t) {
            // Throwable, not Exception: a missing or mismatched JNA is a LinkageError, and the feature going
            // quiet is a better outcome than a poll thread dying silently.
            SpotifyLyricsFeature.LOGGER.warn("Could not read the Spotify window: {}", String.valueOf(t));
            reason = "could not read the Spotify window";
            return NowPlaying.paused();
        }
    }

    /** Cached because the process table is not cheap to walk - see {@link #spotifyPids()}. */
    private static volatile Set<Long> cachedPids = Set.of();
    private static volatile long cachedPidsAtMs;

    /** How long a process-id lookup is reused. Spotify does not restart between one poll and the next. */
    private static final long PID_CACHE_MS = 15_000;

    /**
     * Process ids of every running {@code Spotify.exe}, via the JDK - no native call needed for this half.
     *
     * <p>Measured at <b>57 ms</b> a call on killer560's PC (2026-09-30), because
     * {@link ProcessHandle#allProcesses()} walks the whole process table and reads each entry's command
     * line. Far too expensive for a 250 ms poll, which is why the live-window path in {@link #fetch()}
     * avoids it entirely and this is only reached when there is no window to read - and cached for
     * {@value #PID_CACHE_MS} ms even then.
     */
    private static Set<Long> spotifyPids() {
        long now = System.currentTimeMillis();
        Set<Long> cached = cachedPids;
        if (now - cachedPidsAtMs < PID_CACHE_MS) {
            return cached;
        }
        Set<Long> pids = new HashSet<>();
        try {
            ProcessHandle.allProcesses().forEach(ph -> {
                String command = ph.info().command().orElse("");
                String lower = command.toLowerCase(Locale.ROOT).replace('\\', '/');
                if (lower.endsWith("/spotify.exe") || lower.equals("spotify.exe")) {
                    pids.add(ph.pid());
                }
            });
        } catch (Throwable ignored) {
            // A process we are not allowed to inspect is not an error; it just is not Spotify's.
        }
        cachedPids = pids;
        cachedPidsAtMs = now;
        return pids;
    }

    /**
     * The JNA half, in its own class so nothing here touches JNA until it is actually used.
     *
     * <p>A class is only loaded when first referenced, so keeping every {@code com.sun.jna} type behind this
     * boundary means a client without JNA - or with an incompatible one - gets {@code supported() == false}
     * instead of a {@code NoClassDefFoundError} thrown out of the lyrics poll thread.
     */
    private static final class Win {

        private static Boolean ok;
        private static String why = "";

        private Win() {
        }

        static synchronized boolean supported() {
            if (ok != null) {
                return ok;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("win")) {
                why = "reading the Spotify app only works on Windows";
                ok = false;
                return false;
            }
            try {
                Class.forName("com.sun.jna.platform.win32.User32");
                why = "";
                ok = true;
            } catch (Throwable t) {
                why = "this Minecraft build has no JNA, so the Spotify app cannot be read";
                ok = false;
            }
            return ok;
        }

        static String why() {
            supported();
            return why;
        }

        /**
         * The now-playing title among this process's visible top-level windows.
         *
         * <p>Every one of them, not the first: an app can have several, and taking whichever Windows happens
         * to enumerate first is a coin flip. Measured against Explorer (2026-09-30) that returns "Downloads -
         * File Explorer" and "Program Manager" - two visible titled windows from one process. A track title is
         * picked out by preferring one that is not an idle title and that carries Spotify's {@code " - "}
         * separator, so a stray helper window cannot outvote the real one.
         *
         * <p>Verified end to end against Discord, which is the same Chromium multi-process shape as Spotify:
         * six processes, one visible titled window, and the title matched what Windows itself reports
         * ("#supporter-general | AshFall - Discord") exactly.
         */
        /**
         * The window handle that answered last time, so the steady state is two syscalls rather than a full
         * enumeration.
         *
         * <p>Worth caching because the poll went from every two seconds to every 250 ms (the lyric clock
         * starts when the title change is SEEN, so a slow poll makes every line in the song late). Enumerating
         * every top-level window four times a second, with a process-id lookup on each, is a lot of syscalls
         * for an answer that is the same window all session.
         *
         * <p>It is only trusted when it still exists, still belongs to Spotify and still has a title that is
         * not an idle one: a cached handle reporting "Spotify Premium" could be a second window while the real
         * one is playing, so that case falls back to the full enumeration rather than reporting paused.
         */
        private static com.sun.jna.platform.win32.WinDef.HWND cached;

        static String titleOfProcess(Set<Long> pids) {
            com.sun.jna.platform.win32.User32 user32 = com.sun.jna.platform.win32.User32.INSTANCE;
            com.sun.jna.platform.win32.WinDef.HWND hit = cached;
            if (hit != null) {
                String quick = titleOf(user32, hit, pids);
                if (quick != null && !IDLE_TITLES.contains(quick.toLowerCase(Locale.ROOT))) {
                    return quick;
                }
            }
            java.util.List<String> titles = new java.util.ArrayList<>(2);
            java.util.List<com.sun.jna.platform.win32.WinDef.HWND> handles = new java.util.ArrayList<>(2);
            user32.EnumWindows((hwnd, data) -> {
                try {
                    if (!user32.IsWindowVisible(hwnd)) {
                        return true;
                    }
                    String title = titleOf(user32, hwnd, pids);
                    if (title != null) {
                        titles.add(title);
                        handles.add(hwnd);
                    }
                } catch (Throwable ignored) {
                    // One unreadable window does not stop the others.
                }
                return true;
            }, null);
            String fallback = null;
            for (int i = 0; i < titles.size(); i++) {
                String title = titles.get(i);
                String lower = title.toLowerCase(Locale.ROOT);
                if (AD_TITLES.contains(lower)) {
                    cached = handles.get(i);
                    return title;   // an advert is a real state, not a window to skip past
                }
                if (IDLE_TITLES.contains(lower)) {
                    if (fallback == null) {
                        fallback = title;
                        cached = handles.get(i);
                    }
                    continue;
                }
                if (title.contains(" - ")) {
                    cached = handles.get(i);
                    return title;   // "Artist - Title": this is the one
                }
                fallback = title;
                cached = handles.get(i);
            }
            return fallback;
        }

        /** Whether the handle we were reading is still a live window. Two syscalls, no process work. */
        static boolean cachedWindowAlive() {
            try {
                com.sun.jna.platform.win32.WinDef.HWND hit = cached;
                return hit != null && com.sun.jna.platform.win32.User32.INSTANCE.IsWindow(hit);
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * The cached window's title, or null to say "ask properly".
         *
         * <p>Null for a dead handle, and null for an IDLE title too: a cached handle reading "Spotify
         * Premium" may be a second window while the real one plays, and reporting paused on that would stop
         * the lyric clock mid-song. Paused is the one answer worth paying for the slow path to be sure of.
         */
        static String cachedTitle() {
            try {
                com.sun.jna.platform.win32.WinDef.HWND hit = cached;
                if (hit == null) {
                    return null;
                }
                com.sun.jna.platform.win32.User32 user32 = com.sun.jna.platform.win32.User32.INSTANCE;
                if (!user32.IsWindow(hit)) {
                    cached = null;
                    return null;
                }
                char[] buffer = new char[512];
                int length = user32.GetWindowText(hit, buffer, buffer.length);
                if (length <= 0) {
                    return null;
                }
                String title = new String(buffer, 0, length);
                return IDLE_TITLES.contains(title.toLowerCase(Locale.ROOT)) ? null : title;
            } catch (Throwable t) {
                return null;
            }
        }

        /** This window's title, or null when it is not Spotify's or has none. */
        private static String titleOf(com.sun.jna.platform.win32.User32 user32,
                                     com.sun.jna.platform.win32.WinDef.HWND hwnd, Set<Long> pids) {
            if (!user32.IsWindow(hwnd)) {
                return null;
            }
            com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
            user32.GetWindowThreadProcessId(hwnd, pid);
            if (!pids.contains((long) (pid.getValue() & 0xFFFFFFFFL))) {
                return null;
            }
            // GetWindowText, not GetWindowTextW: JNA's User32 is declared with the default W32 options, so it
            // already binds the wide variant behind that name and there is no ...W method to call.
            char[] buffer = new char[512];
            int length = user32.GetWindowText(hwnd, buffer, buffer.length);
            return length > 0 ? new String(buffer, 0, length) : null;
        }
    }
}
