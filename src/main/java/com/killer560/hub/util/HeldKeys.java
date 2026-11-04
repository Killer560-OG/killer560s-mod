package com.killer560.hub.util;

import net.minecraft.client.Minecraft;

/** Sets the seven movement key mappings at once (the fallback when the input mixin is not applied). */
public final class HeldKeys {

    private HeldKeys() {
    }

    public static void set(Minecraft client, boolean forward, boolean back, boolean left, boolean right,
                           boolean jump, boolean sneak, boolean sprint) {
        client.options.keyUp.setDown(forward);
        client.options.keyDown.setDown(back);
        client.options.keyLeft.setDown(left);
        client.options.keyRight.setDown(right);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(sneak);
        client.options.keySprint.setDown(sprint);
    }

    public static void releaseAll(Minecraft client) {
        set(client, false, false, false, false, false, false, false);
    }
}
