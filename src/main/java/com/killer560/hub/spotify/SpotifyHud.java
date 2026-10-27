package com.killer560.hub.spotify;

import com.killer560.hub.gui.OverlayPalette;
import com.killer560.hub.gui.OverlayThemes;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Spotify HUD: a now-playing panel, moved and scaled in Edit HUD like every other element (id {@value #ELEMENT_ID},
 * drawn in game by {@code hud/HudInGameRenderer}).
 *
 * <p>Shows the cover, the title and artist, elapsed / total time with a progress bar, and the lyric line being sung
 * with the current word lit. Everything comes from {@link LyricsEngine.Snapshot}, which the lyrics poll thread
 * publishes whole, and the cover's PNG bytes from {@link SpotifyMediaSession}; this class only reads them, so nothing
 * here touches the network, Windows, a process or a lock. The clock is run from the snapshot's two parts on every
 * frame, so the highlight moves smoothly instead of on the 250 ms poll; while paused both parts are frozen, and the held
 * line is drawn without the moving highlight.
 *
 * <p>Look (killer560, 2026-10-20: "make the HUD feel custom to the mod"): the mod menu's own frame - ScreenChrome's
 * accent ring around MenuStyle's bevel (outline, panel gradient, light top-left and dark bottom-right edges) - and its
 * slider track for the progress bar (recessed track, the played part in the accent gradient), all in the menu theme's
 * colors from {@link OverlayThemes#menu()}, so the panel follows the theme he picked. The word being sung is the bright
 * accent on an accent-tinted chip with an underline.
 *
 * <p>Cover art and the total time come from Spotify via Windows' media controls when that reader runs; without it the
 * tile is a note and the total is the lyrics' lrclib length.
 */
public final class SpotifyHud implements HudElement {

    public static final String ELEMENT_ID = "spotify_hud";
    public static final SpotifyHud INSTANCE = new SpotifyHud();

    private static final int W = 200;
    private static final int PAD = 5;
    private static final int COVER = 32;
    private static final int BAR_Y = 42;
    private static final int LYRIC_Y = 50;
    private static final int ROW_H = 11;
    private static final int LYRIC_ROWS = 2;

    private static final Identifier COVER_ID = Identifier.fromNamespaceAndPath("killer560smod", "spotify/cover");

    private SpotifyHud() {
    }

    @Override
    public String id() {
        return ELEMENT_ID;
    }

    @Override
    public String displayName() {
        return "Spotify HUD";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 60;
    }

    @Override
    public int width() {
        return W;
    }

    @Override
    public int height() {
        return SpotifyHudConfig.getInstance().isShowLyrics() ? LYRIC_Y + LYRIC_ROWS * ROW_H + PAD - 2 : BAR_Y + 4 + PAD;
    }

    @Override
    public boolean isEnabledInSettings() {
        return SpotifyHudConfig.getInstance().isHudEnabled();
    }

    /** The editor's sample, so the panel can be placed before anything plays. */
    private static final LyricsEngine.Snapshot DEMO;
    private static final long DEMO_PLAYED_MS = 72_400;

    static {
        String demoLine = "This is the line being sung right now";
        DEMO = new LyricsEngine.Snapshot(NowPlaying.Kind.TRACK, "Artist", "Song Title", DEMO_PLAYED_MS, 0, 215,
                List.of(new LyricsEngine.LyricLine(71.0, demoLine, LyricTiming.parse(demoLine, 71.0)),
                        new LyricsEngine.LyricLine(76.0, "and this one is next", LyricTiming.parse("and this one is next", 76.0))),
                true, false, 0, true);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        SpotifyHudConfig cfg = SpotifyHudConfig.getInstance();
        boolean editor = HudVisibility.editorOpen();
        if ((!cfg.isHudEnabled() && !editor) || HudVisibility.hidesHud()) {
            return;
        }
        LyricsEngine.Snapshot snap = SpotifyLyricsFeature.snapshot();
        boolean live = snap.hasTrack() || snap.kind() == NowPlaying.Kind.AD;
        if (!live) {
            if (!editor) {
                return; // nothing playing this session (or Spotify closed before anything played)
            }
            snap = DEMO;
        }
        boolean paused = snap.kind() == NowPlaying.Kind.PAUSED;
        if (paused && cfg.isHideWhenPaused() && !editor) {
            return;
        }
        OverlayPalette p = OverlayThemes.menu();
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        int h = height();
        boolean ad = snap.kind() == NowPlaying.Kind.AD;

        long now = System.currentTimeMillis();
        double elapsed = snap == DEMO ? DEMO_PLAYED_MS / 1000.0 : snap.elapsedSeconds(now);
        double total = snap.durationSeconds();
        if (total > 0) {
            elapsed = Math.min(elapsed, total);
        }

        // ---- every fill first, as one batch: frame, cover tile, progress bar, the sung word's chip ----
        GuiRects r = GuiRects.begin(graphics);
        int hilite = p.light() ? 0x90FFFFFF : 0x1CFFFFFF;
        int shade = p.light() ? 0x28000000 : 0x80000000;
        // ScreenChrome.frame: accent ring, then MenuStyle.bevel's outline, gradient and edges.
        r.outline(x - 1, y - 1, W + 2, h + 2, p.border());
        r.outline(x, y, W, h, p.outline());
        r.gradient(x + 1, y + 1, x + W - 1, y + h - 1, OverlayPalette.alpha(p.panel(), 0xE8),
                OverlayPalette.alpha(p.panelDeep(), 0xE8));
        r.fill(x + 1, y + 1, x + W - 1, y + 2, hilite);
        r.fill(x + 1, y + 2, x + 2, y + h - 1, hilite);
        r.fill(x + 1, y + h - 2, x + W - 1, y + h - 1, shade);
        r.fill(x + W - 2, y + 2, x + W - 1, y + h - 2, shade);

        // Cover tile: a recessed well with an accent edge; the picture (or a note) goes on top after the batch.
        int cx = x + PAD;
        int cy = y + PAD;
        r.outline(cx - 1, cy - 1, COVER + 2, COVER + 2, p.border());
        r.fill(cx, cy, cx + COVER, cy + COVER, p.well());

        // Progress bar: MenuStyle.sliderTrack's recessed track, the played part in the accent gradient with a bright
        // leading edge.
        int bx0 = x + PAD;
        int bx1 = x + W - PAD;
        int by = y + BAR_Y;
        r.outline(bx0, by, bx1 - bx0, 4, p.outline());
        r.fill(bx0 + 1, by + 1, bx1 - 1, by + 3, p.well());
        if (total > 0) {
            int filled = (int) Math.round((bx1 - bx0 - 2) * Math.max(0, Math.min(1, elapsed / total)));
            if (filled > 0) {
                int top = paused ? p.accentDeep() : p.accentHover();
                int bottom = paused ? OverlayPalette.alpha(p.accentDeep(), 0xFF) : p.accent();
                r.gradient(bx0 + 1, by + 1, bx0 + 1 + filled, by + 3, top, bottom);
                if (!paused) {
                    r.fill(bx0 + filled, by, bx0 + 1 + filled, by + 4, 0xFFFFFFFF);
                }
            }
        }

        LyricPlan plan = cfg.isShowLyrics() && !ad
                ? planLyrics(font, snap, elapsed, cfg.isWordHighlight() && !paused, x + PAD, y + LYRIC_Y, W - 2 * PAD)
                : null;
        int scroll = plan == null ? 0 : Math.round(scrollOffset(plan, snap, cfg.isSmoothScroll() && snap != DEMO));
        if (plan != null && plan.chip != null) {
            int[] c = plan.chip;
            int cy0 = c[1] + scroll;
            if (cy0 + 10 <= y + LYRIC_Y + LYRIC_ROWS * ROW_H) {   // a chip still sliding in below the rows is not drawn
                r.fill(c[0] - 2, cy0 - 1, c[2] + 2, cy0 + 9, OverlayPalette.alpha(p.accent(), 0x48));
                r.fill(c[0] - 1, cy0 + 9, c[2] + 1, cy0 + 10, p.accent());
            }
        }
        r.submit();

        // ---- the cover picture, or the note ----
        Identifier art = ad ? null : coverTexture(snap);
        if (art != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, art, cx, cy, 0f, 0f, COVER, COVER, coverW, coverH, coverW, coverH,
                    0xFFFFFFFF);
        } else {
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(cx + COVER / 2f, cy + COVER / 2f);
                graphics.pose().scale(2f, 2f);
                String note = "♪";
                graphics.text(font, note, -font.width(note) / 2, -4, p.accent(), true);
            } finally {
                graphics.pose().popMatrix();
            }
        }

        // ---- title, artist, time ----
        int tx = cx + COVER + 6;
        int textW = x + W - PAD - tx;
        graphics.text(font, fit(font, ad ? "Advertisement" : snap.title(), textW), tx, y + 6, p.textHover(), true);
        if (!ad && !snap.artist().isEmpty()) {
            graphics.text(font, fit(font, snap.artist(), textW), tx, y + 17, p.dim(), true);
        }
        String time = clock(elapsed) + (total > 0 ? " / " + clock(total) : "");
        graphics.text(font, time, tx, y + 28, paused ? p.dim() : p.accentHover(), true);
        if (paused) {
            String label = "Paused";
            graphics.text(font, label, x + W - PAD - font.width(label), y + 28, p.faint(), true);
        }

        if (cfg.isShowLyrics() && !ad) {
            int lx = x + PAD;
            int ly = y + LYRIC_Y;
            int lw = W - 2 * PAD;
            if (scroll != 0) {
                // Mid-slide: the rows are clipped to the lyric area, and the line that just ended slides up out of it.
                graphics.enableScissor(lx - 3, ly - 1, lx + lw + 3, ly + LYRIC_ROWS * ROW_H);
                try {
                    drawOutgoing(graphics, font, p, lx, ly - ROW_H + scroll, scroll / (float) ROW_H);
                    drawLyrics(graphics, font, snap, plan, paused, p, lx, ly + scroll, lw);
                } finally {
                    graphics.disableScissor();
                }
            } else {
                drawLyrics(graphics, font, snap, plan, paused, p, lx, ly, lw);
            }
        }
        if (!editor) {
            HudSeen.markDrawn(id());
        }
    }

    // ---- Smooth Lyric Scroll (killer560, 2026-10-26: "have a smoother transition of moving the lines up when a line
    // finishes for lyrics"). When the top row moves on to a later row (the next line, or the next wrapped row of a long
    // one), the rows start one row lower and ease up into place over SCROLL_NS, time-based on System.nanoTime so it is
    // the same speed at any frame rate; the row that just ended slides up out of the area and fades. A jump backwards
    // (a seek back) or a new song does not animate. Render thread only; nothing is allocated per frame: the outgoing
    // row is kept as references to the line and its row bounds, which the wrap cache already holds.

    /** How long a slide takes. */
    static final long SCROLL_NS = 200_000_000L;

    private static List<LyricsEngine.LyricLine> scrollLines;
    private static int scrollLine = -1;
    private static int scrollRow = -1;
    private static long scrollStartNs;
    private static boolean scrolling;
    private static LyricsEngine.LyricLine curTopLine;
    private static int[] curTopRow;
    private static LyricsEngine.LyricLine outLine;
    private static int[] outRow;
    // Test hooks (read by the testkit).
    private static volatile float lastScrollOffset;
    private static volatile long scrollAnimations;

    /** How far below its resting place the lyric rows are drawn this frame (0 at rest, up to {@link #ROW_H}). */
    private static float scrollOffset(LyricPlan plan, LyricsEngine.Snapshot snap, boolean smooth) {
        int line = plan.line == null || plan.line.parsed().words().isEmpty() ? -1 : plan.lineIdx;
        int row = line < 0 ? 0 : plan.firstRow;
        boolean sameSong = snap.lines() == scrollLines;
        boolean forward = sameSong && (line > scrollLine || (line == scrollLine && row > scrollRow));
        if (line != scrollLine || row != scrollRow || !sameSong) {
            if (smooth && forward && scrollLine >= 0 && curTopLine != null) {
                outLine = curTopLine;
                outRow = curTopRow;
                scrollStartNs = System.nanoTime();
                scrolling = true;
                scrollAnimations++;
            } else {
                scrolling = false;
            }
            scrollLines = snap.lines();
            scrollLine = line;
            scrollRow = row;
        }
        if (line >= 0 && row < plan.rows.size()) {
            curTopLine = plan.line;
            curTopRow = plan.rows.get(row);
        } else {
            curTopLine = null;
            curTopRow = null;
        }
        float off = 0f;
        if (scrolling && smooth) {
            double t = (System.nanoTime() - scrollStartNs) / (double) SCROLL_NS;
            if (t >= 1) {
                scrolling = false;
            } else {
                double u = 1 - Math.max(0, t);
                off = (float) (ROW_H * u * u * u);   // ease-out cubic: fast start, gentle landing
            }
        } else {
            scrolling = false;
        }
        lastScrollOffset = off;
        return off;
    }

    /** The row that just ended, sliding up and fading; {@code left} is 1 at the start of the slide and 0 at its end. */
    private static void drawOutgoing(GuiGraphicsExtractor graphics, Font font, OverlayPalette p, int lx, int y, float left) {
        if (outLine == null || outRow == null || left <= 0.05f) {
            return;
        }
        List<String> words = outLine.parsed().words();
        int alpha = Math.max(8, Math.min(255, Math.round(left * 255)));
        int colour = OverlayPalette.alpha(p.textHover(), alpha);
        int space = font.width(" ");
        int wx = lx;
        for (int i = outRow[0]; i < outRow[1] && i < words.size(); i++) {
            String word = clean(words.get(i));
            graphics.text(font, word, wx, y, colour, true);
            wx += font.width(word) + space;
        }
    }

    /** What the lyric rows will draw, worked out before the fill batch so the sung word's chip goes under its text. */
    private static final class LyricPlan {
        LyricsEngine.LyricLine line;
        int lineIdx;
        int sung = -1;
        List<int[]> rows = List.of();
        int firstRow;
        /** {x0, y, x1} of the word being sung, or null. */
        int[] chip;
    }

    private static LyricPlan planLyrics(Font font, LyricsEngine.Snapshot snap, double elapsed, boolean highlight,
                                        int lx, int ly, int lw) {
        LyricPlan plan = new LyricPlan();
        if (snap.lines().isEmpty()) {
            return plan;
        }
        plan.lineIdx = snap.lineAt(elapsed);
        plan.line = plan.lineIdx < 0 ? null : snap.lines().get(plan.lineIdx);
        if (plan.line == null || plan.line.parsed().words().isEmpty()) {
            return plan;
        }
        List<String> words = plan.line.parsed().words();
        int at = LyricTiming.wordAt(plan.line.parsed(), plan.line.timeSeconds(), snap.lineEnd(plan.lineIdx), elapsed);
        plan.sung = highlight ? at : -1;
        // Paused: hold the line where it was, rows and all; only the moving highlight goes.
        plan.rows = wrap(font, snap.lines(), plan.lineIdx, words, lw);
        int currentRow = 0;
        for (int r = 0; r < plan.rows.size(); r++) {
            if (at >= plan.rows.get(r)[0]) {
                currentRow = r;
            }
        }
        plan.firstRow = Math.max(0, Math.min(currentRow, plan.rows.size() - LYRIC_ROWS));
        if (plan.sung >= 0) {
            int space = font.width(" ");
            int drawn = 0;
            for (int r = plan.firstRow; r < plan.rows.size() && drawn < LYRIC_ROWS; r++, drawn++) {
                int[] row = plan.rows.get(r);
                int wx = lx;
                for (int i = row[0]; i < row[1]; i++) {
                    int ww = font.width(clean(words.get(i)));
                    if (i == plan.sung) {
                        plan.chip = new int[]{wx, ly + drawn * ROW_H, wx + ww};
                    }
                    wx += ww + space;
                }
            }
        }
        return plan;
    }

    private static void drawLyrics(GuiGraphicsExtractor graphics, Font font, LyricsEngine.Snapshot snap, LyricPlan plan,
                                   boolean paused, OverlayPalette p, int lx, int ly, int lw) {
        if (snap.lines().isEmpty()) {
            if (snap.versionMismatch()) {
                graphics.text(font, fit(font, "Lyrics found are for another version", lw), lx, ly, p.dim(), true);
                String detail = snap.mismatchSeconds() > 0
                        ? "lyrics " + clock(snap.mismatchSeconds()) + ", Spotify " + clock(snap.durationSeconds())
                        : "none is Spotify's length";
                graphics.text(font, fit(font, detail, lw), lx, ly + ROW_H, p.faint(), true);
                return;
            }
            String msg = snap.lyricsAnswered() ? "No synced lyrics for this song" : "Looking up lyrics...";
            graphics.text(font, msg, lx, ly, p.faint(), true);
            return;
        }
        if (plan == null || plan.line == null || plan.line.parsed().words().isEmpty()) {
            // Before the first line, or an empty LRC line (an instrumental break): show the next line coming up.
            int idx = plan == null ? -1 : plan.lineIdx;
            graphics.text(font, "♪", lx, ly, p.accent(), true);
            LyricsEngine.LyricLine next = idx + 1 < snap.lines().size() ? snap.lines().get(idx + 1) : null;
            if (next != null && !next.text().isEmpty()) {
                graphics.text(font, fit(font, clean(next.text()), lw), lx, ly + ROW_H, p.faint(), true);
            }
            return;
        }
        List<String> words = plan.line.parsed().words();
        int space = font.width(" ");
        int drawn = 0;
        for (int r = plan.firstRow; r < plan.rows.size() && drawn < LYRIC_ROWS; r++, drawn++) {
            int[] row = plan.rows.get(r);
            int wx = lx;
            for (int i = row[0]; i < row[1]; i++) {
                String word = clean(words.get(i));
                int colour;
                if (paused) {
                    colour = p.text();                                    // held, nothing moving
                } else if (plan.sung < 0) {
                    colour = p.textHover();                               // Word Highlight off
                } else {
                    colour = i < plan.sung ? p.textHover() : i == plan.sung ? p.accentHover() : p.faint();
                }
                graphics.text(font, word, wx, ly + drawn * ROW_H, colour, true);
                wx += font.width(word) + space;
            }
        }
        if (drawn < LYRIC_ROWS && plan.lineIdx + 1 < snap.lines().size()) {
            String next = snap.lines().get(plan.lineIdx + 1).text();
            if (!next.isEmpty()) {
                graphics.text(font, fit(font, clean(next), lw), lx, ly + drawn * ROW_H, p.faint(), true);
            }
        }
    }

    // ---- cover texture: render thread only ----
    private static int coverSeq = -1;
    private static boolean coverRegistered;
    private static String coverKey = "";
    private static int coverW = 64;
    private static int coverH = 64;

    /** The cover for the track on the snapshot, uploaded once per new picture, or null for the placeholder. */
    private static Identifier coverTexture(LyricsEngine.Snapshot snap) {
        SpotifyMediaSession.Cover c = SpotifyLyricsFeature.cover();
        if (c == null) {
            return null;
        }
        if (c.seq() != coverSeq) {
            coverSeq = c.seq();
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(c.png()));
                coverW = image.getWidth();
                coverH = image.getHeight();
                DynamicTexture texture = new DynamicTexture(() -> "killer560smod spotify cover", image);
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(COVER_ID, texture);
                coverRegistered = true;
                coverKey = c.title();
            } catch (Exception e) {
                coverRegistered = false;
            }
        }
        if (!coverRegistered || !coverKey.equalsIgnoreCase(snap.title())) {
            return null;   // the picture is another track's (the new one's is not in yet)
        }
        return COVER_ID;
    }

    // One wrap per line, not per frame: the rows only change when the line (or the lyrics) does. Render thread only.
    private static List<LyricsEngine.LyricLine> wrapLines;
    private static int wrapIndex = -1;
    private static int wrapWidth = -1;
    private static List<int[]> wrapRows = List.of();

    /** The line's words broken into rows that fit {@code width}: each row is {first word, end word (exclusive)}. */
    private static List<int[]> wrap(Font font, List<LyricsEngine.LyricLine> lines, int index, List<String> words,
                                    int width) {
        if (lines == wrapLines && index == wrapIndex && width == wrapWidth) {
            return wrapRows;
        }
        List<int[]> rows = new ArrayList<>();
        int space = font.width(" ");
        int start = 0;
        int used = 0;
        for (int i = 0; i < words.size(); i++) {
            int ww = font.width(clean(words.get(i)));
            int need = used == 0 ? ww : used + space + ww;
            if (used > 0 && need > width) {
                rows.add(new int[]{start, i});
                start = i;
                used = ww;
            } else {
                used = need;
            }
        }
        rows.add(new int[]{start, words.size()});
        wrapLines = lines;
        wrapIndex = index;
        wrapWidth = width;
        wrapRows = rows;
        return rows;
    }

    /** A lyric or title never carries formatting codes into the HUD. */
    private static String clean(String s) {
        return s.indexOf('§') < 0 ? s : s.replace('§', '?');
    }

    private static String fit(Font font, String s, int width) {
        String c = clean(s);
        if (font.width(c) <= width) {
            return c;
        }
        return font.plainSubstrByWidth(c, Math.max(0, width - font.width("..."))) + "...";
    }

    /** {@code m:ss}. */
    static String clock(double seconds) {
        long s = Math.max(0, (long) Math.floor(seconds));
        long sec = s % 60;
        return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
    }
}
