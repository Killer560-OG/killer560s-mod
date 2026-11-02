package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.spotify.SongShareConfig;
import com.killer560.hub.supporters.SupportersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Spotify F5 rows, built in two places (killer560, 2026-11-01: "Move the whole f5 section to there as well. But actually
 * double face them. So the settings exist in both areas and always keep the same value"): the Spotify Lyrics tab's
 * "Lyrics In F5" section and Player Cosmetics' "Spotify In F5" section. Both are built by this one method and every row
 * reads and writes the same config instance ({@link SongShareConfig}, and {@link SupportersConfig} for My Name in F5),
 * fresh on every click, so a change in one place is the value in the other the next time it is drawn.
 */
final class SpotifyF5Rows {

    private SpotifyF5Rows() {
    }

    private static SongShareConfig tag() {
        return SongShareConfig.getInstance();
    }

    /**
     * Adds the rows at {@code y} and returns the y under them. {@code withMyName}: also the My Name in F5 row (the
     * Spotify tab has it; Player Cosmetics already shows its own just above).
     */
    static int build(List<AbstractWidget> widgets, int x, int y, int w, boolean withMyName, Runnable requestRebuild) {
        int half = (w - 8) / 2;
        widgets.add(BaseTab.onOffButton("My Lyrics In F5", () -> tag().isLyricsOverHead(),
                v -> tag().setLyricsOverHead(v), () -> tag().save(), null, x, y, half, 20));
        widgets.add(BaseTab.onOffButton("Highlight Word In F5", () -> tag().isHighlightWordInF5(),
                v -> tag().setHighlightWordInF5(v), () -> tag().save(), null, x + half + 8, y, w - half - 8, 20));
        y += 24;
        widgets.add(BaseTab.onOffButton("Song In F5", () -> tag().isSongInF5(),
                v -> tag().setSongInF5(v), () -> tag().save(), null, x, y, withMyName ? half : w, 20));
        if (withMyName) {
            widgets.add(BaseTab.onOffButton("My Name in F5", () -> SupportersConfig.getInstance().isOwnNameInF5(),
                    v -> SupportersConfig.getInstance().setOwnNameInF5(v), () -> SupportersConfig.getInstance().save(),
                    null, x + half + 8, y, w - half - 8, 20));
        }
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(colorsText(), (btn, dir) -> {
                    tag().setCustomF5Colors(!tag().isCustomF5Colors());
                    tag().save();
                    requestRebuild.run();   // the two color rows come and go
                }).bounds(x, y, w, 20).build());
        y += 24;
        if (tag().isCustomF5Colors()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("F5 Text Color", tag().getF5TextColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "F5 Text Color",
                                tag().getF5TextColor(), SongShareConfig.DEFAULT_F5_TEXT, argb -> {
                            tag().setF5TextColor(argb);
                            tag().save();
                        }));
                    }).bounds(x, y, half, 20).build());
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("F5 Highlight Color", tag().getF5HighlightColor()),
                    btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "F5 Highlight Color",
                                tag().getF5HighlightColor(), SongShareConfig.DEFAULT_F5_HIGHLIGHT, argb -> {
                            tag().setF5HighlightColor(argb);
                            tag().save();
                        }));
                    }).bounds(x + half + 8, y, w - half - 8, 20).build());
            y += 24;
        }
        return y;
    }

    private static Component colorsText() {
        return Component.literal("F5 Colors: §b" + (tag().isCustomF5Colors() ? "Custom" : "Menu Theme"));
    }
}
