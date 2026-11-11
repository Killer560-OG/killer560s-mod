package com.killer560.hub.carnival;



/** Fruit Digging Solver settings (see {@link FruitDigging}). Display only; off by default. */
public final class FruitDiggingConfig extends CarnivalSettings {

    /** The best-dig highlight's color per shovel mode: the tile is drawn in the color of the mode to dig it with. */
    public static final int DEFAULT_MINES_COLOR = 0xFFFF5555;
    public static final int DEFAULT_ANCHOR_COLOR = 0xFF55FFFF;
    public static final int DEFAULT_TREASURE_COLOR = 0xFFFFAA00;
    private static final FruitDiggingConfig INSTANCE = new FruitDiggingConfig();

    private FruitDiggingConfig() {
        super("fruitdigging");
        declare("enabled", false);
        declare("bestDig", true);
        declare("clues", true);
        declare("found", true);
        declare("odds", false);
        declare("hud", true);
        declare("remaining", true);
        declare("minesColor", DEFAULT_MINES_COLOR);
        declare("anchorColor", DEFAULT_ANCHOR_COLOR);
        declare("treasureColor", DEFAULT_TREASURE_COLOR);
        declare("preventMisclicks", false);
        declare("preventWrongShovel", false);
        declare("sneakAllows", true);
    }

    /** Prevent Misclicks (legit): a dig on any grid tile but the solver's pick is canceled on the client. */
    public boolean isPreventMisclicks() {
        return bool("preventMisclicks");
    }

    public void setPreventMisclicks(boolean v) {
        put("preventMisclicks", v);
    }

    /** Prevent Wrong Shovel (legit): a dig while the shovel is not in the pick's mode is canceled on the client. */
    public boolean isPreventWrongShovel() {
        return bool("preventWrongShovel");
    }

    public void setPreventWrongShovel(boolean v) {
        put("preventWrongShovel", v);
    }

    /** Sneaking lets a click the two guards would cancel through. */
    public boolean isSneakAllows() {
        return bool("sneakAllows");
    }

    public void setSneakAllows(boolean v) {
        put("sneakAllows", v);
    }

    public static FruitDiggingConfig getInstance() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reload();
    }

    public boolean isBestDig() {
        return bool("bestDig");
    }

    public void setBestDig(boolean v) {
        put("bestDig", v);
    }

    public boolean isClues() {
        return bool("clues");
    }

    public void setClues(boolean v) {
        put("clues", v);
    }

    public boolean isFound() {
        return bool("found");
    }

    public void setFound(boolean v) {
        put("found", v);
    }

    public boolean isOdds() {
        return bool("odds");
    }

    public void setOdds(boolean v) {
        put("odds", v);
    }

    public boolean isHud() {
        return bool("hud");
    }

    public void setHud(boolean v) {
        put("hud", v);
    }

    public boolean isRemaining() {
        return bool("remaining");
    }

    public void setRemaining(boolean v) {
        put("remaining", v);
    }

    public int getMinesColor() {
        return integer("minesColor");
    }

    public void setMinesColor(int v) {
        put("minesColor", v);
    }

    public int getAnchorColor() {
        return integer("anchorColor");
    }

    public void setAnchorColor(int v) {
        put("anchorColor", v);
    }

    public int getTreasureColor() {
        return integer("treasureColor");
    }

    public void setTreasureColor(int v) {
        put("treasureColor", v);
    }

    /** The highlight color for a shovel mode name ("Mines", "Anchor", "Treasure"). */
    public int colorFor(String mode) {
        return switch (mode) {
            case "Anchor" -> getAnchorColor();
            case "Treasure" -> getTreasureColor();
            default -> getMinesColor();
        };
    }
}
