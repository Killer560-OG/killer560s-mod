package com.killer560.hub.util;

import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;

/**
 * The one way automation changes the held hotbar slot (grim-post-autopilot-1016).
 *
 * <p>Inside {@code Minecraft.tick} (from the first START_CLIENT_TICK handler until END_CLIENT_TICK) the held-item change
 * goes out at once, through vanilla's own {@code MultiPlayerGameMode.ensureHasSentCarriedItem}, so the game mode's
 * {@code carriedIndex} agrees and its {@code tick()} does not send the slot a second time (GrimAC BadPacketsA,
 * LESSONS-AUTOMATION). Anywhere else - an END_CLIENT_TICK handler, a task or packet handler drained between ticks, a
 * render frame - those all run after the tick's {@code client_tick_end}, and a held-item change there is GrimAC "Post"
 * as soon as a transaction answer follows it before the next tick (testkit 62-argrim-1016-autosecret-key: Auto Secret
 * started by its key, END, swapping to the AOTV for a precomputed trip). So outside a tick only the slot is set, exactly
 * as vanilla's scroll wheel does it, and vanilla's {@code MultiPlayerGameMode.tick} sends it at the start of the next
 * tick, before that tick's movement packet (javap 26.1.2: {@code gameMode.tick} at offset 142 of {@code Minecraft.tick},
 * ahead of {@code tickEntities}; a use sent first in that tick sends it itself, from {@code useItem}).
 */
public final class HotbarSelect {

    private static final Identifier HEAD = Identifier.fromNamespaceAndPath("killer560smod", "hotbar_select_head");
    /** Render thread only. */
    private static boolean inTick;

    private HotbarSelect() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(HEAD, Event.DEFAULT_PHASE);
        ClientTickEvents.START_CLIENT_TICK.register(HEAD, client -> inTick = true);
        ClientTickEvents.END_CLIENT_TICK.addPhaseOrdering(HEAD, Event.DEFAULT_PHASE);
        ClientTickEvents.END_CLIENT_TICK.register(HEAD, client -> inTick = false);
    }

    // ------------------------------------------------------------------ GrimAC PacketOrderE (gridexp-1102)

    /**
     * What has left this client since the server's last TICK packet that a held-item change must not follow. GrimAC
     * 2.3.74's experimental PacketOrderE flags a {@code set_carried_item} that arrives after a use, a block click, an
     * entity interact or attack, a release, or a player command (sprint, glide, bed, mount jump) inside one of the
     * server's ticks ({@code PacketOrderProcessor}, reset by every tick packet). A vanilla client can never send that
     * order: its hotbar keys are read before the use and attack keys in {@code handleKeybinds}, and the carried item goes
     * out from {@code ensureHasSentCarriedItem} ahead of the action. The server's tick packet is a movement packet (not a
     * teleport's echo, which vanilla sends on the Connection past the send tap), or {@code client_tick_end} when no
     * movement packet went before it in that tick ({@code GrimProcessor.isTickPacket}). 62-argrim-mimic: the Hyperion's
     * use straight down and the slot handed back in the same tick; 62-argrim-1017-breakerwarp: his lever click, then the
     * breaker node's swap, in one server tick.
     */
    private static String orderConflict;
    private static boolean movedBeforeTickEnd;

    /** From the send tap ({@code RightClickSendMixin}), every packet through the listener, client thread. */
    public static void onPacketSent(net.minecraft.network.protocol.Packet<?> p) {
        if (p instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket) {
            orderConflict = null;
            movedBeforeTickEnd = true;
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundClientTickEndPacket) {
            if (!movedBeforeTickEnd) {
                orderConflict = null;
            }
            movedBeforeTickEnd = false;
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
            orderConflict = "use_item";
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket) {
            orderConflict = "use_item_on";
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket) {
            orderConflict = "interact";
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundAttackPacket) {
            orderConflict = "attack";
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket) {
            orderConflict = "player_command";
        } else if (p instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket a
                && (a.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                || a.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STAB)) {
            orderConflict = "player_action " + a.getAction();
        }
    }

    /** True when a held-item change sent now would follow a use / click / attack / command inside one server tick
     *  (GrimAC PacketOrderE): wait for the next tick before swapping. */
    public static boolean swapWouldFlag() { return orderConflict != null; }

    /** What a swap now would follow (for the hold's log line), or null. */
    public static String orderConflict() { return orderConflict; }

    /** Whether a held-item change sent now goes ahead of this tick's {@code client_tick_end}. */
    public static boolean inTick() { return inTick && Minecraft.getInstance().isSameThread(); }

    /**
     * Selects {@code slot} (0-8). @return true when the server was told now; false when the slot was already held, or
     * when vanilla tells it at the start of the next tick (called outside a tick).
     */
    public static boolean select(Minecraft client, LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) return false;
        player.getInventory().setSelectedSlot(slot);
        if (!inTick()) return false;
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        } else {
            // The invoker config did not load: vanilla's next ensureHasSentCarriedItem will send it again.
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
        return true;
    }
}
