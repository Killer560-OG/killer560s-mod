package com.killer560.hub.util;

import com.killer560.hub.BuildVariant;

/**
 * False in every testing jar, cheat and legit (killer560, 2026-11-26, the "Remove for 2.0" list: Mining, Foraging, Garden
 * Plot Menu and Farming stay out of test jars and, at the 2.0 push, out of the release). Compile-time constant: javac drops
 * every guarded branch. The gate points are listed in killer560s-mod-logs/testjar-no-mining-foraging-1126-state.md.
 */
public final class RemoveFor20 {

    public static final boolean ACTIVE = !BuildVariant.TESTING;

    private RemoveFor20() {
    }
}
