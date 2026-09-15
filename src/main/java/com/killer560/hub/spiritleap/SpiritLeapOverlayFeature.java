package com.killer560.hub.spiritleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.LeapMenuConfig;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom Spirit Leap menu. Replaces Hypixel's "Spirit Leap" chest GUI with 4 big boxes (one per teammate, name
 * only) - the real chest and your inventory are hidden while it is open, same as the terminal Custom GUI
 * (see {@code spiritleap/mixin/SpiritLeapHideMixin}). Clicking a quarter of the screen (or pressing 1-4) sends
 * the same container click a click on that teammate's head would.
 * <p>
 * Spot order comes from the Leap Order editor ({@link com.killer560.hub.leapmenu.LeapOrderScreen}) for the class
 * you are playing; anyone not placed there fills the remaining spots in Hypixel's own order. Targets are
 * re-read from the live slots every frame because Hypixel's items arrive after the screen opens (NoammAddons
 * 26.1.2 LeapMenu does the same).
 */
public final class SpiritLeapOverlayFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-spiritleap");
    private static final Pattern IGN = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");

    private static final int BOX_W = 180;
    private static final int BOX_H = 50;
    private static final int GAP = 6;

    private record LeapTarget(String name, int slotIndex) {
    }

    /** The leap screen currently being replaced, or null - read by the hide mixin. */
    private static volatile Screen activeScreen = null;

    private SpiritLeapOverlayFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SpiritLeapOverlayFeature::onScreenInit);
    }

    /** True while {@code screen} is a leap menu this feature is drawing over (hide its background, slots, labels). */
    public static boolean isHiding(Object screen) {
        return screen != null && screen == activeScreen && SpiritLeapOverlayConfig.getInstance().isEnabled();
    }

    private static boolean isLeapTitle(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("spirit leap") || t.contains("teleport to player");
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!SpiritLeapOverlayConfig.getInstance().isEnabled()
                || !(screen instanceof AbstractContainerScreen<?> containerScreen)
                || !isLeapTitle(containerScreen.getTitle().getString())) {
            return;
        }
        activeScreen = screen;
        ScreenEvents.remove(screen).register(s -> {
            if (activeScreen == s) {
                activeScreen = null;
            }
        });

        AtomicReference<List<LeapTarget>> current = new AtomicReference<>(List.of());
        LOGGER.info("[SpiritLeap] Leap screen '{}' opened (containerId={}), playing class {}",
                containerScreen.getTitle().getString(), containerScreen.getMenu().containerId, LeapMenuFeature.playingClass());

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
            List<LeapTarget> fresh = readTargets(containerScreen);
            if (!fresh.equals(current.get())) {
                LOGGER.info("[SpiritLeap] Leap targets (containerId={}): {}", containerScreen.getMenu().containerId, fresh);
                current.set(fresh);
                PartyTracker.noteTeammates(fresh.stream().map(LeapTarget::name).toList());
            }
            if (isHiding(s)) {
                render(graphics, s.width, s.height, layout(fresh), mouseX, mouseY);
            }
        });

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!isHiding(s)) {
                return true;
            }
            LeapTarget[] spots = layout(readTargets(containerScreen));
            int spot = spotFor(event.x(), event.y(), s.width, s.height);
            if (spot >= 0 && spots[spot] != null) {
                leap(client, containerScreen, spots[spot], "click spot " + spot);
            }
            return false; // never let a click reach the hidden chest/inventory
        });
        // Releases/drags are harmless without a press, but block them anyway so nothing reaches the hidden slots.
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !isHiding(s));

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!isHiding(s)) {
                return true;
            }
            int key = event.key();
            if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_4) {
                LeapTarget[] spots = layout(readTargets(containerScreen));
                LeapTarget t = spots[key - InputConstants.KEY_1];
                if (t != null) {
                    leap(client, containerScreen, t, "key " + (key - InputConstants.KEY_1 + 1));
                }
                return false;
            }
            // Escape and the inventory key still close the menu; everything else (hotbar swaps, drop) is swallowed
            // because it would act on whatever hidden slot the mouse happens to be over.
            return key == InputConstants.KEY_ESCAPE || client.options.keyInventory.matches(event);
        });
    }

    private static void leap(Minecraft client, AbstractContainerScreen<?> screen, LeapTarget target, String how) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        LOGGER.info("[SpiritLeap] Leap to \"{}\" via {} (slot {}, containerId={})", target.name(), how,
                target.slotIndex(), screen.getMenu().containerId);
        client.gameMode.handleContainerInput(screen.getMenu().containerId, target.slotIndex(), 0,
                ContainerInput.PICKUP, client.player);
    }

    /** Player heads from the container's own slots (never the 36 appended player-inventory slots). */
    private static List<LeapTarget> readTargets(AbstractContainerScreen<?> containerScreen) {
        List<Slot> slots = containerScreen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<LeapTarget> targets = new ArrayList<>();
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack item = slots.get(i).getItem();
            if (item.isEmpty() || !item.is(Items.PLAYER_HEAD)) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(item.getHoverName().getString());
            if (plain == null) {
                continue;
            }
            Matcher m = IGN.matcher(plain.trim());
            targets.add(new LeapTarget(m.find() ? m.group(1) : plain.trim(), slots.get(i).index));
        }
        return targets;
    }

    /** Targets placed into the 4 spots by the Leap Order for the class you are playing. */
    private static LeapTarget[] layout(List<LeapTarget> targets) {
        List<String> names = targets.stream().map(LeapTarget::name).toList();
        String[] arranged = LeapMenuFeature.arrange(names,
                LeapMenuConfig.getInstance().getClassOrder(LeapMenuFeature.playingClass()));
        LeapTarget[] out = new LeapTarget[4];
        for (int spot = 0; spot < 4; spot++) {
            if (arranged[spot] == null) {
                continue;
            }
            for (LeapTarget t : targets) {
                if (t.name().equals(arranged[spot])) {
                    out[spot] = t;
                    break;
                }
            }
        }
        return out;
    }

    /** The whole screen counts as 4 quarters for clicking (Odin's behaviour) - the boxes are a visual guide. */
    private static int spotFor(double mouseX, double mouseY, int width, int height) {
        int row = mouseY >= height / 2.0 ? 1 : 0;
        int col = mouseX >= width / 2.0 ? 1 : 0;
        return row * 2 + col;
    }

    private static void render(GuiGraphicsExtractor graphics, int width, int height, LeapTarget[] spots, int mouseX, int mouseY) {
        float scale = SpiritLeapOverlayConfig.getInstance().getScale();
        float fit = Math.min((width - 20) / (float) (BOX_W * 2 + GAP * 2), (height - 20) / (float) (BOX_H * 2 + GAP * 2));
        scale = Math.max(0.4f, Math.min(scale, fit));
        int boxW = (int) (BOX_W * scale);
        int boxH = (int) (BOX_H * scale);
        int centerX = width / 2;
        int centerY = height / 2;
        int hovered = spotFor(mouseX, mouseY, width, height);
        var font = Minecraft.getInstance().font;

        for (int spot = 0; spot < 4; spot++) {
            LeapTarget target = spots[spot];
            if (target == null) {
                continue;
            }
            int x0 = spot % 2 == 0 ? centerX - GAP - boxW : centerX + GAP;
            int y0 = spot < 2 ? centerY - GAP - boxH : centerY + GAP;
            DungeonClass cls = LeapMenuConfig.getInstance().getAssignedClass(target.name());
            int color = SpiritLeapOverlayConfig.getInstance().isUseClassColors() && cls != null ? cls.color() : 0xFFCC6600;

            graphics.fill(x0, y0, x0 + boxW, y0 + boxH, spot == hovered ? 0xE0262626 : 0xCC0D0D0D);
            graphics.outline(x0, y0, boxW, boxH, color);
            float textScale = Math.max(1.0f, scale * 1.5f);
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(x0 + boxW / 2.0f, y0 + boxH / 2.0f);
                graphics.pose().scale(textScale, textScale);
                graphics.centeredText(font, target.name(), 0, -4, color);
            } finally {
                graphics.pose().popMatrix();
            }
        }
    }
}
