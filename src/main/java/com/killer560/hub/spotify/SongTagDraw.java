package com.killer560.hub.spotify;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.ChatFormatting;
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
        int idx = snap.lineAt(snap.elapsedSeconds(System.currentTimeMillis()));
        if (idx < 0) {
            return null;
        }
        if (snap.lines() != lyricLines || idx != lyricIndex) {
            lyricLines = snap.lines();
            lyricIndex = idx;
            String text = snap.lines().get(idx).text().replace('\u00a7', '?').strip();
            if (text.codePointCount(0, text.length()) > LYRIC_MAX) {
                text = text.substring(0, text.offsetByCodePoints(0, LYRIC_MAX - 1)).stripTrailing() + "\u2026";
            }
            lyricTag = text.isEmpty() ? null : Component.literal("\u266b " + text).withStyle(ChatFormatting.WHITE);
            lastOwnLyric = lyricTag == null ? "" : lyricTag.getString();
        }
        return lyricTag;
    }

    /** A lyric line longer than this is cut, so the line over your head stays a sensible width. */
    private static final int LYRIC_MAX = 60;
    // Render thread only.
    private static java.util.List<LyricsEngine.LyricLine> lyricLines;
    private static int lyricIndex = -1;
    private static Component lyricTag;

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
