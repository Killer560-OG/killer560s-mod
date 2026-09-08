package com.killer560.hub.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * SkyHanni-style HUD position editor: every registered {@link HudElement} is drawn at its current
 * position with a bounding box, click-and-drag anywhere on a box to move it, release to save.
 * Scrolling while holding a box (or just hovering one) resizes it.
 */
public class HudEditorScreen extends Screen {

    private final Screen parent;
    private final Map<String, int[]> livePositions = new HashMap<>();
    private final Map<String, Float> liveScales = new HashMap<>();

    private String draggingId = null;
    private double dragOffsetX;
    private double dragOffsetY;

    private static final int BOX_BG = 0x55FFFFFF;
    private static final int BOX_BG_DRAGGING = 0x8055FF55;
    private static final int BOX_OUTLINE = 0xFF55FFFF;
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
        livePositions.clear();
        liveScales.clear();
        for (HudElement element : HudElementRegistry.all()) {
            int[] pos = HudElementRegistry.resolvePosition(element);
            livePositions.put(element.id(), pos);
            liveScales.put(element.id(), HudElementRegistry.resolveScale(element));
        }

        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x33000000);
        graphics.text(this.font, "§eDrag a box to reposition it, scroll to resize it. Click Done when finished.",
                8, 8, 0xFFFFFFFF);

        for (HudElement element : HudElementRegistry.all()) {
            int[] pos = livePositions.get(element.id());
            int x = pos[0];
            int y = pos[1];
            float scale = liveScales.get(element.id());
            int scaledW = Math.round(element.width() * scale);
            int scaledH = Math.round(element.height() * scale);
            boolean dragging = element.id().equals(draggingId);

            graphics.fill(x, y, x + scaledW, y + scaledH, dragging ? BOX_BG_DRAGGING : BOX_BG);
            graphics.outline(x, y, scaledW, scaledH, BOX_OUTLINE);
            graphics.text(this.font, element.displayName() + String.format(" (%.1fx)", scale), x + 2, y - 10, 0xFFFFFFFF);

            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 2, y + 2);
            graphics.pose().scale(scale, scale);
            element.render(graphics, 0, 0);
            graphics.pose().popMatrix();
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Topmost element whose scaled box contains (mx, my), or null. */
    private HudElement elementAt(double mx, double my) {
        var elements = HudElementRegistry.all();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            int[] pos = livePositions.get(element.id());
            float scale = liveScales.get(element.id());
            int scaledW = Math.round(element.width() * scale);
            int scaledH = Math.round(element.height() * scale);
            if (mx >= pos[0] && mx <= pos[0] + scaledW && my >= pos[1] && my <= pos[1] + scaledH) {
                return element;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
        return super.mouseClicked(event, doubleClick);
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
