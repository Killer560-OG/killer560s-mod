package com.killer560.hub.autosecret;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoclear.AutoClearFeature;
import com.killer560.hub.autopuzzles.AutoPuzzleActivity;
import com.killer560.hub.autoroutes.RunPlan;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.OverlayPalette;
import com.killer560.hub.gui.OverlayThemes;
import com.killer560.hub.hud.HudAvoid;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

/**
 * The one Status HUD every auto clear shares (killer560, 2026-10-15: "the auto clear hud should work on all of the auto
 * clear things ... make it the same hud for all the auto clears. Make it far shorter and have one side that says clearing
 * and one for secreting if it is doing both"). It replaces Auto Clear's "Auto Clear Status" and Dungeon Autopilot's
 * "Autopilot Status" lines, whose second line printed the planner's whole reasoning ("score-weighted wait 22598.4, all in
 * 357.6 s; ...") straight across the screen and over the dungeon map. Those numbers stay in the log.
 * <p>
 * Two short columns: <b>Clearing</b> (the room, what it is doing in a word or two, mob rooms left) and <b>Secreting</b>
 * (the room, its secrets found, the next room). Auto Clear on its own shows only Clearing; Auto Secret only Secreting
 * (both when it hands rooms to Auto Clear); Dungeon Autopilot both. The column that is not working right now says
 * "paused" and names its next room. Doors, keys and puzzles belong to the Clearing column when it is shown. Nothing is
 * drawn while nothing runs, apart from a short "Stopped: ..." line for a few seconds after a run ends.
 * <p>
 * Cost: the state is read once a client tick (the parts that read the map every {@link #SLOW_TICKS}) and the text is
 * only rebuilt when something it shows changed; a frame draws the cached lines and allocates nothing of its own. Each
 * column is cut at {@link #MAX_COL} units ("..") so the box stays narrow whatever a room or a reason is called.
 */
public final class AutoStatusHud {

    /** Widest a column's text may be, in HUD units. Two columns, the gap and the padding stay under 240 units. */
    static final int MAX_COL = 108;
    static final int GAP = 9;
    static final int PAD = 3;
    static final int PITCH = 10;
    /** Map-reading inputs (rooms left, a room's secrets) are refreshed this often. */
    static final int SLOW_TICKS = 5;
    static final long STOP_SHOWN_MS = 6000L;

    private AutoStatusHud() {
    }

