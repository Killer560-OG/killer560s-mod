package com.killer560.hub.hud.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Read-only access to the live boss bars, {@code private final Map<UUID, LerpingBossEvent> events} (javap-verified on
 *  both 26.1.2 and 26.2, 2026-10-25). Boss Health reads the bar's name and progress from here. Moved out of cheatutils,
 *  where nothing called it, so the legit build has it too. */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> killer560smod$getEvents();
}
