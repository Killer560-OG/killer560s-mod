package com.killer560.hub.gui;

/**
 * A mod-menu widget that scrolls itself with the mouse wheel. {@link ModScreen} scrolls the whole content pane on a
 * wheel turn before any child sees it; a widget implementing this gets the turn first while the cursor is over it, and
 * keeps it when it could still move that way (a list already at its end hands the turn back to the page).
 */
public interface WheelTarget {

    /** @return true if the turn moved this widget (and must not also scroll the page). */
    boolean wheel(double mouseX, double mouseY, double scrollY);
}
