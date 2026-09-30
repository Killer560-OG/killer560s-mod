package com.killer560.hub.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Everything that moved between Minecraft versions, for 26.2.
 *
 * <p>See the 26.1 copy in {@code src/mc26_1/java} for what this class is for and the rule that governs it.
 * Keep the two method-for-method identical in signature: a method in one and not the other turns a clean
 * compile on one Minecraft version into a break on the other.
 */
public final class McCompat {

    private McCompat() {
    }

    // ------------------------------------------------------------------------------------------------ screens
    //
    // 26.2 takes the screen off Minecraft entirely and gives it to Minecraft.gui: the field and setScreen are
    // gone, and nothing on Minecraft returns a Screen any more. Found by disassembling the one survivor,
    // Minecraft.setScreenAndShow, which turns out to be a Gui.setScreen call followed by a frame render - so
    // it is NOT the equivalent of the old setScreen and must not be used as one here, or every screen change
    // would force a render.
    //
    // Gui.screen() is the reader and Gui.setScreen(Screen) the writer. On 26.1.2 both were on Minecraft.
    // This is the single largest difference between the two versions: 396 of the 706 errors a straight 26.2
    // compile produced, across 173 files.

    /** The screen that is open, or null for none. */
    public static Screen screen(Minecraft client) {
        return client.gui.screen();
    }

    /** Opens a screen, or closes the current one when given null. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    /** Whether a screen is open at all - the commonest of the 350 reads, and clearer than a null test. */
    public static boolean hasScreen(Minecraft client) {
        return client.gui.screen() != null;
    }
}
