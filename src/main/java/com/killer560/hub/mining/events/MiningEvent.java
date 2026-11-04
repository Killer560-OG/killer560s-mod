package com.killer560.hub.mining.events;

import java.util.Locale;

/** The Dwarven Mines and Crystal Hollows mining events: names and lengths as SkyHanni's {@code MiningEventType} has them. */
public enum MiningEvent {
    DOUBLE_POWDER("2x Powder", "2X POWDER", 15 * 60),
    GOBLIN_RAID("Goblin Raid", "GOBLIN RAID", 5 * 60),
    RAFFLE("Raffle", "RAFFLE", 160),
    MITHRIL_GOURMAND("Mithril Gourmand", "MITHRIL GOURMAND", 10 * 60),
    BETTER_TOGETHER("Better Together", "BETTER TOGETHER", 18 * 60),
    GONE_WITH_THE_WIND("Gone With The Wind", "GONE WITH THE WIND", 18 * 60);

    public final String label;
    /** The upper-case form Hypixel's boss bar and its STARTED line use. */
    public final String upper;
    /** How long the event runs, in seconds. */
    public final int seconds;

    MiningEvent(String label, String upper, int seconds) {
        this.label = label;
        this.upper = upper;
        this.seconds = seconds;
    }

    /** The event a chat or boss bar name means (case ignored), or null. */
    public static MiningEvent byName(String name) {
        if (name == null) {
            return null;
        }
        String u = name.trim().toUpperCase(Locale.ROOT);
        for (MiningEvent e : values()) {
            if (e.upper.equals(u)) {
                return e;
            }
        }
        return null;
    }
}
