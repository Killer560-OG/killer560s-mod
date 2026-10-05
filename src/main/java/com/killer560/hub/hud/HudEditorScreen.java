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

    private String draggingId = null;
    private double dragOffsetX;
    private double dragOffsetY;

    private SettingsButtonWidget showAllButton;

    private static final int BOX_BG = 0x55FFFFFF;
    private static final int BOX_BG_DRAGGING = 0x8055FF55;
    private static final int BOX_OUTLINE = 0xFFCC6600;
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

    /** Recomputes the draggable list and (re)snapshots the draggable ones' positions/scales. Positions come through
     *  {@link HudElementRegistry#resolvePosition}, so a never-moved element starts inside the screen. */
    private void rebuildList() {
        draggingId = null;
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
            if (!showUnseen && !HudSeen.drawnWithinGrace(element.id(), asOfMs)) {
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
        graphics.fill(0, 0, this.width, this.height, 0x33000000);
        graphics.text(this.font, "§eDrag a box to reposition it, scroll to resize it. Click Done when finished.",
                8, 8, 0xFFFFFFFF);
        graphics.text(this.font, listingLine(), 8, 20, 0xFFFFFFFF);

        for (HudElement element : shown) {
            int[] pos = livePositions.get(element.id());
            int x = pos[0];
            int y = pos[1];
            float own = liveScales.get(element.id());
            float scale = drawScale(element.id());
            int scaledW = scaledWidth(element, scale);
            int scaledH = scaledHeight(element, scale);
            boolean dragging = element.id().equals(draggingId);

            graphics.fill(x, y, x + scaledW, y + scaledH, dragging ? BOX_BG_DRAGGING : BOX_BG);
            graphics.outline(x, y, scaledW, scaledH, BOX_OUTLINE);
            graphics.text(this.font, element.displayName() + String.format(" (%.1fx)", own), x + 2, y - 10, 0xFFFFFFFF);

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

    /** Topmost listed element whose scaled box contains (mx, my), or null. */
    private HudElement elementAt(double mx, double my) {
        for (int i = shown.size() - 1; i >= 0; i--) {
            HudElement element = shown.get(i);
            int[] pos = livePositions.get(element.id());
            float scale = drawScale(element.id());
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
            // Saved in baseline units (Auto Scale): resolvePosition multiplies back, so the box draws where it dropped.
            int[] saved = HudElementRegistry.toSaved(pos[0], pos[1]);
            HudConfig.getInstance().setPosition(draggingId, saved[0], saved[1]);
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
