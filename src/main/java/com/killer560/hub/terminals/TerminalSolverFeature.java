package com.killer560.hub.terminals;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.storageoverlay.mixin.SlotClickInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/** Highlights the correct slot(s) to click in Floor 7 dungeon terminals - Solver Only, per killer560's
 *  explicit "Not auto terminals yet just the solver": this never clicks anything, only draws colored
 *  outlines (plus a number/click-count label where relevant) over the slots the player should click
 *  themselves. Modeled on Odin's own Terminal Solver (decompiled for reference, 2026-09-08) - the exact
 *  title regexes, item-matching rules, and the Rubix color-cycle math below are all ported from its real
 *  handler classes, not guessed. Ships disabled by default - see {@link TerminalSolverConfig}. */
public final class TerminalSolverFeature {

    private static final int SLOT_SIZE = 16;

    // Custom GUI mode's own layout constants - a fixed local grid cell size (vanilla's own 16px item
    // plus a 2px gap, same spacing convention as vanilla chest slots) that the shared Scale setting
    // then blows up via a pose transform, same trick StorageOverlayFeature's own grid already uses.
    private static final int CELL_SIZE = 18;
    private static final int GRID_COLUMNS = 9;
    private static final int PANEL_PADDING = 6;

    // A normal Hypixel container GUI always appends the player's own 36 inventory+hotbar slots after
    // the GUI's own content - subtracting this out is a simpler, more robust way to isolate "just the
    // terminal grid" than hardcoding each type's own grid slot count.
    private static final int PLAYER_INVENTORY_SIZE = 36;

    // Per killer560's "focus on the orange side of the mod's gui... more orange in a sense for the
    // tiles" request (2026-09-09) - the mod's own established amber/orange accent (see
    // SettingsButtonWidget's own BORDER_HOVER), leaned into here instead of the old green/cyan/gold
    // rainbow mix, everywhere the puzzle itself doesn't force a specific color choice.
    private static final int THEME_ORANGE = 0xFFFF8C00;
    // "Having them be bright would be better" (2026-09-09, round 4) - a more vivid/saturated orange
    // than THEME_ORANGE specifically for Panes/Numbers' flat boxes, which are otherwise a big flat
    // area of solid color (unlike an outline or a small Select box) where "bright" reads better.
    private static final int BRIGHT_ORANGE = 0xFFFFA500;
    private static final int MUTED_ORANGE = 0xFFB37744;
    // Round 11's first guess (0xFFCC9966, lighter than MUTED_ORANGE) was too close to tier 2 to tell
    // apart - per killer560's round-12 follow-up "it is very hard to tell which one is second and which
    // is 3rd, I should barely be able to see the 3rd one", this is now much darker/dimmer instead, close
    // to the panel's own background color so it barely stands out at all.
    private static final int FAINT_ORANGE = 0xFF4D3319;

    private static final int PANES_COLOR = BRIGHT_ORANGE;
    private static final int STARTS_WITH_COLOR = BRIGHT_ORANGE;
    // Per killer560's "make more of the actual in element gui orange" request (2026-09-09, round 7) -
    // the panel itself (background + border) leans into the same theme now, not just the highlighted
    // cells: a warm dark amber instead of a neutral gray-black, and the same bright orange as every
    // other accent for the border (was a muted brown that barely read as "orange" at a glance). Shared
    // by every type's panel, Melody included - it used to have its own identical-value constant.
    // Alpha bumped to fully opaque (was 0xEE, ~93%) per killer560's round-10 screenshot showing real
    // background content ("Inactive Terminal"/"CLICK HERE" ghost text) bleeding through the panel fill
    // right as a terminal opens - a translucent panel can never fully hide whatever's still being drawn
    // underneath it, however briefly, so opaque is the only way to guarantee nothing shows through.
    private static final int PANEL_BG_COLOR = 0xFF241206;
    private static final int PANEL_BORDER_COLOR = BRIGHT_ORANGE;
    // Melody's own per-role palette. Round 9 (2026-09-09) had the two fixed endpoint pieces as
    // THEME_ORANGE, the moving piece as its own darker shade, buttons as a light orange, and the static
    // track base as black. Round 10 (2026-09-09) revised per killer560's exact follow-up: the endpoints
    // now match the panel border color itself (not just the general theme orange), the moving piece
    // matches the endpoints exactly (was a separate darker shade), and the static track base goes from
    // black to a very light orange instead - see #melodySlotColor for the classification.
    private static final int MELODY_ENDPOINT_COLOR = PANEL_BORDER_COLOR;
    private static final int MELODY_MOVING_PIECE_COLOR = MELODY_ENDPOINT_COLOR;
    // Round 12 (2026-09-09): per killer560's "the bar that shows where I actually need to click... is
    // the same as the rest of the gui, that should be the same color as the moving square" - the real
    // clickable buttons are the functionally important part, so they now match the bright
    // endpoint/moving color exactly instead of their own separate light shade.
    private static final int MELODY_BUTTON_COLOR = MELODY_ENDPOINT_COLOR;
    // Round 10's 0xFFFFF2E0 read as basically white, round 11's 0xFFFFCC80 still wasn't light enough per
    // killer560's round-12 "you can lighten up the main 4x5" follow-up. Round 15 dimmed it back down to
    // 0xFFF5D2A0 ("so close to perfect... just make those white spaces a little bit dimmer"); round 16's
    // "even dimmer" ("other than that it is perfect") dims it further again.
    private static final int MELODY_TRACK_BASE_COLOR = 0xFFDCB37D;
    // Rubix keeps a real functional 2-color split (left-click vs right-click), per killer560's explicit
    // request - orange for the common forward/left-click case, a clearly distinct blue for the reverse/
    // right-click case, rather than 4 shades that don't actually mean anything extra at a glance.
    private static final int RUBIX_LEFT_CLICK_COLOR = THEME_ORANGE;
    private static final int RUBIX_RIGHT_CLICK_COLOR = 0xFF3399FF;

