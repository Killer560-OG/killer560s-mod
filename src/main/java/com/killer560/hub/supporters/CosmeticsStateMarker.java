package com.killer560.hub.supporters;

/**
 * Duck interface {@code AvatarRenderStateMarkerMixin} attaches to every {@code AvatarRenderState} so
 * {@code CosmeticsModelShapeMixin} can tell, later in the SAME render pass's {@code scale(...)} call, whether the
 * state it's holding belongs to the local player - {@code AvatarRenderer.scale(AvatarRenderState, PoseStack)}
 * is never handed the entity itself, only this per-frame state object, so the identity check has to happen
 * earlier (at {@code extractRenderState}, where the entity IS available) and be carried forward on the state.
 * <p>
 * <b>It lives OUTSIDE the mixin package on purpose.</b> Mixin owns {@code com.killer560.hub.supporters.mixin.*}
 * through killer560smod-supporters.mixins.json, and a class in an owned package may not be referenced directly -
 * doing so threw at load: "is in a defined mixin package ... and cannot be referenced directly", which took the
 * whole game down rather than just this feature. A duck interface is normal code that mixins happen to apply, so
 * it belongs out here with the rest of the feature.
 */
public interface CosmeticsStateMarker {
    boolean killer560smod$isLocalPlayer();

    void killer560smod$setLocalPlayer(boolean value);
}
