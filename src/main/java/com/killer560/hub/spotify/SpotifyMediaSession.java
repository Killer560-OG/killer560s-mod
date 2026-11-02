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
 * the Spotify HUD, Send Lyrics To Chat or My Lyrics In F5 is on, or while Spotify Controls need it (a control key bound,
 * or a control or the tab used in the last two minutes), stopped otherwise and when the game closes; measured 2026-10-20
 * on killer560's PC at about 0.5 s of CPU for its start-up plus eight readings. It exits on its own when the game's
 * process is gone (checked every five seconds), when its input pipe closes, or when its output pipe breaks, so it cannot
 * outlive the game. Since 2026-10-26 its input carries the controls ({@link #send}). The cover is
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
    private volatile Process process;
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
        ensureRunning(false);
    }

    /** {@code urgent}: a control the player just pressed, which starts the helper even inside the failure back-off. */
    private synchronized void ensureRunning(boolean urgent) {
        if (process != null && process.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((!urgent && now < nextStartMs) || !isWindows()) {
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
            pb.environment().put("K560_SPOTIFY_SOURCE", Integer.toString(sourceMode));
            pb.environment().put("K560_SPOTIFY_AUDIO",
                    Base64.getEncoder().encodeToString(AUDIO_SOURCE.getBytes(StandardCharsets.UTF_8)));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // stdin stays open: it carries the controls ({@link #send}); the script exits when it closes.
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
        volume = -2;
        allowed = null;
        nextStartMs = 0;
        failures = 0;
        try {
            Files.deleteIfExists(coverFile);
        } catch (Exception ignored) {
            // a temp file the OS will clear anyway
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- controls (2026-10-26, killer560: "make the spotify menu have a pause button and a skip button and rewind and
    // stuff ... also have a volume slider") ---------------------------------------------------------------------------

    /** Spotify's volume in the Windows mixer, 0-100; -1 when Spotify has no audio session; -2 when not asked yet. */
    private volatile int volume = -2;
    /** Which controls Spotify allows right now: play/pause, next, previous, seek. Null until a reading had them. */
    private volatile boolean[] allowed;
    // Test hooks (read by the testkit): the last command result line ("toggle True") and how many there were.
    private volatile String lastResult = "";
    private volatile long results;

    /** Reads the volume and control fields a reading carries after the eight timeline fields. */
    private void parseExtras(String line) {
        if (line == null || !line.startsWith("S\t")) {
            return;
        }
        String[] f = line.split("\t", -1);
        if (f.length >= 10) {
            try {
                volume = Integer.parseInt(f[8].trim());
            } catch (NumberFormatException ignored) {
                // keep the last volume
            }
            album = f.length >= 11 ? f[10].trim() : "";
            sourceApp = f.length >= 12 ? f[11].trim() : "";
            String flags = f[9].trim();
            if (flags.length() == 4) {
                boolean[] a = new boolean[4];
                for (int i = 0; i < 4; i++) {
                    a[i] = flags.charAt(i) == '1';
                }
                allowed = a;
            }
        }
    }

    /** The current track's album as Windows reports it (lrclib's /api/get can match on it), or "". */
    private volatile String album = "";

    public String album() {
        return album;
    }

    /** The player the reading came from (Windows' app id, "Spotify.exe" for the desktop app), or "" before a reading. */
    private volatile String sourceApp = "";

    public String sourceApp() {
        return sourceApp;
    }

    /** Whether the reading is the Spotify app's (or carries no app id, as an older helper's did). */
    public boolean fromSpotify() {
        String s = sourceApp;
        return s.isEmpty() || s.toLowerCase(Locale.ROOT).contains("spotify");
    }

    /** {@link SpotifyHudConfig.MusicSource} ordinal: which players the helper may follow. */
    private volatile int sourceMode = SpotifyHudConfig.MusicSource.MUSIC_PLAYERS.ordinal();

    /** Sets which players the helper follows; a running helper is told at once, a new one starts with it. Any thread. */
    public void setSourceMode(int mode) {
        if (mode == sourceMode) {
            return;
        }
        sourceMode = mode;
        if (running()) {
            send("src " + mode);
        }
    }

    /** How the volume was last read or set: "app" (Spotify's own slider), "mixer" (Windows' mixer) or "". */
    private volatile String volumeHow = "";
    // Test hook: volume commands the helper skipped because a later one was already waiting.
    private volatile long volumesSkipped;

    public String volumeHow() {
        return volumeHow;
    }

    /** Spotify's mixer volume (0-100), -1 when it has no audio session, -2 when not read yet. Any thread. */
    public int volume() {
        return volume;
    }

    /** Whether Spotify allows control {@code index} (0 play/pause, 1 next, 2 previous, 3 seek); true while unknown. */
    public boolean allows(int index) {
        boolean[] a = allowed;
        return a == null || index < 0 || index >= a.length || a[index];
    }

    /**
     * Hands one command to the helper, starting it first if needed: {@code toggle}, {@code next}, {@code prev},
     * {@code rel <ms>} (seek by that much from Spotify's own position), {@code vol <0-100>} or {@code volq}. The helper
     * answers it at once and reads Spotify again within a quarter second. Returns false when it cannot be handed over
     * (not Windows, PowerShell missing). Any thread; a pipe write, never a wait for Spotify.
     */
    public synchronized boolean send(String command) {
        ensureRunning(true);
        Process p = process;
        if (p == null || !p.isAlive()) {
            return false;
        }
        try {
            java.io.OutputStream out = p.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Whether the helper process is up. Any thread. */
    public boolean running() {
        Process p = process;
        return p != null && p.isAlive();
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
                if (process != p) {
                    break;   // stopped (or replaced) while this line was in flight: it must not repopulate latest/cover
                }
                long now = System.currentTimeMillis();
                if (line.startsWith("K\t")) {
                    volumesSkipped++;   // a volume setting a later one replaced before it ran
                    continue;
                }
                if (line.startsWith("C\t")) {
                    String[] f = line.split("\t", -1);
                    lastResult = f.length >= 3 ? f[1] + " " + f[2] : line;
                    results++;
                    if (f.length >= 5 && f[1].startsWith("vol")) {
                        if (!f[3].equals(volumeHow)) {
                            LOGGER.info("Spotify volume goes through {}", "app".equals(f[3])
                                    ? "Spotify's own slider" : "the Windows volume mixer (Spotify's slider not found)");
                        }
                        volumeHow = f[3];
                        try {
                            volume = Integer.parseInt(f[4].trim());
                        } catch (NumberFormatException ignored) {
                            // keep the last reading
                        }
                    }
                    if (f.length >= 3 && !"True".equalsIgnoreCase(f[2].trim())) {
                        LOGGER.info("Spotify control '{}' was refused (Spotify said no)", f[1]);
                    }
                    continue;
                }
                Timeline t = parse(line, now);
                parseExtras(line);
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
            if (process == p || process == null) {
                latest = null;   // a replacement reader's readings are not this one's to clear
            }
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
            $srcMode = [int]$env:K560_SPOTIFY_SOURCE
            $audioSource = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:K560_SPOTIFY_AUDIO))
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
            $inputType = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
            # PowerShell cannot bind the stream (a bare COM object) to AsStreamForRead's IInputStream parameter: the plain
            # call fails "Cannot find an overload", which is why no cover ever showed (2026-11-01). Reflection casts it.
            $asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [type[]]@($inputType))
            $mgr = Await ($mgrType::RequestAsync()) $mgrType
            function Clean([string]$s) { if ($null -eq $s) { '' } else { $s -replace "[`t`r`n]", ' ' } }
            function SaveCover($props) {
              if ([string]::IsNullOrEmpty($coverPath)) { return $false }
              $ref = $props.Thumbnail
              if ($null -eq $ref) { return $false }
              $ras = Await ($ref.OpenReadAsync()) $streamType
              $net = $asStreamForRead.Invoke($null, @($ras))
              try {
                $img = [System.Drawing.Image]::FromStream($net)
                try {
                  # A square from the middle (a browser's thumbnail is often 16:9), scaled to 64x64.
                  $side = [Math]::Min($img.Width, $img.Height)
                  $bmp = [System.Drawing.Bitmap]::new(64, 64)
                  try {
                    $g = [System.Drawing.Graphics]::FromImage($bmp)
                    try {
                      $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                      $src = [System.Drawing.Rectangle]::new([int](($img.Width - $side) / 2), [int](($img.Height - $side) / 2), $side, $side)
                      $g.DrawImage($img, [System.Drawing.Rectangle]::new(0, 0, 64, 64), $src, [System.Drawing.GraphicsUnit]::Pixel)
                    } finally { $g.Dispose() }
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
            $vol = -2
            $volHow = ''
            $audioReady = $false
            $fast = 0
            $stdin = [System.IO.StreamReader]::new([Console]::OpenStandardInput())
            $pending = $stdin.ReadLineAsync()
            function IsBrowser([string]$id) { return $id -match '(?i)chrome|msedge|firefox|opera|brave|vivaldi|^arc' }
            # Which player to follow (2026-11-01). Mode 0: the Spotify app only. Mode 1: also other players, and a browser
            # tab only when it names an album (a music site does, a plain video does not). Mode 2: anything. A playing
            # session beats a paused one, and the Spotify app beats another player of the same state.
            function FindSession {
              $best = $null
              $bestScore = -1
              foreach ($x in $mgr.GetSessions()) {
                $id = [string]$x.SourceAppUserModelId
                $isSpotify = $id -like '*Spotify*'
                if (-not $isSpotify -and $script:srcMode -le 0) { continue }
                if (-not $isSpotify -and $script:srcMode -eq 1 -and (IsBrowser $id)) {
                  $album = ''
                  try { $album = [string](Await ($x.TryGetMediaPropertiesAsync()) $propsType).AlbumTitle } catch { }
                  if ([string]::IsNullOrWhiteSpace($album)) { continue }
                }
                $score = 0
                try { if ([int]$x.GetPlaybackInfo().PlaybackStatus -eq 4) { $score += 10 } } catch { }
                if ($isSpotify) { $score += 5 }
                if ($score -gt $bestScore) { $best = $x; $bestScore = $score }
              }
              return $best
            }
            function EnsureAudio { if (-not $script:audioReady) { Add-Type -TypeDefinition $script:audioSource -Language CSharp -ReferencedAssemblies @('UIAutomationClient', 'UIAutomationTypes', 'WindowsBase'); $script:audioReady = $true } }
            # Spotify's own volume: its in-app slider when Windows' UI Automation can reach it (2026-11-01: "changing the
            # volume slider doesnt change the actual slider in spotify"), else its slider in the Windows volume mixer.
            function GetVol { $a = [K560.SpotifyAppVolume]::Get(); if ($a -ge 0) { $script:volHow = 'app'; return $a }; $script:volHow = 'mixer'; return [K560.SpotifyVolume]::Get() }
            function SetVol([int]$v) { if ([K560.SpotifyAppVolume]::Set($v)) { $script:volHow = 'app'; return $true }; $script:volHow = 'mixer'; return [K560.SpotifyVolume]::Set($v) }
            function RunCmd([string]$cmd) {
              $parts = $cmd.Trim().Split(' ')
              $ok = $false
              if ($parts[0] -eq 'vol') { EnsureAudio; $ok = SetVol ([int]$parts[1]); $script:vol = GetVol }
              elseif ($parts[0] -eq 'volrel') { EnsureAudio; $cur = GetVol; if ($cur -ge 0) { $ok = SetVol ($cur + [int]$parts[1]) }; $script:vol = GetVol }
              elseif ($parts[0] -eq 'volq') { EnsureAudio; $script:vol = GetVol; $ok = $script:vol -ge 0 }
              elseif ($parts[0] -eq 'src') { $script:srcMode = [int]$parts[1]; $script:lastKey = $null; $ok = $true }
              else {
                $s = FindSession
                if ($null -ne $s) {
                  if ($parts[0] -eq 'toggle') { $ok = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
                  elseif ($parts[0] -eq 'next') { $ok = Await ($s.TrySkipNextAsync()) ([bool]) }
                  elseif ($parts[0] -eq 'prev') { $ok = Await ($s.TrySkipPreviousAsync()) ([bool]) }
                  elseif ($parts[0] -eq 'rel') {
                    $tl = $s.GetTimelineProperties()
                    $pb = $s.GetPlaybackInfo()
                    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                    $pos = [long]$tl.Position.TotalMilliseconds
                    if ([int]$pb.PlaybackStatus -eq 4) { $pos += [Math]::Max(0, $nowMs - $tl.LastUpdatedTime.ToUnixTimeMilliseconds()) }
                    $target = [Math]::Max([long]0, $pos + [long]$parts[1])
                    $end = [long]$tl.EndTime.TotalMilliseconds
                    if ($end -gt 1000) { $target = [Math]::Min($target, $end - 1000) }
                    $ok = Await ($s.TryChangePlaybackPositionAsync([long]$target * 10000)) ([bool])
                  }
                }
              }
              $res = "C`t" + $parts[0] + "`t" + $ok
              if ($parts[0] -like 'vol*') { $res = $res + "`t" + $script:volHow + "`t" + $script:vol }
              $res
            }
            while ($true) {
              if (($i % 10) -eq 0 -and $parentPid -gt 0 -and -not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { exit 0 }
              $i++
              $line = 'N'
              try {
                $s = $null
                $s = FindSession
                if ($null -ne $s) {
                  $props = Await ($s.TryGetMediaPropertiesAsync()) $propsType
                  $tl = $s.GetTimelineProperties()
                  $pb = $s.GetPlaybackInfo()
                  $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                  $key = [string]$s.SourceAppUserModelId + '|' + [string]$props.Artist + '|' + [string]$props.Title
                  if ($key -ne $lastKey) {
                    $lastKey = $key
                    $retryAt = $nowMs + 2500
                    try { if (SaveCover $props) { $seq++ } } catch { }
                  } elseif ($retryAt -ne 0 -and $nowMs -ge $retryAt) {
                    $retryAt = 0
                    try { if (SaveCover $props) { $seq++ } } catch { }
                  }
                  $line = "S`t" + (Clean $props.Artist) + "`t" + (Clean $props.Title) + "`t" + [int]$pb.PlaybackStatus + "`t" + [long]$tl.Position.TotalMilliseconds + "`t" + [long]$tl.EndTime.TotalMilliseconds + "`t" + $tl.LastUpdatedTime.ToUnixTimeMilliseconds() + "`t" + $seq
                  if ($audioReady -and ($i % 4) -eq 0) { try { $vol = GetVol } catch { $vol = -1 } }
                  $c = $pb.Controls
                  $flags = '' + [int]$c.IsPlayPauseToggleEnabled + [int]$c.IsNextEnabled + [int]$c.IsPreviousEnabled + [int]$c.IsPlaybackPositionEnabled
                  $line = $line + "`t" + $vol + "`t" + $flags + "`t" + (Clean $props.AlbumTitle) + "`t" + (Clean $s.SourceAppUserModelId)
                }
              } catch { $line = 'E' }
              [Console]::Out.WriteLine($line)
              [Console]::Out.Flush()
              # Waits for the next reading, answering commands from the game at once (and reading again soon after, so a
              # seek or a skip reaches the lyric clock within a quarter second). End of input means the game is gone.
              # Every command already waiting is taken in one go, and of several volume settings only the LAST is applied:
              # a dragged slider sends one per step, and running each in turn left the volume seconds behind the slider
              # (2026-11-01: "making the volume quieter takes a long time to update").
              $waitMs = 500
              if ($fast -gt 0) { $fast--; $waitMs = 120 }
              $deadline = [Environment]::TickCount + $waitMs
              while ($true) {
                $left = $deadline - [Environment]::TickCount
                if ($left -le 0) { break }
                $got = $false
                try { $got = $pending.Wait($left) } catch { exit 0 }
                if ($got) {
                  $cmds = [System.Collections.Generic.List[string]]::new()
                  $eof = $false
                  while ($pending.IsCompleted) {
                    $cmd = $null
                    try { $cmd = $pending.Result } catch { $eof = $true; break }
                    if ($null -eq $cmd) { $eof = $true; break }
                    $cmds.Add($cmd)
                    $pending = $stdin.ReadLineAsync()
                  }
                  $lastVol = -1
                  for ($k = 0; $k -lt $cmds.Count; $k++) { if ($cmds[$k].StartsWith('vol ')) { $lastVol = $k } }
                  for ($k = 0; $k -lt $cmds.Count; $k++) {
                    $cmd = $cmds[$k]
                    if ($cmd.StartsWith('vol ') -and $k -ne $lastVol) { [Console]::Out.WriteLine("K`t" + $cmd); continue }
                    $res = "C`t" + $cmd + "`tFalse"
                    try { $res = RunCmd $cmd } catch { }
                    [Console]::Out.WriteLine($res)
                  }
                  [Console]::Out.Flush()
                  if ($eof) { exit 0 }
                  $fast = 3
                  break
                }
              }
            }
            """;

    /**
     * Spotify's own volume in the Windows mixer, through Core Audio (ISimpleAudioVolume on every audio session of a
     * {@code Spotify} process on the default output device). Compiled by the script's {@code Add-Type} only the first
     * time the game asks about the volume, so a player who never touches it never pays for the compile. Needs no Spotify
     * login and no Premium: it is the same slider as Windows' volume mixer. -1 when Spotify has no audio session yet
     * (it opens one the first time it plays sound).
     */
    static final String AUDIO_SOURCE = """
            using System;
            using System.Collections.Generic;
            using System.Diagnostics;
            using System.Runtime.InteropServices;
            using System.Windows.Automation;
            namespace K560 {
              [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom { }
              [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IMMDeviceEnumerator {
                [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
                [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
              }
              [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IMMDevice {
                [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
              }
              [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IAudioSessionManager2 {
                [PreserveSig] int GetAudioSessionControl(IntPtr a, int b, out IntPtr c);
                [PreserveSig] int GetSimpleAudioVolume(IntPtr a, int b, out IntPtr c);
                [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator e);
              }
              [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IAudioSessionEnumerator {
                [PreserveSig] int GetCount(out int n);
                [PreserveSig] int GetSession(int i, out IAudioSessionControl2 s);
              }
              [ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IAudioSessionControl2 {
                [PreserveSig] int GetState(out int s);
                [PreserveSig] int GetDisplayName(out IntPtr p);
                [PreserveSig] int SetDisplayName(IntPtr a, IntPtr b);
                [PreserveSig] int GetIconPath(out IntPtr p);
                [PreserveSig] int SetIconPath(IntPtr a, IntPtr b);
                [PreserveSig] int GetGroupingParam(out Guid g);
                [PreserveSig] int SetGroupingParam(IntPtr a, IntPtr b);
                [PreserveSig] int RegisterAudioSessionNotification(IntPtr a);
                [PreserveSig] int UnregisterAudioSessionNotification(IntPtr a);
                [PreserveSig] int GetSessionIdentifier(out IntPtr p);
                [PreserveSig] int GetSessionInstanceIdentifier(out IntPtr p);
                [PreserveSig] int GetProcessId(out uint pid);
              }
              [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface ISimpleAudioVolume {
                [PreserveSig] int SetMasterVolume(float level, ref Guid ctx);
                [PreserveSig] int GetMasterVolume(out float level);
              }
              /**
               * Spotify's own in-app volume slider, through Windows UI Automation (2026-11-01). Spotify is a Chromium app;
               * its volume bar carries an accessible slider (a range from 0 to 1) that UI Automation can read and set
               * without focusing or clicking anything. Found once and kept; looked for again at most every 10 s while it
               * cannot be found (Spotify closed to the tray has no window to look in). -1 / false when it is not there,
               * and the caller falls back to the Windows mixer.
               */
              public static class SpotifyAppVolume {
                static AutomationElement slider;
                static DateTime nextFind = DateTime.MinValue;
                static RangeValuePattern Pattern() {
                  if (slider != null) {
                    try { return (RangeValuePattern)slider.GetCurrentPattern(RangeValuePattern.Pattern); } catch { slider = null; }
                  }
                  if (DateTime.UtcNow < nextFind) return null;
                  nextFind = DateTime.UtcNow.AddSeconds(10);
                  foreach (var p in Process.GetProcessesByName("Spotify")) {
                    IntPtr h;
                    try { h = p.MainWindowHandle; } catch { continue; }
                    if (h == IntPtr.Zero) continue;
                    AutomationElementCollection all;
                    try {
                      all = AutomationElement.FromHandle(h).FindAll(TreeScope.Descendants,
                          new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Slider));
                    } catch { continue; }
                    // The slider named for volume; failing that (another language), the ONE writable slider whose
                    // range is 0..1 (the song's progress bar is in milliseconds). Two such sliders: neither, so a seek
                    // can never be mistaken for the volume.
                    AutomationElement unit = null;
                    int units = 0;
                    foreach (AutomationElement s in all) {
                      try {
                        object pat;
                        if (!s.TryGetCurrentPattern(RangeValuePattern.Pattern, out pat)) continue;
                        var rv = (RangeValuePattern)pat;
                        if (rv.Current.IsReadOnly) continue;
                        string name = s.Current.Name ?? "";
                        if (name.IndexOf("volume", StringComparison.OrdinalIgnoreCase) >= 0) {
                          slider = s;
                          return rv;
                        }
                        if (rv.Current.Maximum <= 1.0001 && rv.Current.Minimum >= -0.0001 && rv.Current.Maximum > 0) {
                          unit = s;
                          units++;
                        }
                      } catch { }
                    }
                    if (units == 1) {
                      try {
                        slider = unit;
                        return (RangeValuePattern)unit.GetCurrentPattern(RangeValuePattern.Pattern);
                      } catch { slider = null; }
                    }
                  }
                  return null;
                }
                public static int Get() {
                  try {
                    var rv = Pattern();
                    if (rv == null) return -1;
                    double min = rv.Current.Minimum, max = rv.Current.Maximum;
                    if (max <= min) return -1;
                    return (int)Math.Round((rv.Current.Value - min) * 100 / (max - min));
                  } catch { slider = null; return -1; }
                }
                public static bool Set(int percent) {
                  try {
                    var rv = Pattern();
                    if (rv == null) return false;
                    double min = rv.Current.Minimum, max = rv.Current.Maximum;
                    if (max <= min) return false;
                    rv.SetValue(min + (max - min) * Math.Max(0, Math.Min(100, percent)) / 100.0);
                    return true;
                  } catch { slider = null; return false; }
                }
              }
              public static class SpotifyVolume {
                static List<ISimpleAudioVolume> Find() {
                  var list = new List<ISimpleAudioVolume>();
                  var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
                  IMMDevice dev;
                  if (en.GetDefaultAudioEndpoint(0, 1, out dev) != 0 || dev == null) return list;
                  Guid iid = typeof(IAudioSessionManager2).GUID;
                  object o;
                  if (dev.Activate(ref iid, 23, IntPtr.Zero, out o) != 0 || o == null) return list;
                  IAudioSessionEnumerator se;
                  if (((IAudioSessionManager2)o).GetSessionEnumerator(out se) != 0 || se == null) return list;
                  int n;
                  se.GetCount(out n);
                  for (int i = 0; i < n; i++) {
                    IAudioSessionControl2 c;
                    if (se.GetSession(i, out c) != 0 || c == null) continue;
                    uint pid;
                    c.GetProcessId(out pid);
                    if (pid == 0) continue;
                    string name;
                    try { name = Process.GetProcessById((int)pid).ProcessName; } catch { continue; }
                    if (!string.Equals(name, "Spotify", StringComparison.OrdinalIgnoreCase)) continue;
                    var v = c as ISimpleAudioVolume;
                    if (v != null) list.Add(v);
                  }
                  return list;
                }
                public static int Get() {
                  foreach (var v in Find()) { float f; if (v.GetMasterVolume(out f) == 0) return (int)Math.Round(f * 100); }
                  return -1;
                }
                public static bool Set(int percent) {
                  float f = Math.Max(0, Math.Min(100, percent)) / 100f;
                  Guid g = Guid.Empty;
                  bool ok = false;
                  foreach (var v in Find()) { if (v.SetMasterVolume(f, ref g) == 0) ok = true; }
                  return ok;
                }
              }
            }
            """;
}
