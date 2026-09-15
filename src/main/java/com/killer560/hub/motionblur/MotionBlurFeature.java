package com.killer560.hub.motionblur;

/** Entry point: loads the persisted settings at client init. All rendering happens from
 *  {@code MotionBlurGameRendererMixin} -> {@link MotionBlurRenderer#onFrame}. */
public final class MotionBlurFeature {

    private MotionBlurFeature() {
    }

    public static void register() {
        MotionBlurConfig.load();
    }
}
