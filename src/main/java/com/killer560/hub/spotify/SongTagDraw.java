package com.killer560.hub.spotify;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Song Tag's two render steps, kept out of the mixin package (CLAUDE.md). The line is drawn the way vanilla stacks a
 * nametag over Hypixel's below-name score ({@code EntityRenderer.submitNameDisplay}, javap 26.1.2 and 26.2): the same
 * attachment, light and see-through rule as the name, lifted by one text line ({@code 9 * 1.15 * 0.025} blocks) per line
 * already drawn there, so it sits directly above the name and follows Others' Size with it.
 */
public final class SongTagDraw {

    /** Vanilla's line step between {@code scoreText} and {@code nameTag}. */
    private static final float LINE = 9.0f * 1.15f * 0.025f;
    /** Only players this close carry a song line (16 blocks), so a crowded hub is not a wall of text. */
    private static final double MAX_DISTANCE_SQ = 16.0 * 16.0;

    // Test hooks (read by the testkit).
    private static volatile long submitted;
    private static volatile long ownSongSubmitted;
    private static volatile long ownLyricSubmitted;
    private static volatile String lastOwnLyric = "";

    private SongTagDraw() {
    }

    /** The song line for this player this frame, or null. {@code distanceSq} is the state's distance to the camera. */
    public static Component tagFor(Entity entity, double distanceSq) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) {
            Component shared = SongShare.ownTag();
            return shared != null ? shared : ownSongLine();
        }
        if (distanceSq > MAX_DISTANCE_SQ) {
            return null;
        }
        UUID id = entity.getUUID();
        return RemoteSongs.tagFor(entity.getName().getString(), id);
    }

    /**
     * Song In F5 (killer560, 2026-11-01: "add an option for in f5 to also show song title and artist"): "♪ Title -
     * Artist" over your own head, local only, for everyone (not a supporter perk: nothing is sent). Held while paused,
     * gone after a minute of pause like the lyric line. Built once per song, color or censor change.
     */
    private static Component ownSongLine() {
        SongShareConfig cfg = SongShareConfig.getInstance();
        if (!cfg.isSongInF5()) {
            return null;
        }
        LyricsEngine.Snapshot snap = SpotifyLyricsFeature.snapshot();
        if (!snap.hasTrack() || snap.kind() == NowPlaying.Kind.AD || SpotifyLyricsFeature.pausedTooLong(snap)) {
            return null;
        }
        String title = RemoteSongs.clean(SpotifyLyricsFeature.censorDisplay(snap.title()), RemoteSongs.TITLE_MAX);
        String artist = RemoteSongs.clean(SpotifyLyricsFeature.censorDisplay(snap.artist()), RemoteSongs.ARTIST_MAX);
        int colour = cfg.isCustomF5Colors() ? cfg.getF5TextColor() : -1;
        String key = title + "\n" + artist + "\n" + colour;
        if (!key.equals(songKey)) {
            songKey = key;
            songLine = title.isEmpty() ? null : RemoteSongs.buildTag(artist, title, colour);
            lastOwnSong = songLine == null ? "" : songLine.getString();
        }
        return songLine;
    }

    // Render thread only.
    private static String songKey = "";
    private static Component songLine;
    // Test hook: the Song In F5 line last built.
    private static volatile String lastOwnSong = "";

    /**
     * The lyric line over a player's head this frame, or null: your own (My Lyrics In F5), or another player's that they
     * share (Share My Song set to share lyrics), drawn by {@link RemoteSongs#lyricFor}.
     */
    public static Component lyricFor(Entity entity, double distanceSq) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) {
            return lyricFor(entity);
        }
        if (distanceSq > MAX_DISTANCE_SQ) {
            return null;
        }
        return RemoteSongs.lyricFor(entity.getName().getString(), entity.getUUID());
    }

    /**
     * My Lyrics In F5: the lyric line being sung, for your own player only, or null. One Component per lyric line, made
     * when the line changes, so a frame allocates nothing. Held while paused, gone after a minute of pause (killer560,
     * 2026-11-01); nothing before the first line, in an instrumental gap, on an ad or while Spotify has nothing.
     */
    public static Component lyricFor(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player) {
            return null;
        }
        if (!SongShareConfig.getInstance().isLyricsOverHead()) {
            whyNoLyric("not drawn, My Lyrics In F5 is off");
            return null;
        }
        LyricsEngine.Snapshot snap = SpotifyLyricsFeature.snapshot();
        if (!snap.hasTrack()) {
            whyNoLyric("not drawn, no track");
            return null;
        }
        if (snap.lines().isEmpty()) {
            whyNoLyric("not drawn, the track has no synced lines");
            return null;
        }
        if (SpotifyLyricsFeature.pausedTooLong(snap)) {
            pauseHides++;
            whyNoLyric("not drawn, paused for over a minute");
            return null;
        }
        double elapsed = snap.elapsedSeconds(SpotifyLyricsFeature.nowMs());
        int idx = snap.lineAt(elapsed);
        if (idx < 0) {
            whyNoLyric("not drawn, before the first line (at " + Math.round(elapsed) + " s)");
            return null;
        }
        boolean paused = snap.kind() == NowPlaying.Kind.PAUSED;
        // Highlight Word In F5: the same word as the HUD (SpotifyHud.sungWord), in the F5 colors.
        int sung = SongShareConfig.getInstance().isHighlightWordInF5() && !paused ? SpotifyHud.sungWord(snap, idx, elapsed) : -1;
        com.killer560.hub.gui.OverlayPalette p = com.killer560.hub.gui.OverlayThemes.menu();
        int style = styleKey(p);
        // Rebuilt only when the line, the lit word, the pause, the theme, the F5 colors or the censor change: a frame
        // normally allocates nothing.
        if (snap.lines() != lyricLines || idx != lyricIndex || sung != lyricSung || paused != lyricPaused
                || style != lyricStyle) {
            lyricLines = snap.lines();
            lyricIndex = idx;
            lyricSung = sung;
            lyricPaused = paused;
            lyricStyle = style;
            lyricTag = buildLyric(snap.lines().get(idx).parsed().words(), sung, paused, p);
            if (lyricTag == null) {
                whyNoLyric("not drawn, line " + idx + " has no words");
            }
            lastOwnLyric = lyricTag == null ? "" : lyricTag.getString();
            lastF5Word = sung;
            lyricBuilds++;
        }
        if (lyricTag != null) {
            whyNoLyric("drawn");
        }
        return lyricTag;
    }

    private static String lastWhy = "";

    /** Logs (INFO, only when the reason changes) why your own F5 lyric line is not drawn: it is a silent feature, and
     *  the owner's game showed the song line but no lyric (2026-11-03) with nothing in the log to say why. */
    private static void whyNoLyric(String why) {
        if (!why.equals(lastWhy)) {
            lastWhy = why;
            SpotifyLyricsFeature.LOGGER.info("[SongTag] own F5 lyric line: {}", why);
        }
    }

    /** One number that changes when anything an F5 line's look depends on changes: theme, F5 colors, censor level. */
    static int styleKey(com.killer560.hub.gui.OverlayPalette p) {
        SongShareConfig c = SongShareConfig.getInstance();
        int h = p.text();
        h = 31 * h + p.textHover();
        h = 31 * h + p.accentHover();
        h = 31 * h + p.faint();
        h = 31 * h + (c.isCustomF5Colors() ? 1 : 0);
        h = 31 * h + c.getF5TextColor();
        h = 31 * h + c.getF5HighlightColor();
        h = 31 * h + SpotifyHudConfig.getInstance().getDisplayCensor().ordinal();
        return h;
    }

    /**
     * Word {@code i}'s color in an F5 line: the HUD's colors while the F5 colors follow the theme; with custom F5 colors
     * (killer560, 2026-11-01: "a highlight color for the f5 stuff and the text color for all of it") the text color for
     * every word, the highlight color for the word being sung, and words still to come a shade darker.
     */
    static int f5Color(com.killer560.hub.gui.OverlayPalette p, int i, int sung, boolean paused) {
        SongShareConfig c = SongShareConfig.getInstance();
        if (!c.isCustomF5Colors()) {
            return SpotifyHud.wordColor(p, i, sung, paused);
        }
        int text = c.getF5TextColor();
        if (paused || sung < 0 || i < sung) {
            return text;
        }
        if (i == sung) {
            return c.getF5HighlightColor();
        }
        int r = ((text >> 16) & 255) * 3 / 5;
        int g = ((text >> 8) & 255) * 3 / 5;
        int b = (text & 255) * 3 / 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** An F5 lyric line: "♫ " and the words, each in its F5 color; the lit word also underlined, as the HUD's chip is.
     *  Starred at the Display Censor level, cut after {@link #LYRIC_MAX} characters. */
    static Component buildLyric(java.util.List<String> words, int sung, boolean paused,
                                com.killer560.hub.gui.OverlayPalette p) {
        if (words.isEmpty()) {
            return null;
        }
        net.minecraft.network.chat.MutableComponent out = Component.literal("♫ ")
                .withStyle(s -> s.withColor(f5Color(p, -1, -1, paused) & 0xFFFFFF));
        int used = 2;
        for (int i = 0; i < words.size(); i++) {
            String word = SpotifyLyricsFeature.censorDisplay(words.get(i)).replace('§', '?');
            if (used + word.length() > LYRIC_MAX) {
                out.append(Component.literal("…").withStyle(s -> s.withColor(p.faint() & 0xFFFFFF)));
                break;
            }
            final int colour = f5Color(p, i, sung, paused) & 0xFFFFFF;
            final boolean lit = i == sung;
            out.append(Component.literal(i + 1 < words.size() ? word + " " : word)
                    .withStyle(s -> s.withColor(colour).withUnderlined(lit)));
            used += word.length() + 1;
        }
        return out;
    }

    // Test hook: frames the F5 lyric skipped because the pause passed a minute.
    private static volatile long pauseHides;

    /** A lyric line longer than this is cut, so the line over your head stays a sensible width. */
    private static final int LYRIC_MAX = 60;
    // Render thread only.
    private static java.util.List<LyricsEngine.LyricLine> lyricLines;
    private static int lyricIndex = -1;
    private static int lyricSung = -2;
    private static boolean lyricPaused;
    private static int lyricStyle;
    private static Component lyricTag;
    // Test hooks: the word the F5 line lit when last built (-1 = none) and how many times it was built.
    private static volatile int lastF5Word = -1;
    private static volatile long lyricBuilds;

    /**
     * Whether your own player has a line to draw above its name this frame (your song with Share My Song on, or your
     * lyric with My Lyrics In F5 on). {@code supporters.mixin.OwnNameTagF5Mixin} then shows your own name in third person
     * even with My Name in F5 off, because the line hangs over the name and vanilla never draws the camera entity's name.
     */
    public static boolean hasOwnLine() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (tagFor(mc.player, 0) != null || lyricFor(mc.player) != null);
    }

    public static void submit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                              CameraRenderState camera, int yOffset) {
        if (!((Object) state instanceof SongTagState tagged)) {
            return;
        }
        Component song = tagged.killer560smod$songTag();
        Component lyric = tagged.killer560smod$lyricTag();
        if ((song == null && lyric == null) || state.nameTag == null || state.nameTagAttachment == null) {
            return;
        }
        int line = state.scoreText != null ? 2 : 1;
        boolean own = Minecraft.getInstance().player != null
                && state.nameTag != null && lyric != null;
        if (song != null) {
            poseStack.pushPose();
            poseStack.translate(0.0f, LINE * line, 0.0f);
            McRender.submitNameTag(collector, poseStack, state.nameTagAttachment, yOffset, song, !state.isDiscrete,
                    state.lightCoords, state.distanceToCameraSq, camera);
            poseStack.popPose();
            submitted++;
            if (song == SongShare.ownTag()) {
                ownSongSubmitted++;
            }
            line++;
        }
        if (lyric != null) {
            poseStack.pushPose();
            poseStack.translate(0.0f, LINE * line, 0.0f);
            McRender.submitNameTag(collector, poseStack, state.nameTagAttachment, yOffset, lyric, !state.isDiscrete,
                    state.lightCoords, state.distanceToCameraSq, camera);
            poseStack.popPose();
            if (own) {
                ownLyricSubmitted++;
            }
        }
    }

    /** Song lines submitted since start. */
    public static long submitted() {
        return submitted;
    }
}
