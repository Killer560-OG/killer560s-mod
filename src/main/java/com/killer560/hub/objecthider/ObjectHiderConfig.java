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
    private boolean hideExplosionParticles = false;
    private boolean hideSmokeParticles = false;

    private ObjectHiderConfig() {
    }

    /** Fast master check for {@link com.killer560.hub.objecthider.ObjectHiderFeature#shouldHideEntity}, which
     *  vanilla calls for EVERY entity EVERY frame (2026-09-20, FPS pass). Reads the raw fields: when not one
     *  entity-hiding toggle is set, every individual check in that method would return false anyway, so the
     *  whole per-entity path - including the armor-stand name/NBT probes - can be skipped outright. */
    public boolean hidesAnyEntity() {
        return hideFairy || hideHealerOrbs || hideSoulweaverSkulls || hideArcherPassive || hideSheep
                || hideCloakCreepers || hideDyingDragons || hideWitherKing || hideBossDamageSplash
                || cleanEnd || hideGroundedArrows || hideDeathAnimations;
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
                cfg.hideExplosionParticles = ConfigJson.getBool(obj, "hideExplosionParticles", false);
                cfg.hideSmokeParticles = ConfigJson.getBool(obj, "hideSmokeParticles", false);
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
            obj.addProperty("hideExplosionParticles", hideExplosionParticles);
            obj.addProperty("hideSmokeParticles", hideSmokeParticles);
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

    public boolean isHideExplosionParticles() {
        return hideExplosionParticles && SkyblockGate.allows();
    }

    public boolean getHideExplosionParticlesRaw() {
        return hideExplosionParticles;
    }

    public void setHideExplosionParticles(boolean v) {
        hideExplosionParticles = v;
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
}
