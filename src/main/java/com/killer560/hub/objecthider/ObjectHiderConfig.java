package com.killer560.hub.objecthider;

import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted toggles for the Object Hider pack ({@link ObjectHiderFeature}). Every switch defaults to OFF
 * so a fresh install renders exactly like vanilla; nothing here ever removes an entity from the world, it
 * only suppresses rendering / particle spawning (see {@link ObjectHiderFeature} for the per-hider sources).
 * <p>
 * Same shape as every other config in this mod: one file per feature under the Fabric config dir, read
 * through {@link ConfigJson}'s per-key never-throwing getters so one bad value can't reset the whole file,
 * {@code save()} called from each GUI change, and every gated getter ANDed with {@link SkyblockGate#allows()}
 * so "Skyblock Only" suspends the pack outside Skyblock/p3sim without touching the saved values. The
 * {@code *Raw()} getters are what the settings tab draws with, so the menu always shows what is really
 * turned on.
 * <p>
 * 2026-09-20: expanded with every hide QUOI 1.1.0/1.1.1 has that this pack didn't (see
 * {@code wave/research-pack.md} section 1) - grouped the same way QUOI groups them (Render Optimizer / Name
 * Tags / Player Display -> Hide / Tweaks / Item Animations / Chat Replacements / Splits / 1.1.1's Hide
 * Players), nothing added beyond QUOI's own set per killer560's "dont go beyond what they have".
 */
