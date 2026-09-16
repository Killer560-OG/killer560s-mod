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

/**
 * SkyHanni-style HUD position editor: every listed {@link HudElement} is drawn at its current
 * position with a bounding box, click-and-drag anywhere on a box to move it, release to save.
 * Scrolling while holding a box (or just hovering one) resizes it.
 * <p>
 * Which elements are listed (2026-09-16): only those whose {@link HudElement#isRelevantNow()} is true -
 * killer560: "for editing huds have it only edit huds that are supposed to be open right now. So for
 * instance if i am in dungeons i dont need to edit the rng meter hud." The "Show All" button lists every
 * registered element instead, so a HUD for a context you are not in right now (pre-arranging the P5 dragon
 * timers from the hub, say) stays editable; its state persists in {@link HudConfig}. The list is fixed when
 * the screen opens / the toggle flips rather than re-evaluated every frame, so a timer expiring mid-drag
 * can't yank the box out from under the cursor.
 */
public class HudEditorScreen extends Screen {

    private final Screen parent;
    private final Map<String, int[]> livePositions = new HashMap<>();
    private final Map<String, Float> liveScales = new HashMap<>();
    /** Elements currently listed, in registry order (topmost last). */
    private final List<HudElement> shown = new ArrayList<>();
    private int hiddenCount = 0;

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

    private static Component showAllLabel() {
        return Component.literal("Show All: " + (HudConfig.getInstance().isEditorShowAll() ? "§aON" : "§cOFF"));
    }

    /** Recomputes the listed elements and (re)snapshots their positions/scales. Positions come through
     *  {@link HudElementRegistry#resolvePosition}, so a never-moved element starts inside the screen. */
    private void rebuildList() {
        draggingId = null;
        shown.clear();
        livePositions.clear();
        liveScales.clear();
        boolean showAll = HudConfig.getInstance().isEditorShowAll();
        int hidden = 0;
        for (HudElement element : HudElementRegistry.all()) {
            if (!showAll && !HudElementRegistry.isRelevantNow(element)) {
                hidden++;
                continue;
            }
            shown.add(element);
            int[] pos = HudElementRegistry.resolvePosition(element);
            livePositions.put(element.id(), pos);
            liveScales.put(element.id(), HudElementRegistry.resolveScale(element));
        }
        hiddenCount = hidden;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x33000000);
        graphics.text(this.font, "§eDrag a box to reposition it, scroll to resize it. Click Done when finished.",
                8, 8, 0xFFFFFFFF);
        String listing = hiddenCount == 0
                ? "§7Showing all " + shown.size() + " HUD elements."
                : "§7Showing " + shown.size() + " HUD elements relevant right now (" + hiddenCount
                + " hidden - turn on Show All to arrange those too).";
        graphics.text(this.font, listing, 8, 20, 0xFFFFFFFF);

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
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
