package com.killer560.hub.tooltipscroll;

import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * "Scrollable Tooltips" - killer560, 2026-09-16: "make sure it has scrollable tool tips as well."
 * <p>
 * A maxed Hyperion or a fully-enchanted chestplate has more lore lines than the window is tall, and vanilla
 * simply crops whatever doesn't fit - the bottom of the tooltip is unreachable. This shows a scrollable
 * WINDOW of the tooltip instead: the mouse wheel moves which lines are visible, and the tooltip box itself is
 * always sized to the lines actually on screen.
 * <p>
 * Approach borrowed from NotEnoughUpdates' "Scrollable Tooltips" ({@code TooltipTextScrolling}), which also
 * drops lines rather than panning the whole box - dropping lines keeps the tooltip frame fully on screen and,
 * more importantly here, means we never touch the matrix stack during tooltip rendering (a pushed matrix that
 * a thrown exception prevented from being popped would corrupt every later draw call in the frame). We
 * diverge from NEU by windowing BOTH ends (NEU only trims from whichever end you scroll towards) and by
 * offering the modifier-key gate killer560 explicitly asked for, which NEU has no equivalent of.
 * <p>
 * Hooks, both verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar:
 * <ul>
 *   <li>{@code GuiGraphicsExtractor#tooltip(Font, List&lt;ClientTooltipComponent&gt;, int, int,
 *       ClientTooltipPositioner, Identifier)} - the single place every tooltip is actually drawn (the
 *       {@code setTooltipForNextFrame} overloads all funnel into a deferred call to it). The component list
 *       is replaced there, which is also where {@link #windowFor} measures the tooltip and records whether
 *       it overflowed at all.</li>
 *   <li>{@code AbstractContainerScreen#extractTooltip(GuiGraphicsExtractor, int, int)} and
 *       {@code AbstractContainerScreen#mouseScrolled(double, double, double, double)} - hover tracking and
 *       the wheel itself. Every Skyblock menu is a container screen, so this covers all real item lore.</li>
 * </ul>
 * Nothing here is Skyblock-specific, so it is deliberately NOT behind {@code SkyblockGate} - see
 * {@link TooltipScrollConfig}'s class doc.
 */
public final class TooltipScrollFeature {

    /** Vertical space the tooltip chrome needs outside the component list: the background's own top/bottom
     *  padding plus the screen-edge margin both tooltip positioners keep (MenuTooltipPositioner MARGIN = 5,
     *  private, so it is repeated here rather than referenced). Read off the 26.1.2 jar with javap. */
    private static final int VERTICAL_CHROME = TooltipRenderUtil.PADDING_TOP + TooltipRenderUtil.PADDING_BOTTOM + 5 + 5;

    /** Number of leading tooltip components currently scrolled past. */
    private static int offset;
    /** Largest {@link #offset} that still shows the final line - recomputed every time a tooltip is drawn. */
    private static int maxOffset;
    /** Whether the tooltip drawn last frame was taller than the screen. killer560's "only engage when the
     *  tooltip actually overflows": while this is false the wheel is never consumed, so a short tooltip
     *  behaves exactly as it does today. */
    private static boolean scrollable;
    /** The hovered stack the current {@link #offset} belongs to; weak so a cached stack can never pin an
     *  item (or its whole container) in memory. */
    private static WeakReference<ItemStack> hovered = new WeakReference<>(null);

    private TooltipScrollFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Hover tracking only runs inside container screens; without this, closing one would leave a
            // stale offset that the next unrelated (e.g. widget) tooltip would silently inherit.
            if (!(client.screen instanceof AbstractContainerScreen<?>)) {
                setHovered(null);
            }
        });
    }

    // ---------------------------------------------------------------- hover tracking

    /** Called every frame from the container-screen mixin with whatever stack is under the cursor (null when
     *  nothing is). Changing item resets the scroll - killer560: "scroll resets when you hover a different
     *  item". Compared by identity, like {@code ItemRarityFeature}'s cache, because {@link ItemStack} has no
     *  value equality. */
    public static void setHovered(ItemStack stack) {
        ItemStack previous = hovered.get();
        if (previous == stack) {
            return;
        }
        hovered = new WeakReference<>(stack);
        offset = 0;
        maxOffset = 0;
        scrollable = false;
    }

    // ---------------------------------------------------------------- the wheel

    /** @return true if the scroll was consumed, in which case the container screen must not also act on it. */
    public static boolean onMouseScrolled(double scrollY) {
        TooltipScrollConfig cfg = TooltipScrollConfig.getInstance();
        // Not enabled, nothing hovered, or a tooltip that already fits: hand the wheel straight back so it
        // keeps doing whatever it normally does (hotbar slot, another mod's scrollable list, bundles).
        if (!cfg.isEnabled() || !scrollable || hovered.get() == null || scrollY == 0.0) {
            return false;
        }
        if (cfg.isRequireModifier()) {
            Minecraft client = Minecraft.getInstance();
            if (!KeyUtil.isKeyDown(client.getWindow(), cfg.getModifierKey())) {
                return false;
            }
        }
        // Wheel up shows earlier lines, matching every other scroll surface in the game.
        int direction = scrollY > 0 ? -1 : 1;
        if (cfg.isInvert()) {
            direction = -direction;
        }
        offset = clamp(offset + direction * cfg.getLinesPerScroll(), 0, maxOffset);
        // Consumed even when already clamped at an end, so the wheel can't "fall through" to the container
        // the moment you hit the top or bottom of a long tooltip.
        return true;
    }

    // ---------------------------------------------------------------- rendering

    /**
     * Replaces the component list about to be drawn with the visible window. Returns {@code components}
     * unchanged - and leaves the tooltip behaving exactly as it does today - whenever the feature is off,
     * nothing is hovered, or the tooltip already fits.
     */
    public static List<ClientTooltipComponent> windowFor(GuiGraphicsExtractor graphics, Font font,
                                                        List<ClientTooltipComponent> components) {
        TooltipScrollConfig cfg = TooltipScrollConfig.getInstance();
        if (!cfg.isEnabled() || font == null || components == null || components.size() < 2 || hovered.get() == null) {
            scrollable = false;
            maxOffset = 0;
            return components;
        }

        int n = components.size();
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = components.get(i).getHeight(font);
        }

        int available = graphics.guiHeight() - VERTICAL_CHROME;
        if (available < 10 || contentHeight(heights, 0, n) <= available) {
            scrollable = false;
            maxOffset = 0;
            return components;
        }

        // Smallest start index whose tail still fits: scrolling all the way down lands exactly on the last line.
        int smallestFittingStart = n - 1;
        while (smallestFittingStart > 0 && contentHeight(heights, smallestFittingStart - 1, n) <= available) {
            smallestFittingStart--;
        }
        scrollable = true;
        maxOffset = smallestFittingStart;
        offset = clamp(offset, 0, maxOffset);

        int end = offset + 1;
        while (end < n && contentHeight(heights, offset, end + 1) <= available) {
            end++;
        }
        if (offset == 0 && end == n) {
            return components;
        }
        return components.subList(offset, end);
    }

    /** Mirrors vanilla {@code GuiGraphicsExtractor#tooltip}'s own height arithmetic exactly, including its
     *  {@code -2} for a single-component tooltip, so "does it overflow" is measured the same way the
     *  renderer measures it. */
    private static int contentHeight(int[] heights, int from, int to) {
        int total = (to - from == 1) ? -2 : 0;
        for (int i = from; i < to; i++) {
            total += heights[i];
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
