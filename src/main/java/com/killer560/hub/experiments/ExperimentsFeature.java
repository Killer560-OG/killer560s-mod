package com.killer560.hub.experiments;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.notify.ModOverlayMessage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs off the client tick, reads whatever container screen is open, and does one of two completely
 * different things depending on {@link ExperimentsConfig#isAutonomousMode()}:
 * <ul>
 *   <li><b>Autonomous</b> - the real auto-clicking bot: clicks the required slot via the CLONE (Pick
 *   Block) interaction, which doesn't move/consume items, and also auto-navigates menus, claims
 *   rewards, and buys renews/XP bottles (see {@link ExperimentNavigator}). This is real automated
 *   input with no human action behind each click, the kind of thing Hypixel's rules on macros/
 *   autoclickers target directly.</li>
 *   <li><b>Solver Only</b> - per killer560's explicit correction (2026-09-06): this mode must never click
 *   anything. It only calls {@link ExperimentSolver#observe} (the same tested game-state tracking the
 *   bot itself relies on) and highlights the correct slot(s) on screen - the exact same idea as
 *   SkyHanni's own "Next Click Helper." A human using only this mode is doing 100% manual clicking; the
 *   mod just tells them where to click.</li>
 * </ul>
 */
public final class ExperimentsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-experiments");
    // Real Hypixel reward caps, confirmed by killer560 directly (2026-09-06): the maximum Enchanting Exp
    // reward is paid out once you pass 15 notes on Chronomatron or 20 numbers on Ultrasequencer -
    // going further gives literally nothing more, regardless of tier (e.g. Metaphysical caps at
    // 90,000 XP for Chronomatron / 140,000 XP for Ultrasequencer specifically, but the ROUND count
    // that triggers the cap - 15/20 - is the same across every tier). An earlier "no cap, reward
    // scales forever" conclusion drawn from a single completed run (140,000 XP at round 35) was wrong -
    // that number IS the real Metaphysical Ultrasequencer cap, it just doesn't prove anything about
    // whether stopping earlier at round 20 would have paid the same (it does).
    private static final int CHRONOMATRON_REWARD_CAP = 15;
    private static final int ULTRASEQUENCER_REWARD_CAP = 20;

    private static final ExperimentSolver SOLVER = new ExperimentSolver();
    private static final ExperimentNavigator NAVIGATOR = new ExperimentNavigator();
    private static final GuardianPetSwapper GUARDIAN_SWAPPER = new GuardianPetSwapper();
    private static String lastTitle = "";
    private static long lastClickAtMs = 0L;
    private static long lastExitAttemptAtMs = 0L;
    /** Rounds needed for max bonus on the CURRENT game, read off real "Chain of N:"/"Series of N:"
     *  lore by the navigator on the way in - -1 means none was seen, fall back to the configured
     *  default. */
    private static int activeRoundsNeeded = -1;
    /** Solver Only's max-clicks chat notification only ever fires once per round - reset alongside
     *  {@link #activeRoundsNeeded} whenever {@link #logModeChangeIfAny} sees the puzzle mode change. */
    private static boolean maxClicksNotifiedThisRound = false;
    /** The entity killer560 himself last right-clicked - captured passively via {@link UseEntityCallback}
     *  (never our own synthetic clicks, which go through {@code handleContainerInput} and never fire
     *  this), so this stays pinned to the real Experimentation Table entity for as long as killer560
     *  doesn't manually right-click something else while autonomous mode runs. Used to simulate
     *  right-clicking it again after claiming rewards closes the menu - see {@link #maybeReopenTable}. */
    private static Entity lastInteractedEntity = null;
    /** 0 = no reopen pending; otherwise the timestamp at/after which {@link #maybeReopenTable} should
     *  fire, set to "now + 1s" the moment a claim closes the table - per killer560's "have it right click
     *  again a short amount of time after maybe 1s to reopen the menu." */
    private static long pendingReopenAtMs = 0L;
    /** Actions awaiting their (optionally jittered) fire time - see {@link #scheduleAction}. Almost
     *  always 0-1 entries; a List keeps the rare same-tick double-action (e.g. a claim click AND a
     *  nav click landing together) from clobbering each other the way a single pending slot would.
     *  Generalized (2026-09-06) from a click-only list to arbitrary actions per killer560's "make sure
     *  the random delay is actually working on everything" - screen closes and /pets command sends
     *  previously fired synchronously with no jitter at all, unlike real container clicks. */
    private static final List<PendingAction> pendingActions = new ArrayList<>();
    /** The most recent cell snapshot taken while a puzzle mode was active - cached so the highlight
     *  renderer (a separate call, from the Gui mixin hook) can reuse it without re-reading the menu,
     *  and so Superpairs highlighting can confirm a remembered match is still currently valid. Empty
     *  whenever no puzzle is active. */
    private static List<ExperimentSolver.Cell> lastCells = List.of();
    /** Real item snapshot of every Superpairs slot the instant it's genuinely visible (uncovered),
     *  keyed by slot - per killer560's "show everything as the default item texture... instead of the
     *  glass pane for that box." A known-but-currently-recovered tile (flipped back to its covering
     *  glass pane, the normal memory-game behavior) still needs SOMETHING to render, since the real
     *  vanilla item render only ever shows what's ACTUALLY in the slot right now. Mirrors SkyHanni's own
     *  {@code superpairsSlotMap: MutableMap<Int, SafeItemStack>}. Slot keys are a small fixed range
     *  (9-44) that just get overwritten round after round, so this never grows unbounded. */
    private static final Map<Integer, ItemStack> superpairsIconCache = new HashMap<>();
    private static final int HIGHLIGHT_SEQUENCE_COLOR = 0xFF00FF00;
    /** The slot to click AFTER the next one (Chronomatron/Ultrasequencer) - shown one step ahead in
     *  orange, per killer560's explicit "highlight the one i should click now in green and the next one in
     *  order orange" (2026-09-06). */
    private static final int HIGHLIGHT_FOLLOWING_CLICK_COLOR = 0xFFFFA500;
    /** A Superpairs pair the solver knows matches (both slots' identities remembered) but hasn't
     *  actually been linked/found yet - see {@link #HIGHLIGHT_CONFIRMED_MATCH_COLOR} for once it has. */
    private static final int HIGHLIGHT_MATCH_COLOR = 0xFFFFD700;
    /** A known-but-not-yet-matched Superpairs single (its partner hasn't turned up yet) - distinct from
     *  a known pair (gold)/confirmed pair (green) so it's visually obvious at a glance which is which. */
    private static final int HIGHLIGHT_KNOWN_SINGLE_COLOR = 0xFF00BFFF;
    /** A Superpairs pair that's actually been linked together and found (both tiles currently revealed
     *  at once - see {@link ExperimentSolver#superpairsConfirmedMatches}), not just known - per
     *  killer560's request (2026-09-08) to tell the two states apart visually. Same green as
     *  {@link #HIGHLIGHT_SEQUENCE_COLOR} - unrelated features, just reusing the same "done/correct"
     *  color. */
    private static final int HIGHLIGHT_CONFIRMED_MATCH_COLOR = 0xFF00FF00;
    private static final int SLOT_SIZE = 16;
    /** Per killer560's explicit request: autonomous mode never starts clicking into games on its own the
     *  instant the table opens - it waits for a real click on the "Start ETable" overlay button
     *  first (see {@link #shouldShowStartButton()}), so the Guardian-pet swap always gets to run
     *  first and killer560 always gets a deliberate say in when the macro portion begins. Reset only on
     *  world change, same lifecycle as the navigator's own "done this run" flags - once armed, it
     *  stays armed for the rest of the session unless something actually interrupts that (leaving/
     *  rejoining the world). */
    private static boolean armed = false;

    /** Per killer560's explicit request (2026-09-08), after reporting he could still click the correct
     *  block then whip the mouse over and land a wrong click: a hard, unconditional 50ms lockout after
     *  EVERY click attempt on the puzzle grid (not just confirmed-correct ones, unlike
     *  {@link ExperimentSolver}'s own {@code MANUAL_CONFIRM_MIN_GAP_MS}, which only re-gates a click that
     *  would otherwise be confirmed) - see {@link #shouldBlockManualMisclick}. */
    private static long lastManualClickAttemptAtMs = 0L;
    private static final long MANUAL_CLICK_LOCKOUT_MS = 50;

    private record PendingAction(Runnable action, long fireAtMs) {
    }

    // Diagnostic-only state, not used by the solver itself - just tracked so log lines only fire on
    // an actual change instead of once per tick (20/sec).
    private static ExperimentSolver.Mode lastLoggedMode = ExperimentSolver.Mode.NONE;
    private static String lastLoggedControlItem = null;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        // Autonomous mode's "done" flags reset only here (leaving/rejoining a world), NOT every
        // time a container screen merely closes - field-tested bug (2026-09-06): resetting on every
        // close meant closing and reopening the table after finishing Chronomatron made the
        // navigator think it still needed to be done, instead of moving on to Ultrasequencer.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            NAVIGATOR.reset();
            GUARDIAN_SWAPPER.reset();
            armed = false;
        });
        // Purely observational - never cancels (always PASS) - just remembers whatever entity killer560
        // himself right-clicked, so autonomous mode can simulate the same right-click later to reopen
        // the table after claiming rewards closes it.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            lastInteractedEntity = entity;
            return InteractionResult.PASS;
        });

        // Per killer560's request: the Start ETable button (and, in its place once armed, the Guardian
        // Swap countdown) is now a real HUD-editable element - draggable/resizable via the same "Edit
        // HUD Positions" flow every other overlay uses, instead of being hardcoded to always sit just
        // below the container. Registered here but drawn from renderStartButtonOverContainer (called
        // from the container screen's OWN render pass, not this HUD-level one) - see that method's
        // doc for why the render pass matters.
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return START_BUTTON_ELEMENT_ID;
            }

            @Override
            public String displayName() {
                return "Start ETable Button";
            }

            @Override
            public int defaultX() {
                return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - START_BUTTON_WIDTH / 2;
            }

            @Override
            public int defaultY() {
                return Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2 + 90;
            }

            @Override
            public int width() {
                return START_BUTTON_WIDTH;
            }

            @Override
            public int height() {
                return START_BUTTON_HEIGHT;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                drawButtonBox(graphics, x, y, "Start ETable");
            }
        });
    }

    /** Called every frame from {@link com.killer560.hub.experiments.mixin.ExperimentsGuiMixin}. Real
     *  bug found and fixed (2026-09-07), per killer560's report that "chronomatron still never shows any
     *  type of solver at all" (same for Ultrasequencer/Superpairs): this used to draw the highlight
     *  overlay directly, but that's the SAME early {@code Gui}-level HUD pass already found and fixed
     *  for the Start ETable button (see {@link #renderStartButtonOverContainer}'s doc) - it runs BEFORE
     *  the container screen draws its own darkened background over the whole viewport, so every
     *  highlight tint got drawn and then immediately covered, invisible, even though the underlying
     *  solver logic (SOLVER.observe(), the known-item tracking, etc.) was working correctly the whole
     *  time. Now a no-op - the highlight call moved to {@link #renderStartButtonOverContainer}, the
     *  same later container-screen pass the button already uses. Left in place (rather than removing
     *  the mixin registration) since {@code ExperimentsGuiMixin} still calls this every frame. */
    public static void renderOverlay(GuiGraphicsExtractor graphics) {
    }

    private static final String START_BUTTON_ELEMENT_ID = "experiments_start_button";
    private static final int START_BUTTON_WIDTH = 100;
    private static final int START_BUTTON_HEIGHT = 20;

    /**
     * Called every frame from {@link com.killer560.hub.experiments.mixin.ExperimentsContainerRenderMixin},
     * AFTER the container screen has drawn its own background/box - real bug found and fixed
     * (2026-09-06): this used to be called from the earlier {@code Gui}-level HUD render pass, which
     * runs BEFORE the container screen draws its own darkened background over the whole viewport, so
     * the button was getting dimmed by that overlay and looked greyed-out/disabled even though it was
     * actually fully functional. Drawing it from the container screen's own render pass instead means
     * nothing draws over it afterward - same reason {@code RngMeterOverlay} does the same thing.
     * <p>
     * Per killer560's request, its position/scale are now real HUD-editable state (see the
     * {@code experiments_start_button} registration in {@link #register()}) rather than hardcoded
     * relative to the container's frame - drag it wherever via "Edit HUD Positions" like any other
     * overlay. The Guardian-swap countdown renders in the exact same spot once armed, since the two
     * are mutually exclusive by definition (the button only shows before arming).
     */
    public static void renderStartButtonOverContainer(GuiGraphicsExtractor graphics) {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        // Moved here (2026-09-07) from the old renderOverlay - see that method's doc for the real
        // z-order bug this fixes (highlights were drawing, then getting covered by the container's own
        // darkened background, invisible the whole time).
        renderHighlights(graphics, cfg);
        if (shouldShowStartButton()) {
            int[] pos = resolveStartButtonPosition();
            float scale = resolveStartButtonScale();
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawButtonBox(graphics, 0, 0, "Start ETable");
            graphics.pose().popMatrix();
        } else {
            renderGuardianSwapStatusIfNeeded(graphics);
        }
    }

    private static void drawButtonBox(GuiGraphicsExtractor graphics, int x, int y, String label) {
        graphics.fill(x, y, x + START_BUTTON_WIDTH, y + START_BUTTON_HEIGHT, 0xFF2D8A3E);
        graphics.outline(x, y, START_BUTTON_WIDTH, START_BUTTON_HEIGHT, 0xFFFFFFFF);
        graphics.centeredText(Minecraft.getInstance().font, label, x + START_BUTTON_WIDTH / 2, y + 6, 0xFFFFFFFF);
    }

    private static HudElement startButtonElement() {
        return HudElementRegistry.all().stream().filter(e -> e.id().equals(START_BUTTON_ELEMENT_ID)).findFirst().orElse(null);
    }

    private static int[] resolveStartButtonPosition() {
        HudElement element = startButtonElement();
        return element == null ? new int[]{0, 0} : HudElementRegistry.resolvePosition(element);
    }

    private static float resolveStartButtonScale() {
        HudElement element = startButtonElement();
        return element == null ? 1.0f : HudElementRegistry.resolveScale(element);
    }

    /** Live on-screen countdown for the Guardian-pet swap, per killer560's report that the swap and the
     *  /pets command "instantly" fired with no perceptible delay despite the 1s pacing logic already
     *  in {@link GuardianPetSwapper} - rather than guess at another blind fix, this makes the actual
     *  real-time countdown visible so it's obvious from watching the screen whether the delay is
     *  really elapsing or not. */
    private static void renderGuardianSwapStatusIfNeeded(GuiGraphicsExtractor graphics) {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        if (!cfg.isAutonomousMode() || !cfg.isAutoSwapGuardianPet() || !armed) {
            return;
        }
        String status = GUARDIAN_SWAPPER.statusText(System.currentTimeMillis());
        if (status == null) {
            return;
        }
        int[] pos = resolveStartButtonPosition();
        float scale = resolveStartButtonScale();
        graphics.pose().pushMatrix();
        graphics.pose().translate(pos[0], pos[1]);
        graphics.pose().scale(scale, scale);
        graphics.centeredText(Minecraft.getInstance().font, status, START_BUTTON_WIDTH / 2, 6, 0xFFFFFF55);
        graphics.pose().popMatrix();
    }

    /** @return whether the "Start ETable" button should currently be shown/clickable: the whole
     *  feature and Autonomous mode are on, the run hasn't been armed yet, and the open screen is
     *  actually the table's own main menu. Per killer560's explicit correction, this does NOT wait on
     *  the Guardian-pet swap - that only starts AFTER this button is pressed (see {@link #armed}),
     *  so it's never a reason to hide the button itself, only a reason to hold off on macro clicking
     *  once armed. Also per killer560's explicit request, this must never show at all outside Autonomous
     *  mode - {@code cfg.isAutonomousMode()} below is exactly that gate. */
    static boolean shouldShowStartButton() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isAutonomousMode() || armed) {
            return false;
        }
        return Minecraft.getInstance().screen instanceof ContainerScreen screen
                && screen.getTitle().getString().equals("Experimentation Table");
    }

    /** Called from {@link com.killer560.hub.experiments.mixin.ExperimentsInputBlockMixin} on every
     *  real mouse click to a container screen - arms the run and consumes the click if it landed on
     *  the Start ETable button, otherwise leaves the click alone. Hit-test uses the same resolved
     *  HUD position/scale the render call uses, mirroring how {@code RngMeterOverlay.handleScroll}
     *  hit-tests its own draggable overlay. */
    public static boolean tryClickStartButton(double mouseX, double mouseY) {
        if (!shouldShowStartButton()) {
            return false;
        }
        int[] pos = resolveStartButtonPosition();
        float scale = resolveStartButtonScale();
        int w = Math.round(START_BUTTON_WIDTH * scale);
        int h = Math.round(START_BUTTON_HEIGHT * scale);
        if (mouseX < pos[0] || mouseX >= pos[0] + w || mouseY < pos[1] || mouseY >= pos[1] + h) {
            return false;
        }
        armed = true;
        LOGGER.info("Start ETable clicked - autonomous mode armed");
        return true;
    }

    /** Solver-Only mode's whole visible effect: colored tints over the correct slot(s), numbered for
     *  Chronomatron/Ultrasequencer's ordered sequence, matched-pair colored for Superpairs - never a
     *  click. See the class doc for why this exists as a mode of its own. */
    private static void renderHighlights(GuiGraphicsExtractor graphics, ExperimentsConfig cfg) {
        if (!(Minecraft.getInstance().screen instanceof ContainerScreen screen)) {
            return;
        }
        ExperimentSolver.Mode mode = lastLoggedMode;
        if (mode == ExperimentSolver.Mode.NONE) {
            return;
        }
        // Real bug found and fixed (2026-09-06), per killer560's report that "the solver still doesnt
        // show the item for superpairs": this whole method used to be skipped entirely whenever
        // Autonomous Mode was on (see renderOverlay) - but killer560 has been testing the actual
        // Autonomous macro this whole time, not Solver-Only, so the Superpairs item-name labels never
        // had a chance to render at all, even though the underlying tracking (SOLVER.observe(), called
        // from inside nextClick()) was already running correctly during Autonomous mode the whole time.
        // Originally only Superpairs was exempted from the Autonomous-mode skip (Chronomatron/
        // Ultrasequencer's numbered-sequence highlight seemed redundant once the bot is already
        // clicking them in order on its own) - per killer560's explicit "can you have both of those work
        // as well in autonomouse mode just becaused," all three now render unconditionally, regardless
        // of mode.
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int left = accessor.killer560smod$getLeftPos();
        int top = accessor.killer560smod$getTopPos();
        ChestMenu menu = screen.getMenu();

        // Per killer560's report (2026-09-07): the Superpairs ghost icon (see #superpairsGhostIcon) was
        // still showing the real covering glass pane underneath/around it even though this whole method
        // already runs after the container's own slot items are extracted (see the z-order doc on
        // renderStartButtonOverContainer above). Root cause: Minecraft's GUI render-state is organized
        // into "strata" that composite back-to-front, and item icons render with real depth (the 3D-ish
        // isometric look) - drawing a second item at the same screen position within the SAME stratum as
        // the vanilla one can still lose a depth test to it despite being submitted later. Starting a
        // fresh stratum here guarantees everything drawn below (the border, ghost icon, and label) fully
        // composites on top of the vanilla-rendered pane, the same fix category as the button's own
        // earlier z-order bug.
        graphics.nextStratum();

        switch (mode) {
            // Per killer560's explicit request (2026-09-06): show only the next click (green) and the one
            // after it (orange), not the whole accumulated sequence with numbers - a clean "click this,
            // then this" indicator matching what the bot itself is about to do.
            case CHRONOMATRON -> {
                int next = SOLVER.chronomatronNextClickSlot();
                int following = SOLVER.chronomatronFollowingClickSlot();
                // Per killer560's report (2026-09-08): when the sequence repeats the same note back-to-
                // back, "next" and "following" share the exact same colored slots - whichever draws
                // LAST wins that overlap. Following (orange) drawn first, next (green) drawn last, so a
                // repeated note always shows as the correct-right-now green, never orange.
                if (following >= 0) {
                    highlightSlot(graphics, menu, following, left, top, HIGHLIGHT_FOLLOWING_CLICK_COLOR, null);
                    highlightMatchingChronomatronSlots(graphics, menu, following, left, top, HIGHLIGHT_FOLLOWING_CLICK_COLOR);
                }
                if (next >= 0) {
                    highlightSlot(graphics, menu, next, left, top, HIGHLIGHT_SEQUENCE_COLOR, null);
                    highlightMatchingChronomatronSlots(graphics, menu, next, left, top, HIGHLIGHT_SEQUENCE_COLOR);
                }
            }
            case ULTRASEQUENCER -> {
                int next = SOLVER.ultrasequencerNextClickSlot();
                int following = SOLVER.ultrasequencerFollowingClickSlot();
                if (next >= 0) highlightSlot(graphics, menu, next, left, top, HIGHLIGHT_SEQUENCE_COLOR, null);
                if (following >= 0) highlightSlot(graphics, menu, following, left, top, HIGHLIGHT_FOLLOWING_CLICK_COLOR, null);
            }
            case SUPERPAIRS -> {
                if (!cfg.isSuperpairsEnabled()) {
                    return;
                }
                // Per killer560's request: label every known tile with what's actually under it (not just
                // confirmed pairs) - "tell me what is under it in case I manually have to take over to
                // get a super rare or something." Covered-but-known singles get their own color
                // (HIGHLIGHT_KNOWN_SINGLE_COLOR) so it's visually obvious they're not a confirmed match
                // yet, but the name is shown either way.
                List<int[]> matches = SOLVER.superpairsKnownMatches(lastCells);
                Set<Integer> confirmedSlots = new HashSet<>();
                for (int[] confirmed : SOLVER.superpairsConfirmedMatches(lastCells)) {
                    confirmedSlots.add(confirmed[0]);
                    confirmedSlots.add(confirmed[1]);
                }
                Set<Integer> matchedSlots = new HashSet<>();
                for (int[] match : matches) {
                    matchedSlots.add(match[0]);
                    matchedSlots.add(match[1]);
                }
                Map<Integer, String> knownNames = SOLVER.superpairsKnownItemNames(lastCells);
                for (int[] match : matches) {
                    // Green once actually linked/found (both tiles currently revealed together), gold
                    // while just known - per killer560's request (2026-09-08).
                    int color = confirmedSlots.contains(match[0]) && confirmedSlots.contains(match[1])
                            ? HIGHLIGHT_CONFIRMED_MATCH_COLOR : HIGHLIGHT_MATCH_COLOR;
                    highlightSlot(graphics, menu, match[0], left, top, color, shortLabel(knownNames.get(match[0])));
                    highlightSlot(graphics, menu, match[1], left, top, color, shortLabel(knownNames.get(match[1])));
                }
                for (Map.Entry<Integer, String> entry : knownNames.entrySet()) {
                    if (matchedSlots.contains(entry.getKey())) continue;
                    highlightSlot(graphics, menu, entry.getKey(), left, top, HIGHLIGHT_KNOWN_SINGLE_COLOR, shortLabel(entry.getValue()));
                }
                // Per killer560's request: always show SOMETHING once the board is visible, the same way
                // SkyHanni's own Next Click Helper does, instead of going blank whenever nothing at all
                // is known yet (right at the very start of a board) - suggests the next exploration
                // click instead. No name to show for this one - its contents are genuinely unknown.
                // Per killer560's follow-up (2026-09-08): "remove the green box that always outlines the
                // very first block during the manual mode" - Solver Only no longer shows this fallback
                // suggestion at all (it was correctly re-evaluating each frame, not stuck - killer560 just
                // doesn't want it there once he's the one actually clicking); Autonomous mode keeps it,
                // since it still communicates what the bot itself is about to explore next.
                if (cfg.isAutonomousMode() && matches.isEmpty() && knownNames.isEmpty()) {
                    int suggested = SOLVER.superpairsSuggestedExploreSlot(lastCells);
                    if (suggested >= 0) {
                        highlightSlot(graphics, menu, suggested, left, top, HIGHLIGHT_SEQUENCE_COLOR, null);
                    }
                }
            }
            case NONE -> {
            }
        }
    }

    /** Strips real Minecraft "§x" color codes and truncates to a short hint - full item names (e.g.
     *  "§9Sharpness VI Enchanted Book") are far too long to fit legibly over a 16px slot, so this is
     *  just enough to recognize at a glance; hovering the real item still shows the full name/tooltip. */
    // Real bug found and fixed (2026-09-07) from killer560's screenshot: at the 0.5 render scale
    // highlightSlot draws labels at, Minecraft's font runs roughly 2.5-3px per character, so the old
    // 8-char cap (e.g. "Enchante", "Grand Ex") came out to ~20-22px wide - visibly wider than the
    // 16-18px slot itself, spilling into the NEXT slot and merging with its own label into what looked
    // like garbled/duplicated text. 5 chars (~13-15px) comfortably fits inside one slot, matching how
    // short the XP-amount labels (e.g. "131k") already are.
    private static final int SHORT_LABEL_MAX_CHARS = 5;

    private static String shortLabel(String name) {
        if (name == null) {
            return null;
        }
        String stripped = name.replaceAll("§.", "").trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > SHORT_LABEL_MAX_CHARS ? stripped.substring(0, SHORT_LABEL_MAX_CHARS) : stripped;
    }

    /** Per killer560's report (2026-09-07): Hypixel renders each Chronomatron note as a run of identical-
     *  colored blocks taller than one row - the solver only ever records ONE slot of that run (see
     *  {@code ExperimentSolver#observeChronomatron}, which always takes the ascending-order-first
     *  foiled cell), so the highlight needs to light up the REST of the run too, or only part of a
     *  multi-block note looks highlighted. Originally assumed a fixed "always exactly 9 slots apart,
     *  always exactly 2 tall" pairing, which killer560's 2026-09-08 report and screenshot showed is wrong -
     *  the very first tier's blocks are 3 tall, not 2. Fixed by not assuming any fixed height/offset at
     *  all: reads the real item at {@code primarySlot} and highlights every OTHER slot on the board
     *  showing that exact same item (color), whatever the real run length turns out to be.
     *  <p>
     *  Real bug found and fixed (2026-09-08), per killer560's report of the highlight/tracker getting
     *  confused ("wanted me to immediately click it twice") whenever the SAME note color reappeared
     *  later in the sequence with a gap (other colors) in between: matching by color across the WHOLE
     *  board, with no column restriction at all, also lit up a completely different, unrelated note
     *  that just happens to reuse the same block color for a different sequence position - not an
     *  actual multi-block run of the CURRENT note. {@link ExperimentSolver#confirmManualChronomatronClick}
     *  matches the same way (by color, not exact slot), so clicking that unrelated note was also
     *  silently accepted as fulfilling the current step, desyncing the tracked index. Fixed by
     *  restricting the "run" to the SAME COLUMN as {@code primarySlot} (a real note's multi-row run
     *  never spans columns in this GUI layout - only its height varies between tiers), so a
     *  same-colored note in a different column is never treated as part of the same run. */
    private static void highlightMatchingChronomatronSlots(GuiGraphicsExtractor graphics, ChestMenu menu, int primarySlot, int left, int top, int color) {
        String itemId = null;
        for (ExperimentSolver.Cell cell : lastCells) {
            if (cell.slot() == primarySlot) {
                itemId = cell.itemId();
                break;
            }
        }
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        int column = primarySlot % 9;
        for (ExperimentSolver.Cell cell : lastCells) {
            if (cell.slot() == primarySlot || cell.slot() < 10 || cell.slot() > 43) continue;
            if (cell.slot() % 9 != column) continue;
            if (itemId.equals(cell.itemId())) {
                highlightSlot(graphics, menu, cell.slot(), left, top, color, null);
            }
        }
    }

    /** @return the cached real item icon for a known Superpairs slot, but ONLY if that slot is
     *  currently covered again - if it's genuinely visible right now, the real vanilla render already
     *  shows it correctly and drawing a second copy on top would be redundant.
     *  <p>
     *  Also called from {@link com.killer560.hub.experiments.mixin.ExperimentsSlotItemMixin} (hence
     *  {@code public}) - per killer560's real-log report (2026-09-07) that the covering glass pane was
     *  STILL visible behind this ghost icon even after the {@code graphics.nextStratum()} draw-order fix
     *  attempt, the real fix substitutes the item at the SOURCE (what {@code Slot.getItem()} returns
     *  during {@code AbstractContainerScreen.extractSlot}) instead of trying to draw a second item on top
     *  of the vanilla one and relying on z-order/compositing to hide the first - that approach already
     *  failed once, so this one doesn't depend on draw order at all. */
    public static ItemStack superpairsGhostIcon(int slot) {
        // Real bug found and fixed (2026-09-08), per killer560's screenshot of stray Superpairs items
        // showing up around the border of Chronomatron's own blocks: this had no mode gate at all, so a
        // covered/blank-named Chronomatron slot (isRevealedPair is false for a blank name, same as an
        // actual Superpairs cover) matched just as easily as a real Superpairs one, pulling a STALE
        // cached icon left over from the last Superpairs round. superpairsIconCache is also cleared on
        // leaving Superpairs (see logModeChangeIfAny) as defense in depth, but the real fix is this mode
        // check - the cache being stale should never matter once it can't be read outside Superpairs.
        if (lastLoggedMode != ExperimentSolver.Mode.SUPERPAIRS) {
            return null;
        }
        for (ExperimentSolver.Cell cell : lastCells) {
            if (cell.slot() == slot) {
                return ExperimentSolver.isRevealedPair(cell) ? null : superpairsIconCache.get(slot);
            }
        }
        return null;
    }

    /** Real bug found and fixed (2026-09-07): this used to also draw the Superpairs ghost icon here
     *  (see {@link #superpairsGhostIcon}) on top of whatever the container's own render already showed
     *  for the slot - killer560 confirmed via real testing that once {@link com.killer560.hub.experiments.mixin.ExperimentsSlotItemMixin}
     *  started substituting the ghost icon at the SOURCE (what the container actually renders for that
     *  slot), this second manual draw became a redundant duplicate, visible as two slightly-offset
     *  copies of the same item/text layered on top of each other. Now this only draws the colored
     *  border and label - the item icon itself is handled entirely by the mixin. */
    private static void highlightSlot(GuiGraphicsExtractor graphics, ChestMenu menu, int slotIndex, int left, int top, int color, String label) {
        for (Slot slot : menu.slots) {
            if (slot.index != slotIndex) continue;
            int x0 = left + slot.x;
            int y0 = top + slot.y;
            graphics.outline(x0 - 1, y0 - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, color);
            if (label != null) {
                float scale = 0.5f;
                graphics.pose().pushMatrix();
                graphics.pose().translate(x0, y0 + SLOT_SIZE - 7);
                graphics.pose().scale(scale, scale);
                graphics.text(Minecraft.getInstance().font, label, 1, 1, 0xFFFFFFFF, true);
                graphics.pose().popMatrix();
            }
            return;
        }
    }

    private static void tick() {
        if (!ExperimentsConfig.getInstance().isEnabled()) {
            return;
        }
        try {
            tickUnsafe();
        } catch (Exception e) {
            LOGGER.error("Experiment solver tick failed", e);
        }
    }

    private static void tickUnsafe() {
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        firePendingActions(now);
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        if (!(client.screen instanceof ContainerScreen screen)) {
            SOLVER.select("");
            logModeChangeIfAny(ExperimentSolver.Mode.NONE, "");
            lastCells = List.of();
            if (cfg.isAutonomousMode()) {
                // Per killer560's explicit correction (2026-09-06): closing the table and sending /pets
                // are two separate 1s-paced steps now (see GuardianPetSwapper's WAITING_TO_SEND_PETS
                // state), and the gap between them has no container screen open at all - the swapper
                // needs to keep ticking through that gap too, not just while a screen is open.
                if (armed && cfg.isAutoSwapGuardianPet() && GUARDIAN_SWAPPER.isPending()) {
                    GuardianPetSwapper.Result swap = GUARDIAN_SWAPPER.tick("", null, now);
                    if (swap.action() != GuardianPetSwapper.Action.NONE) {
                        applyGuardianSwapAction(swap, null, now, cfg);
                    }
                } else {
                    maybeReopenTable(client, now);
                }
            }
            return;
        }

        String title = screen.getTitle().getString();
        ExperimentSolver.Mode mode = SOLVER.select(title);
        logModeChangeIfAny(mode, title);
        if (!title.equals(lastTitle)) {
            lastTitle = title;
            lastClickAtMs = 0L;
        }

        ChestMenu menu = screen.getMenu();

        if (mode != ExperimentSolver.Mode.NONE) {
            List<ExperimentSolver.Cell> cells = snapshot(menu);
            lastCells = cells;
            if (mode == ExperimentSolver.Mode.SUPERPAIRS) {
                updateSuperpairsIconCache(menu, cells);
            }
            logControlSlotIfChanged(cells);

            // Real bug found and fixed (2026-09-06), per killer560's report: "i did press my stop key
            // and that didnt stop it during this phase" - this branch used to be gated on
            // cfg.isAutonomousMode() alone, completely independent of armed. emergencyCancel() only
            // ever resets armed (and the navigator/swapper) - it never touches this config toggle - so
            // pressing the emergency-stop keybind while actively mid-puzzle (Chronomatron/
            // Ultrasequencer/Superpairs) did nothing at all to this loop: it kept observing and
            // clicking every tick regardless, since armed was never part of its gate. Only the
            // NAVIGATOR's own menu-navigation/claim/Titanic-purchase logic further below was ever
            // actually stopped by emergency cancel, since that block already gated on "&& armed".
            // Requiring armed here too means the moment emergency cancel clears it, this loop falls
            // through to the same observe()-only, never-clicks path Solver-Only mode already uses.
            if (cfg.isAutonomousMode() && armed) {
                if (chainLengthAtOrOverMax(mode, cfg)) {
                    tryExitFinishedRound(screen, menu, now);
                    return;
                }
                OptionalInt click = SOLVER.nextClick(cells, cfg.isSuperpairsEnabled(), cfg.isSuperpairsValuableOnly(),
                        now, lastClickAtMs, cfg.getDelayMs(), cfg.getFirstClickDelayMs());
                if (click.isPresent()) {
                    int slot = click.getAsInt();
                    LOGGER.info("Clicking slot {} (mode={})", slot, mode);
                    lastClickAtMs = now;
                    scheduleClick(menu.containerId, slot, now, cfg);
                }
            } else {
                // Solver Only: observe the exact same tested game state so the highlight overlay
                // (renderHighlights) is accurate, but NEVER click anything - per killer560's explicit
                // "that mode shouldn't click at all inside of the exp table. It should only
                // highlight the correct blocks the exact same way skyhanni does."
                SOLVER.observe(cells, cfg.isSuperpairsValuableOnly(), now);
                maybeNotifyMaxClicksReached(mode, cfg);
            }
        } else {
            lastCells = List.of();
            // Solver Only has no navigator of its own driving it to the tier-pick screen - killer560
            // clicks a tier himself - so this passively scans whatever screen is open for the same
            // rounds-needed lore Autonomous mode's navigator would have discovered while picking a
            // tier itself. No-op unless a real tier-pick screen is actually showing right now.
            if (!cfg.isAutonomousMode()) {
                NAVIGATOR.peekTierScreenForRoundsNeeded(menu, title);
            }
        }

        if (cfg.isAutonomousMode() && armed) {
            // Per killer560's explicit correction (2026-09-06): the Guardian-pet swap must NOT run on
            // its own the instant the table opens - it only starts after the "Start ETable" button
            // is actually pressed (armed), same as the macro clicking it gates below. While it's
            // still pending, normal navigation is held off entirely so the swap always finishes
            // first once it does begin.
            boolean guardianPending = cfg.isAutoSwapGuardianPet() && GUARDIAN_SWAPPER.isPending();
            if (guardianPending) {
                GuardianPetSwapper.Result swap = GUARDIAN_SWAPPER.tick(title, menu, now);
                if (swap.action() != GuardianPetSwapper.Action.NONE) {
                    applyGuardianSwapAction(swap, menu, now, cfg);
                }
            } else if (looksLikeExperimentationTableScreen(title)) {
                // Real bug found and fixed (2026-09-06): killer560 manually closing the table mid-run,
                // then opening something unrelated (Pets, Auction House, ...) used to still trigger
                // navigation clicks - neither tryClaimIfAvailable() nor the navigator's own tier-pick
                // fallback ever checked that the open screen was actually part of the Experimentation
                // Table at all, so ANY open container with mode==NONE (true for basically every
                // screen that isn't literally one of the three puzzles) fell into "must be a stakes/
                // tier picker, try to click a tier" or "scan for anything claim-like." Both are now
                // gated behind this title check.
                tryClaimIfAvailable(menu, now);
                int navSlot = NAVIGATOR.nextNavigationClick(screen, mode, now, cfg.getAutoRenewCount(), cfg.getTitanicMaxPriceCoins());
                if (navSlot >= 0) {
                    scheduleClick(menu.containerId, navSlot, now, cfg);
                } else if (navSlot == ExperimentNavigator.CLOSED_SIGNAL) {
                    // Per killer560's explicit request (2026-09-07): "it should almost never close the
                    // menu unless it is swapping pet" - backing out of a sub-screen (e.g. Bottles of
                    // Enchanting after buying/failing to buy a Titanic bottle, or a Stakes screen
                    // locked by "Not enough experience!") used to fully close the whole GUI and
                    // re-open the table via a real entity right-click a second later. Hypixel's own
                    // menus already have a real "Go Back" item for this exact purpose (confirmed from
                    // killer560's screenshot) - clicking that instead stays entirely within the menu, no
                    // close/reopen round trip needed. Falls back to the old close+reopen only if no
                    // Go Back item is found on the current screen, so this can't get the run stuck.
                    int goBackSlot = findGoBackSlot(menu);
                    if (goBackSlot >= 0) {
                        scheduleClick(menu.containerId, goBackSlot, now, cfg);
                    } else {
                        scheduleAction(() -> {
                            if (Minecraft.getInstance().screen != null) {
                                Minecraft.getInstance().screen.onClose();
                            }
                            armReopenAfterClaim(System.currentTimeMillis());
                        }, now, cfg);
                    }
                } else if (navSlot == ExperimentNavigator.DONE_SIGNAL) {
                    // Per killer560's explicit "it should stop on this screen and flash an alert... to
                    // tell me that it is done for the day" - deliberately instant, same reasoning as
                    // emergencyCancel(): never routed through scheduleAction's jitter, and never
                    // touches whatever screen is currently open (stays exactly where it is).
                    String doneReason = NAVIGATOR.takeDoneReason();
                    LOGGER.info("Autonomous run finished on its own: {}", doneReason);
                    resetRunState();
                    ModOverlayMessage.show("§a[Killer560's Mod] Experiment Table automation finished: " + doneReason, 6000);
                }
            }
        }
    }

    /** Translates a {@link GuardianPetSwapper} step into the real client actions it needs: closing
     *  the table, running {@code /pets} (the same real chat-command send path {@code TranslateFeature}
     *  already uses), clicking the best Guardian slot found (or the "Next Page" item, same
     *  mechanism), flashing a "not found" message, or closing the Pets screen and reopening the table
     *  via the same entity-right-click mechanism claiming rewards already uses. Every branch goes
     *  through {@link #scheduleAction}/{@link #scheduleClick} so the random-delay setting applies
     *  here exactly like every other action in this feature. */
    private static void applyGuardianSwapAction(GuardianPetSwapper.Result swap, ChestMenu menu, long now, ExperimentsConfig cfg) {
        Minecraft client = Minecraft.getInstance();
        switch (swap.action()) {
            case CLOSE_TABLE -> scheduleAction(() -> {
                if (client.screen != null) {
                    client.screen.onClose();
                }
            }, now, cfg);
            case RUN_PETS_COMMAND -> scheduleAction(() -> {
                if (client.player != null) {
                    client.player.connection.sendCommand("pets");
                }
            }, now, cfg);
            case CLICK_SLOT, NEXT_PAGE -> scheduleClick(menu.containerId, swap.slot(), now, cfg);
            case CLOSE_NOT_FOUND -> scheduleAction(() -> {
                if (Minecraft.getInstance().screen != null) {
                    Minecraft.getInstance().screen.onClose();
                }
                ModOverlayMessage.show("§c[Killer560's Mod] No Guardian pet found!", 4000);
            }, now, cfg);
            case CLOSE_AND_REOPEN -> scheduleAction(() -> {
                if (Minecraft.getInstance().screen != null) {
                    Minecraft.getInstance().screen.onClose();
                }
                armReopenAfterClaim(System.currentTimeMillis());
            }, now, cfg);
            case NONE -> {
            }
        }
    }

    /** @return whether {@code title} looks like any screen the Experimentation Table flow can show:
     *  the main menu, the XP-bottle shop, the natural-end reward screen, or a puzzle/stakes screen
     *  for one of the three games - anything else (Pets, Auction House, Wardrobe, ...) is deliberately
     *  NOT touched by autonomous mode, even while mode tracking elsewhere is momentarily NONE. */
    private static boolean looksLikeExperimentationTableScreen(String title) {
        return title.equals("Experimentation Table")
                || title.equals("Bottles of Enchanting")
                || title.equals("Experiment Over")
                || title.contains("Chronomatron")
                || title.contains("Ultrasequencer")
                || title.startsWith("Superpairs");
    }

    /**
     * Field-tested (2026-09-06): when Ultrasequencer played all the way to its own natural end (no
     * early exit - see {@link #chainLengthAtOrOverMax}), the resulting "Experiment Over" screen never
     * got its "Click to claim rewards!" item clicked, because that screen's mode is NONE (not a
     * puzzle) so {@link #tryExitFinishedRound} never runs, AND the navigator itself refuses to click
     * anything once it thinks the whole run is complete. Claiming a finished reward screen has nothing
     * to do with whether the autonomous RUN is "done," so it's checked unconditionally here, every
     * tick, independent of mode/run-complete state.
     */
    private static void tryClaimIfAvailable(ChestMenu menu, long now) {
        if (now - lastExitAttemptAtMs < 1000) {
            return;
        }
        int claimSlot = findClaimSlot(menu);
        if (claimSlot < 0) {
            return;
        }
        lastExitAttemptAtMs = now;
        LOGGER.info("Claim item found - clicking slot {}", claimSlot);
        scheduleClick(menu.containerId, claimSlot, now, ExperimentsConfig.getInstance());
        armReopenAfterClaim(now);
    }

    /**
     * Field-tested (2026-09-06): claiming Chronomatron/Ultrasequencer rewards closes the whole table
     * menu instead of returning to the main menu, which stopped autonomous mode cold since nothing
     * else re-opens it. Per killer560's request, this schedules a real right-click-equivalent interact
     * on the table entity ~1s later, exactly like actually walking up and right-clicking it again.
     */
    private static void armReopenAfterClaim(long now) {
        pendingReopenAtMs = now + 1000;
    }

    /** Simulates right-clicking whatever entity killer560 last actually interacted with (see
     *  {@link #lastInteractedEntity}) - real {@code MultiPlayerGameMode.interact(...)} call, verified
     *  via javap to send the same {@code ServerboundInteractPacket} a genuine right-click does, not
     *  just client-side prediction. */
    private static void maybeReopenTable(Minecraft client, long now) {
        if (pendingReopenAtMs == 0 || now < pendingReopenAtMs) {
            return;
        }
        pendingReopenAtMs = 0;
        Entity entity = lastInteractedEntity;
        if (entity == null || !entity.isAlive() || client.player == null || client.gameMode == null) {
            LOGGER.warn("Wanted to reopen the table but no valid remembered entity to right-click");
            return;
        }
        LOGGER.info("Reopening the table by right-clicking entity {}", entity.getId());
        client.gameMode.interact(client.player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
    }

    private static boolean chainLengthAtOrOverMax(ExperimentSolver.Mode mode, ExperimentsConfig cfg) {
        if (cfg.getStopStrategy() == ExperimentStopStrategy.MAX_XP) {
            // Confirmed directly by killer560 (2026-09-06), correcting an earlier wrong conclusion: the
            // 140,000 XP paid out for a real round-35 Ultrasequencer completion is NOT proof of an
            // uncapped reward - it's exactly the real Metaphysical-tier cap, reached the moment you
            // pass 20 numbers (15 notes for Chronomatron); going further pays nothing more regardless
            // of tier. So MAX_XP means exactly what it always should have: stop once that real cap is
            // reached, since continuing is pure wasted time.
            int cap = mode == ExperimentSolver.Mode.CHRONOMATRON ? CHRONOMATRON_REWARD_CAP
                    : mode == ExperimentSolver.Mode.ULTRASEQUENCER ? ULTRASEQUENCER_REWARD_CAP : Integer.MAX_VALUE;
            return SOLVER.currentChainLength() >= cap;
        }
        // MAX_CLICKS: only stop once real "Chain of N:"/"Series of N:" lore was actually found -
        // no manual fallback number, per killer560's explicit "I want skyhanni's format of auto
        // detection not manual choice." If it's never found, this strategy just never backs out.
        return roundsAtOrOverMaxClicksThreshold(mode);
    }

    /** The lore-based "Chain of N:"/"Series of N:" auto-detect threshold itself, independent of
     *  {@link ExperimentStopStrategy} - Solver Only's max-clicks chat notification (see
     *  {@link #maybeNotifyMaxClicksReached}) always uses this specific detection regardless of the
     *  configured stop strategy, since Solver Only doesn't expose or use that setting at all (it's an
     *  Autonomous-only concept - see {@code ExperimentsTab}). Threshold mirrors SkyHanni's own
     *  comparison exactly (SuperpairsClicksAlert.kt): Chronomatron checks round > roundsNeeded,
     *  Ultrasequencer checks round > roundsNeeded - 1 (a documented Hypixel bug makes Ultrasequencer
     *  need one fewer round) - both rewritten here as >= so they compose with currentChainLength()
     *  cleanly. */
    private static boolean roundsAtOrOverMaxClicksThreshold(ExperimentSolver.Mode mode) {
        if (activeRoundsNeeded <= 0) {
            return false;
        }
        int threshold = mode == ExperimentSolver.Mode.CHRONOMATRON ? activeRoundsNeeded + 1 : activeRoundsNeeded;
        return SOLVER.currentChainLength() >= threshold;
    }

    /** Solver Only has no stop condition of its own - it never backs out of anything - so per
     *  killer560's request (2026-09-08), this sends a real client-side chat message (not the action-bar
     *  {@link ModOverlayMessage} popup - a message that stays in the chat log) the first time the same
     *  max-clicks threshold Autonomous mode would have stopped at is reached, gated behind
     *  {@link ExperimentsConfig#isNotifyMaxClicksReached()} so it's fully optional. Only ever fires
     *  once per round - {@link #maxClicksNotifiedThisRound} is reset in {@link #logModeChangeIfAny}. */
    private static void maybeNotifyMaxClicksReached(ExperimentSolver.Mode mode, ExperimentsConfig cfg) {
        if (!cfg.isNotifyMaxClicksReached() || maxClicksNotifiedThisRound) {
            return;
        }
        if (mode != ExperimentSolver.Mode.CHRONOMATRON && mode != ExperimentSolver.Mode.ULTRASEQUENCER) {
            return;
        }
        if (!roundsAtOrOverMaxClicksThreshold(mode)) {
            return;
        }
        maxClicksNotifiedThisRound = true;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            // Per killer560's explicit request (2026-09-08): the whole line in orange (§6/GOLD - the
            // closest vanilla color code to true orange, matching this mod's own amber/orange theme
            // elsewhere), plus a real sound so it's noticed even if chat isn't being watched right now -
            // same play(SimpleSoundInstance.forUI(...)) pattern already used for the /killer560 command.
            player.sendSystemMessage(Component.literal(
                    "§6[Killer560's Mod] You've reached the max rounds needed for max clicks (round "
                            + activeRoundsNeeded + ")."));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        }
    }

    /** Whether killer560's own mouse/keyboard input to a container screen should be swallowed right
     *  now - only ever true while autonomous mode AND this specific toggle are both on, so it can
     *  never activate on its own (per killer560's "this will only be able to be enabled if the auto
     *  solver is on"), and only once the run is actually armed (the "Start ETable" button must stay
     *  clickable beforehand, and there's nothing to protect against interfering with before then
     *  anyway). Checked from {@link com.killer560.hub.experiments.mixin.ExperimentsInputBlockMixin},
     *  which only targets {@code AbstractContainerScreen} in the first place - this mod's own menu
     *  screens aren't container screens, so they're never affected regardless. */
    public static boolean shouldBlockInput() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return cfg.isEnabled() && cfg.isAutonomousMode() && cfg.isBlockInputEnabled() && armed;
    }

    /** Per killer560's explicit request (2026-09-08): in Solver Only mode, prevent clicking anything
     *  except the exact slot(s) the solver currently says are correct for Chronomatron/Ultrasequencer -
     *  real Shift-held override still lets a click through regardless. Gated on
     *  {@link ExperimentsConfig#isClickProtectionEnabled()} (added 2026-09-08 per killer560's request -
     *  previously always-on with no way to disable it). Deliberately never applies to Superpairs - that
     *  puzzle's whole mechanic is clicking still-covered tiles to explore them, so almost any click is
     *  legitimate, not a misclick. Also doubles as the mechanism that advances
     *  {@link ExperimentSolver#confirmManualChronomatronClick}/{@code confirmManualUltrasequencerClick}
     *  when the click IS correct - Solver Only never routes through the autonomous decide-and-advance
     *  path, so without this the highlight would freeze on the same slot forever regardless of what
     *  killer560 actually clicked.
     *  <p>
     *  Real bug found and fixed (2026-09-08), per killer560's report that Shift-held did not actually
     *  override the block: the old code returned early on {@code event.hasShiftDown()} BEFORE ever
     *  calling {@code confirmManualChronomatronClick}/{@code confirmManualUltrasequencerClick} - so a
     *  Shift-click on the genuinely correct slot got let through to the game (unblocked, as intended)
     *  but never advanced the solver's own tracked index, desyncing the highlight/tracker from what had
     *  actually been clicked. Every click AFTER that one then looked wrong too, since the tracker was
     *  still expecting the slot from before the Shift-click - indistinguishable from "the override
     *  doesn't work" even though the click itself did go through. Fixed by always attempting to confirm
     *  the click first (a click on the wrong slot never advances anything either way - see
     *  {@code confirmManualChronomatronClick}'s own early-return), and only using Shift to decide
     *  whether an incorrect click should still be let through.
     *  @return true if the click should be BLOCKED (cancelled). */
    public static boolean shouldBlockManualMisclick(MouseButtonEvent event) {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isClickProtectionEnabled() || cfg.isAutonomousMode()) {
            return false;
        }
        if (lastLoggedMode != ExperimentSolver.Mode.CHRONOMATRON && lastLoggedMode != ExperimentSolver.Mode.ULTRASEQUENCER) {
            return false;
        }
        if (!(Minecraft.getInstance().screen instanceof ContainerScreen screen)) {
            return false;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        ChestMenu menu = screen.getMenu();
        int left = accessor.killer560smod$getLeftPos();
        int top = accessor.killer560smod$getTopPos();
        int slot = hitTestSlot(menu, left, top, event.x(), event.y());
        int containerSlotCount = menu.slots.size() - 36;
        if (slot < 0 || slot >= containerSlotCount) {
            return false;
        }
        // Per killer560's report (2026-09-08) that he could still click the correct block, whip the
        // mouse over, and land a wrong click: a hard, unconditional lockout right after ANY click
        // attempt here - unlike the solver's own MANUAL_CONFIRM_MIN_GAP_MS below, which only re-gates a
        // click that would otherwise be CONFIRMED correct, this blocks every attempt (correct or not,
        // Shift-held or not) and never lets the solver advance, for the full 50ms after the previous one.
        long now = System.currentTimeMillis();
        long sinceLastAttempt = now - lastManualClickAttemptAtMs;
        if (sinceLastAttempt < MANUAL_CLICK_LOCKOUT_MS) {
            LOGGER.info("Misclick guard: LOCKOUT slot={} sinceLastAttempt={}ms (< {}ms)",
                    slot, sinceLastAttempt, MANUAL_CLICK_LOCKOUT_MS);
            return true;
        }
        lastManualClickAttemptAtMs = now;
        // Re-snapshotting live at the exact moment of the click (real bug found and fixed 2026-09-08,
        // see git history) rather than reusing lastCells (a per-tick cache, up to 50ms stale) removes a
        // staleness window that could read a momentarily wrong/blank item for a slot that's actually
        // correct right now.
        boolean correct = lastLoggedMode == ExperimentSolver.Mode.CHRONOMATRON
                ? SOLVER.confirmManualChronomatronClick(slot, snapshot(menu))
                : SOLVER.confirmManualUltrasequencerClick(slot);
        // Diagnostic logging (2026-09-08) - killer560 has now reported "click correct, whip mouse over,
        // click wrong, it goes through" TWICE, once before and once after the lockout above was added,
        // and static analysis says this whole function should already be rejecting a genuinely wrong
        // slot regardless of timing (see the final `return true` below). Logging every real decision so
        // the next test run gives an actual log to root-cause from instead of a third blind guess.
        LOGGER.info("Misclick guard: slot={} sinceLastAttempt={}ms correct={} shiftDown={} mode={}",
                slot, sinceLastAttempt, correct, event.hasShiftDown(), lastLoggedMode);
        if (correct) {
            return false;
        }
        if (event.hasShiftDown()) {
            // Real bug found and fixed (2026-09-08), per killer560's report that Shift-held still
            // didn't let the click through: un-blocking the click here isn't enough on its own, because
            // vanilla's OWN mouseClicked branches on hasShiftDown() itself - verified via javap that
            // AbstractContainerScreen sends a ContainerInput.QUICK_MOVE action whenever Shift is held,
            // never a normal click. Hypixel's custom Chronomatron/Ultrasequencer menu doesn't treat a
            // quick-move as "click this note," so the override never actually did anything even though
            // it wasn't being blocked. Cancelling vanilla's handling unconditionally and sending the
            // real click ourselves via the same clickSlot mechanism the autonomous auto-clicker already
            // uses successfully sidesteps vanilla's shift-specific branching entirely.
            clickSlot(menu.containerId, slot);
            return true;
        }
        return true;
    }

    /** @return the slot index whose real screen rectangle contains ({@code mouseX}, {@code mouseY}), or
     *  -1 if it lands on none of them - the reverse of what {@link #highlightSlot} does with a known
     *  slot index. */
    private static int hitTestSlot(ChestMenu menu, int left, int top, double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            int x0 = left + slot.x;
            int y0 = top + slot.y;
            if (mouseX >= x0 && mouseX < x0 + SLOT_SIZE && mouseY >= y0 && mouseY < y0 + SLOT_SIZE) {
                return slot.index;
            }
        }
        return -1;
    }

    /**
     * Per killer560's explicit request: an emergency stop, bound to its own keybind (polled raw against
     * the keyboard every tick from {@link com.killer560.hub.Killer560ModClient}, the same mechanism
     * the HUD editor's own keybind already uses - NOT routed through any container screen's key-event
     * handling, so it fires "even on the prevent keypress option," which only ever blocks input a
     * screen's own listeners would otherwise see). Immediately un-arms the run, resets both the
     * navigator and the Guardian-pet swapper back to their idle state (so "Start ETable" reappears
     * and a fresh run can begin cleanly), drops any actions still queued, and cancels a pending table
     * reopen - a real bug killer560 found and reported: once armed, there was previously no way at all
     * to interrupt or reset a run in progress short of leaving and rejoining the world.
     * <p>
     * Deliberately instant - never routed through {@link #scheduleAction}'s jitter, since a random
     * delay on an EMERGENCY stop would defeat the entire point of it.
     * <p>
     * Per killer560's explicit correction: never closes anything at all, regardless of what's open -
     * "just stop the script from doing any more actions." Whatever screen is currently open (the
     * table, Pets, this mod's own menu, anything) is left exactly as-is; this only stops the
     * automation state from producing any further clicks/commands/closes of its own.
     */
    public static void emergencyCancel() {
        // Per killer560's request (2026-09-07): only announce a cancel if a run was actually armed/running
        // at the moment the keybind fired - pressing it while doing something else entirely (autonomous
        // mode off, or on but no run started yet) has nothing to actually cancel, so it shouldn't pop up
        // a "cancelled" message implying it interrupted something.
        boolean wasArmed = armed;
        resetRunState();
        if (wasArmed) {
            LOGGER.info("Emergency cancel triggered - autonomous run stopped and reset");
            ModOverlayMessage.show("§c[Killer560's Mod] Experiment Table automation cancelled", 3000);
        }
    }

    /** Shared by {@link #emergencyCancel()} and the natural DONE_SIGNAL stop (see the navigator branch
     *  in {@link #tickUnsafe()}) - un-arms the run and resets everything back to idle, without touching
     *  whatever screen is currently open. Extracted (2026-09-06) once a second caller needed the exact
     *  same reset, per killer560's "it should stop on this screen" - a natural stop should leave the
     *  screen alone exactly like an emergency cancel already does. */
    private static void resetRunState() {
        armed = false;
        NAVIGATOR.reset();
        GUARDIAN_SWAPPER.reset();
        pendingActions.clear();
        pendingReopenAtMs = 0L;
    }

    /**
     * Once the configured max chain length is reached, autonomous mode backs out instead of
     * continuing to grind: looks for an item whose lore says to claim rewards (same real text
     * SkyHanni's own claim-detection uses - "Click to claim rewards!") and clicks it if present,
     * otherwise presses escape (via the screen's own onClose(), exactly what the Escape key does -
     * verified via javap that this is what actually notifies the server the container closed,
     * unlike just blanking the screen). Paced the same fixed 1s as menu navigation.
     */
    private static void tryExitFinishedRound(ContainerScreen screen, ChestMenu menu, long now) {
        if (now - lastExitAttemptAtMs < 1000) {
            return;
        }
        lastExitAttemptAtMs = now;
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        int claimSlot = findClaimSlot(menu);
        if (claimSlot >= 0) {
            LOGGER.info("Max chain reached - clicking claim slot {}", claimSlot);
            scheduleClick(menu.containerId, claimSlot, now, cfg);
            armReopenAfterClaim(now);
            return;
        }
        LOGGER.info("Max chain reached - no claim item found, pressing escape");
        scheduleAction(() -> {
            screen.onClose();
            armReopenAfterClaim(System.currentTimeMillis());
        }, now, cfg);
    }

    private static int findClaimSlot(ChestMenu menu) {
        // Same lesson as ExperimentNavigator's tier/stakes search: ChestMenu always appends the full
        // 36-slot player inventory after the container's own grid, so this must stay out of scope too.
        int containerSlotCount = menu.slots.size() - 36;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                if (line.getString().toLowerCase(Locale.US).contains("claim")) {
                    return slot.index;
                }
            }
        }
        return -1;
    }

    /** @return the slot of the real "Go Back" item Hypixel's own sub-menus (Bottles of Enchanting, the
     *  Stakes tier-pick screens) show for returning to the Experimentation Table, or -1 if the current
     *  screen doesn't have one - matched on the item's real display name per killer560's screenshot
     *  ("Go Back" name, "To Experimentation Table" lore). See the {@code CLOSED_SIGNAL} handling in
     *  {@link #tickUnsafe} for why this replaced closing and re-opening the whole menu. */
    private static int findGoBackSlot(ChestMenu menu) {
        int containerSlotCount = menu.slots.size() - 36;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getHoverName().getString().toLowerCase(Locale.US).contains("go back")) {
                return slot.index;
            }
        }
        return -1;
    }

    private static void logModeChangeIfAny(ExperimentSolver.Mode mode, String title) {
        if (mode != lastLoggedMode) {
            LOGGER.info("Experiment mode changed: {} -> {} (title=\"{}\")", lastLoggedMode, mode, title);
            // Defense in depth alongside the mode gate in superpairsGhostIcon - clears stale cached
            // icons the instant Superpairs is left, rather than trusting the gate alone to keep them
            // from ever being read again.
            if (lastLoggedMode == ExperimentSolver.Mode.SUPERPAIRS && mode != ExperimentSolver.Mode.SUPERPAIRS) {
                superpairsIconCache.clear();
            }
            lastLoggedMode = mode;
            lastLoggedControlItem = null;
            maxClicksNotifiedThisRound = false;
            if (mode == ExperimentSolver.Mode.CHRONOMATRON || mode == ExperimentSolver.Mode.ULTRASEQUENCER) {
                int discovered = NAVIGATOR.takePendingRoundsNeeded();
                activeRoundsNeeded = discovered;
                if (discovered > 0) {
                    LOGGER.info("Using rounds-needed={} discovered from stakes lore for this round", discovered);
                }
            } else {
                activeRoundsNeeded = -1;
            }
        }
    }

    /** Logs slot 49's item id whenever it changes - lets a real test confirm the "glowstone" /
     *  "clock" control-item assumption the solver is built on actually matches this live game. */
    private static void logControlSlotIfChanged(List<ExperimentSolver.Cell> cells) {
        String controlItem = cells.stream()
                .filter(c -> c.slot() == 49)
                .findFirst()
                .map(c -> c.empty() ? "(empty)" : c.itemId())
                .orElse("(no slot 49)");
        if (!controlItem.equals(lastLoggedControlItem)) {
            LOGGER.info("Control slot (49) changed to: {}", controlItem);
            lastLoggedControlItem = controlItem;
        }
    }

    /**
     * Queues {@code action} to run after an optional random jitter (0..{@code randomDelayMaxMs}),
     * added on top of whatever fixed delay already decided it was time to act - per killer560's "added
     * onto any click it performs" (2026-09-06 clarified to "make sure the random delay is actually
     * working on everything"), applying uniformly to every real client-visible action this feature
     * performs: container clicks (via {@link #scheduleClick}), closing a screen, and sending the
     * {@code /pets} command. Bookkeeping fields like {@code lastClickAtMs} are still updated
     * immediately at the call site, exactly as before jitter existed - only the actual action is
     * deferred, so none of the solver's/navigator's/swapper's own pacing logic needs to know jitter
     * exists at all.
     */
    private static void scheduleAction(Runnable action, long now, ExperimentsConfig cfg) {
        int maxJitter = cfg.getRandomDelayMaxMs();
        if (maxJitter <= 0) {
            action.run();
            return;
        }
        long fireAtMs = now + ThreadLocalRandom.current().nextInt(maxJitter + 1);
        pendingActions.add(new PendingAction(action, fireAtMs));
    }

    private static void scheduleClick(int containerId, int slot, long now, ExperimentsConfig cfg) {
        // The real click only happens once this jittered action actually fires, which can be well
        // after `now` - Superpairs' confirm-timeout clock needs to start from that real send time, not
        // the moment this click was decided (see ExperimentSolver#superpairsClickSent). Harmless no-op
        // for Chronomatron/Ultrasequencer/navigation/claim clicks, which never arm that gate at all.
        scheduleAction(() -> {
            clickSlot(containerId, slot);
            SOLVER.superpairsClickSent(slot, System.currentTimeMillis());
        }, now, cfg);
    }

    /** Fires any scheduled actions whose jittered time has arrived - checked every tick regardless of
     *  what screen (if any) is currently open, since a jittered action can outlive the screen change
     *  that queued it. */
    private static void firePendingActions(long now) {
        if (pendingActions.isEmpty()) {
            return;
        }
        Iterator<PendingAction> it = pendingActions.iterator();
        while (it.hasNext()) {
            PendingAction pending = it.next();
            if (now >= pending.fireAtMs()) {
                it.remove();
                pending.action().run();
            }
        }
    }

    private static boolean clickSlot(int containerId, int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            LOGGER.warn("Wanted to click slot {} but player/gameMode was null", slot);
            return false;
        }
        client.gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.CLONE, client.player);
        return true;
    }

    private static List<ExperimentSolver.Cell> snapshot(ChestMenu menu) {
        List<ExperimentSolver.Cell> cells = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            boolean empty = stack == null || stack.isEmpty();
            cells.add(new ExperimentSolver.Cell(
                    slot.index,
                    empty ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    empty ? 0 : stack.getCount(),
                    !empty && stack.hasFoil(),
                    empty ? "" : stack.getHoverName().getString(),
                    empty,
                    empty ? "" : loreText(stack)));
        }
        return cells;
    }

    /** Snapshots the real {@link ItemStack} of every currently-visible (uncovered) Superpairs slot into
     *  {@link #superpairsIconCache}, so a slot that later flips back to its covering glass pane can
     *  still render its actual item icon instead of just a text label - see that field's doc. Uses the
     *  exact same reveal classification ({@link ExperimentSolver#isRevealedPair}) the solver itself
     *  relies on for identity-tracking, so this stays consistent with what the highlight overlay
     *  considers "known." */
    private static void updateSuperpairsIconCache(ChestMenu menu, List<ExperimentSolver.Cell> cells) {
        for (ExperimentSolver.Cell cell : cells) {
            if (!ExperimentSolver.isRevealedPair(cell)) continue;
            for (Slot slot : menu.slots) {
                if (slot.index == cell.slot()) {
                    superpairsIconCache.put(cell.slot(), slot.getItem().copy());
                    break;
                }
            }
        }
    }

    /** All lore lines joined with newlines, for the solver to keyword-match against (e.g. the real
     *  Superpairs bonus tile's "Powerup" text) without needing an exact display-name match, which
     *  field-tested (2026-09-06) turned out to vary between instances ("Powerup for next click!" vs
     *  "Instant powerup!"). */
    private static String loreText(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Component line : lore.lines()) {
            sb.append(line.getString()).append('\n');
        }
        return sb.toString();
    }

    private ExperimentsFeature() {
    }
}
