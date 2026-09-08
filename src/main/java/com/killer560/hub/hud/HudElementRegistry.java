package com.killer560.hub.hud;

import java.util.ArrayList;
import java.util.List;

public final class HudElementRegistry {

    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    private HudElementRegistry() {
    }

    public static void register(HudElement element) {
        ELEMENTS.add(element);
    }

    /** Removes a previously registered element (e.g. a GIF that's been toggled off) so it stops
     *  showing up in the HUD editor and being rendered. */
    public static void unregister(String id) {
        ELEMENTS.removeIf(e -> e.id().equals(id));
    }

    public static List<HudElement> all() {
        return ELEMENTS;
    }

    public static int[] resolvePosition(HudElement element) {
        return HudConfig.getInstance().getPosition(element.id(), element.defaultX(), element.defaultY());
    }

    public static float resolveScale(HudElement element) {
        return HudConfig.getInstance().getScale(element.id(), 1.0f);
    }
}
