package com.killer560.hub.posmsg;

import java.util.UUID;

/** One "position message" waypoint: a chat-relayed marker at a fixed dungeon coordinate. Sending it
 *  (via the tab's Send button, {@code /killer560 posmsg send <name>}, or a Fast Leap preset) puts a
 *  tagged line in Party Chat that every party member running this mod parses back out and shows in
 *  their own Posmsg HUD list - so the whole team sees the same marker without everyone needing to
 *  type or scream coordinates. Plain mutable holder (not a record), same style as {@code DvdEntry} -
 *  the tab edits fields on the live instance and saves after each change. */
public final class PosmsgEntry {

    public String id = UUID.randomUUID().toString();
    public String name = "Waypoint";
    /** Master per-waypoint toggle - "toggle each individual circle" from killer560's request. */
    public boolean enabled = true;
    public double x;
    public double y;
    public double z;
    public double radius = 3.0;
    /** False until real coordinates have been set (either typed in or captured via "Set to my
     *  position") - a freshly seeded preset with x=y=z=0 must never actually be sendable, since 0,0,0
     *  is a real (wrong) in-world location, not an "unset" sentinel. */
    public boolean configured = false;
    public boolean showRadius = true;
    public boolean showDisplay = true;
    /** Plain RGB hex, e.g. "CC6600" - no leading '#'. */
    public String colorHex = "CC6600";
    /** If true, this waypoint only shows up in the receiving player's HUD list while THEY are
     *  standing within its radius - keeps the list decluttered for room-specific callouts. If false,
     *  it's always listed (with distance) once received, regardless of the receiver's position. */
    public boolean showOnlyInsideRadius = false;
    /** If true, this waypoint can only be sent once per dungeon run (tracked by
     *  {@link PosmsgFeature}, reset whenever a new run is detected) - killer560's "use a message more
     *  than once a run or only once a run" request. */
    public boolean onceOnlyPerRun = false;
    /** True for the mod's preloaded presets (Simon Says, EE2, EE3, etc.) - shown first in the list and
     *  never removed by "seed presets if missing," only ever edited/disabled by the player. */
    public boolean builtin = false;

    public int color() {
        try {
            return 0xFF000000 | Integer.parseInt(colorHex, 16);
        } catch (Exception e) {
            return 0xFFCC6600;
        }
    }
}
