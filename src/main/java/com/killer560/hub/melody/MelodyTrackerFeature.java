package com.killer560.hub.melody;

import com.killer560.hub.bridge.BridgeTables;
import com.killer560.hub.bridge.MelodyIntel;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
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
        ClientTickEvents.END_CLIENT_TICK.register(MelodyTrackerFeature::tick);
        HudElementRegistry.register(HUD);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "team_melody_hud"),
                (graphics, deltaTracker) -> drawHudInGame(graphics));
    }

    private static void tick(Minecraft client) {
        tickSelf(client);
        rebuildRows();
    }

    // ------------------------------------------------------------------ our own read

    private static void tickSelf(Minecraft client) {
        if (!SkyblockGate.allows() || !(client.screen instanceof ContainerScreen screen)) {
            closeIfOpen();
            return;
        }
        String title = screen.getTitle().getString();
        if (!MELODY_TITLE.matcher(title).matches()) {
            closeIfOpen();
            return;
        }
        open = true;
        MelodySlotReader.Result r = MelodySlotReader.read(terminalItems(screen.getMenu()));
        applySelf(r);
    }

    private static void closeIfOpen() {
        if (open) {
            open = false;
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
    }

    /** Client tick only: refreshes {@link #ROWS}/{@link #rowCount} from {@link MelodyIntel}, which is itself
     *  fed by Odin, our relay, and {@link #applySelf} above - this method re-derives nothing, it only reads
     *  and formats. A row's label string is rebuilt only when its name or percent actually changed, so a
     *  teammate who isn't moving costs one string compare per tick, not one allocation. */
    private static void rebuildRows() {
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
                row.label = percent < 0 ? mate + ": ..." : mate + ": " + percent + "%";
            }
        }
        rowCount = n;
    }

    private static void drawHudInGame(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this, matching every other HUD in this mod.
        if (client.player == null || HudVisibility.menuOpen() || client.options.hideGui
                || !MelodyHudConfig.getInstance().isHudEnabled() || !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3)) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(HUD);
        float scale = HudElementRegistry.resolveScale(HUD);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            HUD.render(graphics, 0, 0);
        } catch (RuntimeException ignored) {
            // One broken frame must never take down the whole HUD.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** Movable in the HUD editor like every other element. Amber-themed: dim-orange bar background, bright
     *  orange fill, the mod's own off-white text colour (see {@code ModChat}). Default OFF, New tab. */
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
            return BAR_WIDTH + 10;
        }

        @Override
        public int height() {
            return ROW_HEIGHT * VISIBLE_ROWS;
        }

        @Override
        public boolean isRelevantNow() {
            return MelodyHudConfig.getInstance().isHudEnabled() && Floor7Tracker.inPhase(Floor7Tracker.Phase.P3);
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!MelodyHudConfig.getInstance().isHudEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            if (rowCount == 0) {
                graphics.text(font, "Team Melody: waiting...", x, y, COLOR_DIM, false);
                return;
            }
            int lineY = y;
            int shown = Math.min(rowCount, VISIBLE_ROWS);
            for (int i = 0; i < shown; i++) {
                Row row = ROWS[i];
                graphics.text(font, row.label, x, lineY, COLOR_TEXT, false);
                int barY = lineY + 10;
                graphics.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, COLOR_BAR_BG);
                int filled = row.percent <= 0 ? 0 : Math.round(BAR_WIDTH * (row.percent / 100f));
                if (filled > 0) {
                    graphics.fill(x, barY, x + filled, barY + BAR_HEIGHT, COLOR_BAR_FILL);
                }
                lineY += ROW_HEIGHT;
            }
        }
    }
}