    // Real Hypixel Rubix mechanic (confirmed via Odin's own RubixHandler): each click on a pane
    // advances it ONE step through this 5-color cycle - no adjacency/neighbor coupling despite the name.
    private static final List<DyeColor> RUBIX_COLOR_ORDER =
            List.of(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED);

    private static final Map<DyeColor, List<String>> SELECT_PREFIXES = Map.ofEntries(
            Map.entry(DyeColor.BLACK, List.of("black", "ink")),
            Map.entry(DyeColor.BLUE, List.of("blue", "lapis")),
            Map.entry(DyeColor.BROWN, List.of("brown", "cocoa")),
            Map.entry(DyeColor.WHITE, List.of("white", "bone", "wool")),
            Map.entry(DyeColor.GREEN, List.of("green", "cactus")),
            Map.entry(DyeColor.RED, List.of("red", "rose")),
            Map.entry(DyeColor.YELLOW, List.of("yellow", "dandelion")),
            Map.entry(DyeColor.LIGHT_GRAY, List.of("silver", "light gray"))
    );

    private static TerminalType currentType;
    private static Map<Integer, SlotHighlight> currentHighlights = Map.of();
    private static int currentTerminalSlotCount;
    private static boolean wasHoldingCarriedItem;
    // Per killer560's "quick flash thing" report (2026-09-09, round 8) - a terminal's real items can
    // momentarily be a transient "not yet populated" state on the very first frame or two after opening
    // (e.g. every slot briefly filler/air before Hypixel's real puzzle data arrives), which used to make
    // #computeGridBounds fall back to a full 9-wide guess for that one frame before shrinking down to
    // the real cropped size - a visible flash of the panel briefly being much bigger than it should be.
    // Caching the last real bounds found and reusing them for that one blank frame avoids the flash
    // entirely; cleared whenever a DIFFERENT terminal is detected so it never leaks between terminals.
    private static GridBounds lastGoodBounds;
    // Per killer560's round-12 report that terminals "still flash and quickly resize... making it look
    // still like it opens multiple menus" even after round 11's fixes: rounds 8/9/11 each patched one
    // specific symptom (panel size, then highlight correctness) of the same underlying cause - Hypixel's
    // real container data can keep arriving/settling over several frames after a terminal opens, not just
    // one. Rounds 12-14 tried a blind wall-clock grace period (draw nothing for N ms after a new terminal
    // is detected) - but that's a real tradeoff with no right answer: killer560 kept finding it either
    // still flashed occasionally ("opening a ton of solvers") or felt sluggish, because population time
    // genuinely varies and no fixed constant covers every case without also being slower than necessary
    // the rest of the time. Round 15 replaces the timer entirely with a content-stability check instead:
    // don't draw until this terminal's real item data has been IDENTICAL for two consecutive frames (see
    // #hasStabilizedOnce), then never re-check for the rest of that terminal's lifetime (so a later
    // legitimate change, like a correct click updating a pane, never re-triggers this gate) - this
    // self-adapts to however long the real population actually takes, as fast as truly possible, with no
    // constant to keep re-tuning.
    private static boolean hasStabilizedOnce;
    private static List<ItemStack> previousItemsSnapshot = List.of();

    // Public - per killer560's round-13 "my solver overlay still isnt happening on [Termism]" request,
    // TermismPracticeScreen (a different package) reuses this exact record via the public #solve entry
    // point below, instead of re-deriving its own approximation of the real highlight logic.
    public record SlotHighlight(int color, String label) {
    }

    private TerminalSolverFeature() {
    }

    /** Real bug found and fixed (2026-09-09), per killer560's report of a real, if brief, flash of the
     *  raw unmodified terminal on open before Custom GUI kicks in: state used to only refresh once per
     *  client TICK (~50ms), but rendering happens far more often than that (every frame, up to several
     *  times before the next tick even fires) - the first few frames after a terminal screen opens
     *  could render with stale (null) state, showing the real vanilla screen for a moment before the
     *  next tick corrected it. Called from {@link com.killer560.hub.terminals.mixin.TerminalSolverBackgroundMixin}
     *  at the very HEAD of {@code extractBackground} - the true first thing a screen's whole render pass
     *  does (confirmed via javap: {@code Screen.extractRenderStateWithTooltipAndSubtitles} calls it
     *  before {@code extractRenderState} even begins) - so every hook downstream of it, every single
     *  frame including the very first, already sees correct, freshly-computed state. */
    public static void refreshState() {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        if (!cfg.isEnabled() || !(Minecraft.getInstance().screen instanceof ContainerScreen screen)) {
            currentType = null;
            currentHighlights = Map.of();
            wasHoldingCarriedItem = false;
            lastGoodBounds = null;
            return;
        }
        String title = screen.getTitle().getString();
        TerminalType type = matchType(title, cfg);
        if (type == null) {
            currentType = null;
            currentHighlights = Map.of();
            wasHoldingCarriedItem = false;
            lastGoodBounds = null;
            return;
        }
        if (type != currentType) {
            lastGoodBounds = null;
            // A genuinely different terminal (or the very first one) just opened - whatever was
            // highlighted before belongs to a different board entirely, so it must not carry over even
            // for one frame while this new one's own real data is still arriving.
            currentHighlights = Map.of();
            rubixTargetColorIndex = -1;
            hasStabilizedOnce = false;
            previousItemsSnapshot = List.of();
        }
        currentType = type;
        List<ItemStack> items = terminalItems(screen.getMenu());
        currentTerminalSlotCount = items.size();
        if (!hasStabilizedOnce) {
            boolean realContent = hasRealContent(items);
            if (realContent && ItemStack.listMatches(items, previousItemsSnapshot)) {
                hasStabilizedOnce = true;
            }
            previousItemsSnapshot = realContent ? List.copyOf(items) : List.of();
        }
        // Melody has no solving logic - nothing to mark correct/incorrect - so currentHighlights always
        // stays empty for it. Its Custom GUI panel (see #renderMelodyCustomGui) instead just redraws
        // every real terminal-grid item as-is, decluttered from the rest of the screen.
        if (type == TerminalType.MELODY) {
            currentHighlights = Map.of();
        } else if (hasRealContent(items)) {
            // Per killer560's "flashes incorrect answers for about a frame... like it is opening two
            // menus and one gets closed" report (2026-09-09, round 11) on Numbers/Starts With or Select -
            // same underlying cause as the already-fixed panel-size flash (round 8/9): a terminal's real
            // items can be transiently incomplete for a frame or two right after opening. Round 8/9 only
            // guarded the PANEL SIZE against that (see #lastGoodBounds) - this frame's item list could
            // still feed #solve a partial/stale board and briefly highlight the wrong slot(s). Only
            // trusting a fresh solve() result once real, non-filler content is actually present - and
            // simply keeping whatever was already showing otherwise - closes that same gap for the
            // highlights themselves, not just the panel's dimensions.
            currentHighlights = solve(type, title, items);
        }
        maybeClearAccidentalCarriedItem(screen);
    }

