package com.killer560.hub.peekchat;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.Keys;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;

/**
 * Peek Chat (killer560, 2026-10-13): one key shows the whole chat history, as tall as the open chat. Since 2026-11-04
 * ("chat peak should not be able to stop my movement") it opens NO screen: the chat is drawn on the HUD in its open
 * (focused) form while the game keeps the keyboard and mouse, so walking, attacking, using items, the hotbar keys and
 * the camera all keep working.
 * <ul>
 * <li>{@code mixin/PeekChatFocusMixin}: {@code ChatComponent.isChatFocused} also reads true while peeking, so vanilla
 * uses the open chat's height and lines per page, and keeps the scroll place when a line arrives.</li>
 * <li>{@code mixin/PeekChatHudMixin}: the HUD then draws the chat in the foreground mode (every line, no fade).</li>
 * <li>{@code mixin/PeekChatInputMixin}: the wheel scrolls the chat instead of the hotbar, Page Up / Page Down a page,
 * Home / End back to the newest line, and Escape closes the peek without opening the pause menu.</li>
 * </ul>
 * Toggle Peek (the default): a press opens it and the next press closes it. Hold to Peek: open while the key is held.
 * Any screen opening (inventory, the real chat, the pause menu) ends a peek. Links and hover text need a mouse cursor,
 * which only a screen gives, so they are not clickable here; the real chat still has them.
 * <p>
 * The key is polled raw at the end of each tick, like the HUD editor key, and only opens with no screen open, so it never
 * fires while he is typing anywhere (a typed letter reaches a screen, not this poll).
 */
public final class PeekChatFeature {

    private static boolean wasDown;
    private static boolean peeking;
    /** Test hook (docs/TESTING-HOOKS.md): stands in for the key being held; null in play. */
    public static volatile Boolean keyHeldForTest;

    private PeekChatFeature() {
    }

    public static void tick(Minecraft client) {
        PeekChatConfig cfg = PeekChatConfig.getInstance();
        int code = cfg.getKeyCode();
        if (!cfg.isEnabled() || code == KeyUtil.NONE || client.player == null) {
            wasDown = false;
            close(client);
            return;
        }
        Boolean forced = keyHeldForTest;
        boolean down = forced != null ? forced : KeyUtil.isBindDown(client.getWindow(), code);
        if (McCompat.screen(client) != null) {
            close(client);
        } else if (down && !wasDown) {
            if (peeking) {
                close(client);
            } else {
                peeking = true;
            }
        } else if (!down && cfg.isHoldToPeek()) {
            close(client);
        }
        wasDown = down;
    }

    /** True while the peek is shown: read every frame by the chat's focus check, so it is two field reads. */
    public static boolean isPeeking() {
        return peeking && McCompat.screen(Minecraft.getInstance()) == null;
    }

    /** Shows the peek now (the key's action; also the testkit's). */
    public static void open(Minecraft client) {
        if (client.player != null && McCompat.screen(client) == null) {
            peeking = true;
        }
    }

    public static void close(Minecraft client) {
        if (peeking) {
            peeking = false;
            McCompat.chat(client).resetChatScroll();
        }
    }

    /** The wheel while peeking: 7 lines a notch, 1 with Shift, as in the open chat. @return true to keep it off the hotbar */
    public static boolean onScroll(double scrollY) {
        if (!isPeeking()) {
            return false;
        }
        double dy = Math.max(-1.0, Math.min(1.0, scrollY));
        if (dy != 0.0) {
            var window = Minecraft.getInstance().getWindow();
            boolean shift = KeyUtil.isKeyDown(window, Keys.LEFT_SHIFT) || KeyUtil.isKeyDown(window, Keys.RIGHT_SHIFT);
            McCompat.chat(Minecraft.getInstance()).scrollChat((int) (shift ? dy : dy * 7.0));
        }
        return true;
    }

    /** A key press or repeat while peeking. @return true when it was Peek Chat's (the game does not see it) */
    public static boolean onKey(int key, int action) {
        if (action == InputConstants.RELEASE || !isPeeking()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = McCompat.chat(client);
        switch (key) {
            case InputConstants.KEY_ESCAPE -> close(client);
            case InputConstants.KEY_PAGEUP -> chat.scrollChat(Math.max(1, chat.getLinesPerPage() - 1));
            case InputConstants.KEY_PAGEDOWN -> chat.scrollChat(-Math.max(1, chat.getLinesPerPage() - 1));
            case InputConstants.KEY_HOME, InputConstants.KEY_END -> chat.resetChatScroll();
            default -> {
                return false;
            }
        }
        return true;
    }
}
