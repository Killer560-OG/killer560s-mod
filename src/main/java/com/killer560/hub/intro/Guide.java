package com.killer560.hub.intro;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The tour guide: Minecraft's own 3D player model wearing killer560's skin (shipped in the jar, so it works offline),
 * drawn the way vanilla's {@code PlayerSkinWidget} draws one - {@code GuiGraphicsExtractor.skin}, a picture-in-picture
 * render with the player-skin lighting - but posed and animated here: idle breathing, a wave, pointing at what the
 * tour is talking about (turning toward it), and walking between spots.
 *
 * <p>{@code Model.Simple.setupAnim} does nothing (javap 26.3 and 26.2), so the part rotations set before
 * {@code skin(...)} are the ones the model is drawn with later in the frame; there is one guide, drawn once a frame.
 */
final class Guide {

    static final Identifier SKIN = Identifier.fromNamespaceAndPath("killer560smod", "textures/intro/killer560.png");

    /** The model's box in GUI units: the model is ~{@link #MODEL_FILL} of its height, the rest is room for a raised
     *  arm. */
    static final int BOX_W = 90;
    static final int BOX_H = 190;
    private static final float MODEL_FILL = 0.74f;
    /** Where the feet are, as a fraction of the box height from its top (measured from screenshots). */
    static final float FEET = 0.96f;
    /** Where the face is, as a fraction of the box height from its top. */
    static final float FACE = 0.32f;

    enum Pose { IDLE, WAVE, POINT, CHEER }

    private Model.Simple model;
    private ModelPart head;
    private ModelPart body;
    private ModelPart rightArm;
    private ModelPart leftArm;
    private ModelPart rightLeg;
    private ModelPart leftLeg;

    /** Box left edge (GUI units), animated between spots. */
    private float x;
    private float fromX;
    private float toX;
    private long moveStart = Long.MIN_VALUE;
    private long moveNanos;
    private boolean placed;

    private Pose pose = Pose.IDLE;
    private long poseStart;
    /** Screen point the guide points or looks at (GUI units), or NaN for the viewer. */
    private float targetX = Float.NaN;
    private float targetY = Float.NaN;
    /** Smoothed body turn, degrees. */
    private float turn;
    private float pointBlend;

    /** Walks (or, the first time, appears) so the box's left edge ends at {@code newX}. */
    void moveTo(float newX, long now) {
        if (!placed) {
            x = fromX = toX = newX;
            placed = true;
            return;
        }
        if (Math.abs(newX - toX) < 0.5f && moveStart != Long.MIN_VALUE) {
            return;
        }
        fromX = currentX(now);
        toX = newX;
        float dist = Math.abs(toX - fromX);
        moveStart = now;
        // About a block per 0.4 s at the model's size: a real walking pace, never under 0.35 s.
        moveNanos = (long) (Math.max(350f, dist * 4.2f) * 1_000_000L);
    }

    void setPose(Pose p, long now) {
        if (p != pose) {
            pose = p;
            poseStart = now;
        }
    }

    void lookAt(float tx, float ty) {
        targetX = tx;
        targetY = ty;
    }

    boolean walking(long now) {
        return moveStart != Long.MIN_VALUE && now - moveStart < moveNanos;
    }

    float currentX(long now) {
        if (!walking(now)) {
            return toX;
        }
        float t = (now - moveStart) / (float) moveNanos;
        float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
        return fromX + (toX - fromX) * e;
    }

    float targetXEnd() {
        return toX;
    }

    /** The idle bob (GUI units, up is negative), shared with nothing: the bubble has its own float. */
    private static float bob(long now) {
        return (float) Math.sin(now / 1_000_000_000.0 * 2.2) * 0.8f;
    }

    /** Where the head is drawn now (GUI units): the bubble's tail points here. */
    float headX(long now, int boxTop) {
        return currentX(now) + BOX_W / 2f;
    }

    float headY(long now, int boxTop) {
        return boxTop + BOX_H * FACE + bob(now);
    }

    private boolean ensureModel() {
        if (model != null) {
            return true;
        }
        try {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
            model = new Model.Simple(root, RenderTypes::entityTranslucent);
            head = root.getChild("head");
            body = root.getChild("body");
            rightArm = root.getChild("right_arm");
            leftArm = root.getChild("left_arm");
            rightLeg = root.getChild("right_leg");
            leftLeg = root.getChild("left_leg");
            return true;
        } catch (RuntimeException e) {
            model = null;
            return false;
        }
    }

