package com.killer560.hub.melody;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.bridge.BridgeTables;
import com.killer560.hub.bridge.MelodyIntel;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.terminals.TerminalType;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Legit, read-only Floor 7 Melody terminal progress tracker - killer560 (2026-09-21): "I really want
 * everything to tie into my mod", building on the new Cross-Mod Bridge's Odin adapter, which was left
 * receive-only because nothing in this mod produced our own Melody state (see {@code OdinAdapter}'s class
 * doc before this feature existed). This is the producer: it detects the real Melody terminal by its own
 * container title (same regex {@link TerminalType#MELODY} matches, just read here instead of solved) and
 * scans its current contents every tick - see {@link MelodySlotReader} for exactly what and why, including
 * why that scan is a duplicate of (not a call into) the cheat-only Auto Melody click loop.
 * <p>
 * <b>Never clicks anything</b> - every field here comes from reading {@link AbstractContainerMenu#getItems()},
 * nothing is sent to the server. Works in both builds.
 * <p>
 * Our own progress, once read, goes three places, none of them re-deriving it a second time:
 * <ul>
 * <li>{@link MelodyIntel} (bridge package), under our own real name - the same store Odin-sourced teammate
 * progress already lands in, so the Team Melody HUD below can read one place for every source.</li>
 * <li>{@code OdinAdapter#produceOutbound} polls {@link #selfSnapshot()} and sends it to Odin's own socket, in
 * Odin's own wire format, only while that bridge is on, connected, and we are in the Melody window - see its
 * own class doc for the gating.</li>
 * <li>{@code PartyDataFeature#selfFactSnapshot()} polls the same snapshot and queues it as a new
 * {@code dg.v1.melody} fact over our own relay, gated by {@link MelodyHudConfig#isShareProgress()} on top of
 * the relay's existing share/rate/dedupe machinery - see that class's own doc.</li>
 * </ul>
 * <b>Team Melody HUD</b> ({@link #HUD}) shows every teammate {@link MelodyIntel} currently has progress for
 * (Odin users, our own relay's users, or - for a teammate running this same build - both), live, only during
 * F7/M7 Phase 3 ({@link Floor7Tracker#inPhase}), Amber-themed like the rest of the mod. The row cache is
 * rebuilt once per client tick (not per rendered frame - HUD elements can render hundreds of times a second)
 * so {@link TeamMelodyHudElement#render} never allocates.
 */
public final class MelodyTrackerFeature {

    private static final Pattern MELODY_TITLE = TerminalType.MELODY.titlePattern();
    private static final int PLAYER_INVENTORY_SIZE = 36;
    private static final int MAX_ROWS = 8;

    public static final HudElement HUD = TeamMelodyHudElement.INSTANCE;

    // ---- our own live read (client tick only) ----
    private static boolean open;
    private static int lastLitRow = -1;
    private static int lastTarget = -1;
    private static int lastCurrent = -1;

    // ---- HUD row cache, rebuilt once per tick, read every frame with zero allocation ----
    private static final Row[] ROWS = new Row[MAX_ROWS];
    private static int rowCount;

    static {
        for (int i = 0; i < ROWS.length; i++) {
            ROWS[i] = new Row();
        }
    }

    private MelodyTrackerFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MelodyTrackerFeature.tick", MelodyTrackerFeature::tick));
        com.killer560.hub.util.ChatObserver.subscribe(message -> onChat(com.killer560.hub.util.ChatObserver.strip(message)));
        HudElementRegistry.register(HUD);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "team_melody_hud"),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
    }

    private static void tick(Minecraft client) {
        tickSelf(client);
        tickLifetime();
        rebuildRows();
    }

    // ------------------------------------------------------------------ lifetime (2026-11-01)

    /** "&lt;name&gt; activated a terminal! (n/m)" - a real username, anchored, so no chat prefix can forge it. */
    private static final Pattern TERMINAL_DONE =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) activated a terminal! \\(\\d/\\d\\)(?:\\s.*)?$");
    /** A party line, sender anchored (the same shape I4 Leap reads Melody callouts with). */
    private static final Pattern PARTY_LINE = Pattern.compile("^Party > (?:\\[[^\\]]{1,20}\\] )?([A-Za-z0-9_]{1,16}): (.{1,120})$");
    /** A Melody progress callout: "Melody 1/3 - 33%", "Melody 67%", "mel 2/4". Bounded digits. */
    private static final Pattern PROGRESS = Pattern.compile("(?i)\\bmel(?:ody)?\\b\\D{0,6}(?:(\\d)/(\\d)|(\\d{1,3})%)");

    /** When our own Melody was last finished (0 = not this section). */
    private static long selfDoneAtMs;
    /** When our own Melody screen last closed, for the completion line that follows it. */
    private static long selfClosedAtMs;
    private static Floor7Tracker.Stage lastStage;
    private static boolean lastInP3;

    /** @return when our own Melody was finished, 0 if not recently; polled by {@code PartyDataFeature} to share it. */
    public static long selfDoneAtMs() {
        return selfDoneAtMs;
    }

    /** Section changes and staleness (killer560, 2026-11-01: "if a section ends then it removes it or at max it is there
     *  for 15s without an update"). */
    private static void tickLifetime() {
        boolean inP3 = Floor7Tracker.inPhase(Floor7Tracker.Phase.P3);
        Floor7Tracker.Stage stage = Floor7Tracker.getStage();
        if (inP3 != lastInP3 || (inP3 && stage != lastStage)) {
            MelodyIntel.clear();
            selfDoneAtMs = 0;
        }
        lastInP3 = inP3;
        lastStage = stage;
        MelodyIntel.expire(System.currentTimeMillis());
    }

    static void onChat(String plain) {
        if (plain == null || plain.isEmpty() || !Floor7Tracker.inF7Boss()) {
            return;
        }
        java.util.regex.Matcher done = TERMINAL_DONE.matcher(plain);
        if (done.matches()) {
            String who = done.group(1);
            String self = selfIgn();
            long now = System.currentTimeMillis();
            if (self != null && self.equals(who)) {
                if (open || now - selfClosedAtMs <= MelodyIntel.CLOSED_GRACE_MS) {
                    selfDoneAtMs = now;
                    MelodyIntel.markDone(self);
                }
            } else {
                MelodyIntel.onTerminalCompleted(who);
            }
            return;
        }
        if (!plain.startsWith("Party > ")) {
            return;
        }
        java.util.regex.Matcher party = PARTY_LINE.matcher(plain);
        if (!party.matches()) {
            return;
        }
        String sender = party.group(1);
        if (!PartyTracker.teammates().stream().anyMatch(sender::equalsIgnoreCase)) {
            return;
        }
        java.util.regex.Matcher m = PROGRESS.matcher(party.group(2));
        if (!m.find()) {
            return;
        }
        int percent;
        if (m.group(1) != null) {
            int done2 = Integer.parseInt(m.group(1));
            int of = Integer.parseInt(m.group(2));
            if (of <= 0 || done2 > of) {
                return;
            }
            percent = Math.round(done2 * 100f / of);
        } else {
            percent = Integer.parseInt(m.group(3));
        }
        if (percent >= 0 && percent < 100) {
            MelodyIntel.offerChat(sender, percent);
        }
    }

    // ------------------------------------------------------------------ our own read

    private static void tickSelf(Minecraft client) {
        if (!SkyblockGate.allows() || !(McCompat.screen(client) instanceof ContainerScreen screen)) {
            closeIfOpen();
            return;
        }
        String title = TerminalType.normalizeTitle(screen.getTitle().getString());
        if (!MELODY_TITLE.matcher(title).matches()) {
            closeIfOpen();
            return;
        }
        open = true;
        MelodySlotReader.Result r = MelodySlotReader.read(terminalItems(screen.getMenu()));
        applySelf(r);
    }

    /** Rows already announced this opening, so each step goes to party chat once. */
    private static int lastSentDone = 0;

    /**
     * "Send Melody Progress" (killer560, 2026-10-06): when the lit clay row moves to row N, N-1 rows are done; post
     * "Melody done/rows - pct%" - both the fraction and the percent, so mods reading either style ("1/3", "33%")
     * pick it up. F7/M7 boss only, straight to party chat (like the coords callout), never the last row (the
     * terminal closing says that), never twice for the same step.
     */
    private static void maybeSendProgress(int clayRow) {
        com.killer560.hub.terminals.TerminalSolverConfig solver = com.killer560.hub.terminals.TerminalSolverConfig.getInstance();
        if (!solver.isEnabled() || !solver.isMelodyEnabled() || !solver.isMelodySendProgress()
                || !com.killer560.hub.fastleap.Floor7Tracker.inF7Boss()) {
            return;
        }
        int rows = Math.max(com.killer560.hub.terminals.TerminalLayouts.melodyRows(), clayRow);
        int done = clayRow - 1;
        if (done <= lastSentDone || done >= rows) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        lastSentDone = done;
        int pct = Math.round(done * 100f / rows);
        // No trailing ")": NoammAddons' MelodyDisplay ignores progress lines ending in ")" (1.2.9, PR #419).
        client.player.connection.sendCommand("pc Melody " + done + "/" + rows + " - " + pct + "%");
    }

    private static void closeIfOpen() {
        if (open) {
            open = false;
            selfClosedAtMs = System.currentTimeMillis();
            lastSentDone = 0;
            lastLitRow = -1;
            lastTarget = -1;
            lastCurrent = -1;
        }
    }

    private static void applySelf(MelodySlotReader.Result r) {
        String self = selfIgn();
        if (self == null) {
            return;
        }
        if (r.highestLitClayRow() != lastLitRow && r.highestLitClayRow() >= BridgeTables.MELODY_MIN_CLAY_ROW
                && r.highestLitClayRow() <= BridgeTables.MELODY_MAX_CLAY_ROW) {
            lastLitRow = r.highestLitClayRow();
            MelodyIntel.offer(self, BridgeTables.MELODY_TYPE_CLAY, lastLitRow);
            maybeSendProgress(lastLitRow);
        }
        if (r.target() != lastTarget && r.target() >= BridgeTables.MELODY_MIN_COLUMN
                && r.target() <= BridgeTables.MELODY_MAX_COLUMN) {
            lastTarget = r.target();
            MelodyIntel.offer(self, BridgeTables.MELODY_TYPE_PURPLE, lastTarget);
        }
        if (r.current() != lastCurrent && r.current() >= BridgeTables.MELODY_MIN_COLUMN
                && r.current() <= BridgeTables.MELODY_MAX_COLUMN) {
            lastCurrent = r.current();
            MelodyIntel.offer(self, BridgeTables.MELODY_TYPE_PANE, lastCurrent);
        }
    }

    /** A normal Hypixel container GUI always appends the player's own 36 inventory+hotbar slots after the
     *  GUI's own content - same trick {@code TerminalSolverFeature#terminalItems} uses, duplicated here so
     *  this package has no dependency on that class beyond the read-only regex in {@link TerminalType}. */
    private static List<ItemStack> terminalItems(AbstractContainerMenu menu) {
        List<ItemStack> all = menu.getItems();
        int n = Math.max(0, all.size() - PLAYER_INVENTORY_SIZE);
        return n == 0 ? all : all.subList(0, n);
    }

    /** Same identity rule the Cross-Mod Bridge uses (see {@code BridgeFeature#context}): only the signed-in
     *  account's own name, and only while it matches the in-world player's own profile name. */
    private static String selfIgn() {
        Minecraft client = Minecraft.getInstance();
        if (client.getUser() == null || client.player == null || client.getUser().getName() == null) {
            return null;
        }
        String name = client.getUser().getName();
        return name.equals(client.player.getGameProfile().name()) ? name : null;
    }

    /** Our own current Melody reading, or null while the terminal isn't open. Polled (never pushed) by
     *  {@code OdinAdapter} and {@code PartyDataFeature} - see this class's own doc for both paths.
     *  @param clayRow 1-4 highest lit row, or -1 unknown; {@code target}/{@code current} 0-4, or -1 unknown. */
    public record SelfMelody(int clayRow, int target, int current) {
    }

    public static SelfMelody selfSnapshot() {
        return open ? new SelfMelody(lastLitRow, lastTarget, lastCurrent) : null;
    }

    // ------------------------------------------------------------------ Team Melody HUD

    /** One HUD row's already-formatted content - mutated in place by {@link #rebuildRows} (tick rate), never
     *  reallocated by {@link TeamMelodyHudElement#render} (frame rate). */
    private static final class Row {
        String name = "";
        String label = "";
        int percent = -1;
        /** A teammate on this mod or Odin: their board (target, moving column, button row) is drawn, not a bar. */
        boolean board;
        int clay = -1;
        int target = -1;
        int current = -1;
    }

    /** Client tick only: refreshes {@link #ROWS}/{@link #rowCount} from {@link MelodyIntel}, which is itself
     *  fed by Odin, our relay, and {@link #applySelf} above - this method re-derives nothing, it only reads
     *  and formats. A row's label string is rebuilt only when its name or percent actually changed, so a
     *  teammate who isn't moving costs one string compare per tick, not one allocation. */
    private static void rebuildRows() {
        // Nothing reads the rows while the HUD is off, so skip the teammate walk and the per-name lowercase lookup.
        if (!MelodyHudConfig.getInstance().isHudEnabledRaw()) {
            rowCount = 0;
            return;
        }
        int n = 0;
        for (String mate : PartyTracker.teammates()) {
            if (n >= ROWS.length) {
                break;
            }
            MelodyIntel.Progress p = MelodyIntel.get(mate);
            if (p == null) {
                continue;
            }
            Row row = ROWS[n++];
            int percent = p.percent();
            if (percent != row.percent || !mate.equals(row.name)) {
                row.name = mate;
                row.percent = percent;
                row.label = percent < 0 ? mate + ": ..." : percent >= 100 ? mate + ": 100% (done)" : mate + ": " + percent + "%";
            }
            // Their own board when it came from a teammate's mod; a chat callout only gives the bar (killer560, 2026-11-01:
            // "if someone is sharing as a part of our mod then have it show the actual melody screen they are seeing").
            row.board = p.hasBoard() && !p.done();
            row.clay = p.clayRow();
            row.target = p.target();
            row.current = p.current();
        }
        rowCount = n;
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this, matching every other HUD in this mod.
        if (client.player == null || HudVisibility.menuOpen() || McCompat.hudHidden(client)
                || !MelodyHudConfig.getInstance().isHudEnabled() || !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3)) {
            return;
        }
        HudElementRegistry.drawAt(graphics, HUD);
    }

    /** Movable in the HUD editor like every other element. Amber-themed: dim-orange bar background, bright
     *  orange fill, the mod's own off-white text color (see {@code ModChat}). Default OFF, New tab. */
    /** Testkit probes (1101-melody-*): boards and bars drawn since launch. */
    static int boardsDrawn;
    static int barsDrawn;

    private static final class TeamMelodyHudElement implements HudElement {
        static final TeamMelodyHudElement INSTANCE = new TeamMelodyHudElement();

        private static final int ROW_HEIGHT = 12;
        private static final int BAR_HEIGHT = 3;
        private static final int BAR_WIDTH = 90;
        private static final int VISIBLE_ROWS = 4; // a Hypixel dungeon party is at most 4 players (3 teammates)

        private static final int COLOR_TEXT = 0xFFF0E6DC;
        private static final int COLOR_DIM = 0xFF9A8C80;
        private static final int COLOR_BAR_BG = 0xFF4D3319;
        private static final int COLOR_BAR_FILL = 0xFFFF8C00;

        @Override
        public String id() {
            return "team_melody";
        }

        @Override
        public String displayName() {
            return "Team Melody";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 300;
        }

        @Override
        public int width() {
            // What render() draws: the waiting line, or the widest of the shown rows' labels, bars and boards.
            Font font = Minecraft.getInstance().font;
            if (rowCount == 0) {
                return font.width(WAITING);
            }
            int w = BAR_WIDTH;
            for (int i = 0; i < Math.min(rowCount, VISIBLE_ROWS); i++) {
                w = Math.max(w, font.width(ROWS[i].label));
                if (ROWS[i].board) {
                    w = Math.max(w, boardWidth());
                }
            }
            return w;
        }

        @Override
        public int height() {
            if (rowCount == 0) {
                return com.killer560.hub.hud.HudText.ROW;
            }
            int h = 0;
            for (int i = 0; i < Math.min(rowCount, VISIBLE_ROWS); i++) {
                h += rowHeight(ROWS[i]);
            }
            return h - (ROW_HEIGHT - 10 - BAR_HEIGHT);
        }

        // ---- the board: what the teammate's Melody screen shows, cell for cell ----
        private static final int CELL = 5;
        private static final int GAP = 1;
        private static final int COLUMNS = 5;
        private static final int COLOR_CELL = 0xFF3A2A1C;
        private static final int COLOR_TARGET = 0xFFD24BD2;
        private static final int COLOR_MOVING = 0xFF7CE04A;
        private static final int COLOR_ROW_DONE = 0xFF3F8F3F;
        private static final int COLOR_BUTTON_WAIT = 0xFFA33A2A;

        private static int boardRows() {
            return Math.max(1, Math.min(BridgeTables.MELODY_MAX_CLAY_ROW, com.killer560.hub.terminals.TerminalLayouts.melodyRows()));
        }

        private static int boardWidth() {
            return (COLUMNS + 1) * (CELL + GAP) + 2;
        }

        private static int boardHeight() {
            return (boardRows() + 1) * (CELL + GAP);
        }

        private static int rowHeight(Row row) {
            return row.board ? 10 + boardHeight() + 3 : ROW_HEIGHT;
        }

        /** Top strip: the target column (purple on the real board). Then one line per button row: rows already played are
         *  green, the active row shows the moving pane at its column and a lit button, later rows wait. */
        private static void drawBoard(GuiGraphicsExtractor graphics, Row row, int x, int y) {
            int rows = boardRows();
            int active = row.clay - 1;
            for (int c = 0; c < COLUMNS; c++) {
                int cx = x + c * (CELL + GAP);
                graphics.fill(cx, y, cx + CELL, y + CELL, c == row.target ? COLOR_TARGET : COLOR_CELL);
            }
            for (int r = 0; r < rows; r++) {
                int ry = y + (r + 1) * (CELL + GAP);
                for (int c = 0; c < COLUMNS; c++) {
                    int cx = x + c * (CELL + GAP);
                    int color = r < active ? COLOR_ROW_DONE : (r == active && c == row.current ? COLOR_MOVING : COLOR_CELL);
                    graphics.fill(cx, ry, cx + CELL, ry + CELL, color);
                }
                int bx = x + COLUMNS * (CELL + GAP) + 2;
                int button = r < active ? COLOR_ROW_DONE : r == active ? COLOR_MOVING : COLOR_BUTTON_WAIT;
                graphics.fill(bx, ry, bx + CELL, ry + CELL, button);
            }
        }

        private static final String WAITING = "Team Melody: waiting...";

        @Override
        public boolean isEnabledInSettings() {
            // Setting only - being in P3 is what the draw stamp in render() reports.
            return MelodyHudConfig.getInstance().isHudEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MelodyHudConfig.getInstance().isHudEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            // Both branches below draw something, and drawHudInGame is what applies the P3 gate before
            // calling this at all, so past this point the element really is on screen.
            HudSeen.markDrawn(id());
            if (rowCount == 0) {
                graphics.text(font, WAITING, x, y, COLOR_DIM, false);
                return;
            }
            int lineY = y;
            int shown = Math.min(rowCount, VISIBLE_ROWS);
            for (int i = 0; i < shown; i++) {
                Row row = ROWS[i];
                graphics.text(font, row.label, x, lineY, COLOR_TEXT, false);
                if (row.board) {
                    drawBoard(graphics, row, x, lineY + 10);
                    boardsDrawn++;
                } else {
                    int barY = lineY + 10;
                    graphics.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, COLOR_BAR_BG);
                    int filled = row.percent <= 0 ? 0 : Math.round(BAR_WIDTH * (Math.min(100, row.percent) / 100f));
                    if (filled > 0) {
                        graphics.fill(x, barY, x + filled, barY + BAR_HEIGHT, COLOR_BAR_FILL);
                    }
                    barsDrawn++;
                }
                lineY += rowHeight(row);
            }
        }
    }
}
