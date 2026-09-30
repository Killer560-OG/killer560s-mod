package com.killer560.hub.hud;

import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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
 * An element that is on but has not drawn recently is named in a dim side panel with the reason, and is not
 * clickable, because "you haven't been anywhere that shows this" and "you turned this off" look identical if
 * both just vanish. The "Show Unseen" button drops the second test only - so pre-arranging the
 * P5 dragon timers from the hub still works - and never brings back a switched-off element; its state
 * persists in {@link HudConfig}.
 * <p>
 * Both lists are fixed when the screen opens / the toggle flips rather than re-evaluated every frame, so a
 * timer expiring mid-drag can't yank the box out from under the cursor. The grace window is measured against
 * {@link #asOfMs} - the last frame the HUD was really live - rather than against wall time, for the same
 * reason: nothing draws while a screen is open, so a running clock would empty the list ten seconds after he
 * opened this screen, and would empty it before he ever got here if he reached it through the settings menu.
 */
public class HudEditorScreen extends Screen {

    private final Screen parent;
    private final Map<String, int[]> livePositions = new HashMap<>();
    private final Map<String, Float> liveScales = new HashMap<>();
    /** Elements currently draggable, in registry order (topmost last). */
    private final List<HudElement> shown = new ArrayList<>();
    /** Enabled, but not drawn within the grace window - listed in the dim side panel, not draggable. */
    private final List<HudElement> unseen = new ArrayList<>();
    private int disabledCount = 0;
    /** The reference point the grace window is measured from: the last frame the HUD was actually live, which
     *  is not the same as now - see {@link HudSeen#markHudFrame()}. Snapshotted in the constructor rather than
     *  {@link #init()}, which re-runs on every window resize. */
    private final long asOfMs = HudSeen.lastLiveFrameMs();

    private String draggingId = null;
    private double dragOffsetX;
    private double dragOffsetY;

    private SettingsButtonWidget showAllButton;

    private static final int BOX_BG = 0x55FFFFFF;
    private static final int BOX_BG_DRAGGING = 0x8055FF55;
    private static final int BOX_OUTLINE = 0xFFCC6600;
    /** Dimmed pair for an enabled-but-unseen element: same orange family, far enough back that it reads as
     *  "listed, not available" instead of competing with the boxes he can actually grab. */
    private static final int BOX_BG_UNSEEN = 0x22FFFFFF;
    private static final int BOX_OUTLINE_UNSEEN = 0x66CC6600;
    private static final float SCALE_STEP = 0.1f;
    // No upper bound; only a small positive floor so scale can't hit zero/negative (which would
    // make the element invisible or flip it) - killer560 explicitly wants the old 0.5x-3x range gone.
    private static final float MIN_SCALE = 0.05f;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Edit HUD Positions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildList();

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
        showAllButton = SettingsButtonWidget.builder(showAllLabel(), btn -> {
            HudConfig cfg = HudConfig.getInstance();
            cfg.setEditorShowAll(!cfg.isEditorShowAll());
            cfg.save();
            btn.setMessage(showAllLabel());
            rebuildList();
        }).bounds(this.width / 2 + 48, this.height - 28, 110, 20).build();
        this.addRenderableWidget(showAllButton);
    }

    /** Reads "Show Unseen" rather than the older "Show All": a switched-off element is never listed either
     *  way now, so calling it "all" would be a lie. The persisted key stays {@code editorShowAll}. */
    private static Component showAllLabel() {
        return Component.literal("Show Unseen: " + (HudConfig.getInstance().isEditorShowAll() ? "§aON" : "§cOFF"));
    }

    /** Recomputes both lists and (re)snapshots the draggable ones' positions/scales. Positions come through
     *  {@link HudElementRegistry#resolvePosition}, so a never-moved element starts inside the screen. */
    private void rebuildList() {
        draggingId = null;
        shown.clear();
        unseen.clear();
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
            if (!showUnseen && !HudSeen.drawnWithinGrace(element.id(), asOfMs)) {
                unseen.add(element);
                continue;
            }
            shown.add(element);
            int[] pos = HudElementRegistry.resolvePosition(element);
            livePositions.put(element.id(), pos);
            liveScales.put(element.id(), HudElementRegistry.resolveScale(element));
        }
        disabledCount = disabled;
    }

    /** Why a listed-but-unseen element can't be dragged. "Never drawn" and "drew a while ago" are different
     *  answers - the first usually means he has not been where it shows, the second that he waited too long. */
    private String unseenReason(HudElement element) {
        if (!HudSeen.everDrawn(element.id())) {
            return "not yet this session";
        }
        return (HudSeen.msSince(element.id(), asOfMs) / 1000L) + "s ago";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x33000000);
        graphics.text(this.font, "§eDrag a box to reposition it, scroll to resize it. Click Done when finished.",
                8, 8, 0xFFFFFFFF);
        graphics.text(this.font, listingLine(), 8, 20, 0xFFFFFFFF);
        drawUnseenList(graphics);

        for (HudElement element : shown) {
            int[] pos = livePositions.get(element.id());
            int x = pos[0];
            int y = pos[1];
            float scale = liveScales.get(element.id());
            int scaledW = scaledWidth(element, scale);
            int scaledH = scaledHeight(element, scale);
            boolean dragging = element.id().equals(draggingId);

            graphics.fill(x, y, x + scaledW, y + scaledH, dragging ? BOX_BG_DRAGGING : BOX_BG);
            graphics.outline(x, y, scaledW, scaledH, BOX_OUTLINE);
            graphics.text(this.font, element.displayName() + String.format(" (%.1fx)", scale), x + 2, y - 10, 0xFFFFFFFF);

            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(x + 2, y + 2);
                graphics.pose().scale(scale, scale);
                element.render(graphics, 0, 0);
            } catch (RuntimeException e) {
                // One element's broken preview must not take the whole editor down with it.
            } finally {
                graphics.pose().popMatrix();
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The enabled-but-unseen elements, as a dim panel down the right-hand side.
     * <p>
     * Deliberately a list and not a dimmed box at each element's own position: in the case killer560
     * described - standing around with only the clock on screen - nearly every element is unseen, and thirty
     * overlapping grey boxes at their saved positions would read as a broken editor rather than as an
     * explanation. A named row with a reason is the thing he can actually act on ("oh, that one is on, I just
     * haven't been in a dungeon"), which is the whole point of listing them instead of hiding them.
     */
    private void drawUnseenList(GuiGraphicsExtractor graphics) {
        if (unseen.isEmpty()) {
            return;
        }
        int top = 40;
        int rowHeight = 10;
        int maxRows = Math.max(1, (this.height - top - 40) / rowHeight);
        int panelW = 210;
        int rows = Math.min(unseen.size(), maxRows);
        int overflow = unseen.size() - rows;
        int panelH = (rows + (overflow > 0 ? 1 : 0)) * rowHeight + 14;
        int left = this.width - panelW - 8;
        graphics.fill(left, top, left + panelW, top + panelH, BOX_BG_UNSEEN);
        graphics.outline(left, top, panelW, panelH, BOX_OUTLINE_UNSEEN);
        graphics.text(this.font, "§7On, but not seen in the last " + (HudSeen.GRACE_MS / 1000L) + "s:",
                left + 4, top + 4, 0xFFFFFFFF);
        int y = top + 4 + rowHeight + 2;
        for (int i = 0; i < rows; i++) {
            HudElement element = unseen.get(i);
            graphics.text(this.font, "§8" + element.displayName() + " §8- " + unseenReason(element),
                    left + 4, y, 0xFFFFFFFF);
            y += rowHeight;
        }
        if (overflow > 0) {
            graphics.text(this.font, "§8+" + overflow + " more", left + 4, y, 0xFFFFFFFF);
        }
    }

    /** One line saying what is editable and, separately, what is on-but-unseen and what is switched off -
     *  three different reasons an element isn't under the cursor, and he can't act on any of them if they
     *  all read as "missing". */
    private String listingLine() {
        StringBuilder line = new StringBuilder("§7" + shown.size() + " editable");
        if (!unseen.isEmpty()) {
            line.append("§7, §8").append(unseen.size()).append(" on but not seen recently");
        }
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

    /** Topmost listed element whose scaled box contains (mx, my), or null. */
    private HudElement elementAt(double mx, double my) {
        for (int i = shown.size() - 1; i >= 0; i--) {
            HudElement element = shown.get(i);
            int[] pos = livePositions.get(element.id());
            float scale = liveScales.get(element.id());
            int scaledW = scaledWidth(element, scale);
            int scaledH = scaledHeight(element, scale);
            if (mx >= pos[0] && mx <= pos[0] + scaledW && my >= pos[1] && my <= pos[1] + scaledH) {
                return element;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Buttons first, so a box that happens to sit under Done / Show All doesn't swallow the click.
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == 0) {
            HudElement element = elementAt(event.x(), event.y());
            if (element != null) {
                int[] pos = livePositions.get(element.id());
                draggingId = element.id();
                dragOffsetX = event.x() - pos[0];
                dragOffsetY = event.y() - pos[1];
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingId != null) {
            int newX = (int) Math.round(event.x() - dragOffsetX);
            int newY = (int) Math.round(event.y() - dragOffsetY);
            livePositions.put(draggingId, new int[]{newX, newY});
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingId != null) {
            int[] pos = livePositions.get(draggingId);
            HudConfig.getInstance().setPosition(draggingId, pos[0], pos[1]);
            HudConfig.getInstance().setScale(draggingId, liveScales.get(draggingId));
            HudConfig.getInstance().save();
            draggingId = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Prefer whatever's actively being dragged, otherwise resize whatever's under the cursor.
        String targetId = draggingId != null ? draggingId : (elementAt(mouseX, mouseY) != null ? elementAt(mouseX, mouseY).id() : null);
        if (targetId != null && scrollY != 0) {
            float newScale = liveScales.get(targetId) + (scrollY > 0 ? SCALE_STEP : -SCALE_STEP);
            newScale = Math.max(MIN_SCALE, newScale);
            liveScales.put(targetId, newScale);
            HudConfig.getInstance().setScale(targetId, newScale);
            HudConfig.getInstance().save();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
