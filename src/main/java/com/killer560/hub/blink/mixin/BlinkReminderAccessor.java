package com.killer560.hub.blink.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's position reminder count (a still player resends his position every 20 ticks), see BlinkCharge#dropStash. */
@Mixin(LocalPlayer.class)
public interface BlinkReminderAccessor {

    @Accessor("positionReminder")
    int killer560$positionReminder();

    @Accessor("positionReminder")
    void killer560$setPositionReminder(int ticks);

    /** The yaw the last movement packet reported (util/PlaceRotationGuard#beforeUse). */
    @Accessor("yRotLast")
    float killer560$yRotLast();

    /** What vanilla believes the server last heard (invwalk/InventoryWalkFeature#standUpForMenu). */
    @Accessor("lastSentInput")
    void killer560$setLastSentInput(net.minecraft.world.entity.player.Input input);
}
