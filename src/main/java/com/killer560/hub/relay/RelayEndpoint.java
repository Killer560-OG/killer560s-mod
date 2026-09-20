package com.killer560.hub.relay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Where the relay lives, and how its three URLs are built from that one base.
 * <p>
 * <b>This is the single place the relay's address is defined.</b> {@link #DEFAULT_BASE_URL} is a deliberate
 * placeholder until the Worker in {@code killer560s-mod-relay} is actually deployed - change that one constant
 * and every install that never touched the setting picks the real host up. Anyone who HAS typed a URL into the
 * Mod Chat tab keeps theirs (it is persisted in {@code killer560smod-modchat.json}).
 * <p>
 * While the constant is still the placeholder, {@link #isUsable} is false and the client never opens a socket
 * at all - no DNS lookups, no retry loop, no error spam. The feature simply reports "no relay URL set".
 */
public final class RelayEndpoint {

    /** Placeholder - replace with the deployed Worker URL (e.g. {@code https://relay.<subdomain>.workers.dev}). */
    public static final String DEFAULT_BASE_URL = "https://relay-url-not-set.invalid";

    private RelayEndpoint() {
    }

    /** Trailing slashes dropped so "https://x/" and "https://x" are the same endpoint (and the same cached token). */
    public static String normalise(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** @return true only for a real http(s) URL that isn't still the not-yet-deployed placeholder. */
    public static boolean isUsable(String baseUrl) {
        String base = normalise(baseUrl);
        if (base.isEmpty() || base.equalsIgnoreCase(DEFAULT_BASE_URL)) {
            return false;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            return URI.create(base).getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static URI challenge(String baseUrl, String uuid) {
        return URI.create(normalise(baseUrl) + "/challenge?uuid=" + enc(uuid));
    }

    public static URI auth(String baseUrl) {
        return URI.create(normalise(baseUrl) + "/auth");
    }

    /** {@code https} -> {@code wss}, {@code http} -> {@code ws}; the Worker only speaks WebSocket on {@code /ws}. */
    public static URI websocket(String baseUrl, String room, String token) {
        String base = normalise(baseUrl);
        String ws = base.regionMatches(true, 0, "https://", 0, 8)
                ? "wss://" + base.substring(8)
                : "ws://" + base.substring(7);
        return URI.create(ws + "/ws?room=" + enc(room) + "&token=" + enc(token));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
