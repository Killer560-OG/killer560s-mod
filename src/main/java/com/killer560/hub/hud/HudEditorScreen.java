package com.killer560.hub.hud;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import com.killer560.hub.playerstats.StatLayout;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.killer560.hub.compat.McCompat;

/**
 * SkyHanni-style HUD position editor: every listed {@link HudElement} is drawn at its current
 * position with a bounding box, click-and-drag anywhere on a box to move it, release to save.
 * Scrolling while holding a box (or just hovering one) resizes it.
 * <p>
 * Which elements are draggable (2026-09-30, sharpening the 2026-09-16 "only edit huds that are supposed to
 * be open right now"). killer560: "if a setting is disabled i shouldn't be able to edit its gui... any hud
 * that has been on my screen in the last 10s i should be able to edit." So two independent tests, and an
 * element must pass both:
 * <ul>
 *   <li>{@link HudElement#isEnabledInSettings()} - its own toggle. Off means it is not listed at all, not
 *       even greyed: there is nothing there to place.</li>
 *   <li>{@link HudSeen} - it actually drew within {@link HudSeen#GRACE_MS} of this screen opening. This is
 *       stamped by the element's real render path, so it is the drawing itself and not a guess about it.</li>
 * </ul>
 * An element that is on but has not drawn recently is simply not shown. Until 2026-10-04 it was also named,
 * with the reason, in a dim side panel and counted in the status line; killer560 asked for both to go ("Remove
 * the section in Edit HUDs about things that are on but not moveable currently"). The "Show Unseen" button
 * drops the second test only - so pre-arranging the
 * P5 dragon timers from the hub still works - and never brings back a switched-off element; its state
 * persists in {@link HudConfig}.
 * <p>
 * Both lists are fixed when the screen opens / the toggle flips rather than re-evaluated every frame, so a
 * timer expiring mid-drag can't yank the box out from under the cursor. The grace window is measured against
 * {@link #asOfMs} - the last frame the HUD was really live - rather than against wall time, for the same
 * reason: nothing draws while a screen is open, so a running clock would empty the list ten seconds after he
 * opened this screen, and would empty it before he ever got here if he reached it through the settings menu.
 * <p>
 * Resizing like a desktop window (2026-10-07, killer560: "make it so it is draggable to resize as well as the scroll, so
 * I think it'll act the same as a normal Chrome window or PC window"). Every box's corners resize it: dragging one
 * changes the element's own scale with its shape kept, the opposite corner staying put. A {@link ResizableHudElement}
 * (the stat bars) also has edge handles, and its edges and corners change its width and height separately - the bar's
 * length and thickness - instead of its scale. The cursor turns into the matching resize arrow over a handle (vanilla's
 * {@code requestCursor}, plus GLFW's diagonal standard cursors, which vanilla does not define). Scroll still scales.
 * <p>
 * Snapping (same day: "Those should kind of do a snapping style where they snap to align with things. You can choose
 * what all they will align with."): see {@link HudSnap}. The Snapping button opens the list of what to snap to; Alt
 * held while dragging places freely.
 * <p>
 * Health and Mana Bars' Predefined layout (same day: "predefined spots or fully custom ... look at how Skyblocker has
 * their snap-to-area kind of set up"): its readouts are placed by {@link StatLayout}, not by position. Dragging one shows
 * every area it can go to (the one under the cursor highlighted) and the others making room for it where it would land;
 * dropping it there moves it into that area at that place, Hidden included (the tray at the left). Dropped anywhere else
 * it goes back. No handles: the area decides a bar's length; scroll still scales it. Its own saved position is never
 * written here, so switching back to Custom finds it where it was.
 */
public class HudEditorScreen extends Screen {

    private final Screen parent;
    private final Map<String, int[]> livePositions = new HashMap<>();
    /** Each element's OWN scale (what is saved). Boxes are drawn at this times the global HUD scale - see
     *  {@link #drawScale}. */
    private final Map<String, Float> liveScales = new HashMap<>();
    /** Elements currently draggable, in registry order (topmost last). */
    private final List<HudElement> shown = new ArrayList<>();
    private int disabledCount = 0;
    /** The reference point the grace window is measured from: the last frame the HUD was actually live, which
     *  is not the same as now - see {@link HudSeen#markHudFrame()}. Snapshotted in the constructor rather than
     *  {@link #init()}, which re-runs on every window resize. */
    private final long asOfMs = HudSeen.lastLiveFrameMs();

