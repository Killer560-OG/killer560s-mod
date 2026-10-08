package com.killer560.hub.bazaar;

import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one container click the Bazaar screen may make that is not itself a press of his: the direct follow-up to one.
 * <p>
 * When he clicks a product (or a bottom-bar button) and no open menu holds it, the screen sends Hypixel's
 * {@code /bz <name>} (or bare {@code /bz}) - the command he would have typed - and ARMS this. When Hypixel's answer
 * arrives and has settled, {@link #decide} names the one slot to click, only if exactly ONE item on the page is that
 * product (or that button); the reskin then sends that one click through the same guarded path as his own presses, and
 * this disarms. It never clicks twice, never on any other page, never without his click just before:
 * <ul>
 *   <li>armed only from his press ({@link BazaarView}), and only with Bazaar Follow-up Click on (default on);</li>
 *   <li>disarmed after its one click, after {@link #TIMEOUT_MS}, on any other press or key of his in the Bazaar screen,
 *       when the screen closes, and when a page arrives that is not the one it waits for (zero or several matches, a
 *       different menu);</li>
 *   <li>the same in the legit and cheat builds (killer560, 2026-10-07: "it's not automation, just a different way of
 *       making the menu").</li>
 * </ul>
 */
public final class BazaarFollowUp {

    /** What the armed follow-up waits for: a product in the search results, or a named button on the main page. */
    public enum Target { PRODUCT, BUTTON }

    record Armed(Target target, String name, String productId, long armedMs) {
    }

    /** A /bz round trip plus the settle window, with room to spare; after this nothing is clicked. */
    public static final long TIMEOUT_MS = 5000L;

    private static Armed armed;
    private static int clicks;
    private static String outcome = "";

    private BazaarFollowUp() {
    }

    static void armProduct(String name, String productId) {
        arm(Target.PRODUCT, name, productId);
    }

    static void armButton(String name) {
        arm(Target.BUTTON, name, null);
    }

    private static void arm(Target target, String name, String productId) {
        if (!BazaarConfig.getInstance().isFollowUpClick()) {
            armed = null;
            outcome = "off";
            return;
        }
        armed = new Armed(target, name, productId, System.currentTimeMillis());
        outcome = "armed " + target + " " + name;
    }

    /** Drops an armed follow-up (no-op when none is armed). */
    static void disarm(String why) {
        if (armed != null) {
            armed = null;
            outcome = "disarmed: " + why;
        }
    }

    /** The armed follow-up, or null; one past its timeout is dropped here. */
    static Armed armed() {
        Armed a = armed;
        if (a != null && System.currentTimeMillis() - a.armedMs() > TIMEOUT_MS) {
            armed = null;
            outcome = "timeout";
            return null;
        }
        return a;
    }

    static boolean isArmed() {
        return armed() != null;
    }

    /** Every client tick: times out, and drops it when no screen is open (he closed the Bazaar). */
    static void tick() {
        if (armed() != null && McCompat.screen(Minecraft.getInstance()) == null) {
            disarm("screen closed");
        }
    }

    /**
     * A Bazaar page has arrived and settled. Returns the one slot to click for the armed follow-up, or -1; whatever it
     * returns, the follow-up is spent unless this page is not yet the one it is waiting for and could still lead there
     * (it never is: every other answer disarms).
     */
    static int decide(BazaarPages.Page page) {
        Armed a = armed();
        if (a == null || page == null) {
            return -1;
        }
        List<BazaarPages.Item> matches = new ArrayList<>();
        if (a.target() == Target.PRODUCT && page.kind() == BazaarPages.Kind.SEARCH) {
            for (BazaarPages.Item it : page.content()) {
                if (sameName(it.name(), a.name())) {
                    matches.add(it);
                }
            }
        } else if (a.target() == Target.BUTTON && page.kind() == BazaarPages.Kind.CATEGORY) {
            for (List<BazaarPages.Item> part : List.of(page.content(), page.nav(), page.sidebar())) {
                for (BazaarPages.Item it : part) {
                    if (sameName(it.name(), a.name())) {
                        matches.add(it);
                    }
                }
            }
        } else {
            disarm("a " + page.kind() + " page arrived instead");
            return -1;
        }
        if (matches.size() != 1) {
            disarm(matches.size() + " matches for " + a.name());
            return -1;
        }
        return matches.get(0).slot();
    }

    /** The reskin sent the follow-up click: count it and disarm for good. */
    static void clicked(int slot, String label) {
        armed = null;
        clicks++;
        outcome = "clicked slot " + slot + " " + label;
    }

    private static boolean sameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return BazaarOrderParser.strip(a).trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** "clicks|armed?|last outcome". */
    public static String stateForTest() {
        return clicks + "|" + (isArmed() ? "armed" : "idle") + "|" + outcome;
    }
}
