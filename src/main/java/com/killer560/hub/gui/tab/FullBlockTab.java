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

        SecretsConfig cfg0 = SecretsConfig.getInstance();
        y = blockType(widgets, contentX, y, requestRebuild, "Levers",
                cfg0::isLeversEnabled, cfg0::setLeversEnabled, false,
                cfg0::getLeverShape, cfg0::setLeverShape,
                cfg0::getLeverWidthPct, cfg0::setLeverWidthPct, cfg0::getLeverHeightPct, cfg0::setLeverHeightPct,
                cfg0::getLeverLengthPct, cfg0::setLeverLengthPct);
        y = blockType(widgets, contentX, y, requestRebuild, "Buttons",
                cfg0::isButtonsEnabled, cfg0::setButtonsEnabled, true,
                cfg0::getButtonShape, cfg0::setButtonShape,
                cfg0::getButtonWidthPct, cfg0::setButtonWidthPct, cfg0::getButtonHeightPct, cfg0::setButtonHeightPct,
                cfg0::getButtonLengthPct, cfg0::setButtonLengthPct);
        y = blockType(widgets, contentX, y, requestRebuild, "Chests",
                cfg0::isChestsEnabled, cfg0::setChestsEnabled, false,
                cfg0::getChestShape, cfg0::setChestShape,
                cfg0::getChestWidthPct, cfg0::setChestWidthPct, cfg0::getChestHeightPct, cfg0::setChestHeightPct,
                cfg0::getChestLengthPct, cfg0::setChestLengthPct);
        y = blockType(widgets, contentX, y, requestRebuild, "Wither Essence",
                cfg0::isEssenceEnabled, cfg0::setEssenceEnabled, false,
                cfg0::getEssenceShape, cfg0::setEssenceShape,
                cfg0::getEssenceWidthPct, cfg0::setEssenceWidthPct, cfg0::getEssenceHeightPct, cfg0::setEssenceHeightPct,
                cfg0::getEssenceLengthPct, cfg0::setEssenceLengthPct);
        y += 4;
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

    /** One block type: its on/off toggle, then (while on) its Shape, then (only on Custom) its three size sliders.
     *  Buttons cycle Flat / Full / Custom; the other types never had a Flat shape, so they cycle Full / Custom. */
    private static int blockType(List<AbstractWidget> widgets, int x, int y, Runnable requestRebuild, String name,
                                 java.util.function.BooleanSupplier enabled, java.util.function.Consumer<Boolean> setEnabled,
                                 boolean hasFlat,
                                 java.util.function.Supplier<SecretsConfig.Shape> shape,
                                 java.util.function.Consumer<SecretsConfig.Shape> setShape,
                                 java.util.function.IntSupplier w, java.util.function.IntConsumer setW,
                                 java.util.function.IntSupplier h, java.util.function.IntConsumer setH,
                                 java.util.function.IntSupplier l, java.util.function.IntConsumer setL) {
        widgets.add(SettingsButtonWidget.builder(onOff(name, enabled.getAsBoolean()), btn -> {
                    setEnabled.accept(!enabled.getAsBoolean());
                    SecretsConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(x, y, 220, 20).build());
        y += 24;
        if (!enabled.getAsBoolean()) {
            return y + 2;
        }
        widgets.add(SettingsButtonWidget.builder(shapeText(name, shape.get()), btn -> {
                    setShape.accept(nextShape(shape.get(), hasFlat));
                    SecretsConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(x + 10, y, 210, 20).build());
        y += 24;
        if (shape.get() == SecretsConfig.Shape.CUSTOM) {
            y = sizeSlider(widgets, x + 10, y, "Width", w, setW);
            y = sizeSlider(widgets, x + 10, y, "Height", h, setH);
            y = sizeSlider(widgets, x + 10, y, "Length", l, setL);
        }
        return y + 2;
    }

    private static SecretsConfig.Shape nextShape(SecretsConfig.Shape current, boolean hasFlat) {
        return switch (current) {
            case FLAT -> SecretsConfig.Shape.FULL;
            case FULL -> SecretsConfig.Shape.CUSTOM;
            case CUSTOM -> hasFlat ? SecretsConfig.Shape.FLAT : SecretsConfig.Shape.FULL;
        };
    }

    private static Component shapeText(String name, SecretsConfig.Shape shape) {
        String label = switch (shape) {
            case FLAT -> "Flat";
            case FULL -> "Full";
            case CUSTOM -> "Custom";
        };
        return Component.literal(singular(name) + " Shape: \u00a7b" + label);
    }

    private static String singular(String name) {
        return switch (name) {
            case "Levers" -> "Lever";
            case "Buttons" -> "Button";
            case "Chests" -> "Chest";
            default -> "Essence";
        };
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "\u00a7aON" : "\u00a7cOFF"));
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


    private static Component masterText() {
        return Component.literal("Full Block: " + (SecretsConfig.getInstance().isMasterEnabled() ? "§aON" : "§cOFF"));
    }






    private static Component dungeonsOnlyText() {
        return Component.literal("Dungeons Only: " + (SecretsConfig.getInstance().isDungeonsOnly() ? "§aON" : "§cOFF"));
    }

    private static Component bossOnlyText() {
        return Component.literal("Boss Only (Levers/Buttons): " + (SecretsConfig.getInstance().isBossOnly() ? "§aON" : "§cOFF"));
    }
}
