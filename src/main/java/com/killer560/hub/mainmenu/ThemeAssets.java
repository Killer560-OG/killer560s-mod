package com.killer560.hub.mainmenu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoupdate.AutoUpdater;
import com.killer560.hub.menuthemes.ThemeExtrasConfig;
import com.killer560.hub.menuthemes.ThemeMusic;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * The main menu's big theme files on demand (themedl-1104, killer560 2026-11-04): the jar holds only a small preview of
 * each painted picture (Amber's, the first-boot default, at medium quality), and the full WebP and every theme's music are
 * GitHub release assets, downloaded the first time the main menu shows that theme and kept ONCE per PC in the shared folder
 * ({@code %USERPROFILE%\.killer560smod\shared\theme-assets\}, never %APPDATA%). The jar's {@code theme-assets.json}
 * (written by tools/theme-assets.py) names each file's sha256 and size; a download that does not match is thrown away.
 * A cached file is named by its hash, so it is only ever written once, checked, and is trusted afterwards.
 *
 * <p>With no custom title screen (the themed main menu off) nothing is ever downloaded. Downloaded files are never deleted
 * by the mod on its own; Menu Theme's Delete Unused Downloads removes the ones not in use, and those come back if picked.
 * Everything here that touches the disk or the network runs on one background thread; {@link #ready} and {@link #request}
 * are map lookups, safe every frame.
 */
public final class ThemeAssets {

    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");
    /** The ModNet service key (testkit fakes it): github.com release downloads. */
    public static final String SERVICE = "theme-assets";
    /** A failed download is tried again no sooner than this. */
    private static final long RETRY_NANOS = 120_000_000_000L;

    public record Asset(String name, String sha256, long size) {
    }

    private enum State { PENDING, READY, MISSING, FAILED }

    private static volatile Map<String, Asset> assets;
    private static volatile String release = "";
    private static final Map<String, State> STATE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    /** Asked for while the start-up scan still had it queued: downloaded if the scan does not find it. */
    private static final Set<String> WANTED = ConcurrentHashMap.newKeySet();
    private static volatile ExecutorService io;
    private static volatile int version;

    // testkit evidence
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static final AtomicInteger REJECTED = new AtomicInteger();
    private static volatile String lastResult = "";

    private ThemeAssets() {
    }

    /** The manifest's assets by name, read from the jar once; empty when it is missing or unreadable. */
    static Map<String, Asset> assets() {
        Map<String, Asset> a = assets;
        if (a == null) {
            synchronized (ThemeAssets.class) {
                a = assets;
                if (a == null) {
                    a = new LinkedHashMap<>();
                    try (InputStream in = ThemeAssets.class.getResourceAsStream("/assets/killer560smod/theme-assets.json")) {
                        if (in == null) throw new IOException("theme-assets.json is not in the jar");
                        JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        release = o.get("release").getAsString();
                        for (JsonElement e : o.getAsJsonArray("assets")) {
                            JsonObject x = e.getAsJsonObject();
                            Asset asset = new Asset(x.get("name").getAsString(), x.get("sha256").getAsString(), x.get("size").getAsLong());
                            a.put(asset.name(), asset);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.warn("[MainMenu] no theme asset manifest, so no theme downloads: {}", e.toString());
                    }
                    assets = a;
                }
            }
        }
        return a;
    }

    /** The cache folder, one per PC (a test client's own sandbox in a testkit run). */
    public static Path dir() { return SharedRecords.root().resolve("theme-assets"); }

    /** Where {@code a} is kept: its name with the first 12 hex digits of its hash, {@code amber_aurora-901466a6ac66.webp}. */
    static Path file(Asset a) {
        String n = a.name();
        int dot = n.lastIndexOf('.');
        return dir().resolve(n.substring(0, dot) + "-" + a.sha256().substring(0, 12) + n.substring(dot));
    }

    /** Bumped whenever an asset becomes ready or goes, so a caller can cache what it resolved. */
    public static int version() { return version; }

    /** The downloaded, checked file for {@code name}, or null (not downloaded, still checking, or not an asset). Never
     *  touches the disk. */
    public static Path ready(String name) {
        Asset a = name == null ? null : assets().get(name);
        return a != null && STATE.get(name) == State.READY ? file(a) : null;
    }

    /** {@link #ready}, but looks at the disk itself when the answer is not known yet (marking a cached file ready).
     *  Blocking: background threads only (WebpPictures' preload, so a cached picture is decoded once, not preview first). */
    static Path cachedNow(String name) {
        Asset a = name == null ? null : assets().get(name);
        if (a == null) return null;
        if (STATE.get(name) != State.READY) {
            Path f = file(a);
            try {
                if (!Files.isRegularFile(f) || Files.size(f) != a.size()) return null;
            } catch (IOException e) {
                return null;
            }
            set(name, State.READY);
        }
        return file(a);
    }

    /** Downloads may happen: the custom title screen is on (with it off - "no custom title screen" - nothing ever is). */
    static boolean allowed() { return MainMenuTheme.active(); }

    /** Looks every asset up in the cache, in the background (client init), so {@link #ready} knows a cached one at once. */
    public static void scan() {
        for (Asset a : assets().values()) {
            if (STATE.putIfAbsent(a.name(), State.PENDING) == null) {
                executor().execute(() -> fetch(a, false));
            }
        }
    }

    /** Starts downloading {@code name} if it is an asset that is not cached, not on its way, and downloads are allowed. A
     *  map lookup when there is nothing to do, so it may run every frame. */
    public static void request(String name) {
        Asset a = name == null ? null : assets().get(name);
        if (a == null) return;
        State s = STATE.get(name);
        if (s == State.READY) return;
        if (s == State.PENDING) {
            if (!WANTED.contains(name) && allowed()) WANTED.add(name);
            return;
        }
        if (s == State.FAILED && System.nanoTime() - FAILED_AT.getOrDefault(name, 0L) < RETRY_NANOS) return;
        if (!allowed()) return;
        STATE.put(name, State.PENDING);
        executor().execute(() -> fetch(a, true));
    }

    /** Requests what {@code look} shows and plays: its painted picture and, with Theme Music on, its theme's own loop. */
    public static void requestLook(MainMenuLook look) {
        if (look == null || !allowed()) return;
        request(pictureName(MainMenuScenes.pictureFor(look)));
        if (ThemeExtrasConfig.getInstance().isMusicOn()) {
            request(ThemeMusic.assetFor(look.theme()));
        }
    }

    /** The asset name of a picture id, {@code killer560smod:textures/gui/amber_aurora.webp} -> {@code amber_aurora.webp}. */
    static String pictureName(net.minecraft.resources.Identifier id) {
        if (id == null) return null;
        String p = id.getPath();
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static ExecutorService executor() {
        ExecutorService e = io;
        if (e == null) {
            synchronized (ThemeAssets.class) {
                e = io;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "killer560smod-theme-assets");
                        t.setDaemon(true);
                        return t;
                    });
                    io = e;
                }
            }
        }
        return e;
    }

    /** Background thread: the cached copy if it is there, else (when {@code download}) the release asset, checked. */
    private static void fetch(Asset a, boolean download) {
        Path target = file(a);
        if (cachedNow(a.name()) != null) return;
        if (!WANTED.remove(a.name()) && !download) {
            STATE.remove(a.name(), State.PENDING);
            return;
        }
        if (!allowed()) { // switched to no custom title screen while this waited
            STATE.remove(a.name(), State.PENDING);
            return;
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        String url = ModNet.url(SERVICE, "https://github.com/" + AutoUpdater.REPO + "/releases/download/" + release + "/" + a.name());
        try {
            Files.createDirectories(target.getParent());
            DOWNLOADS.incrementAndGet();
            String sha = AutoUpdater.download(AutoUpdater.client(), url, part, a.size());
            if (!a.sha256().equalsIgnoreCase(sha) || Files.size(part) != a.size()) {
                REJECTED.incrementAndGet();
                throw new IOException("checksum mismatch (got " + sha + ", " + Files.size(part) + " bytes)");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            lastResult = "downloaded " + a.name();
            LOGGER.info("[MainMenu] downloaded theme asset {} ({} bytes)", a.name(), a.size());
            set(a.name(), State.READY);
        } catch (Exception e) {
            lastResult = "failed " + a.name() + ": " + e;
            FAILED_AT.put(a.name(), System.nanoTime());
            set(a.name(), State.FAILED);
            if (WARNED.add(a.name())) {
                LOGGER.warn("[MainMenu] could not download theme asset {} ({}); the preview shows meanwhile", a.name(), e.toString());
            }
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
        }
    }

    private static void set(String name, State s) {
        STATE.put(name, s);
        version++;
    }

    /** What the main menu uses now (its picture and its theme's loop), by asset name; read on the render thread. */
    static Set<String> inUse() {
        Set<String> out = new HashSet<>();
        MainMenuLook look = MainMenuLook.current();
        String pic = pictureName(MainMenuScenes.pictureFor(look));
        if (pic != null) out.add(pic);
        String music = ThemeMusic.assetFor(look.theme());
        if (music != null) out.add(music);
        return out;
    }

    /**
     * Menu Theme's Delete Unused Downloads (after it asked): deletes every downloaded file except what the main menu uses
     * now (older versions of a file included), in the background. Completes with {count, bytes}. A deleted theme downloads
     * again if it is picked.
     */
    public static CompletableFuture<long[]> deleteUnused() {
        Set<Path> keep = new HashSet<>();
        for (String name : inUse()) {
            Asset a = assets().get(name);
            if (a != null) keep.add(file(a).getFileName());
        }
        return CompletableFuture.supplyAsync(() -> {
            long count = 0;
            long bytes = 0;
            Path dir = dir();
            if (!Files.isDirectory(dir)) return new long[]{0, 0};
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path f : files) {
                    if (!Files.isRegularFile(f) || keep.contains(f.getFileName()) || f.getFileName().toString().endsWith(".part")) {
                        continue;
                    }
                    long size = Files.size(f);
                    Files.delete(f);
                    count++;
                    bytes += size;
                }
            } catch (IOException e) {
                LOGGER.warn("[MainMenu] could not delete every unused theme download: {}", e.toString());
            }
            for (Asset a : assets().values()) {
                if (!keep.contains(file(a).getFileName()) && STATE.remove(a.name()) != null) {
                    version++;
                }
            }
            LOGGER.info("[MainMenu] deleted {} unused theme downloads ({} bytes)", count, bytes);
            return new long[]{count, bytes};
        }, executor());
    }

    // ---- test hooks (killer560s-mod-testkit 1104-logic-themedl-*)

    /** Network downloads started since start-up (or the last reset). */
    public static int downloadsForTest() { return DOWNLOADS.get(); }

    /** Downloads thrown away for a wrong hash or size. */
    public static int rejectedForTest() { return REJECTED.get(); }

    public static String lastResultForTest() { return lastResult; }

    /** An asset's state ("READY", "PENDING", "FAILED", "MISSING" for none). */
    public static String stateForTest(String name) {
        State s = STATE.get(name);
        return s == null ? "MISSING" : s.name();
    }

    /** The cache file an asset is kept in. */
    public static Path fileForTest(String name) {
        Asset a = assets().get(name);
        return a == null ? null : file(a);
    }

    public static java.util.List<String> namesForTest() { return java.util.List.copyOf(assets().keySet()); }

    /** Forgets every state and the counters (the next request or scan looks again); waits for queued work first. */
    public static void resetForTest() throws Exception {
        executor().submit(() -> { }).get();
        STATE.clear();
        FAILED_AT.clear();
        WANTED.clear();
        WARNED.clear();
        DOWNLOADS.set(0);
        REJECTED.set(0);
        lastResult = "";
        version++;
    }

    /** Waits until the background thread has done everything queued so far. */
    public static void awaitIdleForTest() throws Exception { executor().submit(() -> { }).get(); }
}
