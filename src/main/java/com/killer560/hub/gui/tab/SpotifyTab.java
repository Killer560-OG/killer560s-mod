package com.killer560.hub.gui.tab;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.spotify.SpotifyLyricsFeature;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Spotify Lyrics settings, folded in as a tab instead of its own screen/command. */
public class SpotifyTab extends BaseTab {

    private static final int STEP_MS = 500;
    private static final int MAX_MS = 15_000;
    private static final int NUM_STEPS = MAX_MS / STEP_MS;

    private static final Path INSTRUCTIONS_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-spotify-setup-guide.txt");

    private boolean onLastFmPage = false;
    private EditBox apiKeyField;
    private EditBox usernameField;

    public SpotifyTab() {
        super("Spotify Mod");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        return onLastFmPage
                ? buildLastFmPage(contentX, contentY, requestRebuild)
                : buildMainPage(contentX, contentY, contentWidth, requestRebuild);
    }

    private List<AbstractWidget> buildMainPage(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        int half = (contentWidth - 8) / 2;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    SpotifyLyricsFeature.enabled = !SpotifyLyricsFeature.enabled;
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        // Where now-playing comes from, and whether that is working right now.
        //
        // The status line under it is the whole reason this is not just a silent setting: "no lyrics" used to
        // be indistinguishable from "Spotify is closed", "you are on Last.fm with no key" and "the feature is
        // off", and every one of those looks like the mod being broken.
        widgets.add(SettingsButtonWidget.builder(sourceText(), btn -> {
                    SpotifyLyricsFeature.source = SpotifyLyricsFeature.source.next();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(sourceText());
                    requestRebuild.run();
                }).secondaryPress(btn -> {
                    SpotifyLyricsFeature.source = SpotifyLyricsFeature.source.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(sourceText());
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        widgets.add(new StringWidget(contentX, y, contentWidth, 10, statusText(),
                Minecraft.getInstance().font));
        y += 14;

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
        y += 24;

        double normalizedOffset = (double) SpotifyLyricsFeature.lyricTimingOffsetMs / MAX_MS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, timingOffsetText(), normalizedOffset) {
            @Override
            protected void updateMessage() {
                setMessage(timingOffsetText());
            }

            @Override
            protected void applyValue() {
                int steps = (int) Math.round(this.value * NUM_STEPS);
                SpotifyLyricsFeature.lyricTimingOffsetMs = steps * STEP_MS;
                SpotifyLyricsFeature.saveConfig();
            }
        });
        y += 26;

        // Hidden on the Spotify-App setting, because there it is five website steps that would do nothing.
        if (SpotifyLyricsFeature.source != com.killer560.hub.spotify.SourceKind.SPOTIFY_APP) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("Last.fm Setup (phone / web player) ->"),
                    btn -> {
                        onLastFmPage = true;
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 26;
        }

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
            KILLER560'S MOD - SPOTIFY LYRICS SETUP GUIDE
            =============================================

            This feature posts the lyrics of whatever song you're playing into your
            Minecraft chat.

            THE SHORT VERSION - THERE IS NO SETUP
              Have the Spotify app open on this PC and play something. That's it.
              No account, no API key, no website, nothing to log in to. The mod reads
              what the Spotify app on this computer is playing, directly.

              Check the "Now Playing From" line in the Spotify Mod tab - if it says
              "Reading Spotify App" in green, it's working.

            IF THE STATUS LINE ISN'T GREEN
              "Spotify is not running on this PC"
                 Open the Spotify desktop app. The mod can only read the app on this
                 machine - it cannot see your phone or the browser web player.

              "reading the Spotify app only works on Windows"
                 Use the Last.fm option below instead.

            IF YOU PLAY FROM YOUR PHONE OR THE WEB PLAYER
              The mod can't read those, so it has to ask Last.fm what your Spotify has
              been playing. This is the only path that needs websites, and it is
              entirely optional:

                1. Make a free account at last.fm
                2. Spotify -> Settings -> Social -> turn on "Last.fm Scrobbling"
                3. Get a key at last.fm/api/account/create (any app name will do)
                4. Spotify Mod tab -> "Last.fm Setup ->" -> paste the key and your
                   username -> Save Credentials
                5. Set "Now Playing From" to Last.fm, or leave it on Automatic and it
                   will use Last.fm whenever the Spotify app isn't open

            SETTINGS WORTH KNOWING
              Chat Destination  - which chat the lyrics go to (party by default).
              Lyrics Mode       - every line, or just the song title on each change.
              Profanity Filter  - censors words before they are sent.
              Lyric Offset      - nudges the lyric timing. On the Spotify App source
                                  this wants to be near 0, because the app reports a
                                  track change instantly. On Last.fm it needs about 5
                                  seconds, because a scrobble takes that long to land.

            WHAT LEAVES YOUR PC
              On the Spotify App source: only the artist and title, sent to lrclib.net
              to look up the lyrics. Nothing else - no account, no key, no listening
              history. On Last.fm it also sends your key and username to last.fm.
            """;

    private List<AbstractWidget> buildLastFmPage(int contentX, int contentY, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, 260, 12, Component.literal("Last.fm API Key"),
                Minecraft.getInstance().font));
        y += 12;
        apiKeyField = new EditBox(Minecraft.getInstance().font, contentX, y, 260, 20,
                Component.literal("Last.fm API Key"));
        apiKeyField.setMaxLength(64);
        apiKeyField.setHint(Component.literal("Paste your API key here..."));
        // Masked, same as the Hypixel key field in ProfileViewerTab (2026-09-16 security pass): this is a
        // real credential and the settings screen is the one place it would show up in a stream or a
        // screenshot. addFormatter only changes what is DRAWN - getValue() still returns the real key.
        apiKeyField.addFormatter((text, offset) ->
                net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()),
                        net.minecraft.network.chat.Style.EMPTY));
        apiKeyField.setValue(SpotifyLyricsFeature.lastFmApiKey);
        widgets.add(apiKeyField);
        y += 30;

        widgets.add(new StringWidget(contentX, y, 260, 12, Component.literal("Last.fm Username"),
                Minecraft.getInstance().font));
        y += 12;
        usernameField = new EditBox(Minecraft.getInstance().font, contentX, y, 260, 20,
                Component.literal("Last.fm Username"));
        usernameField.setMaxLength(64);
        usernameField.setHint(Component.literal("Your Last.fm username..."));
        usernameField.setValue(SpotifyLyricsFeature.lastFmUsername);
        widgets.add(usernameField);
        y += 30;

        widgets.add(new StringWidget(contentX, y, 260, 12,
                Component.literal("§7Need a key? -> last.fm/api/account/create"), Minecraft.getInstance().font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Save Credentials"), btn -> {
                    SpotifyLyricsFeature.lastFmApiKey = apiKeyField.getValue().trim();
                    SpotifyLyricsFeature.lastFmUsername = usernameField.getValue().trim();
                    SpotifyLyricsFeature.saveConfig();
                    onLastFmPage = false;
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back"), btn -> {
                    onLastFmPage = false;
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());

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

    private static Component sourceText() {
        return Component.literal("Now Playing From: §b" + SpotifyLyricsFeature.source.displayName);
    }

    private static Component statusText() {
        String status = SpotifyLyricsFeature.sourceStatus();
        boolean working = status.startsWith("Reading ");
        return Component.literal((working ? "§a" : "§e") + status);
    }

    private static Component timingOffsetText() {
        double secs = SpotifyLyricsFeature.lyricTimingOffsetMs / 1000.0;
        return Component.literal(String.format("Lyric Offset: §b%.1fs", secs));
    }
}
