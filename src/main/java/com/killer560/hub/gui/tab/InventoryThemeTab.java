package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.ScreenThemes;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.inventorytheme.InventoryCustomizeScreen;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Custom Inventory Overlay settings - killer560's item 5.8, "Custom inventory overlay in the mod's
 *  theme." See {@link com.killer560.hub.inventorytheme.InventoryThemeFeature}'s class doc for exactly
 *  which vanilla screens/draws this re-skins. In-panel explanatory text is intentionally left out per
 *  the mod-wide "no in-panel paragraphs" rule - hover each control for its tooltip.
 *  <p>
 *  Rearranged 2026-10-18 (killer560): there is no Inventory Theme row in here (the card's switch is that setting); the first
 *  row is the Theme - Default follows the mod menu's theme, then the menu's themes, Vanilla and Custom - and while it is on
 *  Custom a Customize row opens {@link InventoryCustomizeScreen}. Hide Potion Effects is gone (always on with the theme),
 *  and the Hotbar Scale slider sits with Theme Hotbar. */
public class InventoryThemeTab extends BaseTab {

    public InventoryThemeTab() {
        super("Inventory Theme");
    }

    /** The card's switch, built with every drawer but not drawn in it: the setting is the card's own switch (killer560,
     *  2026-10-18: "remove the inventory theme option because if the setting itself is on then that is on"). */
    private SettingsButtonWidget cardSwitch;

    @Override
    public SettingsButtonWidget masterToggle(List<AbstractWidget> built) {
        return cardSwitch;
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return cardSwitch == null ? List.of() : List.of(cardSwitch);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();

        cardSwitch = SettingsButtonWidget.builder(enabledText(cfg), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, contentY, contentWidth, 20).build();

        // The Theme: Default is the mod menu's own theme; scroll to another, to Vanilla, or to Custom.
        widgets.add(SettingsButtonWidget.cycling(themeText(cfg), (btn, dir) -> {
                    List<String> all = themeChoices();
                    int i = Math.max(0, all.indexOf(cfg.getChoice()));
                    cfg.setChoice(all.get(SettingsButtonWidget.step(i, dir, all.size())));
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (cfg.isCustom()) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("Customise"), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new InventoryCustomizeScreen(McCompat.screen(client)));
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 26;
        }

        widgets.add(SettingsButtonWidget.cycling(scopeText(cfg), (btn, dir) -> {
                    cfg.setHypixelOnly(!cfg.isHypixelOnly());
                    cfg.save();
                    btn.setMessage(scopeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(hotbarText(cfg), btn -> {
                    cfg.setThemeHotbar(!cfg.isThemeHotbar());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isThemeHotbar()) {
            double scaleNorm = (cfg.getHotbarScale() - InventoryThemeConfig.MIN_HOTBAR_SCALE)
                    / (InventoryThemeConfig.MAX_HOTBAR_SCALE - InventoryThemeConfig.MIN_HOTBAR_SCALE);
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, hotbarScaleText(cfg), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(hotbarScaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    // Whole percent steps, so the label and the saved value always agree.
                    float raw = (float) (InventoryThemeConfig.MIN_HOTBAR_SCALE + this.value
                            * (InventoryThemeConfig.MAX_HOTBAR_SCALE - InventoryThemeConfig.MIN_HOTBAR_SCALE));
                    cfg.setHotbarScale(Math.round(raw * 100f) / 100f);
                    cfg.save();
                }
            });
            y += 26;
        }

        return widgets;
    }

    /** Default, the menu's presets, every theme he saved (read live), then Vanilla and Custom. The menu's own Custom
     *  colours are not a choice of their own: Default already is whatever the menu is on. */
    public static List<String> themeChoices() {
        List<String> out = new ArrayList<>();
        for (String c : ScreenThemes.builtInChoices()) {
            if (!MenuTheme.CUSTOM.name().equals(c)) {
                out.add(c);
            }
        }
        out.add(InventoryThemeConfig.VANILLA_CHOICE);
        out.add(InventoryThemeConfig.CUSTOM_CHOICE);
        return out;
    }

    /** A Theme choice as the button shows it. */
    public static String choiceLabel(String choice) {
        if (ScreenThemes.DEFAULT.equals(choice)) {
            return "Default";
        }
        if (InventoryThemeConfig.VANILLA_CHOICE.equals(choice)) {
            return "Vanilla";
        }
        if (InventoryThemeConfig.CUSTOM_CHOICE.equals(choice)) {
            return "Custom";
        }
        if (ScreenThemes.isSaved(choice) && ScreenThemes.savedTheme(choice) != null) {
            return MenuConfig.shownName(ScreenThemes.savedTheme(choice));
        }
        return ScreenThemes.label(choice.toUpperCase(Locale.ROOT));
    }

    private static Component enabledText(InventoryThemeConfig cfg) {
        return Component.literal("Inventory Theme: " + (cfg.isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component scopeText(InventoryThemeConfig cfg) {
        return Component.literal("Applies To: " + (cfg.isHypixelOnly() ? "Hypixel/p3sim Menus Only" : "Every Container"));
    }

    private static Component themeText(InventoryThemeConfig cfg) {
        return Component.literal("Theme: " + choiceLabel(cfg.getChoice()));
    }

    private static Component hotbarText(InventoryThemeConfig cfg) {
        return Component.literal("Theme Hotbar: " + (cfg.isThemeHotbar() ? "§aON" : "§cOFF"));
    }

    private static Component hotbarScaleText(InventoryThemeConfig cfg) {
        return Component.literal(String.format("Hotbar Scale: %.0f%%", cfg.getHotbarScale() * 100));
    }
}
