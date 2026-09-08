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

import java.awt.Desktop;
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

        widgets.add(SettingsButtonWidget.builder(destText(), btn -> {
                    SpotifyLyricsFeature.chatDestination = SpotifyLyricsFeature.chatDestination.next();
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

        widgets.add(SettingsButtonWidget.builder(Component.literal("Last.fm Setup ->"), btn -> {
                    onLastFmPage = true;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Instructions"), btn -> openInstructions())
                .bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static void openInstructions() {
        try {
            Files.writeString(INSTRUCTIONS_FILE, INSTRUCTIONS_TEXT, StandardCharsets.UTF_8);
            Desktop.getDesktop().open(INSTRUCTIONS_FILE.toFile());
        } catch (Exception e) {
            ModOverlayMessage.show("§c[Killer560's Mod] Couldn't open setup guide: " + e.getMessage(), 4000);
        }
    }

    private static final String INSTRUCTIONS_TEXT = """
            KILLER560'S MOD - SPOTIFY LYRICS SETUP GUIDE
            =============================================

            This feature posts the lyrics of whatever song you're playing on Spotify into
            your Minecraft chat. It finds out what you're playing through Last.fm, so
            Last.fm needs to know what your Spotify is doing (this is called "scrobbling").

            STEP 1 - Make a free Last.fm account
              Go to last.fm and sign up if you don't already have an account.

            STEP 2 - Connect Spotify to Last.fm
              Open Spotify (desktop or mobile) -> Settings -> Social, and turn on
              "Last.fm Scrobbling". If you don't see that option, log in to last.fm in
              your browser, go to last.fm/settings/applications, and connect Spotify
              from there instead.

            STEP 3 - Create a Last.fm API key
              Go to last.fm/api/account/create
              Fill in any application name (e.g. "Killer560 Lyrics") and submit.
              Copy the "API key" it gives you - you'll need it in the next step.

            STEP 4 - Enter your details in the mod
              Open the mod menu -> Spotify Mod -> "Last.fm Setup ->"
              Paste your API key and type in your Last.fm username, then hit
              "Save Credentials".

            STEP 5 - Play something
              Start playing a song on Spotify. After a few seconds it should start
              showing up in your chat (or party chat, depending on your Chat
              Destination setting).

              If the lyrics feel early or late, adjust the "Lyric Offset" slider in
              the Spotify Mod tab - it compensates for the delay between a song
              starting and Last.fm reporting it.

            That's it - nothing outside the mod needs to keep running.
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

    private static Component timingOffsetText() {
        double secs = SpotifyLyricsFeature.lyricTimingOffsetMs / 1000.0;
        return Component.literal(String.format("Lyric Offset: §b%.1fs", secs));
    }
}
