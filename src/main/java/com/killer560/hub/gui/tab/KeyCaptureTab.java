package com.killer560.hub.gui.tab;

import com.killer560.hub.util.KeyUtil;

/** Implemented by any tab that has a "click to set, then press any key" keybind field - lets
 *  {@link com.killer560.hub.gui.ModScreen#keyPressed} route the next key press to whichever tab is
 *  currently listening, without needing an {@code instanceof} check per tab.
 *
 *  <p>While a row listens, ModScreen does the same for every row (killer560, 2026-11-02: "most of the set keybinds
 *  things wont let me set mouse buttons to it ... fix that for all areas"): any mouse button binds that button
 *  ({@link #onMouseCaptured}), Escape cancels and keeps the old bind ({@link #cancelKeyCapture}), Backspace or Delete
 *  clears it ({@code onKeyCaptured(KeyUtil.NONE)}), and any other key binds that key. */
public interface KeyCaptureTab {

    boolean isListeningForKey();

    /** Called with the STORED (GLFW-numbered, {@code util/Keys}) code of the pressed key: ModScreen passes
     *  {@code McInput.key(event)}, never the raw event key (an SDL scancode on 26.3). -1 for a key with no GLFW twin,
     *  and {@link KeyUtil#NONE} for Backspace/Delete (clear the bind). Mouse binds arrive here too, as
     *  {@link KeyUtil#codeForMouseButton} codes, unless the tab overrides {@link #onMouseCaptured}. */
    void onKeyCaptured(int keyCode);

    /** Escape while listening: stop listening and keep the bind as it was. Every implementation must reset its own
     *  "capturing" state here (abstract on purpose, so a new key row cannot forget it). */
    void cancelKeyCapture();

    /** Whether this tab can take a MOUSE button as a bind, not just a key - every bind can (2026-11-02); a tab
     *  overrides this only for a row whose consumer truly cannot read a mouse button. */
    default boolean supportsMouseCapture() {
        return true;
    }

    /** Called with the STORED (GLFW, 0 left / 1 right / 2 middle / 3 mouse 4 ...) button number when this tab is
     *  listening and {@link #supportsMouseCapture()}. By default stored like a key, as its mouse code. */
    default void onMouseCaptured(int button) {
        onKeyCaptured(KeyUtil.codeForMouseButton(button));
    }
}
