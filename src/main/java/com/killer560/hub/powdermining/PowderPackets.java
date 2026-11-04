package com.killer560.hub.powdermining;

import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

/** Packet helpers kept out of the mixin (a mixin holds no lambda). */
public final class PowderPackets {

    private PowderPackets() {
    }

    public static void onSectionChanged(ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates((pos, state) -> TreasureChests.onBlockChanged(pos));
    }
}
