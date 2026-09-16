package com.killer560.hub.objecthider.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code AbstractArrow.isInGround()} is {@code protected} (javap-verified against the 26.1.2 merged jar:
 *  {@code protected boolean isInGround();}), so the "Grounded Arrows" hider reaches it through an invoker,
 *  exactly like Devonian's own {@code mixin/accessor/AbstractArrowAccessor.java}. Read-only - the arrow is
 *  never mutated and never removed, only skipped for one frame. */
@Mixin(AbstractArrow.class)
public interface AbstractArrowInGroundAccessor {

    @Invoker("isInGround")
    boolean killer560smod$isInGround();
}
