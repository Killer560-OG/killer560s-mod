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
 * render with the player-skin lighting - but posed here: a wave, pointing at what the tour is talking about, a cheer,
 * and walking between spots, always facing the way he walks and, standing, toward what he talks about.
 *
 * <p>Standing he is still (killer560, 2026-11-01: no idle bob; nothing moves while he stands, so the bubble's tail stays
 * on his mouth). {@code Model.Simple.setupAnim} does nothing (javap 26.3 and 26.2), so the part rotations set before
 * {@code skin(...)} are the ones the model is drawn with later in the frame; there is one guide, drawn once a frame.
 *
 * <p>{@link #facing}: degrees, positive = his face turned toward the right of the screen. {@code skin()}'s rotationY
 * turns him that way for a positive value (the first version passed the negative and he walked backwards; testkit
 * 1101-ui-intro-facing reads which side of his head the eyes are on).
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
    /** Model pixels (1/16 block) above the soles: the mouth (the head's front face, bottom row) is 24.5 up. */
    private static final float MOUTH_UP_PX = 24.5f;
    /** The head's front face is 4 model pixels in front of the body's vertical axis. */
    private static final float FACE_DEPTH_PX = 4f;
    private static final float TILT_DEG = -4f;

    enum Pose { IDLE, WAVE, POINT, CHEER }

    private Model.Simple model;
    private ModelPart head;
    private ModelPart rightArm;
    private ModelPart leftArm;
    private ModelPart rightLeg;
    private ModelPart leftLeg;

    /** Box left edge (GUI units), animated between spots. */
    private float fromX;
    private float toX;
    private long moveStart = Long.MIN_VALUE;
    private long moveNanos;
    private boolean placed;

    private Pose pose = Pose.IDLE;
    private long poseStart;
    /** Screen point he faces and points at when standing (GUI units), or NaN for the viewer. */
    private float targetX = Float.NaN;
    private float targetY = Float.NaN;
    /** His facing now (degrees, see the class doc), eased toward the wanted one. */
    private float facing;
    private long lastFrame = Long.MIN_VALUE;

    /** Walks (or, the first time, appears) so the box's left edge ends at {@code newX}. */
    void moveTo(float newX, long now) {
        if (!placed) {
            fromX = toX = newX;
            placed = true;
            return;
        }
        if (Math.abs(newX - toX) < 0.5f) {
            return; // already there or already going there: never a walk on the spot
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

    /** The facing he turns toward now: the way he walks, else toward his target (up to 35 degrees, never past it). */
    private float wantedFacing(long now, float centerX) {
        if (walking(now)) {
            return toX > fromX ? 70f : -70f;
        }
        if (Float.isNaN(targetX)) {
            return 0f;
        }
        float dx = targetX - centerX;
        float max = pose == Pose.POINT ? 35f : 25f;
        return Math.max(-max, Math.min(max, dx * 0.15f));
    }

    /** Model pixel (1/16 block) size in GUI units. */
    private static float px() {
        return BOX_H * MODEL_FILL / (2.125f * 16f);
    }

    /** Where his mouth is drawn now (GUI units): the bubble's tail comes from here. */
    float mouthX(long now) {
        double f = Math.toRadians(facing);
        return currentX(now) + BOX_W / 2f + (float) (Math.sin(f) * FACE_DEPTH_PX * px());
    }

    float mouthY(long now, int boxTop) {
        // The -4 degree tilt leans the head back a little: its front face rises by depth * sin(tilt) (a hair).
        double tilt = Math.toRadians(-TILT_DEG);
        float lean = (float) (Math.sin(tilt) * FACE_DEPTH_PX * Math.cos(Math.toRadians(facing)));
        return boxTop + BOX_H * FEET - (MOUTH_UP_PX + lean) * px();
    }

    private boolean ensureModel() {
        if (model != null) {
            return true;
        }
        try {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
            model = new Model.Simple(root, RenderTypes::entityTranslucent);
            head = root.getChild("head");
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

    /** Eases the facing toward the wanted one (call once a frame, before reading the mouth). */
    void update(long now) {
        float centerX = currentX(now) + BOX_W / 2f;
        float want = wantedFacing(now, centerX);
        float dt = lastFrame == Long.MIN_VALUE ? 1f : Math.max(0f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        // ~90% of the way in 0.25 s whatever the frame rate; the instant test clock (dt 0) keeps the last value.
        float k = dt >= 1f ? 1f : 1f - (float) Math.exp(-dt * 9.2);
        if (com.killer560.hub.intro.SpeechBubble.instant()) {
            k = 1f;
        }
        facing += (want - facing) * k;
    }

    /** Draws the guide with the box's top at {@code boxTop}. */
    void draw(GuiGraphicsExtractor g, long now, int boxTop) {
        if (!ensureModel()) {
            return;
        }
        float cx = currentX(now);
        float sec = now / 1_000_000_000f;
        float poseT = (now - poseStart) / 1_000_000_000f;
        boolean walk = walking(now);

        model.resetPose();

        if (walk) {
            float phase = sec * 9.5f;
            float swing = (float) Math.sin(phase) * 0.75f;
            rightLeg.xRot = swing;
            leftLeg.xRot = -swing;
            rightArm.xRot = -swing * 0.8f;
            leftArm.xRot = swing * 0.8f;
        } else {
            // Standing still: arms a hair away from the body, nothing moving.
            rightArm.zRot = 0.06f;
            leftArm.zRot = -0.06f;
        }

        // The gesture: the arm on the target's side of the screen. Facing the viewer his right arm is on the screen's
        // left, so a target on the left is pointed at with his right arm.
        float centerX = cx + BOX_W / 2f;
        boolean targetLeft = !Float.isNaN(targetX) && targetX < centerX;
        ModelPart gestureArm = targetLeft ? rightArm : leftArm;
        float side = gestureArm == rightArm ? 1f : -1f; // zRot sign that lifts this arm outward
        if (!walk) {
            switch (pose) {
                case WAVE -> {
                    float k = Math.min(1f, poseT * 5f);
                    float fade = poseT > 2.4f ? Math.max(0f, 1f - (poseT - 2.4f) * 3f) : 1f;
                    float w = (float) Math.sin(poseT * 11f) * 0.32f;
                    rightArm.zRot = (2.55f + w) * k * fade + rightArm.zRot * (1 - k * fade);
                    rightArm.xRot = -0.15f * k * fade;
                }
                case POINT -> {
                    float b = Math.min(1f, poseT * 4f);
                    float e = 1 - (1 - b) * (1 - b);
                    float up = 0f;
                    if (!Float.isNaN(targetY)) {
                        float dy = (boxTop + BOX_H * 0.35f) - targetY;
                        up = Math.max(-0.35f, Math.min(0.5f, dy * 0.006f));
                    }
                    gestureArm.xRot = -(1.45f + up) * e + gestureArm.xRot * (1 - e);
                    gestureArm.zRot = side * 0.08f * e + gestureArm.zRot * (1 - e);
                }
                case CHEER -> {
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
        head.yRot = 0f;
        head.xRot = 0f;

        // A picture-in-picture state ignores the pose (GuiGraphicsExtractor.skin passes bare coordinates, javap 26.3 and
        // 26.2), so the box is put through the pose here: the tour is drawn under Auto Scale's pose scale.
        org.joml.Vector2f a = g.pose().transformPosition(cx, boxTop, new org.joml.Vector2f());
        org.joml.Vector2f c = g.pose().transformPosition(cx + BOX_W, boxTop + BOX_H, new org.joml.Vector2f());
        int x0 = Math.round(a.x);
        int y0 = Math.round(a.y);
        int x1 = Math.round(c.x);
        int y1 = Math.round(c.y);
        float scale = (y1 - y0) * MODEL_FILL / 2.125f;
        g.skin(model, SKIN, scale, TILT_DEG, facing, -1.0625f, x0, y0, x1, y1);
        drawn++;
        lastFacing = facing;
        lastBox = new int[]{x0, y0, x1, y1};
    }

    /** Frames the 3D model was submitted, the facing it was drawn with last and its box in window GUI units
     *  (read by the testkit). */
    static long drawn;
    static float lastFacing;
    static int[] lastBox = new int[4];
}
