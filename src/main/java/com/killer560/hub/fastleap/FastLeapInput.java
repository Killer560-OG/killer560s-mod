package com.killer560.hub.fastleap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;

/**
 * Input decisions for the Fast Leap mixins ({@code FastLeapMouseHandlerMixin}, {@code FastLeapKeyboardHandlerMixin}).
 * <ul>
 * <li>Left-button press with no screen open -&gt; {@link FastLeapFeature#onLeftClick()} (fast leap; cancels the click
 * while holding a Spirit Leap).</li>
 * <li>{@link LeapManager#blocksInput()} (QUOI "Block inputs" / "Fast mode"): every mouse-button press, key press,
 * scroll and camera turn is cancelled, in the world and while the Spirit Leap menu itself is open.</li>
 * <li>{@link I4LeapFeature#blocksInput()} ("Prevent Inputs"): in the world only - mouse-button presses, movement-key
 * presses (forward/back/left/right/jump/sprint/sneak), scroll and camera turn are cancelled.</li>
 * </ul>
 * Releases and Escape are never cancelled (so nothing sticks down and the menu/pause screen can always be opened or
 * closed); when a block ends the key states are re-synced with
 * {@code KeyMapping.setAll()}. Camera turn is cancelled at {@code MouseHandler.turnPlayer}, whose caller still resets the
 * accumulated mouse delta, so the view doesn't jump when the block ends; code that sets rotation directly (Auto i4)
 * is unaffected.
 */
public final class FastLeapInput {

    private FastLeapInput() {
    }

    private static boolean leapMenuOrWorld(Minecraft client) {
        return client.screen == null || LeapManager.leapMenuScreen(client.screen) != null;
    }

    /** @param action GLFW action (1 = press, 0 = release) */
    public static boolean shouldCancelMouseButton(int button, int action) {
        if (action != 1) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (LeapManager.blocksInput() && leapMenuOrWorld(client)) {
            return true;
        }
        if (client.screen != null) {
            return false;
        }
        if (I4LeapFeature.blocksInput()) {
            return true;
        }
        return button == 0 && FastLeapFeature.onLeftClick();
    }

    /** @param action GLFW action (1 = press, 2 = repeat, 0 = release) */
    public static boolean shouldCancelKey(KeyEvent event, int action) {
        // Releases always go through, and Escape is never blocked: the player must always be able to close the leap
        // menu (which cleanly fails the leap - "Container changed before click") or open the pause menu.
        if (action == 0 || event.key() == InputConstants.KEY_ESCAPE) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (LeapManager.blocksInput() && leapMenuOrWorld(client)) {
            return true;
        }
        if (client.screen != null || !I4LeapFeature.blocksInput()) {
            return false;
        }
        Options o = client.options;
        return o.keyUp.matches(event) || o.keyDown.matches(event) || o.keyLeft.matches(event) || o.keyRight.matches(event)
                || o.keyJump.matches(event) || o.keySprint.matches(event) || o.keyShift.matches(event);
    }

    public static boolean shouldCancelScroll() {
        Minecraft client = Minecraft.getInstance();
        return client.screen == null && (LeapManager.blocksInput() || I4LeapFeature.blocksInput());
    }

    public static boolean shouldCancelTurn() {
        Minecraft client = Minecraft.getInstance();
        return client.screen == null && (LeapManager.blocksInput() || I4LeapFeature.blocksInput());
    }
}
