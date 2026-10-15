package com.killer560.hub.gui;

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
