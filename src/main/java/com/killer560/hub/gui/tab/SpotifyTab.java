package com.killer560.hub.gui.tab;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.spotify.SpotifyLyricsFeature;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Path INSTRUCTIONS_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-spotify-setup-guide.txt");

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

        // Whether it is actually reading Spotify right now.
        //
        // The one thing this tab genuinely needs, because with no settings left to get wrong, "no lyrics" has
        // exactly two causes - the feature is off, or Spotify is not open - and this says which.
        widgets.add(new StringWidget(contentX, y, contentWidth, 10, statusText(),
                Minecraft.getInstance().font));
        y += 16;

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

        widgets.add(SettingsButtonWidget.builder(Component.literal("Instructions"), btn -> openInstructions())
                .bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static void openInstructions() {
        try {
            Files.writeString(INSTRUCTIONS_FILE, INSTRUCTIONS_TEXT, StandardCharsets.UTF_8);
            // Minecraft runs AWT headless, so java.awt.Desktop threw and the button did nothing (killer560,
            // 2026-09-21). The game's own opener hands the file to Windows like the other "open folder" buttons.
            net.minecraft.util.Util.getPlatform().openPath(INSTRUCTIONS_FILE);
        } catch (Exception e) {
            ModOverlayMessage.show("§c[Killer560's Mod] Couldn't open setup guide: " + e.getMessage(), 4000);
        }
    }

    private static final String INSTRUCTIONS_TEXT = """
            KILLER560'S MOD - SPOTIFY LYRICS
            ================================

            Posts the lyrics of whatever you're playing into your Minecraft chat.

            THERE IS NO SETUP
              Open the Spotify app on this PC and play something. That's it. No
              account, no API key, no website, nothing to log in to and nothing to
              configure. The mod reads what the Spotify app on this computer is
              playing, directly off the app.

              The status line in the Spotify Mod tab says "Reading Spotify" in green
              when it's working.

            IF THE STATUS LINE ISN'T GREEN
              "Spotify is not running on this PC"
                 Open the Spotify desktop app. The mod reads the app on this machine
                 only - it cannot see your phone or the browser web player.

              "reading the Spotify app only works on Windows"
                 Nothing to be done; the feature needs the Windows desktop app.

            TIMING
              There is no offset to set. The mod knows the moment a track changes,
              because the Spotify app tells it instantly, and it stops the lyric
              clock while you're paused and restarts it when you resume.

              The one thing it can't know is where you are inside a track, because
              the app doesn't report that. So if you SKIP FORWARD in a song, or
              start the mod halfway through one, the lyrics will be off until the
              next track begins.

            ADS
              While an ad is playing it says you're listening to an ad instead of
              showing a stale lyric from the song before it.

            SETTINGS
              Chat Destination  - which chat the lyrics go to (party by default).
              Lyrics Mode       - every line, or just the song title on each change.
              Profanity Filter  - censors words before they are sent.

            WHAT LEAVES YOUR PC
              Only the artist and title, sent to lrclib.net to look up the lyrics.
              Nothing else - no account, no key, no listening history.
            """;

    private static Component enabledText() {
        return Component.literal("Mod Enabled: " + (SpotifyLyricsFeature.enabled ? "§aON" : "§cOFF"));
    }

    private static Component statusText() {
        String status = SpotifyLyricsFeature.sourceStatus();
        boolean working = status.startsWith("Reading ");
        return Component.literal((working ? "§a" : "§e") + status);
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
