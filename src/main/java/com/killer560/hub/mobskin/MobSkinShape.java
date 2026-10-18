package com.killer560.hub.mobskin;

/**
 * Duck interface {@code MobSkinStateMixin} puts on every {@code LivingEntityRenderState}: the Width/Height/Thickness
 * stretch {@link MobSkinRender} sets on YOUR mob's state, which {@code MobSkinShapeMixin} applies inside
 * {@code LivingEntityRenderer.submit} right after the renderer's own {@code scale} - the same spot in the chain where
 * {@code CosmeticsModelShapeMixin} stretches the player model. Null (the default, and every state but yours) means none.
 * Outside the mixin package on purpose (see {@code CosmeticsStateMarker}).
 */
public interface MobSkinShape {

    float[] killer560smod$mobShape();

    void killer560smod$setMobShape(float[] widthHeightThickness);
}
