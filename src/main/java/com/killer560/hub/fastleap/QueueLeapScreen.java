package com.killer560.hub.fastleap;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.spiritleap.SpiritLeapOverlayFeature;
import com.killer560.hub.util.ActionGate;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * {@link QueueLeap}'s client-side leap menu: the custom leap menu's four boxes (Leap Order layout, class colors, faces),
 * a click on a quarter or keys 1-4 pick, Escape cancels. The server never sees it; the real menu stays hidden behind it.
 */
public final class QueueLeapScreen extends Screen implements ActionGate.NoSettleScreen {

    private final List<String> names;
    private String[] spots = new String[4];
    private boolean done;

    QueueLeapScreen(List<String> names) {
        super(Component.literal("Spirit Leap"));
        this.names = names;
    }

    /** The four spots as laid out, for tests. */
    public String[] spots() {
        return spots.clone();
    }

    @Override
    protected void init() {
        spots = SpiritLeapOverlayFeature.layoutNames(names);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // no blur or dim: the boxes are drawn over the world, as over the real leap menu
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        SpiritLeapOverlayFeature.drawBoxes(graphics, width, height, spots, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        pick(SpiritLeapOverlayFeature.spotAt(event.x(), event.y(), width, height));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_4) {
            pick(key - InputConstants.KEY_1);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Pick spot 0-3 (an empty spot does nothing). Public for the testkit. */
    public void pick(int spot) {
        String name = spot < 0 || spot > 3 ? null : spots[spot];
        if (done || name == null || name.isEmpty()) {
            return;
        }
        done = true;
        LeapManager.pickQueued(name);
        McCompat.setScreen(minecraft, null);
    }

    @Override
    public void onClose() {
        if (!done) {
            done = true;
            LeapManager.cancelQueued();
        }
        super.onClose();
    }
}
