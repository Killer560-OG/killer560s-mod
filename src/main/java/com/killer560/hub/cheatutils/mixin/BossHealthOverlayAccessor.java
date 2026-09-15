package com.killer560.hub.cheatutils.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Read-only access to the live boss bars (javap-verified 26.1.2:
 *  {@code private final Map<UUID, LerpingBossEvent> events}) - Wither ESP's phase source, the same boss-bar
 *  name NoammAddons' WitherESP reads through its BossBarUpdateEvent. */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> killer560smod$getEvents();
}
