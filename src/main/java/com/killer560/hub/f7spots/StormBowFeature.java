package com.killer560.hub.f7spots;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.HotbarSelect;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Storm Bow (cheat build, default OFF; F7 Spots tab, stormm5-1111): while he charges a Last Breath (his own right click,
 * starred or not), the shot is released on the first client tick whose charge reaches Release At Tick (default 34.25,
 * so the 35th tick of the draw), then optionally the Terminator is put in hand and he walks forward for a few ticks.
 * <p>
 * The release is vanilla's own ({@code gameMode.releaseUsingItem}: the release packet and the client's arrow), sent at the
 * START of the tick where vanilla's handleKeybinds would send one. A held right button would start a new draw in that
 * same tick (a use after a release, which no vanilla tick sends), so the use delay is set to 4 as {@code startUseItem}
 * does; a held button then draws again 4 ticks later, so holding right click fires a full charge every time.
 * <p>
 * Same-tick rules (vanilla drops his attack and use clicks in a tick that releases, and reads hotbar keys before it): the
 * release waits a tick while his attack, use click or a hotbar key is queued, the Terminator swap waits out
 * {@link HotbarSelect#swapWouldFlag()} (never after the release in one server tick, GrimAC PacketOrderE), and the walk is
 * the real forward key held down, given back to the physical key when it ends (Escape, a movement key or the Emergency
 * Cancel Key end it early through {@link MacroStop}). Nothing writes a position, a velocity or a rotation.
 */
public final class StormBowFeature {

    private static final String LAST_BREATH = "LAST_BREATH";
    private static final String TERMINATOR = "TERMINATOR";
    /** Ticks a decided Terminator swap keeps asking before it is given up (the swap is never worth a late surprise). */
    private static final int SWAP_BUDGET_TICKS = 10;

    private static int swapSlot = -1;
    private static int swapWait;
    private static int walkLeft;
    /** True from a walk's start until its forward key was given back. */
    private static boolean walking;
    /** Testkit readouts. */
    private static int releases;
    private static int lastReleaseCharge;
    private static int swaps;
    private static int deferred;

    private StormBowFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        MacroStop.register("Storm Bow walk", MacroStop.Kind.MOVEMENT, () -> walking, why -> endWalk(Minecraft.getInstance()));
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("StormBowFeature.tick",
                FeatureBits.register("Storm Bow tick", () -> F7SpotsConfig.getInstance().getStormBowRaw() || walking),
                StormBowFeature::tick));
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        if (player == null || client.gameMode == null || !cfg.isStormBow()) {
            swapSlot = -1;
            endWalk(client);
            return;
        }
        if (walkLeft > 0 && --walkLeft == 0) {
            endWalk(client);
        }
        if (swapSlot >= 0) {
            if (++swapWait > SWAP_BUDGET_TICKS) {
                swapSlot = -1;
            } else if (!HotbarSelect.swapWouldFlag() && ActionGate.tryAct(ActionGate.Actor.STORM_BOW_HOTBAR)) {
                HotbarSelect.select(client, player, swapSlot);
                swaps++;
                swapSlot = -1;
            }
        }
        if (!player.isUsingItem() || McCompat.screen(client) != null
                || !ItemIdentity.matches(player.getUseItem(), LAST_BREATH)) {
            return;
        }
        int charge = 72000 - player.getUseItemRemainingTicks();
        if (charge < (int) Math.ceil(cfg.getStormBowReleaseTicks())) {
            return;
        }
        if (ActionGate.hisAttackOrSwapQueued(client) || ActionGate.hisClickQueued(client.options.keyUse)
                || !ActionGate.tryAct(ActionGate.Actor.STORM_BOW)) {
            deferred++;
            return;
        }
        client.gameMode.releaseUsingItem(player);
        if (client instanceof com.killer560.hub.zpew.mixin.ZpewMinecraftAccessor m) {
            m.killer560smod$setRightClickDelay(4);
        }
        releases++;
        lastReleaseCharge = charge;
        ModChat.log("[Storm Bow] released at charge tick " + charge);
        if (cfg.isStormBowTerminator()) {
            int slot = ItemIdentity.findHotbarSlot(player, TERMINATOR);
            if (slot >= 0 && slot != player.getInventory().getSelectedSlot()) {
                swapSlot = slot;
                swapWait = 0;
            }
        }
        if (cfg.isStormBowWalk()) {
            walkLeft = cfg.getStormBowWalkTicks();
            walking = true;
            client.options.keyUp.setDown(true);
        }
    }

    private static void endWalk(Minecraft client) {
        if (!walking) {
            return;
        }
        walkLeft = 0;
        walking = false;
        client.options.keyUp.setDown(Islands.physical(client, client.options.keyUp));
    }

    public static int releases() { return releases; }
    public static int lastReleaseCharge() { return lastReleaseCharge; }
    public static int swaps() { return swaps; }
    public static int deferred() { return deferred; }
    public static int walkLeft() { return walkLeft; }
}
