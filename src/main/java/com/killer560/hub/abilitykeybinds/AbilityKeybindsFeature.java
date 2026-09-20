package com.killer560.hub.abilitykeybinds;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/**
 * Real dungeon class ability keybinds, ported from Noamm's own {@code Abilities.kt} (the keybind half
 * only - Noamm's own "Auto Use Ultimate", which fires automatically on specific real boss chat lines
 * with no key press at all, is deliberately not ported here per killer560's "keep skipping automation
 * things" instruction).
 * <p>
 * Real mechanic: Hypixel repurposes the real vanilla "drop item" (Q) and "drop stack" (Ctrl+Q) actions
 * as the real dungeon class Ability/Ultimate triggers - pressing either sends the exact same real
 * {@code ServerboundPlayerActionPacket} (real {@code DROP_ITEM}/{@code DROP_ALL_ITEMS} actions) vanilla's
 * own hotkeys already send, just from whichever key you choose instead of Q/Ctrl+Q. This is not
 * automation - it only ever fires on YOUR OWN real key press, doing exactly what pressing the real
 * vanilla key would already do. Gated strictly to {@link DungeonState#isInDungeon()}: outside a real
 * dungeon (or while holding an item without a real Hypixel ability) this same real action just drops
 * your held item, identical to what pressing real vanilla Q there would do - a real, accepted trade-off
 * already present in Noamm's own reference implementation, not a new risk this port introduces.
 */
public final class AbilityKeybindsFeature {

    private static boolean abilityKeyWasDown = false;
    private static boolean ultimateKeyWasDown = false;

    private AbilityKeybindsFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AbilityKeybindsFeature::tick);
    }

    private static void tick(Minecraft client) {
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();
        if (!cfg.isEnabled() || client.screen != null || !DungeonState.isInDungeon() || client.player == null) {
            abilityKeyWasDown = false;
            ultimateKeyWasDown = false;
            return;
        }

        boolean abilityDown = isBindDown(client, cfg.getAbilityKeyCode());
        if (abilityDown && !abilityKeyWasDown) {
            useAbility(client, false);
        }
        abilityKeyWasDown = abilityDown;

        boolean ultimateDown = isBindDown(client, cfg.getUltimateKeyCode());
        if (ultimateDown && !ultimateKeyWasDown) {
            useAbility(client, true);
        }
        ultimateKeyWasDown = ultimateDown;
    }

    /**
     * killer560 (2026-09-20): "the ultimate keybind does the ability and the ability does the ultimate."
     * The two real vanilla actions were the wrong way round: Hypixel reads the plain Drop action (Q) as the
     * ULTIMATE - which is exactly what this mod's own {@code AutoUltFeature} already sends - and the
     * drop-stack action (Ctrl+Q) as the class ABILITY. Existing saved binds are swapped once on load (see
     * {@code AbilityKeybindsConfig}) so no physical key silently changes what it does.
     */
    private static void useAbility(Minecraft client, boolean ultimate) {
        ServerboundPlayerActionPacket.Action action = ultimate
                ? ServerboundPlayerActionPacket.Action.DROP_ITEM
                : ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS;
        client.player.connection.send(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
    }

    /** Polls a keyboard code through {@code KeyUtil} or a mouse code through {@code glfwGetMouseButton}
     *  (killer560: "make all of the keybind things compatible with mouse buttons and middle mouse buttons").
     *  Local helper until {@code KeyUtil} itself learns about mouse binds - patch in this wave's notes. */
    private static boolean isBindDown(Minecraft client, int code) {
        if (code == -1 || client.getWindow() == null) {
            return false;
        }
        if (AbilityKeybindsConfig.isMouseCode(code)) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().handle(),
                    AbilityKeybindsConfig.mouseButton(code)) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
    }
}