    static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        HudElementRegistry.register(ELEMENT);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "auto_status"), (graphics, deltaTracker) -> draw(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoStatusHud.refresh", c -> refresh()));
    }

    // ------------------------------------------------------------------------------------------- inputs

    private static String stopReason;
    private static long stopMs;

    /** Auto Secret / Autopilot ended (Auto Clear's own end is read from it). */
    static void stopped(String why) {
        stopReason = why;
        stopMs = System.currentTimeMillis();
    }

    /** What the HUD shows, as plain values; compared field by field each tick, so nothing is built when nothing moved. */
    static final class State {
        boolean clear;
        boolean secret;
        boolean clearActive;
        String cRoom;
        String cDoing;
        int cLeft = -1;
        String sRoom;
        String sDoing;
        int sFound = -1;
        int sTotal = -1;
        String sNext;
        String stop;
        /** Whole seconds the running planner estimates the rest takes, counting down; -1 for no estimate. */
        int etaSec = -1;

        boolean same(State o) {
            return clear == o.clear && secret == o.secret && clearActive == o.clearActive && cLeft == o.cLeft
                    && sFound == o.sFound && sTotal == o.sTotal && Objects.equals(cRoom, o.cRoom)
                    && Objects.equals(cDoing, o.cDoing) && Objects.equals(sRoom, o.sRoom)
                    && Objects.equals(sDoing, o.sDoing) && Objects.equals(sNext, o.sNext) && Objects.equals(stop, o.stop)
                    && etaSec == o.etaSec;
        }

        void copy(State o) {
            clear = o.clear;
            secret = o.secret;
            clearActive = o.clearActive;
            cRoom = o.cRoom;
            cDoing = o.cDoing;
            cLeft = o.cLeft;
            sRoom = o.sRoom;
            sDoing = o.sDoing;
            sFound = o.sFound;
            sTotal = o.sTotal;
            sNext = o.sNext;
            stop = o.stop;
            etaSec = o.etaSec;
        }

        void reset() {
            clear = secret = clearActive = false;
            cRoom = cDoing = sRoom = sDoing = sNext = stop = null;
            cLeft = sFound = sTotal = -1;
            etaSec = -1;
        }
    }

    private static final State NOW = new State();
    private static final State SHOWN = new State();
    private static int tickCount;
    private static int slowLeft = -1;
    private static String slowRoom;
    private static int slowFound = -1;
    private static int slowTotal = -1;
    /** The column that worked last, kept through the planner's in-between ticks (DECIDE, SETTLE). */
    private static boolean lastClearActive;

    /** One client tick: read every runner's state and rebuild the text if anything shown changed. */
    static void refresh() {
        if (!ELEMENT.isEnabledInSettings()) {
            return;
        }
        try {
            read(NOW);
        } catch (RuntimeException e) {
            NOW.reset();   // never break the tick over a status line
        }
        if (!NOW.same(SHOWN)) {
            SHOWN.copy(NOW);
            layout = build(SHOWN);
        }
    }

    private static void read(State s) {
        s.reset();
        boolean slow = tickCount++ % SLOW_TICKS == 0;
        boolean secretRun = AutoSecretFeature.isRunning();
        boolean clearRun = AutoClearFeature.isBusy();
        if (!secretRun && !clearRun) {
            slowLeft = -1;
            slowRoom = null;
            String end = AutoClearFeature.hudLastEnd();
            if (stopReason != null && System.currentTimeMillis() - stopMs < STOP_SHOWN_MS
                    && (end == null || stopMs >= AutoClearFeature.hudLastEndMs())) {
                s.stop = "Stopped: " + stopReason;
            } else if (end != null) {
                s.stop = "Auto Clear: " + end;
            }
            return;
        }
        boolean puzzle = AutoPuzzleActivity.isWorking();
        if (!secretRun) {
            // Auto Clear on its own.
            s.clear = true;
            s.clearActive = true;
            s.cRoom = AutoClearFeature.hudRoom();
            s.cDoing = puzzle ? "waiting: puzzle" : clearDoing(AutoClearFeature.hudAction(), AutoClearFeature.hudPathFailures());
            RunPlan.Snapshot plan = RunPlan.of(RunPlan.Source.AUTO_CLEAR);
            s.etaSec = etaOf(plan);
            if (plan != null) {
                s.cLeft = plan.rooms().size();
            } else if (AutoClearFeature.isStandaloneRun()) {
                if (slow || slowLeft < 0) {
                    slowLeft = AutoClearFeature.mobRoomsLeft();
                }
                s.cLeft = slowLeft;
            }
            return;
        }
        boolean pilot = AutoSecretFeature.hudPilot();
        AutoSecretFeature.Phase phase = AutoSecretFeature.hudPhase();
        AutoSecretFeature.Trip trip = AutoSecretFeature.hudTrip();
        String target = AutoSecretFeature.hudTarget();
        s.secret = true;
        s.clear = pilot || AutoSecretConfig.getInstance().isAutoClearRooms() && AutoClearFeature.isAvailable();

        // Which column is working: a route (and the trip to its start) is secreting; everything else is clearing's
        // when that column is shown. The planner's in-between ticks keep the last answer.
        Boolean secreting = switch (phase) {
            case AWAIT_ROUTE, ROUTE -> Boolean.TRUE;
            case TRAVEL -> trip == AutoSecretFeature.Trip.START_NODE;
            case AUTO_CLEAR, ICE_FILL, PUZZLE_WAIT, DOOR, KEY_WAIT -> Boolean.FALSE;
            default -> null;
        };
        boolean clearActive = s.clear && (secreting == null ? lastClearActive : !secreting);
        lastClearActive = clearActive;
        s.clearActive = clearActive;

        String doing = puzzle ? "waiting: puzzle" : secretDoing(phase, trip, AutoSecretFeature.statusText(),
                AutoSecretFeature.hudTripFailures(), AutoSecretFeature.hudLeaveWait() != null);
        RunPlan.Snapshot plan = RunPlan.of(pilot ? RunPlan.Source.AUTOPILOT : RunPlan.Source.AUTO_SECRET);
        s.etaSec = etaOf(plan);

        if (s.clear) {
            if (slow || slowLeft < 0) {
                slowLeft = AutoClearFeature.mobRoomsLeft();
            }
            s.cLeft = slowLeft;
            if (clearActive) {
                if (phase == AutoSecretFeature.Phase.AUTO_CLEAR && AutoClearFeature.isBusy()) {
                    s.cRoom = AutoClearFeature.hudRoom();
                    s.cDoing = puzzle ? "waiting: puzzle"
                            : clearDoing(AutoClearFeature.hudAction(), AutoClearFeature.hudPathFailures());
                } else {
                    s.cRoom = shortTarget(target);
                    s.cDoing = doing;
                }
            } else {
                s.cRoom = nextOf(plan, "Clear", null);
                s.cDoing = "paused";
            }
        }
        if (!clearActive) {
            s.sRoom = target;
            s.sDoing = doing;
            if (slow || !Objects.equals(slowRoom, target)) {
                int[] c = AutoSecretFeature.hudRoomCount(target);
                slowRoom = target;
                slowFound = c == null ? -1 : c[0];
                slowTotal = c == null ? -1 : c[1];
            }
            s.sFound = slowFound;
            s.sTotal = slowTotal;
            s.sNext = nextOf(plan, pilot ? "Secret" : null, target);
        } else {
            s.sRoom = nextOf(plan, pilot ? "Secret" : null, null);
            s.sDoing = "paused";
        }
    }

    /** Whole seconds left on {@code plan}'s estimate (rounded up), or -1 when there is no plan or no estimate. */
    static int etaOf(RunPlan.Snapshot plan) {
        if (plan == null || plan.rooms().isEmpty()) {
            return -1;
        }
        double left = plan.secondsLeft(System.currentTimeMillis());
        return Double.isNaN(left) ? -1 : (int) Math.ceil(left);
    }

    /** The first room of {@code plan} with {@code tag} (any, when null) that is not {@code except}; null for none. */
    static String nextOf(RunPlan.Snapshot plan, String tag, String except) {
        if (plan == null) {
            return null;
        }
        List<String> rooms = plan.rooms();
        List<String> tags = plan.tags();
        for (int i = 0; i < rooms.size(); i++) {
            if ((tag == null || tag.equals(tags.get(i))) && !rooms.get(i).equals(except)) {
                return rooms.get(i);
            }
        }
        return null;
    }

    /** "the Wither Key #3" reads "Wither Key", "the door at cell 63" "wither door" (or "blood door"). */
    static String shortTarget(String t) {
        if (t == null) {
            return null;
        }
        if (t.startsWith("the door at cell")) {
            return AutoSecretFeature.hudDoorBlood() ? "blood door" : "wither door";
        }
        if (t.startsWith("the ")) {
            int hash = t.indexOf(" #");
            return hash > 0 ? t.substring(4, hash) : t.substring(4);
        }
        return t;
    }

    /** Auto Clear's action line in a word or two (the full line stays in its log and {@code AutoClearFeature.status()}). */
    static String clearDoing(String a, int failures) {
        if (a == null || a.isEmpty() || a.startsWith("starting")) {
            return "starting";
        }
        if (a.startsWith("waiting (a screen")) {
            return "waiting: screen";
        }
        if (a.startsWith("waiting (Auto Routes")) {
            return "waiting: route";
        }
        if (a.startsWith("waiting (")) {
            return "waiting: puzzle";
        }
        if (a.contains("wither door")) {
            if (a.contains("no Wither Key")) {
                return "waiting: key";
            }
            if (a.startsWith("no ")) {
                return "stuck - door";
            }
            if (a.startsWith("opening") || a.startsWith("turning") || a.startsWith("waiting for the wither door")) {
                return "opening door";
            }
            return failures > 0 ? "stuck - retrying" : "to the door";
        }
        if (a.startsWith("looking for starred")) {
            return "finding mobs";
        }
        if (a.startsWith("etherwarp to ") || a.startsWith("waiting to land")) {
            return failures > 0 ? "stuck - retrying" : "travelling";
        }
        if (a.startsWith("etherwarp towards")) {
            return "warping to mob";
        }
        if (a.contains(" -> ")) {
            return "attacking";
        }
        if (a.startsWith("landing") || a.contains("hop")) {
            return "hopping";
        }
        return "working";
    }

    /** Auto Secret's / Autopilot's step in a word or two. */
    static String secretDoing(AutoSecretFeature.Phase phase, AutoSecretFeature.Trip trip, String status, int failures,
                              boolean lastSecrets) {
        if (lastSecrets) {
            return "last secrets";
        }
        return switch (phase) {
            case TRAVEL -> failures > 0 ? "stuck - retrying" : switch (trip == null ? AutoSecretFeature.Trip.START_NODE : trip) {
                case DOOR -> "to the door";
                case KEY -> "getting key";
                case PUZZLE, ICE_FILL -> "to the puzzle";
                case EXPLORE -> "exploring";
                default -> "travelling";
            };
            case AWAIT_ROUTE -> "starting route";
            case ROUTE -> "running route";
            case ICE_FILL -> "Ice Fill";
            case PUZZLE_WAIT -> "doing puzzle";
            case AUTO_CLEAR -> "clearing";
            case KEY_WAIT -> "getting key";
            case DOOR -> status != null && status.contains("no key") ? "waiting: key"
                    : status != null && status.startsWith("Waiting") ? "waiting: door" : "opening door";
            case WAITING -> status != null && status.contains("path") ? "stuck - retrying"
                    : status != null && status.contains("door") ? "waiting: door" : "waiting";
            default -> "planning";
        };
    }

    // ------------------------------------------------------------------------------------------- layout

    /** The text as drawn: built on a change, read by every frame. */
    static final class Layout {
        final String[] left;
        final int[] leftRgb;
        final String[] right;
        final int[] rightRgb;
        final String footer;
        /** The footer is the time-left line (theme accent), not a stop reason (red). */
        final boolean footerGood;
        final int leftW;
        final int rightW;
        final int width;
        final int height;
        final boolean clearActive;

        Layout(String[] left, int[] leftRgb, String[] right, int[] rightRgb, String footer, boolean footerGood, int leftW,
               int rightW, int width, int height, boolean clearActive) {
            this.left = left;
            this.leftRgb = leftRgb;
            this.right = right;
            this.rightRgb = rightRgb;
            this.footer = footer;
            this.footerGood = footerGood;
            this.leftW = leftW;
            this.rightW = rightW;
            this.width = width;
            this.height = height;
            this.clearActive = clearActive;
        }
    }

    /** Colour roles; the drawing maps them onto the menu theme's palette. */
    static final int HEAD = 0;
    static final int HEAD_PAUSED = 1;
    static final int TEXT = 2;
    static final int SOFT = 3;
    static final int BAD = 4;

    private static Layout layout;
    private static Layout sample;

    static Layout build(State s) {
        Font font = Minecraft.getInstance().font;
        String[] left = null;
        int[] leftRgb = null;
        String[] right = null;
        int[] rightRgb = null;
        if (s.clear) {
            String left3 = s.cLeft < 0 ? null : s.cLeft == 0 ? "none left" : s.cLeft == 1 ? "1 room left"
                    : s.cLeft + " rooms left";
            String room = s.clearActive ? s.cRoom : s.cRoom == null ? null : "next: " + s.cRoom;
            left = compact(font, "Clearing", room == null ? "-" : room, s.cDoing, left3);
            leftRgb = roles(left, s.clearActive, s.cDoing);
        }
        if (s.secret) {
            boolean active = !s.clear || !s.clearActive;
            String room = active ? s.sRoom : s.sRoom == null ? null : "next: " + s.sRoom;
            String secrets = active && s.sTotal > 0 ? "secrets " + s.sFound + "/" + s.sTotal : null;
            String next = active && s.sNext != null ? "next: " + s.sNext : null;
            right = compact(font, "Secreting", room == null ? "-" : room, s.sDoing, secrets, next);
            rightRgb = roles(right, active, s.sDoing);
        }
        if (left == null && right != null) {
            left = right;
            leftRgb = rightRgb;
            right = null;
            rightRgb = null;
        }
        int leftW = colWidth(font, left);
        int rightW = colWidth(font, right);
        int textW = leftW + (right == null ? 0 : GAP + rightW);
        String footer = null;
        boolean footerGood = false;
        if (s.stop != null) {
            footer = fit(font, s.stop, Math.max(textW, MAX_COL));
            textW = Math.max(textW, font.width(footer));
        } else if (s.etaSec >= 0 && (left != null || right != null)) {
            // killer560, 2026-10-18: "an estimated time till completion that counts down".
            footer = fit(font, "Done in ~" + RunPlan.clock(s.etaSec), Math.max(textW, MAX_COL));
            footerGood = true;
            textW = Math.max(textW, font.width(footer));
        }
        int rows = Math.max(left == null ? 0 : left.length, right == null ? 0 : right.length) + (footer == null ? 0 : 1);
        if (rows == 0) {
            return null;
        }
        int height = 2 * PAD + HudText.height(rows, PITCH);
        return new Layout(left, leftRgb, right, rightRgb, footer, footerGood, leftW, rightW, textW + 2 * PAD, height,
                s.clear && s.clearActive);
    }

    /** The non-null lines, each cut to {@link #MAX_COL}. */
    private static String[] compact(Font font, String... lines) {
        int n = 0;
        for (String l : lines) {
            if (l != null) {
                n++;
            }
        }
        String[] out = new String[n];
        int i = 0;
        for (String l : lines) {
            if (l != null) {
                out[i++] = fit(font, l, MAX_COL);
            }
        }
        return out;
    }

    private static int[] roles(String[] lines, boolean active, String doing) {
        int[] r = new int[lines.length];
        r[0] = active ? HEAD : HEAD_PAUSED;
        for (int i = 1; i < lines.length; i++) {
            r[i] = !active ? SOFT : i == 1 ? TEXT : SOFT;
        }
        if (active && lines.length > 2 && doing != null && doing.startsWith("stuck")) {
            r[2] = BAD;
        }
        return r;
    }

    private static int colWidth(Font font, String[] lines) {
        if (lines == null) {
            return 0;
        }
        int w = 1;
        for (String l : lines) {
            w = Math.max(w, font.width(l));
        }
        return w;
    }

    static String fit(Font font, String s, int max) {
        if (font.width(s) <= max) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, max - font.width(".."))) + "..";
    }

    /** What is drawn right now: the live layout, the editor's sample while a menu is open, or null. */
    static Layout current() {
        if (HudVisibility.menuOpen()) {
            if (sample == null) {
                State s = new State();
                s.clear = true;
                s.secret = true;
                s.clearActive = true;
                s.cRoom = "Mushroom";
                s.cDoing = "attacking";
                s.cLeft = 6;
                s.sRoom = "Gold";
                s.sDoing = "paused";
                sample = build(s);
            }
            return sample;
        }
        return layout;
    }

    /** The lines as drawn now, left column then right then the footer (for the testkit); empty when nothing shows. */
    public static List<String> shownText() {
        Layout l = layout;
        List<String> out = new java.util.ArrayList<>();
        if (l == null) {
            return out;
        }
        if (l.left != null) {
            out.addAll(List.of(l.left));
        }
        if (l.right != null) {
            out.addAll(List.of(l.right));
        }
        if (l.footer != null) {
            out.add(l.footer);
        }
        return out;
    }

    /** {left column, right column}, each null when not shown (for the testkit). */
    public static String[][] shownColumns() {
        Layout l = layout;
        return l == null ? new String[][]{null, null} : new String[][]{l.left, l.right};
    }

    /** The box in HUD units, {width, height}, or {0, 0} (for the testkit). */
    public static int[] shownSize() {
        Layout l = layout;
        return l == null ? new int[]{0, 0} : new int[]{l.width, l.height};
    }

    // ------------------------------------------------------------------------------------------- element

    private static long avoidAtMs;
    private static int[] avoided;

    static final HudElement ELEMENT = new HudElement() {
        @Override
        public String id() {
            return "auto_status";
        }

        @Override
        public String displayName() {
            return "Auto Clear Status";
        }

        /** Top centre, away from the Dungeon Map (bottom left by default) and the left column of dungeon HUDs. */
        @Override
        public int defaultX() {
            return Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2
                    - (MAX_COL + GAP / 2 + PAD + 2));
        }

        @Override
        public int defaultY() {
            return 46;
        }

        @Override
        public int width() {
            Layout l = current();
            return l == null ? 20 : l.width;
        }

        @Override
        public int height() {
            Layout l = current();
            return l == null ? HudText.ROW : l.height;
        }

        @Override
        public boolean isEnabledInSettings() {
            return BuildVariant.CHEAT_FEATURES_ENABLED && AutoSecretConfig.getInstance().isStatusHud();
        }

        /** In game, never on top of the Dungeon Map ({@link HudAvoid}); in the editor its own position. Re-asked at most
         *  every 250 ms (HudAvoid builds a key string per call). */
        @Override
        public int[] layoutPosition() {
            if (HudVisibility.editorOpen()) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - avoidAtMs >= 250L) {
                avoidAtMs = now;
                avoided = HudAvoid.clear(this, "live_map");
            }
            return avoided;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Layout l = current();
            if (l == null) {
                return;
            }
            HudSeen.markDrawn(id());
            OverlayPalette p = OverlayThemes.menu();
            Font font = Minecraft.getInstance().font;
            graphics.fill(x, y, x + l.width, y + l.height, OverlayPalette.alpha(p.panel(), 0xC8));
            graphics.outline(x, y, l.width, l.height, OverlayPalette.alpha(p.border(), 0xFF));
            int tx = x + PAD;
            int ty = y + PAD;
            if (l.left != null) {
                column(graphics, font, p, l.left, l.leftRgb, tx, ty);
            }
            if (l.right != null) {
                int rx = tx + l.leftW + GAP;
                int lines = Math.max(l.left.length, l.right.length);
                graphics.fill(rx - (GAP + 1) / 2, ty, rx - (GAP + 1) / 2 + 1, ty + HudText.height(lines, PITCH),
                        OverlayPalette.alpha(p.border(), 0xFF));
                column(graphics, font, p, l.right, l.rightRgb, rx, ty);
            }
            if (l.footer != null) {
                int rows = Math.max(l.left == null ? 0 : l.left.length, l.right == null ? 0 : l.right.length);
                graphics.text(font, l.footer, tx, ty + rows * PITCH, 0xFF000000 | (l.footerGood ? p.accent() : 0xE05555),
                        !p.light());
            }
        }

        private void column(GuiGraphicsExtractor graphics, Font font, OverlayPalette p, String[] lines, int[] roles,
                            int x, int y) {
            boolean shadow = !p.light();
            for (int i = 0; i < lines.length; i++) {
                int c = switch (roles[i]) {
                    case HEAD -> p.accent();
                    case HEAD_PAUSED, SOFT -> p.dim();
                    case BAD -> 0xFFE05555;
                    default -> p.text();
                };
                graphics.text(font, lines[i], x, y + i * PITCH, 0xFF000000 | c, shadow);
            }
        }
    };

    private static void draw(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (layout == null || client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen()
                    || !ELEMENT.isEnabledInSettings()) {
                return;
            }
            HudElementRegistry.drawAt(graphics, ELEMENT);
        } catch (RuntimeException e) {
            // never take the HUD frame down over a status line
        }
    }
}
