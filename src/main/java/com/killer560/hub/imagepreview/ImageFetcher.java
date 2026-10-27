package com.killer560.hub.imagepreview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network and disk half of Image Preview. Every call blocks, so it only ever runs on {@link ImagePreviewFeature}'s
 * worker threads, never the render thread.
 *
 * <p>Safety: http and https only; at most {@link #MAX_REDIRECTS} redirects, each one checked against the same host rule
 * as the link; never a private, loopback or link-local address (a chat link must not make the game poke the player's
 * router); {@link #MAX_BYTES} per picture, refused from the Content-Length or as soon as the body passes it; a
 * connect, header and whole-body deadline; no cookies (the client has no cookie handler) and no account data. Every
 * request goes through {@link ModNet}, so the testkit's offline switch and service fakes apply.
 */
final class ImageFetcher {

    private static final Logger LOGGER = ModLog.get("killer560smod-imagepreview");

    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** A page read for its og:image stops here: the tag is in the head. */
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    private static final int MAX_API_BYTES = 2 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(10);
    private static final long BODY_DEADLINE_MS = 20_000;

    /** imgur's own web client id (public, in imgur.com's page script): their post API answers albums and single pages
     *  with it, no account and no key of ours. Used only for imgur.com page and album links. */
    private static final String IMGUR_CLIENT_ID = "546c25a59c58ad7";
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static final Pattern OG_IMAGE = Pattern.compile(
            "(?i)<meta[^>]{0,200}?(?:property|name)\\s*=\\s*[\"'](?:og:image|og:image:url|og:image:secure_url|twitter:image)[\"'][^>]{0,200}?content\\s*=\\s*[\"']([^\"'<>]{1,2000})[\"']");
    private static final Pattern OG_IMAGE_REV = Pattern.compile(
            "(?i)<meta[^>]{0,200}?content\\s*=\\s*[\"']([^\"'<>]{1,2000})[\"'][^>]{0,200}?(?:property|name)\\s*=\\s*[\"'](?:og:image|og:image:url|og:image:secure_url|twitter:image)[\"']");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-imagepreview-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Requests actually sent (test hook: a cache hit must not move it). */
    static final AtomicInteger REQUESTS = new AtomicInteger();

    private ImageFetcher() {
    }

    /** A failure worth showing in the preview ("too large", "HTTP 404"...). */
    static final class FetchException extends IOException {
        FetchException(String message) {
            super(message);
        }
    }

    /** The answer was not a picture (and is not one we look inside): show nothing. */
    static final class NotAnImage extends IOException {
        NotAnImage(String message) {
            super(message);
        }
    }

    record Body(byte[] bytes, String contentType, String finalUrl) {
    }

    // ------------------------------------------------------------------------------------------------- disk cache

    static Path cacheDir() {
        return ModPaths.config("killer560smod-imagepreview-cache");
    }

    private static String key(String prefix, String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(d, 0, 20);
        } catch (Exception e) {
            return prefix + Integer.toHexString(url.hashCode());
        }
    }

    private static byte[] cacheRead(String name) {
        try {
            Path p = cacheDir().resolve(name);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            byte[] b = Files.readAllBytes(p);
            // Least recently USED: a read moves the file to the back of the queue.
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static void cacheWrite(String name, byte[] bytes) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            trimCache(ImagePreviewConfig.getInstance().getCacheSizeMb() * 1024L * 1024L);
        } catch (Exception e) {
            LOGGER.warn("[ImagePreview] couldn't write the cache: {}", e.toString());
        }
    }

    /** Deletes the least recently used files until the folder is under {@code capBytes}. */
    static synchronized void trimCache(long capBytes) {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                    total += Files.size(p);
                }
            }
        } catch (IOException e) {
            return;
        }
        if (total <= capBytes) {
            return;
        }
        files.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
            } catch (IOException e) {
                return 0;
            }
        });
        for (Path p : files) {
            if (total <= capBytes) {
                break;
            }
            try {
                long size = Files.size(p);
                Files.delete(p);
                total -= size;
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- picture

    /** The bytes of the picture at {@code url}, from the disk cache when there. */
    static byte[] picture(String url, boolean anyHost) throws IOException {
        String name = key("img-", url);
        byte[] cached = cacheRead(name);
        if (cached != null) {
            return cached;
        }
        Body body = get(url, anyHost, MAX_BYTES, "image/png,image/jpeg,image/gif,image/webp,image/bmp;q=0.9,*/*;q=0.5");
        if (body.finalUrl().toLowerCase(Locale.ROOT).endsWith("i.imgur.com/removed.png")) {
            throw new FetchException("Removed from imgur");
        }
        if (ImageDecoding.sniff(body.bytes()) == null) {
            throw new NotAnImage(body.contentType());
        }
        cacheWrite(name, body.bytes());
        return body.bytes();
    }

    /** The page's og:image (or twitter:image) address, or null when it has none. */
    static String ogImage(String pageUrl, boolean anyHost) throws IOException {
        String name = key("og-", pageUrl);
        byte[] cached = cacheRead(name);
        if (cached != null) {
            String s = new String(cached, StandardCharsets.UTF_8);
            return s.isEmpty() ? null : s;
        }
        Body body = get(pageUrl, anyHost, MAX_PAGE_BYTES, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
        String found = null;
        if (ImageDecoding.sniff(body.bytes()) != null) {
            found = body.finalUrl(); // the "page" was a picture after all
        } else if (body.contentType() != null && body.contentType().toLowerCase(Locale.ROOT).contains("html")) {
            String html = new String(body.bytes(), StandardCharsets.UTF_8);
            Matcher m = OG_IMAGE.matcher(html);
            Matcher r = OG_IMAGE_REV.matcher(html);
            String raw = m.find() ? m.group(1) : r.find() ? r.group(1) : null;
            if (raw != null) {
                raw = raw.replace("&amp;", "&").trim();
                try {
                    found = URI.create(body.finalUrl()).resolve(raw).toString();
                } catch (Exception ignored) {
                    found = null;
                }
            }
        }
        cacheWrite(name, (found == null ? "" : found).getBytes(StandardCharsets.UTF_8));
        return found;
    }

    /** The picture addresses behind an imgur page or album (one for a page). */
    static List<String> imgur(ImageLinks.Target target) throws IOException {
        String name = key("imgur-", target.kind() + "/" + target.id());
        byte[] cached = cacheRead(name);
        if (cached != null) {
            List<String> urls = new ArrayList<>();
            for (String line : new String(cached, StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    urls.add(line.trim());
                }
            }
            if (!urls.isEmpty()) {
                return urls;
            }
        }
        List<String> urls = new ArrayList<>();
        IOException failure = null;
        // A gallery link may be a post with one picture: try it as an album first, then as one picture.
        String[] kinds = target.kind() == ImageLinks.Kind.IMGUR_ALBUM ? new String[]{"albums", "media"} : new String[]{"media"};
        for (String k : kinds) {
            try {
                String api = "https://api.imgur.com/post/v1/" + k + "/" + target.id() + "?client_id=" + IMGUR_CLIENT_ID
                        + "&include=media";
                Body body = get(api, false, MAX_API_BYTES, "application/json");
                JsonObject obj = JsonParser.parseString(new String(body.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray media = obj.has("media") && obj.get("media").isJsonArray() ? obj.getAsJsonArray("media") : new JsonArray();
                for (JsonElement e : media) {
                    if (!e.isJsonObject() || urls.size() >= 100) {
                        continue;
                    }
                    JsonObject m = e.getAsJsonObject();
                    String id = m.has("id") ? m.get("id").getAsString() : null;
                    String url = m.has("url") ? m.get("url").getAsString() : null;
                    String type = m.has("type") ? m.get("type").getAsString() : "image";
                    if ("video".equalsIgnoreCase(type) && id != null) {
                        url = "https://i.imgur.com/" + id + ".gif";
                    }
                    if (url != null && ImageLinks.isImgurHost(String.valueOf(ImageLinks.hostOf(url)))) {
                        urls.add(url);
                    }
                }
                if (!urls.isEmpty()) {
                    break;
                }
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new FetchException("imgur answered something unreadable");
            }
        }
        if (urls.isEmpty() && target.kind() == ImageLinks.Kind.IMGUR_MEDIA) {
            // imgur serves any picture at i.imgur.com/<id>.png in its own format.
            urls.add("https://i.imgur.com/" + target.id() + ".png");
        }
        if (urls.isEmpty() && failure != null) {
            // The API failed (the shared client id may be dead): the album's page still names its cover picture in
            // og:image, a direct i.imgur.com link. Not cached, so the full album comes back once the API answers.
            String cover = imgurPageCover(target.id());
            if (cover != null) {
                LOGGER.warn("imgur API failed ({}), showing the album's cover picture only", failure.getMessage());
                return List.of(cover);
            }
        }
        if (urls.isEmpty()) {
            throw failure != null ? failure : new FetchException("Empty imgur album");
        }
        cacheWrite(name, String.join("\n", urls).getBytes(StandardCharsets.UTF_8));
        return urls;
    }

    /** The cover picture an imgur album or gallery page names in its og:image tag (an i.imgur.com address), or null. */
    static String imgurPageCover(String id) {
        for (String page : new String[]{"https://imgur.com/a/" + id, "https://imgur.com/gallery/" + id}) {
            try {
                String cover = ogImage(page, false);
                if (cover != null && "i.imgur.com".equals(ImageLinks.hostOf(cover))) {
                    int q = cover.indexOf('?');
                    return q < 0 ? cover : cover.substring(0, q);
                }
            } catch (IOException | RuntimeException ignored) {
                // try the next page form
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------- HTTP

    /** The address the request really goes to: imgur's and Discord's hosts go through their {@link ModNet} services, so
     *  the testkit can stand fakes in for them. Unchanged in play. */
    static String route(String url) {
        String host = ImageLinks.hostOf(url);
        if (host == null) {
            return url;
        }
        return switch (host) {
            case "i.imgur.com" -> ModNet.url("imgur-i", url);
            case "api.imgur.com" -> ModNet.url("imgur-api", url);
            case "imgur.com", "www.imgur.com", "m.imgur.com" -> ModNet.url("imgur", url);
            case "cdn.discordapp.com" -> ModNet.url("discord-cdn", url);
            case "media.discordapp.net" -> ModNet.url("discord-media", url);
            default -> url;
        };
    }

    /** True when {@code host} resolves to a private, loopback, link-local or otherwise local address. */
    static boolean isPrivate(String host) throws IOException {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            throw new FetchException("Unknown host");
        }
        for (InetAddress a : all) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || a.isMulticastAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            if (a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique local
            }
            if (b.length == 4 && isPrivateV4(b, 0)) {
                return true;
            }
            if (b.length == 16) {
                // An IPv6 address that carries an IPv4 one (security audit 2026-10-27): NAT64 64:ff9b::/96, 6to4
                // 2002::/16 and IPv4-compatible ::a.b.c.d reach the embedded address, so a private one counts too.
                boolean nat64 = (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF
                        && (b[3] & 0xFF) == 0x9B && allZero(b, 4, 12);
                boolean compat = allZero(b, 0, 12);
                if ((nat64 || compat) && isPrivateV4(b, 12)) {
                    return true;
                }
                if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02 && isPrivateV4(b, 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The IPv4 ranges InetAddress's own tests miss, plus the ones it covers (for an address embedded in IPv6). */
    private static boolean isPrivateV4(byte[] b, int o) {
        int a0 = b[o] & 0xFF, a1 = b[o + 1] & 0xFF, a2 = b[o + 2] & 0xFF;
        return a0 == 0                                   // 0.0.0.0/8 "this network"
                || a0 == 10 || a0 == 127                 // private, loopback
                || (a0 == 100 && (a1 & 0xC0) == 64)      // 100.64.0.0/10 carrier-grade NAT
                || (a0 == 169 && a1 == 254)              // link-local
                || (a0 == 172 && (a1 & 0xF0) == 16)      // 172.16.0.0/12
                || (a0 == 192 && a1 == 168)              // 192.168.0.0/16
                || (a0 == 192 && a1 == 0 && a2 == 0)     // 192.0.0.0/24 protocol assignments
                || (a0 == 198 && (a1 & 0xFE) == 18)      // 198.18.0.0/15 benchmarking
                || a0 >= 224;                            // multicast, reserved, broadcast
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static Body get(String url, boolean anyHost, long cap, String accept) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!ImageLinks.allowedHost(current, anyHost)) {
                throw new FetchException(hop == 0 ? "Host not allowed" : "Redirected to a host that is not allowed");
            }
            URI original = URI.create(current);
            String routed = route(current);
            URI target = URI.create(routed);
            String host = original.getHost().toLowerCase(Locale.ROOT);
            // A routed address is a harness fake; a test host is named by the harness. Anything else must be public.
            if (routed.equals(current) && !ImageLinks.isTestHost(host, original.getPort()) && isPrivate(host)) {
                throw new FetchException("Local addresses are never fetched");
            }
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(HEADER_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            REQUESTS.incrementAndGet();
            try {
                response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new FetchException("Timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Canceled");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new FetchException("HTTP " + status);
                }
                // Resolved against the address as the link gave it, so a relative redirect stays on the link's host.
                current = original.resolve(location.trim()).toString();
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new FetchException("HTTP " + status);
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (length > cap) {
                response.body().close();
                throw new FetchException(tooLarge(length, cap));
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            return new Body(readCapped(response.body(), cap, cap == MAX_PAGE_BYTES), type, current);
        }
        throw new FetchException("Too many redirects");
    }

    static String tooLarge(long bytes, long cap) {
        return String.format(Locale.US, "Too large (%.1f MB, limit %d MB)", bytes / 1048576.0, cap / 1048576);
    }

    /** Reads at most {@code cap} bytes, under a whole-body deadline. {@code truncateOk} keeps the first {@code cap} bytes
     *  instead of refusing (a page's head is enough). */
    private static byte[] readCapped(InputStream in, long cap, boolean truncateOk) throws IOException {
        ScheduledFuture<?> deadline = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }, BODY_DEADLINE_MS, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        try (in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + (long) n > cap) {
                    if (truncateOk) {
                        out.write(buf, 0, (int) (cap - out.size()));
                        break;
                    }
                    throw new FetchException(String.format(Locale.US, "Too large (over %d MB)", cap / 1048576));
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            if (System.currentTimeMillis() - start >= BODY_DEADLINE_MS - 50) {
                throw new FetchException("Timed out");
            }
            throw e;
        } finally {
            deadline.cancel(false);
        }
    }
}
