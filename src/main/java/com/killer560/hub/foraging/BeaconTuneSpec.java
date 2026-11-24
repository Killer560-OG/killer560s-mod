package com.killer560.hub.foraging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The Moonglade Beacon's two beat menus as the logger mod recorded them (killer560, 2026-11-23, one tune and one upgrade).
 * "Tune Frequency" and "Upgrade Signal Strength" are both 9x6 chests: row 10-16 shows the beacon's own beats
 * ("Match the Beat": one colored pane per beat, the rest gray, the colored pane steps one slot along the row every
 * {@code 15 + 10 * (5 - speed)} ticks and plays a note block bass sound at its pitch), row 28-34 shows the player's beats
 * the same way, and under it each player beat has four buttons: Color (left click next, right click back), Speed, Pitch
 * and Pause. The tune has one beat (buttons 46/48/50/52); the upgrade has two (37/39/41/43 and 46/48/50/52). Matching a
 * beat's color, speed, pitch and position ends the menu with the reward chat lines.
 */
final class BeaconTuneSpec {

    /** The two menu titles. The main menu ("Moonglade Beacon 0") only holds the two buttons (slots 30 and 32). */
    static final Pattern TITLE = Pattern.compile("^(Tune Frequency|Upgrade Signal Strength)$");
    static final String UPGRADE = "Upgrade Signal Strength";

    static final int TOP = 10;
    static final int BOTTOM = 28;
    static final int LEN = 7;

    /** Button slots (Color, Speed, Pitch, Pause) of each player beat. */
    static final int[][] TUNE_BEATS = {{46, 48, 50, 52}};
    static final int[][] UPGRADE_BEATS = {{37, 39, 41, 43}, {46, 48, 50, 52}};

    /** The Color button's cycle, left click forward; red wraps to white and Pitch High wraps to Low, Speed 5 to 1 (recorded 11-23 6:32 PM). */
    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "cyan",
            "purple", "blue", "brown", "green", "red");
    static final List<String> PITCHES = List.of("low", "normal", "high");
    /** The note block sound pitch each Pitch level plays (0.095, 0.794, 1.492 in the recording). */
    static final float[] PITCH_SOUND = {0.095f, 0.794f, 1.492f};
    static final String BEAT_SOUND = "block.note_block.bass";
    static final String[] DIALS = {"Color", "Speed", "Pitch"};
    static final int[] WRAP = {13, 5, 3};

    private BeaconTuneSpec() {
    }
}
