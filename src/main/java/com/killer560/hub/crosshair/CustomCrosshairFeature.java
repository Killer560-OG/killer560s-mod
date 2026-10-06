package com.killer560.hub.crosshair;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.crosshair.CustomCrosshairConfig.SizeMode;
import com.killer560.hub.hud.AutoScale;
import com.killer560.hub.hud.HudVisibility;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Custom Crosshair: replaces vanilla's crosshair HUD layer with {@link CrosshairRenderer}'s.
 * <p>
 * <b>Suppressing vanilla cleanly, on both versions.</b> Vanilla's crosshair is a Fabric HUD layer of its own
 * ({@code VanillaHudElements.CROSSHAIR}, identical in Fabric API 0.155.2+26.1.2 and 0.160.0+26.2 - javap), so this
 * WRAPS that layer with {@code replaceElement} rather than mixing into {@code Gui}: no mixin, nothing that can fail to
 * apply on 26.2 (whose {@code Gui.extractRenderState} has a different signature - docs/LESSONS.md), and with the
 * feature off the wrapped vanilla layer runs untouched. On, the vanilla layer is never called, so its crosshair sprite
 * and its attack indicator are both gone; the indicator is redrawn here (Attack Indicator setting) from the same
 * sprites and the same rule vanilla uses ({@code Gui/Hud.extractCrosshair}, javap 26.1.2 and 26.2, identical).
 * <p>
 * Kept from vanilla: F1 hides it, spectator mode and the F3 3D crosshair go back to vanilla's own handling.
 */
public final class CustomCrosshairFeature {

    private static final Identifier ATTACK_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");
    private static final Identifier ATTACK_BACKGROUND =
            Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_background");
    private static final Identifier ATTACK_PROGRESS =
            Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_progress");

    /** Frames drawn by the custom crosshair / handed to vanilla's layer (testkit evidence that the swap happened). */
    public static volatile long customFrames;
    public static volatile long vanillaFrames;

    /** Smoothed movement spread, in crosshair units. */
    private static float spread;
    private static long lastNanos;

    private CustomCrosshairFeature() {
    }

    public static void register() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR,
                vanilla -> (graphics, deltaTracker) -> extract(graphics, deltaTracker, vanilla));
    }

    /** Screen pixels per crosshair unit: the baseline GUI pixel (GUI Scale, times Auto Scale when that is on), or one
     *  screen pixel in Screen Pixels mode; times the Scale slider. */
    public static float unit(CustomCrosshairConfig c, int guiScale) {
        float base = c.getSizeMode() == SizeMode.PIXELS ? 1f : guiScale * AutoScale.current();
        return base * c.getScale();
    }

    /** The main colour right now: chroma if on. Target colours are applied by the caller. */
    public static int baseColor(CustomCrosshairConfig c) {
        int main = c.getColor();
        if (c.isChroma()) {
            long period = Math.max(100L, (long) (3000f / c.getChromaSpeed()));
            float hue = (System.currentTimeMillis() % period) / (float) period;
            main = (main & 0xFF000000) | (java.awt.Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF);
        }
        return main;
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, HudElement vanilla) {
        CustomCrosshairConfig c = CustomCrosshairConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        if (!c.isEnabled() || mc.player == null
                || (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
                || mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR)) {
            vanillaFrames++;
            vanilla.extractRenderState(graphics, deltaTracker);
            return;
        }
        customFrames++;
        if (McCompat.hudHidden(mc)) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson() && !c.isShowInThirdPerson()) {
            return;
        }
        if (c.isHideInMenus() && HudVisibility.menuOpen()) {
            return;
        }
        LocalPlayer player = mc.player;
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        float dynamic = dynamicSpread(c, player, partial);

        int main = baseColor(c);
        if (c.isColorOnEntity() && mc.crosshairPickEntity != null) {
            main = c.getEntityColor();
        } else if (c.isColorOnBlock() && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            main = c.getBlockColor();
        }
        int dot = c.isSeparateDotColor() ? c.getDotColor() : main;

        Window window = mc.getWindow();
        int gs = Math.max(1, window.getGuiScale());
        graphics.nextStratum();
        CrosshairRenderer.draw(graphics, c, window.getWidth() / 2f, window.getHeight() / 2f, gs, unit(c, gs), dynamic,
                main, dot);
        if (c.isAttackIndicator()) {
            attackIndicator(graphics, mc, player);
        }
    }

    /** Movement spread (smoothed) plus recoil, in crosshair units. */
    private static float dynamicSpread(CustomCrosshairConfig c, LocalPlayer player, float partial) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float target = 0f;
        if (c.anySpread()) {
            float amount = c.getSpreadAmount();
            Vec3 v = player.getDeltaMovement();
            boolean moving = v.x * v.x + v.z * v.z > 0.0004;
            if (c.isSpreadJumping() && !player.onGround()) {
                target = amount;
            }
            if (c.isSpreadSprinting() && moving && player.isSprinting()) {
                target = amount;
            }
            if (c.isSpreadMoving() && moving) {
                target = Math.max(target, amount * 0.5f);
            }
        }
        spread += (target - spread) * (1f - (float) Math.exp(-dt * 18f));
        if (Math.abs(spread - target) < 0.01f) {
            spread = target;
        }
        float total = spread;
        if (c.isRecoil()) {
            float anim = player.getAttackAnim(partial);
            if (anim > 0f) {
                total += c.getRecoilAmount() * (float) Math.sin(anim * Math.PI);
            }
        }
        return total;
    }

    /** Vanilla's crosshair attack indicator, same sprites, place and rule (only when vanilla's own option is
     *  Crosshair). */
    private static void attackIndicator(GuiGraphicsExtractor graphics, Minecraft mc, LocalPlayer player) {
        if (mc.options.attackIndicator().get() != AttackIndicatorStatus.CROSSHAIR) {
            return;
        }
        float strength = player.getAttackStrengthScale(0f);
        boolean full = false;
        if (mc.crosshairPickEntity instanceof LivingEntity && strength >= 1f) {
            full = player.getCurrentItemAttackStrengthDelay() > 5f && mc.crosshairPickEntity.isAlive();
            AttackRange range = player.getActiveItem().get(DataComponents.ATTACK_RANGE);
            full &= range == null || (mc.hitResult != null && range.isInRange(player, mc.hitResult.getLocation()));
        }
        int y = graphics.guiHeight() / 2 - 7 + 16;
        int x = graphics.guiWidth() / 2 - 8;
        if (full) {
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_FULL, x, y, 16, 16);
        } else if (strength < 1f) {
            int progress = (int) (strength * 17f);
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_BACKGROUND, x, y, 16, 4);
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_PROGRESS, 16, 4, 0, 0, x, y, progress, 4);
        }
    }
}
