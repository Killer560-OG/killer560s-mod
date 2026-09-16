package com.killer560.hub.proxy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Singleton holding the proxy configuration. Persisted as JSON in the Fabric
 * config directory ({@code config/proxyclient.json}) and used to build the
 * Netty {@link ProxyHandler} that is inserted into the client's outbound
 * channel pipeline.
 */
public final class ProxyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("proxyclient.json");

    private static ProxyConfig instance;

    /** Universal proxy (2026-09-15, killer560: "if I select it then it'll use that proxy no matter what instance or
     *  what the predefined one is"). Lives in Prism's shared data folder so every instance reads the same file;
     *  re-read whenever the file changes so toggling it in one instance applies to the others' next connection. */
    private static final Path UNIVERSAL_PATH = com.killer560.hub.accounts.core.PrismAccountStore.prismRootDir()
            .resolve("prismaccountswitcher").resolve("universal-proxy.json");
    private static ProxyConfig universal;
    private static long universalMtime = Long.MIN_VALUE;

    /** Where this config is saved - the per-instance file or the shared universal one. Not serialized. */
    private transient Path path = CONFIG_PATH;

    // ---- Persisted fields (serialized by Gson) ----
    private ProxyType type = ProxyType.SOCKS5;
    private String host = "";
    private int port = 1080;
    private String username = "";
    private String password = "";
    private boolean enabled = false;

    private ProxyConfig() {
    }

    public static ProxyConfig getInstance() {
        if (instance == null) {
            instance = new ProxyConfig();
        }
        return instance;
    }

    // ---- Persistence ----

    /**
     * Loads config from disk, replacing the current singleton. Missing or
     * malformed files fall back to defaults (and are left untouched).
     */
    public static void load() {
        instance = readFrom(CONFIG_PATH);
    }

    /** The shared universal proxy, re-read from disk if another instance changed it. Never null. */
    public static synchronized ProxyConfig universal() {
        long mtime;
        try {
            mtime = Files.exists(UNIVERSAL_PATH) ? Files.getLastModifiedTime(UNIVERSAL_PATH).toMillis() : -1L;
        } catch (IOException e) {
            mtime = -1L;
        }
        if (universal == null || mtime != universalMtime) {
            universal = readFrom(UNIVERSAL_PATH);
            universalMtime = mtime;
        }
        return universal;
    }

    /** The proxy a new connection should use: the universal one when it's on, otherwise this instance's. */
    public static synchronized ProxyConfig effective() {
        // One universal() read, tested on that same object: calling universal() twice (once for u, once inside
        // isUniversalActive) could re-read the file in between and return a stale, disabled u while the new file is
        // on - the netty thread would then connect with NO proxy. Synchronized so the choice is atomic vs the toggles.
        ProxyConfig u = universal();
        return u.isEnabled() && u.hasValidAddress() ? u : getInstance();
    }

    /** True when the universal proxy is actually in force (on AND has an address) - same test as {@link #effective()}. */
    public static boolean isUniversalActive() {
        ProxyConfig u = universal();
        return u.isEnabled() && u.hasValidAddress();
    }

    // ---- Universal toggle (2026-09-15, killer560: "For universal that should just be a toggle on or off not open any
    // menu. Remove the edit universal button.") There is no separate universal editor anymore: the universal file is
    // always a published copy of an instance proxy. ----

    /**
     * Turns the universal proxy ON by publishing this instance's proxy settings (type/host/port/credentials) into the
     * shared universal file, so every instance connects through it.
     *
     * @return false - and changes nothing - when this instance's proxy has no valid address to publish.
     */
    public static synchronized boolean enableUniversalFromInstance() {
        ProxyConfig inst = getInstance();
        if (!inst.hasValidAddress()) {
            return false;
        }
        ProxyConfig u = universal();
        u.copyConnectionFrom(inst);
        u.setEnabled(true);
        u.save();
        return true;
    }

    /** Turns the universal proxy OFF (settings kept in the file, just disabled). */
    public static synchronized void disableUniversal() {
        ProxyConfig u = universal();
        u.setEnabled(false);
        u.save();
    }

    /**
     * Called after this instance's proxy is saved from its editor ("Set Instance Proxy"): while the universal proxy is
     * ON, mirror the new settings into it so it can still be edited without a separate universal editor. Clearing the
     * instance address therefore also switches universal off. No-op while universal is OFF. Deliberately NOT called
     * from {@link #applyAccountProfile} - an account swap changes only this instance's proxy; universal still wins.
     */
    public static synchronized void syncUniversalFromInstance() {
        ProxyConfig u = universal();
        if (!u.isEnabled()) {
            return;
        }
        u.copyConnectionFrom(getInstance());
        u.setEnabled(u.hasValidAddress());
        u.save();
    }

    /** Copies the connection settings (not {@code enabled}, not the save path) from {@code source}. */
    private void copyConnectionFrom(ProxyConfig source) {
        this.type = source.type;
        this.host = source.host;
        this.port = source.port;
        this.username = source.username;
        this.password = source.password;
        normalize();
    }

    public boolean isUniversal() {
        return UNIVERSAL_PATH.equals(path);
    }

    private static ProxyConfig readFrom(Path file) {
        ProxyConfig fallback = new ProxyConfig();
        fallback.path = file;
        if (!Files.exists(file)) {
            return fallback;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            // Per-key reads (2026-09-15 persistence audit) instead of GSON.fromJson(whole class): one
            // malformed value (e.g. "port": "abc") used to throw and reset EVERY proxy field. Keys are
            // the same field names Gson writes in save().
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ProxyConfig loaded = new ProxyConfig();
            loaded.type = ConfigJson.getEnum(obj, "type", ProxyType.class, ProxyType.SOCKS5);
            loaded.host = ConfigJson.getString(obj, "host", "");
            loaded.port = ConfigJson.getInt(obj, "port", 1080);
            loaded.username = ConfigJson.getString(obj, "username", "");
            loaded.password = ConfigJson.getString(obj, "password", "");
            loaded.enabled = ConfigJson.getBool(obj, "enabled", false);
            loaded.normalize();
            loaded.path = file;
            return loaded;
        } catch (Exception e) {
            // Corrupt config: keep defaults rather than crashing the client.
            return fallback;
        }
    }

    /** Writes the current config to disk. Failures are swallowed (logged to stderr). */
    public void save() {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
            if (isUniversal()) {
                synchronized (ProxyConfig.class) {
                    universalMtime = Files.getLastModifiedTime(path).toMillis();
                }
            }
        } catch (IOException e) {
            System.err.println("[proxyclient] Failed to save config: " + e.getMessage());
        }
    }

    /** Guards against nulls when a hand-edited JSON omits fields. */
    private void normalize() {
        if (type == null) type = ProxyType.SOCKS5;
        if (host == null) host = "";
        if (username == null) username = "";
        if (password == null) password = "";
        if (port < 1 || port > 65535) port = 1080;
    }

    // ---- Netty handler factory ----

    /**
     * Builds a fresh {@link ProxyHandler} for the configured proxy, or
     * {@code null} if no valid host is set. A new instance must be created per
     * connection because Netty handlers are not shareable across channels.
     */
    public ProxyHandler createHandler() {
        if (host == null || host.isBlank()) {
            return null;
        }
        // Resolved locally; this is the proxy server address, not the game server.
        InetSocketAddress proxyAddress = new InetSocketAddress(host, port);
        boolean hasAuth = username != null && !username.isBlank();

        if (type == ProxyType.SOCKS4) {
            return hasAuth
                    ? new Socks4ProxyHandler(proxyAddress, username)
                    : new Socks4ProxyHandler(proxyAddress);
        } else {
            String pass = (password == null) ? "" : password;
            return hasAuth
                    ? new Socks5ProxyHandler(proxyAddress, username, pass)
                    : new Socks5ProxyHandler(proxyAddress);
        }
    }

    // ---- Address parsing helpers ----

    /**
     * Parses a "host:port" string (splitting on the last colon so IPv6-ish
     * inputs degrade gracefully) into the host/port fields. A missing or
     * invalid port defaults to 1080.
     */
    public void parseAndSetAddress(String input) {
        if (input == null) {
            return;
        }
        String trimmed = input.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon < 0) {
            this.host = trimmed;
            this.port = 1080;
            return;
        }
        this.host = trimmed.substring(0, colon).trim();
        String portPart = trimmed.substring(colon + 1).trim();
        try {
            int parsed = Integer.parseInt(portPart);
            this.port = (parsed >= 1 && parsed <= 65535) ? parsed : 1080;
        } catch (NumberFormatException e) {
            this.port = 1080;
        }
    }

    public String getAddressString() {
        if (host == null || host.isBlank()) {
            return "";
        }
        return host + ":" + port;
    }

    public boolean hasValidAddress() {
        return host != null && !host.isBlank() && port >= 1 && port <= 65535;
    }

    // ---- Accessors ----

    public ProxyType getType() {
        return type;
    }

    public void setType(ProxyType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = (username == null) ? "" : username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = (password == null) ? "" : password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Per killer560's "add a button to set a proxy by account... whenever i swap to that account it
     *  should auto swap to that proxy" request (2026-09-10), with his explicit choice that swapping to
     *  an account with no saved proxy should turn the active proxy OFF rather than leave whatever was
     *  active before - so {@code profile == null} always disables, never just leaves things alone.
     *  Called from {@code AccountSwitcherScreen#onAccountSelected} right after a successful swap. */
    public void applyAccountProfile(AccountProxyProfile profile) {
        if (profile == null) {
            this.enabled = false;
            this.host = "";
            save();
            return;
        }
        this.type = profile.getType();
        this.host = profile.getHost();
        this.port = profile.getPort();
        this.username = profile.getUsername();
        this.password = profile.getPassword();
        this.enabled = hasValidAddress();
        save();
    }
}
