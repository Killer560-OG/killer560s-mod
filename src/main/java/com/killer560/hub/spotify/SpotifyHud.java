package com.killer560.hub.spotify;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotify HUD: a now-playing panel, moved and scaled in Edit HUD like every other element (id {@value #ELEMENT_ID},
 * drawn in game by {@code hud/HudInGameRenderer}).
 *
 * <p>Shows a cover tile, the title and artist, elapsed / total time with a progress bar, and the lyric line being sung
 * with the current word highlighted. Everything comes from {@link LyricsEngine.Snapshot}, which the lyrics poll thread
 * publishes whole; this class only reads it, so nothing here touches the network, Windows or a lock. The clock is run
 * from the snapshot's two parts on every frame, so the highlight moves smoothly instead of on the 250 ms poll.
 *
 * <p>Cover art: the Spotify window title (the only thing read from the app) carries no picture, and lrclib has none,
 * so the tile is a themed placeholder with a note. The total time is lrclib's {@code duration} for the matched lyrics.
 *
 * <p>Colours are the mod's existing HUD ones: Inventory HUD's panel {@code 0x101010} and border {@code 0xCC6600}
 * (InventoryHudFeature), its slot grey {@code 0x262626} for the bar track and cover tile, the Listing Helper's
 * {@code 0xD0} panel alpha, and Real Time's light orange {@code 0xFFA040} (RealTimeConfig) for the word being sung.
 */
public final class SpotifyHud implements HudElement {

    public static final String ELEMENT_ID = "spotify_hud";
    public static final SpotifyHud INSTANCE = new SpotifyHud();

    private static final int PANEL_BG = 0xD0101010;
    private static final int BORDER = 0xFFCC6600;
    private static final int TRACK = 0xFF262626;
    private static final int ACCENT = 0xFFFFA040;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int DARK_GREY = 0xFF777777;

    private static final int W = 200;
    private static final int PAD = 4;
    private static final int COVER = 32;
    private static final int BAR_Y = 40;
    private static final int LYRIC_Y = 46;
    private static final int ROW_H = 10;
    private static final int LYRIC_ROWS = 2;

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
        return SpotifyHudConfig.getInstance().isShowLyrics() ? LYRIC_Y + LYRIC_ROWS * ROW_H + PAD : BAR_Y + 2 + PAD;
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
                true);
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
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        int h = height();

        // Panel: dark fill with a one-pixel orange border.
        graphics.fill(x, y, x + W, y + h, PANEL_BG);
        graphics.fill(x, y, x + W, y + 1, BORDER);
        graphics.fill(x, y + h - 1, x + W, y + h, BORDER);
        graphics.fill(x, y + 1, x + 1, y + h - 1, BORDER);
        graphics.fill(x + W - 1, y + 1, x + W, y + h - 1, BORDER);

        // Cover tile (placeholder: see the class doc).
        int cx = x + PAD;
        int cy = y + PAD;
        graphics.fill(cx, cy, cx + COVER, cy + COVER, TRACK);
        graphics.fill(cx, cy, cx + COVER, cy + 1, BORDER);
        graphics.fill(cx, cy + COVER - 1, cx + COVER, cy + COVER, BORDER);
        graphics.fill(cx, cy + 1, cx + 1, cy + COVER - 1, BORDER);
        graphics.fill(cx + COVER - 1, cy + 1, cx + COVER, cy + COVER - 1, BORDER);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(cx + COVER / 2f, cy + COVER / 2f);
            graphics.pose().scale(2f, 2f);
            String note = "♪";
            graphics.text(font, note, -font.width(note) / 2, -4, ACCENT, true);
        } finally {
            graphics.pose().popMatrix();
        }

        // Title, artist, time.
        int tx = cx + COVER + 6;
        int textW = x + W - PAD - tx;
        boolean ad = snap.kind() == NowPlaying.Kind.AD;
        graphics.text(font, fit(font, ad ? "Advertisement" : snap.title(), textW), tx, y + 6, WHITE, true);
        if (!ad && !snap.artist().isEmpty()) {
            graphics.text(font, fit(font, snap.artist(), textW), tx, y + 17, GREY, true);
        }
        long now = System.currentTimeMillis();
        double elapsed = editor && snap == DEMO ? DEMO_PLAYED_MS / 1000.0 : snap.elapsedSeconds(now);
        double total = snap.durationSeconds();
        if (total > 0) {
            elapsed = Math.min(elapsed, total);
        }
        String time = clock(elapsed) + (total > 0 ? " / " + clock(total) : "") + (paused ? "  Paused" : "");
        graphics.text(font, time, tx, y + 28, paused ? DARK_GREY : ACCENT, true);

        // Progress bar across the panel, under the cover.
        int bx0 = x + PAD;
        int bx1 = x + W - PAD;
        graphics.fill(bx0, y + BAR_Y, bx1, y + BAR_Y + 2, TRACK);
        if (total > 0) {
            int filled = (int) Math.round((bx1 - bx0) * Math.max(0, Math.min(1, elapsed / total)));
            if (filled > 0) {
                graphics.fill(bx0, y + BAR_Y, bx0 + filled, y + BAR_Y + 2, BORDER);
            }
        }

        if (cfg.isShowLyrics()) {
            drawLyrics(graphics, font, snap, elapsed, cfg.isWordHighlight(), x + PAD, y + LYRIC_Y, W - 2 * PAD, ad);
        }
        if (!editor) {
            HudSeen.markDrawn(id());
        }
    }

    private static void drawLyrics(GuiGraphicsExtractor graphics, Font font, LyricsEngine.Snapshot snap,
                                   double elapsed, boolean highlight, int lx, int ly, int lw, boolean ad) {
        if (ad) {
            return;
        }
        if (snap.lines().isEmpty()) {
            String msg = snap.lyricsAnswered() ? "No synced lyrics for this song" : "Looking up lyrics...";
            graphics.text(font, msg, lx, ly, DARK_GREY, true);
            return;
        }
        int lineIdx = snap.lineAt(elapsed);
        LyricsEngine.LyricLine line = lineIdx < 0 ? null : snap.lines().get(lineIdx);
        if (line == null || line.parsed().words().isEmpty()) {
            // Before the first line, or an empty LRC line (an instrumental break): show the next line coming up.
            graphics.text(font, "♪", lx, ly, DARK_GREY, true);
            LyricsEngine.LyricLine next = lineIdx + 1 < snap.lines().size() ? snap.lines().get(lineIdx + 1) : null;
            if (next != null && !next.text().isEmpty()) {
                graphics.text(font, fit(font, clean(next.text()), lw), lx, ly + ROW_H, DARK_GREY, true);
            }
            return;
        }
        List<String> words = line.parsed().words();
        int sung = highlight ? LyricTiming.wordAt(line.parsed(), line.timeSeconds(), snap.lineEnd(lineIdx), elapsed)
                : -1;
        List<int[]> rows = wrap(font, snap.lines(), lineIdx, words, lw);
        int currentRow = 0;
        for (int r = 0; r < rows.size(); r++) {
            if (sung >= rows.get(r)[0]) {
                currentRow = r;
            }
        }
        int firstRow = Math.max(0, Math.min(currentRow, rows.size() - LYRIC_ROWS));
        int space = font.width(" ");
        int drawn = 0;
        for (int r = firstRow; r < rows.size() && drawn < LYRIC_ROWS; r++, drawn++) {
            int[] row = rows.get(r);
            int wx = lx;
            for (int i = row[0]; i < row[1]; i++) {
                String word = clean(words.get(i));
                int colour = !highlight ? WHITE : i < sung ? WHITE : i == sung ? ACCENT : GREY;
                graphics.text(font, word, wx, ly + drawn * ROW_H, colour, true);
                wx += font.width(word) + space;
            }
        }
        if (drawn < LYRIC_ROWS && lineIdx + 1 < snap.lines().size()) {
            String next = snap.lines().get(lineIdx + 1).text();
            if (!next.isEmpty()) {
                graphics.text(font, fit(font, clean(next), lw), lx, ly + drawn * ROW_H, DARK_GREY, true);
            }
        }
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
