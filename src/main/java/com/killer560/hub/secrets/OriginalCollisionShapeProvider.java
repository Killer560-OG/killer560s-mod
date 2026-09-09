package com.killer560.hub.secrets;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Implemented by any block-specific mixin (Chest/Skull/WallSkull) whose real vanilla
 *  {@code getCollisionShape} would otherwise follow {@code getShape} once that's expanded to a full
 *  block - see {@code BlockBehaviourMixin}'s own doc for why this split exists.
 *  <p>
 *  Real bug found and fixed (2026-09-09): this interface originally lived in
 *  {@code com.killer560.hub.secrets.mixin} alongside the actual mixin classes, which crashed the game on
 *  boot - {@code IllegalClassLoadError: ...is in a defined mixin package
 *  com.killer560.hub.secrets.mixin.* owned by killer560smod-secrets.mixins.json and cannot be referenced
 *  directly}. Mixin's classloader reserves an entire package declared as a mixin config's own
 *  {@code "package"} for actual {@code @Mixin} classes only - a plain interface can't live there even
 *  though it's only ever implemented by mixin classes. quoi's own equivalent
 *  ({@code IOriginalCollisionShapeProvider}) already keeps this exact separation (a dedicated
 *  {@code mixininterfaces} package, distinct from its {@code mixins} package) - missed porting that
 *  detail the first time around; this class's location now matches it. */
public interface OriginalCollisionShapeProvider {
    VoxelShape killer560smod$getOriginalCollisionShape(BlockState state);
}
