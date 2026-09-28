package com.killer560.hub.partyfinder.mixin;

import com.killer560.hub.partyfinder.PartyFinderLoreCache;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records a menu's lore as the SERVER sent it, before any client code can rewrite it.
 *
 * <p>At HEAD of the three container handlers, which is the earliest point the data exists on this side and
 * strictly before the menu's own {@code ItemStack}s are built - so nothing any mod does on a later tick can
 * affect what is recorded. See {@link PartyFinderLoreCache} for why that matters: another mod restyles Party
 * Finder lore in place every tick, and reading the live stack reads its edits rather than Hypixel's text.
 *
 * <p>Targets verified with javap against the 26.1.2 mapped jar:
 * {@code handleContainerContent(ClientboundContainerSetContentPacket)},
 * {@code handleContainerSetSlot(ClientboundContainerSetSlotPacket)} and
 * {@code handleContainerClose(ClientboundContainerClosePacket)} on {@code ClientPacketListener}, with the
 * content packet a record exposing {@code containerId()} and {@code items()}. That verification is not
 * ceremony - this project's mixin config uses {@code defaultRequire: 0}, so a wrong signature fails silently
 * and would leave the overlay reading mutated lore again with no error to explain it.
 *
 * <p>{@code require = 1} on every injection, deliberately overriding this config's {@code defaultRequire: 0}.
 * A silent miss here would put the overlay straight back to reading rewritten lore with nothing to explain it,
 * which is the bug this class exists to fix - so a bad target should stop the launch and say so instead.
 *
 * <p>Nothing is cancelled and no stack is copied or touched; every throw is swallowed, because this sits on the
 * packet path for every container in the game and a fault in a cache must never be able to break a menu.
 */
@Mixin(ClientPacketListener.class)
public abstract class PartyFinderLoreCacheMixin {

    @Inject(method = "handleContainerContent", at = @At("HEAD"), require = 1)
    private void killer560smod$cacheContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        try {
            PartyFinderLoreCache.onContent(packet.containerId(), packet.items());
        } catch (Throwable ignored) {
            // a cache must not break a container
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), require = 1)
    private void killer560smod$cacheSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        try {
            PartyFinderLoreCache.onSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), require = 1)
    private void killer560smod$clearOnClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        try {
            PartyFinderLoreCache.clear();
        } catch (Throwable ignored) {
        }
    }
}
