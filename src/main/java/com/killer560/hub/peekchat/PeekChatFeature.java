package com.killer560.hub.peekchat;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;

/**
 * Peek Chat (killer560, 2026-10-13): one key opens the chat history read-only - {@link PeekChatScreen}, scrollable, links
 * and hover text working, no input line. A PRESS toggles it: the key opens it, and the same key (or Escape) closes it.
 * Not a hold: holding a key while scrolling with the wheel and clicking links is awkward, and a hold would close the
 * moment another window took focus.
 * <p>
 * The key is polled raw at the end of each tick, like the HUD editor key, and only acts with no screen open, so it never
 * fires while he is typing anywhere (a typed letter reaches a screen, not this poll) and never opens over a menu. It
 * opens on the press edge only, so the key still held after it closed the screen does not open it again.
 */
public final class PeekChatFeature {

    private static boolean wasDown;

    private PeekChatFeature() {
    }

    public static void tick(Minecraft client) {
        PeekChatConfig cfg = PeekChatConfig.getInstance();
        int code = cfg.getKeyCode();
        if (!cfg.isEnabled() || code == KeyUtil.NONE) {
            wasDown = false;
            return;
        }
        boolean down = KeyUtil.isBindDown(client.getWindow(), code);
        if (down && !wasDown && McCompat.screen(client) == null && client.player != null) {
            open(client);
        }
        wasDown = down;
    }

    /** The key closed the screen: its press reached the screen before this tick's poll, so without this the same press
     *  would read as a fresh edge with no screen open and reopen it at once. Wait for a release first. */
    static void closedByKey() {
        wasDown = true;
    }

    /** Opens the read-only chat now (the key's action; also the testkit's). */
    public static void open(Minecraft client) {
        McCompat.setScreen(client, new PeekChatScreen());
    }
}
