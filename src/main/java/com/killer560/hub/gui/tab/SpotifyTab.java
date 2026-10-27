package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.spotify.SongShare;
import com.killer560.hub.spotify.SongShareConfig;
import com.killer560.hub.spotify.SpotifyControls;
import com.killer560.hub.spotify.SpotifyControlsConfig;
import com.killer560.hub.spotify.SpotifyHudConfig;
import com.killer560.hub.spotify.SpotifyLyricsFeature;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
public class SpotifyTab extends BaseTab implements KeyCaptureTab {

    /** The control whose key is being set, or null. */
    private SpotifyControls.Action capturing;

    public SpotifyTab() {
        super("Spotify Lyrics");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        bind(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        bind(KeyUtil.codeForMouseButton(button));
    }

    private void bind(int code) {
        if (capturing != null) {
            SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
            cfg.setKey(capturing, code);
            cfg.save();
        }
        capturing = null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        int half = (contentWidth - 8) / 2;

        // The tab's two features, each with its own switch, both at the top (killer560, 2026-10-20: "it needs a
        // setting for the hud and an option for sending lyrics as well"). Send Lyrics To Chat was the "Mod Enabled"
        // row, which only ever controlled chat; the HUD switch moved up from under its header.
        widgets.add(SettingsButtonWidget.builder(sendText(), btn -> {
                    SpotifyLyricsFeature.setSendLyricsToChat(!SpotifyLyricsFeature.isSendLyricsToChat());
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(sendText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(onOff("Spotify HUD", hud().isHudEnabled()), btn -> {
                    hud().setHudEnabled(!hud().isHudEnabled());
                    hud().save();
                    btn.setMessage(onOff("Spotify HUD", hud().isHudEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // No status line at all: the "Reading Spotify" line went on 2026-09-30 and the "Spotify is not running"
        // one on 2026-10-18 (killer560).

        y = controls(widgets, contentX, y, contentWidth, half, requestRebuild);

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Chat Lyrics", false));
        y += 14;
        widgets.add(SettingsButtonWidget.cycling(destText(), (btn, dir) -> {
                    SpotifyLyricsFeature.chatDestination = dir > 0 ? SpotifyLyricsFeature.chatDestination.next()
                            : SpotifyLyricsFeature.chatDestination.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(destText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.cycling(detailText(), (btn, dir) -> {
                    SpotifyLyricsFeature.fullLyrics = !SpotifyLyricsFeature.fullLyrics;
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(detailText());
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.cycling(profanityText(), (btn, dir) -> {
                    SpotifyLyricsFeature.profanityLevel = dir > 0 ? SpotifyLyricsFeature.profanityLevel.next()
                            : SpotifyLyricsFeature.profanityLevel.previous();
                    SpotifyLyricsFeature.saveConfig();
                    btn.setMessage(profanityText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // The Spotify HUD's own options; its switch is the second row at the top, independent of chat sending.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Spotify HUD", false));
        y += 14;
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
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Smooth Lyric Scroll", hud().isSmoothScroll()), btn -> {
                    hud().setSmoothScroll(!hud().isSmoothScroll());
                    hud().save();
                    btn.setMessage(onOff("Smooth Lyric Scroll", hud().isSmoothScroll()));
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(onOff("Word-Timed Lyrics", hud().isWordTimedLyrics()), btn -> {
                    hud().setWordTimedLyrics(!hud().isWordTimedLyrics());
                    hud().save();
                    btn.setMessage(onOff("Word-Timed Lyrics", hud().isWordTimedLyrics()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // Song Tag (2026-10-24): the song as a line above your name for other mod users. Its own two switches.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Song Tag", false));
        y += 14;
        // Supporters only (killer560, 2026-10-26): grayed out for everyone else, and its hover says so. Turning it OFF
        // stays possible, so an old config with it on can still be switched off. SongShare checks it again where the
        // song is sent, so a file edited by hand sends nothing either.
        SettingsButtonWidget share = SettingsButtonWidget.builder(onOff("Share My Song", tag().isShareSong()), btn -> {
                    tag().setShareSong(!tag().isShareSong());
                    tag().save();
                    btn.setMessage(onOff("Share My Song", tag().isShareSong()));
                }).bounds(contentX, y, contentWidth, 20).build()
                .activeWhen(() -> SongShare.selfIsSupporter() || tag().isShareSong());
        widgets.add(share);
        SettingTooltips.live(share, () -> {
            if (!SongShare.selfIsSupporter()) {
                return "Supporters only. Shows the song you play on Spotify as a line above your name to other mod users;"
                        + " it is a supporter perk, so it cannot be turned on for this account.";
            }
            String why = SongShare.notSentReason();
            return why == null ? null : "On, but not reaching anyone right now: " + why
                    + ". You still see your own line above your name in F5.";
        });
        y += 24;
        widgets.add(SettingsButtonWidget.builder(onOff("Show Others' Songs", tag().isShowOthers()), btn -> {
                    tag().setShowOthers(!tag().isShowOthers());
                    tag().save();
                    btn.setMessage(onOff("Show Others' Songs", tag().isShowOthers()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        // Everyone (not a supporter perk): your own lyric over your own head, local only.
        widgets.add(SettingsButtonWidget.builder(onOff("My Lyrics In F5", tag().isLyricsOverHead()), btn -> {
                    tag().setLyricsOverHead(!tag().isLyricsOverHead());
                    tag().save();
                    btn.setMessage(onOff("My Lyrics In F5", tag().isLyricsOverHead()));
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Highlight Word In F5", tag().isHighlightWordInF5()), btn -> {
                    tag().setHighlightWordInF5(!tag().isHighlightWordInF5());
                    tag().save();
                    btn.setMessage(onOff("Highlight Word In F5", tag().isHighlightWordInF5()));
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 24;

        return widgets;
    }

    /** Spotify Controls (2026-10-26): transport buttons, the volume slider, the steps and a key for each control. */
    private int controls(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, int half,
                         Runnable requestRebuild) {
        SpotifyControls.tabOpened();
        SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Controls", false));
        y += 14;
        int third = (contentWidth - 16) / 3;
        control(widgets, SpotifyControls.Action.PREVIOUS, "Previous", contentX, y, third);
        control(widgets, SpotifyControls.Action.PLAY_PAUSE, "Play/Pause", contentX + third + 8, y,
                contentWidth - 2 * (third + 8));
        control(widgets, SpotifyControls.Action.NEXT, "Next", contentX + contentWidth - third, y, third);
        y += 24;
        control(widgets, SpotifyControls.Action.REWIND, "Rewind " + cfg.getSeekSeconds() + "s", contentX, y, half);
        control(widgets, SpotifyControls.Action.SKIP_AHEAD, "Skip Ahead " + cfg.getSeekSeconds() + "s",
                contentX + half + 8, y, half);
        y += 24;

        int shown = SpotifyControls.volume();
        ThemedSliderButton volume = new ThemedSliderButton(contentX, y, contentWidth, 18, volumeText(),
                (shown < 0 ? 100 : shown) / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                SpotifyControls.setVolume((int) Math.round(this.value * 100));
                saveOnRelease(() -> SpotifyControlsConfig.getInstance().save());
            }

            @Override
            public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                    float partialTick) {
                active = SpotifyControls.unavailableReason(SpotifyControls.Action.VOLUME_UP) == null;
                super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
            }
        };
        volume.steps(100);
        widgets.add(volume);
        SettingTooltips.live(volume, () -> SpotifyControls.unavailableReason(SpotifyControls.Action.VOLUME_UP));
        y += 24;

        widgets.add(SettingsButtonWidget.cycling(seekText(), (btn, dir) -> {
                    int[] steps = {5, 10, 15, 30};
                    int i = 0;
                    for (int k = 0; k < steps.length; k++) {
                        if (steps[k] == cfg.getSeekSeconds()) {
                            i = k;
                        }
                    }
                    SpotifyControlsConfig.getInstance().setSeekSeconds(steps[SettingsButtonWidget.step(i, dir, steps.length)]);
                    SpotifyControlsConfig.getInstance().save();
                    btn.setMessage(seekText());
                    requestRebuild.run();   // Rewind and Skip Ahead show the step
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.cycling(volumeStepText(), (btn, dir) -> {
                    int[] steps = {2, 5, 10};
                    int i = 0;
                    for (int k = 0; k < steps.length; k++) {
                        if (steps[k] == cfg.getVolumeStep()) {
                            i = k;
                        }
                    }
                    SpotifyControlsConfig.getInstance().setVolumeStep(steps[SettingsButtonWidget.step(i, dir, steps.length)]);
                    SpotifyControlsConfig.getInstance().save();
                    btn.setMessage(volumeStepText());
                }).bounds(contentX + half + 8, y, half, 20).build());
        y += 26;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Control Keys", false));
        y += 14;
        SpotifyControls.Action[] all = SpotifyControls.Action.values();
        for (int i = 0; i < all.length; i++) {
            SpotifyControls.Action a = all[i];
            boolean left = i % 2 == 0;
            widgets.add(SettingsButtonWidget.builder(keyText(a), btn -> {
                        capturing = a;
                        btn.setMessage(Component.literal(a.label + " Key: Press any key..."));
                    }).bounds(left ? contentX : contentX + half + 8, y, half, 20).build());
            if (!left || i == all.length - 1) {
                y += 24;
            }
        }
        return y + 2;
    }

    private static void control(List<AbstractWidget> widgets, SpotifyControls.Action action, String label, int x, int y,
                                int w) {
        SettingsButtonWidget b = SettingsButtonWidget.builder(Component.literal(label),
                btn -> SpotifyControls.perform(action)).bounds(x, y, w, 20).build()
                .activeWhen(() -> SpotifyControls.unavailableReason(action) == null);
        widgets.add(b);
        SettingTooltips.live(b, () -> SpotifyControls.unavailableReason(action));
    }

    private Component keyText(SpotifyControls.Action a) {
        if (capturing == a) {
            return Component.literal(a.label + " Key: Press any key...");
        }
        int code = SpotifyControlsConfig.getInstance().getKey(a);
        return Component.literal(a.label + " Key: " + (code == KeyUtil.NONE ? "Not Set" : KeyUtil.bindDisplayName(code)));
    }

    private static Component volumeText() {
        int v = SpotifyControls.volume();
        return Component.literal("Spotify Volume: " + (v < 0 ? "?" : v + "%"));
    }

    private static Component seekText() {
        return Component.literal("Rewind/Skip Step: " + SpotifyControlsConfig.getInstance().getSeekSeconds() + "s");
    }

    private static Component volumeStepText() {
        return Component.literal("Volume Key Step: " + SpotifyControlsConfig.getInstance().getVolumeStep() + "%");
    }

    /** Chat lyrics, the HUD and the two Song Tag switches are separate features: the card is ON while any is on. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Send Lyrics To Chat", "Spotify HUD", "Share My Song", "Show Others' Songs",
                "My Lyrics In F5");
    }

    /** Read fresh each time: applying a profile replaces the config instance. */
    private static SpotifyHudConfig hud() {
        return SpotifyHudConfig.getInstance();
    }

    /** Read fresh each time, like {@link #hud()}: a profile reload replaces this instance too. */
    private static SongShareConfig tag() {
        return SongShareConfig.getInstance();
    }

    private static Component sendText() {
        return onOff("Send Lyrics To Chat", SpotifyLyricsFeature.isSendLyricsToChat());
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
