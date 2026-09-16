package com.killer560.hub.realtime;

import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Real Time clock HUD, modelled on SkyHanni's "Real Time" display (TimeFeatures.kt): the current computer
 * time formatted {@code hh:mm[:ss] a} (12h) or {@code HH:mm[:ss]} (24h). Extended with a custom time zone
 * (any {@link ZoneId}), optional zone abbreviation ("EST"), optional "Real Time: " label, text color and shadow.
 * Drawn in game by {@code HudInGameRenderer} (id {@value #ELEMENT_ID}); only visible with no screen open,
 * plus a live preview inside the HUD editor.
 */
public final class RealTimeFeature {

    public static final String ELEMENT_ID = "real_time";
    private static final String LABEL = "Real Time: ";

    private static String cachedPatternKey;
    private static DateTimeFormatter cachedFormatter;

    private RealTimeFeature() {
    }

    /** Zone the clock currently shows: the computer's zone in SYSTEM mode, else the picked custom zone. */
    public static ZoneId currentZone(RealTimeConfig cfg) {
        return cfg.getZoneMode() == RealTimeConfig.ZoneMode.CUSTOM
                ? RealTimeZones.resolve(cfg.getCustomZone())
                : ZoneId.systemDefault();
    }

    /** Full HUD line, e.g. "Real Time: 09:41 PM EDT". */
    public static String buildText(RealTimeConfig cfg) {
        String time = ZonedDateTime.now(currentZone(cfg)).format(formatter(cfg));
        return cfg.isShowLabel() ? LABEL + time : time;
    }

    /** Short zone name (e.g. "CDT") for the given zone right now. */
    public static String abbreviation(ZoneId zone) {
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("zzz", Locale.US));
    }

    private static DateTimeFormatter formatter(RealTimeConfig cfg) {
        String seconds = cfg.isShowSeconds() ? ":ss" : "";
        String pattern = (cfg.isUse24Hour() ? "HH:mm" + seconds : "hh:mm" + seconds + " a")
                + (cfg.isShowZoneAbbreviation() ? " zzz" : "");
        if (!pattern.equals(cachedPatternKey)) {
            cachedFormatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
            cachedPatternKey = pattern;
        }
        return cachedFormatter;
    }

    public static final class RealTimeHudElement implements HudElement {

        public static final RealTimeHudElement INSTANCE = new RealTimeHudElement();

        private RealTimeHudElement() {
        }

        @Override
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Real Time";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 40;
        }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            if (font == null) {
                return 80;
            }
            return Math.max(20, font.width(buildText(RealTimeConfig.getInstance())) + 1);
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public boolean isRelevantNow() {
            return RealTimeConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            RealTimeConfig cfg = RealTimeConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            int color = cfg.getTextColor();
            if ((color >>> 24) == 0) {
                color |= 0xFF000000;
            }
            graphics.text(client.font, buildText(cfg), x, y, color, cfg.isTextShadow());
        }
    }
}
