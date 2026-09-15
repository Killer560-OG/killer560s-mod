package com.killer560.hub.scoreboard;

import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.killer560.hub.scoreboard.ScoreboardData.firstMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.nextAfter;

/**
 * Custom Scoreboard - a port of SkyHanni's Custom Scoreboard ({@code features/gui/customscoreboard/}), which SkyHanni
 * deprecated in 2026 in favour of the standalone "SkyBlock Custom Scoreboard" mod (meowdding/CustomScoreboard). Hides
 * the vanilla sidebar ({@code CustomScoreboardGuiMixin}) and draws a rebuilt one from the parsed sidebar, tab list and
 * action bar: an ordered, toggleable list of {@link ScoreboardEntry} lines, with {@link ScoreboardEvent}s inside the
 * Events line. Sidebar lines no pattern recognises are shown unmodified by the "Unknown Lines" entry (SkyHanni's
 * UnknownLinesHandler), so nothing silently disappears.
 * <p>
 * Only active on Skyblock / p3sim ({@link SkyblockGate#isOnSkyblock()}); anywhere else the vanilla sidebar is left
 * alone. Rebuilt every 5 ticks (SkyHanni rebuilds every 250ms). Drawn through its own Fabric HUD layer like
 * {@code InventoryHudFeature}; {@link Element#render} is the HUD-editor preview only. The screen-edge snap is
 * SkyHanni's auto-alignment: dragging the element in the HUD editor turns it off.
 */
public final class CustomScoreboardFeature {

    public static final String ELEMENT_ID = "custom_scoreboard";

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-customscoreboard");
    private static final long UNKNOWN_LINES_WORLD_GRACE_MS = 3000L;

    private static List<ScoreboardLine> current = Collections.emptyList();
    private static List<String> unknown = Collections.emptyList();
    private static List<Pattern> allPatterns;
    private static int tickCounter = 0;
    private static Object lastLevel = null;
    private static long worldChangedAtMs = 0L;
    private static int[] lastSnapped = null;
    private static boolean loggedError = false;

    private CustomScoreboardFeature() {
    }

