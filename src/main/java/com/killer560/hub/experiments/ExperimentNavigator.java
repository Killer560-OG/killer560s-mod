package com.killer560.hub.experiments;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives "autonomous mode": from the Experimentation Table's main menu, opens Chronomatron, picks
 * the highest tier available, lets {@link ExperimentSolver} play it, then repeats for Ultrasequencer
 * and finally Superpairs (field-tested 2026-09-06: initially left unwired since killer560 deferred it,
 * then added once he started actually testing it - same live-recheck pattern as the other two,
 * including the real "Play add-ons first!" lock text Superpairs shows when it has a charge but the
 * add-ons still need to be replayed).
 * <p>
 * Field-tested and screenshot-confirmed (2026-09-06): clicking Chronomatron/Ultrasequencer from the
 * main menu goes straight to a "&lt;Game&gt; ➜ Stakes" screen that shows several tier items
 * (Beginner/High/Grand/Supreme/Transcendent/Metaphysical) side by side - picking a tier both chooses
 * the stakes AND starts the game immediately, there is no separate confirmation screen. Any screen
 * with 2+ items named after a known tier is treated as this tier/stakes picker (skipping any that are
 * locked - "Enchanting level too low!"/"Not enough experience!"/"Practice mode has no rewards", the
 * real lock-lore text confirmed via SkyHanni's own {@code ExperimentsAddonsHelper.kt}). The best one
 * is picked by matching its own name against {@link #TIER_NAMES} (ordered highest to lowest) rather
 * than by screen position - field-tested (2026-09-06): Superpairs lays all 6 tiers out across TWO
 * rows, unlike Chronomatron/Ultrasequencer's single row, so "furthest right column" doesn't reflect
 * tier rank at all once a game wraps to a second row.
 * <p>
 * There is no known signal for the puzzle's own true completion round - it plays out to a random
 * natural end, confirmed by reading SkyHanni's own source, which doesn't try to predict that either.
 * What IS real and readable up front, straight from the chosen tier's own tooltip, is the round at
 * which the maximum extra Superpairs-click bonus is earned (see {@link #readMaxClickBonusRounds}) -
 * that's what {@link ExperimentsFeature}'s MAX_CLICKS stop strategy actually exits at.
 * <p>
 * Per killer560's explicit correction: this navigator's own menu-to-menu clicks are ALWAYS spaced by a
 * fixed 1 second, no first-click grace period here - that only applies inside the puzzle-solving
 * itself (see {@link ExperimentSolver}), not menu navigation.
 */
final class ExperimentNavigator {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-experiments-nav");
    private static final String MAIN_MENU_TITLE = "Experimentation Table";
    private static final String BOTTLES_TITLE = "Bottles of Enchanting";
    private static final List<String> TIER_NAMES = List.of(
            "Metaphysical", "Transcendent", "Supreme", "Grand", "High", "Beginner");
    private static final List<String> LOCKED_LORE_MARKERS = List.of(
            "Enchanting level too low!", "Not enough experience!", "Practice mode has no rewards");
    private static final Pattern ROUNDS_NEEDED_PATTERN = Pattern.compile("(?:Chain|Series) of (\\d+):");
    private static final Pattern RENEWED_TODAY_PATTERN = Pattern.compile("You've renewed (\\d+)/3 charges today!");
    private static final Pattern BAZAAR_PRICE_PATTERN = Pattern.compile("Bazaar (?:Buy )?Price: ([0-9][0-9,]*(?:\\.[0-9]+)?) Coins");
    /** Real bug found and fixed (2026-09-06) from a real lore dump: the Titanic Experience Bottle's
     *  own purchase-preview tooltip splits "Bazaar Price" (a bare heading, no colon) and its value
     *  ("596,728 Coins", no "Bazaar Price" prefix, and no decimal point that time) onto two SEPARATE
     *  lore lines, not one combined "Bazaar Price: X Coins" line like {@link #BAZAAR_PRICE_PATTERN}
     *  expects - {@link #readBazaarPrice} now also checks for this heading-then-value pair. */
    private static final Pattern BAZAAR_PRICE_HEADING_PATTERN = Pattern.compile("^Bazaar (?:Buy )?Price$");
    private static final Pattern BARE_COINS_PATTERN = Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]+)?) Coins$");
    private static final Pattern XP_COST_PATTERN = Pattern.compile("(\\d+)\\s*XP Levels");
    private static final long MENU_ACTION_DELAY_MS = 1000;
    /** Distinct from -1 ("nothing to do right now") - means the navigator wants to back out right now
     *  (e.g. an unaffordable Titanic bottle purchase) and {@link ExperimentsFeature} should actually
     *  close the screen (through the same jittered {@code scheduleAction} every other action goes
     *  through, per killer560's "make sure random delay is working on everything") and then schedule a
     *  real reopen, same as after claiming rewards. The navigator itself never calls
     *  {@code screen.onClose()} directly - it only signals the intent. */
    static final int CLOSED_SIGNAL = -2;
    /** Distinct from both -1 ("nothing to do right now, keep evaluating") and CLOSED_SIGNAL ("back out
     *  and try again shortly") - means the navigator has determined nothing more is achievable this run
     *  at all, and the WHOLE autonomous run should stop rather than keep evaluating forever with
     *  nothing to show for it. Per killer560's explicit "have it end and send the alert" (2026-09-06):
     *  fires either when the day's Experiments are genuinely exhausted (all three games done AND Renew
     *  Experiments can't be clicked again today for any reason) or when a Titanic Experience Bottle
     *  purchase needed to keep going turns out unaffordable - replacing the previous "keep retrying
     *  every 5 minutes forever" behavior for the latter, and adding a stop for the former case, which
     *  previously just sat idle forever with zero feedback. {@link ExperimentsFeature} is expected to
     *  leave whatever screen is currently open exactly as-is (per killer560's "it should stop on this
     *  screen") and just unarm/reset while showing {@link #takeDoneReason()}'s message. */
    static final int DONE_SIGNAL = -3;

    private boolean chronomatronDoneThisRun = false;
    private boolean ultrasequencerDoneThisRun = false;
    private boolean superpairsDoneThisRun = false;
    private long lastNavClickAtMs = 0;
    private String lastLoggedTitle = null;
    private int pendingRoundsNeeded = -1;
    /** Set when either a better tier is locked behind "Not enough experience!" or Renew Experiments
     *  itself is blocked by insufficient XP - the navigator backs out to go buy a Titanic Experience
     *  Bottle in both cases (per killer560's explicit "make sure it buys titanics during any instance
     *  never grands. All of those should be set up for titanics everything relating to xp") instead of
     *  settling for a worse tier or hammering an unaffordable Renew. Consumed the next time the main
     *  menu is seen, routing straight to the XP-bottle shop before re-attempting anything. See
     *  {@link #nextNavigationClick}. */
    private boolean pendingTitanicPurchase = false;
    /** Real bug found and fixed (2026-09-06) from killer560's own field test: with no cap at all, the
     *  navigator kept re-clicking Titanic Experience Bottle every single evaluation pass for as long
     *  as the Bottles of Enchanting screen stayed open and affordable - killer560: "it sat there and would
     *  have presumably bought infinitely many if i had the money for it." Only ONE purchase is allowed
     *  per visit to this screen; once bought, the navigator immediately backs out (CLOSED_SIGNAL)
     *  instead of buying again. If more XP turns out to be needed later, whichever trigger noticed that
     *  (the opportunistic top-up or the Renew-blocked-by-XP backout) will naturally reopen this screen
     *  and buy exactly one more, repeating as needed - per killer560's "if it goes out and ends up needing
     *  more somehow just have it go back to the exp menu to buy another one and go back out." Reset
     *  every time the screen title changes (see the top of nextNavigationClick), so a fresh visit can
     *  always buy one. */
    private boolean titanicPurchasedThisVisit = false;
    /** The reason the navigator last signaled {@link #DONE_SIGNAL} for - consumed one-shot by
     *  {@link #takeDoneReason()}, same pattern as {@link #takePendingRoundsNeeded()}. */
    private String doneReason;

    /** Consumes and returns the human-readable reason the navigator last signaled {@link #DONE_SIGNAL}
     *  for, or null if none is pending. */
    String takeDoneReason() {
        String value = doneReason;
        doneReason = null;
        return value;
    }

    /** @return a slot to click to advance the autonomous run, or -1 if there's nothing to do right
     *  now (on cooldown, not a screen this navigator understands, or the run is already complete). */
    int nextNavigationClick(ContainerScreen screen, ExperimentSolver.Mode currentPuzzleMode, long now,
            int autoRenewCount, double titanicMaxPriceCoins) {
        String title = screen.getTitle().getString();
        if (!title.equals(lastLoggedTitle)) {
            LOGGER.info("Navigator sees screen \"{}\" (chronomatronDone={}, ultrasequencerDone={}, superpairsDone={})",
                    title, chronomatronDoneThisRun, ultrasequencerDoneThisRun, superpairsDoneThisRun);
            lastLoggedTitle = title;
            // Field-tested (2026-09-06): lastNavClickAtMs started at 0, so the FIRST click the
            // navigator ever made (right after enabling autonomous mode, before any prior click had
            // set a real timestamp) fired instantly instead of waiting the full 1s like every click
            // after it did. Arming the delay the instant a new screen appears - and skipping this
            // tick entirely - means every screen, including the very first one, gets a full 1s
            // before the navigator acts on it.
            lastNavClickAtMs = now;
            titanicPurchasedThisVisit = false;
            return -1;
        }
        if (now - lastNavClickAtMs < MENU_ACTION_DELAY_MS) {
            return -1;
        }

        ChestMenu menu = screen.getMenu();
        int result = -1;
        String reason = null;

        if (title.equals(MAIN_MENU_TITLE)) {
            // A tier picker or Renew Experiments itself just told us something needs more XP than we
            // have - go buy a Titanic Experience Bottle before re-attempting anything, rather than
            // immediately re-hitting the same wall again.
            if (pendingTitanicPurchase && titanicMaxPriceCoins > 0) {
                pendingTitanicPurchase = false;
                Slot bottles = findSlotContaining(menu, "Experience Bottles");
                if (bottles != null) {
                    result = bottles.index;
                    reason = "open Bottles of Enchanting (need more XP)";
                }
            }

            // Field-tested (2026-09-06): these two flags used to be set true once and never
            // re-checked, so reopening the table after a completed run stayed permanently "done"
            // even once Hypixel's own UI said otherwise (a Superpairs charge's "Play add-ons
            // first!" tooltip implies the add-ons ARE available again) - the navigator just sat
            // idle forever instead of retrying. Every visit to the main menu now re-derives both
            // flags fresh from each entry's own live cooldown lore, exactly like the cooldown
            // check already does, instead of trusting a stale in-memory flag.
            if (result < 0) {
                Slot chrono = findSlotContaining(menu, "Chronomatron");
                boolean chronoAvailable = chrono != null && !isMainMenuEntryUnavailable(chrono.getItem());
                if (chronomatronDoneThisRun && chronoAvailable) {
                    LOGGER.info("Chronomatron is available again - retrying it");
                }
                chronomatronDoneThisRun = !chronoAvailable;
                if (chronoAvailable) {
                    result = chrono.index;
                    reason = "Chronomatron entry";
                }
            }

            if (result < 0) {
                Slot ultra = findSlotContaining(menu, "Ultrasequencer");
                boolean ultraAvailable = ultra != null && !isMainMenuEntryUnavailable(ultra.getItem());
                if (ultrasequencerDoneThisRun && ultraAvailable) {
                    LOGGER.info("Ultrasequencer is available again - retrying it");
                }
                ultrasequencerDoneThisRun = !ultraAvailable;
                if (ultraAvailable) {
                    result = ultra.index;
                    reason = "Ultrasequencer entry";
                }
            }

            // Field-tested (2026-09-06): Superpairs was never actually wired into main-menu
            // navigation at all - only Chronomatron/Ultrasequencer entries were ever searched for -
            // so once both finished, the navigator had nothing left to click even though Superpairs
            // was available. Same live-recheck pattern as the other two, including the real "Play
            // add-ons first!" lock text (distinct from "cooldown") shown when Superpairs has a charge
            // but the add-ons need to be replayed before it - see isMainMenuEntryUnavailable.
            if (result < 0) {
                Slot pairs = findSlotContaining(menu, "Superpairs");
                boolean pairsAvailable = pairs != null && !isMainMenuEntryUnavailable(pairs.getItem());
                if (superpairsDoneThisRun && pairsAvailable) {
                    LOGGER.info("Superpairs is available again - retrying it");
                }
                superpairsDoneThisRun = !pairsAvailable;
                if (pairsAvailable) {
                    result = pairs.index;
                    reason = "Superpairs entry";
                }
            }

            // Real bug found and fixed (2026-09-06), from real screenshots: this used to run BEFORE
            // Renew Experiments below, so whenever a Titanic purchase was unaffordable, the
            // opportunistic top-up kept re-opening Bottles of Enchanting on literally every main-menu
            // visit forever (open -> too expensive -> back out -> reopen -> ...), permanently starving
            // Renew of ever getting a turn and never actually gaining any XP - matching killer560's
            // report of "repeatedly clicking on refresh without actually getting new xp." Renew now
            // goes first (it's a genuinely limited daily resource worth prioritizing). An unaffordable
            // Titanic bottle now stops the whole run outright (see DONE_SIGNAL) rather than needing a
            // backoff timer to avoid retrying too often.
            if (result < 0 && autoRenewCount > 0) {
                Slot renew = findSlotContaining(menu, "Renew Experiments");
                if (renew != null) {
                    int renewedToday = readRenewedToday(renew.getItem());
                    if (renewedToday < autoRenewCount) {
                        // Real bug found and fixed (2026-09-06), from a real screenshot: killer560's own
                        // chat was being spammed with Hypixel's real "You cannot afford to reset your
                        // experiments!" message, once per second, forever - this used to click Renew
                        // unconditionally whenever a charge was still available today, with no check
                        // that it was actually affordable first.
                        //
                        // Real SECOND bug found and fixed (2026-09-06), from a real log: the original
                        // fix only skipped clicking when isRenewBlockedByXp() specifically confirmed an
                        // XP shortfall (needs a "Your XP: N" lore line) - but the real lore doesn't
                        // always include that line (confirmed from the log: "Costs / 50 XP Levels /
                        // 150 Bits / Cannot afford this!" with NO "Your XP" line at all that time), so
                        // isRenewBlockedByXp() returned false and Renew got clicked anyway, right back
                        // to the original bug. Cannot afford this! is now checked directly and ALWAYS
                        // blocks the click regardless of whether the finer XP-vs-bits cause can be
                        // pinned down - isRenewBlockedByXp() now only decides whether it's ALSO worth
                        // trying to fix by buying a Titanic bottle specifically.
                        if (cannotAffordRenew(renew.getItem())) {
                            if (isRenewBlockedByXp(renew.getItem()) && titanicMaxPriceCoins > 0) {
                                // Real bug found and fixed (2026-09-06), per killer560's exact report:
                                // this used to set pendingTitanicPurchase and return CLOSED_SIGNAL,
                                // closing the table just to immediately reopen it a second later and
                                // click the SAME menu's "Experience Bottles" entry - a pointless close/
                                // reopen round trip when we're already looking right at the main menu
                                // with menu already in hand. Just find and click it directly, same tick.
                                Slot bottles = findSlotContaining(menu, "Experience Bottles");
                                if (bottles != null) {
                                    LOGGER.info("Renew Experiments is blocked by insufficient XP - opening Bottles of Enchanting directly (same menu)");
                                    result = bottles.index;
                                    reason = "open Bottles of Enchanting (need more XP)";
                                } else {
                                    LOGGER.warn("Renew Experiments is blocked by insufficient XP but no \"Experience Bottles\" entry is showing right now");
                                }
                            } else {
                                LOGGER.info("Renew Experiments can't be afforded right now - skipping it for now");
                            }
                        } else {
                            result = renew.index;
                            reason = "renew charge (" + renewedToday + "/" + autoRenewCount + " today)";
                        }
                    }
                }
            }

            // Real feature added (2026-09-06), per killer560's explicit "it should stop on this screen and
            // flash an alert... to tell me that it is done for the day" - previously, once all three
            // games were done and Renew Experiments was exhausted (his own configured daily cap
            // reached, in his exact field test), the navigator just sat there silently re-evaluating
            // once a second forever with nothing to show for it. Checked BEFORE the opportunistic
            // top-up below: once every game is done for the day AND Renew genuinely can't be clicked
            // again (see renewExhaustedReason), buying more XP has nothing left to spend it on either,
            // so the whole run stops here instead of continuing to top up pointlessly.
            boolean stoppedForToday = false;
            if (result < 0 && chronomatronDoneThisRun && ultrasequencerDoneThisRun && superpairsDoneThisRun) {
                String exhaustedReason = renewExhaustedReason(menu, autoRenewCount, titanicMaxPriceCoins);
                if (exhaustedReason != null) {
                    LOGGER.info("Autonomous run has nothing left to do today: {}", exhaustedReason);
                    doneReason = "All Experiments finished for today - " + exhaustedReason;
                    result = DONE_SIGNAL;
                    stoppedForToday = true;
                }
            }

            // Nothing left to play (or renew) right now - opportunistically top up resources, per
            // killer560's request. The same "$" icon shows either an "Experience Bottles" or "Renew
            // Experiments" tooltip depending on Hypixel's own current state, so both are just name
            // searches on whatever's actually showing.
            if (!stoppedForToday && result < 0 && titanicMaxPriceCoins > 0) {
                Slot bottles = findSlotContaining(menu, "Experience Bottles");
                if (bottles != null) {
                    result = bottles.index;
                    reason = "open Bottles of Enchanting";
                }
            }
        } else if (title.equals(BOTTLES_TITLE)) {
            // Field-tested (2026-09-06): reached whenever the main-menu branch above opened this
            // screen looking for XP. Real per-item lore: "Bazaar Price: X Coins" / "Bazaar Buy
            // Price: X Coins" (comma-formatted), and clicking the item both previews AND completes
            // the instant-buy, same "hover shows cost, click confirms" pattern as everything else in
            // this table - no separate confirmation screen. Always Titanic specifically, per killer560's
            // explicit "make sure it buys titanics during any instance never grands" - every XP-bottle
            // purchase this navigator ever makes, for any reason, targets this one item.
            if (titanicPurchasedThisVisit) {
                LOGGER.info("Already bought one Titanic Experience Bottle this visit - backing out (capped at 1 per trip)");
                return CLOSED_SIGNAL;
            }
            if (titanicMaxPriceCoins > 0) {
                Slot titanic = findSlotContaining(menu, "Titanic Experience Bottle");
                if (titanic == null) {
                    LOGGER.warn("No \"Titanic Experience Bottle\" item found at all in Bottles of Enchanting");
                } else {
                    ItemLore lore = titanic.getItem().get(DataComponents.LORE);
                    List<String> loreLines = new ArrayList<>();
                    if (lore != null) {
                        for (Component line : lore.lines()) {
                            loreLines.add(line.getString());
                        }
                    }
                    LOGGER.info("Titanic Experience Bottle lore={}", loreLines);
                }
                Double price = titanic != null ? readBazaarPrice(titanic.getItem()) : null;
                // Real bug found and fixed (2026-09-06) from a real log: the navigator only ever
                // compared the Bazaar price against killer560's own configured budget, never checking
                // whether the purchase would actually SUCCEED - when his real Bazaar coin balance was
                // genuinely short (a totally different problem from "too expensive by my own
                // preference"), the lore's last line reads "You don't have enough Coins!" instead of
                // "Click to buy now!", but the code still clicked buy anyway. The click silently failed
                // server-side (no "[Bazaar] Bought..." chat line), yet titanicPurchasedThisVisit still
                // got set as if it succeeded - so the navigator backed out via CLOSED_SIGNAL (not
                // DONE_SIGNAL) and the SAME tier/Renew trigger fired again next cycle, reopening this
                // exact screen forever. Matches killer560's exact report: "it did close out of the menu but
                // then it just reopened it. it never actually stopped the entire macro." Same lesson as
                // cannotAffordRenew: a hard, unconditional real-affordability gate beats trusting a
                // price comparison alone.
                boolean insufficientRealCoins = titanic != null && hasInsufficientRealCoins(titanic.getItem());
                if (titanic != null && price != null && price <= titanicMaxPriceCoins && !insufficientRealCoins) {
                    result = titanic.index;
                    reason = "buy Titanic XP bottle (" + price + " coins)";
                    titanicPurchasedThisVisit = true;
                } else {
                    // Real bug found and fixed (2026-09-06), per killer560's exact report: this used to
                    // back out and retry every 5 minutes forever ("going to the main screen and back
                    // into the exp bottle thing again and again") - now it just stops the whole run
                    // here instead, on this same screen, and tells him why.
                    String priceText;
                    if (titanic == null) {
                        priceText = "no Titanic Experience Bottle was showing";
                    } else if (insufficientRealCoins) {
                        priceText = "your real Bazaar coin balance is short of the " + price + "-coin price";
                    } else if (price == null) {
                        priceText = "its price couldn't be read";
                    } else {
                        priceText = price + " coins, over your " + titanicMaxPriceCoins + "-coin budget";
                    }
                    LOGGER.info("Titanic Experience Bottle unaffordable ({}) - stopping the run instead of retrying", priceText);
                    doneReason = "Titanic Experience Bottle is unaffordable (" + priceText + ")";
                    return DONE_SIGNAL;
                }
            } else {
                return CLOSED_SIGNAL;
            }
        } else {
            // Not the main menu - remember which addon game we've actually reached, so once we're
            // back at the main menu we move on to the next one instead of re-entering the same one.
            if (currentPuzzleMode == ExperimentSolver.Mode.CHRONOMATRON) {
                chronomatronDoneThisRun = true;
            } else if (currentPuzzleMode == ExperimentSolver.Mode.ULTRASEQUENCER) {
                ultrasequencerDoneThisRun = true;
            } else if (currentPuzzleMode == ExperimentSolver.Mode.SUPERPAIRS) {
                superpairsDoneThisRun = true;
            }
            boolean midPuzzle = currentPuzzleMode != ExperimentSolver.Mode.NONE;
            boolean runComplete = chronomatronDoneThisRun && ultrasequencerDoneThisRun && superpairsDoneThisRun;
            // Only actually a tier/stakes picker for one of our three games - excludes "Experiment
            // Over" and anything else that can reach this generic branch, same spirit as the
            // screen-title gate ExperimentsFeature applies before calling this method at all.
            boolean looksLikeGameScreen = title.contains("Chronomatron") || title.contains("Ultrasequencer")
                    || title.startsWith("Superpairs");
            if (!midPuzzle && !runComplete && looksLikeGameScreen) {
                TierScan scan = scanTiers(menu);
                if (scan.betterLockedByExperience() && titanicMaxPriceCoins > 0) {
                    LOGGER.info("A better tier on \"{}\" is locked by \"Not enough experience!\" - backing "
                            + "out to buy a Titanic Experience Bottle instead of settling for a worse tier", title);
                    pendingTitanicPurchase = true;
                    return CLOSED_SIGNAL;
                }
                if (scan.bestSlot() >= 0) {
                    pendingRoundsNeeded = readMaxClickBonusRounds(scan.bestStack());
                    if (pendingRoundsNeeded > 0) {
                        LOGGER.info("Tier at slot {} reaches max Superpairs-click bonus at round {}", scan.bestSlot(), pendingRoundsNeeded);
                    }
                    result = scan.bestSlot();
                    reason = "tier pick";
                }
            }
        }

        if (result >= 0) {
            LOGGER.info("Navigator clicking slot {} ({}) on screen \"{}\"", result, reason, title);
        }
        // Real bug found and fixed (2026-09-06), from a real log showing dozens of full
        // re-evaluations (menu scans, regex matching, logging) within a two-second window instead of
        // the intended one per second - killer560 reported this as the game "freezing." lastNavClickAtMs
        // used to only be refreshed when an actual click happened (result >= 0); once nothing was
        // clickable (e.g. games on cooldown AND Renew unaffordable), it never advanced again, so
        // "now - lastNavClickAtMs < MENU_ACTION_DELAY_MS" at the top of this method eventually always
        // evaluated false (now keeps growing, lastNavClickAtMs doesn't) and the FULL evaluation ran on
        // every single client tick (20/sec) forever instead of throttling to once a second. Now
        // refreshed unconditionally at the end of every completed evaluation pass, click or not, so
        // the 1s pacing gate actually holds even when there's nothing to do.
        lastNavClickAtMs = now;
        return result;
    }

    void reset() {
        chronomatronDoneThisRun = false;
        ultrasequencerDoneThisRun = false;
        superpairsDoneThisRun = false;
        lastLoggedTitle = null;
        pendingRoundsNeeded = -1;
        pendingTitanicPurchase = false;
        titanicPurchasedThisVisit = false;
        doneReason = null;
    }

    /** @return the round at which the max Superpairs-click bonus is reached, per the chosen tier's
     *  own "Chain of N:"/"Series of N:" lore (real Hypixel text, per SkyHanni), consuming it so it's
     *  only applied to the very next game that starts - or -1 if none has been seen. */
    int takePendingRoundsNeeded() {
        int value = pendingRoundsNeeded;
        pendingRoundsNeeded = -1;
        return value;
    }

    private Slot findSlotContaining(ChestMenu menu, String needle) {
        int containerSlotCount = topContainerSlotCount(menu);
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getHoverName().getString().contains(needle)) {
                return slot;
            }
        }
        return null;
    }

    /** A {@code ChestMenu} always appends the full 36-slot player inventory (27 main + 9 hotbar)
     *  after its own container grid - field-tested (2026-09-06): without excluding those trailing
     *  slots, a tier/stakes search could match an unrelated item sitting in killer560's own inventory
     *  (whichever slot happened to score "rightmost") and click that instead of the real in-menu
     *  item, which does nothing and leaves the navigator stuck clicking the same dead slot forever. */
    private static int topContainerSlotCount(ChestMenu menu) {
        return menu.slots.size() - 36;
    }

    /** Real Hypixel tooltip text shown on a main-menu entry when it can't be played right now:
     *  "On cooldown!" (already played today, even from OUTSIDE the mod) or, for Superpairs
     *  specifically, "Play add-ons first!" (a charge is available but Chronomatron/Ultrasequencer
     *  need to be replayed before it can be used) - both confirmed from real screenshots. */
    private boolean isMainMenuEntryUnavailable(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = line.getString().toLowerCase(Locale.US);
            if (text.contains("cooldown") || text.contains("add-ons first") || text.contains("addons first")) {
                return true;
            }
        }
        return false;
    }

    /** @param bestSlot the best UNLOCKED tier's slot (-1 if none), {@code bestStack} its item (null if
     *  none), and {@code betterLockedByExperience} whether some tier ranked better than {@code bestSlot}
     *  exists but is locked specifically by "Not enough experience!" (as opposed to a real Enchanting-
     *  level gate or "Practice mode has no rewards", neither of which a Titanic bottle purchase fixes). */
    private record TierScan(int bestSlot, ItemStack bestStack, boolean betterLockedByExperience) {
    }

    /** Field-tested (2026-09-06): picking "whichever non-locked tier item has the highest column"
     *  broke on Superpairs specifically, which - unlike Chronomatron/Ultrasequencer's single-row
     *  layout - lays all 6 tiers out across TWO rows (per killer560: reads top-left to top-right, wraps
     *  to bottom-left, continues right, highest tier first). Comparing raw column numbers across
     *  different rows doesn't reflect tier rank at all, which is how "Grand" beat the real best tier.
     *  Ranking by the tier's own NAME against {@link #TIER_NAMES} (already ordered highest to lowest)
     *  sidesteps needing to know the grid's exact row/column geometry altogether - it works regardless
     *  of how many rows a given game happens to lay tiers out in. */
    private TierScan scanTiers(ChestMenu menu) {
        int containerSlotCount = topContainerSlotCount(menu);
        int bestSlot = -1;
        int bestRank = Integer.MAX_VALUE;
        ItemStack bestStack = null;
        int bestExperienceLockedRank = Integer.MAX_VALUE;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            int rank = tierRank(name);
            if (rank < 0) continue;
            if (isLockedForExperience(stack)) {
                if (rank < bestExperienceLockedRank) {
                    bestExperienceLockedRank = rank;
                }
                continue;
            }
            if (isLocked(stack)) continue;
            if (rank < bestRank) {
                bestRank = rank;
                bestSlot = slot.index;
                bestStack = stack;
            }
        }
        return new TierScan(bestSlot, bestStack, bestExperienceLockedRank < bestRank);
    }

    /** @return the item's rank against {@link #TIER_NAMES} (0 = Metaphysical, the best) or -1 if the
     *  name doesn't match any known tier at all. */
    private static int tierRank(String name) {
        for (int i = 0; i < TIER_NAMES.size(); i++) {
            if (name.contains(TIER_NAMES.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Real mechanism, ported from SkyHanni's {@code SuperpairsClicksAlert.kt} (misleadingly named -
     *  its own config description says it's for "Chronomatron or Ultrasequencer"): every tier's own
     *  tooltip lists "Series of N: +M Clicks" lines, one per Superpairs-click bonus tier (e.g. for
     *  Metaphysical: Series of 2/4/6) - these are NOT the puzzle's own completion target (there isn't
     *  one; it plays out to a random natural end with no advance signal, confirmed from SkyHanni's own
     *  source, which doesn't try to predict it either). The LAST (highest) "Chain of N:"/"Series of
     *  N:" line is the round at which the max extra Superpairs-click bonus has been earned - past that
     *  point, further rounds only add proportional Enchanting XP with no more Superpairs benefit,
     *  which is the real "max click bonus" killer560 originally asked to auto-exit at. */
    private int readMaxClickBonusRounds(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return -1;
        int last = -1;
        for (Component line : lore.lines()) {
            Matcher m = ROUNDS_NEEDED_PATTERN.matcher(line.getString());
            if (m.find()) {
                last = Integer.parseInt(m.group(1));
            }
        }
        return last;
    }

    /** @return a human-readable reason Renew Experiments won't be clickable again this run, or null if
     *  it might still be clickable later (real charges remain under killer560's own configured cap, and
     *  isn't blocked by something only a Titanic purchase can't fix - or IS blocked by XP but a Titanic
     *  purchase is enabled and hasn't been ruled out yet). Only ever consulted once all three games are
     *  already done for the day (see the main-menu branch) - used to decide whether the whole
     *  autonomous run has genuinely nothing left to do, per killer560's explicit "it should stop... and
     *  flash an alert... to tell me that it is done for the day." */
    private String renewExhaustedReason(ChestMenu menu, int autoRenewCount, double titanicMaxPriceCoins) {
        if (autoRenewCount <= 0) {
            return "Renew Experiments is disabled in settings";
        }
        Slot renew = findSlotContaining(menu, "Renew Experiments");
        if (renew == null) {
            return "no more Renew Experiments charges are available today";
        }
        int renewedToday = readRenewedToday(renew.getItem());
        if (renewedToday >= autoRenewCount) {
            return "used all " + autoRenewCount + " configured Renew Experiments charge(s) today ("
                    + renewedToday + "/" + autoRenewCount + ")";
        }
        if (cannotAffordRenew(renew.getItem())) {
            if (!isRenewBlockedByXp(renew.getItem())) {
                return "Renew Experiments is blocked by insufficient Bits, which can't be auto-purchased";
            }
            if (titanicMaxPriceCoins <= 0) {
                return "Renew Experiments needs more XP, but Titanic Experience Bottle purchasing is disabled";
            }
        }
        return null;
    }

    /** @return how many of the real "You've renewed N/3 charges today!" purchases have already been
     *  made, or 0 if the line isn't found (e.g. the very first renew of the day). */
    private int readRenewedToday(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            Matcher m = RENEWED_TODAY_PATTERN.matcher(line.getString());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    /** @return whether {@code stack} (the Titanic Experience Bottle) currently shows the real "You
     *  don't have enough Coins!" lore line - Hypixel's own hard, unconditional signal that the purchase
     *  would actually fail right now even though the Bazaar price itself is within killer560's configured
     *  budget (a real Bazaar-balance shortfall, not a "too expensive by my own preference" situation).
     *  Checked unconditionally, same discipline as {@link #cannotAffordRenew} - the price comparison
     *  alone isn't enough. */
    private boolean hasInsufficientRealCoins(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains("You don't have enough Coins!")) return true;
        }
        return false;
    }

    /** @return the real Bazaar price parsed off a "Bazaar Price: X Coins"/"Bazaar Buy Price: X
     *  Coins" lore line (comma-formatted, e.g. "596,736.0"), or null if not found. */
    private Double readBazaarPrice(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;
        List<String> lines = new ArrayList<>();
        for (Component line : lore.lines()) {
            lines.add(line.getString());
        }
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            Matcher combined = BAZAAR_PRICE_PATTERN.matcher(text);
            if (combined.find()) {
                return Double.parseDouble(combined.group(1).replace(",", ""));
            }
            // Real format confirmed 2026-09-06 (see BAZAAR_PRICE_HEADING_PATTERN's doc): "Bazaar
            // Price" as a bare heading line, its value on the very next line.
            if (BAZAAR_PRICE_HEADING_PATTERN.matcher(text.trim()).matches() && i + 1 < lines.size()) {
                Matcher bare = BARE_COINS_PATTERN.matcher(lines.get(i + 1).trim());
                if (bare.find()) {
                    return Double.parseDouble(bare.group(1).replace(",", ""));
                }
            }
        }
        return null;
    }

    private boolean isLocked(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = line.getString();
            for (String marker : LOCKED_LORE_MARKERS) {
                if (text.contains(marker)) return true;
            }
        }
        return false;
    }

    /** Narrower than {@link #isLocked} - only true for the specific "Not enough experience!" lock
     *  reason, the one a Titanic Experience Bottle purchase can actually fix (unlike a real Enchanting-
     *  level gate or "Practice mode has no rewards", which buying more experience doesn't unlock). */
    private boolean isLockedForExperience(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains("Not enough experience!")) return true;
        }
        return false;
    }

    /** @return whether {@code stack} (the Renew Experiments item), already confirmed unaffordable via
     *  {@link #cannotAffordRenew}, is SPECIFICALLY unaffordable because of insufficient XP levels (as
     *  opposed to bits) - i.e. worth fixing by buying a Titanic Experience Bottle, per killer560's
     *  explicit "it needs a way to detect whether its bits or xp." Real bug found and fixed
     *  (2026-09-06): originally tried to read killer560's CURRENT xp level from a "Your XP: M" lore line,
     *  but that line isn't always present in the real tooltip (confirmed from a real log: "Costs / 50
     *  XP Levels / 150 Bits / Cannot afford this!" with no "Your XP" line at all that time) - there's
     *  no need to parse it from lore at all, since the real number is directly available from the
     *  game client itself: {@code Player.experienceLevel} (verified via javap - a plain public int on
     *  the real class, exactly what Hypixel's own "Your XP" line was just echoing back). Only the
     *  required cost ("N XP Levels") still needs to come from the item's own lore, since that's
     *  specific to this particular purchase. */
    private boolean isRenewBlockedByXp(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        Integer requiredXp = null;
        for (Component line : lore.lines()) {
            Matcher req = XP_COST_PATTERN.matcher(line.getString());
            if (req.find()) {
                requiredXp = Integer.parseInt(req.group(1));
                break;
            }
        }
        if (requiredXp == null) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        boolean blocked = player.experienceLevel < requiredXp;
        LOGGER.info("isRenewBlockedByXp: requiredXp={} yourRealXpLevel={} blocked={}",
                requiredXp, player.experienceLevel, blocked);
        return blocked;
    }

    /** @return whether {@code stack} (the Renew Experiments item) currently shows the real "Cannot
     *  afford this!" lore line - checked unconditionally, regardless of whether the specific cause
     *  (XP vs bits) can be determined, so Renew is never clicked while genuinely unaffordable for any
     *  reason. */
    private boolean cannotAffordRenew(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        List<String> loreLines = new ArrayList<>();
        boolean cannotAfford = false;
        for (Component line : lore.lines()) {
            String text = line.getString();
            loreLines.add(text);
            if (text.contains("Cannot afford this!")) {
                cannotAfford = true;
            }
        }
        if (cannotAfford) {
            LOGGER.info("cannotAffordRenew: lore={} -> cannotAfford=true (Renew will not be clicked)", loreLines);
        }
        return cannotAfford;
    }
}
