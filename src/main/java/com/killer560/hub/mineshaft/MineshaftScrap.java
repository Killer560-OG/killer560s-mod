package com.killer560.hub.mineshaft;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.mining.fossil.FossilSolver;
import com.killer560.hub.util.SackCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Suspicious Scrap button: in the Fossil Excavator menu, while no Suspicious Scrap is in your inventory or the menu, a button
 * above the panel asks the sack for one through the shared sack command sender (the same one Legit GFS uses). One click, one request.
 */
public final class MineshaftScrap {

    private static final String SCRAP = "SUSPICIOUS_SCRAP";
    private static final int W = 130;
    private static final int H = 16;
    private static int x0 = -1, y0;

    /** x, y, width, height of the button the last frame drew, or null when none showed (read by the testkit). */
    public static int[] bounds() {
        return x0 < 0 ? null : new int[] {x0, y0, W, H};
    }

    private MineshaftScrap() {
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register(MineshaftScrap::onScreenInit);
    }

    /** Whether the open menu holds a Suspicious Scrap anywhere (inventory or excavator slot). */
    static boolean held(AbstractContainerScreen<?> screen) {
        for (Slot s : screen.getMenu().slots) {
            if (SCRAP.equals(ItemIdentity.skyblockId(s.getItem()))) {
                return true;
            }
        }
        return false;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !FossilSolver.isExcavatorTitle(container.getTitle().getString())) {
            return;
        }
        x0 = -1;
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            if (!Mineshaft.CFG.on("scrap") || held(container)) {
                x0 = -1;
                return;
            }
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) container;
            x0 = acc.killer560smod$getLeftPos();
            y0 = Math.max(2, acc.killer560smod$getTopPos() - 34);
            int line = ThemeAccent.accent(0xFFCC6600);
            g.nextStratum();
            g.fill(x0, y0, x0 + W, y0 + H, 0xC0101010);
            g.outline(x0, y0, W, H, line);
            g.text(client.font, "Get Suspicious Scrap", x0 + 6, y0 + 4, 0xFFFFFFFF, true);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (x0 < 0 || !Mineshaft.CFG.on("scrap") || event.x() < x0 || event.x() >= x0 + W || event.y() < y0
                    || event.y() >= y0 + H || held(container)) {
                return true;
            }
            SackCommands.send(client, "suspicious_scrap 1");
            return false;
        });
    }
}
