package com.killer560.hub.invsort;

import com.killer560.hub.bazaarflip.BazaarStackSizes;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.SackCommands;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch Missing From Sacks (invsortsacks-1113, killer560 2026-11-13: "if something is in my sax that it can get have it do that
 * for invsort ... prioritize items in storage first then from sacks"). After the storage visits, every item the layout needs
 * that no storage page held is asked for with {@code /gfs <item_id> <amount>}, one at a time through {@link SackCommands}
 * (Hypixel Skyblock only, waits for the reply, stops on unknown-command). Replies, as Hypixel writes them (26.3 logs):
 * "Moved 33 Superboom TNT from your Sacks to your inventory.", "You have no X in your Sacks!" (Auto GFS), "Your inventory is full!".
 */
final class InvSortSacks {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    private static final Pattern MOVED = Pattern.compile("^Moved ([\\d,]+) .+ from your Sacks to your inventory\\.$");
    private static final Pattern NONE = Pattern.compile("^You have no .+ in your Sacks!$");
    private static final Pattern FULL = Pattern.compile("^Your inventory is full!$");
    /** Only plain item ids go to /gfs: a rune or attribute id never names a sack item. */
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Z0-9_]+");

    private static InvSortSacks active;

    private record Ask(String id, int amount) {
    }

    private final Deque<Ask> todo = new ArrayDeque<>();
    private final List<String> missing = new ArrayList<>();
    private Ask sent;
    private int movedNow = -1;
    private boolean noneNow;
    private boolean fullNow;
    private boolean full;
    private int fetched;

    /** @param notFound item id to how many layout slots of it storage could not fill */
    InvSortSacks(Map<String, Integer> notFound) {
        for (Map.Entry<String, Integer> e : notFound.entrySet()) {
            String id = e.getKey() == null ? "" : e.getKey().toUpperCase(Locale.ROOT);
            if (PLAIN_ID.matcher(id).matches()) {
                todo.add(new Ask(id, Math.max(1, e.getValue()) * BazaarStackSizes.of(id)));
            } else {
                missing.add(e.getKey());
            }
        }
        active = this;
    }

    static void register() {
        ChatObserver.subscribe(m -> {
            InvSortSacks s = active;
            if (s != null && s.sent != null) {
                s.onChat(ChatObserver.strip(m));
            }
        });
    }

    private void onChat(String plain) {
        Matcher m = MOVED.matcher(plain);
        if (m.matches()) {
            movedNow = Integer.parseInt(m.group(1).replace(",", ""));
        } else if (NONE.matcher(plain).matches()) {
            noneNow = true;
        } else if (FULL.matcher(plain).matches()) {
            fullNow = true;
        } else {
            return;
        }
        SackCommands.answered();
    }

    /** Items found in neither storage nor the sacks. */
    List<String> missing() {
        return missing;
    }

    int fetched() {
        return fetched;
    }

    void release() {
        if (active == this) {
            active = null;
        }
    }

    /** One step. @return true when everything was asked (or it had to give up). */
    boolean tick(Minecraft client, boolean mayAct) {
        if (sent != null) {
            if (SackCommands.awaiting() && movedNow < 0 && !noneNow && !fullNow) {
                return false;
            }
            if (movedNow > 0) {
                fetched++;
            } else {
                missing.add(sent.id());
            }
            LOGGER.info("[InvSort] /gfs {} {}: moved {}, none {}, full {}", sent.id(), sent.amount(), movedNow, noneNow, fullNow);
            full |= fullNow;
            sent = null;
            movedNow = -1;
            noneNow = false;
            fullNow = false;
            InventorySorterExecutor.paced();
        }
        if (full) {
            todo.forEach(a -> missing.add(a.id()));
            todo.clear();
        }
        if (todo.isEmpty()) {
            return true;
        }
        if (!mayAct) {
            return false;
        }
        Ask next = todo.peek();
        if (!SackCommands.send(client, next.id().toLowerCase(Locale.ROOT) + " " + next.amount())) {
            if (!SackCommands.available(client)) { // not Skyblock, or an unknown-command reply: nothing more to ask
                todo.forEach(a -> missing.add(a.id()));
                todo.clear();
                return true;
            }
            return false;
        }
        sent = todo.poll();
        return false;
    }
}
