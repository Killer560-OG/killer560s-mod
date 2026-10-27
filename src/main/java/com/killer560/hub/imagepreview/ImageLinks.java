package com.killer560.hub.imagepreview;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which chat links are pictures, and where each one's picture really is. Pure string work, no network: the fetcher
 * resolves imgur pages and albums ({@link Kind#IMGUR_MEDIA}, {@link Kind#IMGUR_ALBUM}) through imgur's API.
 */
public final class ImageLinks {

    /** Test hook: {@code -Dkiller560.test.imagePreviewHosts=127.0.0.1:PORT,...} (or set at run time) - these hosts count
     *  as trusted and may be private addresses. Read on every call. Never set outside the testkit. */
    public static final String TEST_HOSTS_PROPERTY = "killer560.test.imagePreviewHosts";

    public enum Kind {
        /** A picture file: fetch it as is. */
        DIRECT,
        /** imgur.com/&lt;id&gt; (one picture behind a page). */
        IMGUR_MEDIA,
        /** imgur.com/a/&lt;id&gt;, /gallery/..., /t/.../&lt;id&gt; (an album: several pictures). */
        IMGUR_ALBUM,
        /** Any other page, read for its og:image (Any Host only). */
        PAGE
    }

    /** One link to preview. {@code url} is the address to fetch for {@link Kind#DIRECT} and {@link Kind#PAGE}; {@code id}
     *  is the imgur id for the imgur kinds. */
    public record Target(Kind kind, String url, String id) {
    }

    /** Bounded: other players' text arrives here. Scheme optional (bare imgur/Discord links are common in chat). */
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.){1,10}[a-z]{2,24}|(?:\\d{1,3}\\.){3}\\d{1,3})"
                    + "(?::\\d{1,5})?(?:/[^\\s<>\"]{0,2000})?");
    private static final Pattern IMGUR_ID = Pattern.compile("[A-Za-z0-9]{5,10}");
    private static final List<String> IMAGE_EXT = List.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private ImageLinks() {
    }

    /** A URL found in plain text: its start and end (exclusive) in the text, and the address with a scheme. */
    public record Found(int start, int end, String url) {
    }

    /** Every link in {@code text}. A link with no scheme counts only when its host is imgur or Discord. */
    public static List<Found> find(String text) {
        List<Found> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher m = URL.matcher(text);
        while (m.find() && out.size() < 32) {
            int s = m.start();
            int e = m.end();
            // Trailing punctuation belongs to the sentence, not the link.
            while (e > s && ".,;:!?)]}'\"".indexOf(text.charAt(e - 1)) >= 0) {
                e--;
            }
            String raw = text.substring(s, e);
            String lower = raw.toLowerCase(Locale.ROOT);
            String url;
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                url = raw;
            } else {
                if (s > 0 && (Character.isLetterOrDigit(text.charAt(s - 1)) || text.charAt(s - 1) == '@')) {
                    continue; // part of a word or an e-mail address
                }
                String host = hostOf("https://" + raw);
                if (host == null || !isKnownHost(host)) {
                    continue;
                }
                url = "https://" + raw;
            }
            out.add(new Found(s, e, url));
        }
        return out;
    }

    static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isImgurHost(String host) {
        return host.equals("imgur.com") || host.endsWith(".imgur.com");
    }

    static boolean isDiscordHost(String host) {
        return host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net")
                || host.endsWith(".discordapp.net") || host.endsWith(".discordapp.com") || host.equals("cdn.discord.com");
    }

    /** imgur or Discord: the hosts fetched with Trusted Hosts Only on. */
    static boolean isKnownHost(String host) {
        return isImgurHost(host) || isDiscordHost(host);
    }

    /** A host (or host:port) named by the test hook. */
    static boolean isTestHost(String host, int port) {
        String v = System.getProperty(TEST_HOSTS_PROPERTY);
        if (v == null || v.isBlank()) {
            return false;
        }
        for (String part : v.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals(host) || p.equals(host + ":" + port)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code url}'s host may be fetched under the current host setting. */
    public static boolean allowedHost(String url, boolean anyHost) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            if (host == null || host.isEmpty()) {
                return false;
            }
            return anyHost || isKnownHost(host) || isTestHost(host, u.getPort());
        } catch (Exception e) {
            return false;
        }
    }

    /** The path's extension (lower case, no dot), or "" - the query is ignored (Discord's links are signed). */
    static String extension(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean hasImageExtension(String url) {
        try {
            return IMAGE_EXT.contains(extension(URI.create(url).getPath()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * What to fetch for {@code url}, or null when it is not a picture link we would show (wrong scheme, a Discord link to
     * a file that is not a picture, a page while Any Host is off...). The host rule is applied here too, so a null means
     * "show nothing at all".
     */
    public static Target classify(String url, boolean anyHost) {
        URI u;
        try {
            u = URI.create(url);
        } catch (Exception e) {
            return null;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return null;
        }
        String path = u.getPath() == null ? "" : u.getPath();
        String ext = extension(path);

        if (isImgurHost(host)) {
            String[] seg = path.replaceAll("^/+|/+$", "").split("/");
            if (host.equals("i.imgur.com")) {
                if (seg.length == 1 && !seg[0].isEmpty()) {
                    String file = seg[0];
                    int dot = file.indexOf('.');
                    String id = dot < 0 ? file : file.substring(0, dot);
                    if (!IMGUR_ID.matcher(id).matches()) {
                        return null;
                    }
                    if (ext.equals("gifv") || ext.equals("mp4") || ext.equals("webm")) {
                        // The video forms of an animated picture: imgur also serves it as a GIF.
                        return new Target(Kind.DIRECT, "https://i.imgur.com/" + id + ".gif", id);
                    }
                    if (IMAGE_EXT.contains(ext)) {
                        return new Target(Kind.DIRECT, "https://i.imgur.com/" + file, id);
                    }
                    if (dot < 0) {
                        return new Target(Kind.IMGUR_MEDIA, null, id);
                    }
                }
                return null;
            }
            if (seg.length >= 2 && (seg[0].equals("a") || seg[0].equals("gallery") || seg[0].equals("t"))) {
                String last = seg[seg.length - 1];
                int dash = last.lastIndexOf('-');
                String id = dash >= 0 ? last.substring(dash + 1) : last; // /gallery/some-title-AbC123x
                return IMGUR_ID.matcher(id).matches() ? new Target(Kind.IMGUR_ALBUM, null, id) : null;
            }
            if (seg.length == 1 && !seg[0].isEmpty()) {
                String file = seg[0];
                int dot = file.indexOf('.');
                String id = dot < 0 ? file : file.substring(0, dot);
                if (!IMGUR_ID.matcher(id).matches()) {
                    return null;
                }
                if (IMAGE_EXT.contains(ext)) {
                    return new Target(Kind.DIRECT, "https://i.imgur.com/" + file, id);
                }
                if (ext.equals("gifv") || ext.equals("mp4")) {
                    return new Target(Kind.DIRECT, "https://i.imgur.com/" + id + ".gif", id);
                }
                return dot < 0 ? new Target(Kind.IMGUR_MEDIA, null, id) : null;
            }
            return null;
        }
        boolean allowed = anyHost || isDiscordHost(host) || isTestHost(host, u.getPort());
        if (!allowed) {
            return null;
        }
        if (IMAGE_EXT.contains(ext)) {
            return new Target(Kind.DIRECT, url, null);
        }
        if (isDiscordHost(host)) {
            return null; // a Discord attachment that is not a picture (a .txt, a .zip...)
        }
        // A page: only worth reading for its og:image when the player allowed any host (a test host counts).
        return new Target(Kind.PAGE, url, null);
    }
}
