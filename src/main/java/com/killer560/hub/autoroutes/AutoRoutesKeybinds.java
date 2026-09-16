package com.killer560.hub.autoroutes;

import com.killer560.hub.autoroutes.AutoRoutesCommands.Action;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * Raw-polled keybinds for every {@code /ar} command - "Keybinds for every command: start/stop recording,
 * etherwarp, all the others" (killer560, 2026-09-16). One key per {@link Action}, each stored in
 * {@link AutoRoutesConfig} under {@link Action#id}, all defaulting to {@link KeyUtil#NONE} (unbound).
 * <p>
 * Same pattern as {@code CommandKeybindsFeature}: polled every client tick with {@link KeyUtil#isKeyDown} (never
 * vanilla {@code KeyMapping}s, which would show up in Controls and could collide with real binds), edge-triggered so
 * a held key fires exactly once, and inert whenever any screen is open so typing "r" into chat or the settings
 * search box can't start a recording. A key press runs {@link Action#run()} - the identical code path the chat
 * command takes, including its gating and chat feedback - so there is no way for a keybind and its command to
 * drift apart.
 */
public final class AutoRoutesKeybinds {

    private static final Action[] ACTIONS = Action.values();
    private static final boolean[] wasDown = new boolean[ACTIONS.length];

    private AutoRoutesKeybinds() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see INTEGRATION.md). */
    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(AutoRoutesKeybinds::tick);
    }

    private static void tick(Minecraft client) {
        try {
            if (client.screen != null || client.player == null || client.getWindow() == null
                    || !AutoRoutesConfig.getInstance().isEnabledRaw()) {
                // Reset the edge state so a key that was held while a screen opened doesn't fire the moment it
                // closes - the press happened somewhere else.
                Arrays.fill(wasDown, false);
                return;
            }
            AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
            for (int i = 0; i < ACTIONS.length; i++) {
                boolean down = KeyUtil.isKeyDown(client.getWindow(), cfg.getKeybind(ACTIONS[i].id));
                if (down && !wasDown[i]) {
                    ACTIONS[i].run();
                }
                wasDown[i] = down;
            }
        } catch (Exception e) {
            // Action#run already fences its own body; this only guards the config/poll itself. A tick handler
            // that throws takes the whole client tick with it, so swallow and keep polling next tick.
            Arrays.fill(wasDown, false);
        }
    }
}
