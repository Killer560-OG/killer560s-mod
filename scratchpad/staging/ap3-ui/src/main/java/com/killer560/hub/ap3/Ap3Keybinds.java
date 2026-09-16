package com.killer560.hub.ap3;

import com.killer560.hub.ap3.Ap3Commands.Action;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * Raw-polled keybinds for every {@code /ap3} command - "Every command gets an assignable keybind, all unbound by
 * default, raw-polled, edge-triggered" (spec). One key per {@link Action}, each stored in {@link Ap3Config} under
 * {@link Action#id}, all defaulting to {@link KeyUtil#NONE}.
 * <p>
 * Same pattern as {@code AutoRoutesKeybinds} / {@code CommandKeybindsFeature}: polled every client tick with
 * {@link KeyUtil#isKeyDown} (never vanilla {@code KeyMapping}s, which would show up in Controls and could collide
 * with real binds), edge-triggered so a held key fires exactly once, and inert whenever any screen is open so typing
 * into chat or the settings search box can't add a node. A key press runs {@link Action#run()} - the identical code
 * path the chat command takes, including its gating and chat feedback - so a keybind and its command can't drift.
 * <p>
 * The {@link Action#STOP} key is polled even while the master toggle is off: {@link Ap3Commands#run} lets STOP
 * through regardless, because "the tab's own stop button must work at any time" and a key is the faster panic
 * button. Everything else is skipped while OFF so an unbound-by-accident key can't place nodes.
 */
public final class Ap3Keybinds {

    private static final Action[] ACTIONS = Action.values();
    private static final boolean[] wasDown = new boolean[ACTIONS.length];

    private Ap3Keybinds() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see INTEGRATION.md). */
    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(Ap3Keybinds::tick);
    }

    private static void tick(Minecraft client) {
        try {
            if (client.screen != null || client.player == null || client.getWindow() == null) {
                // Reset the edge state so a key that was held while a screen opened doesn't fire the moment it
                // closes - the press happened somewhere else.
                Arrays.fill(wasDown, false);
                return;
            }
            Ap3Config cfg = Ap3Config.getInstance();
            boolean enabled = cfg.isEnabledRaw();
            for (int i = 0; i < ACTIONS.length; i++) {
                if (!enabled && ACTIONS[i] != Action.STOP) {
                    wasDown[i] = false;
                    continue;
                }
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
