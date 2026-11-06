package com.killer560.hub.abilitykeybinds;

import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.util.ChatObserver;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.regex.Pattern;

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

    /** How long a shared ability/player-list key is held before the list shows anyway (killer560, 2026-11-06). */
    static final long LIST_HOLD_MS = 300;
    private static final Pattern ON_COOLDOWN = Pattern.compile("^This [A-Za-z]+ is on cooldown");
    private static long listHoldSince;
    private static boolean listCooldownSeen;

    private AbilityKeybindsFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(m -> {
            if (listHoldSince != 0 && ON_COOLDOWN.matcher(ChatObserver.strip(m)).find()) {
                listCooldownSeen = true;
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AbilityKeybindsFeature.tick", FeatureBits.register("Ability Keybinds tick", () -> AbilityKeybindsConfig.getInstance().isEnabledRaw()), AbilityKeybindsFeature::tick));
    }

    private static void tick(Minecraft client) {
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();
        if (!cfg.isEnabled() || McCompat.screen(client) != null || !DungeonState.isInDungeon() || client.player == null) {
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
     * True while the vanilla player list must stay hidden: its key is also the Ability/Ultimate bind and the press is a
     * quick tap that casts (killer560, 2026-11-06). The list shows again once the key is held {@link #LIST_HOLD_MS}, or
     * at once when Hypixel says the ability is on cooldown (nothing was cast); outside a dungeon nothing casts, so it
     * never hides. Asked once a frame by {@code tabtop/TabOnTop}, and only does work while the list key is down.
     */
    public static boolean hidesPlayerList(Minecraft client) {
        boolean shared = false;
        if (client.options.keyPlayerList.isDown() && client.player != null && McCompat.screen(client) == null
                && DungeonState.isInDungeon()) {
            AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();
            shared = cfg.isEnabled() && (sharedDown(client, cfg.getAbilityKeyCode()) || sharedDown(client, cfg.getUltimateKeyCode()));
        }
        return listHidden(shared, System.currentTimeMillis());
    }

    /** The tap/hold rule alone (the testkit drives it with its own clock): {@code shared} is the shared key being held now. */
    public static boolean listHidden(boolean shared, long now) {
        if (!shared) {
            listHoldSince = 0;
            listCooldownSeen = false;
            return false;
        }
        if (listHoldSince == 0) {
            listHoldSince = now;
        }
        return !listCooldownSeen && now - listHoldSince < LIST_HOLD_MS;
    }

    /** The bind is held and is the key vanilla's player list is on (compared in native numbering, as LoadoutSwapper does). */
    private static boolean sharedDown(Minecraft client, int code) {
        if (!isBindDown(client, code)) {
            return false;
        }
        InputConstants.Key want = AbilityKeybindsConfig.isMouseCode(code)
                ? McInput.storedButtonInput(AbilityKeybindsConfig.mouseButton(code)) : McInput.storedKeyInput(code);
        InputConstants.Key bound = ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) client.options.keyPlayerList)
                .killer560smod$getKey();
        return bound != null && want != InputConstants.UNKNOWN && bound.getValue() == want.getValue() && bound.getType() == want.getType();
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
     *  (killer560: "make all of the keybind things compatible with mouse buttons and middle mouse buttons"). */
    private static boolean isBindDown(Minecraft client, int code) {
        if (code == -1 || client.getWindow() == null) {
            return false;
        }
        return AbilityKeybindsConfig.isMouseCode(code)
                ? KeyUtil.isMouseDown(client.getWindow(), AbilityKeybindsConfig.mouseButton(code))
                : KeyUtil.isKeyDown(client.getWindow(), code);
    }
}