    /** The element being moved or resized, or null. */
    private String draggingId = null;
    private double dragOffsetX;
    private double dragOffsetY;
    /** Which box edges a resize drags ({@link #LEFT} | {@link #RIGHT} | {@link #TOP} | {@link #BOTTOM}); 0 = a move. */
    private int resizeEdges = 0;
    /** At the press: the drawn box (screen), the cursor, the element's own scale and its resizable size. */
    private int[] pressBox;
    private double pressX;
    private double pressY;
    private float pressScale;
    private int pressW;
    private int pressH;
    /** Guides of the current snap, drawn until the drag ends. */
    private final List<HudSnap.Guide> guides = new ArrayList<>();
    /** While a Predefined stat readout is dragged: the areas it can go to (laid out without it), and where it would
     *  land now (null = nowhere: a drop puts it back). */
    private StatLayout.Layout dragZones;
    private StatLayout.Target dragTarget;

    private SettingsButtonWidget showAllButton;
    /** Whether the snapping options list is open above its button (session-only). */
    private static boolean snapPanelOpen;

    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int TOP = 4;
    static final int BOTTOM = 8;
    /** Pixels outside the box outline that still grab its edge, and inside it. Inside is kept small so a click near
     *  the edge of a small box still moves it (and the HUD editor smoke case's grab 4 px in stays a move). */
    static final int HANDLE_OUT = 3;
    static final int HANDLE_IN = 2;

    private static final int BOX_BG = 0x55FFFFFF;
    private static final int BOX_BG_DRAGGING = 0x8055FF55;
    private static final int BOX_OUTLINE = 0xFFCC6600;
    private static final int HANDLE = 0xFFFF8800;
    private static final int GUIDE = 0xFFFF9933;
    private static final int ZONE_FILL = 0x33FF8800;
    private static final int ZONE_EDGE = 0xCCFF8800;
    private static final int ZONE_FILL_TARGET = 0x55FF8800;
    private static final int ZONE_EDGE_TARGET = 0xFFFF8800;
    private static final int TRAY_FILL = 0x66000000;
    /**
     * Screen pixels the box stands out from the element on every side - the same for every element at every scale.
     *
     * <p>2026-10-07 (killer560: "those split timers the box is way too large for how big they actually are"): the
     * box is the element's own {@code width()/height()} at its drawn scale, grown by this, and the preview is drawn
     * at exactly the position it is drawn at in game. It used to be drawn 2 px right and down of the box's corner,
     * so every preview sat 2 px off its real spot and against the box's left/top edge while the padding all landed
     * on the right and bottom.
     */
    static final int BOX_PAD = 2;
    private static final float SCALE_STEP = 0.1f;
    // No upper bound; only a small positive floor so scale can't hit zero/negative (which would
    // make the element invisible or flip it) - killer560 explicitly wants the old 0.5x-3x range gone.
    private static final float MIN_SCALE = 0.05f;

