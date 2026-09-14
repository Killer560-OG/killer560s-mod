package com.killer560.hub.spiritleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real Hypixel dungeon "Spirit Leap" quick-select overlay, ported from Odin's own {@code LeapMenu.kt}.
 * The real Spirit Leap item opens a real vanilla chest-style GUI titled "Spirit Leap" listing your real
 * party members, each as a real player-head item you click to teleport to them - this draws 4 large,
 * easy-to-click quadrant boxes (top-left/top-right/bottom-left/bottom-right of the screen) over that
 * real GUI, one per real listed teammate, colored by their {@link LeapMenuConfig} class assignment if
 * one exists. Clicking a quadrant performs the exact same real container click
 * ({@code MultiPlayerGameMode#handleContainerInput}, real {@code ContainerInput.PICKUP}) the already-
 * shipped, already-boot-tested {@code AutoLeapFeature} uses to click a leap target by name - this is the
 * same real action a precise click on the tiny real player-head icon would do, just from a much bigger
 * target and without needing to read anyone's name first.
 * <p>
 * Built entirely on Fabric API's real screen-events module ({@code ScreenEvents}/{@code ScreenMouseEvents},
 * already bundled with this project's Fabric API dependency - confirmed loaded via this session's own
 * boot-test logs) rather than a mixin - every callback is scoped to the specific real Spirit Leap screen
 * instance it's registered against, so this can never affect any other real vanilla or Hypixel GUI.
 */
public final class SpiritLeapOverlayFeature {

    private record LeapTarget(String name, int slotIndex) {
    }

    private SpiritLeapOverlayFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SpiritLeapOverlayFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!SpiritLeapOverlayConfig.getInstance().isEnabled()
                || !(screen instanceof AbstractContainerScreen<?> containerScreen)
                || !containerScreen.getTitle().getString().toLowerCase(Locale.ROOT).contains("leap")) {
            return;
        }

        List<Slot> slots = containerScreen.getMenu().slots;
        // Real bug class already found and fixed once in AutoLeapFeature: the menu's slot list always
        // appends the player's own 36 inventory+hotbar slots after the real container's own rows, so an
        // unbounded scan can pick up an unrelated held item. Bounded the same way.
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<LeapTarget> targets = new ArrayList<>();
        for (int i = 0; i < containerSlotCount && targets.size() < 4; i++) {
            ItemStack item = slots.get(i).getItem();
            if (item.isEmpty()) {
                continue;
            }
            targets.add(new LeapTarget(item.getHoverName().getString(), slots.get(i).index));
        }
        if (targets.isEmpty()) {
            return;
        }

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                render(graphics, scaledWidth, scaledHeight, targets));

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            int quadrant = quadrantFor(event.x(), event.y(), scaledWidth, scaledHeight);
            if (quadrant < 0 || quadrant >= targets.size()) {
                return true;
            }
            LeapTarget target = targets.get(quadrant);
            client.gameMode.handleContainerInput(containerScreen.getMenu().containerId, target.slotIndex(), 0,
                    ContainerInput.PICKUP, client.player);
            return false;
        });
    }

    /** Real Odin behavior, ported directly: the WHOLE screen counts as 4 quadrants for click purposes
     *  (not just inside the drawn boxes) - the box is a visual guide, not a strict click boundary. */
    private static int quadrantFor(double mouseX, double mouseY, int width, int height) {
        int row = mouseY >= height / 2.0 ? 1 : 0;
        int col = mouseX >= width / 2.0 ? 1 : 0;
        return row * 2 + col;
    }

    private static void render(GuiGraphicsExtractor graphics, int width, int height, List<LeapTarget> targets) {
        float scale = SpiritLeapOverlayConfig.getInstance().getScale();
        int boxW = (int) (180 * scale);
        int boxH = (int) (50 * scale);
        int gap = 6;
        int centerX = width / 2;
        int centerY = height / 2;

        for (int i = 0; i < targets.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            int x0 = col == 0 ? centerX - gap - boxW : centerX + gap;
            int y0 = row == 0 ? centerY - gap - boxH : centerY + gap;

            LeapTarget target = targets.get(i);
            DungeonClass assigned = LeapMenuConfig.getInstance().getAssignedClass(target.name());
            int color = SpiritLeapOverlayConfig.getInstance().isUseClassColors() && assigned != null
                    ? assigned.color() : 0xFFCC6600;

            graphics.fill(x0, y0, x0 + boxW, y0 + boxH, 0xCC0D0D0D);
            graphics.outline(x0, y0, boxW, boxH, color);
            graphics.text(Minecraft.getInstance().font, target.name(), x0 + 8, y0 + boxH / 2 - 4, color, false);
        }
    }
}
