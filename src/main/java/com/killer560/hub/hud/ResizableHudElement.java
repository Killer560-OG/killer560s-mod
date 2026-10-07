package com.killer560.hub.hud;

/**
 * A {@link HudElement} whose width and height can be changed separately, by dragging its box's edges and corners in
 * the HUD editor like a desktop window (2026-10-07, killer560: "make it so it is draggable to resize as well as the
 * scroll, so I think it'll act the same as a normal Chrome window or PC window").
 *
 * <p>Every element can be resized by its corners, which changes its uniform scale and keeps its shape; only an element
 * implementing this also gets edge handles, and its corners then change width and height independently. Sizes are in
 * the element's own units - the units of {@link #width()} / {@link #height()} - which the editor converts from screen
 * pixels through the element's drawn scale. The element clamps what it is given; the editor then re-reads
 * {@code width()}/{@code height()} so the box always shows what will really draw.
 */
public interface ResizableHudElement extends HudElement {

    /** The element's resizable width now, in its own units (for a stat bar, the bar's length). */
    int resizeWidth();

    /** The element's resizable height now, in its own units (for a stat bar, the bar's thickness - its box can be
     *  taller, see {@link #height()}). */
    int resizeHeight();

    /** Applies a new size in the element's own units; values outside the element's range are clamped. Not saved. */
    void resizeTo(int width, int height);

    /** Persists the size {@link #resizeTo} set (called once when the drag ends). */
    void saveSize();
}
