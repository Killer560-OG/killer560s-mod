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
 * simply draws the whole thing anyway - whatever falls off the top or bottom of the screen is rendered but
 * invisible, and permanently unreachable. This <b>moves the whole rendered tooltip</b> up and down with the
 * mouse wheel - the background box and every line inside it translate together as one rigid block, sliding
 * previously-hidden lines into view exactly the way a window pans over a taller document.
 * <p>
 * <b>2026-09-20 rewrite, killer560</b> (after trying the first version): "There is a chance it is actually
 * scrolling the tooltip instead of just moving the tooltip itself. It should just move not actually scroll a
 * really long one." The original design deliberately sliced the component list down to only the lines that
 * fit (see git history / old {@code windowFor}), resizing the background box every scroll step, specifically
 * to avoid ever touching the render matrix stack - a pushed matrix that a thrown exception prevented from
 * being popped would corrupt every later draw call in the frame. That concern is still real and is why the
 * new {@link com.killer560.hub.tooltipscroll.mixin.TooltipScrollGraphicsMixin} pushes/translates/pops inside
 * a {@code try/finally}, following the same pattern already used throughout this repo for GUI-space
 * transforms (e.g. {@code F7SpotsFeature}, {@code HudInGameRenderer}) - see that mixin's own doc.
 * <p>
 * Hooks, both re-verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar as part of the 2026-09-20
 * investigation below:
 * <ul>
 *   <li>{@code GuiGraphicsExtractor#tooltip(Font, List&lt;ClientTooltipComponent&gt;, int, int,
 *       ClientTooltipPositioner, Identifier)} - the single place every tooltip is actually drawn (the
 *       {@code setTooltipForNextFrame} overloads all funnel into a deferred call to it). {@link
 *       #pixelOffsetFor} measures the tooltip here and records whether it overflowed at all; the mixin wraps
 *       the whole call in a translate built from that measurement.</li>
 *   <li>{@code AbstractContainerScreen#extractTooltip(GuiGraphicsExtractor, int, int)} and
 *       {@code AbstractContainerScreen#mouseScrolled(double, double, double, double)} - hover tracking and
 *       the wheel itself. Every Skyblock menu is a container screen, so this covers all real item lore.</li>
 * </ul>
 * Nothing here is Skyblock-specific, so it is deliberately NOT behind {@code SkyblockGate} - see
 * {@link TooltipScrollConfig}'s class doc.
 * <p>
 * <b>2026-09-20, killer560: "scrollable tooltips does not work"</b> - re-tested by killer560 in
 * "26.1.2 (Mod Only Test)" (mods: fabric-api, fabric-language-kotlin, this jar, modmenu, packdisabler only -
 * no third-party tooltip mod present), and it still did nothing there, ruling out the tooltip-scroll-mod
 * collision theory from the first pass as the (sole) explanation. Both mixin targets were re-verified with
 * javap against the exact 26.1.2 jar and are correct (identical signatures; confirmed
 * {@code AbstractContainerScreen#getTooltipFromContainerItem} really does delegate to
 * {@code Screen#getTooltipFromItem}; confirmed {@code MouseHandler#onScroll} passes the raw wheel delta
 * straight to {@code Screen#mouseScrolled} whenever a screen is open, so the new {@code ScrollWheelHandler}
 * class in this version only affects hotbar/spectator scrolling with NO screen open and is not a factor
 * here). The feature's own gating/offset math was hand-traced and found sound. No further static bug was
 * found - the leading remaining theory, straight from killer560's own report ("chance it is actually
 * scrolling... instead of just moving"), is that the OLD line-slicing design was technically working but
 * visually indistinguishable from "nothing happening": swapping which lines are drawn inside a box that also
 * changes height every notch reads as noise, not motion. Moving the whole box (this rewrite) is unambiguous
 * either way. {@link #renderHookSeen()} and {@link #scrollHookSeen()} are exposed separately (rather than
 * one merged flag) so the tab can say exactly which half of the pipeline - render or input - has or hasn't
 * fired, in case the mixins themselves are still the problem after this change.
 */
public final class TooltipScrollFeature {

    /** Vertical space the tooltip chrome needs outside the component list: the background's own top/bottom
     *  padding plus the screen-edge margin both tooltip positioners keep (MenuTooltipPositioner MARGIN = 5,
     *  private, so it is repeated here rather than referenced). Read off the 26.1.2 jar with javap. */
    private static final int VERTICAL_CHROME = TooltipRenderUtil.PADDING_TOP + TooltipRenderUtil.PADDING_BOTTOM + 5 + 5;

    /** Number of leading tooltip lines currently scrolled past (translated off the top). */
    private static int offset;
    /** Largest {@link #offset} that still shows genuinely new content at the bottom - recomputed every time
     *  a tooltip is drawn. Capping here (rather than letting the wheel translate arbitrarily far) is what
     *  guarantees the tooltip can never be scrolled entirely off screen with no way back: at {@code
     *  maxOffset} the last line's bottom edge lands exactly at the bottom of the available space, never past
     *  it, so at least the tail of the tooltip is always reachable. */
    private static int maxOffset;
    /** Whether the tooltip drawn last frame was taller than the screen. killer560's "only engage when the
     *  tooltip actually overflows": while this is false the wheel is never consumed, so a short tooltip
     *  behaves exactly as it does today. */
    private static boolean scrollable;
    /** The hovered stack the current {@link #offset} belongs to; weak so a cached stack can never pin an
     *  item (or its whole container) in memory. */
    private static WeakReference<ItemStack> hovered = new WeakReference<>(null);

    /** Set the first time {@link #pixelOffsetFor} is actually called by the render mixin, regardless of
     *  whether the feature is enabled - i.e. "is {@code TooltipScrollGraphicsMixin} applying at all". The
     *  mixin config is {@code required:false} so a signature change after a Minecraft update can never stop
     *  the game booting - but that also means it could silently do nothing, which is exactly how killer560
     *  found this feature completely dead in-game (2026-09-20). Copied from {@code ArmourDye.renderHookSeen}
     *  so the tab can say so out loud instead of leaving him to wonder. Deliberately separate from {@link
     *  #scrollHookSeen} - this fires on ANY tooltip anywhere (buttons, widgets, not just items), so it is by
     *  far the easier of the two to trigger, and a tab that only checked one merged flag could tell him
     *  everything looks alive when only half the pipeline actually is. */
    private static volatile boolean renderHookSeen;
    /** Set the first time {@link #onMouseScrolled} is actually called by the container-screen mixin,
     *  regardless of whether the feature is enabled - i.e. "is {@code TooltipScrollContainerMixin} applying
     *  and is a real scroll event reaching it at all". */
    private static volatile boolean scrollHookSeen;

    public static boolean renderHookSeen() {
        return renderHookSeen;
    }

    public static boolean scrollHookSeen() {
        return scrollHookSeen;
    }

    private TooltipScrollFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Hover tracking only runs inside container screens; without this, closing one would leave a
            // stale offset that the next unrelated (e.g. widget) tooltip would silently inherit. Also covers
            // "reset when the screen closes" - the very next tick after a container screen closes runs this.
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
        scrollHookSeen = true;
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
     * How many pixels {@link com.killer560.hub.tooltipscroll.mixin.TooltipScrollGraphicsMixin} should
     * translate the ENTIRE tooltip render up by (0 = don't touch it - feature off, nothing hovered, or the
     * tooltip already fits, in which case the mixin must not push/pop at all).
     */
    public static int pixelOffsetFor(GuiGraphicsExtractor graphics, Font font, List<ClientTooltipComponent> components) {
        renderHookSeen = true;
        TooltipScrollConfig cfg = TooltipScrollConfig.getInstance();
        if (!cfg.isEnabled() || font == null || components == null || components.size() < 2 || hovered.get() == null) {
            scrollable = false;
            maxOffset = 0;
            return 0;
        }

        int n = components.size();
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = components.get(i).getHeight(font);
        }
        // prefix[i] = the real, rendered vertical extent of components[0, i): each component's own height
        // plus the 2px separator vanilla inserts before every component after the first. Read straight off
        // GuiGraphicsExtractor#tooltip's actual drawing loop with javap -c - deliberately NOT the same
        // arithmetic vanilla uses to size/position its background box (which sums getHeight() alone, with no
        // separator, plus a -2 special case for a lone component). This mod never resizes or repositions the
        // background anymore - it translates the whole already-correctly-rendered picture as one rigid
        // block - so what matters here is how tall the actual drawn TEXT is, which is this formula, not
        // vanilla's internal box-sizing shortcut.
        int[] prefix = new int[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + heights[i] + (i == 0 ? 0 : 2);
        }
        int total = prefix[n];

        int available = graphics.guiHeight() - VERTICAL_CHROME;
        int overflow = total - available;
        if (available < 10 || overflow <= 0) {
            scrollable = false;
            maxOffset = 0;
            offset = 0;
            return 0;
        }
        scrollable = true;

        // Smallest line count whose prefix height already covers the overflow: translating by exactly that
        // many pixels puts the last line's bottom edge at (or just past) the bottom of the available space,
        // never further - see the maxOffset field doc for why that is the "can always get back" guarantee.
        int max = n;
        for (int i = 0; i <= n; i++) {
            if (prefix[i] >= overflow) {
                max = i;
                break;
            }
        }
        maxOffset = max;
        offset = clamp(offset, 0, maxOffset);
        return prefix[offset];
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
