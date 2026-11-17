package com.killer560.hub.invsort;

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

    private static final int MAX_TRIES = 3;

    private static InvSortSacks active;

    private record Ask(String id, int amount) {
    }

    private final Deque<Ask> todo = new ArrayDeque<>();
    private final List<String> missing = new ArrayList<>();
    private Ask sent;
    private int movedNow = -1;
    private boolean noneNow;
    private boolean cooldownNow;
    private int tries;
    private boolean fullNow;
    private boolean full;
    private int fetched;

    /** @param shortfall item id to how many the layout still lacks after the storage visits (wanted minus held) */
    InvSortSacks(Map<String, Integer> shortfall) {
        for (Map.Entry<String, Integer> e : shortfall.entrySet()) {
            if (PLAIN_ID.matcher(e.getKey()).matches()) {
                todo.add(new Ask(e.getKey(), e.getValue()));
            } else {
                missing.add(e.getKey());
            }
        }
        active = this;
    }

    /**
     * What the layout still lacks: per item id, the amounts its slots want (the amount saved with the layout, 1 for a
     * layout saved before amounts were kept) minus what the inventory holds now, storage's items included. Items the
     * storage covered in full are absent, one it covered partly keeps only the shortfall.
     */
    static Map<String, Integer> shortfall(InventoryLayout layout, net.minecraft.world.entity.player.Inventory inv) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        lacking(layout, inv).forEach((key, n) -> out.merge(InvSortMatch.id(key).toUpperCase(Locale.ROOT), n, Integer::sum));
        return out;
    }

    /** Per want key (exact or id only): how many the layout wants minus what the inventory holds; short keys only. */
    static Map<String, Integer> lacking(InventoryLayout layout, net.minecraft.world.entity.player.Inventory inv) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        Map<String, Integer> wanted = new java.util.LinkedHashMap<>();
        layout.wants().forEach((slot, key) -> wanted.merge(key, layout.counts().getOrDefault(slot, 1), Integer::sum));
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            int held = 0;
            for (int i = 0; i < 36; i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty() && InvSortMatch.matches(stack, w.getKey())) {
                    held += stack.getCount();
                }
            }
            if (w.getValue() > held) {
                out.put(w.getKey(), w.getValue() - held);
            }
        }
        return out;
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
        if (SackCommands.isCooldown(plain)) {
            cooldownNow = true;
        } else if (m.matches()) {
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
            if (SackCommands.awaiting() && movedNow < 0 && !noneNow && !fullNow && !cooldownNow) {
                return false;
            }
            if (cooldownNow && movedNow < 0 && !noneNow && !fullNow && tries < MAX_TRIES) {
                // Hypixel refused it ("on cooldown"): nothing was looked up, so ask the same thing again after the gap.
                LOGGER.info("[InvSort] /gfs {} {} refused (cooldown), asking again (try {})", sent.id(), sent.amount(), ++tries);
                todo.addFirst(sent);
                sent = null;
                cooldownNow = false;
                return false;
            }
            tries = 0;
            cooldownNow = false;
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
        if (!SackCommands.sendPaced(client, next.id().toLowerCase(Locale.ROOT) + " " + next.amount())) {
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
