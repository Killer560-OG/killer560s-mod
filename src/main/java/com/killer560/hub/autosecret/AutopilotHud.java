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
            // The two drawn lines, one unit in (it was a fixed 240 while the editor's sample second line ran
            // to about 315).
            String[] l = lines();
            if (l == null) {
                return 20;
            }
            var font = Minecraft.getInstance().font;
            return 1 + Math.max(font.width("Autopilot ") + font.width(l[0]), font.width(l[1]));
        }

        @Override
        public int height() {
            return 11 + com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && AutoSecretConfig.getInstance().isAutopilotHud();
        }

        /** {line1, line2} as drawn right now, or null; render() and the box both read it. */
        private String[] lines() {
            if (AutoSecretFeature.isAutopilot()) {
                return AutoSecretFeature.autopilotHud();
            }
            if (lastStop != null && System.currentTimeMillis() - lastStopMs < 6000L) {
                return new String[]{"Stopped", lastStop};
            }
            if (HudVisibility.menuOpen()) {
                return new String[]{"[Solo] Clearing Mushroom",
                        "score 243 (S) | 4.7 pts in ~9 s - Solo: best score per second"};
            }
            return null;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            String[] l = lines();
            if (l == null) {
                return;
            }
            String line1 = l[0];
            String line2 = l[1];
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
