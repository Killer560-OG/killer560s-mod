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
    public void cancelKeyCapture() {
        capturing = null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        bind(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
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
        widgets.add(onOffButton("Spotify HUD", () -> hud().isHudEnabled(), v -> hud().setHudEnabled(v), () -> hud().save(), null, contentX, y, contentWidth, 20));
        y += 24;
        // Which player to follow and what the display stars out (2026-11-01), for the HUD, F5 and shared lines alike.
        widgets.add(SettingsButtonWidget.cycling(sourceText(), (btn, dir) -> {
                    // Spotify App Only is no longer offered (killer560, 2026-11-02): Music Players <-> Anything Playing.
                    SpotifyHudConfig.MusicSource[] all = SpotifyHudConfig.MusicSource.offered();
                    int at = Math.max(0, java.util.Arrays.asList(all).indexOf(hud().getMusicSource()));
                    hud().setMusicSource(all[SettingsButtonWidget.step(at, dir, all.length)]);
                    hud().save();
                    btn.setMessage(sourceText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.cycling(censorText(), (btn, dir) -> {
                    hud().setDisplayCensor(dir > 0 ? hud().getDisplayCensor().next() : hud().getDisplayCensor().previous());
                    hud().save();
                    btn.setMessage(censorText());
                }).bounds(contentX + half + 8, y, contentWidth - half - 8, 20).build());
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
        widgets.add(onOffButton("HUD Lyrics", () -> hud().isShowLyrics(), v -> hud().setShowLyrics(v), () -> hud().save(), null, contentX, y, half, 20));
        widgets.add(onOffButton("Word Highlight", () -> hud().isWordHighlight(), v -> hud().setWordHighlight(v), () -> hud().save(), null, contentX + half + 8, y, half, 20));
        y += 24;
        widgets.add(onOffButton("Hide When Paused", () -> hud().isHideWhenPaused(), v -> hud().setHideWhenPaused(v), () -> hud().save(), null, contentX, y, half, 20));
        widgets.add(onOffButton("Smooth Lyric Scroll", () -> hud().isSmoothScroll(), v -> hud().setSmoothScroll(v), () -> hud().save(), null, contentX + half + 8, y, half, 20));
        y += 24;
        widgets.add(onOffButton("Word-Timed Lyrics", () -> hud().isWordTimedLyrics(), v -> hud().setWordTimedLyrics(v), () -> hud().save(), null, contentX, y, half, 20));
        widgets.add(onOffButton("Always Show Controls", () -> hud().isAlwaysShowControls(), v -> hud().setAlwaysShowControls(v), () -> hud().save(), null, contentX + half + 8, y, half, 20));
        y += 26;

        // Lyrics In F5 (2026-11-01): every F5 row, the same rows (same values) as Player Cosmetics' Spotify In F5.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Lyrics In F5", false));
        y += 14;
        y = SpotifyF5Rows.build(widgets, contentX, y, contentWidth, true, requestRebuild) + 2;

        // Song Tag (2026-10-24): the song as a line above your name for other mod users.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Song Tag", false));
        y += 14;
        // Supporters only (killer560, 2026-10-26): grayed out for everyone else, and its hover says so. Turning it OFF
        // stays possible, so an old config with it on can still be switched off. SongShare checks it again where the
        // song is sent, so a file edited by hand sends nothing either.
        SettingsButtonWidget share = onOffButton("Share My Song", () -> tag().isShareSong(), v -> tag().setShareSong(v), () -> tag().save(), null, contentX, y, contentWidth, 20)
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
        // What each sharer sends (killer560, 2026-11-01): the song name, the lyric line, or both, and the lit word.
        widgets.add(SettingsButtonWidget.cycling(shareWhatText(), (btn, dir) -> {
                    SongShareConfig.ShareWhat[] all = SongShareConfig.ShareWhat.values();
                    tag().setShareWhat(all[SettingsButtonWidget.step(tag().getShareWhat().ordinal(), dir, all.length)]);
                    tag().save();
                    btn.setMessage(shareWhatText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(onOffButton("Share Current Word", () -> tag().isShareWord(), v -> tag().setShareWord(v), () -> tag().save(), null, contentX + half + 8, y, contentWidth - half - 8, 20));
        y += 24;
        SettingsButtonWidget others = onOffButton("Show Others' Songs", () -> tag().isShowOthers(), v -> tag().setShowOthers(v), () -> tag().save(), null, contentX, y, contentWidth, 20);
        widgets.add(others);
        // Says whose songs can reach you (spotshare-1105: a friend had it on and saw nothing; the relay room decides).
        SettingTooltips.live(others, () -> !tag().isShowOthers() ? null
                : SettingTooltips.describe(null, "Show Others' Songs") + " Right now: " + SongShare.receiveScope());
        y += 24;

        return widgets;
    }

    /**
     * Spotify Controls (2026-10-26): the volume slider, the steps and a key for each control. The transport buttons that
     * stood here (Previous, Play/Pause, Next, Rewind, Skip Ahead) were removed on 2026-11-01 (killer560: "remove the
     * controls buttons in the mod settings"); they live on the Spotify HUD's control row and on the keys.
     */
    private int controls(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, int half,
                         Runnable requestRebuild) {
        SpotifyControls.tabOpened();
        SpotifyControlsConfig cfg = SpotifyControlsConfig.getInstance();
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Controls", false));
        y += 14;

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
                "My Lyrics In F5", "Song In F5");
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

    private static Component sourceText() {
        return Component.literal("Music Source: §b" + hud().getMusicSource().label);
    }

    private static Component censorText() {
        return Component.literal("Display Censor: §b" + hud().getDisplayCensor().shortName());
    }

    private static Component shareWhatText() {
        return Component.literal("Share What: §b" + tag().getShareWhat().label);
    }

    private static Component profanityText() {
        return Component.literal("Profanity Filter: §b" + SpotifyLyricsFeature.profanityLevel.displayName);
    }
}
