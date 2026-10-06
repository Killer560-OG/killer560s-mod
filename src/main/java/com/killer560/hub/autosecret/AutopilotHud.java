package com.killer560.hub.autosecret;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Dungeon Autopilot's status line (Auto Clear tab, "Autopilot HUD"): the mode and what it is doing, then the score
 * estimate and why - the same two-line shape and Fabric HUD layer as Auto Clear's status HUD.
 */
final class AutopilotHud {

    private static String lastStop;
    private static long lastStopMs;

    private AutopilotHud() {
    }

    static void register() {
        HudElementRegistry.register(ELEMENT);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "autopilot_status"), (graphics, deltaTracker) -> draw(graphics));
    }

    /** Shown for a few seconds after a run ends. */
    static void stopped(String why) {
        lastStop = why;
        lastStopMs = System.currentTimeMillis();
    }

    static final HudElement ELEMENT = new HudElement() {
        @Override
        public String id() {
            return "autopilot_status";
        }

        @Override
        public String displayName() {
            return "Autopilot Status";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 136;
        }

        @Override
        public int width() {
            return 240;
        }

        @Override
        public int height() {
            return 20;
        }

        @Override
        public boolean isEnabledInSettings() {
            return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && AutoSecretConfig.getInstance().isAutopilotHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            String line1;
            String line2;
            if (AutoSecretFeature.isAutopilot()) {
                String[] lines = AutoSecretFeature.autopilotHud();
                line1 = lines[0];
                line2 = lines[1];
            } else if (lastStop != null && System.currentTimeMillis() - lastStopMs < 6000L) {
                line1 = "Stopped";
                line2 = lastStop;
            } else if (HudVisibility.menuOpen()) {
                line1 = "[Solo] Clearing Mushroom";
                line2 = "score 243 (S) | 4.7 pts in ~9 s - Solo: best score per second";
            } else {
                return;
            }
            var font = Minecraft.getInstance().font;
            HudSeen.markDrawn(id());
            String label = "Autopilot ";
            graphics.text(font, label, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, line1, x + 1 + font.width(label), y + 1, 0xFFFFFFFF, true);
            graphics.text(font, line2, x + 1, y + 11, 0xFF000000 | ModChat.DIM, true);
        }
    };

    private static void draw(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen()
                    || !ELEMENT.isEnabledInSettings()) {
                return;
            }
            HudElementRegistry.drawAt(graphics, ELEMENT);
        } catch (RuntimeException e) {
            // never take the HUD frame down over a status line
        }
    }
}
