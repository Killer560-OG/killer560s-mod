package com.killer560.hub.islands;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.Vec3;

/** World labels for the island features: billboard text, the transform Waypoint Routes and Pathfinding use. */
public final class IslandRender {

    private IslandRender() {
    }

    /** One line of text facing the camera at (x, y, z), growing with distance so it stays readable. Not through walls. */
    public static void label(LevelRenderContext context, double x, double y, double z, String text, int color) {
        label(context, x, y, z, text, color, false);
    }

    /** {@link #label}, drawn through walls when {@code walls}. */
    public static void label(LevelRenderContext context, double x, double y, double z, String text, int color,
                             boolean walls) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null || text == null || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(context);
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            McRender.rotate(poseStack, McRender.cameraRotation(context));
            poseStack.scale(s, -s, s);
            McRender.drawText(context, font, text, -McRender.textWidth(font, text) / 2f, -font.lineHeight / 2f, color | 0xFF000000,
                    false, poseStack, walls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0x40000000, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }

    /** The plain (color-stripped) custom name of an entity, or null. */
    public static String plainName(net.minecraft.world.entity.Entity e) {
        if (e == null || !e.hasCustomName() || e.getCustomName() == null) {
            return null;
        }
        String raw = e.getCustomName().getString();
        String plain = com.killer560.hub.util.ChatObserver.stripCodes(raw);
        return plain == null ? raw : plain;
    }
}