    /** @return whether {@code items} contains at least one real, non-filler slot (same "not empty, not a
     *  black filler pane" signal {@link #computeGridBounds} already uses) - used by {@link #refreshState}
     *  to tell a genuinely populated board apart from a transient population gap right after a terminal
     *  opens. */
    private static boolean hasRealContent(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && stack.getItem() != Items.BLACK_STAINED_GLASS_PANE) {
                return true;
            }
        }
        return false;
    }


    /** Per killer560's explicit "make it so it doesnt pick up panes at all anymore" request (2026-09-09,
     *  round 8) - Custom GUI's redirected clicks already avoid {@code ContainerInput.PICKUP} for every
     *  type except Rubix (which genuinely needs a real left-vs-right click so Hypixel knows which
     *  direction to cycle the pane - see {@link #handleCustomGuiClick}), but that one real click can
     *  still end up with the pane briefly in the cursor. Same real, already-diagnosed bug class
     *  ExperimentsFeature's own Click Protection hit (2026-09-08, see its own doc): Hypixel's server can
     *  briefly grant/echo the item into the cursor as click feedback no matter how the click was sent -
     *  nothing about preventing it client-side actually works. Reacts instead: the instant a carried
     *  item is detected while Custom GUI is showing, clears it the same real way clicking outside any
     *  inventory slot does ({@code ContainerInput.PICKUP} at slot -999, well-established vanilla
     *  behavior) - called every frame from {@link #refreshState()}, fast enough it can't be seen. */
    private static void maybeClearAccidentalCarriedItem(ContainerScreen screen) {
        if (!isCustomGuiActive()) {
            wasHoldingCarriedItem = false;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ItemStack carried = screen.getMenu().getCarried();
        boolean holdingNow = carried != null && !carried.isEmpty();
        if (holdingNow && !wasHoldingCarriedItem && client.player != null && client.gameMode != null) {
            client.gameMode.handleContainerInput(screen.getMenu().containerId, -999, 0, ContainerInput.PICKUP, client.player);
        }
        wasHoldingCarriedItem = holdingNow;
    }

    /** @return whether the given real slot index should be hidden right now - true for every type,
     *  Melody included, while Custom GUI mode is showing (each type either draws a full replacement
     *  panel of its own, or - for Melody - a plain redraw of the real terminal items, per killer560's
     *  "follow a similar track to the other one... hide the normal screen and redraw it entirely"
     *  request, 2026-09-09 round 6, which folded Melody into the same unified treatment). */
    public static boolean shouldHideSlot(int slotIndex) {
        return isCustomGuiActive();
    }

    /** @return whether the vanilla background texture and title/"Inventory" labels should be hidden -
     *  same as {@link #shouldHideSlot}, unconditionally true for every type while Custom GUI is on. */
    public static boolean shouldHideBackgroundAndLabels() {
        return isCustomGuiActive();
    }

    /** @return whether Custom GUI mode should currently be showing (feature + that toggle both on,
     *  and a covered terminal is actually open right now). */
    public static boolean isCustomGuiActive() {
        return currentType != null && TerminalSolverConfig.getInstance().isCustomGuiEnabled();
    }

    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (currentType == null) {
            return;
        }
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!isCustomGuiActive()) {
            // Melody has nothing to show outside Custom GUI mode - no solving logic means no
            // vanilla-overlay outlines to draw either.
            if (currentType != TerminalType.MELODY && !currentHighlights.isEmpty()) {
                renderVanillaHighlights(graphics, screen);
            }
            return;
        }
        if (withinOpenGracePeriod()) {
            return;
        }
        if (currentType == TerminalType.MELODY) {
            renderMelodyCustomGui(graphics, screen);
        } else {
            // Per killer560's "rubix is still flashing on the last item" report (2026-09-09, round 15) -
            // this used to skip drawing entirely once currentHighlights went empty (the last correct
            // click leaves nothing left to highlight), so the whole panel would abruptly vanish back to
            // the hidden/blank real background for whatever's left of the moment before Hypixel closes
            // the solved terminal - reading as a flash. Now always draws the panel itself (background +
            // border), just with zero highlighted cells when there's nothing left to click, so something
            // stays on screen continuously instead of a sudden gap.
            renderCustomGui(graphics, screen);
        }
    }

    /** @return whether this terminal's data hasn't stabilized yet (see {@link #hasStabilizedOnce}'s own
     *  doc) - Custom GUI deliberately shows nothing at all until it has. */
    private static boolean withinOpenGracePeriod() {
        return !hasStabilizedOnce;
    }

    /** Melody has no solving logic - nothing here is marked correct/incorrect - so this just redraws
     *  every real terminal-grid item, bare (no fill/outline decoration), in the same bigger decluttered
     *  panel style every other type's Custom GUI mode already uses. Per killer560's "follow a similar
     *  track to the other one... hide the normal screen and just redraw it entirely" request
     *  (2026-09-09, round 6) - folds Melody into the same unified hide-and-redraw treatment instead of
     *  the narrower "just hide inventory" special case round 5 had. */
    private static void renderMelodyCustomGui(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        if (layout == null) {
            return;
        }

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(layout.originX, layout.originY);
        graphics.pose().scale(layout.scale, layout.scale);

        graphics.fill(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING, layout.panelHeight + PANEL_PADDING, PANEL_BG_COLOR);
        graphics.outline(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING * 2, layout.panelHeight + PANEL_PADDING * 2, PANEL_BORDER_COLOR);

        List<Slot> slots = screen.getMenu().slots;
        DyeColor movingColor = findMelodyMovingColor(slots);
        for (int slotIndex = 0; slotIndex < currentTerminalSlotCount && slotIndex < slots.size(); slotIndex++) {
            ItemStack stack = slots.get(slotIndex).getItem();
            if (!isMelodyButtonSlot(stack)) {
                continue;
            }
            int col = slotIndex % GRID_COLUMNS - layout.minCol();
            int row = slotIndex / GRID_COLUMNS - layout.minRow();
            if (col < 0 || row < 0) {
                continue;
            }
            int x0 = col * CELL_SIZE;
            int y0 = row * CELL_SIZE;
            graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, melodySlotColor(stack, movingColor));
        }
        graphics.pose().popMatrix();
    }

    /** @return whether the given real item is one of Melody's actual clickable buttons, as opposed to a
     *  black filler/background pane - same "not a black filler pane" rule every other type already uses
     *  to skip decorative background slots (see {@link #solveSelect}), reused here so Melody's rendered
     *  cells and clickable cells always agree on what counts as a real button. */
    private static boolean isMelodyButtonSlot(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() != Items.BLACK_STAINED_GLASS_PANE;
    }

    /** @return the single {@link DyeColor} that appears EXACTLY ONCE among Melody's own track panes
     *  (every real slot that's a stained glass pane and isn't purple), or null if there's no such unique
     *  color right now. Round 9's original model (2026-09-09) assumed one repeated "majority" base color
     *  plus a single differently-colored "moving" marker, and colored everything that didn't match the
     *  majority as the mover - but killer560's round-10 live screenshot showed nearly the ENTIRE track
     *  rendering as the mover's bright color, meaning a real board can have enough color variety that no
     *  true majority exists, and most panes end up "not equal to majority" by default. Round 10.1 flips
     *  the default to the safer direction instead: every track pane is base (light) UNLESS it's the one
     *  pane whose color is a genuine singleton (appears nowhere else on the board) - a real moving marker
     *  should almost always be uniquely colored that frame, so this is a much narrower, safer trigger for
     *  the "moving" highlight than "isn't the majority." If more than one color happens to be a singleton
     *  (ambiguous - no way to tell which one is the real mover), returns null and everything just renders
     *  as base instead of guessing wrong. */
    private static DyeColor findMelodyMovingColor(List<Slot> slots) {
        EnumMap<DyeColor, Integer> counts = new EnumMap<>(DyeColor.class);
        for (int slotIndex = 0; slotIndex < currentTerminalSlotCount && slotIndex < slots.size(); slotIndex++) {
            DyeColor pane = paneDyeColor(slots.get(slotIndex).getItem());
            if (pane == null || isMelodyEndpointColor(pane)) {
                continue;
            }
            counts.merge(pane, 1, Integer::sum);
        }
        DyeColor singleton = null;
        int singletonCount = 0;
        for (Map.Entry<DyeColor, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == 1) {
                singleton = entry.getKey();
                singletonCount++;
            }
        }
        return singletonCount == 1 ? singleton : null;
    }

    /** Per killer560's exact per-role coloring request, from his own read of a real screenshot - not
     *  confirmed against a decompiled handler, just his own direct observation of the real board. Round 9
     *  (2026-09-09) first split the board into 4 shades; round 10 tied the endpoint and moving-piece
     *  colors together and lightened the track base; round 10.1 fixed the "whole board renders as the
     *  mover" bug that round 10 exposed (see {@link #findMelodyMovingColor}'s own doc for the root cause);
     *  round 12 tied the buttons to the endpoint/mover color too (they're the actually-important part) and
     *  lightened the track base further:
     *  <ul>
     *  <li>The two purple pieces (fixed track endpoints) -&gt; same color as the panel border.
     *  <li>The "moving piece" (see {@link #findMelodyMovingColor}) -&gt; same color as the endpoints.
     *  <li>The real buttons you click (not a stained glass pane at all - a full block item, distinct from
     *      the flat track panes in the original screenshot) -&gt; same color as the endpoints/mover too.
     *  <li>Everything else (the static track base) -&gt; a light orange.
     *  </ul> */
    private static int melodySlotColor(ItemStack stack, DyeColor movingColor) {
        DyeColor pane = paneDyeColor(stack);
        if (pane == null) {
            return MELODY_BUTTON_COLOR;
        }
        if (isMelodyEndpointColor(pane)) {
            return MELODY_ENDPOINT_COLOR;
        }
        if (movingColor != null && pane == movingColor) {
            return MELODY_MOVING_PIECE_COLOR;
        }
        return MELODY_TRACK_BASE_COLOR;
    }

    // Real bug found and fixed (2026-09-09, round 14), per killer560's screenshot comparison against a
    // real vanilla Melody board: the longest column's two tip/endpoint panes weren't getting colored at
    // all, staying the plain track-base shade. Round 9's "the two purple pieces" read was checking only
    // DyeColor.PURPLE - Hypixel's actual endpoint pane is close enough to purple to read as the same
    // color in a screenshot but is really MAGENTA, a distinct DyeColor. Treating both as the endpoint
    // color covers whichever one a given board actually uses without needing to guess further.
    private static boolean isMelodyEndpointColor(DyeColor pane) {
        return pane == DyeColor.PURPLE || pane == DyeColor.MAGENTA;
    }

    private static void renderVanillaHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int left = accessor.killer560smod$getLeftPos();
        int top = accessor.killer560smod$getTopPos();
        float scale = TerminalSolverConfig.getInstance().getScale();
        int inflate = Math.max(1, Math.round(scale));

        graphics.nextStratum();
        for (Slot slot : screen.getMenu().slots) {
            SlotHighlight highlight = currentHighlights.get(slot.index);
            if (highlight == null) {
                continue;
            }
            int x0 = left + slot.x;
            int y0 = top + slot.y;
            graphics.outline(x0 - inflate, y0 - inflate, SLOT_SIZE + inflate * 2, SLOT_SIZE + inflate * 2, highlight.color());
            if (highlight.label() != null) {
                float textScale = 0.5f * scale;
                graphics.pose().pushMatrix();
                graphics.pose().translate(x0, y0 + SLOT_SIZE - 7);
                graphics.pose().scale(textScale, textScale);
                graphics.text(Minecraft.getInstance().font, highlight.label(), 1, 1, 0xFFFFFFFF, true);
                graphics.pose().popMatrix();
            }
        }
    }

    /** Custom GUI mode: a big, standalone panel showing ONLY the slots actually in the solution -
     *  "the buttons i have to press are the only things I see in the gui", per killer560's explicit
     *  request (2026-09-09) - laid out at each item's real row/column within the terminal's own 9-wide
     *  grid (every Hypixel chest-style container is 9 columns wide) so the spatial layout still makes
     *  sense, just with every irrelevant slot skipped entirely and everything scaled up via the same
     *  Scale setting the vanilla-overlay mode already uses. The real slots are hidden elsewhere (see
     *  {@code TerminalSolverSlotMixin}) - clicking a cell here redirects to the real underlying slot by
     *  index via {@link SlotClickInvoker}, the same trick Storage Overlay's own custom grid uses. */
    private static void renderCustomGui(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        if (layout == null) {
            return;
        }

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(layout.originX, layout.originY);
        graphics.pose().scale(layout.scale, layout.scale);

        graphics.fill(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING, layout.panelHeight + PANEL_PADDING, PANEL_BG_COLOR);
        graphics.outline(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING * 2, layout.panelHeight + PANEL_PADDING * 2, PANEL_BORDER_COLOR);

        List<Slot> slots = screen.getMenu().slots;
        for (Map.Entry<Integer, SlotHighlight> entry : currentHighlights.entrySet()) {
            int slotIndex = entry.getKey();
            if (slotIndex >= slots.size()) {
                continue;
            }
            SlotHighlight highlight = entry.getValue();
            int x0 = (slotIndex % GRID_COLUMNS - layout.minCol()) * CELL_SIZE;
            int y0 = (slotIndex / GRID_COLUMNS - layout.minRow()) * CELL_SIZE;

            // Per killer560's per-type style requests (2026-09-09) - every type is now a flat colored
            // box (no real item icon, no outline) except Rubix, which also centers its click-count text.
            graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, highlight.color());
            if (currentType == TerminalType.RUBIX && highlight.label() != null) {
                // Per killer560's "please remove that text shadow" report (2026-09-09, round 14) -
                // #centeredText has no shadow-off overload, so this centers the text by hand instead
                // using the plain #text overload's own explicit shadow=false parameter. Round 15's "the
                // numbers are now off center a little high and left" follow-up - this formula is actually
                // identical to #centeredText's own internal one (confirmed via decompile), so the shadow
                // itself was likely visually masking the same small offset before; nudged down/right a
                // touch since removing the shadow made it more noticeable.
                Font font = Minecraft.getInstance().font;
                int textY = y0 + (SLOT_SIZE - font.lineHeight) / 2 + 1;
                int textX = x0 + SLOT_SIZE / 2 - Math.round(font.width(highlight.label()) / 2f) + 1;
                graphics.text(font, highlight.label(), textX, textY, 0xFF000000, false);
            }
        }
        graphics.pose().popMatrix();
    }

    /** @return whether the click was consumed - Custom GUI mode swallows every click while it's
     *  showing (redirecting the ones that land on a real cell, discarding the rest), since the real
     *  slots underneath are hidden and shouldn't be reachable by an unrelated click landing on empty
     *  panel background. */
    public static boolean handleCustomGuiClick(AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
        if (!isCustomGuiActive()) {
            return false;
        }
        if (withinOpenGracePeriod()) {
            // Nothing is even drawn yet (see #renderOverlay) - still swallow the click rather than let
            // it fall through to the hidden real slots underneath.
            return true;
        }
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        if (layout == null) {
            return true;
        }
        double localX = (mouseX - layout.originX) / layout.scale;
        double localY = (mouseY - layout.originY) / layout.scale;
        int localCol = (int) Math.floor(localX / CELL_SIZE);
        int localRow = (int) Math.floor(localY / CELL_SIZE);
        int panelColumns = layout.panelWidth / CELL_SIZE;
        int panelRows = layout.panelHeight / CELL_SIZE;
        // Clicks landing outside the (now cropped-to-content) panel grid have nothing to redirect to -
        // still consumed below, same as a click on empty panel padding always was.
        if (localCol < 0 || localCol >= panelColumns || localRow < 0 || localRow >= panelRows) {
            return true;
        }
        int slotIndex = (localRow + layout.minRow()) * GRID_COLUMNS + (localCol + layout.minCol());

        List<Slot> slots = screen.getMenu().slots;
        // Melody has no solved/correct set to check against - any real button cell is fair game (matches
        // #isMelodyButtonSlot, the same "not black filler" rule its own rendering already uses so
        // clickable and rendered cells never disagree).
        boolean validCell = currentType == TerminalType.MELODY
                ? slotIndex < currentTerminalSlotCount && slotIndex < slots.size() && isMelodyButtonSlot(slots.get(slotIndex).getItem())
                : currentHighlights.containsKey(slotIndex);
        if (validCell && slotIndex >= 0 && slotIndex < slots.size()) {
            Slot slot = slots.get(slotIndex);
            // Per killer560's "make it so it doesnt pick up panes at all anymore" request (2026-09-09,
            // round 8): every type except Rubix only needs a single undirected "click this slot" signal
            // to register with Hypixel, so CLONE (pick-block) - a genuine no-op for real item movement
            // in survival, same trick ExperimentsFeature's own click redirect already relies on - never
            // actually picks anything up. Rubix is the one real exception: it needs an actual left-vs-
            // right click so Hypixel knows which direction to cycle the pane, so it still sends a real
            // PICKUP with the real button.
            boolean needsRealClick = currentType == TerminalType.RUBIX;
            if (needsRealClick) {
                // Per killer560's "add prevent misclicks" request (2026-09-09, round 16) - a highlighted
                // Rubix cell already tells you which direction to click via its color/label (see
                // #renderCustomGui); clicking it with the OPPOSITE button would otherwise still send a
                // real click that direction, actively undoing progress instead of doing nothing. Swallow
                // a wrong-direction click on an otherwise-valid cell rather than redirecting it - matches
                // this mod's existing click-protection precedent (ExperimentsFeature) of never letting an
                // incorrect action reach the real server.
                SlotHighlight highlight = currentHighlights.get(slotIndex);
                boolean needsRightClick = highlight != null && highlight.label() != null && highlight.label().startsWith("-");
                int expectedButton = needsRightClick ? 1 : 0;
                if (button != expectedButton) {
                    return true;
                }
            }
            ContainerInput clickType = needsRealClick ? ContainerInput.PICKUP : ContainerInput.CLONE;
            int effectiveButton = needsRealClick ? button : 0;
            ((SlotClickInvoker) (Object) screen).killer560smod$slotClicked(slot, slot.index, effectiveButton, clickType);
            if (needsRealClick) {
                // Per killer560's "whenever I click in rubix it makes my held item move... please dont
                // make that happen, it is the only term it does that for" report (2026-09-09, round 11):
                // a real PICKUP click predicts the pickup LOCALLY and synchronously as part of the call
                // above (menu.clicked() moves the slot's item into the carried stack itself, before any
                // server round-trip) - #maybeClearAccidentalCarriedItem only caught this reactively on
                // the NEXT frame's refreshState(), a frame too late to stop the visible flash. Clearing
                // it immediately, same call, means the very next render already sees an empty cursor.
                screen.getMenu().setCarried(ItemStack.EMPTY);
            }
        }
        return true;
    }

    private record CustomGuiLayout(int originX, int originY, float scale, int panelWidth, int panelHeight, int minCol, int minRow) {
    }

    /** Per killer560's "if boxes can only spawn in lets say a 2x7 space, then only show the 2x7 space...
     *  i dont need to see the outer border that will never have anything rendered" request (2026-09-09,
     *  round 7) - the panel used to always span the full 9-wide grid regardless of how much of it a
     *  given terminal type actually uses, wasting most of the panel on dead space for types whose real
     *  pattern only occupies a small sub-rectangle. Now crops to the bounding box of every real,
     *  non-filler item slot (same "not a black filler pane" rule {@link #solveSelect} already uses to
     *  skip Select's own background panes) - applies to every type uniformly, Melody included. */
    private static CustomGuiLayout computeCustomGuiLayout(AbstractContainerScreen<?> screen) {
        GridBounds bounds = computeGridBounds(screen);
        if (bounds == null) {
            return null;
        }
        float scale = TerminalSolverConfig.getInstance().getScale();
        int panelWidth = bounds.columns() * CELL_SIZE;
        int panelHeight = bounds.rows() * CELL_SIZE;
        int scaledWidth = Math.round(panelWidth * scale);
        int scaledHeight = Math.round(panelHeight * scale);
        int originX = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - scaledWidth) / 2;
        int originY = (Minecraft.getInstance().getWindow().getGuiScaledHeight() - scaledHeight) / 2;
        return new CustomGuiLayout(originX, originY, scale, panelWidth, panelHeight, bounds.minCol(), bounds.minRow());
    }

    private record GridBounds(int minCol, int minRow, int columns, int rows) {
    }

    private static GridBounds computeGridBounds(AbstractContainerScreen<?> screen) {
        List<ItemStack> items = terminalItems(screen.getMenu());
        int minCol = GRID_COLUMNS;
        int maxCol = -1;
        int minRow = Integer.MAX_VALUE;
        int maxRow = -1;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || stack.getItem() == Items.BLACK_STAINED_GLASS_PANE) {
                continue;
            }
            int col = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        if (maxCol < 0) {
            // No real puzzle items found this frame - either a genuinely empty/transient state (e.g. the
            // very first frame or two after opening, before Hypixel's real puzzle data has arrived) or
            // this terminal just doesn't have any right now. Reuse the last real size found instead of
            // guessing (see #lastGoodBounds's own doc for why) - and if there's no previous size to fall
            // back on either (the very first terminal opened this session), return null so the caller
            // just skips drawing for that one frame instead of showing a wrong-size panel that then
            // visibly resizes once real data arrives - killer560's "still a quick load flash... i think
            // is it from before it resizes" report (2026-09-09, round 9) confirmed the old full-grid
            // guess fallback was exactly that residual case.
            return lastGoodBounds;
        }
        GridBounds bounds = new GridBounds(minCol, minRow, maxCol - minCol + 1, maxRow - minRow + 1);
        lastGoodBounds = bounds;
        return bounds;
    }

    private static TerminalType matchType(String title, TerminalSolverConfig cfg) {
        for (TerminalType type : TerminalType.values()) {
            if (!isTypeEnabled(type, cfg)) {
                continue;
            }
            if (type.titlePattern().matcher(title).matches()) {
                return type;
            }
        }
        return null;
    }

    private static boolean isTypeEnabled(TerminalType type, TerminalSolverConfig cfg) {
        return switch (type) {
            case PANES -> cfg.isPanesEnabled();
            case RUBIX -> cfg.isRubixEnabled();
            case NUMBERS -> cfg.isNumbersEnabled();
            case STARTS_WITH -> cfg.isStartsWithEnabled();
            case SELECT -> cfg.isSelectEnabled();
            // Has no solving logic of its own - toggling this just turns detection on/off (Custom GUI's
            // hide-inventory treatment, see #shouldHideSlot) - per killer560's "there is no melody toggle
            // in the terminals to solve box" report (2026-09-09, round 11); used to be hardcoded true.
            case MELODY -> cfg.isMelodyEnabled();
        };
    }

    private static List<ItemStack> terminalItems(AbstractContainerMenu menu) {
        List<ItemStack> all = menu.getItems();
        int terminalSlotCount = Math.max(0, all.size() - PLAYER_INVENTORY_SIZE);
        return terminalSlotCount == 0 ? all : all.subList(0, terminalSlotCount);
    }

    /** Public - lets {@code TermismPracticeScreen} feed its own generated practice board through the
     *  exact same solving logic the real terminal uses (per killer560's round-13 "my solver overlay
     *  still isnt happening on it which I want" request), rather than reimplementing an approximation of
     *  it. {@code title} only matters for Starts With/Select (they parse the target letter/color out of
     *  it via each type's own {@link TerminalType#titlePattern()}) - Termism builds a synthetic title
     *  matching that same pattern since it has no real Hypixel container title to read from. */
    public static Map<Integer, SlotHighlight> solve(TerminalType type, String title, List<ItemStack> items) {
        return switch (type) {
            case PANES -> solvePanes(items);
            case RUBIX -> solveRubix(items);
            case NUMBERS -> solveNumbers(items);
            case STARTS_WITH -> solveStartsWith(type, title, items);
            case SELECT -> solveSelect(type, title, items);
            // refreshState() never calls solve() for Melody - see its own doc.
            case MELODY -> Map.of();
        };
    }

    /** "Correct all the panes!" - click every red pane once (any order, no sequencing). */
    private static Map<Integer, SlotHighlight> solvePanes(List<ItemStack> items) {
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() == Items.RED_STAINED_GLASS_PANE) {
                result.put(i, new SlotHighlight(PANES_COLOR, null));
            }
        }
        return result;
    }

    /** "Click in order!" - same red-pane detection as Panes, but the real order is encoded in each
     *  pane's stack COUNT (not its name/lore) - confirmed via Odin's NumbersHandler. No label of our
     *  own on these - per killer560's report (2026-09-09), vanilla already renders that same count as
     *  a real number in the corner of the item, so our own text just doubled up on top of it.
     *  <p>
     *  Per killer560's follow-up request (2026-09-09, round 4): only ever surfaces the immediate next
     *  click (bright orange) and the one after it (muted orange) - everything else stays completely
     *  unhighlighted, the same "next + following" two-tier reveal Chronomatron/Ultrasequencer's own
     *  Solver Only mode already uses, rather than lighting up every remaining pane's own order at once.
     *  Self-correcting every frame with no extra state: once the real current-lowest pane is clicked,
     *  Hypixel's own server stops it being a red pane, so the freshly re-sorted list naturally advances
     *  on its own.
     *  <p>
     *  Round 11 (2026-09-09): optional 3rd tier, per killer560's "show the one I need to click, the one
     *  after that, then one after that as well" request - a fainter orange again, off by default via
     *  {@link TerminalSolverConfig#isNumbersThreeTierReveal()} since the 2-tier reveal is the one
     *  already confirmed working. */
    private static Map<Integer, SlotHighlight> solveNumbers(List<ItemStack> items) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() == Items.RED_STAINED_GLASS_PANE) {
                slots.add(i);
            }
        }
        slots.sort(Comparator.comparingInt(i -> items.get(i).getCount()));
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        if (!slots.isEmpty()) {
            result.put(slots.get(0), new SlotHighlight(BRIGHT_ORANGE, null));
        }
        if (slots.size() > 1) {
            result.put(slots.get(1), new SlotHighlight(MUTED_ORANGE, null));
        }
        if (slots.size() > 2 && TerminalSolverConfig.getInstance().isNumbersThreeTierReveal()) {
            result.put(slots.get(2), new SlotHighlight(FAINT_ORANGE, null));
        }
        return result;
    }

    /** "What starts with: '<letter>'?" - select every item whose (color-code-stripped) name starts
     *  with the given letter, ignoring case. Real items already selected/correct glint (are enchanted)
     *  in Hypixel's own UI - excluded here the same way Odin's StartsWithHandler does, with a Golden
     *  Apple allowlist for the one common item that glints but is still a valid answer. */
    private static Map<Integer, SlotHighlight> solveStartsWith(TerminalType type, String title, List<ItemStack> items) {
        Matcher matcher = type.titlePattern().matcher(title);
        if (!matcher.matches()) {
            return Map.of();
        }
        String letter = matcher.group(1);
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty()) {
                continue;
            }
            if (item.hasFoil() && item.getItem() != Items.GOLDEN_APPLE) {
                continue;
            }
            String name = stripColor(item.getHoverName().getString());
            if (name.isEmpty()) {
                continue;
            }
            if (name.regionMatches(true, 0, letter, 0, letter.length())) {
                result.put(i, new SlotHighlight(STARTS_WITH_COLOR, null));
            }
        }
        return result;
    }

    /** "Select all the '<color>' items!" - select every item whose name starts with one of that
     *  color's known aliases (Hypixel's real item names don't all literally start with the dye color's
     *  own name - e.g. "Rose" for red, "Cactus" for green - see {@link #SELECT_PREFIXES}), excluding
     *  glinted items and the black filler panes. Highlight color is always the mod's own bright orange
     *  now - a round-4 version briefly used the real target dye's own color instead, but killer560
     *  explicitly asked (round 5, 2026-09-09) for orange regardless of the real target color. */
    private static Map<Integer, SlotHighlight> solveSelect(TerminalType type, String title, List<ItemStack> items) {
        Matcher matcher = type.titlePattern().matcher(title);
        if (!matcher.matches()) {
            return Map.of();
        }
        String colorText = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        DyeColor color = parseSelectColor(colorText);
        List<String> prefixes = color != null ? SELECT_PREFIXES.getOrDefault(color, List.of(colorText)) : List.of(colorText);

        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty() || item.hasFoil() || item.getItem() == Items.BLACK_STAINED_GLASS_PANE) {
                continue;
            }
            String name = stripColor(item.getHoverName().getString()).toLowerCase(Locale.ROOT);
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    result.put(i, new SlotHighlight(BRIGHT_ORANGE, null));
                    break;
                }
            }
        }
        return result;
    }

    // Hypixel's terminal title says "SILVER" (Minecraft's old wool-color name) for what DyeColor calls
    // LIGHT_GRAY - same special case Odin's own TerminalTypes.openHandler handles.
    private static DyeColor parseSelectColor(String lowerCaseText) {
        if (lowerCaseText.equals("silver")) {
            return DyeColor.LIGHT_GRAY;
        }
        try {
            return DyeColor.valueOf(lowerCaseText.replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Per killer560's "if the rubix gives a pattern and I misclick, dont have it adjust the solver in
    // the sense of trying to find a new pattern, this causes a flash... just have it stay" request
    // (2026-09-09, round 14) - once a target color has been picked for the CURRENT puzzle, every later
    // frame keeps solving toward that exact same target rather than re-running the cheapest-target
    // search from scratch (which a misclick, or just Hypixel's own state syncing mid-solve, could
    // legitimately cause to land on a DIFFERENT target - flashing every remaining pane's color/count to
    // match it). -1 means "no target committed yet" - reset whenever a genuinely new terminal opens (see
    // #refreshState) or a new Termism puzzle generates (see TermismPracticeScreen#resetRubixTarget).
    private static int rubixTargetColorIndex = -1;

    /** "Change all to same color!" - each pane cycles forward one step through
     *  {@link #RUBIX_COLOR_ORDER} per click (no neighbor coupling). The FIRST time this runs for a given
     *  puzzle, tries every possible target color and commits to whichever needs the fewest total clicks
     *  (counting a "3 or 4 forward" pane as cheaper clicked backwards instead - real Hypixel lets you
     *  reverse-cycle a pane, same mechanic Odin's RubixHandler relies on) - every later frame reuses that
     *  SAME committed target (see {@link #rubixTargetColorIndex}) instead of re-searching, so a misclick
     *  never suddenly flashes the whole board over to a different target. Each highlighted slot's label
     *  is the signed click count: positive = click normally that many times, negative = click backwards. */
    private static Map<Integer, SlotHighlight> solveRubix(List<ItemStack> items) {
        List<int[]> panes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DyeColor color = paneDyeColor(items.get(i));
            if (color == null || color == DyeColor.BLACK) {
                continue;
            }
            int colorIndex = RUBIX_COLOR_ORDER.indexOf(color);
            if (colorIndex < 0) {
                continue;
            }
            panes.add(new int[]{i, colorIndex});
        }
        if (panes.isEmpty()) {
            return Map.of();
        }

        int cycleLength = RUBIX_COLOR_ORDER.size();
        int target = rubixTargetColorIndex;
        if (target < 0 || target >= cycleLength) {
            target = cheapestRubixTarget(panes, cycleLength);
            rubixTargetColorIndex = target;
        }

        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int[] pane : panes) {
            int forward = Math.floorMod(target - pane[1], cycleLength);
            if (forward == 0) {
                continue;
            }
            int clicksRequired = forward <= 2 ? forward : forward - cycleLength;
            result.put(pane[0], new SlotHighlight(rubixColorFor(clicksRequired), String.valueOf(clicksRequired)));
        }
        return result;
    }

    private static int cheapestRubixTarget(List<int[]> panes, int cycleLength) {
        int best = 0;
        int bestCost = Integer.MAX_VALUE;
        for (int target = 0; target < cycleLength; target++) {
            int cost = 0;
            for (int[] pane : panes) {
                int forward = Math.floorMod(target - pane[1], cycleLength);
                if (forward == 0) {
                    continue;
                }
                int clicksRequired = forward <= 2 ? forward : forward - cycleLength;
                cost += Math.abs(clicksRequired);
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = target;
            }
        }
        return best;
    }

    /** Lets {@code TermismPracticeScreen} clear the committed Rubix target when a fresh practice puzzle
     *  generates, so it doesn't accidentally inherit whatever a real terminal (or a previous practice
     *  puzzle) last committed to - {@link #rubixTargetColorIndex} is shared static state, not scoped to
     *  any one board. */
    public static void resetRubixTarget() {
        rubixTargetColorIndex = -1;
    }

    private static int rubixColorFor(int clicksRequired) {
        return clicksRequired > 0 ? RUBIX_LEFT_CLICK_COLOR : RUBIX_RIGHT_CLICK_COLOR;
    }

    private static DyeColor paneDyeColor(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        if (!(blockItem.getBlock() instanceof StainedGlassPaneBlock pane)) {
            return null;
        }
        return pane.getColor();
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "").trim();
    }
}
