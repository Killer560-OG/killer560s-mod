package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.OriginalCollisionShapeProvider;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Real bug BOTH reference mods independently had to solve (confirmed via decompile, 2026-09-09): for a
 *  block that has REAL solid collision (a chest you can't walk through, a skull whose real vanilla
 *  {@code getCollisionShape} follows its own {@code getShape} by default) - simply expanding
 *  {@code getShape} to a full block (see {@link ChestBlockMixin}/{@link SkullBlockMixin}/
 *  {@link WallSkullBlockMixin}) would ALSO silently expand the player's own movement collision to a full
 *  1x1x1 cube, since vanilla derives collision from the outline shape unless a block overrides it
 *  separately. quoi solves this with the exact same
 *  {@code IOriginalCollisionShapeProvider}/{@code BlockBehaviourMixin} split this class ports 1:1;
 *  NoammAddons arrives at the identical fix independently, just by overriding
 *  {@code SkullBlock.getCollisionShape} directly per-class instead of through one shared interface -
 *  same end result, confirmed by two independently-written mods agreeing. Levers and Buttons don't need
 *  this at all (neither reference mod bothers) - their real vanilla collision is hardcoded empty
 *  regardless of shape, confirmed by neither one implementing this interface for those two types.
 *  <p>
 *  Targets the broad base {@code BlockBehaviour} (parent of every block in the game) rather than each
 *  block type individually, matching quoi's own choice - safe because the actual override only fires for
 *  the specific block instances whose own mixin (Chest/Skull/WallSkull) implements
 *  {@link OriginalCollisionShapeProvider}; every other block in the game passes straight through
 *  untouched.
 *  <p>
 *  A {@code @ModifyReturnValue}, not a cancellable HEAD {@code @Inject} (perfalloc-1105): the inject built a
 *  {@code CallbackInfoReturnable} on every collision-shape lookup of every block (player and entity physics, every ray
 *  of Predictive Rooms), JFR's top allocation under the predictor. Same answer: the provider's shape replaces whatever
 *  vanilla computed. */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {

    @ModifyReturnValue(method = "getCollisionShape", at = @At("RETURN"))
    private VoxelShape killer560smod$restoreOriginalCollisionShape(VoxelShape original, BlockState state, BlockGetter level,
                                                                   BlockPos pos, CollisionContext context) {
        return (Object) this instanceof OriginalCollisionShapeProvider provider
                ? provider.killer560smod$getOriginalCollisionShape(state) : original;
    }
}
