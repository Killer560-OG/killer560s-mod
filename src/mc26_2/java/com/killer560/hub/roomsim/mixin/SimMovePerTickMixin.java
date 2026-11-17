package com.killer560.hub.roomsim.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/** 26.2's server has no one-position-a-tick rule (no receivedPositionThisTick, javap 26.2): nothing to change. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SimMovePerTickMixin {
}
