package com.killer560.hub.autoupdate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoupdate.AutoUpdateConfig.Channel;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Auto Update's network half, with no Minecraft in it: ask GitHub for the channel's release, pick the asset that
 * matches this jar's Minecraft version and variant, and stage it - only when the release was built from a different
 * commit, only from this repository's own release downloads, and only when its SHA-256 matches the release's
 * {@code SHA256SUMS.txt}. Installing is {@link SwapHelper}'s job, after the game has exited.
 *
 * <p>A release carries, besides its jars, {@code SHA256SUMS.txt} ({@code sha256sum} format) and {@code commit.txt}
 * (the 40-hex commit the jars were built from): dev-build.yml writes both for the Beta channel, and
 * {@code tools/release-assets.py} for a full release (CLAUDE.md, "Releases"). A release without them - every release
 * before 2026-10-11 - is never installed.
 *
 * <p>Hosts: {@code github-api} (api.github.com) and {@code github-download} (github.com, the release downloads), each
 * read on every call through {@link ModNet} so the testkit can fake both at run time (docs/TESTING-HOOKS.md).
 */
public final class AutoUpdater {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoupdate");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String REPO = "Killer560-OG/killer560s-mod";
    public static final String BETA_TAG = "dev-latest";
    public static final String SUMS_ASSET = "SHA256SUMS.txt";
    public static final String COMMIT_ASSET = "commit.txt";
    /** Written by deploy-to-instances.py into each instance's game folder: that instance never auto-updates. */
    public static final String MARKER_FILE = "killer560smod-no-auto-update";
    public static final String STAGED_FILE = "staged.json";
    public static final String INSTALLED_FILE = "installed.json";
    /** A real jar is about 11 MB; anything past this is not one of ours. */
    static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

    public enum Status {
        /** A gate said no before anything touched the network. */
        BLOCKED,
        /** The release was built from this jar's commit. */
        UP_TO_DATE,
        /** Downloaded and verified this run. */
        STAGED,
        /** The same release was already downloaded and verified earlier; nothing downloaded. */
        ALREADY_STAGED,
        NO_RELEASE,
        NO_ASSET,
        /** An asset URL that is not this repository's release download. */
        BAD_SOURCE,
        NO_CHECKSUM,
        CHECKSUM_MISMATCH,
        ERROR
    }

    /** One check: what this jar is, and where its downloads go. */
    public record Request(Channel channel, boolean testingBuild, Path gameDir, String minecraft, String variant,
                          String currentCommit, Path stagingDir) {
    }

    /** What a check found. {@code asset}, {@code remoteCommit}, {@code notes} and {@code jar} may be null. */
    public record Outcome(Status status, String detail, String asset, String remoteCommit, String tag, String notes,
                          Path jar) {
        static Outcome of(Status s, String detail) {
            return new Outcome(s, detail, null, null, null, null, null);
        }

        @Override
        public String toString() {
            return status + (detail == null ? "" : " " + detail) + (asset == null ? "" : " asset=" + asset)
                    + (remoteCommit == null ? "" : " commit=" + remoteCommit);
        }
    }

    private AutoUpdater() {
    }

    // ---- gates -------------------------------------------------------------------------------------------------

    /** Why this request must not touch the network at all, or {@code null} when it may. */
    public static String blockedReason(Request r) {
        if (r.testingBuild()) {
            return "testing build";
        }
        if (r.gameDir() != null && Files.exists(r.gameDir().resolve(MARKER_FILE))) {
            return "instance managed by deploy-to-instances.py (" + MARKER_FILE + ")";
        }
        if (r.channel() == null || r.channel() == Channel.OFF) {
            return "Auto Update is Off";
        }
        if (r.currentCommit() == null || !r.currentCommit().matches("[0-9a-f]{40}")) {
            return "this jar has no build commit";
        }
        if (r.minecraft() == null || r.variant() == null) {
            return "this jar has no Minecraft version or variant";
        }
        return null;
    }

    // ---- URLs ----------------------------------------------------------------------------------------------------

    static String releaseApiUrl(Channel channel) {
        String path = channel == Channel.BETA ? "/releases/tags/" + BETA_TAG : "/releases/latest";
        return ModNet.url("github-api", "https://api.github.com/repos/" + REPO + path);
    }

    /** Every download must start with this: this repository's release downloads and nothing else. */
    public static String allowedDownloadPrefix() {
        return ModNet.url("github-download", "https://github.com/" + REPO + "/releases/download/");
    }

    /** The jar asset name for a Minecraft version and variant: {@code killer560smod-<mod>-<mc>-<variant>.jar}. */
    public static boolean assetMatches(String name, String minecraft, String variant) {
        if (name == null || !name.startsWith("killer560smod-") || name.contains("-testing")) {
            return false;
        }
        return name.endsWith("-" + minecraft + "-" + variant + ".jar");
    }

