package com.killer560.hub.dungeonclass;

/** The 5 Hypixel Skyblock dungeon classes, shared across every feature that needs to color-code or
 *  label a party member by class (Leap Menu, future teammate ESP/map dot recoloring). One place for
 *  the class -&gt; color mapping so every feature stays visually consistent - per killer560's "each class
 *  has its own unique color... use these to help distinguish things as well." */
public enum DungeonClass {
    MAGE("Mage", 0xFF3B82F6),
    TANK("Tank", 0xFF22C55E),
    HEALER("Healer", 0xFFA855F7),
    ARCHER("Archer", 0xFFEF4444),
    BERSERKER("Berserker", 0xFFF97316);

    private final String displayName;
    private final int color;

    DungeonClass(String displayName, int color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** 0xAARRGGBB, ready to hand straight to {@code GuiGraphicsExtractor.fill}/{@code text}. */
    public int color() {
        return color;
    }

    /** Same color with a given alpha byte substituted in - for translucent fills (circles, panels). */
    public int colorWithAlpha(int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static DungeonClass byName(String name) {
        for (DungeonClass c : values()) {
            if (c.name().equalsIgnoreCase(name) || c.displayName.equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }
}
