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
                if (line.startsWith("C\t")) {
                    String[] f = line.split("\t", -1);
                    lastResult = f.length >= 3 ? f[1] + " " + f[2] : line;
                    results++;
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
            $vol = -2
            $audioReady = $false
            $fast = 0
            $stdin = [System.IO.StreamReader]::new([Console]::OpenStandardInput())
            $pending = $stdin.ReadLineAsync()
            function FindSession { foreach ($x in $mgr.GetSessions()) { if ($x.SourceAppUserModelId -like '*Spotify*') { return $x } }; return $null }
            function EnsureAudio { if (-not $script:audioReady) { Add-Type -TypeDefinition $script:audioSource -Language CSharp; $script:audioReady = $true } }
            function RunCmd([string]$cmd) {
              $parts = $cmd.Trim().Split(' ')
              $ok = $false
              if ($parts[0] -eq 'vol') { EnsureAudio; $ok = [K560.SpotifyVolume]::Set([int]$parts[1]); $script:vol = [K560.SpotifyVolume]::Get() }
              elseif ($parts[0] -eq 'volrel') { EnsureAudio; $cur = [K560.SpotifyVolume]::Get(); if ($cur -ge 0) { $ok = [K560.SpotifyVolume]::Set($cur + [int]$parts[1]) }; $script:vol = [K560.SpotifyVolume]::Get() }
              elseif ($parts[0] -eq 'volq') { EnsureAudio; $script:vol = [K560.SpotifyVolume]::Get(); $ok = $script:vol -ge 0 }
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
              "C`t" + $parts[0] + "`t" + $ok
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
                  if ($audioReady -and ($i % 4) -eq 0) { try { $vol = [K560.SpotifyVolume]::Get() } catch { $vol = -1 } }
                  $c = $pb.Controls
                  $flags = '' + [int]$c.IsPlayPauseToggleEnabled + [int]$c.IsNextEnabled + [int]$c.IsPreviousEnabled + [int]$c.IsPlaybackPositionEnabled
                  $line = $line + "`t" + $vol + "`t" + $flags
                }
              } catch { $line = 'E' }
              [Console]::Out.WriteLine($line)
              [Console]::Out.Flush()
              # Waits for the next reading, answering a command from the game at once (and reading again soon after it,
              # so a seek or a skip reaches the lyric clock within a quarter second). End of input means the game is gone.
              $waitMs = 500
              if ($fast -gt 0) { $fast--; $waitMs = 120 }
              $deadline = [Environment]::TickCount + $waitMs
              while ($true) {
                $left = $deadline - [Environment]::TickCount
                if ($left -le 0) { break }
                $got = $false
                try { $got = $pending.Wait($left) } catch { exit 0 }
                if ($got) {
                  $cmd = $pending.Result
                  if ($null -eq $cmd) { exit 0 }
                  $pending = $stdin.ReadLineAsync()
                  $res = "C`t" + $cmd + "`tFalse"
                  try { $res = RunCmd $cmd } catch { }
                  [Console]::Out.WriteLine($res)
                  [Console]::Out.Flush()
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
