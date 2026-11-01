package com.killer560.hub.gui.tab;

/** Implemented by any tab that has a "click to set, then press any key" keybind field - lets
 *  {@link com.killer560.hub.gui.ModScreen#keyPressed} route the next key press to whichever tab is
 *  currently listening, without needing an {@code instanceof} check per tab. */
public interface KeyCaptureTab {

    boolean isListeningForKey();

    /** Called with the STORED (GLFW-numbered, {@code util/Keys}) code of the pressed key: ModScreen passes
     *  {@code McInput.key(event)}, never the raw event key (an SDL scancode on 26.3). -1 for a key with no GLFW twin. */
    void onKeyCaptured(int keyCode);

    /** Whether this tab can take a MOUSE button as a bind, not just a key - killer560 (2026-09-20):
     *  "make all of the keybind things compatible with mouse buttons and middle mouse buttons".
     *  Default false so a key-only tab keeps letting clicks through to its widgets while it listens. */
    default boolean supportsMouseCapture() {
        return false;
    }

    /** Called with the STORED (GLFW, 0 left / 1 right / 2 middle) button number when this tab is listening and {@link #supportsMouseCapture()}. */
    default void onMouseCaptured(int button) {
    }
}
