package com.killer560.hub.gui;

import com.killer560.hub.accounts.gui.AccountSwitcherScreen;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.AutoScale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The mod menu's fade out (gui/MenuAnim). Closing must be immediate - Escape hands the game (or the screen underneath)
 * its input that instant, and the testkit checks the menu is gone right after the key - so the menu is closed at once and
 * only its PICTURE fades: for {@link MenuAnim#CLOSE_MS} the closed {@link ModScreen} is drawn again, fading and shrinking,
 * over whatever is on screen now. Over the game it is a HUD layer drawn last; over another screen it is drawn after
 * that screen ({@code ScreenEvents.afterExtract}). It never takes input, and it ends at once when a menu opens again.
 */
public final class MenuCloseFade {

    private MenuCloseFade() {
    }

    private static ModScreen closing;

    /** The menu-themed screens already given their clock hook (a screen that is initialized again keeps one). */
    private static final java.util.Set<Object> CLOCK_HOOKED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static void start(ModScreen screen) {
        closing = screen;
    }

    static void stop() {
        closing = null;
    }

    /** True while a closed menu is still fading out (for the testkit). */
    public static boolean active() {
        return closing != null;
    }

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("killer560smod", "menu_close_fade"), (g, delta) -> {
            if (closing != null && McCompat.screen(Minecraft.getInstance()) == null) {
                draw(g);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            // Only ModScreen (and the closing fade) advanced the animation clock, so a knob or switch glide started on
            // a menu-themed screen (the Visual Editor, the editors, the trackers) never got a frame and stayed at its
            // 'from' value (2026-10-18). Such a screen samples the clock once after each of its draws; the draw itself
            // uses the frame before, a one-frame lag. AccountSwitcherScreen samples it itself.
            if (screen instanceof MenuThemedScreen && !(screen instanceof AccountSwitcherScreen)
                    && CLOCK_HOOKED.add(screen)) {
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> MenuAnim.frame());
            }
            if (!(screen instanceof ModScreen)) {
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                    if (closing != null && s == McCompat.screen(Minecraft.getInstance())) {
                        draw(g);
                    }
                });
            }
        });
    }

    private static void draw(GuiGraphicsExtractor g) {
        ModScreen s = closing;
        if (s == null) {
            return;
        }
        MenuAnim.frame();
        float f = AutoScale.appliedFactor(s);
        g.pose().pushMatrix();
        if (f != 1.0f) {
            g.pose().scale(f, f);
        }
        try {
            if (!s.drawClosing(g, 0f)) {
                closing = null;
            }
        } catch (RuntimeException e) {
            closing = null; // a picture is never worth a crash
        } finally {
            g.pose().popMatrix();
            MenuStyle.use();
        }
    }
}
