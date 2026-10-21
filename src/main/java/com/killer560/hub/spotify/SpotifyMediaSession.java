package com.killer560.hub.spotify;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * Spotify's real playback position, length, play/pause state and cover, read from Windows' media controls.
 *
 * <h2>Why</h2>
 *
 * <p>killer560 (2026-10-20): "make sure it realizes if I go back in time or something from spotify", and the HUD
 * showing 3:59 for a song Spotify called 4:06. The window title {@link SpotifyDesktopSource} reads has no clock, so the
 * lyric timer was a stopwatch from the track change: wrong after any scrub, and wrong when the game started mid-song.
 * Spotify's desktop app publishes its session to Windows' System Media Transport Controls (the media flyout by the volume
 * keys) with the position, the time that position was taken, the track's length, playing or paused, and a thumbnail.
 *
 * <h2>How, and what it costs</h2>
 *
 * <p>That API is WinRT, which JNA cannot call without hand-written COM vtables, so a small PowerShell script (Windows
 * PowerShell 5.1 ships with every Windows 10 and 11, no new dependency) reads it and prints one tab-separated line every
 * {@value #LOOP_MS} ms, which a daemon thread here reads. One {@code powershell.exe} for the session, started only while
 * the Spotify HUD or Send Lyrics To Chat is on, stopped when both are off and when the game closes; measured 2026-10-20 on
 * killer560's PC at about 0.5 s of CPU for its start-up plus eight readings. It exits on its own when the game's process
 * is gone (checked every five seconds) or when its output pipe breaks, so it cannot outlive the game. The cover is
 * written by the script as a 64x64 PNG to the temp folder when the track changes (and once more 2.5 s later, since
 * Spotify sets the picture after the title), and only its bytes are handed over here. Nothing touches the network.
 *
 * <p>Everything here is off the render thread: the HUD reads {@link #cover()} bytes, never the file or the process.
 * Any failure (not Windows, no PowerShell, an old Windows without the API) leaves {@link #latest} null, and the lyric
 * clock falls back to the stopwatch exactly as before.
 */
public final class SpotifyMediaSession {

    /** How often the script reads the session. The position is extrapolated between readings, so this can be slow. */
    static final int LOOP_MS = 500;

    /** A reading older than this means the script is gone or stuck. */
    static final long STALE_MS = 2500;

    /** {@code GlobalSystemMediaTransportControlsSessionPlaybackStatus}: Playing and Paused. */
    static final int STATUS_PLAYING = 4;
    static final int STATUS_PAUSED = 5;

    /** One reading of Spotify's session. Times are wall-clock milliseconds. */
    public record Timeline(String artist, String title, int status, long positionMs, long endMs, long updatedMs,
                           long receivedMs, int coverSeq) {

        public boolean playing() {
            return status == STATUS_PLAYING;
        }

        public boolean paused() {
            return status == STATUS_PAUSED;
        }

        /**
         * Spotify's position at {@code nowMs}: the reported position plus the time since Spotify took it while playing,
         * held while paused, never past the end.
         */
        public long positionAt(long nowMs) {
            long p = positionMs + (playing() ? Math.max(0, nowMs - updatedMs) : 0);
            if (endMs > 0) {
                p = Math.min(p, endMs);
            }
            return Math.max(0, p);
        }

        /** Whether this reading is about the track the window title names (title, and artist when both have one). */
        public boolean matches(String artist, String title) {
            if (title == null || title.isBlank() || !this.title.trim().equalsIgnoreCase(title.trim())) {
                return false;
            }
            if (artist == null || artist.isBlank() || this.artist.isBlank()) {
                return true;
            }
            String a = this.artist.toLowerCase(Locale.ROOT);
            String b = artist.toLowerCase(Locale.ROOT);
            return a.contains(b) || b.contains(a);
        }
    }

    /** The cover picture of one track: PNG bytes, tagged with the reading's artist and title. */
    public record Cover(int seq, String artist, String title, byte[] png) {
    }

    /** Parses one line of the script's output, or null for "no Spotify session" / an error line / garbage. */
    static Timeline parse(String line, long receivedMs) {
        if (line == null || !line.startsWith("S\t")) {
            return null;
        }
        String[] f = line.split("\t", -1);
        if (f.length < 8) {
            return null;
        }
        try {
            return new Timeline(f[1].trim(), f[2].trim(), Integer.parseInt(f[3].trim()), Long.parseLong(f[4].trim()),
                    Long.parseLong(f[5].trim()), Long.parseLong(f[6].trim()), receivedMs, Integer.parseInt(f[7].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private volatile Timeline latest;
    private volatile Cover cover;
    private Process process;
    private long nextStartMs;
    private int failures;
    private boolean hookAdded;
    private final Path coverFile = Path.of(System.getProperty("java.io.tmpdir"),
            "killer560smod-spotify-cover-" + ProcessHandle.current().pid() + ".png");

    /** The newest reading, or null when there is none younger than {@link #STALE_MS}. Any thread. */
    public Timeline latest(long nowMs) {
        Timeline t = latest;
        return t == null || nowMs - t.receivedMs() > STALE_MS ? null : t;
    }

    /** The newest cover picture, or null. Any thread. */
    public Cover cover() {
        return cover;
    }

    /** Starts the helper if it is not running (with a back-off after failures). Poll thread; returns at once. */
    public synchronized void ensureRunning() {
        if (process != null && process.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextStartMs || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        // 5 s, 10 s, 20 s ... up to five minutes between attempts, so a Windows without the API is not retried forever.
        nextStartMs = now + Math.min(300_000L, 5_000L << Math.min(6, failures));
        failures++;
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            pb.environment().put("K560_SPOTIFY_PARENT", Long.toString(ProcessHandle.current().pid()));
            pb.environment().put("K560_SPOTIFY_COVER", coverFile.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.getOutputStream().close();
            process = p;
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "killer560smod-spotify-session-stop"));
            }
            Thread reader = new Thread(() -> read(p), "killer560smod-spotify-session");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            LOGGER.warn("Could not start the Spotify media-session reader: {}", String.valueOf(t));
        }
    }

    /** Stops the helper; the next {@link #ensureRunning} starts a fresh one at once. Any thread. */
    public synchronized void stop() {
        Process p = process;
        if (p == null && latest == null && cover == null) {
            return;   // already stopped: this runs every poll while both switches are off
        }
        process = null;
        if (p != null) {
            p.destroy();
        }
        latest = null;
        cover = null;
        nextStartMs = 0;
        failures = 0;
        try {
            Files.deleteIfExists(coverFile);
        } catch (Exception ignored) {
            // a temp file the OS will clear anyway
        }
    }

    /** What the helper last said, for the log: a line is written only when this changes. */
    private volatile String state = "stopped";

    /** "stopped", "reading Spotify", "no Spotify session", "read failed" or "ended"; any thread. */
    public String state() {
        return state;
    }

    private void setState(String s) {
        if (!s.equals(state)) {
            state = s;
            LOGGER.info("Windows media session: {}", s);
        }
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-spotify");

    private void read(Process p) {
        int coverSeq = 0;
        boolean sawReading = false;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                long now = System.currentTimeMillis();
                Timeline t = parse(line, now);
                setState(t != null ? "reading Spotify" : line.startsWith("N") ? "no Spotify session" : "read failed");
                if (t != null || line.startsWith("N")) {
                    if (!sawReading) {
                        sawReading = true;
                        synchronized (this) {
                            failures = 0;
                        }
                    }
                }
                latest = t;
                if (t != null && t.coverSeq() != coverSeq) {
                    coverSeq = t.coverSeq();
                    try {
                        byte[] png = Files.readAllBytes(coverFile);
                        if (png.length > 0 && png.length < 1_000_000) {
                            cover = new Cover(coverSeq, t.artist(), t.title(), png);
                        }
                    } catch (Exception ignored) {
                        // no picture this time; the HUD keeps its placeholder
                    }
                }
            }
        } catch (Exception ignored) {
            // the process ended; ensureRunning restarts it
        } finally {
            latest = null;
            setState("ended");
        }
    }

    /**
     * The reader. Inputs come from the environment (the game's pid, the cover path); output is one line per loop:
     * {@code S<TAB>artist<TAB>title<TAB>status<TAB>positionMs<TAB>endMs<TAB>updatedUnixMs<TAB>coverSeq}, {@code N} for
     * no Spotify session, {@code E} for a failed read.
     */
    static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            $parentPid = [int]$env:K560_SPOTIFY_PARENT
            $coverPath = $env:K560_SPOTIFY_COVER
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            Add-Type -AssemblyName System.Drawing
            $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, [Type]$type) {
              $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
              if (-not $task.Wait(3000)) { throw 'timeout' }
              $task.Result
            }
            $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
            $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
            $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
            $mgr = Await ($mgrType::RequestAsync()) $mgrType
            function Clean([string]$s) { if ($null -eq $s) { '' } else { $s -replace "[`t`r`n]", ' ' } }
            function SaveCover($props) {
              if ([string]::IsNullOrEmpty($coverPath)) { return $false }
              $ref = $props.Thumbnail
              if ($null -eq $ref) { return $false }
              $ras = Await ($ref.OpenReadAsync()) $streamType
              $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($ras)
              try {
                $img = [System.Drawing.Image]::FromStream($net)
                try {
                  $bmp = [System.Drawing.Bitmap]::new($img, 64, 64)
                  try {
                    $tmp = $coverPath + '.tmp'
                    $bmp.Save($tmp, [System.Drawing.Imaging.ImageFormat]::Png)
                  } finally { $bmp.Dispose() }
                  Move-Item -Force -LiteralPath $tmp -Destination $coverPath
                } finally { $img.Dispose() }
              } finally { $net.Dispose() }
              return $true
            }
            $lastKey = $null
            $seq = 0
            $retryAt = 0
            $i = 0
            while ($true) {
              if (($i % 10) -eq 0 -and $parentPid -gt 0 -and -not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { exit 0 }
              $i++
              $line = 'N'
              try {
                $s = $null
                foreach ($x in $mgr.GetSessions()) { if ($x.SourceAppUserModelId -like '*Spotify*') { $s = $x; break } }
                if ($null -ne $s) {
                  $props = Await ($s.TryGetMediaPropertiesAsync()) $propsType
                  $tl = $s.GetTimelineProperties()
                  $pb = $s.GetPlaybackInfo()
                  $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                  $key = [string]$props.Artist + '|' + [string]$props.Title
                  if ($key -ne $lastKey) {
                    $lastKey = $key
                    $retryAt = $nowMs + 2500
                    try { if (SaveCover $props) { $seq++ } } catch { }
                  } elseif ($retryAt -ne 0 -and $nowMs -ge $retryAt) {
                    $retryAt = 0
                    try { if (SaveCover $props) { $seq++ } } catch { }
                  }
                  $line = "S`t" + (Clean $props.Artist) + "`t" + (Clean $props.Title) + "`t" + [int]$pb.PlaybackStatus + "`t" + [long]$tl.Position.TotalMilliseconds + "`t" + [long]$tl.EndTime.TotalMilliseconds + "`t" + $tl.LastUpdatedTime.ToUnixTimeMilliseconds() + "`t" + $seq
                }
              } catch { $line = 'E' }
              [Console]::Out.WriteLine($line)
              [Console]::Out.Flush()
              Start-Sleep -Milliseconds 500
            }
            """;
}
