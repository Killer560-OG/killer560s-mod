package com.killer560.hub.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Everything that moved between Minecraft versions, for 26.1.2.
 *
 * <p>There is one of these per supported Minecraft version, in {@code src/mc26_1/java},
 * {@code src/mc26_2/java} and so on, and {@code build.gradle} puts exactly one of them on the source path
 * according to {@code minecraft_version}. They are plain classes with identical public statics rather than an
 * interface and implementations: there is no runtime indirection, no registry to keep in step, and the wrong
 * one cannot be selected because only one is ever compiled. It is the same shape as the generated
 * {@code BuildVariant}.
 *
 * <p><b>The rule.</b> Nothing under {@code src/main/java} may touch a Minecraft API that differs between
 * versions; it calls this instead. That is what makes a new Minecraft version a new directory here rather than
 * another sweep of 229 files - which is what porting to 26.2 measured at before this existed.
 *
 * <p><b>Keep every version's copy method-for-method identical in signature.</b> A method present in one and
 * missing in another turns a clean compile on one version into a break on the other, which is exactly the
 * failure this class exists to prevent. When you add one, add it everywhere.
 */
public final class McCompat {

    private McCompat() {
    }

    // ------------------------------------------------------------------------------------------------ screens
    //
    // 26.2 removes both `Minecraft.screen` and `Minecraft.setScreen`, and nothing on Minecraft returns a Screen
    // any more - 396 of the 706 errors a straight 26.2 compile produces, across 158 files. On 26.1.2 these are
    // the field and the method they always were.

    /** The screen that is open, or null for none. */
    public static Screen screen(Minecraft client) {
        return client.screen;
    }

    /** Opens a screen, or closes the current one when given null. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    /** Whether a screen is open at all - the commonest of the 350 reads, and clearer than a null test. */
    public static boolean hasScreen(Minecraft client) {
        return client.screen != null;
    }
}
