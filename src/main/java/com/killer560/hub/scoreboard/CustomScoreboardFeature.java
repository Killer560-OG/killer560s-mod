package com.killer560.hub.scoreboard;

import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.killer560.hub.scoreboard.ScoreboardData.firstMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.nextAfter;

/**
 * Custom Scoreboard - a port of SkyHanni's Custom Scoreboard ({@code features/gui/customscoreboard/}), which SkyHanni
 * deprecated in 2026 in favour of the standalone "SkyBlock Custom Scoreboard" mod (meowdding/CustomScoreboard). Hides
 * the vanilla sidebar ({@code CustomScoreboardGuiMixin}) and draws a rebuilt one from the parsed sidebar, tab list,
 * action bar and {@link ScoreboardExtraData}: an ordered, toggleable list of {@link ScoreboardEntry} lines, with
 * {@link ScoreboardEvent}s inside the Events line. Sidebar lines no pattern recognises are shown unmodified by the
 * "Unknown Lines" entry (SkyHanni's UnknownLinesHandler), so nothing silently disappears.
 * <p>
 * The full board is only active on Skyblock / p3sim ({@link SkyblockGate#isOnSkyblock()}). Elsewhere the vanilla
 * sidebar is always left alone; "Outside Skyblock: Minimal Board" only adds a small extra board next to it. Rebuilt
 * every 5 ticks (SkyHanni rebuilds every 250ms). Drawn through its own Fabric HUD layer like {@code InventoryHudFeature};
 * {@link Element#render} is the HUD-editor preview only. The screen-edge snap is SkyHanni's auto-alignment: dragging the
 * element in the HUD editor turns it off; the snapped position is written to {@code HudConfig}.
 * <p>
 * "Clickable Lines" (SkyBlock Custom Scoreboard's line actions): while chat is open, hovering a line with actions
 * shows its tooltip and clicking runs its command.
 */
public final class CustomScoreboardFeature {

    public static final String ELEMENT_ID = "custom_scoreboard";

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-customscoreboard");
    private static final long UNKNOWN_LINES_WORLD_GRACE_MS = 3000L;
    private static final long ISLAND_SWITCH_CACHE_MS = 6000L;
    private static final long UNKNOWN_WARNING_COOLDOWN_MS = 10_000L;
    private static final int UNKNOWN_WARNING_SESSION_LIMIT = 20;

    private static List<ScoreboardLine> current = Collections.emptyList();
    private static boolean currentIsMinimal = false;
    private static List<String> unknown = Collections.emptyList();
    private static List<Pattern> allPatterns;
    private static int tickCounter = 0;
    private static Object lastLevel = null;
    private static long worldChangedAtMs = 0L;
    private static int[] lastSnapped = null;
    private static boolean hudConfigDirty = false;
    private static long hudConfigSavedAtMs = 0L;
    private static boolean loggedError = false;
    private static final Set<String> warnedUnknown = new HashSet<>();
    private static long lastUnknownWarningMs = 0L;

    /** Where the board was last drawn in game, for hover/click hit-testing. */
    private record Layout(List<ScoreboardLine> lines, int x, int y, float scale, int contentX, int contentY,
                          int contentW, int lineStep, int lineHeight) {
    }

    private static volatile Layout lastLayout = null;

    private CustomScoreboardFeature() {
    }

