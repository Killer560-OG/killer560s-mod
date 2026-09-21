package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.secrets.SecretsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Full Block settings (class renamed from {@code SecretsTab} to {@code FullBlockTab} 2026-09-21, freeing
 *  up the "Secrets" name for the new folder tab that now hosts this as one of its sections - see
 *  {@link SecretsTab} - per killer560's menu-structure request ("secret sound, messages, secret waypoints,
 *  etherwarp waypoints, Secret Aura, Secret Triggerbot and Lever Aura all in one place"); Full Block wasn't
 *  named in that list, but it is the same kind of dungeon-secrets cheat feature as the rest of the new
 *  folder's contents (expanded hitboxes for secret chests/levers/buttons/essence) and having a second,
 *  separate "Secrets"-branded home for it would only recreate the naming collision this move is meant to
 *  fix - so it moved in alongside them rather than staying a lone top-level Dungeon tab. Behaviour is
 *  unchanged: a master toggle on top, then an independent toggle for each covered block type's expanded
 *  interaction hitbox - killer560's explicit request (2026-09-09), "each should have their own toggle
 *  under a main secrets tab in dungeons." The master toggle is a later follow-up request so the whole
 *  feature can be flipped off in one click without losing each block type's individual on/off state - per
 *  killer560's explicit follow-up ("make it hide the other settings if it is off or on"), every setting
 *  below the master toggle is only actually built (not just disabled-looking) while it's ON, collapsing
 *  the tab down to just the master toggle itself while it's OFF. Buttons additionally gets a Flat/Full Box
 *  shape choice, plus two further optional restriction toggles (also killer560's explicit follow-up
 *  request, 2026-09-09): Dungeons Only (all 4 types) and Boss Only (Levers/Buttons only, restricts to the
 *  real F7/M7 boss fight). See {@link com.killer560.hub.secrets.SecretsFeature} for the real mechanic
 *  (ported from and cross-checked against both quoi's and NoammAddons' own reference implementations) and
 *  {@link com.killer560.hub.secrets.DungeonState} for how the two restriction toggles are really
 *  detected. */
public class FullBlockTab extends BaseTab {

    public FullBlockTab() {
        super("Full Block");
    }

    /** Only added to {@link SecretsTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(masterText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setMasterEnabled(!cfg.isMasterEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!SecretsConfig.getInstance().isMasterEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(leversText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setLeversEnabled(!cfg.isLeversEnabled());
                    cfg.save();
                    btn.setMessage(leversText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(buttonsText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setButtonsEnabled(!cfg.isButtonsEnabled());
                    cfg.save();
                    btn.setMessage(buttonsText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(buttonShapeText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setButtonsFullBox(!cfg.isButtonsFullBox());
                    cfg.save();
                    btn.setMessage(buttonShapeText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(customSizeText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setButtonsCustomSize(!cfg.isButtonsCustomSize());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        if (SecretsConfig.getInstance().isButtonsCustomSize()) {
            SecretsConfig cfg = SecretsConfig.getInstance();
            y = sizeSlider(widgets, contentX, y, "Width", cfg::getButtonWidthPct, cfg::setButtonWidthPct);
            y = sizeSlider(widgets, contentX, y, "Height", cfg::getButtonHeightPct, cfg::setButtonHeightPct);
            y = sizeSlider(widgets, contentX, y, "Length", cfg::getButtonLengthPct, cfg::setButtonLengthPct);
        }
        y += 2;

        widgets.add(SettingsButtonWidget.builder(chestsText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setChestsEnabled(!cfg.isChestsEnabled());
                    cfg.save();
                    btn.setMessage(chestsText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(essenceText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setEssenceEnabled(!cfg.isEssenceEnabled());
                    cfg.save();
                    btn.setMessage(essenceText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Restrict when these apply:"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(dungeonsOnlyText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setDungeonsOnly(!cfg.isDungeonsOnly());
                    cfg.save();
                    btn.setMessage(dungeonsOnlyText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(bossOnlyText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setBossOnly(!cfg.isBossOnly());
                    cfg.save();
                    btn.setMessage(bossOnlyText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    /** One Normal-to-Full slider: far left is the normal button size, far right a full block along that axis. */
    private static int sizeSlider(List<AbstractWidget> widgets, int x, int y, String name,
                                  java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
        widgets.add(new com.killer560.hub.gui.ThemedSliderButton(x, y, 220, 18, sizeLabel(name, get.getAsInt()),
                get.getAsInt() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(sizeLabel(name, (int) Math.round(this.value * 100.0)));
            }

            @Override
            protected void applyValue() {
                set.accept((int) Math.round(this.value * 100.0));
                SecretsConfig.getInstance().save();
            }
        });
        return y + 22;
    }

    private static Component sizeLabel(String name, int pct) {
        String value = pct <= 0 ? "Normal" : pct >= 100 ? "Full" : pct + "%";
        return Component.literal(name + ": §b" + value);
    }

    private static Component customSizeText() {
        return Component.literal("Custom Button Size: " + (SecretsConfig.getInstance().isButtonsCustomSize() ? "§aON" : "§cOFF"));
    }

    private static Component masterText() {
        return Component.literal("Full Block: " + (SecretsConfig.getInstance().isMasterEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component leversText() {
        return Component.literal("Levers: " + (SecretsConfig.getInstance().isLeversEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component buttonsText() {
        return Component.literal("Buttons: " + (SecretsConfig.getInstance().isButtonsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component buttonShapeText() {
        return Component.literal("Button Shape: §b" + (SecretsConfig.getInstance().isButtonsFullBox() ? "Full Box" : "Flat"));
    }

    private static Component chestsText() {
        return Component.literal("Chests: " + (SecretsConfig.getInstance().isChestsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component essenceText() {
        return Component.literal("Wither Essence: " + (SecretsConfig.getInstance().isEssenceEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component dungeonsOnlyText() {
        return Component.literal("Dungeons Only: " + (SecretsConfig.getInstance().isDungeonsOnly() ? "§aON" : "§cOFF"));
    }

    private static Component bossOnlyText() {
        return Component.literal("Boss Only (Levers/Buttons): " + (SecretsConfig.getInstance().isBossOnly() ? "§aON" : "§cOFF"));
    }
}
