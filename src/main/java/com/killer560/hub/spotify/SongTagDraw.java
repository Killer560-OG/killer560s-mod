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
            return SongShare.ownTag();
        }
        if (distanceSq > MAX_DISTANCE_SQ) {
            return null;
        }
        UUID id = entity.getUUID();
        return RemoteSongs.tagFor(entity.getName().getString(), id);
    }

    /**
     * My Lyrics In F5: the lyric line being sung, for your own player only, or null. One Component per lyric line, made
     * when the line changes, so a frame allocates nothing. Held while paused; nothing before the first line, in an
     * instrumental gap, on an ad or while Spotify has nothing.
     */
    public static Component lyricFor(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || !SongShareConfig.getInstance().isLyricsOverHead()) {
            return null;
        }
        LyricsEngine.Snapshot snap = SpotifyLyricsFeature.snapshot();
        if (!snap.hasTrack() || snap.lines().isEmpty()) {
            return null;
        }
        double elapsed = snap.elapsedSeconds(SpotifyLyricsFeature.nowMs());
        int idx = snap.lineAt(elapsed);
        if (idx < 0) {
            return null;
        }
        boolean paused = snap.kind() == NowPlaying.Kind.PAUSED;
        // Highlight Word In F5: the same word and the same colors as the HUD (SpotifyHud.sungWord / wordColor).
        int sung = SongShareConfig.getInstance().isHighlightWordInF5() && !paused ? SpotifyHud.sungWord(snap, idx, elapsed) : -1;
        com.killer560.hub.gui.OverlayPalette p = com.killer560.hub.gui.OverlayThemes.menu();
        int c0 = p.text();
        int c1 = p.textHover();
        int c2 = p.accentHover();
        int c3 = p.faint();
        // Rebuilt only when the line, the lit word, the pause or the theme changes: a frame normally allocates nothing.
        if (snap.lines() != lyricLines || idx != lyricIndex || sung != lyricSung || paused != lyricPaused
                || c0 != lyricC0 || c1 != lyricC1 || c2 != lyricC2 || c3 != lyricC3) {
            lyricLines = snap.lines();
            lyricIndex = idx;
            lyricSung = sung;
            lyricPaused = paused;
            lyricC0 = c0;
            lyricC1 = c1;
            lyricC2 = c2;
            lyricC3 = c3;
            lyricTag = buildLyric(snap.lines().get(idx), sung, paused, p);
            lastOwnLyric = lyricTag == null ? "" : lyricTag.getString();
            lastF5Word = sung;
            lyricBuilds++;
        }
        return lyricTag;
    }

    /** The F5 line: "♫ " and the line's words, each in the HUD's color for it; the lit word also underlined, as the HUD's
     *  chip is. Cut after {@link #LYRIC_MAX} characters. */
    private static Component buildLyric(LyricsEngine.LyricLine line, int sung, boolean paused,
                                        com.killer560.hub.gui.OverlayPalette p) {
        java.util.List<String> words = line.parsed().words();
        if (words.isEmpty()) {
            return null;
        }
        net.minecraft.network.chat.MutableComponent out = Component.literal("\u266b ")
                .withStyle(s -> s.withColor(SpotifyHud.wordColor(p, -1, -1, paused) & 0xFFFFFF));
        int used = 2;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).replace('\u00a7', '?');
            if (used + word.length() > LYRIC_MAX) {
                out.append(Component.literal("\u2026").withStyle(s -> s.withColor(p.faint() & 0xFFFFFF)));
                break;
            }
            final int colour = SpotifyHud.wordColor(p, i, sung, paused) & 0xFFFFFF;
            final boolean lit = i == sung;
            out.append(Component.literal(i + 1 < words.size() ? word + " " : word)
                    .withStyle(s -> s.withColor(colour).withUnderlined(lit)));
            used += word.length() + 1;
        }
        return out;
    }

    /** A lyric line longer than this is cut, so the line over your head stays a sensible width. */
    private static final int LYRIC_MAX = 60;
    // Render thread only.
    private static java.util.List<LyricsEngine.LyricLine> lyricLines;
    private static int lyricIndex = -1;
    private static int lyricSung = -2;
    private static boolean lyricPaused;
    private static int lyricC0;
    private static int lyricC1;
    private static int lyricC2;
    private static int lyricC3;
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
        return mc.player != null && (SongShare.ownTag() != null || lyricFor(mc.player) != null);
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