    /** Registers the tick, the in-game HUD layer and the chat-screen click hook. The editor element
     *  ({@link Element#INSTANCE}) is registered separately. */
    public static void register() {
        CustomScoreboardConfig.getInstance();
        ScoreboardExtraData.register();
        // Only register the blur pipeline when the user has actually switched blur on. RenderPipelines.register
        // puts it in the static list ShaderManager precompiles on every resource reload, and a GLSL compile
        // failure there is a hard crash - so an untested shader must never load for someone who isn't using it.
        // Turning blur on therefore takes effect on the next launch (the tab says so).
        if (CustomScoreboardConfig.getInstance().isBackgroundBlur()) {
            ScoreboardBlur.register();
        }
        ClientTickEvents.END_CLIENT_TICK.register(CustomScoreboardFeature::tick);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "custom_scoreboard"),
                (graphics, deltaTracker) -> drawInGame(graphics));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                    try {
                        return event.button() != 0 || !onChatClick(event.x(), event.y());
                    } catch (RuntimeException e) {
                        return true;
                    }
                });
            }
        });
    }

    /** Custom Scoreboard is on and you're on Skyblock / p3sim. */
    public static boolean isActive() {
        return CustomScoreboardConfig.getInstance().isEnabled() && SkyblockGate.isOnSkyblock();
    }

    /** The off-Skyblock minimal board is switched on and allowed ("Skyblock Only" off). */
    private static boolean minimalActive() {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        return cfg.isEnabled() && !SkyblockGate.isOnSkyblock()
                && cfg.getOutsideSkyblockMode() == CustomScoreboardConfig.OutsideSkyblockMode.MINIMAL
                && SkyblockGate.allows();
    }

    /** Checked by {@code CustomScoreboardGuiMixin} before vanilla draws the sidebar. */
    public static boolean shouldHideVanilla() {
        // Only on Skyblock, and only with something to draw: if the rebuild failed or produced nothing, keep the
        // vanilla sidebar rather than leaving the player with no scoreboard at all. The minimal off-Skyblock board
        // never hides it.
        return isActive() && CustomScoreboardConfig.getInstance().isHideVanillaScoreboard()
                && !currentIsMinimal && !current.isEmpty();
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
        if (hudConfigDirty && System.currentTimeMillis() - hudConfigSavedAtMs > 2000L) {
            hudConfigDirty = false;
            hudConfigSavedAtMs = System.currentTimeMillis();
            HudConfig.getInstance().save();
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        if (!cfg.isEnabled()) {
            clearBoard();
            return;
        }
        if (++tickCounter < 5) {
            return;
        }
        tickCounter = 0;
        boolean onSkyblock = SkyblockGate.isOnSkyblock();
        try {
            if (onSkyblock) {
                // Off Skyblock the sidebar/tab list aren't even read.
                ScoreboardData.refresh(client);
            }
            ScoreboardExtraData.update(client, onSkyblock);
            if (client.level == null) {
                clearBoard();
            } else if (onSkyblock) {
                rebuild(cfg);
            } else if (minimalActive()) {
                unknown = Collections.emptyList();
                current = Collections.unmodifiableList(buildMinimal(client, cfg));
                currentIsMinimal = true;
            } else {
                // Off Skyblock: nothing to draw, vanilla sidebar untouched.
                clearBoard();
            }
        } catch (RuntimeException e) {
            // Don't keep drawing a stale board forever; shouldHideVanilla() falls back to vanilla while empty.
            clearBoard();
            if (!loggedError) {
                loggedError = true;
                LOGGER.warn("[CustomScoreboard] Update failed: {}", e.toString(), e);
            }
        }
    }

    private static void clearBoard() {
        current = Collections.emptyList();
        currentIsMinimal = false;
        unknown = Collections.emptyList();
    }

    private static void rebuild(CustomScoreboardConfig cfg) {
        long now = System.currentTimeMillis();
        // "Cache Scoreboard on Island Switch" (SkyHanni): keep the last board while the new island's sidebar and
        // tab list are still loading, instead of shaking through half-empty states.
        if (cfg.isCacheOnIslandSwitch() && !currentIsMinimal && !current.isEmpty()
                && now - worldChangedAtMs < ISLAND_SWITCH_CACHE_MS && !ScoreboardData.islandKnown()) {
            return;
        }
        unknown = now - worldChangedAtMs < UNKNOWN_LINES_WORLD_GRACE_MS
                ? Collections.emptyList() : computeUnknownLines();
        warnUnknownLines(cfg);
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
        currentIsMinimal = false;
    }

    /** Off-Skyblock "Minimal Board": title, today's date and time, online players, footer. */
    private static List<ScoreboardLine> buildMinimal(Minecraft client, CustomScoreboardConfig cfg) {
        List<ScoreboardLine> out = new ArrayList<>();
        if (cfg.isUseCustomTitle() && cfg.isUseCustomTitleOutsideSkyblock()) {
            out.addAll(ScoreboardEntry.TITLE.lines(cfg));
        } else {
            // SkyHanni ScoreboardElementTitle: outside SkyBlock the server's own title unless "Custom Title Outside SkyBlock".
            net.minecraft.world.scores.Objective objective = ScoreboardData.sidebarObjective(client);
            String title = objective == null ? "" : ScoreboardData.formatted(objective.getDisplayName(), false);
            if (!title.isEmpty()) {
                out.add(new ScoreboardLine(title, cfg.getTitleAlignment()));
            }
        }
        out.add(ScoreboardLine.of(""));
        out.add(ScoreboardLine.of("§7" + cfg.getDateFormat().today() + " §8"
                + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(cfg.isTime24h() ? "HH:mm" : "h:mma", java.util.Locale.US)).toLowerCase(java.util.Locale.ROOT)));
        if (client.getConnection() != null) {
            out.add(ScoreboardLine.of(ScoreboardLine.formatNumberDisplay("Players",
                    String.valueOf(client.getConnection().getListedOnlinePlayers().size()), "§a")));
        }
        out.add(ScoreboardLine.of(""));
        for (String part : cfg.getCustomFooter().replace("&&", "§").split("\\\\n")) {
            out.add(new ScoreboardLine(part, cfg.getFooterAlignment()));
        }
        return trimBlankEdges(out);
    }

    /** SkyHanni's "Unknown Lines warning": one chat note per new unknown line, rate limited, Hypixel only. */
    private static void warnUnknownLines(CustomScoreboardConfig cfg) {
        if (!cfg.isUnknownLinesWarning() || unknown.isEmpty() || !ScoreboardData.islandKnown()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (warnedUnknown.size() >= UNKNOWN_WARNING_SESSION_LIMIT || now - lastUnknownWarningMs < UNKNOWN_WARNING_COOLDOWN_MS) {
            return;
        }
        for (String line : unknown) {
            if (warnedUnknown.add(line)) {
                lastUnknownWarningMs = now;
                LOGGER.info("[CustomScoreboard] Unknown scoreboard line: {}", line);
                ModChat.send("Custom Scoreboard", ModChat.text("Unknown scoreboard line: "),
                        Component.literal(line));
                return;
            }
        }
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
        lastLayout = null;
        if (!(isActive() || minimalActive()) || client.player == null || client.options.hideGui
                || client.screen instanceof HudEditorScreen) {
            return;
        }
        List<ScoreboardLine> lines = current;
        if (lines.isEmpty()) {
            return;
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        if (cfg.isHideWhenTab() && client.options.keyPlayerList.isDown()) {
            return;
        }
        boolean chatOpen = client.screen instanceof ChatScreen;
        if (cfg.isHideWhenChat() && chatOpen) {
            return;
        }
        Font font = client.font;
        int[] pos;
        float scale;
        try {
            pos = resolveSnappedPosition(graphics, cfg, boxWidth(font, lines, cfg), boxHeight(font, lines, cfg));
            scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(Element.INSTANCE);
        } catch (RuntimeException e) {
            return;
        }
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawBoard(graphics, font, 0, 0, lines, cfg, true);
        } catch (RuntimeException e) {
            // A broken frame must never take down the rest of the HUD.
        } finally {
            graphics.pose().popMatrix();
        }
        int inset = borderSize(cfg) + cfg.getPadding();
        Layout layout = new Layout(lines, pos[0], pos[1], scale, inset, inset, innerWidth(font, lines, cfg),
                font.lineHeight + cfg.getLineSpacing(), font.lineHeight);
        lastLayout = layout;
        if (chatOpen && cfg.isLineActions()) {
            try {
                drawHoverTooltip(client, graphics, layout);
            } catch (RuntimeException ignored) {
                // tooltip is cosmetic
            }
        }
    }

    private static ScoreboardLine lineAt(Layout layout, double mouseX, double mouseY) {
        if (layout == null || layout.scale <= 0) {
            return null;
        }
        double lx = (mouseX - layout.x) / layout.scale - layout.contentX;
        double ly = (mouseY - layout.y) / layout.scale - layout.contentY;
        if (lx < 0 || lx > layout.contentW || ly < 0) {
            return null;
        }
        int index = (int) (ly / layout.lineStep);
        if (index >= layout.lines.size() || ly - index * layout.lineStep > layout.lineHeight) {
            return null;
        }
        ScoreboardLine line = layout.lines.get(index);
        return line.isBlank() ? null : line;
    }

    private static double[] mouseGui(Minecraft client) {
        return new double[]{client.mouseHandler.getScaledXPos(client.getWindow()), client.mouseHandler.getScaledYPos(client.getWindow())};
    }

    private static void drawHoverTooltip(Minecraft client, GuiGraphicsExtractor graphics, Layout layout) {
        double[] mouse = mouseGui(client);
        ScoreboardLine line = lineAt(layout, mouse[0], mouse[1]);
        if (line == null || line.hover() == null || line.hover().isEmpty()) {
            return;
        }
        List<Component> tooltip = new ArrayList<>(line.hover().size());
        for (String s : line.hover()) {
            tooltip.add(Component.literal(s));
        }
        // Same GuiGraphicsExtractor as the chat screen, whose deferred pass draws this on top.
        graphics.setComponentTooltipForNextFrame(client.font, tooltip, (int) mouse[0], (int) mouse[1]);
    }

    /** @return true if the click hit a line with a command (which is then run and the click consumed). */
    private static boolean onChatClick(double mouseX, double mouseY) {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isLineActions() || client.player == null) {
            return false;
        }
        ScoreboardLine line = lineAt(lastLayout, mouseX, mouseY);
        if (line == null || line.command() == null) {
            return false;
        }
        client.player.connection.sendCommand(line.command());
        return true;
    }

    /** SkyHanni's {@code RenderBackground.updatePosition}; a HUD-editor drag disables the snap like SkyHanni's
     *  {@code onGuiPositionMoved}. The snapped position is persisted to {@code HudConfig} (debounced). */
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
        int margin = cfg.getMargin();
        int x = switch (cfg.getHorizontalSnap()) {
            case NONE -> stored[0];
            case LEFT -> margin;
            case CENTER -> (sw - w) / 2;
            case RIGHT -> sw - w - margin;
        };
        int y = switch (cfg.getVerticalSnap()) {
            case NONE -> stored[1];
            case TOP -> margin;
            case CENTER -> (sh - h) / 2;
            case BOTTOM -> sh - h - margin;
        };
        if (x != stored[0] || y != stored[1]) {
            HudConfig.getInstance().setPosition(ELEMENT_ID, x, y);
            hudConfigDirty = true;
        }
        lastSnapped = new int[]{x, y};
        return lastSnapped;
    }

    private static int borderSize(CustomScoreboardConfig cfg) {
        return cfg.isBorderEnabled() ? cfg.getBorderThickness() : 0;
    }

    private static int lineWidth(Font font, ScoreboardLine line, long now) {
        int w = font.width(line.text());
        String popup = line.activePopup(now);
        return popup == null ? w : w + font.width(popup);
    }

    private static int contentWidth(Font font, List<ScoreboardLine> lines) {
        long now = System.currentTimeMillis();
        int w = 0;
        for (ScoreboardLine line : lines) {
            w = Math.max(w, lineWidth(font, line, now));
        }
        return w;
    }

    private static int contentHeight(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return lines.isEmpty() ? 0 : lines.size() * font.lineHeight + (lines.size() - 1) * cfg.getLineSpacing();
    }

    /** Text area width: the widest line, widened to honour "Min Width" (SkyBlock Custom Scoreboard). */
    private static int innerWidth(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return Math.max(contentWidth(font, lines), cfg.getMinWidth() - 2 * (cfg.getPadding() + borderSize(cfg)));
    }

    static int boxWidth(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return innerWidth(font, lines, cfg) + 2 * (cfg.getPadding() + borderSize(cfg));
    }

    static int boxHeight(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return Math.max(cfg.getMinHeight(), contentHeight(font, lines, cfg) + 2 * (cfg.getPadding() + borderSize(cfg)));
    }

    /** @param inGame true for the real HUD (background blur only exists there, never in the HUD editor preview) */
    static void drawBoard(GuiGraphicsExtractor g, Font font, int x, int y, List<ScoreboardLine> lines, CustomScoreboardConfig cfg,
                          boolean inGame) {
        int b = borderSize(cfg);
        int pad = cfg.getPadding();
        int contentW = innerWidth(font, lines, cfg);
        int w = boxWidth(font, lines, cfg);
        int h = boxHeight(font, lines, cfg);
        int radius = cfg.isRoundedCorners() ? Math.min(cfg.getCornerRadius(), Math.min(w, h) / 2) : 0;
        long now = System.currentTimeMillis();

        if (inGame && cfg.isBackgroundBlur()) {
            ScoreboardBlur.submit(g, x, y, w, h, radius, cfg.getBlurStrength());
        }
        if (cfg.isBackgroundEnabled()) {
            Identifier image = cfg.isImageBackground() ? ScoreboardBackgroundImage.texture() : null;
            if (image != null) {
                blitRounded(g, image, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, radius - b),
                        ((cfg.getImageOpacity() * 255 / 100) << 24) | 0xFFFFFF);
            } else {
                fillRounded(g, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, radius - b), cfg.getBackgroundColor());
            }
        }
        if (b > 0) {
            drawRoundedBorder(g, x, y, w, h, radius, b, cfg, now, 1f);
            // "Border Softness": SkyHanni's outline blur, approximated with fading 1px rings outside the border.
            int soft = cfg.getBorderSoftness();
            for (int i = 1; i <= soft; i++) {
                float fade = (1f - i / (float) (soft + 1)) * 0.6f;
                drawRoundedBorder(g, x - i, y - i, w + 2 * i, h + 2 * i, radius > 0 ? radius + i : 0, 1, cfg, now, fade);
            }
        }

        int textX = x + b + pad;
        int lineStep = font.lineHeight + cfg.getLineSpacing();
        for (int i = 0; i < lines.size(); i++) {
            ScoreboardLine line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lw = lineWidth(font, line, now);
            int lx = switch (line.align()) {
                case LEFT -> textX;
                case CENTER -> textX + (contentW - lw) / 2;
                case RIGHT -> textX + contentW - lw;
            };
            int ly = y + b + pad + i * lineStep;
            g.text(font, line.text(), lx, ly, 0xFFFFFFFF, cfg.isTextShadow());
            String popup = line.activePopup(now);
            if (popup != null) {
                int alpha = NumberChangeTracker.alpha(line.popupUntilMs(), now);
                g.text(font, popup, lx + font.width(line.text()), ly, (alpha << 24) | 0xFFFFFF, cfg.isTextShadow());
            }
        }
    }

    /** {@link #inset} for {@link ScoreboardBlur}. */
    static int roundedInset(int row, int h, int r) {
        return inset(row, h, r);
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

    /** The background image stretched over a rounded rectangle: texture size = box size, so each row is an exact
     *  slice of the image. */
    private static void blitRounded(GuiGraphicsExtractor g, Identifier texture, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (r <= 0 || h <= 2 * r) {
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, w, h, w, h, color);
            return;
        }
        for (int row = 0; row < r; row++) {
            int in = inset(row, h, r);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x + in, y + row, in, row, w - 2 * in, 1, w - 2 * in, 1, w, h, color);
            int bottom = h - 1 - row;
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x + in, y + bottom, in, bottom, w - 2 * in, 1, w - 2 * in, 1, w, h, color);
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y + r, 0f, r, w, h - 2 * r, w, h - 2 * r, w, h, color);
    }

    /** Border colour for row {@code row} of an {@code h}-tall border: solid, top-to-bottom gradient, or chroma. */
    private static int borderColor(CustomScoreboardConfig cfg, int row, int h, long now) {
        float t = h <= 1 ? 0f : row / (float) (h - 1);
        if (cfg.isChromaBorder()) {
            float speed = cfg.getChromaSpeed() / 5f;
            float hue = (now % 100_000L) / 1000f * 0.1f * speed + (cfg.isBorderGradient() ? t * 0.35f : 0f);
            return (cfg.getBorderColor() >>> 24) << 24 | hsvToRgb(hue - (float) Math.floor(hue), 0.8f, 1f);
        }
        if (!cfg.isBorderGradient()) {
            return cfg.getBorderColor();
        }
        return lerpArgb(cfg.getBorderColor(), cfg.getBorderColorBottom(), t);
    }

    /** Hue/saturation/value in [0,1] to 0xRRGGBB (no AWT on the render thread). */
    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = h * 6f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        switch ((int) hp % 6) {
            case 0 -> { r = c; g = x; }
            case 1 -> { r = x; g = c; }
            case 2 -> { g = c; b = x; }
            case 3 -> { g = x; b = c; }
            case 4 -> { r = x; b = c; }
            default -> { r = c; b = x; }
        }
        float m = v - c;
        return Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }

    private static int lerpArgb(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    static void drawRoundedBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int t,
                                  CustomScoreboardConfig cfg, long now, float alphaMul) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int innerW = w - 2 * t;
        int innerH = h - 2 * t;
        int innerR = Math.max(0, r - t);
        for (int row = 0; row < h; row++) {
            int color = borderColor(cfg, row, h, now);
            if (alphaMul < 1f) {
                color = Math.round((color >>> 24) * alphaMul) << 24 | (color & 0xFFFFFF);
            }
            if ((color >>> 24) == 0) {
                continue;
            }
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
        if ((isActive() || minimalActive()) && !current.isEmpty()) {
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

    /** Re-reads {@code background.png} on the next frame. */
    public static void reloadBackgroundImage() {
        ScoreboardBackgroundImage.reload();
    }

    public static boolean backgroundImageExists() {
        return ScoreboardBackgroundImage.fileExists();
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
        public boolean isRelevantNow() {
            return isActive() || minimalActive();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            // Editor preview only - in game this element is drawn by CustomScoreboardFeature's own HUD layer.
            if (!cfg.isEnabled() || !(client.screen instanceof HudEditorScreen)) {
                return;
            }
            drawBoard(graphics, client.font, x, y, previewLines(), cfg, false);
        }
    }
}
