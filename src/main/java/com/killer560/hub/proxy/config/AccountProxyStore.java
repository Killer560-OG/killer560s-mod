package com.killer560.hub.proxy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists each account's own assigned proxy (by uuid), separate from {@link ProxyConfig}'s single
 * global active proxy. Per killer560's "add a button to set a proxy by account... whenever i swap to
 * that account it should auto swap to that proxy" request (2026-09-10) -
 * {@code AccountSwitcherScreen#onAccountSelected} looks up the swapped-to account's uuid here every
 * time and applies whatever it finds (or explicitly clears the active proxy if this account has none
 * saved - per killer560's explicit choice, so one account's proxy never silently carries over to the
 * next).
 */
public final class AccountProxyStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-account-proxies.json");

    private static Map<String, AccountProxyProfile> cache;

    private AccountProxyStore() {
    }

    public static synchronized AccountProxyProfile get(String uuid) {
        return ensureLoaded().get(uuid);
    }

    public static synchronized void set(String uuid, AccountProxyProfile profile) {
        ensureLoaded().put(uuid, profile);
        save();
    }

    public static synchronized void remove(String uuid) {
        ensureLoaded().remove(uuid);
        save();
    }

    private static Map<String, AccountProxyProfile> ensureLoaded() {
        if (cache == null) {
            cache = load();
        }
        return cache;
    }

    private static Map<String, AccountProxyProfile> load() {
        Map<String, AccountProxyProfile> result = new HashMap<>();
        if (!Files.exists(STORE_PATH)) {
            return result;
        }
        try {
            String json = Files.readString(STORE_PATH, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                AccountProxyProfile profile = new AccountProxyProfile();
                profile.setType(obj.has("type") ? ProxyType.valueOf(obj.get("type").getAsString()) : ProxyType.SOCKS5);
                String host = obj.has("host") ? obj.get("host").getAsString() : "";
                int port = obj.has("port") ? obj.get("port").getAsInt() : 1080;
                profile.parseAndSetAddress(host + ":" + port);
                profile.setUsername(obj.has("username") ? obj.get("username").getAsString() : "");
                profile.setPassword(obj.has("password") ? obj.get("password").getAsString() : "");
                result.put(entry.getKey(), profile);
            }
        } catch (Exception ignored) {
            // Corrupt or unreadable file - treat as empty rather than failing the whole screen.
        }
        return result;
    }

    private static void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, AccountProxyProfile> entry : cache.entrySet()) {
                AccountProxyProfile p = entry.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("type", p.getType().name());
                obj.addProperty("host", p.getHost());
                obj.addProperty("port", p.getPort());
                obj.addProperty("username", p.getUsername());
                obj.addProperty("password", p.getPassword());
                root.add(entry.getKey(), obj);
            }
            Files.writeString(STORE_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
