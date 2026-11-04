package com.killer560.hub.hud;

/** A {@link HudElement} whose id, label and default position are constants. */
public abstract class BasicHud implements HudElement {

    private final String id;
    private final String displayName;
    private final int defaultX;
    private final int defaultY;

    protected BasicHud(String id, String displayName, int defaultX, int defaultY) {
        this.id = id;
        this.displayName = displayName;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public int defaultX() {
        return defaultX;
    }

    @Override
    public int defaultY() {
        return defaultY;
    }
}
