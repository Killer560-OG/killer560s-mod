package com.killer560.hub.accounts.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists the last known Hypixel ban status per account UUID to a single file under Prism's own
 * data directory (a sibling of accounts.json, not inside any one instance's folder), so a result
 * observed in one instance is visible from every other instance too.
 */
public final class SharedBanStatusStore {

    private SharedBanStatusStore() {
    }

    private static Path storeFile() {
        return PrismAccountStore.prismRootDir().resolve("prismaccountswitcher").resolve("ban-status.json");
    }

    public static synchronized Map<String, StoredBanStatus> load() {
        Map<String, StoredBanStatus> result = new HashMap<>();
        Path file = storeFile();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                HypixelBanStatus.Status status = HypixelBanStatus.Status.valueOf(obj.get("status").getAsString());
                String rawMessage = obj.has("rawMessage") && !obj.get("rawMessage").isJsonNull()
                        ? obj.get("rawMessage").getAsString() : null;
                Long expiry = obj.has("estimatedExpiryEpochMillis") && !obj.get("estimatedExpiryEpochMillis").isJsonNull()
                        ? obj.get("estimatedExpiryEpochMillis").getAsLong() : null;
                long recordedAt = obj.get("recordedAtEpochMillis").getAsLong();
                result.put(entry.getKey(), new StoredBanStatus(status, rawMessage, expiry, recordedAt));
            }
        } catch (Exception ignored) {
            // Corrupt or unreadable file - treat as empty rather than failing the whole screen.
        }
        return result;
    }

    public static synchronized void recordStatus(String uuid, HypixelBanStatus status) {
        Map<String, StoredBanStatus> all = load();
        Long expiryMillis = status.estimatedExpiry() == null ? null : status.estimatedExpiry().toEpochMilli();
        all.put(uuid, new StoredBanStatus(status.status(), status.rawMessage(), expiryMillis, Instant.now().toEpochMilli()));
        save(all);
    }

    private static void save(Map<String, StoredBanStatus> all) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, StoredBanStatus> entry : all.entrySet()) {
            StoredBanStatus s = entry.getValue();
            JsonObject obj = new JsonObject();
            obj.addProperty("status", s.status().name());
            if (s.rawMessage() != null) {
                obj.addProperty("rawMessage", s.rawMessage());
            }
            if (s.estimatedExpiryEpochMillis() != null) {
                obj.addProperty("estimatedExpiryEpochMillis", s.estimatedExpiryEpochMillis());
            }
            obj.addProperty("recordedAtEpochMillis", s.recordedAtEpochMillis());
            root.add(entry.getKey(), obj);
        }
        try {
            Path file = storeFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString());
        } catch (IOException ignored) {
            // Best-effort persistence; losing one ban-status update isn't worth crashing over.
        }
    }
}
