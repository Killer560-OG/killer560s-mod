package com.killer560.hub.auction;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.scoreboard.mixin.CustomScoreboardTabOverlayAccessor;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Is the player's Booster Cookie buff active? Read fresh from the tab list each time it is asked (only when
 * {@code /killer560bz}, the browser key or a settings button opens the browser), so it does not depend on another
 * feature having ticked.
 * <p>
 * Formats (read 2026-10-07; colour codes stripped). The tab list is server-only text: no player's chat can write it.
 * <ul>
 *   <li>Footer: a line {@code Cookie Buff}, then the time left on the next line, or, when off, the two lines
 *       {@code Not active! Obtain booster cookies from the community} / {@code shop in the hub.} - SkyHanni
 *       {@code features/misc/compacttablist/TabListReader.kt} (beta) and Skyblocker
 *       {@code skyblock/tabhud/widget/EffectWidget.java} (main), whose footer regex takes the line after
 *       {@code Cookie Buff} and calls it inactive when it starts with "Not", otherwise the time left.</li>
 *   <li>The Cookie Buff tab widget: {@code Cookie Buff: <time>}, {@code INACTIVE} when off, or a line starting
 *       {@code Not active! Obtain booster cookies from the} - NotEnoughUpdates {@code miscfeatures/CookieWarning.java}
 *       (master), which also meets {@code Less than an hour}.</li>
 *   <li>Neither shown (custom tab settings, the tab not sent yet): the expiry the Custom Scoreboard last read from the
 *       SkyBlock Menu or the "You consumed a Booster Cookie!" line ({@code ScoreboardExtraData}), if still in the
 *       future.</li>
 * </ul>
 * The time-left format itself is not in any source, so an active line is anything short that is not "Not active",
 * as Skyblocker reads it. Every line is matched whole and every quantifier is bounded.
 */
public final class BoosterCookie {

    public enum State { ACTIVE, INACTIVE, UNKNOWN }

    static final Pattern HEADER = Pattern.compile("^Cookie Buff$");
    static final Pattern NOT_ACTIVE = Pattern.compile("^Not active!(?: .{0,120})?$");
    /** Time left: "3d 17h 2m", "3 Days, 17 Hours", "Less than an hour" - short, with a digit, or "Less than". */
    static final Pattern TIME_LEFT = Pattern.compile("^(?:.{0,40}\\d.{0,40}|Less than an? [A-Za-z]{1,10})$");
    static final Pattern WIDGET = Pattern.compile("^Cookie Buff: (.{1,64})$");

    private BoosterCookie() {
    }

    public static State state(Minecraft client) {
        try {
            return read(client);
        } catch (RuntimeException | LinkageError e) {
            return State.UNKNOWN;
        }
    }

    private static State read(Minecraft client) {
        if (client == null || client.getConnection() == null || client.gui == null) {
            return State.UNKNOWN;
        }
        PlayerTabOverlay overlay = McCompat.tabList(client);
        String footer = null;
        if (overlay instanceof CustomScoreboardTabOverlayAccessor accessor) {
            Component f = accessor.killer560smod$getFooter();
            footer = f == null ? null : ChatObserver.stripCodes(f.getString());
        }
        State fromFooter = fromFooter(footer);
        if (fromFooter != State.UNKNOWN) {
            return fromFooter;
        }
        List<String> tab = new ArrayList<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String s = ChatObserver.stripCodes(overlay.getNameForDisplay(info).getString());
            if (s != null) {
                tab.add(s);
            }
        }
        State fromTab = fromTabLines(tab);
        if (fromTab != State.UNKNOWN) {
            return fromTab;
        }
        long expires = com.killer560.hub.scoreboard.ScoreboardExtraData.cookieExpiresAtMs();
        return expires > System.currentTimeMillis() ? State.ACTIVE : State.UNKNOWN;
    }

    /** The footer's Cookie Buff block; UNKNOWN when there is none. Public for tests. */
    public static State fromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return State.UNKNOWN;
        }
        String[] lines = footer.split("\n", 200);
        for (int i = 0; i < lines.length; i++) {
            if (!HEADER.matcher(lines[i].trim()).matches()) {
                continue;
            }
            String next = i + 1 < lines.length ? lines[i + 1].trim() : "";
            String after = i + 2 < lines.length ? lines[i + 2].trim() : "";
            if (next.startsWith("Not") && NOT_ACTIVE.matcher((next + " " + after).trim()).matches()) {
                return State.INACTIVE;
            }
            return TIME_LEFT.matcher(next).matches() ? State.ACTIVE : State.UNKNOWN;
        }
        return State.UNKNOWN;
    }

    /** The tab widget's "Cookie Buff: ..." line; UNKNOWN when there is none. Public for tests. */
    public static State fromTabLines(List<String> lines) {
        for (String raw : lines) {
            var m = WIDGET.matcher(raw.trim());
            if (!m.matches()) {
                continue;
            }
            String v = m.group(1).trim();
            if (NOT_ACTIVE.matcher(v).matches() || v.equals("INACTIVE")) {
                return State.INACTIVE;
            }
            if (TIME_LEFT.matcher(v).matches()) {
                return State.ACTIVE;
            }
        }
        return State.UNKNOWN;
    }
}
