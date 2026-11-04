package com.killer560.hub.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Overlays on a container screen that must draw UNDER the item tooltip (killer560, 2026-11-03: "make tooltips show above the
 * search bar"). {@code ScreenEvents.afterExtract} is fired by Fabric's GameRenderer wrap AFTER
 * {@code Screen#extractRenderStateWithTooltipAndSubtitles} returns, i.e. after {@code extractDeferredElements} has moved to a
 * new stratum and run the deferred tooltip, so anything drawn there lands on top of the tooltip (javap 26.2 Screen,
 * GuiGraphicsExtractor, Fabric GameRendererMixin). This runs the callbacks from the TAIL of
 * {@code AbstractContainerScreen#extractRenderState} (and {@code AbstractRecipeBookScreen}'s, which never calls it), the same
 * point the Item Browser panel and Storage Overlay use, which is before the deferred tooltip pass.
 * <p>Register exactly as with {@code ScreenEvents.afterExtract(screen).register}; callbacks are dropped when the screen is
 * re-initialised (Fabric does the same to its own per-screen events), so register from {@code AFTER_INIT}.
 */
public final class BelowTooltip {

    private static final Map<Screen, List<ScreenEvents.AfterExtract>> BY_SCREEN = new WeakHashMap<>();
    private static boolean hooked = false;

    private BelowTooltip() {
    }

    public static synchronized void register(Screen screen, ScreenEvents.AfterExtract callback) {
        if (!hooked) {
            hooked = true;
            ScreenEvents.BEFORE_INIT.register((client, s, w, h) -> {
                synchronized (BelowTooltip.class) {
                    BY_SCREEN.remove(s);
                }
            });
        }
        BY_SCREEN.computeIfAbsent(screen, k -> new ArrayList<>()).add(callback);
    }

    /** From the mixins: runs this screen's callbacks. */
    public static void fire(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        List<ScreenEvents.AfterExtract> list;
        synchronized (BelowTooltip.class) {
            List<ScreenEvents.AfterExtract> l = BY_SCREEN.get(screen);
            if (l == null) {
                return;
            }
            list = new ArrayList<>(l);
        }
        for (ScreenEvents.AfterExtract cb : list) {
            cb.afterExtract(screen, graphics, mouseX, mouseY, partialTick);
        }
    }
}
