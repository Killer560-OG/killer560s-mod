package com.killer560.hub.gui.tab;

import com.killer560.hub.spotify.SpotifyLyricsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotify Lyrics settings.
 *
 * <p>Deliberately small. It used to carry a source selector, a Last.fm API key field, a username field and a
 * timing-offset slider; killer560 (2026-09-30): "make it so there is no longer an option to select how it
 * reads it should only ever be able to read spotify" and "is it possible to make it so you no longer need the
 * delay". All four are gone, because the Spotify app is the only source and it reports a track change
 * instantly, so there is nothing to choose between and nothing to compensate for.
 */
public class SpotifyTab extends BaseTab {

    public SpotifyTab() {
        super("Spotify Mod");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        int half = (contentWidth - 8) / 2;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    SpotifyLyricsFeature.enabled = !SpotifyLyricsFeature.enabled;
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        // Only a problem is shown. The "Reading Spotify" line that used to appear when all was well is gone
        // (killer560, 2026-09-30); a failure reason stays, because with the feature on and Spotify closed
        // there is otherwise no way to tell why nothing is posting.
        String problem = SpotifyLyricsFeature.sourceProblem();
        if (!problem.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§e" + problem),
                    Minecraft.getInstance().font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.builder(destText(), btn -> {
                    SpotifyLyricsFeature.chatDestination = SpotifyLyricsFeature.chatDestination.next();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(destText());
                }).secondaryPress(btn -> {
                    SpotifyLyricsFeature.chatDestination = SpotifyLyricsFeature.chatDestination.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(destText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(detailText(), btn -> {
                    SpotifyLyricsFeature.fullLyrics = !SpotifyLyricsFeature.fullLyrics;
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(detailText());
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(profanityText(), btn -> {
                    SpotifyLyricsFeature.profanityLevel = SpotifyLyricsFeature.profanityLevel.next();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(profanityText());
                }).secondaryPress(btn -> {
                    SpotifyLyricsFeature.profanityLevel = SpotifyLyricsFeature.profanityLevel.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(profanityText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Mod Enabled: " + (SpotifyLyricsFeature.enabled ? "§aON" : "§cOFF"));
    }

    private static Component destText() {
        return Component.literal("Chat Destination: §b" + SpotifyLyricsFeature.chatDestination.displayName);
    }

    private static Component detailText() {
        return Component.literal("Lyrics Mode: §b"
                + (SpotifyLyricsFeature.fullLyrics ? "Full Lyrics" : "Song Title Only"));
    }

    private static Component profanityText() {
        return Component.literal("Profanity Filter: §b" + SpotifyLyricsFeature.profanityLevel.displayName);
    }
}
