package com.killer560.hub.mining.chmap;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.miningqol.MiningState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Crystal Hollows Map as a movable, scalable HUD element (killer560, 2026-11-04: "the crystal hollows map should
 * function more like skyhanni and skyblockers do. it should be a hud element like theirs"). Like Skyblocker's Crystals
 * HUD it shows the whole cave in a small square, only while in the Crystal Hollows: the zones, the Nucleus, the
 * structures found this lobby, the saved waypoints and the travel target, and the player as an arrow turned to his
 * facing. Size comes from the HUD editor's scale. Drawn by {@code HudInGameRenderer} (id in UNDRAWN_ELEMENT_IDS).
 */
public final class ChMapHud implements HudElement {

    public static final String ID = "mining_ch_map";
    private static final int SIZE = 100;
    private static final ChMapRenderer RENDERER = new ChMapRenderer(false);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Crystal Hollows Map";
    }

    @Override
    public int defaultX() {
        // Top right: the mining HUDs fill the left column at x 10.
        Minecraft mc = Minecraft.getInstance();
        return mc.getWindow() == null ? 200 : mc.getWindow().getGuiScaledWidth() - SIZE - 6;
    }

    @Override
    public int defaultY() {
        return 56;
    }

    @Override
    public int width() {
        return SIZE;
    }

    @Override
    public int height() {
        return SIZE;
    }

    @Override
    public boolean isEnabledInSettings() {
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        return cfg.isEnabledRaw();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        boolean here = MiningState.crystalHollows();
        if (!isEnabledInSettings() || HudVisibility.hidesHud() || (!here && !HudVisibility.editorOpen())) {
            return;
        }
        // The outline sits one unit outside the square, so the square starts one in to stay inside the editor's box.
        RENDERER.setWholeCave(x + 1, y + 1, SIZE - 2);
        RENDERER.setTarget(CrystalHollowsMapScreen.targetX(), CrystalHollowsMapScreen.targetZ(),
                CrystalHollowsMapScreen.targetLabel());
        Minecraft mc = Minecraft.getInstance();
        RENDERER.draw(graphics, mc.font, here ? mc.player : null);
        HudSeen.markDrawn(ID);
    }

    /** Rectangles in the last layout (testkit: the map really drew its zones, finds and waypoints). */
    public static int layoutRects() {
        return RENDERER.rectCount();
    }
}
