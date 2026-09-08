package com.killer560.hub.gui.tab;

/** Implemented by any tab that has a "click to set, then press any key" keybind field - lets
 *  {@link com.killer560.hub.gui.ModScreen#keyPressed} route the next key press to whichever tab is
 *  currently listening, without needing an {@code instanceof} check per tab. */
public interface KeyCaptureTab {

    boolean isListeningForKey();

    void onKeyCaptured(int keyCode);
}
