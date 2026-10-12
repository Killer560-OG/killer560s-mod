package com.killer560.hub.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;

/**
 * A HUD element for something drawn only over a container screen (killer560, 2026-10-12: "make it so I can edit the hud
 * position of the croesus profit tracker thing on screen as its own gui"). The feature still draws it from the container
 * screen's own pass; this element only gives it a place in Edit HUD: an id, a box, a preview, a saved position and
 * scale.
 * <p>
 * Never saved: it goes where the feature puts it (its {@link #place} "auto" spot, beside the container) - that spot is
 * this element's default, so Edit HUD shows the box exactly where it draws. Once dragged, the saved position wins. Either
 * way it is drawn at {@link HudElementRegistry#resolveScale} like every other element. {@code HudInGameRenderer} only
 * draws an allow-list, so registering one of these never draws it on the plain HUD.
 */
public final class ContainerOverlayElement implements HudElement {

    /** Draws the element's content with its top-left at (x, y), unscaled: the live content or a preview. */
    @FunctionalInterface
    public interface Drawer {
        void draw(GuiGraphicsExtractor graphics, int x, int y);
    }

    private final String id;
    private final String name;
    private final BooleanSupplier enabled;
    private final Drawer preview;
    private volatile int autoX;
    private volatile int autoY;
    private volatile int width;
    private volatile int height;
    /** The preview's size {w, h} until the content has drawn once (fonts are not loaded when features register). */
    private volatile java.util.function.Supplier<int[]> previewSize;
    private volatile boolean drawn;

    private ContainerOverlayElement(String id, String name, int defaultW, int defaultH, int autoX, int autoY,
                                    BooleanSupplier enabled, Drawer preview) {
        this.id = id;
        this.name = name;
        this.width = defaultW;
        this.height = defaultH;
        this.autoX = autoX;
        this.autoY = autoY;
        this.enabled = enabled;
        this.preview = preview;
    }

    /** Creates and registers one. {@code preview} draws what Edit HUD shows (the last live content when it has one). */
    public static ContainerOverlayElement register(String id, String name, int defaultW, int defaultH, int autoX,
                                                   int autoY, BooleanSupplier enabled, Drawer preview) {
        ContainerOverlayElement e = new ContainerOverlayElement(id, name, defaultW, defaultH, autoX, autoY, enabled, preview);
        HudElementRegistry.register(e);
        HudElementRegistry.markContainerOnly(id);
        return e;
    }

    /**
     * Called by the feature each frame it draws: records where it would put the content on its own ({@code autoX/Y}) and
     * its unscaled size, marks it seen, then draws it at the element's resolved position and scale.
     */
    public void draw(GuiGraphicsExtractor graphics, int autoX, int autoY, int w, int h, Drawer content) {
        this.drawn = true;
        this.autoX = autoX;
        this.autoY = autoY;
        this.width = Math.max(1, w);
        this.height = Math.max(1, h);
        HudSeen.markDrawn(id);
        int[] pos = HudElementRegistry.resolvePosition(this);
        float scale = HudElementRegistry.resolveScale(this);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            content.draw(graphics, 0, 0);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public int defaultX() {
        return autoX;
    }

    @Override
    public int defaultY() {
        return autoY;
    }

    /** Sets how big the preview is until the live content has drawn. */
    public ContainerOverlayElement previewSize(java.util.function.Supplier<int[]> size) {
        this.previewSize = size;
        return this;
    }

    private int[] previewNow() {
        java.util.function.Supplier<int[]> s = previewSize;
        if (drawn || s == null) {
            return null;
        }
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public int width() {
        int[] p = previewNow();
        return p != null ? p[0] : width;
    }

    @Override
    public int height() {
        int[] p = previewNow();
        return p != null ? p[1] : height;
    }

    @Override
    public boolean isEnabledInSettings() {
        return enabled.getAsBoolean();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        preview.draw(graphics, x, y);
    }
}
