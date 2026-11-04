package com.killer560.hub.mining;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A movable HUD element that draws a few lines of text (staging-mining): the Mining category's HUDs are all this shape.
 * Lines may carry section-sign color codes. While the HUD editor is open and there is nothing to show, it draws its
 * sample lines instead, so it can be placed off the island; it marks itself drawn ({@link HudSeen}) only for real lines.
 */
public final class MiningTextHud implements HudElement {

    private final String id;
    private final String name;
    private final int defaultX;
    private final int defaultY;
    private final BooleanSupplier enabled;
    private final Supplier<List<String>> lines;
    private final List<String> sample;

    public MiningTextHud(String id, String name, int defaultX, int defaultY, BooleanSupplier enabled,
                         Supplier<List<String>> lines, List<String> sample) {
        this.id = id;
        this.name = name;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.enabled = enabled;
        this.lines = lines;
        this.sample = sample;
    }

    private List<String> shown() {
        List<String> l = lines.get();
        if ((l == null || l.isEmpty()) && HudVisibility.editorOpen()) {
            return sample;
        }
        return l == null ? List.of() : l;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public int defaultX() {
        return defaultX;
    }

    @Override
    public int defaultY() {
        return defaultY;
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int w = 1;
        for (String s : shown()) {
            w = Math.max(w, font.width(s));
        }
        return w;
    }

    @Override
    public int height() {
        return HudText.height(Math.max(1, shown().size()), 10);
    }

    @Override
    public boolean isEnabledInSettings() {
        return enabled.getAsBoolean();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!enabled.getAsBoolean() || HudVisibility.hidesHud()) {
            return;
        }
        List<String> real = lines.get();
        List<String> draw = shown();
        Font font = Minecraft.getInstance().font;
        int lineY = y;
        for (String s : draw) {
            graphics.text(font, s, x, lineY, 0xFFFFFFFF, true);
            lineY += 10;
        }
        if (real != null && !real.isEmpty()) {
            HudSeen.markDrawn(id);
        }
    }
}
