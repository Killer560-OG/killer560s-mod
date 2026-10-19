package com.killer560.hub.bloodcamp;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Blood Camp settings - see {@link BloodCampFeature}'s class doc for the real Noamm-ported
 *  mechanic this is built on. Ships disabled by default, same as every other new feature in this mod.
 *  <p>
 *  Legit getters are {@code raw && SkyblockGate.allows()}; cheat getters additionally require
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}. Tab labels read the {@code ...Raw()}
 *  getters so a toggle never flips from a gated value (2026-09-21: {@code BloodCampTab} read the gated
 *  getters, so outside Skyblock every row read OFF and clicking it could only ever write true).
 *  <p>
 *  Scope note (2026-09-14): real per-color customization (box/line/timer colors, decimal places) that
 *  Noamm's own real reference exposes is deliberately NOT included here yet, to keep this first version
 *  manageable - fixed real defaults (green=ready, red=already broken, matching Noamm's own real choices)
 *  are used instead. Can be added later if killer560 wants it. */
public final class BloodCampConfig {

    /** Ticks before the Watcher's predicted move that the kill popup flips to "KILL" (SkyHanni's own lead
     *  is 150 ms = 3 ticks). */
    public static final int DEFAULT_KILL_POPUP_LEAD_TICKS = 3;
    public static final int MAX_KILL_POPUP_LEAD_TICKS = 20;
    /** killer560: "the timer on each box should be bigger". Multiplies a 0.025 base; the default 2.0 is
     *  NoammAddons' own label scale (2 x 0.025), which his 2026-10-07 "like noamm's" request matches. */
    public static final float DEFAULT_TIMER_TEXT_SCALE = 2.0f;
    public static final float MIN_TIMER_TEXT_SCALE = 1.0f;
    public static final float MAX_TIMER_TEXT_SCALE = 4.0f;
    /** Same line-width range Thorn ESP's own line-width setting uses. */
    public static final float DEFAULT_SPAWN_LINE_WIDTH = 2.0f;
    public static final float MIN_SPAWN_LINE_WIDTH = 1.0f;
    public static final float MAX_SPAWN_LINE_WIDTH = 10.0f;
    /** Countdown Start Sound and Kill Sound volume, 0-1 (Secret Sound's default). */
    public static final float DEFAULT_SOUND_VOLUME = 0.5f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-bloodcamp.json");

    private static BloodCampConfig instance;

    private boolean enabled = false;
    private boolean showOverlay = true;
    private boolean triggerBotEnabled = false;
    private boolean auraEnabled = false;
    private boolean autoDetectLag = true;
    /** Trigger Bot: ticks from the kill timer (the moment the box turns green and the Kill Sound plays) to the click -
     *  positive clicks that many ticks EARLY, negative that many ticks LATE (killer560, 2026-10-15: "there needs to be a
     *  negative option as well where it will attack late. Have the slider go from -10 to 10 and have it default to -1").
     *  Replaces Hit Lead (0-10, 3), whose old value is not carried over: it counted from a different moment. */
    private int triggerOffsetTicks = DEFAULT_TRIGGER_OFFSET_TICKS;
    public static final int DEFAULT_TRIGGER_OFFSET_TICKS = -1;
    public static final int MIN_TRIGGER_OFFSET_TICKS = -10;
    public static final int MAX_TRIGGER_OFFSET_TICKS = 10;
    /** Aura: turn the body only, the camera stays where his mouse puts it (killer560, 2026-10-15: "have an option for
     *  aura to put you into freecam mode"). */
    private boolean auraFreecam = false;
    /** Never Miss (killer560, 2026-10-15: "attacks the tick it detects the mob is actually there not just when it thinks
     *  it will spawn"): the first click waits for a living mob on the spot, in reach and in sight, not the timer. */
    private boolean neverMiss = false;
    private boolean killPopup = false;
    private int killPopupLeadTicks = DEFAULT_KILL_POPUP_LEAD_TICKS;
    private float timerTextScale = DEFAULT_TIMER_TEXT_SCALE;
    /** Colour of the countdown text on each blood mob's box (killer560, 2026-10-18: a text colour option). Default
     *  white, the colour it always had (NoammAddons' timerColor default). */
    private int timerTextColor = DEFAULT_TIMER_TEXT_COLOR;
    public static final int DEFAULT_TIMER_TEXT_COLOR = 0xFFFFFFFF;
    /** killer560: "it is from the wall spot to where it is going to spawn." Default ON - Blood Camp itself
     *  (and its overlay) is already the opt-in step; this is a sub-option of an overlay he has to turn on
     *  first, not a standalone new feature. */
    private boolean spawnLine = true;
    private float spawnLineWidth = DEFAULT_SPAWN_LINE_WIDTH;
    /** killer560, 2026-10-07: "make the kill timer for blood mobs also make a sound once it starts counting down
     *  and as you need to kill". Both sounds default ON: he asked for them by name, and they only ever play once
     *  Blood Camp itself is switched on, which is the opt-in step (same reasoning as Spawn Line above). */
    private boolean countdownStartSound = true;
    private boolean killSound = true;
    /** Two different sounds by default so the ear can tell "a mob is on its way" from "hit it now". */
    private String countdownStartSoundId = com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.PLING.name();
    private String killSoundId = com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.ARROW_HIT.name();
    private float soundVolume = DEFAULT_SOUND_VOLUME;

    private BloodCampConfig() {
    }

    public static BloodCampConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isShowOverlayRaw() {
        return showOverlay;
    }

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BloodCampConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BloodCampConfig cfg = new BloodCampConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showOverlay = ConfigJson.getBool(obj, "showOverlay", true);
            cfg.triggerBotEnabled = ConfigJson.getBool(obj, "triggerBotEnabled", false);
            cfg.auraEnabled = ConfigJson.getBool(obj, "auraEnabled", false);
            cfg.autoDetectLag = ConfigJson.getBool(obj, "autoDetectLag", true);
            // The old hitLeadTicks / manualTickOffset keys are ignored on purpose: they counted from the server-side
            // due moment, this counts from the kill timer he sees, so the old number would not mean the same click.
            cfg.setTriggerOffsetTicks(ConfigJson.getInt(obj, "triggerOffsetTicks", DEFAULT_TRIGGER_OFFSET_TICKS));
            cfg.auraFreecam = ConfigJson.getBool(obj, "auraFreecam", false);
            cfg.neverMiss = ConfigJson.getBool(obj, "neverMiss", false);
            cfg.killPopup = ConfigJson.getBool(obj, "killPopup", false);
            cfg.setKillPopupLeadTicks(ConfigJson.getInt(obj, "killPopupLeadTicks", DEFAULT_KILL_POPUP_LEAD_TICKS));
            cfg.setTimerTextScale(ConfigJson.getFloat(obj, "timerTextScale", DEFAULT_TIMER_TEXT_SCALE));
            cfg.setTimerTextColor(ConfigJson.getInt(obj, "timerTextColor", DEFAULT_TIMER_TEXT_COLOR));
            cfg.spawnLine = ConfigJson.getBool(obj, "spawnLine", true);
            cfg.setSpawnLineWidth(ConfigJson.getFloat(obj, "spawnLineWidth", DEFAULT_SPAWN_LINE_WIDTH));
            cfg.countdownStartSound = ConfigJson.getBool(obj, "countdownStartSound", true);
            cfg.killSound = ConfigJson.getBool(obj, "killSound", true);
            cfg.setCountdownStartSoundId(ConfigJson.getString(obj, "countdownStartSoundId", cfg.countdownStartSoundId));
            cfg.setKillSoundId(ConfigJson.getString(obj, "killSoundId", cfg.killSoundId));
            cfg.setSoundVolume(ConfigJson.getFloat(obj, "soundVolume", DEFAULT_SOUND_VOLUME));
            instance = cfg;
        } catch (Exception e) {
            instance = new BloodCampConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showOverlay", showOverlay);
            obj.addProperty("triggerBotEnabled", triggerBotEnabled);
            obj.addProperty("auraEnabled", auraEnabled);
            obj.addProperty("autoDetectLag", autoDetectLag);
            obj.addProperty("triggerOffsetTicks", triggerOffsetTicks);
            obj.addProperty("auraFreecam", auraFreecam);
            obj.addProperty("neverMiss", neverMiss);
            obj.addProperty("killPopup", killPopup);
            obj.addProperty("killPopupLeadTicks", killPopupLeadTicks);
            obj.addProperty("timerTextScale", timerTextScale);
            obj.addProperty("timerTextColor", timerTextColor);
            obj.addProperty("spawnLine", spawnLine);
            obj.addProperty("spawnLineWidth", spawnLineWidth);
            obj.addProperty("countdownStartSound", countdownStartSound);
            obj.addProperty("killSound", killSound);
            obj.addProperty("countdownStartSoundId", countdownStartSoundId);
            obj.addProperty("killSoundId", killSoundId);
            obj.addProperty("soundVolume", soundVolume);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The saved value with no gate applied - for the settings GUI. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled; com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowOverlay() {
        return showOverlay;
    }

    public void setShowOverlay(boolean showOverlay) {
        this.showOverlay = showOverlay; com.killer560.hub.util.FeatureBits.changed();
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - a real click-assist
     *  macro, same pattern as Simon Says' Trigger Bot. */
    public boolean isTriggerBotEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && triggerBotEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getTriggerBotRaw() {
        return triggerBotEnabled;
    }

    public void setTriggerBotEnabled(boolean triggerBotEnabled) {
        this.triggerBotEnabled = triggerBotEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real simulated camera
     *  rotation, a real macro. */
    public boolean isAuraEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && auraEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getAuraRaw() {
        return auraEnabled;
    }

    public void setAuraEnabled(boolean auraEnabled) {
        this.auraEnabled = auraEnabled;
    }

    /**
     * "Kill Popup" (killer560, 2026-09-21: "make it so it gives a hud popup saying kill as an option for time
     * to kill to skip dialogue") - the movable HUD element in {@link BloodCampMoveTimer}. Legit: it only reads
     * chat and draws text, so it ships in both jars.
     */
    public boolean isKillPopup() {
        return killPopup && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getKillPopupRaw() {
        return killPopup;
    }

    public void setKillPopup(boolean killPopup) {
        this.killPopup = killPopup;
    }

    /** Ticks before the predicted Watcher move that the popup flips from a countdown to "KILL". */
    public int getKillPopupLeadTicks() {
        return killPopupLeadTicks;
    }

    public void setKillPopupLeadTicks(int ticks) {
        this.killPopupLeadTicks = Math.max(0, Math.min(MAX_KILL_POPUP_LEAD_TICKS, ticks));
    }

    public boolean isAutoDetectLag() {
        return autoDetectLag;
    }

    public boolean getShowOverlayRaw() {
        return showOverlay;
    }

    public void setAutoDetectLag(boolean autoDetectLag) {
        this.autoDetectLag = autoDetectLag;
    }

    /** Ticks EARLY (positive) or LATE (negative) relative to the kill timer the Trigger Bot clicks; -10..10. */
    public int getTriggerOffsetTicks() {
        return triggerOffsetTicks;
    }

    public void setTriggerOffsetTicks(int ticks) {
        this.triggerOffsetTicks = Math.max(MIN_TRIGGER_OFFSET_TICKS, Math.min(MAX_TRIGGER_OFFSET_TICKS, ticks));
    }

    /** Never Miss: the Trigger Bot's and the Aura's first click goes the tick the mob is really there, not on the timer. */
    public boolean isNeverMiss() {
        return neverMiss;
    }

    public void setNeverMiss(boolean neverMiss) {
        this.neverMiss = neverMiss;
    }

    /** Aura in freecam mode: the camera stays with his mouse while the aura turns his body. */
    public boolean isAuraFreecam() {
        return auraFreecam;
    }

    public void setAuraFreecam(boolean auraFreecam) {
        this.auraFreecam = auraFreecam;
    }

    /** Text scale for the world-space countdown drawn on each blood mob's box (killer560: "the timer on
     *  each box should be bigger"). Multiplies the base 0.02f every world-space label in this mod uses. */
    public float getTimerTextScale() {
        return timerTextScale;
    }

    public void setTimerTextScale(float timerTextScale) {
        this.timerTextScale = Math.max(MIN_TIMER_TEXT_SCALE, Math.min(MAX_TIMER_TEXT_SCALE, timerTextScale));
    }

    /** ARGB colour of the countdown text on each blood mob's box. */
    public int getTimerTextColor() {
        return timerTextColor;
    }

    /** Any ARGB; a fully transparent value is taken as opaque (the text would vanish). */
    public void setTimerTextColor(int argb) {
        this.timerTextColor = (argb >>> 24) == 0 ? (argb | 0xFF000000) : argb;
    }

    /**
     * killer560: "it is from the wall spot to where it is going to spawn." Draws a line from the mob's
     * first-seen wall spot ({@code BloodMobState#startVec}) to its predicted landing spot
     * ({@code BloodMobState#endVector}) while it is still in flight. Legit: reads packets already parsed
     * for the box/countdown, draws nothing new server-side.
     */
    public boolean isSpawnLine() {
        return spawnLine;
    }

    public boolean getSpawnLineRaw() {
        return spawnLine;
    }

    public void setSpawnLine(boolean spawnLine) {
        this.spawnLine = spawnLine;
    }

    public float getSpawnLineWidth() {
        return spawnLineWidth;
    }

    public void setSpawnLineWidth(float spawnLineWidth) {
        this.spawnLineWidth = Math.max(MIN_SPAWN_LINE_WIDTH, Math.min(MAX_SPAWN_LINE_WIDTH, spawnLineWidth));
    }

    // ---- Blood mob countdown sounds (legit, both builds) ----

    /** A sound when a blood mob's countdown starts (its landing box first appears). */
    public boolean isCountdownStartSound() {
        return countdownStartSound;
    }

    public void setCountdownStartSound(boolean countdownStartSound) {
        this.countdownStartSound = countdownStartSound;
    }

    /** A sound the moment a blood mob's countdown reaches zero - it can be killed now. */
    public boolean isKillSound() {
        return killSound;
    }

    public void setKillSound(boolean killSound) {
        this.killSound = killSound;
    }

    /** A {@link com.killer560.hub.dungeonalerts.SecretSound.SoundChoice} name. */
    public String getCountdownStartSoundId() {
        return countdownStartSoundId;
    }

    public void setCountdownStartSoundId(String id) {
        this.countdownStartSoundId = com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(id).name();
    }

    public String getKillSoundId() {
        return killSoundId;
    }

    public void setKillSoundId(String id) {
        this.killSoundId = com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(id).name();
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(float soundVolume) {
        this.soundVolume = Float.isNaN(soundVolume) ? DEFAULT_SOUND_VOLUME : Math.max(0f, Math.min(1f, soundVolume));
    }
}
