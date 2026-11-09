package com.killer560.hub.bloodcamp;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.BoolFields;
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

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-bloodcamp.json");

    private static BloodCampConfig instance;

    private boolean enabled = false;
    private boolean showOverlay = true;
    private boolean triggerBotEnabled = false;
    private boolean auraEnabled = false;
    private boolean autoDetectLag = true;
    /** Trigger Bot: ticks from the kill timer (the moment the box turns green and the Kill Sound plays) to the click -
     *  positive clicks that many ticks LATE, negative that many ticks EARLY. killer560, 2026-11-01: "make the slider for
     *  trigger offset be such that if i slide it left it is early and right it is late" - the sign was flipped from the
     *  2026-10-15 slider (positive early); a file without {@code triggerOffsetVersion} 2 is negated on load, so his
     *  saved click moment is unchanged. Default 1 (one tick late), the old -1. */
    private int triggerOffsetTicks = DEFAULT_TRIGGER_OFFSET_TICKS;
    public static final int DEFAULT_TRIGGER_OFFSET_TICKS = 1;
    /** {@code triggerOffsetVersion}: 2 = positive is late. */
    private static final int TRIGGER_OFFSET_VERSION = 2;
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
    /** Color of the countdown text on each blood mob's box (killer560, 2026-10-18: a text color option). Default
     *  white, the color it always had (NoammAddons' timerColor default). */
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
    /** Auto Blood Camp acts only while his dungeon class is Mage (killer560, 2026-11-01: "Have an option to only toggle
     *  as mage"). Cheat. */
    private boolean mageOnly = false;
    private boolean outsideRoom = false;
    /** Blood Camp Stats HUD lines (killer560, 2026-11-01): mobs left, mob ticks alive (the ticks lost to slow kills) and
     *  the server lag during the camp. Legit. */
    private boolean hudMobsLeft = false;
    private boolean hudMobTicks = false;
    private boolean hudCampLag = false;
    /** End of run party message with the ticks lost, optionally the camp's lag, optionally when he did not camp himself. */
    private boolean partyMessage = false;
    private boolean partyMessageLag = false;
    private boolean partyMessageAnyClass = false;

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

    /** The plain on/off settings: key, default, and how to read and write the field. */
    private static final BoolFields<BloodCampConfig> BOOLS = new BoolFields<BloodCampConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("showOverlay", true, c -> c.showOverlay, (c, v) -> c.showOverlay = v)
            .add("triggerBotEnabled", false, c -> c.triggerBotEnabled, (c, v) -> c.triggerBotEnabled = v)
            .add("auraEnabled", false, c -> c.auraEnabled, (c, v) -> c.auraEnabled = v)
            .add("autoDetectLag", true, c -> c.autoDetectLag, (c, v) -> c.autoDetectLag = v)
            .add("auraFreecam", false, c -> c.auraFreecam, (c, v) -> c.auraFreecam = v)
            .add("neverMiss", false, c -> c.neverMiss, (c, v) -> c.neverMiss = v)
            .add("killPopup", false, c -> c.killPopup, (c, v) -> c.killPopup = v)
            .add("spawnLine", true, c -> c.spawnLine, (c, v) -> c.spawnLine = v)
            .add("countdownStartSound", true, c -> c.countdownStartSound, (c, v) -> c.countdownStartSound = v)
            .add("killSound", true, c -> c.killSound, (c, v) -> c.killSound = v)
            .add("mageOnly", false, c -> c.mageOnly, (c, v) -> c.mageOnly = v)
            .add("outsideRoom", false, c -> c.outsideRoom, (c, v) -> c.outsideRoom = v)
            .add("hudMobsLeft", false, c -> c.hudMobsLeft, (c, v) -> c.hudMobsLeft = v)
            .add("hudMobTicks", false, c -> c.hudMobTicks, (c, v) -> c.hudMobTicks = v)
            .add("hudCampLag", false, c -> c.hudCampLag, (c, v) -> c.hudCampLag = v)
            .add("partyMessage", false, c -> c.partyMessage, (c, v) -> c.partyMessage = v)
            .add("partyMessageLag", false, c -> c.partyMessageLag, (c, v) -> c.partyMessageLag = v)
            .add("partyMessageAnyClass", false, c -> c.partyMessageAnyClass, (c, v) -> c.partyMessageAnyClass = v);

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BloodCampConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BloodCampConfig cfg = new BloodCampConfig();
            BOOLS.load(cfg, obj);
            // The old hitLeadTicks / manualTickOffset keys are ignored on purpose: they counted from the server-side
            // due moment, this counts from the kill timer he sees, so the old number would not mean the same click.
            int offset = ConfigJson.getInt(obj, "triggerOffsetTicks", DEFAULT_TRIGGER_OFFSET_TICKS);
            if (obj.has("triggerOffsetTicks") && ConfigJson.getInt(obj, "triggerOffsetVersion", 1) < TRIGGER_OFFSET_VERSION) {
                offset = -offset; // saved as "positive early": the same click moment on the new slider
            }
            cfg.setTriggerOffsetTicks(offset);
            cfg.setKillPopupLeadTicks(ConfigJson.getInt(obj, "killPopupLeadTicks", DEFAULT_KILL_POPUP_LEAD_TICKS));
            cfg.setTimerTextScale(ConfigJson.getFloat(obj, "timerTextScale", DEFAULT_TIMER_TEXT_SCALE));
            cfg.setTimerTextColor(ConfigJson.getInt(obj, "timerTextColor", DEFAULT_TIMER_TEXT_COLOR));
            cfg.setSpawnLineWidth(ConfigJson.getFloat(obj, "spawnLineWidth", DEFAULT_SPAWN_LINE_WIDTH));
            cfg.setCountdownStartSoundId(ConfigJson.getString(obj, "countdownStartSoundId", cfg.countdownStartSoundId));
            cfg.setKillSoundId(ConfigJson.getString(obj, "killSoundId", cfg.killSoundId));
            cfg.setSoundVolume(ConfigJson.getFloat(obj, "soundVolume", DEFAULT_SOUND_VOLUME));
            instance = cfg;
        } catch (Exception e) {
            instance = new BloodCampConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("triggerOffsetTicks", triggerOffsetTicks);
        obj.addProperty("triggerOffsetVersion", TRIGGER_OFFSET_VERSION);
        obj.addProperty("killPopupLeadTicks", killPopupLeadTicks);
        obj.addProperty("timerTextScale", timerTextScale);
        obj.addProperty("timerTextColor", timerTextColor);
        obj.addProperty("spawnLineWidth", spawnLineWidth);
        obj.addProperty("countdownStartSoundId", countdownStartSoundId);
        obj.addProperty("killSoundId", killSoundId);
        obj.addProperty("soundVolume", soundVolume);
        ConfigJson.write(CONFIG_PATH, obj);
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

    /** Ticks LATE (positive) or EARLY (negative) relative to the kill timer the Trigger Bot clicks; -10..10. */
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

    /** ARGB color of the countdown text on each blood mob's box. */
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

    // ---- 2026-11-01 (doc-blood-1101) ----

    /** Cheat: Trigger Bot and Aura act only while his class is Mage. */
    public boolean isOutsideRoom() {
        return outsideRoom;
    }

    public void setOutsideRoom(boolean v) {
        outsideRoom = v;
    }

    public boolean isMageOnly() {
        return mageOnly;
    }

    public void setMageOnly(boolean mageOnly) {
        this.mageOnly = mageOnly;
    }

    public boolean isHudMobsLeft() {
        return hudMobsLeft;
    }

    public void setHudMobsLeft(boolean v) {
        this.hudMobsLeft = v;
    }

    public boolean isHudMobTicks() {
        return hudMobTicks;
    }

    public void setHudMobTicks(boolean v) {
        this.hudMobTicks = v;
    }

    public boolean isHudCampLag() {
        return hudCampLag;
    }

    public void setHudCampLag(boolean v) {
        this.hudCampLag = v;
    }

    /** Any Blood Camp Stats line is on. */
    public boolean isStatsHudAny() {
        return hudMobsLeft || hudMobTicks || hudCampLag;
    }

    public boolean isPartyMessage() {
        return partyMessage;
    }

    public void setPartyMessage(boolean v) {
        this.partyMessage = v;
    }

    public boolean isPartyMessageLag() {
        return partyMessageLag;
    }

    public void setPartyMessageLag(boolean v) {
        this.partyMessageLag = v;
    }

    /** Send the message even when he did not camp the Blood Room himself (another class, watching from another room). */
    public boolean isPartyMessageAnyClass() {
        return partyMessageAnyClass;
    }

    public void setPartyMessageAnyClass(boolean v) {
        this.partyMessageAnyClass = v;
    }
}