    /** Draws the guide with the box's top at {@code boxTop}. {@code alpha} is unused by the model (always opaque). */
    void draw(GuiGraphicsExtractor g, long now, int boxTop) {
        if (!ensureModel()) {
            return;
        }
        float cx = currentX(now);
        float sec = now / 1_000_000_000f;
        float poseT = (now - poseStart) / 1_000_000_000f;
        boolean walk = walking(now);

        model.resetPose();

        // ---- facing: walking faces the way he goes, pointing turns toward the target, else a 3/4 view at the viewer
        float wantTurn;
        float centerX = cx + BOX_W / 2f;
        if (walk) {
            wantTurn = toX > fromX ? 62f : -62f;
        } else if (pose == Pose.POINT && !Float.isNaN(targetX)) {
            // Up to 40 degrees toward the side the target is on, more the further it is.
            float dx = targetX - centerX;
            wantTurn = Math.max(-40f, Math.min(40f, dx * 0.18f));
        } else if (!Float.isNaN(targetX)) {
            wantTurn = Math.max(-18f, Math.min(18f, (targetX - centerX) * 0.08f));
        } else {
            wantTurn = 0f;
        }
        // Critically damped-ish follow, frame-rate independent enough for a GUI (the clock is per frame).
        turn += (wantTurn - turn) * 0.18f;

        // ---- legs and arms: walk cycle, else breathing
        if (walk) {
            float phase = sec * 9.5f;
            float swing = (float) Math.sin(phase) * 0.75f;
            rightLeg.xRot = swing;
            leftLeg.xRot = -swing;
            rightArm.xRot = -swing * 0.8f;
            leftArm.xRot = swing * 0.8f;
        } else {
            float breathe = (float) Math.cos(sec * 1.8f) * 0.05f + 0.05f;
            rightArm.zRot = breathe;
            leftArm.zRot = -breathe;
            rightArm.xRot = (float) Math.sin(sec * 1.3f) * 0.04f;
            leftArm.xRot = -(float) Math.sin(sec * 1.3f) * 0.04f;
        }

        // ---- the gesture on top
        boolean targetLeft = !Float.isNaN(targetX) && targetX < centerX;
        ModelPart gestureArm = targetLeft ? rightArm : leftArm;
        float side = gestureArm == rightArm ? 1f : -1f; // zRot sign that lifts this arm outward
        if (!walk) {
            switch (pose) {
                case WAVE -> {
                    // Up and out, waving at the wrist-less elbow: a big friendly wave, settling after 2.4 s.
                    float k = Math.min(1f, poseT * 5f);
                    float fade = poseT > 2.4f ? Math.max(0f, 1f - (poseT - 2.4f) * 3f) : 1f;
                    float w = (float) Math.sin(poseT * 11f) * 0.32f;
                    ModelPart arm = rightArm;
                    arm.zRot = (2.55f + w) * k * fade + arm.zRot * (1 - k * fade);
                    arm.xRot = -0.15f * k * fade;
                }
                case POINT -> {
                    pointBlend = Math.min(1f, poseT * 4f);
                    float e = 1 - (1 - pointBlend) * (1 - pointBlend);
                    // Forward and a little up toward the target; the body turn does the aiming in 3D.
                    float up = 0f;
                    if (!Float.isNaN(targetY)) {
                        float dy = (boxTop + BOX_H * 0.35f) - targetY;
                        up = Math.max(-0.35f, Math.min(0.5f, dy * 0.006f));
                    }
                    gestureArm.xRot = -(1.45f + up) * e + gestureArm.xRot * (1 - e);
                    gestureArm.yRot = side * -0.12f * e;
                    gestureArm.zRot = side * 0.08f * e;
                }
                case CHEER -> {
                    // Both arms up for a moment (the theme was picked, the tour is done).
                    float k = Math.min(1f, poseT * 5f);
                    float pump = (float) Math.abs(Math.sin(poseT * 7f)) * 0.25f;
                    float fade = poseT > 1.6f ? Math.max(0f, 1f - (poseT - 1.6f) * 3f) : 1f;
                    rightArm.zRot = (2.7f - pump) * k * fade + rightArm.zRot * (1 - k * fade);
                    leftArm.zRot = -(2.7f - pump) * k * fade + leftArm.zRot * (1 - k * fade);
                }
                default -> {
                }
            }
        }

        // ---- head: looks at the target (or the viewer), a small nod while idle
        float headYaw = 0f;
        float headPitch = (float) Math.sin(sec * 0.9f) * 0.03f;
        if (!Float.isNaN(targetX) && !walk) {
            headYaw = Math.max(-0.5f, Math.min(0.5f, (targetX - centerX) * 0.004f)) - (float) Math.toRadians(turn) * 0.4f;
            float dy = targetY - (boxTop + BOX_H * FACE);
            headPitch += Math.max(-0.4f, Math.min(0.4f, dy * 0.004f));
        }
        head.yRot = -headYaw;
        head.xRot = headPitch;
        body.yRot = 0f;

        // A picture-in-picture state ignores the pose (GuiGraphicsExtractor.skin passes bare coordinates, javap 26.3 and
        // 26.2), so the box is put through the pose here: the tour is drawn under Auto Scale's pose scale.
        org.joml.Vector2f a = g.pose().transformPosition(cx, boxTop + bob(now), new org.joml.Vector2f());
        org.joml.Vector2f b = g.pose().transformPosition(cx + BOX_W, boxTop + bob(now) + BOX_H, new org.joml.Vector2f());
        int x0 = Math.round(a.x);
        int y0 = Math.round(a.y);
        int x1 = Math.round(b.x);
        int y1 = Math.round(b.y);
        float scale = (y1 - y0) * MODEL_FILL / 2.125f;
        g.skin(model, SKIN, scale, -4f, -turn, -1.0625f, x0, y0, x1, y1);
        drawn++;
        lastTurn = turn;
    }

    /** Frames the 3D model was submitted, and the body turn it was drawn with last (read by the testkit). */
    static long drawn;
    static float lastTurn;
}
