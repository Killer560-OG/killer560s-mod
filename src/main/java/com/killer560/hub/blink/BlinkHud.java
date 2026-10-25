package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** "Blink 12/17 ticks (140)" while a hold is on; movable in the HUD editor. */
final class BlinkHud implements HudElement {

    private static final Component EXAMPLE = Component.literal("§cBlink §f12§7/17 ticks §8(140)");

    @Override
    public String id() {
        return "blink";
    }

    @Override
    public String displayName() {
        return "Blink";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 400;
    }

    private static Component text() {
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen ? EXAMPLE : BlinkFeature.statusLine();
    }

    @Override
    public int width() {
        return Math.max(1, Minecraft.getInstance().font.width(text()));
    }

    @Override
    public int height() {
        return 9;
    }

    @Override
    public boolean isEnabledInSettings() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && cfg.isEnabled() && cfg.isShowHud();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        boolean editor = McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
        if (!editor && !BlinkFeature.isHolding() && !BlinkCharge.isActive()) {
            return;
        }
        graphics.text(Minecraft.getInstance().font, text(), x, y, 0xFFFFFFFF, true);
        if (!editor) {
            BlinkFeature.hudFrames++;
        }
    }
}
