package com.killer560.hub.auction;

import com.killer560.hub.auction.ah.AhNav;
import com.killer560.hub.auction.ah.AhReskin;
import com.killer560.hub.auction.screen.AuctionHouseScreen;
import com.killer560.hub.bazaar.BazaarReskin;
import com.killer560.hub.bazaar.BazaarScreen;
import com.killer560.hub.bazaar.BazaarView;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;
import java.util.Set;

/**
 * "/bazaar sugar" and "/ah hype" typed while a Bazaar or Auction House menu is open run that menu's own search for the
 * term (killer560, 2026-10-18), instead of going to Hypixel as a command. Two places use it:
 * <ul>
 *   <li>the search box: a box that takes a typed "/bazaar " (or "/ah ") takes the rest as its search text, so the
 *       search runs for the item rather than for the literal command;</li>
 *   <li>the command itself: {@link #allowCommand} and the client command handlers ask {@link #tryInMenu} first, and a
 *       term typed into an open menu is searched there and never sent.</li>
 * </ul>
 * With no menu open {@link #tryInMenu} returns false and the command goes to Hypixel exactly as before. Nothing here
 * clicks a slot, so ActionGate is not involved.
 */
public final class MenuSearch {

    /** Command names (no slash, lower case) that mean the Bazaar. */
    public static final Set<String> BAZAAR_NAMES = Set.of("bazaar", "bz", "hypixelbz");
    /** Command names (no slash, lower case) that mean the Auction House. "/hypixelah" is left out on purpose: it is the
     *  explicit bypass that always reaches Hypixel's own menu. */
    public static final Set<String> AH_NAMES = Set.of("ah");

    private MenuSearch() {
    }

    /**
     * The search term of a command line for one of {@code names}, or null when the line is not such a command with a
     * word after it. Accepts the line with or without its leading slash. An empty term (a trailing space) is returned as "".
     */
    public static String termOf(String text, Set<String> names) {
        if (text == null) {
            return null;
        }
        String s = text.startsWith("/") ? text.substring(1) : text;
        int sp = s.indexOf(' ');
        if (sp < 0 || !names.contains(s.substring(0, sp).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return s.substring(sp + 1).trim();
    }

    /** A search box's text after a typed character or paste: "/bazaar sugar" becomes "sugar"; anything else is kept. */
    public static String boxText(String typed, Set<String> names) {
        if (typed == null || !typed.startsWith("/")) {
            return typed;
        }
        String term = termOf(typed, names);
        return term == null ? typed : term;
    }

    /**
     * Runs the command's search inside the menu on screen, if that menu is a Bazaar or an Auction House and the command
     * carries a term. Returns true when it did, so the caller must not send the command. {@code command} has no slash.
     */
    public static boolean tryInMenu(String command) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = McCompat.screen(mc);
        if (screen == null || command == null) {
            return false;
        }
        String bazaarTerm = termOf(command, BAZAAR_NAMES);
        if (bazaarTerm != null && !bazaarTerm.isBlank()
                && (screen instanceof BazaarScreen || BazaarReskin.isHiding(screen))) {
            BazaarView.searchFromCommand(bazaarTerm);
            return true;
        }
        String ahTerm = termOf(command, AH_NAMES);
        if (ahTerm != null && !ahTerm.isBlank()) {
            if (screen instanceof AuctionHouseScreen browser) {
                browser.searchFromCommand(ahTerm);
                return true;
            }
            if (AhReskin.isHiding(screen)) {
                // A real Hypixel AH page: its search is Hypixel's sign, so the term goes to the mod's own browser,
                // which closes the page the way Escape does and runs the same search.
                AhNav.toBrowser(null, ahTerm, false);
                return true;
            }
        }
        return false;
    }

    /** Fabric's ALLOW_COMMAND hook (the command without its slash): false means the command is not sent. */
    public static boolean allowCommand(String command) {
        return !tryInMenu(command);
    }
}
