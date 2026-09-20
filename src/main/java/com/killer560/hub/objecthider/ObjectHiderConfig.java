package com.killer560.hub.objecthider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

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
 * {@code wave/research-pack.md} section 1) - grouped the same way QUOI groups them (Render Optimiser / Name
 * Tags / Player Display -> Hide / Tweaks / Item Animations / Chat Replacements / Splits / 1.1.1's Hide
 * Players), nothing added beyond QUOI's own set per killer560's "dont go beyond what they have".
 */
public final class ObjectHiderConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-objecthider.json");

    private static ObjectHiderConfig instance;

    // --- Dungeon clutter -------------------------------------------------------------------------
    private boolean hideFairy = false;
    private boolean hideHealerOrbs = false;
    private boolean keepNearbyOrbs = false;
    private int keepOrbRadius = 5;
    private boolean hideSoulweaverSkulls = false;
    private boolean hideArcherPassive = false;
    private boolean hideSheep = false;
    private boolean hideCloakCreepers = false;
    private boolean hideHypeHearts = false;

    // --- M7 --------------------------------------------------------------------------------------
    private boolean hideDyingDragons = false;
    private boolean hideWitherKing = false;

    // --- End of run ------------------------------------------------------------------------------
    private boolean hideBossDamageSplash = false;
    private boolean cleanEnd = false;
    private boolean cleanEndKeepGuardians = false;

    // --- General vanilla tweaks ------------------------------------------------------------------
    private boolean hideGroundedArrows = false;
    private boolean disableBlindness = false;
    private boolean hideDeathAnimations = false;
    private boolean hideDeadNametags = false;
    private boolean hideBlockBreakParticles = false;
    // 2026-09-20: renamed from "Explosion Particles" - killer560 asked for a "wither impact explosions"
    // hider and couldn't find it; this already hides HugeExplosionParticle (the visible puff), it was just
    // unfindable under a generic name. See wave/research-pack.md "On wither impact explosions specifically".
    private boolean hideWitherImpactExplosions = false;
    private boolean hideSmokeParticles = false;

    // --- QUOI Render Optimiser ---------------------------------------------------------------------
    private boolean disableTextShadow = false;
    private boolean containerTextShadow = false;
    private boolean disableFog = false;
    private boolean fixCrimsonIsleFog = false;
    private boolean hideFallingBlocks = false;
    private boolean hideLightning = false;
    private boolean hideRecipeBookButton = false;

    // --- QUOI Name Tags -----------------------------------------------------------------------------
    private boolean cancelVanillaNametags = false;

    // --- QUOI Player Display -> Hide (also surfaced as "Stat Bars", see item 3) ---------------------
    private boolean hideHealthBar = false;
    private boolean hideAbsorptionHearts = false;
    private boolean hideMountHealthBar = false;
    private boolean hideRegenBounce = false;
    private boolean hideArmorBar = false;
    private boolean hideHungerBar = false;

    // --- QUOI Tweaks --------------------------------------------------------------------------------
    private boolean disableItemCooldowns = false;

    // --- QUOI Item Animations ------------------------------------------------------------------------
    private boolean noEatAnimation = false;
    private boolean noShortbowSwing = false;

    // --- QUOI Chat Replacements ----------------------------------------------------------------------
    private boolean hideUselessMessages = false;
    private boolean hideDiscordWarnings = false;
    private boolean hideMicrosoftWarnings = false;
    private boolean hideEmptyChatMessages = false;
    private boolean hideActionbar = false;
    private boolean hideNonRankInvites = false;

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
        return hideFairy || hideHealerOrbs || hideSoulweaverSkulls || hideArcherPassive || hideSheep
                || hideCloakCreepers || hideDyingDragons || hideWitherKing || hideBossDamageSplash
                || cleanEnd || hideGroundedArrows || hideDeathAnimations || hidePlayers;
    }


    public static ObjectHiderConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ObjectHiderConfig cfg = new ObjectHiderConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.hideFairy = ConfigJson.getBool(obj, "hideFairy", false);
                cfg.hideHealerOrbs = ConfigJson.getBool(obj, "hideHealerOrbs", false);
                cfg.keepNearbyOrbs = ConfigJson.getBool(obj, "keepNearbyOrbs", false);
                cfg.keepOrbRadius = Math.max(1, Math.min(20, ConfigJson.getInt(obj, "keepOrbRadius", 5)));
                cfg.hideSoulweaverSkulls = ConfigJson.getBool(obj, "hideSoulweaverSkulls", false);
                cfg.hideArcherPassive = ConfigJson.getBool(obj, "hideArcherPassive", false);
                cfg.hideSheep = ConfigJson.getBool(obj, "hideSheep", false);
                cfg.hideCloakCreepers = ConfigJson.getBool(obj, "hideCloakCreepers", false);
                cfg.hideHypeHearts = ConfigJson.getBool(obj, "hideHypeHearts", false);
                cfg.hideDyingDragons = ConfigJson.getBool(obj, "hideDyingDragons", false);
                cfg.hideWitherKing = ConfigJson.getBool(obj, "hideWitherKing", false);
                cfg.hideBossDamageSplash = ConfigJson.getBool(obj, "hideBossDamageSplash", false);
                cfg.cleanEnd = ConfigJson.getBool(obj, "cleanEnd", false);
                cfg.cleanEndKeepGuardians = ConfigJson.getBool(obj, "cleanEndKeepGuardians", false);
                cfg.hideGroundedArrows = ConfigJson.getBool(obj, "hideGroundedArrows", false);
                cfg.disableBlindness = ConfigJson.getBool(obj, "disableBlindness", false);
                cfg.hideDeathAnimations = ConfigJson.getBool(obj, "hideDeathAnimations", false);
                cfg.hideDeadNametags = ConfigJson.getBool(obj, "hideDeadNametags", false);
                cfg.hideBlockBreakParticles = ConfigJson.getBool(obj, "hideBlockBreakParticles", false);
                // Renamed setting, old key kept as fallback so an existing config doesn't silently reset to OFF.
                cfg.hideWitherImpactExplosions = ConfigJson.getBool(obj, "hideWitherImpactExplosions",
                        ConfigJson.getBool(obj, "hideExplosionParticles", false));
                cfg.hideSmokeParticles = ConfigJson.getBool(obj, "hideSmokeParticles", false);

                cfg.disableTextShadow = ConfigJson.getBool(obj, "disableTextShadow", false);
                cfg.containerTextShadow = ConfigJson.getBool(obj, "containerTextShadow", false);
                cfg.disableFog = ConfigJson.getBool(obj, "disableFog", false);
                cfg.fixCrimsonIsleFog = ConfigJson.getBool(obj, "fixCrimsonIsleFog", false);
                cfg.hideFallingBlocks = ConfigJson.getBool(obj, "hideFallingBlocks", false);
                cfg.hideLightning = ConfigJson.getBool(obj, "hideLightning", false);
                cfg.hideRecipeBookButton = ConfigJson.getBool(obj, "hideRecipeBookButton", false);

                cfg.cancelVanillaNametags = ConfigJson.getBool(obj, "cancelVanillaNametags", false);

                cfg.hideHealthBar = ConfigJson.getBool(obj, "hideHealthBar", false);
                cfg.hideAbsorptionHearts = ConfigJson.getBool(obj, "hideAbsorptionHearts", false);
                cfg.hideMountHealthBar = ConfigJson.getBool(obj, "hideMountHealthBar", false);
                cfg.hideRegenBounce = ConfigJson.getBool(obj, "hideRegenBounce", false);
                cfg.hideArmorBar = ConfigJson.getBool(obj, "hideArmorBar", false);
                cfg.hideHungerBar = ConfigJson.getBool(obj, "hideHungerBar", false);

                cfg.disableItemCooldowns = ConfigJson.getBool(obj, "disableItemCooldowns", false);

                cfg.noEatAnimation = ConfigJson.getBool(obj, "noEatAnimation", false);
                cfg.noShortbowSwing = ConfigJson.getBool(obj, "noShortbowSwing", false);

                cfg.hideUselessMessages = ConfigJson.getBool(obj, "hideUselessMessages", false);
                cfg.hideDiscordWarnings = ConfigJson.getBool(obj, "hideDiscordWarnings", false);
                cfg.hideMicrosoftWarnings = ConfigJson.getBool(obj, "hideMicrosoftWarnings", false);
                cfg.hideEmptyChatMessages = ConfigJson.getBool(obj, "hideEmptyChatMessages", false);
                cfg.hideActionbar = ConfigJson.getBool(obj, "hideActionbar", false);
                cfg.hideNonRankInvites = ConfigJson.getBool(obj, "hideNonRankInvites", false);


                cfg.hidePlayers = ConfigJson.getBool(obj, "hidePlayers", false);
                cfg.hidePlayersDistance = Math.max(0, Math.min(128, ConfigJson.getInt(obj, "hidePlayersDistance", 0)));
                cfg.hidePlayersDungeonOnly = ConfigJson.getBool(obj, "hidePlayersDungeonOnly", false);
                cfg.hidePlayersBossOnly = ConfigJson.getBool(obj, "hidePlayersBossOnly", false);
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
            obj.addProperty("hideFairy", hideFairy);
            obj.addProperty("hideHealerOrbs", hideHealerOrbs);
            obj.addProperty("keepNearbyOrbs", keepNearbyOrbs);
            obj.addProperty("keepOrbRadius", keepOrbRadius);
            obj.addProperty("hideSoulweaverSkulls", hideSoulweaverSkulls);
            obj.addProperty("hideArcherPassive", hideArcherPassive);
            obj.addProperty("hideSheep", hideSheep);
            obj.addProperty("hideCloakCreepers", hideCloakCreepers);
            obj.addProperty("hideHypeHearts", hideHypeHearts);
            obj.addProperty("hideDyingDragons", hideDyingDragons);
            obj.addProperty("hideWitherKing", hideWitherKing);
            obj.addProperty("hideBossDamageSplash", hideBossDamageSplash);
            obj.addProperty("cleanEnd", cleanEnd);
            obj.addProperty("cleanEndKeepGuardians", cleanEndKeepGuardians);
            obj.addProperty("hideGroundedArrows", hideGroundedArrows);
            obj.addProperty("disableBlindness", disableBlindness);
            obj.addProperty("hideDeathAnimations", hideDeathAnimations);
            obj.addProperty("hideDeadNametags", hideDeadNametags);
            obj.addProperty("hideBlockBreakParticles", hideBlockBreakParticles);
            obj.addProperty("hideWitherImpactExplosions", hideWitherImpactExplosions);
            obj.addProperty("hideSmokeParticles", hideSmokeParticles);

            obj.addProperty("disableTextShadow", disableTextShadow);
            obj.addProperty("containerTextShadow", containerTextShadow);
            obj.addProperty("disableFog", disableFog);
            obj.addProperty("fixCrimsonIsleFog", fixCrimsonIsleFog);
            obj.addProperty("hideFallingBlocks", hideFallingBlocks);
            obj.addProperty("hideLightning", hideLightning);
            obj.addProperty("hideRecipeBookButton", hideRecipeBookButton);

            obj.addProperty("cancelVanillaNametags", cancelVanillaNametags);

            obj.addProperty("hideHealthBar", hideHealthBar);
            obj.addProperty("hideAbsorptionHearts", hideAbsorptionHearts);
            obj.addProperty("hideMountHealthBar", hideMountHealthBar);
            obj.addProperty("hideRegenBounce", hideRegenBounce);
            obj.addProperty("hideArmorBar", hideArmorBar);
            obj.addProperty("hideHungerBar", hideHungerBar);

            obj.addProperty("disableItemCooldowns", disableItemCooldowns);

            obj.addProperty("noEatAnimation", noEatAnimation);
            obj.addProperty("noShortbowSwing", noShortbowSwing);

            obj.addProperty("hideUselessMessages", hideUselessMessages);
            obj.addProperty("hideDiscordWarnings", hideDiscordWarnings);
            obj.addProperty("hideMicrosoftWarnings", hideMicrosoftWarnings);
            obj.addProperty("hideEmptyChatMessages", hideEmptyChatMessages);
            obj.addProperty("hideActionbar", hideActionbar);
            obj.addProperty("hideNonRankInvites", hideNonRankInvites);


            obj.addProperty("hidePlayers", hidePlayers);
            obj.addProperty("hidePlayersDistance", hidePlayersDistance);
            obj.addProperty("hidePlayersDungeonOnly", hidePlayersDungeonOnly);
            obj.addProperty("hidePlayersBossOnly", hidePlayersBossOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // --- Gated getters (feature code) + raw getters (settings GUI) --------------------------------

    public boolean isHideFairy() {
        return hideFairy && SkyblockGate.allows();
    }

    public boolean getHideFairyRaw() {
        return hideFairy;
    }

    public void setHideFairy(boolean v) {
        hideFairy = v;
    }

    public boolean isHideHealerOrbs() {
        return hideHealerOrbs && SkyblockGate.allows();
    }

    public boolean getHideHealerOrbsRaw() {
        return hideHealerOrbs;
    }

    public void setHideHealerOrbs(boolean v) {
        hideHealerOrbs = v;
    }

    public boolean isKeepNearbyOrbs() {
        return keepNearbyOrbs;
    }

    public void setKeepNearbyOrbs(boolean v) {
        keepNearbyOrbs = v;
    }

    public int getKeepOrbRadius() {
        return keepOrbRadius;
    }

    public void setKeepOrbRadius(int v) {
        keepOrbRadius = Math.max(1, Math.min(20, v));
    }

    public boolean isHideSoulweaverSkulls() {
        return hideSoulweaverSkulls && SkyblockGate.allows();
    }

    public boolean getHideSoulweaverSkullsRaw() {
        return hideSoulweaverSkulls;
    }

    public void setHideSoulweaverSkulls(boolean v) {
        hideSoulweaverSkulls = v;
    }

    public boolean isHideArcherPassive() {
        return hideArcherPassive && SkyblockGate.allows();
    }

    public boolean getHideArcherPassiveRaw() {
        return hideArcherPassive;
    }

    public void setHideArcherPassive(boolean v) {
        hideArcherPassive = v;
    }

    public boolean isHideSheep() {
        return hideSheep && SkyblockGate.allows();
    }

    public boolean getHideSheepRaw() {
        return hideSheep;
    }

    public void setHideSheep(boolean v) {
        hideSheep = v;
    }

    public boolean isHideCloakCreepers() {
        return hideCloakCreepers && SkyblockGate.allows();
    }

    public boolean getHideCloakCreepersRaw() {
        return hideCloakCreepers;
    }

    public void setHideCloakCreepers(boolean v) {
        hideCloakCreepers = v;
    }

    public boolean isHideHypeHearts() {
        return hideHypeHearts && SkyblockGate.allows();
    }

    public boolean getHideHypeHeartsRaw() {
        return hideHypeHearts;
    }

    public void setHideHypeHearts(boolean v) {
        hideHypeHearts = v;
    }

    public boolean isHideDyingDragons() {
        return hideDyingDragons && SkyblockGate.allows();
    }

    public boolean getHideDyingDragonsRaw() {
        return hideDyingDragons;
    }

    public void setHideDyingDragons(boolean v) {
        hideDyingDragons = v;
    }

    public boolean isHideWitherKing() {
        return hideWitherKing && SkyblockGate.allows();
    }

    public boolean getHideWitherKingRaw() {
        return hideWitherKing;
    }

    public void setHideWitherKing(boolean v) {
        hideWitherKing = v;
    }

    public boolean isHideBossDamageSplash() {
        return hideBossDamageSplash && SkyblockGate.allows();
    }

    public boolean getHideBossDamageSplashRaw() {
        return hideBossDamageSplash;
    }

    public void setHideBossDamageSplash(boolean v) {
        hideBossDamageSplash = v;
    }

    public boolean isCleanEnd() {
        return cleanEnd && SkyblockGate.allows();
    }

    public boolean getCleanEndRaw() {
        return cleanEnd;
    }

    public void setCleanEnd(boolean v) {
        cleanEnd = v;
    }

    public boolean isCleanEndKeepGuardians() {
        return cleanEndKeepGuardians;
    }

    public void setCleanEndKeepGuardians(boolean v) {
        cleanEndKeepGuardians = v;
    }

    public boolean isHideGroundedArrows() {
        return hideGroundedArrows && SkyblockGate.allows();
    }

    public boolean getHideGroundedArrowsRaw() {
        return hideGroundedArrows;
    }

    public void setHideGroundedArrows(boolean v) {
        hideGroundedArrows = v;
    }

    public boolean isDisableBlindness() {
        return disableBlindness && SkyblockGate.allows();
    }

    public boolean getDisableBlindnessRaw() {
        return disableBlindness;
    }

    public void setDisableBlindness(boolean v) {
        disableBlindness = v;
    }

    public boolean isHideDeathAnimations() {
        return hideDeathAnimations && SkyblockGate.allows();
    }

    public boolean getHideDeathAnimationsRaw() {
        return hideDeathAnimations;
    }

    public void setHideDeathAnimations(boolean v) {
        hideDeathAnimations = v;
    }

    public boolean isHideDeadNametags() {
        return hideDeadNametags;
    }

    public void setHideDeadNametags(boolean v) {
        hideDeadNametags = v;
    }

    public boolean isHideBlockBreakParticles() {
        return hideBlockBreakParticles && SkyblockGate.allows();
    }

    public boolean getHideBlockBreakParticlesRaw() {
        return hideBlockBreakParticles;
    }

    public void setHideBlockBreakParticles(boolean v) {
        hideBlockBreakParticles = v;
    }

    /** 2026-09-20: renamed from "Explosion Particles"/isHideExplosionParticles - see the field's own comment. */
    public boolean isHideWitherImpactExplosions() {
        return hideWitherImpactExplosions && SkyblockGate.allows();
    }

    public boolean getHideWitherImpactExplosionsRaw() {
        return hideWitherImpactExplosions;
    }

    public void setHideWitherImpactExplosions(boolean v) {
        hideWitherImpactExplosions = v;
    }

    public boolean isHideSmokeParticles() {
        return hideSmokeParticles && SkyblockGate.allows();
    }

    public boolean getHideSmokeParticlesRaw() {
        return hideSmokeParticles;
    }

    public void setHideSmokeParticles(boolean v) {
        hideSmokeParticles = v;
    }

    // --- QUOI Render Optimiser ---------------------------------------------------------------------

    public boolean isDisableTextShadow() {
        return disableTextShadow && SkyblockGate.allows();
    }

    public boolean getDisableTextShadowRaw() {
        return disableTextShadow;
    }

    public void setDisableTextShadow(boolean v) {
        disableTextShadow = v;
    }

    public boolean isContainerTextShadow() {
        return containerTextShadow && SkyblockGate.allows();
    }

    public boolean getContainerTextShadowRaw() {
        return containerTextShadow;
    }

    public void setContainerTextShadow(boolean v) {
        containerTextShadow = v;
    }

    public boolean isDisableFog() {
        return disableFog && SkyblockGate.allows();
    }

    public boolean getDisableFogRaw() {
        return disableFog;
    }

    public void setDisableFog(boolean v) {
        disableFog = v;
    }

    public boolean isFixCrimsonIsleFog() {
        return fixCrimsonIsleFog && SkyblockGate.allows();
    }

    public boolean getFixCrimsonIsleFogRaw() {
        return fixCrimsonIsleFog;
    }

    public void setFixCrimsonIsleFog(boolean v) {
        fixCrimsonIsleFog = v;
    }

    public boolean isHideFallingBlocks() {
        return hideFallingBlocks && SkyblockGate.allows();
    }

    public boolean getHideFallingBlocksRaw() {
        return hideFallingBlocks;
    }

    public void setHideFallingBlocks(boolean v) {
        hideFallingBlocks = v;
    }

    public boolean isHideLightning() {
        return hideLightning && SkyblockGate.allows();
    }

    public boolean getHideLightningRaw() {
        return hideLightning;
    }

    public void setHideLightning(boolean v) {
        hideLightning = v;
    }

    public boolean isHideRecipeBookButton() {
        return hideRecipeBookButton && SkyblockGate.allows();
    }

    public boolean getHideRecipeBookButtonRaw() {
        return hideRecipeBookButton;
    }

    public void setHideRecipeBookButton(boolean v) {
        hideRecipeBookButton = v;
    }

    // --- QUOI Name Tags -----------------------------------------------------------------------------

    public boolean isCancelVanillaNametags() {
        return cancelVanillaNametags && SkyblockGate.allows();
    }

    public boolean getCancelVanillaNametagsRaw() {
        return cancelVanillaNametags;
    }

    public void setCancelVanillaNametags(boolean v) {
        cancelVanillaNametags = v;
    }

    // --- QUOI Player Display -> Hide -----------------------------------------------------------------

    public boolean isHideHealthBar() {
        return hideHealthBar && SkyblockGate.allows();
    }

    public boolean getHideHealthBarRaw() {
        return hideHealthBar;
    }

    public void setHideHealthBar(boolean v) {
        hideHealthBar = v;
    }

    public boolean isHideAbsorptionHearts() {
        return hideAbsorptionHearts && SkyblockGate.allows();
    }

    public boolean getHideAbsorptionHeartsRaw() {
        return hideAbsorptionHearts;
    }

    public void setHideAbsorptionHearts(boolean v) {
        hideAbsorptionHearts = v;
    }

    public boolean isHideMountHealthBar() {
        return hideMountHealthBar && SkyblockGate.allows();
    }

    public boolean getHideMountHealthBarRaw() {
        return hideMountHealthBar;
    }

    public void setHideMountHealthBar(boolean v) {
        hideMountHealthBar = v;
    }

    public boolean isHideRegenBounce() {
        return hideRegenBounce && SkyblockGate.allows();
    }

    public boolean getHideRegenBounceRaw() {
        return hideRegenBounce;
    }

    public void setHideRegenBounce(boolean v) {
        hideRegenBounce = v;
    }

    public boolean isHideArmorBar() {
        return hideArmorBar && SkyblockGate.allows();
    }

    public boolean getHideArmorBarRaw() {
        return hideArmorBar;
    }

    public void setHideArmorBar(boolean v) {
        hideArmorBar = v;
    }

    public boolean isHideHungerBar() {
        return hideHungerBar && SkyblockGate.allows();
    }

    public boolean getHideHungerBarRaw() {
        return hideHungerBar;
    }

    public void setHideHungerBar(boolean v) {
        hideHungerBar = v;
    }

    // --- QUOI Tweaks --------------------------------------------------------------------------------

    public boolean isDisableItemCooldowns() {
        return disableItemCooldowns && SkyblockGate.allows();
    }

    public boolean getDisableItemCooldownsRaw() {
        return disableItemCooldowns;
    }

    public void setDisableItemCooldowns(boolean v) {
        disableItemCooldowns = v;
    }

    // --- QUOI Item Animations ------------------------------------------------------------------------

    public boolean isNoEatAnimation() {
        return noEatAnimation && SkyblockGate.allows();
    }

    public boolean getNoEatAnimationRaw() {
        return noEatAnimation;
    }

    public void setNoEatAnimation(boolean v) {
        noEatAnimation = v;
    }

    public boolean isNoShortbowSwing() {
        return noShortbowSwing && SkyblockGate.allows();
    }

    public boolean getNoShortbowSwingRaw() {
        return noShortbowSwing;
    }

    public void setNoShortbowSwing(boolean v) {
        noShortbowSwing = v;
    }

    // --- QUOI Chat Replacements ----------------------------------------------------------------------

    public boolean isHideUselessMessages() {
        return hideUselessMessages && SkyblockGate.allows();
    }

    public boolean getHideUselessMessagesRaw() {
        return hideUselessMessages;
    }

    public void setHideUselessMessages(boolean v) {
        hideUselessMessages = v;
    }

    public boolean isHideDiscordWarnings() {
        return hideDiscordWarnings && SkyblockGate.allows();
    }

    public boolean getHideDiscordWarningsRaw() {
        return hideDiscordWarnings;
    }

    public void setHideDiscordWarnings(boolean v) {
        hideDiscordWarnings = v;
    }

    public boolean isHideMicrosoftWarnings() {
        return hideMicrosoftWarnings && SkyblockGate.allows();
    }

    public boolean getHideMicrosoftWarningsRaw() {
        return hideMicrosoftWarnings;
    }

    public void setHideMicrosoftWarnings(boolean v) {
        hideMicrosoftWarnings = v;
    }

    public boolean isHideEmptyChatMessages() {
        return hideEmptyChatMessages && SkyblockGate.allows();
    }

    public boolean getHideEmptyChatMessagesRaw() {
        return hideEmptyChatMessages;
    }

    public void setHideEmptyChatMessages(boolean v) {
        hideEmptyChatMessages = v;
    }

    public boolean isHideActionbar() {
        return hideActionbar && SkyblockGate.allows();
    }

    public boolean getHideActionbarRaw() {
        return hideActionbar;
    }

    public void setHideActionbar(boolean v) {
        hideActionbar = v;
    }

    public boolean isHideNonRankInvites() {
        return hideNonRankInvites && SkyblockGate.allows();
    }

    public boolean getHideNonRankInvitesRaw() {
        return hideNonRankInvites;
    }

    public void setHideNonRankInvites(boolean v) {
        hideNonRankInvites = v;
    }

    // --- QUOI 1.1.1 Hide Players -------------------------------------------------------------------------

    public boolean isHidePlayers() {
        return hidePlayers && SkyblockGate.allows();
    }

    public boolean getHidePlayersRaw() {
        return hidePlayers;
    }

    public void setHidePlayers(boolean v) {
        hidePlayers = v;
    }

    /** 0 = no distance cap (hide regardless of range). */
    public int getHidePlayersDistance() {
        return hidePlayersDistance;
    }

    public void setHidePlayersDistance(int v) {
        hidePlayersDistance = Math.max(0, Math.min(128, v));
    }

    public boolean isHidePlayersDungeonOnly() {
        return hidePlayersDungeonOnly;
    }

    public void setHidePlayersDungeonOnly(boolean v) {
        hidePlayersDungeonOnly = v;
    }

    public boolean isHidePlayersBossOnly() {
        return hidePlayersBossOnly;
    }

    public void setHidePlayersBossOnly(boolean v) {
        hidePlayersBossOnly = v;
    }
}
