package com.killer560.hub.dvd;

import java.util.UUID;

/** One configured bouncing "DVD" box. Several of these can exist and run at once - see
 *  {@link DvdConfig#entries()}. Plain mutable holder (not a record) since the tab edits fields on
 *  the live instance directly, saving after each change. */
public final class DvdEntry {

    public String id = UUID.randomUUID().toString();
    public String name = "DVD";
    public boolean enabled = true;
    public DvdContentType contentType = DvdContentType.TEXT;
    public String text = "DVD";
    public String gifFileName = "";
    /** Plain RGB hex, e.g. "FFFFFF" - no leading '#'. */
    public String textColorHex = "FFFFFF";
    public boolean backgroundEnabled = false;
    public float speedMultiplier = 1.0f;
    public int boxWidth = 160;
    public int boxHeight = 50;
    public float scale = 1.0f;
    /** Filename of a companion audio file (from the same folder GIF Player's audio uses) to play on
     *  a perfect corner hit, or blank for no sound. */
    public String cornerHitSoundFile = "";
    /** Shown on screen (not sent to chat) on a perfect corner hit. Supports {name}, replaced with
     *  the gif's filename (content type GIF) or this entry's own name (content type Text). Blank =
     *  no text shown. */
    public String cornerHitText = "";
    public boolean changeColorOnCornerHit = false;
    public boolean changeColorOnWallHit = false;

    public int textColor() {
        try {
            return 0xFF000000 | Integer.parseInt(textColorHex, 16);
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }
}
