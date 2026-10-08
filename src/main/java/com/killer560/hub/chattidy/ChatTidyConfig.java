package com.killer560.hub.chattidy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Chat Hider settings (killer560, 2026-10-08: "Combine hide chat and tidy chat into one setting called Chat
 * Hider"). One master switch over every rule: Chat Tidy's Stack Duplicate Messages and Hide Damage Messages (see
 * {@link ChatTidy}), and the six chat hides that were Object Hider's "Chat Replacements" (the "Hide Chat Messages" tab;
 * matched in {@code objecthider/ObjectHiderFeature}). Hide Damage Messages alone hides both ability-damage and
 * incoming-hit lines; the two family switches are gone.
 * <p>
 * The file keeps Chat Tidy's name ({@code killer560smod-chattidy.json}). A file without the {@code enabled} key was
 * written before Chat Hider existed, and is migrated once (see {@link #load()}): the master is ON if either old feature
 * had anything on, Chat Tidy's two settings carry over from this file, and the six hides are read from Object Hider's
 * file, which no longer writes them. The result is saved at once, so the next Object Hider save cannot lose them.
 */
public final class ChatTidyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chattidy.json");
    /** Where Hide Chat Messages' six switches lived until 2026-10-08. */
    private static final Path OBJECT_HIDER_PATH = ModPaths.config("killer560smod-objecthider.json");
    static final String[] HIDE_CHAT_KEYS = {"hideUselessMessages", "hideDiscordWarnings", "hideMicrosoftWarnings",
            "hideEmptyChatMessages", "hideActionbar", "hideNonRankInvites"};

    private static volatile ChatTidyConfig instance;

    private boolean enabled = false;
    private boolean stackDuplicates = false;
    private boolean hideDamageMessages = false;
    private boolean hideUselessMessages = false;
    private boolean hideDiscordWarnings = false;
    private boolean hideMicrosoftWarnings = false;
    private boolean hideEmptyChatMessages = false;
    private boolean hideActionbar = false;
    private boolean hideNonRankInvites = false;

    private ChatTidyConfig() {
    }

    public static ChatTidyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        ChatTidyConfig cfg = new ChatTidyConfig();
        boolean migrate = true;
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.stackDuplicates = ConfigJson.getBool(obj, "stackDuplicates", false);
            cfg.hideDamageMessages = ConfigJson.getBool(obj, "hideDamageMessages", false);
            if (obj.has("enabled")) {
                migrate = false;
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.hideUselessMessages = ConfigJson.getBool(obj, "hideUselessMessages", false);
                cfg.hideDiscordWarnings = ConfigJson.getBool(obj, "hideDiscordWarnings", false);
                cfg.hideMicrosoftWarnings = ConfigJson.getBool(obj, "hideMicrosoftWarnings", false);
                cfg.hideEmptyChatMessages = ConfigJson.getBool(obj, "hideEmptyChatMessages", false);
                cfg.hideActionbar = ConfigJson.getBool(obj, "hideActionbar", false);
                cfg.hideNonRankInvites = ConfigJson.getBool(obj, "hideNonRankInvites", false);
            }
        }
        if (migrate) {
            // Decided from the OLD files' keys only (docs/LESSONS.md: never from fields a carry-over already filled).
            JsonObject old = readObjectHider();
            boolean anyHideChat = false;
            if (old != null) {
                cfg.hideUselessMessages = ConfigJson.getBool(old, "hideUselessMessages", false);
                cfg.hideDiscordWarnings = ConfigJson.getBool(old, "hideDiscordWarnings", false);
                cfg.hideMicrosoftWarnings = ConfigJson.getBool(old, "hideMicrosoftWarnings", false);
                cfg.hideEmptyChatMessages = ConfigJson.getBool(old, "hideEmptyChatMessages", false);
                cfg.hideActionbar = ConfigJson.getBool(old, "hideActionbar", false);
                cfg.hideNonRankInvites = ConfigJson.getBool(old, "hideNonRankInvites", false);
                for (String k : HIDE_CHAT_KEYS) {
                    anyHideChat |= ConfigJson.getBool(old, k, false);
                }
            }
            boolean anyTidy = obj != null && (ConfigJson.getBool(obj, "stackDuplicates", false)
                    || ConfigJson.getBool(obj, "hideDamageMessages", false));
            cfg.enabled = anyHideChat || anyTidy;
            instance = cfg;
            if (obj != null || old != null) {
                cfg.save();
            }
            return;
        }
        instance = cfg;
    }

    private static JsonObject readObjectHider() {
        if (!Files.exists(OBJECT_HIDER_PATH)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(OBJECT_HIDER_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("stackDuplicates", stackDuplicates);
            obj.addProperty("hideDamageMessages", hideDamageMessages);
            obj.addProperty("hideUselessMessages", hideUselessMessages);
            obj.addProperty("hideDiscordWarnings", hideDiscordWarnings);
            obj.addProperty("hideMicrosoftWarnings", hideMicrosoftWarnings);
            obj.addProperty("hideEmptyChatMessages", hideEmptyChatMessages);
            obj.addProperty("hideActionbar", hideActionbar);
            obj.addProperty("hideNonRankInvites", hideNonRankInvites);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- gated (what the features read): master AND rule AND Skyblock Only ------------------------------------

    private boolean on(boolean rule) {
        return enabled && rule && SkyblockGate.allows();
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isStackDuplicates() {
        return on(stackDuplicates);
    }

    /** Hide Damage Messages: ability-damage AND incoming-hit lines (one switch since 2026-10-08). */
    public boolean isHideDamageMessages() {
        return on(hideDamageMessages);
    }

    public boolean isHideUselessMessages() {
        return on(hideUselessMessages);
    }

    public boolean isHideDiscordWarnings() {
        return on(hideDiscordWarnings);
    }

    public boolean isHideMicrosoftWarnings() {
        return on(hideMicrosoftWarnings);
    }

    public boolean isHideEmptyChatMessages() {
        return on(hideEmptyChatMessages);
    }

    public boolean isHideActionbar() {
        return on(hideActionbar);
    }

    public boolean isHideNonRankInvites() {
        return on(hideNonRankInvites);
    }

    // ---- raw (what the settings menu shows) -----------------------------------------------------------------

    public boolean getEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean getStackDuplicatesRaw() {
        return stackDuplicates;
    }

    public void setStackDuplicates(boolean v) {
        stackDuplicates = v;
    }

    public boolean getHideDamageMessagesRaw() {
        return hideDamageMessages;
    }

    public void setHideDamageMessages(boolean v) {
        hideDamageMessages = v;
    }

    public boolean getHideUselessMessagesRaw() {
        return hideUselessMessages;
    }

    public void setHideUselessMessages(boolean v) {
        hideUselessMessages = v;
    }

    public boolean getHideDiscordWarningsRaw() {
        return hideDiscordWarnings;
    }

    public void setHideDiscordWarnings(boolean v) {
        hideDiscordWarnings = v;
    }

    public boolean getHideMicrosoftWarningsRaw() {
        return hideMicrosoftWarnings;
    }

    public void setHideMicrosoftWarnings(boolean v) {
        hideMicrosoftWarnings = v;
    }

    public boolean getHideEmptyChatMessagesRaw() {
        return hideEmptyChatMessages;
    }

    public void setHideEmptyChatMessages(boolean v) {
        hideEmptyChatMessages = v;
    }

    public boolean getHideActionbarRaw() {
        return hideActionbar;
    }

    public void setHideActionbar(boolean v) {
        hideActionbar = v;
    }

    public boolean getHideNonRankInvitesRaw() {
        return hideNonRankInvites;
    }

    public void setHideNonRankInvites(boolean v) {
        hideNonRankInvites = v;
    }
}
