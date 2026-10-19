package com.killer560.hub.scoreboard;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.util.ChromaWave;
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
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.killer560.hub.scoreboard.ScoreboardData.firstMatches;
import static com.killer560.hub.scoreboard.ScoreboardData.nextAfter;
import com.killer560.hub.compat.McCompat;

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
 * every 5 ticks (SkyHanni rebuilds every 250ms), but a rebuild that reads the same keeps the old list, so the frames
 * re-measure nothing ({@link #publish}). Drawn through its own Fabric HUD layer like {@code InventoryHudFeature};
 * {@link Element#render} is the HUD-editor preview only. Since 2026-10-15 the board is anchored to a screen edge (the
 * right one by default, vertically centered, like the vanilla sidebar) and can never be drawn or dragged off screen -
 * see the position section below. It also dropped the orange box for a plain vanilla-like one (the old look is all
 * still under the editor's Advanced section).
 * <p>
 * "Clickable Lines" (SkyBlock Custom Scoreboard's line actions): while chat is open, hovering a line with actions
 * shows its tooltip and clicking runs its command.
 */
public final class CustomScoreboardFeature {

    public static final String ELEMENT_ID = "custom_scoreboard";

    private static final Logger LOGGER = ModLog.get("killer560smod-customscoreboard");
    private static final long UNKNOWN_LINES_WORLD_GRACE_MS = 3000L;
    private static final long ISLAND_SWITCH_CACHE_MS = 6000L;
    private static final long UNKNOWN_WARNING_COOLDOWN_MS = 10_000L;
    private static final int UNKNOWN_WARNING_SESSION_LIMIT = 20;

    private static List<ScoreboardLine> current = Collections.emptyList();
    /** How many of {@link #current}'s first lines are its title (drawn on the darker title band, as vanilla does). */
    private static int currentTitleRows = 0;
    private static boolean currentIsMinimal = false;
    private static List<String> unknown = Collections.emptyList();
    private static List<Pattern> allPatterns;
    private static int tickCounter = 0;
    private static Object lastLevel = null;
    private static long worldChangedAtMs = 0L;
    private static boolean loggedError = false;
    private static final Set<String> warnedUnknown = new HashSet<>();
    /** Shapes of unknown lines already written to the log this session (bounded; see {@link #shape}). */
    private static final Set<String> loggedUnknown = new HashSet<>();
    private static final int UNKNOWN_LOG_LIMIT = 100;
    private static long lastUnknownWarningMs = 0L;

    /** Where the board was last drawn in game, for hover/click hit-testing. */
    private record Layout(List<ScoreboardLine> lines, float x, int y, float scale, int contentX, int contentY,
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CustomScoreboardFeature.tick", CustomScoreboardFeature::tick));
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
                int[] titleRows = new int[1];
                List<ScoreboardLine> minimal = buildMinimal(client, cfg, titleRows);
                publish(minimal, titleRows[0], true);
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
        currentTitleRows = 0;
        currentIsMinimal = false;
        unknown = Collections.emptyList();
    }

    /**
     * Makes {@code lines} the board - unless it reads exactly as the board already does, in which case the old list
     * object is kept. Every per-frame cache (the measured width, the anchored position, the hover layout) is keyed on the
     * list's identity, so a rebuild that changes nothing (most of them: the board is rebuilt every 5 ticks, the data
     * behind it changes far less often) costs the frames nothing (2026-10-15, perf report: 30-37 us a frame).
     */
    private static void publish(List<ScoreboardLine> lines, int titleRows, boolean minimal) {
        currentIsMinimal = minimal;
        if (titleRows == currentTitleRows && lines.equals(current)) {
            return;
        }
        current = Collections.unmodifiableList(lines);
        currentTitleRows = titleRows;
    }

    /** How many leading lines of {@code lines} are the title's own line objects (after edge trimming). */
    private static int leadingTitleRows(List<ScoreboardLine> lines, List<ScoreboardLine> title) {
        if (title == null || title.isEmpty()) {
            return 0;
        }
        int n = 0;
        while (n < lines.size() && n < title.size() && lines.get(n) == title.get(n)) {
            n++;
        }
        return n;
    }

    private static void rebuild(CustomScoreboardConfig cfg) {
        // THE DUNGEON SIM gets its own board. killer560 (2026-10-04): in the sim the HUD should say this is his
        // personal testing sim with his Discord link, "nothing actually skyblock related". The sim's sidebar
        // (SimSidebar) already carries that block plus the sim lines worth keeping - secrets and room since
        // 2026-10-04, when time, keys, the floor line and the visible "k560l4" holder names went - so it is shown
        // as it is, under a plain title. Gated on SimState only: on Hypixel this branch never runs and the board
        // below is built from the real sidebar exactly as before. The configured entries (purse, bits,
        // location, the hypixel footer ...) are Skyblock data that a singleplayer world can only show as filler.
        if (com.killer560.hub.roomsim.SimState.isActive()) {
            unknown = Collections.emptyList();
            List<ScoreboardLine> sim = new ArrayList<>();
            List<ScoreboardLine> simTitle = List.of(new ScoreboardLine("§6§lDungeon Sim", cfg.getTitleAlignment()));
            sim.addAll(simTitle);
            sim.addAll(ScoreboardLine.of(ScoreboardData.sidebar()));
            List<ScoreboardLine> trimmed = trimBlankEdges(sim);
            publish(trimmed, leadingTitleRows(trimmed, simTitle), false);
            return;
        }
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
        List<ScoreboardLine> title = null;
        if (!cfg.isUseCustomLines()) {
            if (!ScoreboardData.objectiveTitle().isEmpty()) {
                title = List.of(new ScoreboardLine(ScoreboardData.objectiveTitle(), cfg.getTitleAlignment()));
                lines.addAll(title);
            }
            lines.addAll(ScoreboardLine.of(ScoreboardData.sidebar()));
        } else {
            for (ScoreboardEntry entry : cfg.entries().active()) {
                if (!entry.visible(cfg)) {
                    continue;
                }
                List<ScoreboardLine> elementLines = entry.lines(cfg);
                if (elementLines.isEmpty()) {
                    continue;
                }
                if (cfg.isHideConsecutiveEmptyLines() && elementLines.get(0).isBlank()
                        && !lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                    continue;
                }
                if (entry == ScoreboardEntry.TITLE && lines.isEmpty()) {
                    title = elementLines;
                }
                lines.addAll(elementLines);
            }
        }
        List<ScoreboardLine> out = cfg.isHideEmptyLinesAtTopAndBottom() ? trimBlankEdges(lines) : lines;
        publish(out, leadingTitleRows(out, title), false);
    }

    /** Off-Skyblock "Minimal Board": title, today's date and time, online players, footer. {@code titleRows[0]} gets
     *  how many of its first lines are the title. */
    private static List<ScoreboardLine> buildMinimal(Minecraft client, CustomScoreboardConfig cfg, int[] titleRows) {
        List<ScoreboardLine> out = new ArrayList<>();
        List<ScoreboardLine> title;
        if (cfg.isUseCustomTitle() && cfg.isUseCustomTitleOutsideSkyblock()) {
            title = ScoreboardEntry.TITLE.lines(cfg);
        } else {
            // SkyHanni ScoreboardElementTitle: outside SkyBlock the server's own title unless "Custom Title Outside SkyBlock".
            net.minecraft.world.scores.Objective objective = ScoreboardData.sidebarObjective(client);
            String text = objective == null ? "" : ScoreboardData.formatted(objective.getDisplayName(), false);
            title = text.isEmpty() ? List.of() : List.of(new ScoreboardLine(text, cfg.getTitleAlignment()));
        }
        out.addAll(title);
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
        List<ScoreboardLine> trimmed = trimBlankEdges(out);
        titleRows[0] = leadingTitleRows(trimmed, title);
        return trimmed;
    }

    /**
     * SkyHanni's "Unknown Lines warning", made a debug notice (2026-10-07): every unknown line is written to the log
     * ONCE per shape (its digits folded, so a line carrying a changing number is one line, not one per value), and the
     * chat note - also once per shape, rate limited, Hypixel only - comes only with Unknown Line Warning on, which is
     * off by default. Normal play never prints it.
     */
    private static void warnUnknownLines(CustomScoreboardConfig cfg) {
        if (unknown.isEmpty() || !ScoreboardData.islandKnown()) {
            return;
        }
        for (String line : unknown) {
            if (loggedUnknown.size() < UNKNOWN_LOG_LIMIT && loggedUnknown.add(shape(line))) {
                LOGGER.info("[CustomScoreboard] Unknown scoreboard line: \"{}\"", line);
            }
        }
        if (!cfg.isUnknownLinesWarning()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (warnedUnknown.size() >= UNKNOWN_WARNING_SESSION_LIMIT || now - lastUnknownWarningMs < UNKNOWN_WARNING_COOLDOWN_MS) {
            return;
        }
        for (String line : unknown) {
            if (warnedUnknown.add(shape(line))) {
                lastUnknownWarningMs = now;
                ModChat.send("Custom Scoreboard", ModChat.text("Unknown scoreboard line: "),
                        Component.literal(line));
                return;
            }
        }
    }

    /** An unknown line's identity for "seen it already": its digits folded to one '#', so "Wave 3: 0:12" and
     *  "Wave 3: 0:13" are the same line. */
    static String shape(String line) {
        return line == null ? "" : line.replaceAll("\\d+", "#");
    }

    /** The sidebar line as the patterns are matched against it: no invisible format characters (zero-width joiners and
     *  the like, which a server can put in to keep lines unique) and no surrounding blanks. */
    static String normalise(String line) {
        StringBuilder b = null;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (Character.getType(ch) == Character.FORMAT) {
                if (b == null) {
                    b = new StringBuilder(line.length()).append(line, 0, i);
                }
            } else if (b != null) {
                b.append(ch);
            }
        }
        return (b == null ? line : b.toString()).strip();
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
            String norm = normalise(line);
            boolean known = false;
            for (Pattern pattern : allPatterns()) {
                if (pattern.matcher(line).matches() || pattern.matcher(norm).matches()) {
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
        if (!(isActive() || minimalActive()) || client.player == null || McCompat.hudHidden(client)
                || McCompat.screen(client) instanceof HudEditorScreen) {
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
        boolean chatOpen = McCompat.screen(client) instanceof ChatScreen;
        if (cfg.isHideWhenChat() && chatOpen) {
            return;
        }
        Font font = client.font;
        int[] pos;
        float scale;
        try {
            // The same anchored, clamped spot the HUD editor shows (Element#layoutPosition), memoised: no allocation
            // while nothing about the board, its scale or the window changed.
            pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(Element.INSTANCE);
            scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(Element.INSTANCE);
        } catch (RuntimeException e) {
            return;
        }
        // The anchored wall sits on a whole GUI pixel whatever the board's width (anchorShift).
        float drawX = pos[0] + anchorShift(font, lines, cfg, scale);
        graphics.pose().pushMatrix();
        try {
            // The real board; Element#render is only the HUD editor's preview and does not stamp.
            HudSeen.markDrawn(ELEMENT_ID);
            graphics.pose().translate(drawX, pos[1]);
            graphics.pose().scale(scale, scale);
            com.killer560.hub.hud.HudTextCache.begin(); // the board's lines keep their visual order between frames
            try {
                drawBoard(graphics, font, 0, 0, lines, currentTitleRows, cfg, true);
                drawnFrames++;
            } finally {
                com.killer560.hub.hud.HudTextCache.end();
            }
        } catch (RuntimeException e) {
            // A broken frame must never take down the rest of the HUD.
        } finally {
            graphics.pose().popMatrix();
        }
        lastDrawnX = pos[0];
        lastDrawnY = pos[1];
        lastDrawnScale = scale;
        if (!chatOpen) {
            return; // hover and click only exist while chat is open; nothing to lay out for them
        }
        int inset = borderSize(cfg) + cfg.getPadding();
        // The text block, where drawBoard puts it: against the anchored side of the box (blockOffset).
        Layout layout = new Layout(lines, drawX, pos[1], scale, inset + blockOffset(font, lines, cfg), inset,
                contentWidth(font, lines), font.lineHeight + cfg.getLineSpacing(), font.lineHeight);
        lastLayout = layout;
        if (cfg.isLineActions()) {
            try {
                drawHoverTooltip(client, graphics, layout);
            } catch (RuntimeException ignored) {
                // tooltip is cosmetic
            }
        }
    }

    /** Where and at what scale the real board was last drawn in game, and how many frames drew it (testkit). */
    private static int lastDrawnX = Integer.MIN_VALUE;
    private static int lastDrawnY = Integer.MIN_VALUE;
    private static float lastDrawnScale = 0f;
    private static long drawnFrames = 0L;

    /** {x, y, w, h} of the board as last drawn in game (scaled GUI pixels), or null if it never drew. */
    public static int[] lastDrawnBox() {
        if (lastDrawnX == Integer.MIN_VALUE || current.isEmpty()) {
            return null;
        }
        Font font = Minecraft.getInstance().font;
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        return new int[]{lastDrawnX, lastDrawnY, Math.round(boxWidth(font, current, cfg) * lastDrawnScale),
                Math.round(boxHeight(font, current, cfg) * lastDrawnScale)};
    }

    public static long drawnFrames() {
        return drawnFrames;
    }

    /** The board's lines as text, top to bottom (what the in-game board draws). */
    public static List<String> currentTexts() {
        List<String> out = new ArrayList<>(current.size());
        for (ScoreboardLine l : current) {
            out.add(l.text());
        }
        return out;
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

    // ---- position (2026-10-15) ----
    //
    // killer560: "by default aligned on the right side of the screen where the normal scoreboard should be and not able
    // to go off the right side of the screen." The board is stored as an ANCHOR (CustomScoreboardConfig.anchorSide,
    // edgeGap, centerOffsetY), not an absolute x: x is worked out from the edge it is anchored to every time the screen,
    // the scale or the board's own width changes, so a longer line grows the board AWAY from its edge, and a GUI scale or
    // window change keeps it against the edge. Then it is clamped wholly on screen. Before this, SkyHanni's snap wrote an
    // absolute x into HudConfig and the first drag in Edit HUD switched the snap off for good, leaving that x behind:
    // a wider board, or a smaller window, then hung off the right edge.

    private static int posScreenW = -1;
    private static int posScreenH = -1;
    private static int posBoxW = -1;
    private static int posBoxH = -1;
    private static CustomScoreboardConfig.Side posSide;
    private static int posGap;
    private static int posOffset;
    private static int[] posResult;

    /** Top-left of the board's drawn box, {@code w x h} scaled pixels on a {@code sw x sh} screen: against its anchor
     *  edge, then clamped so all of it is on screen (a box larger than the screen pins to the top left). */
    static int[] anchoredPosition(CustomScoreboardConfig cfg, int w, int h, int sw, int sh) {
        int x = cfg.getAnchorSide() == CustomScoreboardConfig.Side.RIGHT ? sw - cfg.getEdgeGap() - w : cfg.getEdgeGap();
        int y = (sh - h) / 2 + cfg.getCenterOffsetY();
        return new int[]{Math.max(0, Math.min(x, sw - w)), Math.max(0, Math.min(y, sh - h))};
    }

    /** Stores a box dropped at {@code x, y} as an anchor: to whichever side its middle is on, clamped on screen first. */
    static void storeAnchor(CustomScoreboardConfig cfg, int x, int y, int w, int h, int sw, int sh) {
        x = Math.max(0, Math.min(x, sw - w));
        y = Math.max(0, Math.min(y, sh - h));
        boolean right = x + w / 2.0 >= sw / 2.0;
        cfg.setAnchor(right ? CustomScoreboardConfig.Side.RIGHT : CustomScoreboardConfig.Side.LEFT,
                right ? sw - x - w : x, y - (sh - h) / 2);
        cfg.save();
    }

    /**
     * An old save whose board was not at the old default (Snap X Right, Snap Y Center): where the old code drew it -
     * the snap's spot, or, with snapping off, the absolute x/y Edit HUD left in HudConfig - becomes the anchor, pulled
     * on screen if it was hanging off. Done once, on the first frame that knows the window and the board's size.
     */
    private static void convertOldPosition(CustomScoreboardConfig cfg, int w, int h, int sw, int sh) {
        int margin = cfg.legacyMargin();
        int[] own;
        try {
            own = com.killer560.hub.hud.HudElementRegistry.ownPosition(Element.INSTANCE);
        } catch (RuntimeException e) {
            own = new int[]{sw - w, (sh - h) / 2};
        }
        int x = switch (cfg.legacyHorizontalSnap()) {
            case NONE -> own[0];
            case LEFT -> margin;
            case CENTER -> (sw - w) / 2;
            case RIGHT -> sw - w - margin;
        };
        int y = switch (cfg.legacyVerticalSnap()) {
            case NONE -> own[1];
            case TOP -> margin;
            case CENTER -> (sh - h) / 2;
            case BOTTOM -> sh - h - margin;
        };
        LOGGER.info("[CustomScoreboard] Old position ({}, {}) on {}x{} converted to an edge anchor", x, y, sw, sh);
        storeAnchor(cfg, x, y, w, h, sw, sh);
    }

    /** {@link Element#layoutPosition()}: memoised on the screen size, the drawn box size and the anchor, so a frame where
     *  none of them changed returns the same array without measuring or allocating. */
    private static int[] resolveAnchored() {
        Minecraft client = Minecraft.getInstance();
        com.mojang.blaze3d.platform.Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return null;
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        int sw = window.getGuiScaledWidth();
        int sh = window.getGuiScaledHeight();
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(Element.INSTANCE);
        int w = Math.max(1, Math.round(Element.INSTANCE.width() * scale));
        int h = Math.max(1, Math.round(Element.INSTANCE.height() * scale));
        if (cfg.isPositionPending()) {
            convertOldPosition(cfg, w, h, sw, sh);
        }
        if (posResult != null && sw == posScreenW && sh == posScreenH && w == posBoxW && h == posBoxH
                && cfg.getAnchorSide() == posSide && cfg.getEdgeGap() == posGap && cfg.getCenterOffsetY() == posOffset) {
            return posResult;
        }
        posScreenW = sw;
        posScreenH = sh;
        posBoxW = w;
        posBoxH = h;
        posSide = cfg.getAnchorSide();
        posGap = cfg.getEdgeGap();
        posOffset = cfg.getCenterOffsetY();
        posResult = anchoredPosition(cfg, w, h, sw, sh);
        return posResult;
    }

    private static int borderSize(CustomScoreboardConfig cfg) {
        return cfg.isBorderEnabled() ? cfg.getBorderThickness() : 0;
    }

    private static int lineWidth(Font font, ScoreboardLine line, long now) {
        int w = font.width(line.text());
        String popup = line.activePopup(now);
        return popup == null ? w : w + font.width(popup);
    }

    /** {@link #contentWidth} of the last list measured: a frame asks it several times (position, box, inner width,
     *  hover layout), each a font measure of every line - most of the board's own text cost (FPS sweep, 2026-10-07).
     *  The same list object and font is the same answer, for as long as no line carries a number-change popup (those
     *  expire by the clock, so a board with one is re-measured each millisecond). {@link #publish} keeps the list
     *  object when a rebuild changed nothing, so this measures once per real change (2026-10-15). */
    private static List<ScoreboardLine> widthLines;
    private static Font widthFont;
    private static long widthAtMs;
    private static boolean widthTimed;
    private static int widthValue;

    private static int contentWidth(Font font, List<ScoreboardLine> lines) {
        long now = System.currentTimeMillis();
        if (lines == widthLines && font == widthFont && (!widthTimed || now == widthAtMs)) {
            return widthValue;
        }
        int w = 0;
        boolean timed = false;
        for (ScoreboardLine line : lines) {
            w = Math.max(w, lineWidth(font, line, now));
            timed |= line.popup() != null;
        }
        if (Minecraft.getInstance().isSameThread()) {
            widthLines = lines;
            widthFont = font;
            widthAtMs = now;
            widthTimed = timed;
            widthValue = w;
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

    /**
     * How far right, in GUI pixels, to draw a right-anchored board from its element box's whole-pixel x, so that its
     * right wall lands exactly on the box's right edge ({@code x + round(width * scale)}, which the anchor keeps at
     * {@code screen - gap}). Without it the board was drawn from the rounded left x and its right wall sat at
     * {@code x + width * scale}, which at any non-integer scale (Auto Scale and HUD Scale make nearly every real scale
     * one) wandered up to a pixel as the width changed - 663 / 664 at 0.72, testkit 1668 (2026-10-15). 0 when
     * left-anchored: the left wall is the whole-pixel x itself.
     */
    static float anchorShift(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg, float scale) {
        if (cfg.getAnchorSide() != CustomScoreboardConfig.Side.RIGHT) {
            return 0f;
        }
        int boxW = boxWidth(font, lines, cfg);
        return Math.round(Math.max(20, boxW) * scale) - boxW * scale; // Element#width is max(20, boxWidth)
    }

    /**
     * Where the text block starts inside the inner area: 0 on a left-anchored board, and on a right-anchored one the
     * slack between a Min Width box and the widest line, so the block hugs the right wall and the furthest right point
     * of any line never moves (killer560, 2026-10-15). Before, a Min Width wider than the text kept the block on the
     * left, and on the right anchor the text's right end moved with every width change (581 -> 615, testkit 1668).
     */
    static int blockOffset(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        if (cfg.getAnchorSide() != CustomScoreboardConfig.Side.RIGHT) {
            return 0;
        }
        return Math.max(0, innerWidth(font, lines, cfg) - contentWidth(font, lines));
    }

    /** Distance from the board's top-left corner to its first line's text: border plus padding (what
     *  {@link #drawBoard} adds). The visual editor maps its drawn rows back to entries with it. */
    static int textInset(CustomScoreboardConfig cfg) {
        return borderSize(cfg) + cfg.getPadding();
    }

    static int boxWidth(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return innerWidth(font, lines, cfg) + 2 * (cfg.getPadding() + borderSize(cfg));
    }

    static int boxHeight(Font font, List<ScoreboardLine> lines, CustomScoreboardConfig cfg) {
        return Math.max(cfg.getMinHeight(), contentHeight(font, lines, cfg) + 2 * (cfg.getPadding() + borderSize(cfg)));
    }

    /**
     * @param titleRows how many first lines are the title: drawn on a slightly darker band, the vanilla sidebar's one
     *                  piece of structure (2026-10-15). 0 for none.
     * @param inGame    true for the real HUD (background blur only exists there, never in the HUD editor preview)
     */
    static void drawBoard(GuiGraphicsExtractor g, Font font, int x, int y, List<ScoreboardLine> lines, int titleRows,
                          CustomScoreboardConfig cfg, boolean inGame) {
        int b = borderSize(cfg);
        int pad = cfg.getPadding();
        int w = boxWidth(font, lines, cfg);
        int h = boxHeight(font, lines, cfg);
        int radius = cfg.isRoundedCorners() ? Math.min(cfg.getCornerRadius(), Math.min(w, h) / 2) : 0;
        long now = System.currentTimeMillis();

        if (inGame && cfg.isBackgroundBlur()) {
            ScoreboardBlur.submit(g, x, y, w, h, radius, cfg.getBlurStrength());
        }
        // Menu Theme (killer560, 2026-10-18): Default is the mod menu's theme, so the background, title band and border take
        // its colours; a theme he picked overrides that. The Box's Background Color keeps only its alpha (how solid the box
        // is), and the image background is his own Box setting, so it still shows on Default (never over a picked theme).
        com.killer560.hub.gui.OverlayPalette menu = menuPalette();
        if (cfg.isBackgroundEnabled()) {
            Identifier image = cfg.isImageBackground() && !themed() ? ScoreboardBackgroundImage.texture() : null;
            if (image != null) {
                blitRounded(g, image, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, radius - b),
                        ((cfg.getImageOpacity() * 255 / 100) << 24) | 0xFFFFFF);
            } else {
                int background = com.killer560.hub.gui.OverlayPalette.alpha(menu.panel(), cfg.getBackgroundColor() >>> 24);
                fillRounded(g, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, radius - b), background);
                if (titleRows > 0 && titleRows < lines.size() && radius == 0) {
                    // From the box's top edge to halfway into the gap under the title's last row.
                    int bandBottom = y + b + pad + titleRows * (font.lineHeight + cfg.getLineSpacing())
                            - (cfg.getLineSpacing() + 1) / 2;
                    g.fill(x + b, y + b, x + w - b, Math.min(y + h - b, bandBottom),
                            com.killer560.hub.gui.OverlayPalette.alpha(menu.panelDeep(), 0x60));
                }
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

        // Lines align inside the text block (as wide as the widest line), and the block sits against the anchored
        // side: so whatever Text Align is, the outermost point of the text on that side is the block's own edge.
        int blockW = contentWidth(font, lines);
        int textX = x + b + pad + blockOffset(font, lines, cfg);
        int lineStep = font.lineHeight + cfg.getLineSpacing();
        for (int i = 0; i < lines.size(); i++) {
            ScoreboardLine line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            // Only a centered or right-aligned line needs its own width (a font measure per line per frame otherwise).
            int lx = switch (line.align()) {
                case LEFT -> textX;
                case CENTER -> textX + (blockW - lineWidth(font, line, now)) / 2;
                case RIGHT -> textX + blockW - lineWidth(font, line, now);
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

    /** The board's Menu Theme as a palette: the theme he picked, or the mod menu's own theme on Default. */
    static com.killer560.hub.gui.OverlayPalette menuPalette() {
        com.killer560.hub.gui.OverlayPalette picked = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.CUSTOM_SCOREBOARD);
        return picked != null ? picked : com.killer560.hub.gui.OverlayThemes.menu();
    }

    /** Whether the board's Menu Theme is a theme he picked (not Default). */
    static boolean themed() {
        return com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.CUSTOM_SCOREBOARD) != null;
    }

    /** Border colour for row {@code row} of an {@code h}-tall border: solid or top-to-bottom gradient. Chroma is
     *  {@link #drawChromaBorder}, which colours by position along the row too. Border Style Default is the menu's accent
     *  (alpha from the Border Color, so a faded border stays faded); Custom uses the Border Color and Bottom Color. */
    private static int borderColor(CustomScoreboardConfig cfg, int row, int h, long now) {
        if (cfg.getBorderStyle() == CustomScoreboardConfig.BorderStyle.MENU) {
            return menuPalette().accent() | (cfg.getBorderColor() & 0xFF000000);
        }
        float t = h <= 1 ? 0f : row / (float) (h - 1);
        if (!cfg.isBorderGradient()) {
            return cfg.getBorderColor();
        }
        return lerpArgb(cfg.getBorderColor(), cfg.getBorderColorBottom(), t);
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
        if (cfg.isChromaBorder()) {
            drawChromaBorder(g, x, y, w, h, r, t, cfg, alphaMul);
            return;
        }
        int innerW = w - 2 * t;
        int innerH = h - 2 * t;
        int innerR = Math.max(0, r - t);
        // Each row is one full-width span (top/bottom band) or a left and a right span (the sides), and consecutive
        // rows with the same spans and colour are merged into one taller fill: the same pixels, but the straight
        // sides of a solid border are now two fills instead of two per pixel row. Every fill is a GUI element with
        // its own matrix copy and intersection test, and this border was 6% of the render thread in a dungeon with
        // the HUD on (95-fps-bench JFR, 2026-10-05) - about 300 fills a frame for a default board.
        int runStart = -1;
        int runA = 0, runB = 0, runColor = 0;
        for (int row = 0; row < h; row++) {
            int color = borderColor(cfg, row, h, now);
            if (alphaMul < 1f) {
                color = Math.round((color >>> 24) * alphaMul) << 24 | (color & 0xFFFFFF);
            }
            int a;
            int b;
            if ((color >>> 24) == 0) {
                a = -1;   // nothing drawn on this row
                b = -1;
            } else {
                int outer = inset(row, h, r);
                int innerRow = row - t;
                if (innerRow < 0 || innerRow >= innerH || innerW <= 0) {
                    a = outer;
                    b = -1;   // one span, outer .. w - outer
                } else {
                    a = outer;
                    b = Math.max(outer, t + inset(innerRow, innerH, innerR));   // two spans, a..b and w-b..w-a
                }
            }
            if (runStart >= 0 && (a != runA || b != runB || color != runColor)) {
                fillBorderRun(g, x, y, w, runStart, row, runA, runB, runColor);
                runStart = -1;
            }
            if (runStart < 0 && a >= 0) {
                runStart = row;
                runA = a;
                runB = b;
                runColor = color;
            }
        }
        if (runStart >= 0) {
            fillBorderRun(g, x, y, w, runStart, h, runA, runB, runColor);
        }
    }

    /** Width (GUI units) of one solid piece of a chroma border's top/bottom band. */
    private static final int CHROMA_PIECE = 3;
    /** Height (rows) of one top-to-bottom gradient piece of a chroma border's side. */
    private static final int CHROMA_SIDE_ROWS = 4;

    /**
     * The chroma border (killer560, 2026-10-13): the shared waterfall ({@link ChromaWave}), sampled at each piece's
     * real GUI position, so the colour bands run diagonally across the board top-left to bottom-right instead of the
     * whole border changing colour at once. The top/bottom bands are {@link #CHROMA_PIECE}-wide solid pieces, the sides
     * {@link #CHROMA_SIDE_ROWS}-row vertical gradients with the waterfall's colour at each piece's top and bottom edge,
     * and everything is one {@link GuiRects} element: about 150 pieces for a default board, where the old per-row
     * chroma colour broke every run into two fills per row (~300 elements).
     */
    private static void drawChromaBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int t,
                                         CustomScoreboardConfig cfg, float alphaMul) {
        int alpha = Math.round((cfg.getBorderColor() >>> 24) * alphaMul);
        if (alpha <= 0) {
            return;
        }
        int a24 = Math.min(255, alpha) << 24;
        float speed = cfg.getChromaSpeed() / 5f;
        long now = ChromaWave.now();
        int innerW = w - 2 * t;
        int innerH = h - 2 * t;
        int innerR = Math.max(0, r - t);
        GuiRects rects = GuiRects.begin(g);
        int row = 0;
        while (row < h) {
            int outer = inset(row, h, r);
            int b = chromaInnerEdge(row, t, innerW, innerH, innerR, outer);
            int end = row + 1;
            if (b < 0) {
                while (end < h && inset(end, h, r) == outer && chromaInnerEdge(end, t, innerW, innerH, innerR, outer) < 0) {
                    end++;
                }
                float cy = y + (row + end) / 2f;
                for (int px = x + outer; px < x + w - outer; px += CHROMA_PIECE) {
                    int px1 = Math.min(px + CHROMA_PIECE, x + w - outer);
                    rects.fill(px, y + row, px1, y + end,
                            a24 | ChromaWave.rainbow((px + px1) / 2f, cy, now, speed, 1f, 0.8f, 1f));
                }
            } else {
                while (end < h && end - row < CHROMA_SIDE_ROWS && inset(end, h, r) == outer
                        && chromaInnerEdge(end, t, innerW, innerH, innerR, outer) == b) {
                    end++;
                }
                float lx = x + (outer + b) / 2f;
                float rx = x + w - (outer + b) / 2f;
                rects.gradient(x + outer, y + row, x + b, y + end,
                        a24 | ChromaWave.rainbow(lx, y + row, now, speed, 1f, 0.8f, 1f),
                        a24 | ChromaWave.rainbow(lx, y + end, now, speed, 1f, 0.8f, 1f));
                rects.gradient(x + w - b, y + row, x + w - outer, y + end,
                        a24 | ChromaWave.rainbow(rx, y + row, now, speed, 1f, 0.8f, 1f),
                        a24 | ChromaWave.rainbow(rx, y + end, now, speed, 1f, 0.8f, 1f));
            }
            row = end;
        }
        rects.submit();
    }

    /** The inner edge {@code b} of a two-span border row (sides {@code outer..b} and {@code w-b..w-outer}), or -1 for a
     *  one-span top/bottom band row - the same split {@link #drawRoundedBorder} makes. */
    private static int chromaInnerEdge(int row, int t, int innerW, int innerH, int innerR, int outer) {
        int innerRow = row - t;
        if (innerRow < 0 || innerRow >= innerH || innerW <= 0) {
            return -1;
        }
        return Math.max(outer, t + inset(innerRow, innerH, innerR));
    }

    /** Rows {@code fromRow..toRow-1} of a border run from {@link #drawRoundedBorder}. */
    private static void fillBorderRun(GuiGraphicsExtractor g, int x, int y, int w, int fromRow, int toRow, int a, int b,
                                      int color) {
        if (b < 0) {
            g.fill(x + a, y + fromRow, x + w - a, y + toRow, color);
        } else {
            g.fill(x + a, y + fromRow, x + b, y + toRow, color);
            g.fill(x + w - b, y + fromRow, x + w - a, y + toRow, color);
        }
    }

    /** Board content for a preview with no live board of its own (HUD editor, {@link ScoreboardEditorScreen}):
     *  the real current board when one exists, otherwise each line on the board's own sample text, in board order. Package-private
     *  so both editors share exactly one "what would this look like" implementation. */
    static List<ScoreboardLine> previewLines() {
        if ((isActive() || minimalActive()) && !current.isEmpty()) {
            previewTitleRows = currentTitleRows;
            return current;
        }
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        List<ScoreboardLine> out = new ArrayList<>();
        List<ScoreboardLine> title = null;
        for (ScoreboardEntry entry : cfg.entries().active()) {
            if (entry == ScoreboardEntry.TITLE || entry == ScoreboardEntry.FOOTER) {
                List<ScoreboardLine> el = entry.lines(cfg);
                if (entry == ScoreboardEntry.TITLE && out.isEmpty()) {
                    title = el;
                }
                out.addAll(el);
            } else if (entry.isSeparator()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                    out.add(ScoreboardLine.of(""));
                }
            } else {
                out.addAll(ScoreboardLine.of(entry.sample()));
            }
        }
        List<ScoreboardLine> trimmed = trimBlankEdges(out);
        previewTitleRows = leadingTitleRows(trimmed, title);
        return trimmed;
    }

    /** {@link #previewLines}'s title rows, as of its last call. */
    private static int previewTitleRows;

    /** Re-reads {@code background.png} on the next frame. */
    public static void reloadBackgroundImage() {
        ScoreboardBackgroundImage.reload();
    }

    public static boolean backgroundImageExists() {
        return ScoreboardBackgroundImage.fileExists();
    }

    /** HUD-editor entry: movable and scalable. Its scale lives in {@code HudConfig}; its position is the edge anchor in
     *  {@link CustomScoreboardConfig} (2026-10-15), served by {@link #layoutPosition()} and kept wholly on screen. */
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

        /** Only read when an old save is converted ({@link #convertOldPosition}); the board's own default is the right
         *  edge, vertically centered ({@link CustomScoreboardConfig#resetAnchor}). */
        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 80;
        }

        @Override
        public int[] layoutPosition() {
            return resolveAnchored();
        }

        @Override
        public boolean keepOnScreen() {
            return true;
        }

        @Override
        public boolean storeEditorPosition(int x, int y, int w, int h, int screenW, int screenH) {
            storeAnchor(CustomScoreboardConfig.getInstance(), x, y, w, h, screenW, screenH);
            return true;
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
        public boolean isEnabledInSettings() {
            // Setting only - isActive()/minimalActive() both fold in a SkyblockGate check, which is a
            // location test and so belongs to the draw stamp in drawInGame.
            return CustomScoreboardConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            // Editor preview only - in game this element is drawn by CustomScoreboardFeature's own HUD layer.
            if (!cfg.isEnabled() || !(McCompat.screen(client) instanceof HudEditorScreen)) {
                return;
            }
            List<ScoreboardLine> lines = previewLines();
            // The editor draws this at its whole-pixel box position and the element's scale, like the in-game layer:
            // the same sub-pixel shift (in this element's own units) keeps the anchored wall where the game has it.
            float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(this);
            float shift = scale > 0f ? anchorShift(client.font, lines, cfg, scale) / scale : 0f;
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(shift, 0f);
                drawBoard(graphics, client.font, x, y, lines, previewTitleRows, cfg, false);
            } finally {
                graphics.pose().popMatrix();
            }
        }
    }
}