    static boolean sameCommit(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        a = a.trim().toLowerCase(Locale.ROOT);
        b = b.trim().toLowerCase(Locale.ROOT);
        int n = Math.min(a.length(), b.length());
        return n >= 7 && a.substring(0, n).equals(b.substring(0, n));
    }

    // ---- the check -------------------------------------------------------------------------------------------------

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Blocking; call it off the render thread. Never throws. */
    public static Outcome check(Request r) {
        String blocked = blockedReason(r);
        if (blocked != null) {
            return Outcome.of(Status.BLOCKED, blocked);
        }
        try {
            return checkUnguarded(r);
        } catch (Exception e) {
            LOGGER.warn("[AutoUpdate] check failed: {}", e.toString());
            return Outcome.of(Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** {@link #check} with plain arguments, for the testkit's reflection. Returns {@link Outcome#toString()}. */
    public static String checkForTest(String channel, boolean testingBuild, String gameDir, String minecraft,
                                      String variant, String commit, String stagingDir) {
        Outcome o = check(new Request(Channel.parse(channel), testingBuild, gameDir == null ? null : Path.of(gameDir),
                minecraft, variant, commit, Path.of(stagingDir)));
        return o.toString();
    }

    private static Outcome checkUnguarded(Request r) throws Exception {
        HttpClient http = client();
        String api = releaseApiUrl(r.channel());
        HttpResponse<String> res = ModNet.send(http, HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            return Outcome.of(Status.NO_RELEASE, "no " + r.channel().label + " release");
        }
        if (res.statusCode() != 200) {
            return Outcome.of(Status.ERROR, "GitHub API returned " + res.statusCode());
        }
        JsonObject rel = JsonParser.parseString(res.body()).getAsJsonObject();
        boolean prerelease = rel.has("prerelease") && rel.get("prerelease").getAsBoolean();
        boolean draft = rel.has("draft") && rel.get("draft").getAsBoolean();
        String tag = rel.has("tag_name") ? rel.get("tag_name").getAsString() : "?";
        if (draft || (r.channel() == Channel.FULL_RELEASE && prerelease)
                || (r.channel() == Channel.BETA && !BETA_TAG.equals(tag))) {
            return Outcome.of(Status.NO_RELEASE, "release " + tag + " is not a " + r.channel().label + " release");
        }
        String notes = rel.has("body") && !rel.get("body").isJsonNull() ? rel.get("body").getAsString() : "";

        // The three assets this needs, each from this repository's own release downloads.
        String prefix = allowedDownloadPrefix();
        JsonObject jarAsset = null;
        String sumsUrl = null;
        String commitUrl = null;
        JsonArray assets = rel.has("assets") ? rel.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") ? a.get("name").getAsString() : "";
            String url = a.has("browser_download_url") ? a.get("browser_download_url").getAsString() : "";
            if (assetMatches(name, r.minecraft(), r.variant())) {
                if (jarAsset != null) {
                    return Outcome.of(Status.NO_ASSET, "two assets match " + r.minecraft() + " " + r.variant());
                }
                jarAsset = a;
            } else if (SUMS_ASSET.equals(name)) {
                sumsUrl = url;
            } else if (COMMIT_ASSET.equals(name)) {
                commitUrl = url;
            }
        }
        if (jarAsset == null) {
            return Outcome.of(Status.NO_ASSET, "no " + r.minecraft() + " " + r.variant() + " jar in " + tag);
        }
        String assetName = jarAsset.get("name").getAsString();
        String jarUrl = jarAsset.has("browser_download_url") ? jarAsset.get("browser_download_url").getAsString() : "";
        for (String u : new String[]{jarUrl, sumsUrl, commitUrl}) {
            if (u != null && !(u.startsWith(prefix) && u.length() > prefix.length() && !u.contains(".."))) {
                LOGGER.warn("[AutoUpdate] refused {}: not under {}", u, prefix);
                return new Outcome(Status.BAD_SOURCE, "refused " + u, assetName, null, tag, null, null);
            }
        }
        if (commitUrl == null) {
            return new Outcome(Status.NO_ASSET, "no " + COMMIT_ASSET + " in " + tag, assetName, null, tag, null, null);
        }
        if (sumsUrl == null) {
            return new Outcome(Status.NO_CHECKSUM, "no " + SUMS_ASSET + " in " + tag, assetName, null, tag, null, null);
        }

        String remoteCommit = fetchSmallText(http, commitUrl).trim().toLowerCase(Locale.ROOT);
        if (!remoteCommit.matches("[0-9a-f]{7,40}")) {
            return new Outcome(Status.ERROR, COMMIT_ASSET + " is not a commit", assetName, null, tag, null, null);
        }
        if (sameCommit(remoteCommit, r.currentCommit())) {
            return new Outcome(Status.UP_TO_DATE, "already on " + shortOf(remoteCommit), assetName, remoteCommit,
                    tag, null, null);
        }

        String expected = expectedSha(fetchSmallText(http, sumsUrl), assetName);
        if (expected == null) {
            return new Outcome(Status.NO_CHECKSUM, "no checksum for " + assetName, assetName, remoteCommit, tag,
                    null, null);
        }

        Path dir = r.stagingDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(assetName);
        JsonObject staged = readJson(dir.resolve(STAGED_FILE));
        if (staged != null && Files.isRegularFile(target)
                && sameCommit(str(staged, "commit"), remoteCommit)
                && expected.equals(str(staged, "sha256")) && expected.equals(sha256(target))) {
            return new Outcome(Status.ALREADY_STAGED, "already downloaded", assetName, remoteCommit, tag, notes,
                    target);
        }

        // A different release replaces whatever was staged: one staged jar at most.
        clearStaging(dir);
        Path part = dir.resolve(assetName + ".part");
        String actual;
        try {
            actual = download(http, jarUrl, part);
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (!expected.equals(actual)) {
            Files.deleteIfExists(part);
            LOGGER.warn("[AutoUpdate] {} rejected: SHA-256 {} but {} lists {}", assetName, actual, SUMS_ASSET, expected);
            return new Outcome(Status.CHECKSUM_MISMATCH, "got " + actual + ", expected " + expected, assetName,
                    remoteCommit, tag, null, null);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);

        JsonObject info = new JsonObject();
        info.addProperty("commit", remoteCommit);
        info.addProperty("asset", assetName);
        info.addProperty("sha256", expected);
        info.addProperty("tag", tag);
        info.addProperty("channel", r.channel().name());
        info.addProperty("notes", notes);
        Files.writeString(dir.resolve(STAGED_FILE), GSON.toJson(info), StandardCharsets.UTF_8);
        LOGGER.info("[AutoUpdate] staged {} ({}, {}), SHA-256 verified", assetName, shortOf(remoteCommit), tag);
        return new Outcome(Status.STAGED, "verified", assetName, remoteCommit, tag, notes, target);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static String shortOf(String commit) {
        return commit != null && commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    /** The SHA-256 a {@code sha256sum}-format file lists for {@code name}, lower case, or {@code null}. */
    static String expectedSha(String sums, String name) {
        for (String line : sums.split("\\R")) {
            String t = line.trim();
            if (t.length() < 66) {
                continue;
            }
            String hash = t.substring(0, 64).toLowerCase(Locale.ROOT);
            String rest = t.substring(64).trim();
            if (rest.startsWith("*")) {
                rest = rest.substring(1);
            }
            if (hash.matches("[0-9a-f]{64}") && rest.equals(name)) {
                return hash;
            }
        }
        return null;
    }

    private static String fetchSmallText(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> res = ModNet.send(http, HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException(url + " returned " + res.statusCode());
        }
        String body = res.body();
        if (body.length() > 64 * 1024) {
            throw new IOException(url + " is too large for a text asset");
        }
        return body;
    }

    /** Streams {@code url} into {@code out}, returning its SHA-256. */
    private static String download(HttpClient http, String url, Path out) throws Exception {
        HttpResponse<InputStream> res = ModNet.send(http, HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        // HttpRequest.timeout covers only the wait for the response headers, so a connection that stalls mid-body
        // would block this thread (and keep "checking" true) forever: close the stream when the five minutes are up.
        java.util.Timer watchdog = new java.util.Timer("killer560smod-auto-update-watchdog", true);
        try (InputStream in = res.body()) {
            watchdog.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }, Duration.ofMinutes(5).toMillis());
            if (res.statusCode() != 200) {
                throw new IOException(url + " returned " + res.statusCode());
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buf = new byte[1 << 16];
            try (OutputStream os = Files.newOutputStream(out)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_JAR_BYTES) {
                        throw new IOException(url + " is larger than " + MAX_JAR_BYTES + " bytes");
                    }
                    md.update(buf, 0, n);
                    os.write(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } finally {
            watchdog.cancel();
        }
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes staged jars and staged.json (never installed.json or the helper's log). */
    static void clearStaging(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> doomed;
        try (Stream<Path> s = Files.list(dir)) {
            doomed = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") || n.endsWith(".jar.part") || n.equals(STAGED_FILE);
            }).toList();
        }
        for (Path p : doomed) {
            Files.deleteIfExists(p);
        }
    }

    public static JsonObject readJson(Path p) {
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
