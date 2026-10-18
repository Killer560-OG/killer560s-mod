package com.killer560.hub.mobskin.mixin;

import com.killer560.hub.mobskin.MobSkinShape;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Attaches {@link MobSkinShape} to every {@code LivingEntityRenderState}: one extra {@code @Unique} field, null unless
 *  Play as Mob set it on your own mob's state this frame (a state is made fresh for every entity every frame). */
@Mixin(LivingEntityRenderState.class)
public abstract class MobSkinStateMixin implements MobSkinShape {

    @Unique
    private float[] killer560smod$mobShape;

    @Override
    public float[] killer560smod$mobShape() {
        return killer560smod$mobShape;
    }

    @Override
    public void killer560smod$setMobShape(float[] widthHeightThickness) {
        killer560smod$mobShape = widthHeightThickness;
    }
}
