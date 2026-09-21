package com.killer560.hub.gui.tab;

import com.killer560.hub.blessings.Blessing;
import com.killer560.hub.blessings.BlessingsConfig;
import com.killer560.hub.blessings.BlessingsFeature;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Blessings settings - the dungeon blessing tracker's HUD, which blessings it shows, and its announcements
 * (see {@link BlessingsFeature}). Everything defaults OFF.
 */
public class BlessingsTab extends BaseTab {

    public BlessingsTab() {
        super("Blessings");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        BlessingsConfig cfg = BlessingsConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Blessings HUD", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Blessings HUD", cfg::getHudRaw, cfg::setHud));
        widgets.add(toggle(col2X, y, colW, "Roman Numerals", cfg::isRomanNumerals, cfg::setRomanNumerals));
        y += 28;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Shown Blessings", false), mc.font));
        y += 16;
        boolean left = true;
        for (Blessing blessing : Blessing.values()) {
            int x = left ? contentX : col2X;
            widgets.add(toggle(x, y, colW, blessing.displayName(), () -> cfg.isShownRaw(blessing),
                    v -> cfg.setShown(blessing, v)));
            if (!left) {
                y += 22;
            }
            left = !left;
        }
        if (!left) {
            y += 22;
        }
        y += 6;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Colors", false), mc.font));
        y += 16;
        left = true;
        for (Blessing blessing : Blessing.values()) {
            int x = left ? contentX : col2X;
            widgets.add(colorButton(x, y, colW, blessing.displayName() + " Color", cfg.getColor(blessing),
                    blessing.defaultColor(), argb -> cfg.setColor(blessing, argb)));
            if (!left) {
                y += 22;
            }
            left = !left;
        }
        if (!left) {
            y += 22;
        }
        y += 6;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Announcements", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Announce In Chat", cfg::getAnnounceChatRaw, cfg::setAnnounceChat));
        widgets.add(toggle(col2X, y, colW, "Announce In Party", cfg::getAnnouncePartyRaw, cfg::setAnnounceParty));
        y += 22;

        // "Levels only - no source gives a blessing stat table" already lives in the tab's own tooltip.
        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                BlessingsConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            BlessingsConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