public final class ObjectHiderConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-objecthider.json");

    private static ObjectHiderConfig instance;

    /** Object Hider as a whole (killer560, 2026-10-13): off, nothing in the pack hides anything (No Fire Overlay too),
     *  whatever the switches below say. On by default, so nothing changes until he flips it. */
    private boolean objectHiderEnabled = false;
    private boolean hideFairy = false;
    private boolean hideHealerOrbs = false;
    private boolean keepNearbyOrbs = false;
    private int keepOrbRadius = 5;
    private boolean hideSoulweaverSkulls = false;
    private boolean hideArcherPassive = false;
    private boolean hideSheep = false;
    private boolean hideCloakCreepers = false;
    /** The Witherborn wither minion of a full F7 armor set, and its particles (killer560, 2026-11-01). */
    private boolean hideWitherborn = false;
    private boolean hideHypeHearts = false;

    // --- M7 --------------------------------------------------------------------------------------
    private boolean hideDyingDragons = false;
    private boolean hideWitherKing = false;

    private boolean hideBossDamageSplash = false;
    private boolean cleanEnd = false;
    private boolean cleanEndKeepGuardians = false;

    private boolean hideGroundedArrows = false;
    private boolean disableBlindness = false;
    private boolean noGlow = false;
    private boolean hideDeathAnimations = false;
    private boolean hideDeadNametags = false;
    private boolean hideBlockBreakParticles = false;
    // 2026-09-20: renamed from "Explosion Particles" - killer560 asked for a "wither impact explosions"
    // hider and couldn't find it; this already hides HugeExplosionParticle (the visible puff), it was just
    // unfindable under a generic name. See wave/research-pack.md "On wither impact explosions specifically".
    private boolean hideWitherImpactExplosions = false;
    private boolean hideSmokeParticles = false;

    private boolean disableTextShadow = false;
    private boolean containerTextShadow = false;
    private boolean disableFog = false;
    private boolean fixCrimsonIsleFog = false;
    private boolean hideFallingBlocks = false;
    private boolean hideLightning = false;
    private boolean hideRecipeBookButton = false;

    private boolean cancelVanillaNametags = false;

    // --- QUOI Player Display -> Hide (also surfaced as "Stat Bars", see item 3) ---------------------
    private boolean hideHealthBar = false;
    private boolean hideAbsorptionHearts = false;
    private boolean hideMountHealthBar = false;
    private boolean hideRegenBounce = false;
    private boolean hideArmorBar = false;
    private boolean hideHungerBar = false;

    private boolean disableItemCooldowns = false;

    private boolean noEatAnimation = false;
    private boolean noShortbowSwing = false;

    // --- QUOI Chat Replacements: moved to Chat Hider (chattidy/ChatTidyConfig) on 2026-10-08, which migrates the old
    // keys out of this file the first time it loads. --------------------------------------------------

    // QUOI's Splits "Hide Not Started" is deliberately absent: our Split Timers HUD already never draws a
    // split it has not reached (SplitTimersFeature.displayRows() only emits rows with timeMs() != 0), so the
    // switch would have been a setting that does nothing (2026-09-20).

    // --- QUOI 1.1.1 "Hide Players" ---------------------------------------------------------------------
    private boolean hidePlayers = false;
    private int hidePlayersDistance = 0;
    private boolean hidePlayersDungeonOnly = false;
    private boolean hidePlayersBossOnly = false;

    private ObjectHiderConfig() {
    }

    /** Fast master check for {@link com.killer560.hub.objecthider.ObjectHiderFeature#shouldHideEntity}, which
     *  vanilla calls for EVERY entity EVERY frame (2026-09-20, FPS pass). Reads the raw fields: when not one
     *  entity-hiding toggle is set, every individual check in that method would return false anyway, so the
     *  whole per-entity path - including the armor-stand name/NBT probes - can be skipped outright. Only
     *  toggles actually consulted from {@code shouldHideEntity} belong here; the 2026-09-20 additions
     *  (HUD bars, fog, text shadow, nametags, chat, cooldowns, item animations) run from their own separate,
     *  already-cheap injection points and do not touch this per-entity path - see each mixin's own class doc. */
    public boolean hidesAnyEntity() {
        return objectHiderEnabled && (hideFairy || hideHealerOrbs || hideSoulweaverSkulls || hideArcherPassive || hideSheep
                || hideCloakCreepers || hideWitherborn || hideDyingDragons || hideWitherKing || hideBossDamageSplash
                || cleanEnd || hideGroundedArrows || hideDeathAnimations || hidePlayers);
    }


    public static ObjectHiderConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** The plain on/off settings: key, default, and how to read and write the field. */
    private static final BoolFields<ObjectHiderConfig> BOOLS = new BoolFields<ObjectHiderConfig>()
            .add("objectHiderEnabled", false, c -> c.objectHiderEnabled, (c, v) -> c.objectHiderEnabled = v)
            .add("hideFairy", false, c -> c.hideFairy, (c, v) -> c.hideFairy = v)
            .add("hideHealerOrbs", false, c -> c.hideHealerOrbs, (c, v) -> c.hideHealerOrbs = v)
            .add("keepNearbyOrbs", false, c -> c.keepNearbyOrbs, (c, v) -> c.keepNearbyOrbs = v)
            .add("hideSoulweaverSkulls", false, c -> c.hideSoulweaverSkulls, (c, v) -> c.hideSoulweaverSkulls = v)
            .add("hideArcherPassive", false, c -> c.hideArcherPassive, (c, v) -> c.hideArcherPassive = v)
            .add("hideSheep", false, c -> c.hideSheep, (c, v) -> c.hideSheep = v)
            .add("hideCloakCreepers", false, c -> c.hideCloakCreepers, (c, v) -> c.hideCloakCreepers = v)
            .add("hideWitherborn", false, c -> c.hideWitherborn, (c, v) -> c.hideWitherborn = v)
            .add("hideHypeHearts", false, c -> c.hideHypeHearts, (c, v) -> c.hideHypeHearts = v)
            .add("hideDyingDragons", false, c -> c.hideDyingDragons, (c, v) -> c.hideDyingDragons = v)
            .add("hideWitherKing", false, c -> c.hideWitherKing, (c, v) -> c.hideWitherKing = v)
            .add("hideBossDamageSplash", false, c -> c.hideBossDamageSplash, (c, v) -> c.hideBossDamageSplash = v)
            .add("cleanEnd", false, c -> c.cleanEnd, (c, v) -> c.cleanEnd = v)
            .add("cleanEndKeepGuardians", false, c -> c.cleanEndKeepGuardians, (c, v) -> c.cleanEndKeepGuardians = v)
            .add("hideGroundedArrows", false, c -> c.hideGroundedArrows, (c, v) -> c.hideGroundedArrows = v)
            .add("noGlow", false, c -> c.noGlow, (c, v) -> c.noGlow = v)
            .add("disableBlindness", false, c -> c.disableBlindness, (c, v) -> c.disableBlindness = v)
            .add("hideDeathAnimations", false, c -> c.hideDeathAnimations, (c, v) -> c.hideDeathAnimations = v)
            .add("hideDeadNametags", false, c -> c.hideDeadNametags, (c, v) -> c.hideDeadNametags = v)
            .add("hideBlockBreakParticles", false, c -> c.hideBlockBreakParticles, (c, v) -> c.hideBlockBreakParticles = v)
            .add("hideSmokeParticles", false, c -> c.hideSmokeParticles, (c, v) -> c.hideSmokeParticles = v)
            .add("disableTextShadow", false, c -> c.disableTextShadow, (c, v) -> c.disableTextShadow = v)
            .add("containerTextShadow", false, c -> c.containerTextShadow, (c, v) -> c.containerTextShadow = v)
            .add("disableFog", false, c -> c.disableFog, (c, v) -> c.disableFog = v)
            .add("fixCrimsonIsleFog", false, c -> c.fixCrimsonIsleFog, (c, v) -> c.fixCrimsonIsleFog = v)
            .add("hideFallingBlocks", false, c -> c.hideFallingBlocks, (c, v) -> c.hideFallingBlocks = v)
            .add("hideLightning", false, c -> c.hideLightning, (c, v) -> c.hideLightning = v)
            .add("hideRecipeBookButton", false, c -> c.hideRecipeBookButton, (c, v) -> c.hideRecipeBookButton = v)
            .add("cancelVanillaNametags", false, c -> c.cancelVanillaNametags, (c, v) -> c.cancelVanillaNametags = v)
            .add("hideHealthBar", false, c -> c.hideHealthBar, (c, v) -> c.hideHealthBar = v)
            .add("hideAbsorptionHearts", false, c -> c.hideAbsorptionHearts, (c, v) -> c.hideAbsorptionHearts = v)
            .add("hideMountHealthBar", false, c -> c.hideMountHealthBar, (c, v) -> c.hideMountHealthBar = v)
            .add("hideRegenBounce", false, c -> c.hideRegenBounce, (c, v) -> c.hideRegenBounce = v)
            .add("hideArmorBar", false, c -> c.hideArmorBar, (c, v) -> c.hideArmorBar = v)
            .add("hideHungerBar", false, c -> c.hideHungerBar, (c, v) -> c.hideHungerBar = v)
            .add("disableItemCooldowns", false, c -> c.disableItemCooldowns, (c, v) -> c.disableItemCooldowns = v)
            .add("noEatAnimation", false, c -> c.noEatAnimation, (c, v) -> c.noEatAnimation = v)
            .add("noShortbowSwing", false, c -> c.noShortbowSwing, (c, v) -> c.noShortbowSwing = v)
            .add("hidePlayers", false, c -> c.hidePlayers, (c, v) -> c.hidePlayers = v)
            .add("hidePlayersDungeonOnly", false, c -> c.hidePlayersDungeonOnly, (c, v) -> c.hidePlayersDungeonOnly = v)
            .add("hidePlayersBossOnly", false, c -> c.hidePlayersBossOnly, (c, v) -> c.hidePlayersBossOnly = v);

    public static void load() {
        ObjectHiderConfig cfg = new ObjectHiderConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                BOOLS.load(cfg, obj);
                cfg.keepOrbRadius = Math.max(1, Math.min(20, ConfigJson.getInt(obj, "keepOrbRadius", 5)));
                // Renamed setting, old key kept as fallback so an existing config doesn't silently reset to OFF.
                cfg.hideWitherImpactExplosions = ConfigJson.getBool(obj, "hideWitherImpactExplosions",
                        ConfigJson.getBool(obj, "hideExplosionParticles", false));








                cfg.hidePlayersDistance = Math.max(0, Math.min(128, ConfigJson.getInt(obj, "hidePlayersDistance", 0)));
            } catch (Exception ignored) {
                // A completely unparseable file falls back to all-defaults (all OFF), like every other config here.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            BOOLS.save(this, obj);
            obj.addProperty("keepOrbRadius", keepOrbRadius);
            obj.addProperty("hideWitherImpactExplosions", hideWitherImpactExplosions);








            obj.addProperty("hidePlayersDistance", hidePlayersDistance);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // --- Gated getters (feature code) + raw getters (settings GUI) --------------------------------

    public boolean isObjectHiderEnabled() { return objectHiderEnabled; }

    public void setObjectHiderEnabled(boolean v) { this.objectHiderEnabled = v; }

    public boolean isHideFairy() { return objectHiderEnabled && hideFairy && SkyblockGate.allows(); }

    public boolean getHideFairyRaw() { return hideFairy; }

    public void setHideFairy(boolean v) { hideFairy = v; }

    public boolean isHideHealerOrbs() { return objectHiderEnabled && hideHealerOrbs && SkyblockGate.allows(); }

    public boolean getHideHealerOrbsRaw() { return hideHealerOrbs; }

    public void setHideHealerOrbs(boolean v) { hideHealerOrbs = v; }

    public boolean isKeepNearbyOrbs() { return keepNearbyOrbs; }

    public void setKeepNearbyOrbs(boolean v) { keepNearbyOrbs = v; }

    public int getKeepOrbRadius() { return keepOrbRadius; }

    public void setKeepOrbRadius(int v) { keepOrbRadius = Math.max(1, Math.min(20, v)); }

    public boolean isHideSoulweaverSkulls() { return objectHiderEnabled && hideSoulweaverSkulls && SkyblockGate.allows(); }

    public boolean getHideSoulweaverSkullsRaw() { return hideSoulweaverSkulls; }

    public void setHideSoulweaverSkulls(boolean v) { hideSoulweaverSkulls = v; }

    public boolean isHideArcherPassive() { return objectHiderEnabled && hideArcherPassive && SkyblockGate.allows(); }

    public boolean getHideArcherPassiveRaw() { return hideArcherPassive; }

    public void setHideArcherPassive(boolean v) { hideArcherPassive = v; }

    public boolean isHideSheep() { return objectHiderEnabled && hideSheep && SkyblockGate.allows(); }

    public boolean getHideSheepRaw() { return hideSheep; }

    public void setHideSheep(boolean v) { hideSheep = v; }

    public boolean isHideWitherborn() { return objectHiderEnabled && hideWitherborn && SkyblockGate.allows(); }

    public boolean getHideWitherbornRaw() { return hideWitherborn; }

    public void setHideWitherborn(boolean v) { hideWitherborn = v; }

    public boolean isHideCloakCreepers() { return objectHiderEnabled && hideCloakCreepers && SkyblockGate.allows(); }

    public boolean getHideCloakCreepersRaw() { return hideCloakCreepers; }

    public void setHideCloakCreepers(boolean v) { hideCloakCreepers = v; }

    public boolean isHideHypeHearts() { return objectHiderEnabled && hideHypeHearts && SkyblockGate.allows(); }

    public boolean getHideHypeHeartsRaw() { return hideHypeHearts; }

    public void setHideHypeHearts(boolean v) { hideHypeHearts = v; }

    public boolean isHideDyingDragons() { return objectHiderEnabled && hideDyingDragons && SkyblockGate.allows(); }

    public boolean getHideDyingDragonsRaw() { return hideDyingDragons; }

    public void setHideDyingDragons(boolean v) { hideDyingDragons = v; }

    public boolean isHideWitherKing() { return objectHiderEnabled && hideWitherKing && SkyblockGate.allows(); }

    public boolean getHideWitherKingRaw() { return hideWitherKing; }

    public void setHideWitherKing(boolean v) { hideWitherKing = v; }

    public boolean isHideBossDamageSplash() { return objectHiderEnabled && hideBossDamageSplash && SkyblockGate.allows(); }

    public boolean getHideBossDamageSplashRaw() { return hideBossDamageSplash; }

    public void setHideBossDamageSplash(boolean v) { hideBossDamageSplash = v; }

    public boolean isCleanEnd() { return objectHiderEnabled && cleanEnd && SkyblockGate.allows(); }

    public boolean getCleanEndRaw() { return cleanEnd; }

    public void setCleanEnd(boolean v) { cleanEnd = v; }

    public boolean isCleanEndKeepGuardians() { return cleanEndKeepGuardians; }

    public void setCleanEndKeepGuardians(boolean v) { cleanEndKeepGuardians = v; }

    public boolean isHideGroundedArrows() { return objectHiderEnabled && hideGroundedArrows && SkyblockGate.allows(); }

    public boolean getHideGroundedArrowsRaw() { return hideGroundedArrows; }

    public void setHideGroundedArrows(boolean v) { hideGroundedArrows = v; }

    public boolean isDisableBlindness() { return objectHiderEnabled && disableBlindness && SkyblockGate.allows(); }

    /** Vanilla glow outlines are hidden (our own ESP glow is exempt, see WitherGlowMinecraftMixin). */
    public boolean isNoGlow() { return objectHiderEnabled && noGlow; }

    public boolean getNoGlowRaw() { return noGlow; }

    public void setNoGlow(boolean v) { noGlow = v; }

    public boolean getDisableBlindnessRaw() { return disableBlindness; }

    public void setDisableBlindness(boolean v) { disableBlindness = v; }

    public boolean isHideDeathAnimations() { return objectHiderEnabled && hideDeathAnimations && SkyblockGate.allows(); }

    public boolean getHideDeathAnimationsRaw() { return hideDeathAnimations; }

    public void setHideDeathAnimations(boolean v) { hideDeathAnimations = v; }

    public boolean isHideDeadNametags() { return objectHiderEnabled && hideDeadNametags; }

    public void setHideDeadNametags(boolean v) { hideDeadNametags = v; }

    public boolean isHideBlockBreakParticles() { return objectHiderEnabled && hideBlockBreakParticles && SkyblockGate.allows(); }

    public boolean getHideBlockBreakParticlesRaw() { return hideBlockBreakParticles; }

    public void setHideBlockBreakParticles(boolean v) { hideBlockBreakParticles = v; }

    /** 2026-09-20: renamed from "Explosion Particles"/isHideExplosionParticles - see the field's own comment. */
    public boolean isHideWitherImpactExplosions() {
        return objectHiderEnabled && hideWitherImpactExplosions && SkyblockGate.allows();
    }

    public boolean getHideWitherImpactExplosionsRaw() { return hideWitherImpactExplosions; }

    public void setHideWitherImpactExplosions(boolean v) { hideWitherImpactExplosions = v; }

    public boolean isHideSmokeParticles() { return objectHiderEnabled && hideSmokeParticles && SkyblockGate.allows(); }

    public boolean getHideSmokeParticlesRaw() { return hideSmokeParticles; }

    public void setHideSmokeParticles(boolean v) { hideSmokeParticles = v; }


    public boolean isDisableTextShadow() { return objectHiderEnabled && disableTextShadow && SkyblockGate.allows(); }

    public boolean getDisableTextShadowRaw() { return disableTextShadow; }

    public void setDisableTextShadow(boolean v) { disableTextShadow = v; }

    public boolean isContainerTextShadow() { return objectHiderEnabled && containerTextShadow && SkyblockGate.allows(); }

    public boolean getContainerTextShadowRaw() { return containerTextShadow; }

    public void setContainerTextShadow(boolean v) { containerTextShadow = v; }

    public boolean isDisableFog() { return objectHiderEnabled && disableFog && SkyblockGate.allows(); }

    public boolean getDisableFogRaw() { return disableFog; }

    public void setDisableFog(boolean v) { disableFog = v; }

    public boolean isFixCrimsonIsleFog() { return objectHiderEnabled && fixCrimsonIsleFog && SkyblockGate.allows(); }

    public boolean getFixCrimsonIsleFogRaw() { return fixCrimsonIsleFog; }

    public void setFixCrimsonIsleFog(boolean v) { fixCrimsonIsleFog = v; }

    public boolean isHideFallingBlocks() { return objectHiderEnabled && hideFallingBlocks && SkyblockGate.allows(); }

    public boolean getHideFallingBlocksRaw() { return hideFallingBlocks; }

    public void setHideFallingBlocks(boolean v) { hideFallingBlocks = v; }

    public boolean isHideLightning() { return objectHiderEnabled && hideLightning && SkyblockGate.allows(); }

    public boolean getHideLightningRaw() { return hideLightning; }

    public void setHideLightning(boolean v) { hideLightning = v; }

    public boolean isHideRecipeBookButton() { return objectHiderEnabled && hideRecipeBookButton && SkyblockGate.allows(); }

    public boolean getHideRecipeBookButtonRaw() { return hideRecipeBookButton; }

    public void setHideRecipeBookButton(boolean v) { hideRecipeBookButton = v; }


    public boolean isCancelVanillaNametags() { return objectHiderEnabled && cancelVanillaNametags && SkyblockGate.allows(); }

    public boolean getCancelVanillaNametagsRaw() { return cancelVanillaNametags; }

    public void setCancelVanillaNametags(boolean v) { cancelVanillaNametags = v; }


    public boolean isHideHealthBar() { return objectHiderEnabled && hideHealthBar && SkyblockGate.allows(); }

    public boolean getHideHealthBarRaw() { return hideHealthBar; }

    public void setHideHealthBar(boolean v) { hideHealthBar = v; }

    public boolean isHideAbsorptionHearts() { return objectHiderEnabled && hideAbsorptionHearts && SkyblockGate.allows(); }

    public boolean getHideAbsorptionHeartsRaw() { return hideAbsorptionHearts; }

    public void setHideAbsorptionHearts(boolean v) { hideAbsorptionHearts = v; }

    public boolean isHideMountHealthBar() { return objectHiderEnabled && hideMountHealthBar && SkyblockGate.allows(); }

    public boolean getHideMountHealthBarRaw() { return hideMountHealthBar; }

    public void setHideMountHealthBar(boolean v) { hideMountHealthBar = v; }

    public boolean isHideRegenBounce() { return objectHiderEnabled && hideRegenBounce && SkyblockGate.allows(); }

    public boolean getHideRegenBounceRaw() { return hideRegenBounce; }

    public void setHideRegenBounce(boolean v) { hideRegenBounce = v; }

    public boolean isHideArmorBar() { return objectHiderEnabled && hideArmorBar && SkyblockGate.allows(); }

    public boolean getHideArmorBarRaw() { return hideArmorBar; }

    public void setHideArmorBar(boolean v) { hideArmorBar = v; }

    public boolean isHideHungerBar() { return objectHiderEnabled && hideHungerBar && SkyblockGate.allows(); }

    public boolean getHideHungerBarRaw() { return hideHungerBar; }

    public void setHideHungerBar(boolean v) { hideHungerBar = v; }


    public boolean isDisableItemCooldowns() { return objectHiderEnabled && disableItemCooldowns && SkyblockGate.allows(); }

    public boolean getDisableItemCooldownsRaw() { return disableItemCooldowns; }

    public void setDisableItemCooldowns(boolean v) { disableItemCooldowns = v; }


    public boolean isNoEatAnimation() { return objectHiderEnabled && noEatAnimation && SkyblockGate.allows(); }

    public boolean getNoEatAnimationRaw() { return noEatAnimation; }

    public void setNoEatAnimation(boolean v) { noEatAnimation = v; }

    public boolean isNoShortbowSwing() { return objectHiderEnabled && noShortbowSwing && SkyblockGate.allows(); }

    public boolean getNoShortbowSwingRaw() { return noShortbowSwing; }

    public void setNoShortbowSwing(boolean v) { noShortbowSwing = v; }

    // --- QUOI 1.1.1 Hide Players -------------------------------------------------------------------------

    public boolean isHidePlayers() { return objectHiderEnabled && hidePlayers && SkyblockGate.allows(); }

    public boolean getHidePlayersRaw() { return hidePlayers; }

    public void setHidePlayers(boolean v) { hidePlayers = v; }

    /** 0 = no distance cap (hide regardless of range). */
    public int getHidePlayersDistance() { return hidePlayersDistance; }

    public void setHidePlayersDistance(int v) { hidePlayersDistance = Math.max(0, Math.min(128, v)); }

    public boolean isHidePlayersDungeonOnly() { return hidePlayersDungeonOnly; }

    public void setHidePlayersDungeonOnly(boolean v) { hidePlayersDungeonOnly = v; }

    public boolean isHidePlayersBossOnly() { return hidePlayersBossOnly; }

    public void setHidePlayersBossOnly(boolean v) { hidePlayersBossOnly = v; }
}
