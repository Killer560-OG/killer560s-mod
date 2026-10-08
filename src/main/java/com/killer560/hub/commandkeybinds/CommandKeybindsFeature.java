package com.killer560.hub.commandkeybinds;

import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Chat Keybinds (originally "Command Keybinds", ported from Odin's own {@code CommandKeybinds.kt} - "various
 * keybinds for common skyblock commands"). killer560 (2026-09-20) asked to "let me type my own command or
 * message" instead of only the 8 built-in menu commands, so a bind is now a key (or mouse button) plus the
 * exact line to send: a leading "/" sends it as a command, anything else as a normal chat message.
 * <p>
 * Each bind only ever fires on the player's own key press and sends only the one line it's set to - not
 * automation, just a shortcut for something that could already be typed by hand.
 * <p>
 * Since 2026-10-08 the feature also owns Command Shortcuts (killer560: "Chat keybinds and command shortcuts should be
 * combined into just chat keybinds."): {@code commandshortcuts.CommandShortcutsFeature} still registers /f7 and the
 * custom shortcuts, under this feature's one switch, and a custom shortcut may carry a key that this tick fires.
 */
public final class CommandKeybindsFeature {

    /** Edge-detect state, index-aligned with the bind list; resized whenever the list changes. */
    private static boolean[] wasDown = new boolean[0];
    /** The same for the custom shortcuts' keys (one per custom row, by position). */
    private static boolean[] customWasDown = new boolean[0];

    private CommandKeybindsFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CommandKeybindsFeature.tick", CommandKeybindsFeature::tick));
    }

    private static void tick(Minecraft client) {
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();
        List<CommandKeybindsConfig.Bind> binds = cfg.binds();
        List<CommandShortcutsConfig.CustomShortcut> customs = cfg.shortcuts().customs();
        if (wasDown.length != binds.size()) {
            wasDown = new boolean[binds.size()];
        }
        if (customWasDown.length != customs.size()) {
            customWasDown = new boolean[customs.size()];
        }
        if (!cfg.isEnabled() || McCompat.screen(client) != null || client.player == null) {
            java.util.Arrays.fill(wasDown, false);
            java.util.Arrays.fill(customWasDown, false);
            return;
        }

        for (int i = 0; i < binds.size(); i++) {
            CommandKeybindsConfig.Bind bind = binds.get(i);
            boolean isDown = bind.enabled && isBindDown(client, bind.key);
            if (isDown && !wasDown[i]) {
                send(client, bind.text);
            }
            wasDown[i] = isDown;
        }

        // A custom shortcut with a key (since the merge, 2026-10-08): the key sends exactly what typing /name sends.
        for (int i = 0; i < customs.size(); i++) {
            CommandShortcutsConfig.CustomShortcut c = customs.get(i);
            boolean isDown = c.key() != -1 && CommandShortcutsFeature.isKeyLive(c) && isBindDown(client, c.key());
            if (isDown && !customWasDown[i]) {
                ServerCommands.toServer(CommandShortcutsFeature.commandOf(c));
            }
            customWasDown[i] = isDown;
        }
    }

    private static void send(Minecraft client, String text) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            return;
        }
        if (line.charAt(0) == '/') {
            String command = line.substring(1);
            if (!command.isEmpty()) {
                client.player.connection.sendCommand(command);
            }
            return;
        }
        client.player.connection.sendChat(line);
    }

    /** Polls a keyboard code through {@code KeyUtil} (which guards GLFW's keyboard-only range) or a mouse
     *  code through {@code glfwGetMouseButton}. Local helper until {@code KeyUtil} itself learns about mouse
     *  binds - that patch is in this wave's staging notes for the main session. */
    static boolean isBindDown(Minecraft client, int code) {
        if (code == -1 || client.getWindow() == null) {
            return false;
        }
        if (CommandKeybindsConfig.isMouseCode(code)) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(),
                    CommandKeybindsConfig.mouseButton(code)) == GLFW.GLFW_PRESS;
        }
        return com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
    }
}
