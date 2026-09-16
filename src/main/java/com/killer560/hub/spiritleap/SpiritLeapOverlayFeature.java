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
import java.util.concurrent.atomic.AtomicLong;
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
 * 26.1.2 LeapMenu does the same) - but only once those slots have settled, see {@code MenuState}.
 */
public final class SpiritLeapOverlayFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-spiritleap");
    private static final Pattern IGN = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");

    private static final int BOX_W = 180;
    private static final int BOX_H = 50;
    private static final int GAP = 6;

    private record LeapTarget(String name, int slotIndex) {
    }

    /**
     * Per-screen "the menu has settled" state. Hypixel sends the screen first and the head stacks a few ticks later
     * (and can re-send them), so the first frames of a leap menu read empty or half-filled slots - which would draw
     * the wrong names in the wrong spots and, worse, leap to whoever happened to be in the quarter you clicked.
     * Same content-stability gate {@code terminals/TerminalSolverFeature} uses instead of a blind delay: targets are
     * only accepted once two consecutive frames read the same slots with at least one head in them, and nothing is
     * drawn or clicked before that.
     */
    private static final class MenuState {
        private List<ItemStack> snapshot = List.of();
        private boolean settled;
        private List<LeapTarget> targets = List.of();
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

    /** Exact titles only (QUOI uses equalsOneOf("Spirit Leap", "Teleport to Player")) - a substring match would also
     *  hijack and hide e.g. a Bazaar/AH page for the Spirit Leap item. */
    private static boolean isLeapTitle(String title) {
        String plain = ChatFormatting.stripFormatting(title);
        String t = plain == null ? "" : plain.trim();
        return t.equalsIgnoreCase("Spirit Leap") || t.equalsIgnoreCase("Teleport to Player");
    }

    /** One leap per press: a double-click or a held 1-4 key (key repeat reaches keyPressed) would otherwise send a
     *  second container click before Hypixel closes the menu. */
    private static final long LEAP_DEBOUNCE_MS = 750L;

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

        MenuState state = new MenuState();
        AtomicLong lastLeapAtMs = new AtomicLong(0L);
        LOGGER.info("[SpiritLeap] Leap screen '{}' opened (containerId={}), playing class {}",
                containerScreen.getTitle().getString(), containerScreen.getMenu().containerId, LeapMenuFeature.playingClass());

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
            poll(containerScreen, state);
            if (isHiding(s)) {
                // nothing is drawn until the slots settle - a couple of frames, never a fixed delay
                render(graphics, s.width, s.height, layout(state.targets), mouseX, mouseY);
            }
        });

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!isHiding(s)) {
                return true;
            }
            poll(containerScreen, state);
            LeapTarget[] spots = layout(state.targets);
            int spot = spotFor(event.x(), event.y(), s.width, s.height);
            if (state.settled && spot >= 0 && spots[spot] != null) {
                leap(client, containerScreen, spots[spot], "click spot " + spot, lastLeapAtMs);
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
                poll(containerScreen, state);
                LeapTarget[] spots = layout(state.targets);
                LeapTarget t = spots[key - InputConstants.KEY_1];
                if (state.settled && t != null) {
                    leap(client, containerScreen, t, "key " + (key - InputConstants.KEY_1 + 1), lastLeapAtMs);
                }
                return false;
            }
            // Escape and the inventory key still close the menu; everything else (hotbar swaps, drop) is swallowed
            // because it would act on whatever hidden slot the mouse happens to be over.
            return key == InputConstants.KEY_ESCAPE || client.options.keyInventory.matches(event);
        });
    }

    private static void leap(Minecraft client, AbstractContainerScreen<?> screen, LeapTarget target, String how, AtomicLong lastLeapAtMs) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLeapAtMs.get() < LEAP_DEBOUNCE_MS) {
            return;
        }
        lastLeapAtMs.set(now);
        LOGGER.info("[SpiritLeap] Leap to \"{}\" via {} (slot {}, containerId={})", target.name(), how,
                target.slotIndex(), screen.getMenu().containerId);
        client.gameMode.handleContainerInput(screen.getMenu().containerId, target.slotIndex(), 0,
                ContainerInput.PICKUP, client.player);
    }

    /**
     * Re-reads the menu's own slots and keeps {@code state.targets} in step with them, but only once the slots have
     * settled (see {@link MenuState}). After that the targets are refreshed whenever they really change - Hypixel
     * re-sends the contents of an open leap menu (a teammate dying/leaving) - but a read that has gone empty is
     * ignored: that is the menu being torn down, not a new set of targets.
     */
    private static void poll(AbstractContainerScreen<?> containerScreen, MenuState state) {
        List<ItemStack> items = containerItems(containerScreen);
        boolean anyHead = items.stream().anyMatch(item -> !item.isEmpty() && item.is(Items.PLAYER_HEAD));
        if (!state.settled) {
            if (anyHead && ItemStack.listMatches(items, state.snapshot)) {
                state.settled = true;
                state.targets = readTargets(containerScreen);
                LOGGER.info("[SpiritLeap] Leap menu settled (containerId={}): {}",
                        containerScreen.getMenu().containerId, state.targets);
                PartyTracker.noteTeammates(state.targets.stream().map(LeapTarget::name).toList());
            }
            state.snapshot = anyHead ? List.copyOf(items) : List.of();
            return;
        }
        List<LeapTarget> fresh = readTargets(containerScreen);
        if (!fresh.isEmpty() && !fresh.equals(state.targets)) {
            LOGGER.info("[SpiritLeap] Leap targets changed (containerId={}): {}",
                    containerScreen.getMenu().containerId, fresh);
            state.targets = fresh;
            PartyTracker.noteTeammates(fresh.stream().map(LeapTarget::name).toList());
        }
    }

    /** The container's own slot contents (never the 36 appended player-inventory slots). */
    private static List<ItemStack> containerItems(AbstractContainerScreen<?> containerScreen) {
        List<Slot> slots = containerScreen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<ItemStack> items = new ArrayList<>(containerSlotCount);
        for (int i = 0; i < containerSlotCount; i++) {
            items.add(slots.get(i).getItem());
        }
        return items;
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
        // Up to 400% (SpiritLeapOverlayConfig.MAX_SCALE): whatever the setting, the 2x2 grid (boxes + gaps) is shrunk to
        // fit inside the window with a 10px margin, so it never runs off-screen on small windows / big GUI scales.
        float fit = Math.min((width - 20) / (float) (BOX_W * 2 + GAP * 2), (height - 20) / (float) (BOX_H * 2 + GAP * 2));
        scale = Math.max(0.1f, Math.min(scale, fit));
        int boxW = Math.max(1, (int) (BOX_W * scale));
        int boxH = Math.max(1, (int) (BOX_H * scale));
        int gap = Math.max(2, Math.round(GAP * scale));
        int centerX = width / 2;
        int centerY = height / 2;
        int hovered = spotFor(mouseX, mouseY, width, height);
        var font = Minecraft.getInstance().font;

        for (int spot = 0; spot < 4; spot++) {
            LeapTarget target = spots[spot];
            if (target == null) {
                continue;
            }
            int x0 = spot % 2 == 0 ? centerX - gap - boxW : centerX + gap;
            int y0 = spot < 2 ? centerY - gap - boxH : centerY + gap;
            DungeonClass cls = LeapMenuConfig.getInstance().getAssignedClass(target.name());
            int color = SpiritLeapOverlayConfig.getInstance().isUseClassColors() && cls != null ? cls.color() : 0xFFCC6600;

            // Dead teammates (tab list "(DEAD)") get a red-tinted box - no extra text, and the click still goes through.
            boolean dead = PartyTracker.isDead(target.name());
            int fill = dead ? (spot == hovered ? 0xE0501414 : 0xCC350A0A) : (spot == hovered ? 0xE0262626 : 0xCC0D0D0D);
            graphics.fill(x0, y0, x0 + boxW, y0 + boxH, fill);
            graphics.outline(x0, y0, boxW, boxH, color);
            // name grows with the box but never spills out of it (long IGNs at high scale / shrunk boxes)
            float maxText = Math.min((boxW - 8) / (float) Math.max(1, font.width(target.name())), (boxH - 4) / 9.0f);
            float textScale = Math.max(0.5f, Math.min(Math.max(1.0f, scale * 1.5f), maxText));
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
