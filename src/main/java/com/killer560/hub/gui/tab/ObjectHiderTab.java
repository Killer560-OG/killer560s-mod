package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.nofire.NoFireConfig;
import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Toggles that hide specific vanilla/Hypixel screen elements, entities and particles. Started (2026-09-08) as a
 *  single "No Fire Overlay" switch; expanded (2026-09-16) into the full Object Hider pack backed by
 *  {@link com.killer560.hub.objecthider.ObjectHiderFeature} - the biggest FPS/visibility win available in M7 P5
 *  and F7 P3. Everything here is legit: pure client-side render suppression, no entity is ever removed from the
 *  world. Every switch defaults to OFF. The whole list scrolls (ModScreen owns scrolling), so it's laid out as
 *  one column of full-width rows under orange section headers. */
public class ObjectHiderTab extends BaseTab {

    public ObjectHiderTab() {
        super("Object Hider");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        int[] y = {contentY};

        header(widgets, contentX, contentWidth, y, "Screen");

        widgets.add(SettingsButtonWidget.builder(onOff("No Fire Overlay", NoFireConfig.getInstance().isEnabled()), btn -> {
                    NoFireConfig nf = NoFireConfig.getInstance();
                    nf.setEnabled(!nf.isEnabled());
                    nf.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Blindness Effect", cfg::getDisableBlindnessRaw, cfg::setDisableBlindness);

        header(widgets, contentX, contentWidth, y, "Dungeon Clutter");

        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Healer Fairy", cfg::getHideFairyRaw, cfg::setHideFairy);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Power Orbs", cfg::getHideHealerOrbsRaw, cfg::setHideHealerOrbs);
        if (cfg.getHideHealerOrbsRaw()) {
            toggle(widgets, contentX + 12, contentWidth - 12, y, cfg, requestRebuild,
                    "Keep Nearby Orbs", cfg::isKeepNearbyOrbs, cfg::setKeepNearbyOrbs);
            if (cfg.isKeepNearbyOrbs()) {
                int min = 1;
                int max = 20;
                widgets.add(new ThemedSliderButton(contentX + 12, y[0], contentWidth - 12, 18,
                        orbRadiusText(cfg), (cfg.getKeepOrbRadius() - min) / (double) (max - min)) {
                    @Override
                    protected void updateMessage() {
                        setMessage(orbRadiusText(cfg));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setKeepOrbRadius((int) Math.round(min + this.value * (max - min)));
                        cfg.save();
                    }
                });
                y[0] += 22;
            }
        }
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Soulweaver Skulls", cfg::getHideSoulweaverSkullsRaw, cfg::setHideSoulweaverSkulls);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Archer Passive", cfg::getHideArcherPassiveRaw, cfg::setHideArcherPassive);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Sheep", cfg::getHideSheepRaw, cfg::setHideSheep);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Wither Cloak Creepers", cfg::getHideCloakCreepersRaw, cfg::setHideCloakCreepers);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Wither Shield Hearts", cfg::getHideHypeHeartsRaw, cfg::setHideHypeHearts);

        header(widgets, contentX, contentWidth, y, "M7 Boss");

        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Dying Dragons", cfg::getHideDyingDragonsRaw, cfg::setHideDyingDragons);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Wither King Model", cfg::getHideWitherKingRaw, cfg::setHideWitherKing);

        header(widgets, contentX, contentWidth, y, "End Of Run");

        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Boss Damage Splash", cfg::getHideBossDamageSplashRaw, cfg::setHideBossDamageSplash);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Clean End", cfg::getCleanEndRaw, cfg::setCleanEnd);
        if (cfg.getCleanEndRaw()) {
            toggle(widgets, contentX + 12, contentWidth - 12, y, cfg, requestRebuild,
                    "F3/M3 Keep Guardians", cfg::isCleanEndKeepGuardians, cfg::setCleanEndKeepGuardians);
        }

        header(widgets, contentX, contentWidth, y, "World");

        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Grounded Arrows", cfg::getHideGroundedArrowsRaw, cfg::setHideGroundedArrows);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Death Animations", cfg::getHideDeathAnimationsRaw, cfg::setHideDeathAnimations);
        if (cfg.getHideDeathAnimationsRaw()) {
            toggle(widgets, contentX + 12, contentWidth - 12, y, cfg, requestRebuild,
                    "Hide Dead Nametags", cfg::isHideDeadNametags, cfg::setHideDeadNametags);
        }
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Block Break Particles", cfg::getHideBlockBreakParticlesRaw, cfg::setHideBlockBreakParticles);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Explosion Particles", cfg::getHideExplosionParticlesRaw, cfg::setHideExplosionParticles);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Smoke Particles", cfg::getHideSmokeParticlesRaw, cfg::setHideSmokeParticles);

        return widgets;
    }

    private void header(List<AbstractWidget> widgets, int x, int width, int[] y, String title) {
        y[0] += 6;
        widgets.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false),
                Minecraft.getInstance().font));
        y[0] += 16;
    }

    private void toggle(List<AbstractWidget> widgets, int x, int width, int[] y, ObjectHiderConfig cfg,
                        Runnable requestRebuild, String label, BooleanSupplier get, Consumer<Boolean> set) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
                    set.accept(!get.getAsBoolean());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Component orbRadiusText(ObjectHiderConfig cfg) {
        return Component.literal("Keep Radius: " + cfg.getKeepOrbRadius() + " blocks");
    }
}
