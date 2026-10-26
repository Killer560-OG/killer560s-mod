package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * "Charge 12/20" while charging, "Hold 5/20" while holding; movable in the HUD editor. It marks itself drawn
 * ({@link HudSeen}), which the editor needs to list it without Show Unseen: before 2026-10-25 it never did, so it could
 * not be moved (killer560: "moving the blink hud doesnt work with the normal hud editor").
 */
final class BlinkHud implements HudElement {

    private static final Component EXAMPLE = Component.literal("§cCharge §f12§7/20  §cHold §f5§7/20");

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
        if (McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen) {
            return EXAMPLE;
        }
        Component line = BlinkFeature.statusLine();
        return line == null ? EXAMPLE : line;
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
        Component line = editor ? EXAMPLE : BlinkFeature.statusLine();
        if (line == null) {
            return;
        }
        graphics.text(Minecraft.getInstance().font, line, x, y, 0xFFFFFFFF, true);
        if (!editor) {
            HudSeen.markDrawn(id());
            BlinkFeature.hudFrames++;
        }
    }
}
