package com.killer560.hub.posmsg;

import java.util.UUID;

/** One "position message" waypoint: a circle drawn on the ground at a fixed coordinate that sends
 *  {@link #message} to Party Chat the moment you walk into it.
 *  <p>
 *  Reworked 2026-09-16 on killer560's own spec: "Remove that hud on the top left it is unneeded for
 *  waypoints instead just draw a line around where the message should be sent if i walk into it. For
 *  send it only needs to send a message like \"at hee2\" no coordinates or anything." So there is no
 *  wire format any more (the old {@code [PM]name|x|y|z|r} payload other copies of the mod parsed back
 *  out), no HUD list, and no coordinates in the chat line - just the plain sentence, which any mod or
 *  no mod at all can read. Plain mutable holder (not a record), same style as {@code DvdEntry} - the
 *  tab edits fields on the live instance and saves after each change. */
public final class PosmsgEntry {

    public static final double MIN_RADIUS = 1.0;
    public static final double MAX_RADIUS = 10.0;
    public static final double MIN_TEXT_SCALE = 0.25;
    public static final double MAX_TEXT_SCALE = 4.0;
    public static final double MIN_TEXT_HEIGHT = 0.0;
    public static final double MAX_TEXT_HEIGHT = 5.0;

    public String id = UUID.randomUUID().toString();
    /** List label. For custom waypoints this is just the message; presets keep their room name. */
    public String name = "Waypoint";
    /** Exactly what gets typed into party chat, e.g. "at hee2". Blank falls back to {@link #name}. */
    public String message = "";
    /** Master per-waypoint toggle - "toggle each individual circle" from killer560's request. OFF by
     *  default since his 2026-09-16 test round: "in posmsg if you turn it on by default every individual
     *  one is off" - flipping the master switch must never light up seven rings at once; you opt each
     *  spot in. */
    public boolean enabled = false;
    public double x;
    public double y;
    public double z;
    public double radius = 3.0;
    /** Multiplier on the floating message label's size (1.0 = the size it has always been). Per waypoint,
     *  killer560's 2026-09-16 request for a text scale option. Clamped to {@link #MIN_TEXT_SCALE}..
     *  {@link #MAX_TEXT_SCALE} by the tab and on load. */
    public double textScale = 1.0;
    /** How many blocks above the waypoint the label floats. 0 (default) keeps the label sitting ON the
     *  waypoint, which is how it looked before this option existed (and what killer560 asked for at the
     *  time: "Make the text not offset though from the waypoint") - the option only exists so a label at
     *  floor level can be lifted when a ring is somewhere you look across, not down at. */
    public double textHeightOffset = 0.0;
    /** False until real coordinates have been set (either typed in or captured via "Set to my
     *  position") - a freshly seeded preset with x=y=z=0 must never actually be sendable, since 0,0,0
     *  is a real (wrong) in-world location, not an "unset" sentinel. */
    public boolean configured = false;
    /** Draw the circle in the world. Off = the waypoint still fires, you just can't see its edge. */
    public boolean showRadius = true;
    /** Line width of that circle, in the same units {@code WorldRenderUtils.renderLineStrip} takes. */
    public double thickness = 2.0;
    /** Plain RGB hex, e.g. "CC6600" - no leading '#'. */
    public String colorHex = "CC6600";
    /** OFF by default: the waypoint fires every single time you walk into it. Turning it on limits it
     *  to one send per dungeon run (killer560, 2026-09-16: "by default it will do it an infinate
     *  amount of times. Then if i press a button titled only send once per run, then itll send once
     *  per run. It will be an on or off button though"). */
    public boolean onceOnlyPerRun = false;
    /** True for the mod's preloaded presets (Simon Says, EE2, EE3, etc.) - shown first in the list and
     *  never removed by "seed presets if missing," only ever edited/disabled by the player. */
    public boolean builtin = false;

    /** The line actually sent to party chat. */
    public String sendText() {
        String text = message == null ? "" : message.trim();
        return text.isEmpty() ? name : text;
    }

    public static double clampRadius(double v) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, v));
    }

    public static double clampTextScale(double v) {
        return Math.max(MIN_TEXT_SCALE, Math.min(MAX_TEXT_SCALE, v));
    }

    public static double clampTextHeight(double v) {
        return Math.max(MIN_TEXT_HEIGHT, Math.min(MAX_TEXT_HEIGHT, v));
    }

    public int color() {
        try {
            return 0xFF000000 | Integer.parseInt(colorHex, 16);
        } catch (Exception e) {
            return 0xFFCC6600;
        }
    }
}
