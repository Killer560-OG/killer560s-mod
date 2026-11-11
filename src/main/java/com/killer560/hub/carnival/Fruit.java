package com.killer560.hub.carnival;

/**
 * What can lie under a Fruit Digging tile (the Skyblock wiki's Fruit Digging table, which SkyHanni's matches): 34 fruits
 * of eight kinds, 10 bombs and 5 rums on the 7x7 grid. Points are the base value; Apple and Cherry grow with each one
 * already collected ({@link #value}), Pomegranate and Durian change the next dig's points, Coconut stops the next dig's
 * bomb, Watermelon reveals a neighbor for half its value.
 */
public enum Fruit {
    BOMB("Bomb", 0, 10, false, 0xFF555555),
    RUM("Rum", 0, 5, false, 0xFFAA5500),
    MANGO("Mango", 300, 10, true, 0xFFFFAA00),
    APPLE("Apple", 0, 8, true, 0xFFFF5555),
    WATERMELON("Watermelon", 100, 4, true, 0xFF55FF55),
    POMEGRANATE("Pomegranate", 200, 4, true, 0xFFAA0000),
    COCONUT("Coconut", 200, 3, true, 0xFFC2A27A),
    CHERRY("Cherry", 200, 2, true, 0xFFFF55FF),
    DURIAN("Durian", 800, 2, true, 0xFFE0E070),
    DRAGONFRUIT("Dragonfruit", 1200, 1, true, 0xFFFF55AA);

    public final String label;
    public final int points;
    public final int count;
    public final boolean edible;
    public final int color;

    Fruit(String label, int points, int count, boolean edible, int color) {
        this.label = label;
        this.points = points;
        this.count = count;
        this.edible = edible;
        this.color = color;
    }

    /** Points this fruit is worth when dug now, before the next-dig multiplier: an Apple is 100 per Apple collected
     *  counting itself, a Cherry 200 plus 300 per Cherry counting itself (SkyHanni's solver; unverified in game). */
    public double value(int applesBefore, int cherriesBefore) {
        return switch (this) {
            case APPLE -> 100.0 * (applesBefore + 1);
            case CHERRY -> 200.0 + 300.0 * (cherriesBefore + 1);
            default -> points;
        };
    }

    /** The order the Treasure and Anchor clues compare fruits by: base points, Apple lowest. */
    public int rank() {
        return this == APPLE ? 0 : points;
    }

    /** The fruit a name (as the reveal tag or the item says it) means, case-insensitive; "Dragon Fruit" too. */
    public static Fruit byName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().replace(" ", "");
        for (Fruit f : values()) {
            if (f.label.replace(" ", "").equalsIgnoreCase(n)) {
                return f;
            }
        }
        return null;
    }
}
