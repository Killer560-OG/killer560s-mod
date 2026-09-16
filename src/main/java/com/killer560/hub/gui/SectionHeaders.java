package com.killer560.hub.gui;

import net.minecraft.network.chat.Component;

/** Section/feature header styling for the settings GUI (2026-09-15, killer560: "if something is added in the cheat
 *  variant that isnt in the regular make the main title for it red"). Legit features keep the bold orange header;
 *  anything that only exists in the cheat jar (whole cheat tabs, cheat-only sections inside legit tabs) gets a bold
 *  red header. Only the header changes - the controls under it keep the normal theme. Use these instead of
 *  hardcoding "§6§l" so the rule stays in one place. */
public final class SectionHeaders {

    public static final String LEGIT_PREFIX = "§6§l";
    public static final String CHEAT_PREFIX = "§c§l";
    /** ARGB colours for headers drawn with an explicit colour instead of § codes. */
    public static final int LEGIT_COLOR = 0xFFFFAA00;
    public static final int CHEAT_COLOR = 0xFFFF5555;

    private SectionHeaders() {
    }

    public static String prefix(boolean cheatOnly) {
        return cheatOnly ? CHEAT_PREFIX : LEGIT_PREFIX;
    }

    public static Component header(String title, boolean cheatOnly) {
        return Component.literal(prefix(cheatOnly) + title);
    }

    public static int color(boolean cheatOnly) {
        return cheatOnly ? CHEAT_COLOR : LEGIT_COLOR;
    }
}
