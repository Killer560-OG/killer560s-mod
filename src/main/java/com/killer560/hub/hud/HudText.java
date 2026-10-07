package com.killer560.hub.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.List;

/**
 * Measuring a HUD element's box from the text it draws.
 *
 * <p>2026-10-07 (killer560: "those split timers the box is way too large for how big they actually are for the
 * editor portion"). Most text elements returned a fixed width/height picked once - 110, 140, 150 - and an audit of
 * every element's box against its drawn bounds (testkit 390-ui-hud-boxes) found 20 of them off by more than three
 * units, from Split Timers' 70 spare units to Autopilot's 76 units of text past its box. The rule now is that an
 * element measures the SAME lines it draws, through here.
 */
public final class HudText {

    /** One text row: the font's line height, glyphs plus the one-pixel gap under them. */
    public static final int ROW = 9;

    private HudText() {
    }

    /** Width of the widest non-null line: its advance, which ends one pixel past the last glyph - where a drop
     *  shadow lands, so a shadow adds nothing. */
    public static int width(String... lines) {
        Font font = Minecraft.getInstance().font;
        int w = 1;
        for (String line : lines) {
            if (line != null) {
                w = Math.max(w, font.width(line));
            }
        }
        return w;
    }

    public static int width(List<String> lines) {
        return width(lines.toArray(new String[0]));
    }

    /** Height of {@code rows} rows drawn {@code pitch} apart: the last row is only {@link #ROW} tall. */
    public static int height(int rows, int pitch) {
        return rows <= 0 ? ROW : (rows - 1) * pitch + ROW;
    }
}
