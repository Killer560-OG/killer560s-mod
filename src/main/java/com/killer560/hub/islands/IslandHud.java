package com.killer560.hub.islands;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * A HUD element of plain text lines for the Kuudra, Dojo and Other Islands features: {@link #lines()} is what is shown
 * now (empty draws nothing), {@link #sample()} what the HUD editor sizes the box by when there is nothing to show. Lines
 * may carry section-sign color codes; {@link #color()} colors the rest. Its id must be in
 * {@code HudInGameRenderer}'s list to be drawn in game.
 */
public abstract class IslandHud implements HudElement {

    /** The lines to draw now, or an empty list. Called once per frame. */
    protected abstract List<String> lines();

    /** Lines the box is sized by while there is nothing to draw. */
    protected abstract List<String> sample();

    protected int color() {
        return 0xFFFFFFFF;
    }

    @Override
    public int defaultX() {
        return 10;
    }

    private List<String> sizedFor;
    private int sizedWidth;

    private List<String> shown() {
        List<String> l = lines();
        return l == null || l.isEmpty() ? sample() : l;
    }

    @Override
    public int width() {
        List<String> l = shown();
        if (l != sizedFor) {
            sizedFor = l;
            sizedWidth = Math.max(20, HudText.width(l));
        }
        return sizedWidth;
    }

    @Override
    public int height() {
        return Math.max(1, shown().size()) * (HudText.ROW + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!isEnabledInSettings() || HudVisibility.hidesHud()) {
            return;
        }
        List<String> l = lines();
        if (l == null || l.isEmpty()) {
            return;
        }
        HudSeen.markDrawn(id());
        Minecraft mc = Minecraft.getInstance();
        int c = color();
        if ((c >>> 24) == 0) {
            c |= 0xFF000000;
        }
        int row = 0;
        for (String s : l) {
            graphics.text(mc.font, s, x, y + row * (HudText.ROW + 1), c, true);
            row++;
        }
    }
}
