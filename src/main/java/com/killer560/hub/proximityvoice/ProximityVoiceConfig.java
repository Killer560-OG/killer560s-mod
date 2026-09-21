package com.killer560.hub.proximityvoice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Proximity Voice settings - see {@link ProximityVoiceFeature}. Ships disabled by default. */
public final class ProximityVoiceConfig {

    /** Who can hear you / who you can hear - killer560, 2026-09-20: "Add toggles so I can set it up to be
     *  able to hear for instance everyone in my lobby, but my chat only goes to people in my party" - two
     *  independent scopes, not one combined mode. {@code PARTY} is the real Hypixel {@code /party} roster
     *  ({@link com.killer560.hub.leapmenu.PartyTracker}); {@code LOBBY} is everyone currently in your
     *  dungeon instance, party or not (there is no wider "server-wide" option - killer560's own
     *  clarification: "the global should only work for people in your lobby"). */
    public enum VoiceScope {
        PARTY, LOBBY
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-proximityvoice.json");

    private static ProximityVoiceConfig instance;

    private boolean enabled = false;
    /** true = push-to-talk (needs {@link #pushToTalkKeyCode}), false = open mic (only {@link #mutedSelf}
     *  gates it). */
    private boolean pushToTalk = true;
    /** A {@link KeyUtil} bind code - keyboard OR mouse button, {@link KeyUtil#NONE} = unbound. */
    private int pushToTalkKeyCode = KeyUtil.NONE;
    private double maxRange = 40.0;
    private float outputVolume = 1.0f;
    private boolean mutedSelf = false;
    /** Empty means "system default" - never an index (killer560, 2026-09-20: indices reorder when USB
     *  devices come and go, so the saved choice has to survive that). */
    private String microphoneDeviceName = "";
    /** Who receives your voice. Default PARTY: matches what this feature could actually already do before
     *  scopes existed (peer discovery has only ever traveled over Party Chat), so turning this mod on for
     *  the first time changes nothing about who can hear you until the player opts into LOBBY. */
    private VoiceScope talkScope = VoiceScope.PARTY;
    /** Who you can hear. Default PARTY for the same reason as {@link #talkScope}. */
    private VoiceScope listenScope = VoiceScope.PARTY;
    /** Only matters for a LOBBY-scoped peer who ISN'T also a real party member - a real party member never
     *  falls off with distance regardless of this (killer560, 2026-09-20: "not based on distance if you
     *  are in a party"). Default true: preserves the distance behavior this feature always had. */
    private boolean lobbyFalloffEnabled = true;
    /** "New HUD, toggleable, PARTY MEMBERS ONLY" (killer560, 2026-09-20) - default OFF like every new
     *  setting until he's confirmed it live. */
    private boolean showPartyVoiceHud = false;

    private ProximityVoiceConfig() {
    }

    public static ProximityVoiceConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ProximityVoiceConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ProximityVoiceConfig cfg = new ProximityVoiceConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.pushToTalk = ConfigJson.getBool(obj, "pushToTalk", true);
            // sanitizeBind (not the key-only sanitize): a saved mouse-button code must survive a reload,
            // not get silently discarded as "invalid" (KeyUtil, 2026-09-20 mouse-bind support).
            cfg.pushToTalkKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "pushToTalkKeyCode", KeyUtil.NONE));
            // Routed through the real setters (not a direct field assignment) so a hand-edited or
            // corrupted value (e.g. maxRange 0 or negative) gets clamped back into a valid range on load
            // instead of silently making proximity voice permanently inaudible with no visible error -
            // real bug found and fixed 2026-09-14, pre-testing bug-review pass.
            cfg.setMaxRange(ConfigJson.getDouble(obj, "maxRange", 40.0));
            cfg.setOutputVolume(ConfigJson.getFloat(obj, "outputVolume", 1.0f));
            cfg.mutedSelf = ConfigJson.getBool(obj, "mutedSelf", false);
            cfg.microphoneDeviceName = ConfigJson.getString(obj, "microphoneDeviceName", "");
            cfg.talkScope = ConfigJson.getEnum(obj, "talkScope", VoiceScope.class, VoiceScope.PARTY);
            cfg.listenScope = ConfigJson.getEnum(obj, "listenScope", VoiceScope.class, VoiceScope.PARTY);
            cfg.lobbyFalloffEnabled = ConfigJson.getBool(obj, "lobbyFalloffEnabled", true);
            cfg.showPartyVoiceHud = ConfigJson.getBool(obj, "showPartyVoiceHud", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new ProximityVoiceConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("pushToTalk", pushToTalk);
            obj.addProperty("pushToTalkKeyCode", pushToTalkKeyCode);
            obj.addProperty("maxRange", maxRange);
            obj.addProperty("outputVolume", outputVolume);
            obj.addProperty("mutedSelf", mutedSelf);
            obj.addProperty("microphoneDeviceName", microphoneDeviceName);
            obj.addProperty("talkScope", talkScope.name());
            obj.addProperty("listenScope", listenScope.name());
            obj.addProperty("lobbyFalloffEnabled", lobbyFalloffEnabled);
            obj.addProperty("showPartyVoiceHud", showPartyVoiceHud);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPushToTalk() {
        return pushToTalk;
    }

    public void setPushToTalk(boolean pushToTalk) {
        this.pushToTalk = pushToTalk;
    }

    public int getPushToTalkKeyCode() {
        return pushToTalkKeyCode;
    }

    public void setPushToTalkKeyCode(int pushToTalkKeyCode) {
        this.pushToTalkKeyCode = pushToTalkKeyCode;
    }

    public double getMaxRange() {
        return maxRange;
    }

    public void setMaxRange(double maxRange) {
        this.maxRange = Math.max(8.0, Math.min(128.0, maxRange));
    }

    public float getOutputVolume() {
        return outputVolume;
    }

    public void setOutputVolume(float outputVolume) {
        this.outputVolume = Math.max(0f, Math.min(1f, outputVolume));
    }

    public boolean isMutedSelf() {
        return mutedSelf;
    }

    public void setMutedSelf(boolean mutedSelf) {
        this.mutedSelf = mutedSelf;
    }

    /** Empty/blank means "system default". Never trust this as an index - see the field doc. */
    public String getMicrophoneDeviceName() {
        return microphoneDeviceName;
    }

    public void setMicrophoneDeviceName(String microphoneDeviceName) {
        this.microphoneDeviceName = microphoneDeviceName == null ? "" : microphoneDeviceName;
    }

    public VoiceScope getTalkScope() {
        return talkScope;
    }

    public void setTalkScope(VoiceScope talkScope) {
        this.talkScope = talkScope == null ? VoiceScope.PARTY : talkScope;
    }

    public VoiceScope getListenScope() {
        return listenScope;
    }

    public void setListenScope(VoiceScope listenScope) {
        this.listenScope = listenScope == null ? VoiceScope.PARTY : listenScope;
    }

    public boolean isLobbyFalloffEnabled() {
        return lobbyFalloffEnabled;
    }

    public void setLobbyFalloffEnabled(boolean lobbyFalloffEnabled) {
        this.lobbyFalloffEnabled = lobbyFalloffEnabled;
    }

    public boolean isShowPartyVoiceHud() {
        return showPartyVoiceHud;
    }

    public void setShowPartyVoiceHud(boolean showPartyVoiceHud) {
        this.showPartyVoiceHud = showPartyVoiceHud;
    }
}
