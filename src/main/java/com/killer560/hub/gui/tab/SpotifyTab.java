package com.killer560.hub.gui.tab;

import com.killer560.hub.spotify.SpotifyHudConfig;
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
        super("Spotify Lyrics");
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

        // No status line at all: the "Reading Spotify" line went on 2026-09-30 and the "Spotify is not running"
        // one on 2026-10-18 (killer560).

        widgets.add(SettingsButtonWidget.builder(destText(), btn -> {
                    SpotifyLyricsFeature.chatDestination = SpotifyLyricsFeature.chatDestination.next();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(destText());
                }).secondaryPress(btn -> {
                    SpotifyLyricsFeature.chatDestination = SpotifyLyricsFeature.chatDestination.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(destText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.cycling(detailText(), (btn, dir) -> {
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

        // The Spotify HUD (2026-10-20): its own switch, independent of Mod Enabled (which only sends to chat).
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("Spotify HUD", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("Spotify HUD", hud().isHudEnabled()), btn -> {
                    hud().setHudEnabled(!hud().isHudEnabled());
                    hud().save();
                    btn.setMessage(onOff("Spotify HUD", hud().isHudEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(onOff("HUD Lyrics", hud().isShowLyrics()), btn -> {
                    hud().setShowLyrics(!hud().isShowLyrics());
                    hud().save();
                    btn.setMessage(onOff("HUD Lyrics", hud().isShowLyrics()));
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Word Highlight", hud().isWordHighlight()), btn -> {
                    hud().setWordHighlight(!hud().isWordHighlight());
                    hud().save();
                    btn.setMessage(onOff("Word Highlight", hud().isWordHighlight()));
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(onOff("Hide When Paused", hud().isHideWhenPaused()), btn -> {
                    hud().setHideWhenPaused(!hud().isHideWhenPaused());
                    hud().save();
                    btn.setMessage(onOff("Hide When Paused", hud().isHideWhenPaused()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        return widgets;
    }

    /** Chat lyrics and the HUD are separate features: the card is ON while either is. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Mod Enabled", "Spotify HUD");
    }

    /** Read fresh each time: applying a profile replaces the config instance. */
    private static SpotifyHudConfig hud() {
        return SpotifyHudConfig.getInstance();
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
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
