package com.killer560.hub.position;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Advanced Position HUD - killer560: "add an advanced position mod. This should display my coordinates
 * as a hud... more precise than what f3 gives." One movable HUD element showing the local player's exact
 * X/Y/Z at a configurable decimal precision (0-8, default 5 - F3 only shows 3), plus optional facing
 * (yaw/pitch), block position and velocity lines. See {@link PositionConfig}. Ships OFF.
 *
 * <p><b>Yaw.</b> Per the mod's rotation rule (never clamp/wrap a simulated yaw), the "Yaw" value shown here
 * is the player's raw {@code getYRot()} exactly as Minecraft holds it - an uncapped running value that can
 * exceed +-360 after enough turning - with the conventional 0-360-ish wrapped value added in brackets for
 * readability, e.g. {@code "Yaw 725.12345 (5.12345)"}. This is a read-only display; nothing here ever
 * writes a rotation.
 *
 * <p><b>Performance.</b> This draws every frame, so number formatting is cached per decimal-place count
 * (a fresh {@link DecimalFormat} is expensive to build but cheap to reuse - {@code java.util.Formatter},
 * which {@code String.format} builds from scratch on every single call, is exactly what this avoids) and
 * the rendered line array is only rebuilt when a tracked input (position, facing, velocity, block, or a
 * setting) actually changed since the last frame - {@link #lastLines} is returned as-is otherwise. All of
 * this runs on the render thread only, so no synchronization is needed.
 */
public final class PositionFeature {

    public static final String HUD_ID = "advanced_position";

    private static final DecimalFormat[] FORMATTERS = new DecimalFormat[PositionConfig.MAX_DECIMAL_PLACES + 1];
    private static final StringBuilder LINE_BUILDER = new StringBuilder(48);

    // Dirty-check cache (render thread only) - see the class doc's Performance note.
    private static boolean cacheValid = false;
    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static double lastZ = Double.NaN;
    private static float lastYaw;
    private static float lastPitch;
    private static double lastVelX;
    private static double lastVelY;
    private static double lastVelZ;
    private static int lastBlockX;
    private static int lastBlockY;
    private static int lastBlockZ;
    private static int lastDecimalPlaces = -1;
    private static boolean lastShowFacing;
    private static boolean lastShowBlock;
    private static boolean lastShowVelocity;
    private static String[] lastLines = new String[0];

    private PositionFeature() {
    }

    /** Fixed-decimal formatter for {@code decimalPlaces} - built once per distinct value and reused for
     *  the life of the game, never per-frame. {@link Locale#ROOT} symbols keep the separator a plain '.'
     *  regardless of the player's system locale (no grouping commas, no scientific notation). */
    private static DecimalFormat formatter(int decimalPlaces) {
        int idx = Math.max(PositionConfig.MIN_DECIMAL_PLACES, Math.min(PositionConfig.MAX_DECIMAL_PLACES, decimalPlaces));
        DecimalFormat fmt = FORMATTERS[idx];
        if (fmt == null) {
            StringBuilder pattern = new StringBuilder("0");
            if (idx > 0) {
                pattern.append('.');
                for (int i = 0; i < idx; i++) {
                    pattern.append('0');
                }
            }
            fmt = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.ROOT));
            fmt.setGroupingUsed(false);
            FORMATTERS[idx] = fmt;
        }
        return fmt;
    }

    private static String format(double value, int decimalPlaces) {
        return formatter(decimalPlaces).format(value);
    }

    /** Builds (or reuses) the current set of HUD lines. Package-visible for the settings tab preview. */
    static String[] lines(PositionConfig cfg, LocalPlayer player) {
        int decimalPlaces = cfg.getDecimalPlaces();
        boolean showFacing = cfg.isShowFacing();
        boolean showBlock = cfg.isShowBlock();
        boolean showVelocity = cfg.isShowVelocity();

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        Vec3 velocity = showVelocity ? player.getDeltaMovement() : Vec3.ZERO;
        BlockPos block = showBlock ? player.blockPosition() : BlockPos.ZERO;

        if (cacheValid && lastX == x && lastY == y && lastZ == z && lastYaw == yaw && lastPitch == pitch
                && lastDecimalPlaces == decimalPlaces && lastShowFacing == showFacing
                && lastShowBlock == showBlock && lastShowVelocity == showVelocity
                && (!showVelocity || (lastVelX == velocity.x && lastVelY == velocity.y && lastVelZ == velocity.z))
                && (!showBlock || (lastBlockX == block.getX() && lastBlockY == block.getY() && lastBlockZ == block.getZ()))) {
            return lastLines;
        }

        int count = 3 + (showFacing ? 2 : 0) + (showBlock ? 1 : 0) + (showVelocity ? 1 : 0);
        String[] out = new String[count];
        int i = 0;
        out[i++] = coordLine("X", x, decimalPlaces);
        out[i++] = coordLine("Y", y, decimalPlaces);
        out[i++] = coordLine("Z", z, decimalPlaces);
        if (showFacing) {
            float wrappedYaw = Mth.wrapDegrees(yaw);
            LINE_BUILDER.setLength(0);
            LINE_BUILDER.append("§6Yaw §f").append(format(yaw, decimalPlaces))
                    .append(" (").append(format(wrappedYaw, decimalPlaces)).append(')');
            out[i++] = LINE_BUILDER.toString();

            LINE_BUILDER.setLength(0);
            LINE_BUILDER.append("§6Pitch §f").append(format(pitch, decimalPlaces));
            out[i++] = LINE_BUILDER.toString();
        }
        if (showBlock) {
            LINE_BUILDER.setLength(0);
            LINE_BUILDER.append("§6Block: §f").append(block.getX()).append(", ")
                    .append(block.getY()).append(", ").append(block.getZ());
            out[i++] = LINE_BUILDER.toString();
        }
        if (showVelocity) {
            LINE_BUILDER.setLength(0);
            LINE_BUILDER.append("§6Velocity: §fX ").append(format(velocity.x, decimalPlaces))
                    .append(" Y ").append(format(velocity.y, decimalPlaces))
                    .append(" Z ").append(format(velocity.z, decimalPlaces));
            out[i++] = LINE_BUILDER.toString();
        }

        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        lastVelX = velocity.x;
        lastVelY = velocity.y;
        lastVelZ = velocity.z;
        lastBlockX = block.getX();
        lastBlockY = block.getY();
        lastBlockZ = block.getZ();
        lastDecimalPlaces = decimalPlaces;
        lastShowFacing = showFacing;
        lastShowBlock = showBlock;
        lastShowVelocity = showVelocity;
        lastLines = out;
        cacheValid = true;
        return out;
    }

    private static String coordLine(String axis, double value, int decimalPlaces) {
        LINE_BUILDER.setLength(0);
        LINE_BUILDER.append("§6").append(axis).append(": §f").append(format(value, decimalPlaces));
        return LINE_BUILDER.toString();
    }

    public static final class PositionHudElement implements HudElement {

        public static final PositionHudElement INSTANCE = new PositionHudElement();

        private PositionHudElement() {
        }

        @Override
        public String id() {
            return HUD_ID;
        }

        @Override
        public String displayName() {
            return "Advanced Position";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 60;
        }

        @Override
        public int width() {
            LocalPlayer player = Minecraft.getInstance().player;
            Font font = Minecraft.getInstance().font;
            if (player == null || font == null) {
                return 90;
            }
            int max = 20;
            for (String line : lines(PositionConfig.getInstance(), player)) {
                max = Math.max(max, font.width(line) + 1);
            }
            return max;
        }

        @Override
        public int height() {
            PositionConfig cfg = PositionConfig.getInstance();
            int lines = 3 + (cfg.isShowFacing() ? 2 : 0) + (cfg.isShowBlock() ? 1 : 0) + (cfg.isShowVelocity() ? 1 : 0);
            return 12 * lines;
        }

        @Override
        public boolean isRelevantNow() {
            return PositionConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PositionConfig cfg = PositionConfig.getInstance();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int lineY = y;
            for (String line : lines(cfg, player)) {
                graphics.text(font, line, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}