    /** Registers the tick and the in-game HUD layer. The editor element ({@link Element#INSTANCE}) is registered separately. */
    public static void register() {
        CustomScoreboardConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(CustomScoreboardFeature::tick);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "custom_scoreboard"),
                (graphics, deltaTracker) -> drawInGame(graphics));
    }

    /** Custom Scoreboard is on and you're on Skyblock / p3sim. */
    public static boolean isActive() {
        return CustomScoreboardConfig.getInstance().isEnabled() && SkyblockGate.isOnSkyblock();
    }

    /** Checked by {@code CustomScoreboardGuiMixin} before vanilla draws the sidebar. */
    public static boolean shouldHideVanilla() {
        return isActive() && CustomScoreboardConfig.getInstance().isHideVanillaScoreboard();
    }

    /** Sidebar lines no pattern recognised this update, unmodified. */
    static List<String> unknownLines() {
        return unknown;
    }

    // ---- update ----

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            worldChangedAtMs = System.currentTimeMillis();
        }
        if (!CustomScoreboardConfig.getInstance().isEnabled()) {
            current = Collections.emptyList();
            unknown = Collections.emptyList();
            return;
        }
        if (++tickCounter < 5) {
            return;
        }
        tickCounter = 0;
        try {
            ScoreboardData.refresh(client);
            if (SkyblockGate.isOnSkyblock() && client.level != null) {
                rebuild();
            } else {
                current = Collections.emptyList();
                unknown = Collections.emptyList();
            }
        } catch (RuntimeException e) {
            if (!loggedError) {
                loggedError = true;
                LOGGER.warn("[CustomScoreboard] Update failed: {}", e.toString(), e);
            }
        }
    }

    private static void rebuild() {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        unknown = System.currentTimeMillis() - worldChangedAtMs < UNKNOWN_LINES_WORLD_GRACE_MS
                ? Collections.emptyList() : computeUnknownLines();
        List<ScoreboardLine> lines = new ArrayList<>();
        if (!cfg.isUseCustomLines()) {
            if (!ScoreboardData.objectiveTitle().isEmpty()) {
                lines.add(new ScoreboardLine(ScoreboardData.objectiveTitle(), cfg.getTitleAlignment()));
            }
            lines.addAll(ScoreboardLine.of(ScoreboardData.sidebar()));
        } else {
            for (CustomScoreboardConfig.Row<ScoreboardEntry> row : cfg.entries()) {
                if (!row.enabled || !row.id.visible(cfg)) {
                    continue;
                }
                List<ScoreboardLine> elementLines = row.id.lines(cfg);
                if (elementLines.isEmpty()) {
                    continue;
                }
                if (cfg.isHideConsecutiveEmptyLines() && elementLines.get(0).isBlank()
                        && !lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                    continue;
                }
                lines.addAll(elementLines);
            }
        }
        current = Collections.unmodifiableList(cfg.isHideEmptyLinesAtTopAndBottom() ? trimBlankEdges(lines) : lines);
    }

    private static List<ScoreboardLine> trimBlankEdges(List<ScoreboardLine> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        return new ArrayList<>(lines.subList(start, end));
    }

    private static List<Pattern> allPatterns() {
        if (allPatterns == null) {
            Set<Pattern> set = new LinkedHashSet<>();
            for (ScoreboardEntry e : ScoreboardEntry.values()) {
                set.addAll(e.patterns);
            }
            for (ScoreboardEvent e : ScoreboardEvent.values()) {
                set.addAll(e.patterns);
            }
            set.addAll(ScoreboardPattern.BROKEN);
            set.add(ScoreboardPattern.SKYBLOCK_AREA);
            allPatterns = List.copyOf(set);
        }
        return allPatterns;
    }

    /** SkyHanni's {@code UnknownLinesHandler.handleUnknownLines}, minus its error reporting. */
    private static List<String> computeUnknownLines() {
        List<String> sidebar = ScoreboardData.sidebar();
        List<String> out = new ArrayList<>();
        for (String raw : sidebar) {
            String line = ScoreboardData.removeResets(raw);
            if (line.isBlank() || line.trim().length() <= 3) {
                continue;
            }
            boolean known = false;
            for (Pattern pattern : allPatterns()) {
                if (pattern.matcher(line).matches()) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                out.add(line);
            }
        }
        if (out.isEmpty()) {
            return out;
        }

        String objectiveLine = firstMatches(ScoreboardPattern.OBJECTIVE, sidebar);
        if (objectiveLine == null) {
            objectiveLine = "Objective";
        }
        String n1 = nextAfter(sidebar, objectiveLine, 1);
        String n2 = nextAfter(sidebar, objectiveLine, 2);
        String n3 = nextAfter(sidebar, objectiveLine, 3);
        out.removeIf(line -> line.equals(n1) || line.equals(n2) || line.equals(n3)
                || ScoreboardData.matches(ScoreboardPattern.THIRD_OBJECTIVE_LINE, line));

        removeSection(out, sidebar, ScoreboardPattern.JACOBS_CONTEST, "§eJacob's Contest", 3);
        removeSection(out, sidebar, ScoreboardPattern.AGATHAS_CONTEST, "§eAgatha's Contest", 2);
        removeSection(out, sidebar, ScoreboardPattern.MIRIAS_CONTEST, "§eMiria's Contest", 2);
        removeSection(out, sidebar, ScoreboardPattern.SLAYER_QUEST, "Slayer Quest", 2);
        removeSection(out, sidebar, ScoreboardPattern.MOB_LOCATION, "Tracker Mob Location:", 1);
        removeSection(out, sidebar, ScoreboardPattern.DARK_AUCTION_CURRENT_ITEM, "Current Item:", 1);
        return out;
    }

    private static void removeSection(List<String> unknownLines, List<String> sidebar, Pattern pattern,
                                      String fallbackHeader, int linesAfter) {
        String header = firstMatches(pattern, sidebar);
        if (header == null) {
            header = fallbackHeader;
        }
        for (int i = 1; i <= linesAfter; i++) {
            String toRemove = nextAfter(sidebar, header, i);
            if (toRemove != null) {
                unknownLines.removeIf(toRemove::equals);
            }
        }
    }

    // ---- rendering ----

    private static void drawInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isActive() || client.player == null || client.options.hideGui || client.screen instanceof HudEditorScreen) {
            return;
        }
        List<ScoreboardLine> lines = current;
        if (lines.isEmpty()) {
            return;
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        Font font = client.font;
        int[] pos = resolveSnappedPosition(graphics, cfg, boxWidth(font, lines, cfg), boxHeight(font, lines, cfg));
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(Element.INSTANCE);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawBoard(graphics, font, 0, 0, lines, cfg);
        } catch (RuntimeException e) {
            // A broken frame must never take down the rest of the HUD.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** SkyHanni's {@code RenderBackground.updatePosition}; a HUD-editor drag disables the snap like SkyHanni's
     *  {@code onGuiPositionMoved}. */
    private static int[] resolveSnappedPosition(GuiGraphicsExtractor graphics, CustomScoreboardConfig cfg, int boxW, int boxH) {
        int[] stored = com.killer560.hub.hud.HudElementRegistry.resolvePosition(Element.INSTANCE);
        if (cfg.getHorizontalSnap() == CustomScoreboardConfig.HorizontalSnap.NONE
                && cfg.getVerticalSnap() == CustomScoreboardConfig.VerticalSnap.NONE) {
            lastSnapped = null;
            return stored;
        }
        if (lastSnapped != null && (stored[0] != lastSnapped[0] || stored[1] != lastSnapped[1])) {
            cfg.setHorizontalSnap(CustomScoreboardConfig.HorizontalSnap.NONE);
            cfg.setVerticalSnap(CustomScoreboardConfig.VerticalSnap.NONE);
            cfg.save();
            lastSnapped = null;
            return stored;
        }
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(Element.INSTANCE);
        int w = Math.round(boxW * scale);
        int h = Math.round(boxH * scale);
        int sw = graphics.guiWidth();
        int sh = graphics.guiHeight();
        int x = switch (cfg.getHorizontalSnap()) {
            case NONE -> stored[0];
            case LEFT -> 0;
            case CENTER -> (sw - w) / 2;
            case RIGHT -> sw - w;
        };
        int y = switch (cfg.getVerticalSnap()) {
            case NONE -> stored[1];
            case TOP -> 0;
            case CENTER -> (sh - h) / 2;
            case BOTTOM -> sh - h;
        };
        if (x != stored[0] || y != stored[1]) {
            HudConfig.getInstance().setPosition(ELEMENT_ID, x, y);
        }
        lastSnapped = new int[]{x, y};
        return lastSnapped;
    }

    private static int borderSize(CustomScoreboardConfig cfg) {
        return cfg.isBorderEnabled() ? cfg.getBorderThickness() : 0;
    }

    private static int contentWidth(Font font, List<ScoreboardLine> lines) {
        int w = 0;
        for (ScoreboardLine line : lines) {
            w = Math.max(w, font.width(line.text()));
        }
        return w;
    }

    private static int contentHeight(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return lines.isEmpty() ? 0 : lines.size() * font.lineHeight + (lines.size() - 1) * cfg.getLineSpacing();
    }

    static int boxWidth(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return contentWidth(font, lines) + 2 * (cfg.getPadding() + borderSize(cfg));
    }

    static int boxHeight(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return contentHeight(font, lines, cfg) + 2 * (cfg.getPadding() + borderSize(cfg));
    }

    static void drawBoard(GuiGraphicsExtractor g, Font font, int x, int y, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        int b = borderSize(cfg);
        int pad = cfg.getPadding();
        int contentW = contentWidth(font, lines);
        int w = boxWidth(font, lines, cfg);
        int h = boxHeight(font, lines, cfg);
        int radius = cfg.isRoundedCorners() ? Math.min(cfg.getCornerRadius(), Math.min(w, h) / 2) : 0;

        if (cfg.isBackgroundEnabled()) {
            fillRounded(g, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, radius - b), cfg.getBackgroundColor());
        }
        if (b > 0) {
            drawRoundedBorder(g, x, y, w, h, radius, b, cfg.getBorderColor());
        }

        int textX = x + b + pad;
        int lineStep = font.lineHeight + cfg.getLineSpacing();
        for (int i = 0; i < lines.size(); i++) {
            ScoreboardLine line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lw = font.width(line.text());
            int lx = switch (line.align()) {
                case LEFT -> textX;
                case CENTER -> textX + (contentW - lw) / 2;
                case RIGHT -> textX + contentW - lw;
            };
            g.text(font, line.text(), lx, y + b + pad + i * lineStep, 0xFFFFFFFF, cfg.isTextShadow());
        }
    }

    /** Horizontal inset of row {@code row} (0-based) of a {@code h}-tall rounded rectangle with corner radius {@code r}. */
    private static int inset(int row, int h, int r) {
        if (r <= 0) {
            return 0;
        }
        int fromEdge = row < r ? row : (row >= h - r ? h - 1 - row : -1);
        if (fromEdge < 0) {
            return 0;
        }
        double dy = r - fromEdge - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
    }

    static void fillRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int row = 0; row < h; row++) {
            if (row == r && h - r > r) {
                g.fill(x, y + r, x + w, y + h - r, color);
                row = h - r - 1;
                continue;
            }
            int in = inset(row, h, r);
            g.fill(x + in, y + row, x + w - in, y + row + 1, color);
        }
    }

    static void drawRoundedBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int t, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int innerW = w - 2 * t;
        int innerH = h - 2 * t;
        int innerR = Math.max(0, r - t);
        for (int row = 0; row < h; row++) {
            int outer = inset(row, h, r);
            int innerRow = row - t;
            if (innerRow < 0 || innerRow >= innerH || innerW <= 0) {
                g.fill(x + outer, y + row, x + w - outer, y + row + 1, color);
                continue;
            }
            int inner = t + inset(innerRow, innerH, innerR);
            g.fill(x + outer, y + row, x + Math.max(outer, inner), y + row + 1, color);
            g.fill(x + w - Math.max(outer, inner), y + row, x + w - outer, y + row + 1, color);
        }
    }

    private static List<ScoreboardLine> previewLines() {
        if (isActive() && !current.isEmpty()) {
            return current;
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        List<ScoreboardLine> out = new ArrayList<>();
        for (CustomScoreboardConfig.Row<ScoreboardEntry> row : cfg.entries()) {
            if (!row.enabled) {
                continue;
            }
            if (row.id == ScoreboardEntry.TITLE || row.id == ScoreboardEntry.FOOTER) {
                out.addAll(row.id.lines(cfg));
            } else if (row.id.isSeparator()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                    out.add(ScoreboardLine.of(""));
                }
            } else {
                out.addAll(ScoreboardLine.of(row.id.sample()));
            }
        }
        return trimBlankEdges(out);
    }

    /** HUD-editor entry (movable/scalable, position persisted in {@code HudConfig}). */
    public static final class Element implements HudElement {

        public static final Element INSTANCE = new Element();

        private Element() {
        }

        @Override
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Custom Scoreboard";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 80;
        }

        @Override
        public int width() {
            return Math.max(20, boxWidth(Minecraft.getInstance().font, previewLines(), CustomScoreboardConfig.getInstance()));
        }

        @Override
        public int height() {
            return Math.max(10, boxHeight(Minecraft.getInstance().font, previewLines(), CustomScoreboardConfig.getInstance()));
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            // Editor preview only - in game this element is drawn by CustomScoreboardFeature's own HUD layer.
            if (!cfg.isEnabled() || !(client.screen instanceof HudEditorScreen)) {
                return;
            }
            drawBoard(graphics, client.font, x, y, previewLines(), cfg);
        }
    }
}
