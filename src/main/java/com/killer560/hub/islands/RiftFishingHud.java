package com.killer560.hub.islands;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A movable HUD of text lines (§ codes allowed) for a Rift or Fishing feature. The supplier hands back a list the
 *  feature rebuilds on its tick, so a frame only reads it. */
public final class RiftFishingHud implements HudElement {

    private final String id;
    private final String name;
    private final int x;
    private final int y;
    private final BooleanSupplier enabled;
    private final Supplier<List<String>> lines;

    public RiftFishingHud(String id, String name, int x, int y, BooleanSupplier enabled, Supplier<List<String>> lines) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.enabled = enabled;
        this.lines = lines;
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
        return x;
    }

    @Override
    public int defaultY() {
        return y;
    }

    @Override
    public int width() {
        List<String> l = lines.get();
        int w = 0;
        for (String s : l) {
            w = Math.max(w, Minecraft.getInstance().font.width(s));
        }
        return w == 0 ? 100 : w;
    }

    @Override
    public int height() {
        return HudText.height(Math.max(1, lines.get().size()), HudText.ROW + 1);
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
        List<String> l = lines.get();
        if (l.isEmpty()) {
            return;
        }
        HudSeen.markDrawn(id);
        int dy = y;
        for (String s : l) {
            graphics.text(Minecraft.getInstance().font, s, x, dy, 0xFFFFFFFF, true);
            dy += HudText.ROW + 1;
        }
    }
}
