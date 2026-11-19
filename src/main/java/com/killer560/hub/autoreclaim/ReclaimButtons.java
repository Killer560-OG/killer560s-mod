package com.killer560.hub.autoreclaim;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.containercover.ContainerCover;
import com.killer560.hub.croesus.AutoCroesusFeature;
import com.killer560.hub.experiments.ExperimentsFeature;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.inventorysearch.InventorySearchFeature;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** The two buttons in the /reclaim menu, drawn and clicked like Start ETable and Start Croesus (same size, same box, HUD-editable):
 *  "Reclaim Inventory" (what the inventory holds now) and "Reclaim All" (every storage page, then everything). Cheat build only. */
public final class ReclaimButtons {

    private static final String[] IDS = {"reclaim_inventory_button", "reclaim_all_button"};
    private static final String[] LABELS = {"Reclaim Inventory", "Reclaim All"};
    private static final int OFFSET = 58; // half a button plus a gap: the two sit either side of the shared start-button spot

    private ReclaimButtons() {
    }

    static void register() {
        for (int i = 0; i < IDS.length; i++) {
            final int n = i;
            HudElementRegistry.markContainerOnly(IDS[n]);
            HudElementRegistry.register(new HudElement() {
                @Override public String id() { return IDS[n]; }
                @Override public String displayName() { return LABELS[n] + " Button"; }
                @Override public int defaultX() { return AutoCroesusFeature.sharedStartButtonPosition()[0] + (n == 0 ? -OFFSET : OFFSET); }
                @Override public int defaultY() { return AutoCroesusFeature.sharedStartButtonPosition()[1]; }
                @Override public int width() { return AutoCroesusFeature.sharedStartButtonWidth(); }
                @Override public int height() { return AutoCroesusFeature.sharedStartButtonHeight(); }
                @Override public boolean isEnabledInSettings() { return wanted(); }
                @Override public void render(GuiGraphicsExtractor graphics, int x, int y) { ExperimentsFeature.drawButtonBox(graphics, x, y, LABELS[n]); }
            });
        }
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> && "Reclaim Menu".equals(screen.getTitle().getString())) {
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(g));
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y()));
            }
        });
    }

    private static boolean wanted() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && AutoReclaimConfig.getInstance().isEnabled()
                && AutoReclaimConfig.getInstance().isPullAndReclaim() && !ReclaimMenu.isRunning();
    }

    /** The button's {x, y, w, h} where it is drawn (clear of the search bar), or null when it is not shown. */
    private static int[] box(int n) {
        HudElement e = HudElementRegistry.byId(IDS[n]);
        if (e == null || !wanted()) {
            return null;
        }
        int[] pos = HudElementRegistry.resolvePosition(e);
        float scale = HudElementRegistry.resolveScale(e);
        int w = Math.round(e.width() * scale);
        int h = Math.round(e.height() * scale);
        int y = (int) InventorySearchFeature.keepAbove(McCompat.screen(Minecraft.getInstance()), pos[0], pos[1], w, h);
        return new int[]{pos[0], y, w, h};
    }

    private static void draw(GuiGraphicsExtractor g) {
        for (int n = 0; n < IDS.length; n++) {
            int[] b = box(n);
            if (b == null) {
                continue;
            }
            HudSeen.markDrawn(IDS[n]);
            ContainerCover.coverRectOnCurrentScreen("reclaim.btn" + n, b[0], b[1], b[0] + b[2], b[1] + b[3]);
            float scale = HudElementRegistry.resolveScale(HudElementRegistry.byId(IDS[n]));
            g.pose().pushMatrix();
            g.pose().translate(b[0], b[1]);
            g.pose().scale(scale, scale);
            ExperimentsFeature.drawButtonBox(g, 0, 0, LABELS[n]);
            g.pose().popMatrix();
        }
    }

    /** @return whether the click landed on a button (and started its run). */
    static boolean click(double mx, double my) {
        for (int n = 0; n < IDS.length; n++) {
            int[] b = box(n);
            if (b != null && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                if (n == 0) {
                    ReclaimMenu.startInventory();
                } else {
                    AutoReclaimFeature.startAll();
                }
                return true;
            }
        }
        return false;
    }

    /** For the testkit: the button's box {x, y, w, h}, or null. */
    public static int[] boxForTest(int n) {
        return box(n);
    }
}
