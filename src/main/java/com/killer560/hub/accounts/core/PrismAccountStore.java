package com.killer560.hub.accounts.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Microsoft accounts Prism Launcher already has stored, without ever writing back to
 * Prism's own accounts.json. Only the fields needed to re-run the login flow are extracted;
 * Prism's cached (and usually stale, ~24h-lived) session tokens are ignored in favor of always
 * refreshing from the long-lived MSA refresh_token.
 */
public final class PrismAccountStore {

    private PrismAccountStore() {
    }

    public static Path defaultAccountsFile() {
        String override = System.getProperty("prismaccountswitcher.accountsFile");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return prismRootDir().resolve("accounts.json");
    }

    /** Prism's own data directory - shared by every instance, unlike an instance's own folder. */
    public static Path prismRootDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : System.getProperty("user.home"), "PrismLauncher");
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "PrismLauncher");
        } else {
            return Paths.get(System.getProperty("user.home"), ".local", "share", "PrismLauncher");
        }
    }

    public static List<PrismAccount> load() throws IOException {
        return load(defaultAccountsFile());
    }

    public static List<PrismAccount> load(Path accountsFile) throws IOException {
        List<PrismAccount> result = new ArrayList<>();
        if (!Files.exists(accountsFile)) {
            return result;
        }
        String json = Files.readString(accountsFile);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement accountsEl = root.get("accounts");
        if (accountsEl == null || !accountsEl.isJsonArray()) {
            return result;
        }
        for (JsonElement el : accountsEl.getAsJsonArray()) {
            JsonObject account = el.getAsJsonObject();
            String type = optString(account, "type");
            if (type != null && !"MSA".equals(type)) {
                continue;
            }
            JsonObject profile = account.getAsJsonObject("profile");
            JsonObject msa = account.getAsJsonObject("msa");
            if (profile == null || msa == null) {
                continue;
            }
            String uuid = optString(profile, "id");
            String name = optString(profile, "name");
            String clientId = optString(account, "msa-client-id");
            String refreshToken = optString(msa, "refresh_token");
            if (uuid == null || name == null || clientId == null || refreshToken == null) {
                continue;
            }
            result.add(new PrismAccount(uuid, name, clientId, refreshToken));
        }
        return result;
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }
}
