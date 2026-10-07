package com.killer560.hub.playerstats;

import com.killer560.hub.hud.AutoScale;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.killer560.hub.playerstats.StatElements.Readout;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Predefined layout of Health and Mana Bars (killer560, 2026-10-07: "a setting that is predefined spots or fully
 * custom. Then for predefined look at how Skyblocker has their snap-to-area kind of set up and use that").
 * <p>
 * <b>What Skyblocker does</b> (Fancy Status Bars, 6.9.1, read with javap from {@code fancybars.BarPositioner} and
 * {@code FancyStatusBars.updatePositions}): nine anchors, each a point on the screen in GUI pixels - three round the
 * hotbar ({@code HOTBAR_TOP} at the hotbar's left edge, 23 above the screen bottom with its own XP bar on and 35 with
 * the vanilla one, which is the vanilla level number's top; {@code HOTBAR_LEFT}/{@code _RIGHT} 2 px outside the
 * hotbar's ends, 5 up) and six at the screen's corners and beside the crosshair. Each anchor holds rows, each row holds
 * bars side by side; rows grow away from the anchor, one bar height (9) plus 1 apart, and an empty row takes no room.
 * Above the hotbar a row always fills the hotbar's 182 px: each bar has a size in twelfths, the row's sizes are pushed
 * to sum to 12, and neighbours sit 2 px apart; with the vanilla hearts still drawn the row takes only the right half.
 * Elsewhere a bar is its own size (25 px a unit, 2 to 6 units). Bar text sits ON the bar. In its editor a picked-up bar
 * follows the mouse; touching another bar's side inserts it beside that bar, touching its top or bottom opens a new row,
 * and an empty anchor shows a white 20x20 square (the full width above the hotbar) that takes it. A disabled bar is
 * parked in a column at the screen's left. Everything is in GUI pixels, so it follows the window and GUI scale only.
 * <p>
 * <b>What this builds from it.</b> Four areas round the hotbar plus Hidden ({@link Area}). The two rows above the
 * hotbar are Skyblocker's HOTBAR_TOP as two layers: every readout in a row gets an equal share of the hotbar's width
 * ({@link #GAP} apart, keeping its order), and the rows stack upward from just above the highest vanilla row that is
 * still drawn - hearts (every heart row, vanilla's own row maths), armour, hunger, air, the XP bar and its level - so
 * they never cover one, whichever of the hide settings are on. Left and Right of Hotbar stack readouts upward beside
 * the hotbar (clear of the offhand slot when it shows), each bar at its own length up to the room there is. The
 * corner and crosshair anchors were left out: the top right is where vanilla draws potion effects, the bottom left is
 * chat, and every area kept is one a dragged bar can reach next to the hotbar it is about. Hidden keeps a readout on
 * but undrawn, and lives in the HUD editor as a tray at the left.
 * <p>
 * Positions are in GUI pixels relative to the hotbar, so they follow the window size and GUI scale exactly as the
 * hotbar does. A readout's thickness and its text are drawn at its HUD scale (its own scale times HUD Scale times Auto
 * Scale, {@link HudElementRegistry#resolveScale}), like every other element, and the rows are stacked by those drawn
 * heights, so a scaled readout still never overlaps its neighbour. Text readouts are members of the areas like the
 * bars rather than being glued to one: a bar already carries its own number (Show Value, which is where Skyblocker
 * puts text), three readouts (Overflow Mana, Intelligence, Effective Health) have no bar to follow, and a text stuck
 * to a bar in a two-row stack would have nowhere to go that is not the next row.
 */
public final class StatLayout {

    /** The areas, in the order they are laid out. */
    public enum Area {
        ABOVE_HOTBAR("above_hotbar", "Above Hotbar", true),
        ABOVE_HOTBAR_2("above_hotbar_2", "Above Hotbar, Row 2", true),
        LEFT_OF_HOTBAR("left_of_hotbar", "Left of Hotbar", false),
        RIGHT_OF_HOTBAR("right_of_hotbar", "Right of Hotbar", false),
        HIDDEN("hidden", "Hidden", false);

        /** Persistence key in PlayerStatsConfig - never change. */
        public final String key;
        public final String label;
        /** A row (members side by side, sharing the hotbar's width) rather than a column. */
        public final boolean row;

        Area(String key, String label, boolean row) {
            this.key = key;
            this.label = label;
            this.row = row;
        }

        public static Area byKey(String key) {
            for (Area a : values()) {
                if (a.key.equals(key)) {
                    return a;
                }
            }
            return null;
        }
    }

    /** Vanilla's hotbar (and XP bar) width. */
    public static final int HOTBAR_WIDTH = 182;
    /** Between two readouts in a row, between rows, and between readouts in a column (Skyblocker: 2 in a row, 1 between rows). */
    public static final int GAP = 2;
    /** From the hotbar's end to a side column. */
    public static final int SIDE_GAP = 4;
    /** The offhand slot vanilla draws beside the hotbar when the offhand holds something (29 = its 22 + the 7 gap). */
    public static final int OFFHAND = 29;
    /** Kept clear at the screen's edges. */
    public static final int EDGE = 2;
    /** The most a side column's drop zone is wide when nothing is in it. */
    public static final int SIDE_ZONE = 100;
    /** Height an empty row keeps while a readout is being dragged, so it can be dropped into. */
    public static final int EMPTY_ROW = 10;
    /** The Hidden tray in the HUD editor: its top-left and least size. */
    public static final int TRAY_X = 8;
    public static final int TRAY_MIN_W = 110;
    public static final int TRAY_LABEL = 12;

    /** Where one readout draws: its top-left in GUI pixels, its drawn size, and (bars) its length in its own units. */
    public record Placed(int[] pos, int w, int h, int widthLocal) {
        public int x() {
            return pos[0];
        }

        public int y() {
            return pos[1];
        }
    }

    /** An area's drop zone in the HUD editor, in GUI pixels (x1/y1 exclusive). */
    public record Zone(Area area, int x0, int y0, int x1, int y1) {
        public boolean contains(double mx, double my) {
            return mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }

        double distance(double mx, double my) {
            double dx = mx < x0 ? x0 - mx : mx >= x1 ? mx - x1 + 1 : 0;
            double dy = my < y0 ? y0 - my : my >= y1 ? my - y1 + 1 : 0;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    /** One computed layout. */
    public static final class Layout {
        public final Map<Readout, Placed> placed = new EnumMap<>(Readout.class);
        public final List<Zone> zones = new ArrayList<>();
        /** Each area's laid-out readouts, in order. */
        public final Map<Area, List<Readout>> members = new EnumMap<>(Area.class);

        public Zone zone(Area a) {
            for (Zone z : zones) {
                if (z.area() == a) {
                    return z;
                }
            }
            return null;
        }
    }

    /** Where a dragged readout would go. {@code index} counts the area's laid-out members it would sit before. */
    public record Target(Area area, int index) {
    }

    // ---- drag preview (HUD editor) -----------------------------------------------------------------------------------
    private static Readout previewMoving;
    private static Area previewArea;
    private static int previewIndex;
    private static boolean previewActive;
    private static int previewVersion;

    // ---- memo -------------------------------------------------------------------------------------------------------
    private static int frame;
    private static Layout memo;
    private static int memoFrame = -1;
    private static int memoW;
    private static int memoH;
    private static boolean memoEditor;
    private static int memoPs = -1;
    private static int memoHud = -1;
    private static int memoPreview = -1;
    private static float memoFactor;

    private StatLayout() {
    }

    /** Whether the Predefined layout is selected. */
    public static boolean predefined() {
        return PlayerStatsConfig.getInstance().isPredefinedLayout();
    }

    /** Whether {@code e} is placed by this layout right now (a stat readout, with Predefined selected). */
    public static boolean manages(HudElement e) {
        return e != null && StatElements.isStatElementId(e.id()) && predefined();
    }

    /** The readout behind a stat element id, or null. */
    public static Readout readout(String hudId) {
        for (Readout r : Readout.values()) {
            if (r.hudId.equals(hudId)) {
                return r;
            }
        }
        return null;
    }

    /** The area a readout starts in: health and mana sharing the row above the hotbar (Skyblocker's default too), the
     *  other bars on the row above that, health-side texts left of the hotbar and mana-side texts right of it. */
    public static Area defaultArea(Readout r) {
        return switch (r) {
            case HEALTH_BAR, MANA_BAR -> Area.ABOVE_HOTBAR;
            case DEFENCE_BAR, VITALITY_BAR, OTHER_BAR, XP_BAR -> Area.ABOVE_HOTBAR_2;
            case HEALTH_TEXT, DEFENCE_TEXT, EFFECTIVE_HEALTH_TEXT, VITALITY_TEXT -> Area.LEFT_OF_HOTBAR;
            default -> Area.RIGHT_OF_HOTBAR;
        };
    }

    /** Every readout assigned to {@code area} (on or off), in its order. */
    static List<Readout> inOrder(PlayerStatsConfig cfg, Area area) {
        List<Readout> out = new ArrayList<>();
        for (Readout r : Readout.values()) {
            if (cfg.getArea(r) == area) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt(cfg::getOrder).thenComparingInt(Enum::ordinal));
        return out;
    }

    /** Called once per frame by the HUD layer and the HUD editor: values and vanilla rows may have changed. */
    public static void newFrame() {
        frame++;
    }

    /** The HUD editor's drag: {@code moving} shown at {@code index} of {@code area}. A null area shows it where it is. */
    public static void setPreview(Readout moving, Area area, int index) {
        if (previewActive && previewMoving == moving && previewArea == area && previewIndex == index) {
            return;
        }
        previewActive = true;
        previewMoving = moving;
        previewArea = area;
        previewIndex = index;
        previewVersion++;
    }

    public static void clearPreview() {
        if (previewActive) {
            previewActive = false;
            previewMoving = null;
            previewArea = null;
            previewVersion++;
        }
    }

    /** Where {@code r} draws now, or null when the layout does not place it (Custom, off, nothing to show, Hidden). */
    public static Placed placed(Readout r) {
        if (!predefined()) {
            return null;
        }
        Layout l = current();
        return l == null ? null : l.placed.get(r);
    }

    /** The layout as it draws now (in game, or in the HUD editor with its drag preview). */
    public static Layout current() {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc == null ? null : mc.getWindow();
        if (window == null) {
            return null;
        }
        int w = window.getGuiScaledWidth();
        int h = window.getGuiScaledHeight();
        boolean editor = HudVisibility.editorOpen();
        int ps = PlayerStatsConfig.layoutVersion();
        int hud = HudConfig.version();
        int pv = editor ? previewVersion : -1;
        float factor = AutoScale.current();
        if (memo != null && memoFrame == frame && memoW == w && memoH == h && memoEditor == editor && memoPs == ps
                && memoHud == hud && memoPreview == pv && memoFactor == factor) {
            return memo;
        }
        boolean preview = editor && previewActive;
        Layout l = compute(mc, w, h, editor, null, preview ? previewMoving : null, preview ? previewArea : null,
                previewIndex, preview);
        memo = l;
        memoFrame = frame;
        memoW = w;
        memoH = h;
        memoEditor = editor;
        memoPs = ps;
        memoHud = hud;
        memoPreview = pv;
        memoFactor = factor;
        return l;
    }

    /** The HUD editor's drop zones for dragging {@code moving}: the layout without it, every empty area kept open. */
    public static Layout zonesWithout(Readout moving) {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        return compute(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight(), true, moving, null, null, 0, true);
    }

    /**
     * Which area (and where in it) a readout dropped at (mx, my) goes to, judged on {@code zones}
     * ({@link #zonesWithout}). The zone under the cursor, else the nearest within 16 px, else null (dropped nowhere:
     * it stays where it was).
     */
    public static Target targetAt(Layout zones, double mx, double my) {
        Zone best = null;
        double bestD = 16.0;
        for (Zone z : zones.zones) {
            if (z.contains(mx, my)) {
                best = z;
                break;
            }
            double d = z.distance(mx, my);
            if (d <= bestD) {
                bestD = d;
                best = z;
            }
        }
        if (best == null) {
            return null;
        }
        List<Readout> in = zones.members.getOrDefault(best.area(), List.of());
        int index = 0;
        for (Readout r : in) {
            Placed p = zones.placed.get(r);
            if (p == null) {
                continue;
            }
            double cx = p.x() + p.w() / 2.0;
            double cy = p.y() + p.h() / 2.0;
            boolean before = switch (best.area()) {
                case ABOVE_HOTBAR, ABOVE_HOTBAR_2 -> cx < mx;
                case HIDDEN -> cy < my;
                default -> cy > my; // side columns grow upward: index 0 is the bottom one
            };
            if (before) {
                index++;
            }
        }
        return new Target(best.area(), index);
    }

    /** The readout a drop at {@code target} goes before, or null for the end of the area. */
    public static Readout beforeOf(Layout zones, Target target) {
        List<Readout> in = zones.members.getOrDefault(target.area(), List.of());
        return target.index() < in.size() ? in.get(target.index()) : null;
    }

    // ---- the layout ---------------------------------------------------------------------------------------------------

    private static Layout compute(Minecraft mc, int w, int h, boolean editor, Readout exclude, Readout moving,
                                  Area into, int index, boolean reserveEmpty) {
        PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
        Layout out = new Layout();
        for (Area a : Area.values()) {
            List<Readout> list = new ArrayList<>();
            for (Readout r : inOrder(cfg, a)) {
                if (r == exclude || r == moving || !cfg.isReadoutOn(r)) {
                    continue;
                }
                // In game only what has something to show takes room (Skyblocker drops a bar that is not visible
                // the same way); the editor previews every readout that is on.
                if (!editor && !StatElements.hasValue(r)) {
                    continue;
                }
                list.add(r);
            }
            out.members.put(a, list);
        }
        if (moving != null && cfg.isReadoutOn(moving)) {
            Area a = into != null ? into : cfg.getArea(moving);
            List<Readout> list = out.members.get(a);
            if (into == null) {
                // Shown where it is: its own place among the others.
                int at = 0;
                for (Readout r : list) {
                    if (cfg.getOrder(r) < cfg.getOrder(moving)
                            || (cfg.getOrder(r) == cfg.getOrder(moving) && r.ordinal() < moving.ordinal())) {
                        at++;
                    }
                }
                list.add(at, moving);
            } else {
                list.add(Math.max(0, Math.min(index, list.size())), moving);
            }
        }

        int hotbarLeft = w / 2 - HOTBAR_WIDTH / 2;
        int hotbarRight = hotbarLeft + HOTBAR_WIDTH;

        // Rows above the hotbar, stacked up from the highest vanilla row still drawn.
        int bottom = vanillaTop(mc, w, h) - 1;
        for (Area a : new Area[]{Area.ABOVE_HOTBAR, Area.ABOVE_HOTBAR_2}) {
            List<Readout> list = out.members.get(a);
            if (list.isEmpty() && !reserveEmpty) {
                continue;
            }
            int rowH = list.isEmpty() ? EMPTY_ROW : 0;
            for (Readout r : list) {
                rowH = Math.max(rowH, drawnHeight(r));
            }
            int top = bottom - rowH;
            int n = list.size();
            int avail = HOTBAR_WIDTH - GAP * Math.max(0, n - 1);
            int x = hotbarLeft;
            for (int i = 0; i < n; i++) {
                Readout r = list.get(i);
                int slot = avail / n + (i < avail % n ? 1 : 0);
                float s = scale(r);
                int dh = drawnHeight(r);
                int y = top + (rowH - dh) / 2;
                if (r.bar) {
                    int local = Math.max(1, (int) Math.floor(slot / s));
                    out.placed.put(r, new Placed(new int[]{x, y}, Math.round(local * s), dh, local));
                } else {
                    int tw = Math.round(StatElements.textWidth(r, editor) * s);
                    out.placed.put(r, new Placed(new int[]{x + Math.max(0, (slot - tw) / 2), y}, tw, dh, -1));
                }
                x += slot + GAP;
            }
            out.zones.add(new Zone(a, hotbarLeft - 1, top - 1, hotbarRight + 1, bottom + 1));
            bottom = top - GAP;
        }

        // Columns beside the hotbar, stacked up from the screen's bottom.
        LocalPlayer player = mc.player;
        boolean offhandShown = player != null && !player.getOffhandItem().isEmpty();
        boolean offhandLeft = offhandShown && player.getMainArm() == HumanoidArm.RIGHT;
        boolean offhandRight = offhandShown && player.getMainArm() == HumanoidArm.LEFT;
        int leftEdge = hotbarLeft - SIDE_GAP - (offhandLeft ? OFFHAND : 0);
        int rightEdge = hotbarRight + SIDE_GAP + (offhandRight ? OFFHAND : 0);
        column(out, Area.LEFT_OF_HOTBAR, editor, leftEdge, Math.max(1, leftEdge - EDGE), h, true);
        column(out, Area.RIGHT_OF_HOTBAR, editor, rightEdge, Math.max(1, w - EDGE - rightEdge), h, false);

        if (editor) {
            hiddenTray(out, h, editor);
        }
        return out;
    }

    /** A side column: readouts stacked upward from the screen's bottom against the hotbar-side {@code edge}. */
    private static void column(Layout out, Area a, boolean editor, int edge, int avail, int h, boolean left) {
        List<Readout> list = out.members.get(a);
        int y = h - EDGE;
        int widest = 0;
        for (Readout r : list) {
            float s = scale(r);
            int dh = drawnHeight(r);
            int dw;
            int local;
            if (r.bar) {
                int want = Math.round(PlayerStatsConfig.getInstance().getBarWidth(r) * s);
                int fit = Math.min(want, avail);
                local = Math.max(1, (int) Math.floor(fit / s));
                dw = Math.round(local * s);
            } else {
                dw = Math.round(StatElements.textWidth(r, editor) * s);
                local = -1;
            }
            y -= dh;
            int x = left ? edge - dw : edge;
            out.placed.put(r, new Placed(new int[]{x, y}, dw, dh, local));
            widest = Math.max(widest, dw);
            y -= GAP;
        }
        int zoneW = Math.min(avail, Math.max(widest, SIDE_ZONE));
        int top = Math.min(y + GAP, h - EDGE - 2 * EMPTY_ROW);
        out.zones.add(left
                ? new Zone(a, edge - zoneW - 1, top - 1, edge + 1, h)
                : new Zone(a, edge - 1, top - 1, edge + zoneW + 1, h));
    }

    /** The HUD editor's Hidden tray at the left middle of the screen; hidden readouts listed in it, top down. */
    private static void hiddenTray(Layout out, int h, boolean editor) {
        List<Readout> list = out.members.get(Area.HIDDEN);
        int x0 = TRAY_X;
        int y0 = Math.max(48, h / 2 - 30);
        int y = y0 + TRAY_LABEL;
        int widest = 0;
        for (Readout r : list) {
            float s = scale(r);
            int dh = drawnHeight(r);
            int dw;
            int local;
            if (r.bar) {
                int want = Math.min(Math.round(PlayerStatsConfig.getInstance().getBarWidth(r) * s), 100);
                local = Math.max(1, (int) Math.floor(want / s));
                dw = Math.round(local * s);
            } else {
                dw = Math.round(StatElements.textWidth(r, editor) * s);
                local = -1;
            }
            out.placed.put(r, new Placed(new int[]{x0 + 4, y}, dw, dh, local));
            widest = Math.max(widest, dw);
            y += dh + GAP + 2;
        }
        int x1 = x0 + Math.max(TRAY_MIN_W, widest + 8);
        int y1 = Math.max(y0 + TRAY_LABEL + 18, y + 2);
        out.zones.add(new Zone(Area.HIDDEN, x0, y0, x1, y1));
    }

    private static float scale(Readout r) {
        HudElement e = HudElementRegistry.byId(r.hudId);
        return e == null ? HudConfig.getInstance().getEffectiveGlobalScale() : HudElementRegistry.resolveScale(e);
    }

    private static int drawnHeight(Readout r) {
        HudElement e = HudElementRegistry.byId(r.hudId);
        int units = e == null ? 9 : e.height();
        return Math.max(1, Math.round(units * scale(r)));
    }

    /**
     * Top (GUI y) of the highest vanilla row drawn over the hotbar right now: the hotbar's own selection frame, the XP
     * bar and its level number, every heart row, armour, hunger and air - each only while it is really drawn (game mode,
     * this mod's hides and Object Hider's). Vanilla's numbers, javap of {@code Gui.extractPlayerHealth} (26.1.2) and
     * {@code Hud.extractPlayerHealth} (26.2), identical: hearts at {@code guiHeight - 39}, rows
     * {@code ceil((max(maxHealth, health) + absorption) / 2 / 10)}, row step {@code max(10 - (rows - 2), 3)}, armour
     * 10 above the top heart row, hunger at -39, air at -49; the XP bar at {@code guiHeight - 24 - 5} and its level text
     * at {@code guiHeight - 24 - 9 - 2}, outlined one pixel out (ContextualBarRenderer / ContextualBar).
     */
    public static int vanillaTop(Minecraft mc, int w, int h) {
        int top = h - 23; // the hotbar (22) and its selection frame, one higher
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) {
            return top;
        }
        if (mc.gameMode.hasExperience() && !PlayerStatsFeature.hidesVanillaXp()) {
            top = Math.min(top, p.experienceLevel > 0 ? h - 24 - 9 - 2 - 1 : h - 24 - 5);
        }
        if (mc.gameMode.canHurtPlayer()) {
            ObjectHiderConfig oh = ObjectHiderConfig.getInstance();
            float health = Math.max(p.getMaxHealth(), p.getHealth());
            int absorption = oh.isHideAbsorptionHearts() ? 0 : Mth.ceil(p.getAbsorptionAmount());
            int rows = Mth.ceil((health + absorption) / 2.0F / 10.0F);
            int rowStep = Math.max(10 - (rows - 2), 3);
            int heartTop = h - 39 - (rows - 1) * rowStep;
            if (!PlayerStatsFeature.hidesVanillaHearts() && !oh.isHideHealthBar()) {
                top = Math.min(top, heartTop);
            }
            if (p.getArmorValue() > 0 && !PlayerStatsFeature.hidesVanillaArmour() && !oh.isHideArmorBar()) {
                top = Math.min(top, heartTop - 10);
            }
            if (!PlayerStatsFeature.hidesVanillaHunger() && !oh.isHideHungerBar()) {
                top = Math.min(top, h - 39);
            }
            if ((p.isEyeInFluid(FluidTags.WATER) || p.getAirSupply() < p.getMaxAirSupply())
                    && !PlayerStatsFeature.hidesVanillaAir()) {
                top = Math.min(top, h - 49);
            }
        }
        return top;
    }
}
