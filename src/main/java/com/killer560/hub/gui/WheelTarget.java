package com.killer560.hub.gui;

/**
 * A mod-menu widget that scrolls itself with the mouse wheel. {@link ModScreen} scrolls the whole content pane on a
 * wheel turn before any child sees it; a widget implementing this gets the turn first while the cursor is over it. A list
 * keeps every turn over it, even one at its end that moves nothing (killer560, 2026-10-11: scrolling past the top must
 * not scroll the menu).
 */
public interface WheelTarget {

    /** @return true if the turn moved this widget (and must not also scroll the page). */
    boolean wheel(double mouseX, double mouseY, double scrollY);
}