    /** GLFW's diagonal resize cursors (GLFW 3.4, LWJGL 3.4.1 on both versions); vanilla's {@link CursorTypes} has only
     *  the straight ones. Created on first use, on the render thread; GLFW hands back nothing on a platform without
     *  them and {@link CursorType#createStandardCursor} then returns the 4-way arrow instead. */
    private static CursorType resizeNwse;
    private static CursorType resizeNesw;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Edit HUD Positions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildList();
        addButtons();
    }

    /**
     * Where the button row goes: the bottom edge, except while Health and Mana Bars use the Predefined layout, whose
     * areas ARE the bottom of the screen round the hotbar - there the buttons sat on the side columns and took their
     * clicks (2026-10-07, seen in testkit 407's picture), so the row moves up under the instructions.
     */
    private int buttonY() {
        return StatLayout.predefined() && anyStatListed() ? 58 : this.height - 28;
    }

    private boolean anyStatListed() {
        for (HudElement e : shown) {
            if (StatLayout.manages(e)) {
                return true;
            }
        }
        return false;
    }

    private void addButtons() {
        int by = buttonY();
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 40, by, 80, 20).build());
        showAllButton = SettingsButtonWidget.builder(showAllLabel(), btn -> {
            HudConfig cfg = HudConfig.getInstance();
            cfg.setEditorShowAll(!cfg.isEditorShowAll());
            cfg.save();
            btn.setMessage(showAllLabel());
            rebuildList();
        }).bounds(this.width / 2 + 48, by, 110, 20).build();
        this.addRenderableWidget(showAllButton);

        int snapX = this.width / 2 - 48 - 110;
        HudConfig cfg = HudConfig.getInstance();
        this.addRenderableWidget(SettingsButtonWidget.builder(snapLabel(), btn -> {
            snapPanelOpen = !snapPanelOpen;
            clearWidgets();
            addButtons();
        }).bounds(snapX, by, 110, 20).build());
        if (snapPanelOpen) {
            String[] labels = {"Snapping", "Element Edges", "Element Centres", "Screen Edges", "Screen Centre",
                    "Equal Spacing"};
            Runnable[] flips = {
                    () -> cfg.setEditorSnap(!cfg.isEditorSnap()),
                    () -> cfg.setSnapElementEdges(!cfg.isSnapElementEdges()),
                    () -> cfg.setSnapElementCentres(!cfg.isSnapElementCentres()),
                    () -> cfg.setSnapScreenEdges(!cfg.isSnapScreenEdges()),
                    () -> cfg.setSnapScreenCentre(!cfg.isSnapScreenCentre()),
                    () -> cfg.setSnapEqualSpacing(!cfg.isSnapEqualSpacing())};
            // Opens upward from a bottom row, downward from a top one.
            int y = by > this.height / 2 ? by - 4 - labels.length * 18 : by + 24;
            for (int i = 0; i < labels.length; i++) {
                int k = i;
                this.addRenderableWidget(SettingsButtonWidget.builder(snapOption(k, labels[k]), btn -> {
                    flips[k].run();
                    cfg.save();
                    clearWidgets();
                    addButtons();
                }).bounds(snapX, y + i * 18, 110, 16).build());
            }
        }
    }

    private static Component snapOption(int i, String label) {
        HudConfig c = HudConfig.getInstance();
        boolean on = switch (i) {
            case 0 -> c.isEditorSnap();
            case 1 -> c.isSnapElementEdges();
            case 2 -> c.isSnapElementCentres();
            case 3 -> c.isSnapScreenEdges();
            case 4 -> c.isSnapScreenCentre();
            default -> c.isSnapEqualSpacing();
        };
        // The five targets read grey while the master switch is off: they are kept, but nothing snaps.
        String name = i > 0 && !c.isEditorSnap() ? "§7" + label : label;
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Component snapLabel() {
        return Component.literal("Snapping: " + (HudConfig.getInstance().isEditorSnap() ? "§aON" : "§cOFF")
                + (snapPanelOpen ? " §7▼" : " §7▲"));
    }

    /** Reads "Show Unseen" rather than the older "Show All": a switched-off element is never listed either
     *  way now, so calling it "all" would be a lie. The persisted key stays {@code editorShowAll}. */
    private static Component showAllLabel() {
        return Component.literal("Show Unseen: " + (HudConfig.getInstance().isEditorShowAll() ? "§aON" : "§cOFF"));
    }

    /** Recomputes the draggable list and (re)snapshots the draggable ones' positions/scales. Positions come through
     *  {@link HudElementRegistry#resolvePosition}, so a never-moved element starts inside the screen. */
    private void rebuildList() {
        draggingId = null;
        resizeEdges = 0;
        guides.clear();
        endAreaDrag();
        shown.clear();
        livePositions.clear();
        liveScales.clear();
        boolean showUnseen = HudConfig.getInstance().isEditorShowAll();
        int disabled = 0;
        for (HudElement element : HudElementRegistry.all()) {
            if (!HudElementRegistry.isEnabledInSettings(element)) {
                // killer560: "if a setting is disabled i shouldn't be able to edit its gui." Not even with
                // Show Unseen on - that button is about where you are, not about what is switched on.
                disabled++;
                continue;
            }
            // A Predefined readout in Hidden never draws, so it can never be "seen"; it is listed in the tray regardless,
            // or it could never be dragged back out.
            boolean hiddenReadout = isHiddenReadout(element);
            if (!showUnseen && !hiddenReadout && !HudSeen.drawnWithinGrace(element.id(), asOfMs)) {
                continue;
            }
            shown.add(element);
            int[] pos = HudElementRegistry.resolvePosition(element);
            livePositions.put(element.id(), pos);
            liveScales.put(element.id(), HudElementRegistry.elementScale(element));
        }
        disabledCount = disabled;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        StatLayout.newFrame();
        graphics.fill(0, 0, this.width, this.height, 0x33000000);
        graphics.text(this.font, "§eDrag a box to move it, drag a corner or edge to resize it, scroll to scale it.",
                8, 8, 0xFFFFFFFF);
        graphics.text(this.font, listingLine(), 8, 20, 0xFFFFFFFF);
        graphics.text(this.font, "§7Hold Alt while dragging to place it without snapping.", 8, 32, 0xFFFFFFFF);

        // Predefined stat readouts go where their layout puts them, every frame (it moves as others are dragged).
        boolean anyAnchored = false;
        for (HudElement e : shown) {
            if (StatLayout.manages(e)) {
                anyAnchored = true;
                if (!e.id().equals(draggingId)) {
                    livePositions.put(e.id(), HudElementRegistry.resolvePosition(e));
                }
            }
        }
        if (anyAnchored) {
            graphics.text(this.font, "§7Health and Mana Bars (Predefined): drag a bar onto an area; Hidden hides it.",
                    8, 44, 0xFFFFFFFF);
            drawAreas(graphics);
        }

        // What the cursor is over: a handle of some box, or the inside of one (topmost first).
        HudElement hover = null;
        int hoverEdges = 0;
        if (draggingId == null) {
            for (int i = shown.size() - 1; i >= 0 && hover == null; i--) {
                HudElement e = shown.get(i);
                int edges = handleAt(e, mouseX, mouseY);
                if (edges != 0 || insideBox(e, mouseX, mouseY)) {
                    hover = e;
                    hoverEdges = edges;
                }
            }
        }

        for (HudElement element : shown) {
            int[] pos = livePositions.get(element.id());
            int x = pos[0];
            int y = pos[1];
            float own = liveScales.get(element.id());
            float scale = drawScale(element.id());
            int scaledW = scaledWidth(element, scale);
            int scaledH = scaledHeight(element, scale);
            boolean dragging = element.id().equals(draggingId);

            graphics.fill(x - BOX_PAD, y - BOX_PAD, x + scaledW + BOX_PAD, y + scaledH + BOX_PAD,
                    dragging ? BOX_BG_DRAGGING : BOX_BG);
            graphics.outline(x - BOX_PAD, y - BOX_PAD, scaledW + 2 * BOX_PAD, scaledH + 2 * BOX_PAD, BOX_OUTLINE);
            // The name above the box is cut to the box's own width so side-by-side boxes' names never run into
            // each other ("Health Bar (1.0x, 100x8)" over "Mana Bar"); the one being hovered or dragged shows in full.
            boolean anchored = StatLayout.manages(element);
            if (anchored && !dragging && element != hover) {
                // Laid-out readouts sit a few pixels apart in rows and columns, so a name over each would cover the
                // next row; the area says what they are, and hovering one names it.
                graphics.pose().pushMatrix();
                try {
                    graphics.pose().translate(x, y);
                    graphics.pose().scale(scale, scale);
                    element.render(graphics, 0, 0);
                } catch (RuntimeException e) {
                    // as below
                } finally {
                    graphics.pose().popMatrix();
                }
                continue;
            }
            String label = element.displayName() + (anchored ? String.format(" (%.1fx)", own) : sizeNote(element, own));
            int labelRoom = Math.max(scaledW + 2 * BOX_PAD, 24);
            if (!dragging && element != hover && this.font.width(label) > labelRoom) {
                label = this.font.plainSubstrByWidth(label, labelRoom - this.font.width("...")) + "...";
            }
            graphics.text(this.font, label, x, y - 10 - BOX_PAD, 0xFFFFFFFF);

            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(x, y);
                graphics.pose().scale(scale, scale);
                element.render(graphics, 0, 0);
            } catch (RuntimeException e) {
                // One element's broken preview must not take the whole editor down with it.
            } finally {
                graphics.pose().popMatrix();
            }
            if (dragging || element == hover) {
                drawHandles(graphics, element, x, y, scaledW, scaledH);
            }
        }

        for (HudSnap.Guide g : guides) {
            drawGuide(graphics, g);
        }

        int edges = draggingId != null ? resizeEdges : hoverEdges;
        if (draggingId != null || hover != null) {
            graphics.requestCursor(cursorFor(edges));
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** The Hidden tray, always; while a Predefined readout is dragged, every area it can go to, the target strongest. */
    private void drawAreas(GuiGraphicsExtractor g) {
        StatLayout.Layout now = StatLayout.current();
        StatLayout.Zone tray = now == null ? null : now.zone(StatLayout.Area.HIDDEN);
        if (dragZones != null) {
            for (StatLayout.Zone z : dragZones.zones) {
                boolean target = dragTarget != null && dragTarget.area() == z.area();
                g.fill(z.x0(), z.y0(), z.x1(), z.y1(), target ? ZONE_FILL_TARGET : ZONE_FILL);
                g.outline(z.x0(), z.y0(), z.x1() - z.x0(), z.y1() - z.y0(), target ? ZONE_EDGE_TARGET : ZONE_EDGE);
                if (z.area() == StatLayout.Area.HIDDEN) {
                    tray = null; // drawn here already
                    g.text(this.font, "§6Hidden", z.x0() + 3, z.y0() + 2, 0xFFFFFFFF);
                }
            }
            String where = dragTarget == null ? "§7Not on an area: it goes back" : "§6" + dragTarget.area().label;
            int[] p = livePositions.get(draggingId);
            if (p != null) {
                g.text(this.font, where, p[0], Math.max(0, p[1] - 22), 0xFFFFFFFF);
            }
        }
        if (tray != null) {
            g.fill(tray.x0(), tray.y0(), tray.x1(), tray.y1(), TRAY_FILL);
            g.outline(tray.x0(), tray.y0(), tray.x1() - tray.x0(), tray.y1() - tray.y0(), ZONE_EDGE);
            g.text(this.font, "§6Hidden", tray.x0() + 3, tray.y0() + 2, 0xFFFFFFFF);
        }
    }

    /** Whether {@code element} is a Predefined stat readout sitting in Hidden. */
    private static boolean isHiddenReadout(HudElement element) {
        if (!StatLayout.manages(element)) {
            return false;
        }
        var r = StatLayout.readout(element.id());
        return r != null && PlayerStatsConfig.getInstance().getArea(r) == StatLayout.Area.HIDDEN;
    }

    private void endAreaDrag() {
        dragZones = null;
        dragTarget = null;
        StatLayout.clearPreview();
    }

    /** "(1.0x)", and for a resizable element its size in its own units too while it can be resized: "(1.0x, 100x8)". */
    private static String sizeNote(HudElement element, float own) {
        if (element instanceof ResizableHudElement r) {
            try {
                return String.format(" (%.1fx, %dx%d)", own, r.resizeWidth(), r.resizeHeight());
            } catch (RuntimeException e) {
                // fall through to the plain scale
            }
        }
        return String.format(" (%.1fx)", own);
    }

    /** The cursor for a handle: the matching resize arrow, or the 4-way move arrow inside a box. */
    static CursorType cursorFor(int edges) {
        boolean h = (edges & (LEFT | RIGHT)) != 0;
        boolean v = (edges & (TOP | BOTTOM)) != 0;
        if (h && v) {
            boolean nwse = (edges & LEFT) != 0 == ((edges & TOP) != 0);
            if (nwse) {
                if (resizeNwse == null) {
                    resizeNwse = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "resize_nwse",
                            CursorTypes.RESIZE_ALL);
                }
                return resizeNwse;
            }
            if (resizeNesw == null) {
                resizeNesw = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "resize_nesw",
                        CursorTypes.RESIZE_ALL);
            }
            return resizeNesw;
        }
        if (h) {
            return CursorTypes.RESIZE_EW;
        }
        if (v) {
            return CursorTypes.RESIZE_NS;
        }
        return CursorTypes.RESIZE_ALL;
    }

    /** Small squares on the corners (and, for a resizable element, the edge midpoints) of the padded box. */
    private static void drawHandles(GuiGraphicsExtractor g, HudElement element, int x, int y, int w, int h) {
        int x0 = x - BOX_PAD;
        int y0 = y - BOX_PAD;
        int x1 = x + w + BOX_PAD;
        int y1 = y + h + BOX_PAD;
        int[][] at = element instanceof ResizableHudElement
                ? new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}, {(x0 + x1) / 2, y0}, {(x0 + x1) / 2, y1},
                {x0, (y0 + y1) / 2}, {x1, (y0 + y1) / 2}}
                : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
        for (int[] p : at) {
            g.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, HANDLE);
        }
    }

    private void drawGuide(GuiGraphicsExtractor g, HudSnap.Guide guide) {
        int at = (int) Math.floor(guide.at());
        if (guide.spacing()) {
            int a = (int) Math.round(Math.min(guide.from(), guide.to()));
            int b = (int) Math.round(Math.max(guide.from(), guide.to()));
            if (guide.vertical()) {
                g.fill(at, a, at + 1, b, GUIDE);
                g.fill(at - 2, a, at + 3, a + 1, GUIDE);
                g.fill(at - 2, b - 1, at + 3, b, GUIDE);
            } else {
                g.fill(a, at, b, at + 1, GUIDE);
                g.fill(a, at - 2, a + 1, at + 3, GUIDE);
                g.fill(b - 1, at - 2, b, at + 3, GUIDE);
            }
            return;
        }
        // A line on the screen's far edge would be drawn just off it; pull it in by one.
        if (guide.vertical()) {
            int x = Math.min(at, this.width - 1);
            g.fill(x, 0, x + 1, this.height, GUIDE);
        } else {
            int y = Math.min(at, this.height - 1);
            g.fill(0, y, this.width, y + 1, GUIDE);
        }
    }

    /** One line saying what is editable and, separately, what is switched off in settings. */
    private String listingLine() {
        StringBuilder line = new StringBuilder("§7" + shown.size() + " editable");
        if (disabledCount > 0) {
            line.append("§7, ").append(disabledCount).append(" turned off in settings (not listed)");
        }
        return line.append("§7.").toString();
    }

    /** width()/height() run feature code; a throw here would otherwise kill the editor's render/click path. */
    private static int scaledWidth(HudElement element, float scale) {
        try {
            return Math.round(element.width() * scale);
        } catch (RuntimeException e) {
            return Math.round(20 * scale);
        }
    }

    private static int scaledHeight(HudElement element, float scale) {
        try {
            return Math.round(element.height() * scale);
        } catch (RuntimeException e) {
            return Math.round(10 * scale);
        }
    }

    /** The scale an element is drawn at in-game: its own scale times the global HUD scale times Auto Scale, the same
     *  product as {@link HudElementRegistry#resolveScale}. This screen is deliberately NOT auto-scaled itself (see
     *  {@link AutoScale#scalesScreen}), so its coordinates are the in-game HUD's and a box here is the drawn box. */
    private float drawScale(String id) {
        return liveScales.get(id) * HudConfig.getInstance().getEffectiveGlobalScale();
    }

    /** The element's drawn box on screen (no padding). */
    private HudSnap.Box box(HudElement element) {
        int[] pos = livePositions.get(element.id());
        float scale = drawScale(element.id());
        return new HudSnap.Box(pos[0], pos[1], pos[0] + scaledWidth(element, scale),
                pos[1] + scaledHeight(element, scale));
    }

    private boolean insideBox(HudElement element, double mx, double my) {
        HudSnap.Box b = box(element);
        return mx >= b.x0() - BOX_PAD && mx <= b.x1() + BOX_PAD && my >= b.y0() - BOX_PAD && my <= b.y1() + BOX_PAD;
    }

    /**
     * Which edges of {@code element}'s padded box a press at (mx, my) would drag: a corner (two bits) for any element,
     * an edge (one bit) only for a {@link ResizableHudElement}; 0 when not on a handle. The band runs
     * {@link #HANDLE_OUT} outside the outline to {@link #HANDLE_IN} inside it.
     */
    int handleAt(HudElement element, double mx, double my) {
        if (StatLayout.manages(element)) {
            return 0; // its area decides its length; it only moves (between areas) and scrolls (scale)
        }
        HudSnap.Box b = box(element);
        int x0 = b.x0() - BOX_PAD;
        int y0 = b.y0() - BOX_PAD;
        int x1 = b.x1() + BOX_PAD;
        int y1 = b.y1() + BOX_PAD;
        if (mx < x0 - HANDLE_OUT || mx > x1 + HANDLE_OUT || my < y0 - HANDLE_OUT || my > y1 + HANDLE_OUT) {
            return 0;
        }
        boolean nearL = mx <= x0 + HANDLE_IN;
        boolean nearR = mx >= x1 - HANDLE_IN;
        boolean nearT = my <= y0 + HANDLE_IN;
        boolean nearB = my >= y1 - HANDLE_IN;
        if (nearL && nearR) {
            // A box narrower than the two bands: the nearer edge.
            nearL = Math.abs(mx - x0) <= Math.abs(mx - x1);
            nearR = !nearL;
        }
        if (nearT && nearB) {
            nearT = Math.abs(my - y0) <= Math.abs(my - y1);
            nearB = !nearT;
        }
        int h = nearL ? LEFT : nearR ? RIGHT : 0;
        int v = nearT ? TOP : nearB ? BOTTOM : 0;
        if (h != 0 && v != 0) {
            return h | v;
        }
        if (element instanceof ResizableHudElement && (h | v) != 0) {
            return h | v;
        }
        return 0;
    }

    /** Topmost listed element whose scaled box contains (mx, my), or null. */
    private HudElement elementAt(double mx, double my) {
        for (int i = shown.size() - 1; i >= 0; i--) {
            HudElement element = shown.get(i);
            if (insideBox(element, mx, my)) {
                return element;
            }
        }
        return null;
    }

    private HudElement byId(String id) {
        for (HudElement e : shown) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** The other listed elements' drawn boxes, what a drag snaps to. */
    private List<HudSnap.Box> otherBoxes(String id) {
        List<HudSnap.Box> out = new ArrayList<>();
        for (HudElement e : shown) {
            if (!e.id().equals(id)) {
                out.add(box(e));
            }
        }
        return out;
    }

    /** Snapping is on and Alt is not held (by the event's modifiers or the keyboard itself, so Alt pressed during the
     *  drag counts too). */
    private boolean snapping(MouseButtonEvent event) {
        if (!HudConfig.getInstance().isEditorSnap()) {
            return false;
        }
        if (event != null && event.hasAltDown()) {
            return false;
        }
        var window = this.minecraft == null ? null : this.minecraft.getWindow();
        return !(KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Buttons first, so a box that happens to sit under Done / Show All doesn't swallow the click.
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == 0) {
            for (int i = shown.size() - 1; i >= 0; i--) {
                HudElement element = shown.get(i);
                int edges = handleAt(element, event.x(), event.y());
                if (edges == 0 && !insideBox(element, event.x(), event.y())) {
                    continue;
                }
                int[] pos = livePositions.get(element.id());
                draggingId = element.id();
                resizeEdges = edges;
                dragOffsetX = event.x() - pos[0];
                dragOffsetY = event.y() - pos[1];
                HudSnap.Box b = box(element);
                pressBox = new int[]{b.x0(), b.y0(), b.x1(), b.y1()};
                pressX = event.x();
                pressY = event.y();
                pressScale = liveScales.get(element.id());
                if (element instanceof ResizableHudElement r) {
                    pressW = r.resizeWidth();
                    pressH = r.resizeHeight();
                }
                guides.clear();
                if (StatLayout.manages(element)) {
                    var readout = StatLayout.readout(element.id());
                    dragZones = StatLayout.zonesWithout(readout);
                    dragTarget = StatLayout.targetAt(dragZones, event.x(), event.y());
                    StatLayout.setPreview(readout, dragTarget == null ? null : dragTarget.area(),
                            dragTarget == null ? 0 : dragTarget.index());
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingId != null) {
            HudElement element = byId(draggingId);
            guides.clear();
            if (element == null) {
                return true;
            }
            if (resizeEdges != 0) {
                resize(element, event);
                return true;
            }
            int newX = (int) Math.round(event.x() - dragOffsetX);
            int newY = (int) Math.round(event.y() - dragOffsetY);
            if (dragZones != null) {
                // A Predefined readout follows the mouse freely; where it lands is the area under the mouse.
                dragTarget = StatLayout.targetAt(dragZones, event.x(), event.y());
                StatLayout.setPreview(StatLayout.readout(draggingId), dragTarget == null ? null : dragTarget.area(),
                        dragTarget == null ? 0 : dragTarget.index());
                livePositions.put(draggingId, new int[]{newX, newY});
                return true;
            }
            if (snapping(event)) {
                HudSnap.Box b = box(element);
                HudSnap.Moved m = HudSnap.move(new HudSnap.Box(newX, newY, newX + b.w(), newY + b.h()),
                        otherBoxes(draggingId), this.width, this.height, HudSnap.Options.fromConfig());
                newX = m.x();
                newY = m.y();
                guides.addAll(m.guides());
            }
            livePositions.put(draggingId, new int[]{newX, newY});
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    /**
     * Applies a resize drag: the dragged edges follow the cursor (snapped), the opposite ones stay where they were at
     * the press. A {@link ResizableHudElement} takes the new width/height in its own units at its fixed scale; any
     * other element takes a new own scale from the corner, shape kept.
     */
    private void resize(HudElement element, MouseButtonEvent event) {
        double dx = event.x() - pressX;
        double dy = event.y() - pressY;
        int x0 = pressBox[0];
        int y0 = pressBox[1];
        int x1 = pressBox[2];
        int y1 = pressBox[3];
        boolean snap = snapping(event);
        HudSnap.Options o = HudSnap.Options.fromConfig();
        List<HudSnap.Box> others = snap ? otherBoxes(draggingId) : List.of();
        boolean snappedX = false;
        boolean snappedY = false;
        if ((resizeEdges & LEFT) != 0) {
            int want = (int) Math.round(x0 + dx);
            x0 = snap ? HudSnap.edge(true, want, others, this.width, o, guides) : want;
            snappedX = x0 != want;
        }
        if ((resizeEdges & RIGHT) != 0) {
            int want = (int) Math.round(x1 + dx);
            x1 = snap ? HudSnap.edge(true, want, others, this.width, o, guides) : want;
            snappedX = x1 != want;
        }
        if ((resizeEdges & TOP) != 0) {
            int want = (int) Math.round(y0 + dy);
            y0 = snap ? HudSnap.edge(false, want, others, this.height, o, guides) : want;
            snappedY = y0 != want;
        }
        if ((resizeEdges & BOTTOM) != 0) {
            int want = (int) Math.round(y1 + dy);
            y1 = snap ? HudSnap.edge(false, want, others, this.height, o, guides) : want;
            snappedY = y1 != want;
        }
        int wantW = Math.max(1, x1 - x0);
        int wantH = Math.max(1, y1 - y0);
        float scale = drawScale(draggingId);
        if (element instanceof ResizableHudElement r) {
            int w = (resizeEdges & (LEFT | RIGHT)) != 0 ? Math.round(wantW / scale) : pressW;
            int h = pressH;
            if ((resizeEdges & (TOP | BOTTOM)) != 0) {
                // The box can be taller than the resizable height (a bar with its number on it is at least a text row
                // tall), so the dragged amount changes the thickness by the same number of units.
                int boxH = pressBox[3] - pressBox[1];
                h = Math.round(pressH + (wantH - boxH) / scale);
            }
            r.resizeTo(w, h);
        } else {
            // Uniform: project the wanted size onto the box's own diagonal, so the corner tracks the cursor with the
            // shape kept; when one axis snapped, that axis decides, so the snapped edge lands exactly.
            int w0 = Math.max(1, pressBox[2] - pressBox[0]);
            int h0 = Math.max(1, pressBox[3] - pressBox[1]);
            double factor;
            if (snappedX) {
                factor = wantW / (double) w0;
            } else if (snappedY) {
                factor = wantH / (double) h0;
            } else {
                factor = (wantW * (double) w0 + wantH * (double) h0) / ((double) w0 * w0 + (double) h0 * h0);
                guides.clear();
            }
            if (snappedX) {
                // The x edge decided the size; the y edge lands wherever the shape puts it, so its guide would lie.
                guides.removeIf(g -> !g.vertical());
            }
            float own = (float) Math.max(MIN_SCALE, pressScale * factor);
            liveScales.put(draggingId, own);
        }
        float drawn = drawScale(draggingId);
        int w = scaledWidth(element, drawn);
        int h = scaledHeight(element, drawn);
        int x = (resizeEdges & LEFT) != 0 ? pressBox[2] - w : pressBox[0];
        int y = (resizeEdges & TOP) != 0 ? pressBox[3] - h : pressBox[1];
        livePositions.put(draggingId, new int[]{x, y});
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingId != null && dragZones != null) {
            // A Predefined readout: into the area it was dropped on, at that place. Its own position is not touched.
            var readout = StatLayout.readout(draggingId);
            if (dragTarget != null && readout != null) {
                PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
                cfg.moveTo(readout, dragTarget.area(), StatLayout.beforeOf(dragZones, dragTarget));
                cfg.save();
            }
            endAreaDrag();
            HudElement e = byId(draggingId);
            if (e != null) {
                livePositions.put(draggingId, HudElementRegistry.resolvePosition(e));
            }
            draggingId = null;
            resizeEdges = 0;
            guides.clear();
            return true;
        }
        if (draggingId != null) {
            int[] pos = livePositions.get(draggingId);
            // Saved in baseline units (Auto Scale): resolvePosition multiplies back, so the box draws where it dropped.
            int[] saved = HudElementRegistry.toSaved(pos[0], pos[1]);
            HudConfig.getInstance().setPosition(draggingId, saved[0], saved[1]);
            HudConfig.getInstance().setScale(draggingId, liveScales.get(draggingId));
            HudConfig.getInstance().save();
            if (resizeEdges != 0 && byId(draggingId) instanceof ResizableHudElement r) {
                r.saveSize();
            }
            draggingId = null;
            resizeEdges = 0;
            guides.clear();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Prefer whatever's actively being dragged, otherwise resize whatever's under the cursor.
        String targetId = draggingId != null ? draggingId : (elementAt(mouseX, mouseY) != null ? elementAt(mouseX, mouseY).id() : null);
        HudElement scrolled = targetId == null ? null : byId(targetId);
        if (scrolled != null && scrollY != 0 && StatLayout.manages(scrolled)) {
            // Predefined Health and Mana Bars share one scale (2026-10-07): scrolling any of them scales them all.
            PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
            ps.setPredefinedScale(Math.max(MIN_SCALE, ps.getPredefinedScale() + (scrollY > 0 ? SCALE_STEP : -SCALE_STEP)));
            ps.save();
            for (HudElement e : shown) {
                if (StatLayout.manages(e)) {
                    liveScales.put(e.id(), HudElementRegistry.elementScale(e));
                }
            }
            return true;
        }
        if (targetId != null && scrollY != 0) {
            float newScale = liveScales.get(targetId) + (scrollY > 0 ? SCALE_STEP : -SCALE_STEP);
            newScale = Math.max(MIN_SCALE, newScale);
            liveScales.put(targetId, newScale);
            HudConfig.getInstance().setScale(targetId, newScale);
            HudConfig.getInstance().save();
            if (targetId.equals(draggingId)) {
                // A scroll during a corner drag starts that drag over from here.
                HudElement e = byId(targetId);
                if (e != null) {
                    HudSnap.Box b = box(e);
                    pressBox = new int[]{b.x0(), b.y0(), b.x1(), b.y1()};
                    pressScale = newScale;
                    pressX = mouseX;
                    pressY = mouseY;
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        endAreaDrag();
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public void removed() {
        endAreaDrag();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
