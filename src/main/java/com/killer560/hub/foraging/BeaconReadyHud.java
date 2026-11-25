package com.killer560.hub.foraging;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Beacon Ready Alert's one HUD line: the cooldown counting down, then "Beacon Ready" until the beacon is tuned. */
public final class BeaconReadyHud extends BasicHud {

    private static final String SAMPLE = "Beacon: 4m 12s";

    private static volatile String drawn;

    /** What the last frame drew, null when it drew nothing (testkit). */
    public static String lastDrawn() {
        return drawn;
    }

    BeaconReadyHud() {
        super("beacon_ready", "Beacon Ready", 10, 150);
    }

    private static String text() {
        String t = BeaconReadyAlert.hudText(System.currentTimeMillis());
        return t == null && McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen ? SAMPLE : t;
    }

    @Override
    public int width() {
        String t = text();
        return HudText.width(t == null ? SAMPLE : t);
    }

    @Override
    public int height() {
        return HudText.ROW;
    }

    @Override
    public boolean isEnabledInSettings() {
        return BeaconReadyAlert.CFG.isEnabledRaw() && (BeaconReadyAlert.CFG.on("keep") || BeaconReadyAlert.CFG.on("cooldownHud"));
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        String t = HudVisibility.hidesHud() ? null : text();
        drawn = t;
        if (t != null) {
            HudSeen.markDrawn(id());
            graphics.text(Minecraft.getInstance().font, t, x, y,
                    ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE)), true);
        }
    }
}
