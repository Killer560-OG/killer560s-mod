package com.killer560.hub.proxy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
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
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ProxyConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            ProxyConfig loaded = GSON.fromJson(json, ProxyConfig.class);
            instance = (loaded != null) ? loaded : new ProxyConfig();
            instance.normalize();
        } catch (Exception e) {
            // Corrupt config: keep defaults rather than crashing the client.
            instance = new ProxyConfig();
        }
    }

    /** Writes the current config to disk. Failures are swallowed (logged to stderr). */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
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
}
