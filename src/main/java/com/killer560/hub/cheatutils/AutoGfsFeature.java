package com.killer560.hub.cheatutils;

import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

/**
 * Auto GFS - refills sack items with {@code /gfs <sack_id> <amount>} while on Hypixel/p3sim. Command syntax is
 * identical in both references: NoammAddons {@code AutoGFS.kt} ({@code "gfs $id $count"}, e.g.
 * {@code gfs ender_pearl 12}) and QUOI {@code PlayerUtils.fillItemFromSack} ({@code "gfs $sackName $missing"}).
 * <ul>
 * <li>Counts inventory stacks by Skyblock id (ENDER_PEARL / SPIRIT_LEAP / SUPERBOOM_TNT / INFLATABLE_JERRY,
 * max 16/16/64/64 - both refs).
 * <li>Refills when count &lt; threshold% of max (QUOI "Amount" mode), pulling {@code max - count} (both refs).
 * Noamm's {@code needed >= 4} floor and {@code current == 0 -> skip} are kept (the latter as a toggle).
 * <li>Checked every N seconds (Noamm "Check Delay", default 20s); commands spaced &gt;= 3s apart (Noamm's
 * {@code sendCommand(..., 3000)} queue spacing). Never with a container screen open (both refs).
 * <li>"You have no X in your Sacks!" (QUOI) marks that item empty until world change.
 * <li>killer560 (2026-09-20): "make an option to only work in dungeons/kuudra" - {@code gfsDungeonKuudraOnly}
 * (off by default) gates on {@link ScoreboardData#inIsland}, mirroring Odin's own {@code AutoGFS} which keeps
 * separate {@code inSkyblock}/{@code inKuudra}/{@code inDungeon} toggles rather than one hardcoded location.
 * </ul>
 */
public final class AutoGfsFeature {

    private static final long COMMAND_SPACING_MS = 3000;

    private enum RefillItem {
        PEARL("ENDER_PEARL", "ender_pearl", 16, "Ender Pearls"),
        LEAP("SPIRIT_LEAP", "spirit_leap", 16, "Spirit Leaps"),
        BOOM("SUPERBOOM_TNT", "superboom_tnt", 64, "Superboom TNT"),
        JERRY("INFLATABLE_JERRY", "inflatable_jerry", 64, "Inflatable Jerries");

        final String itemId;
        final String sackName;
        final int maxStack;
        final String emptyName;

        RefillItem(String itemId, String sackName, int maxStack, String emptyName) {
            this.itemId = itemId;
            this.sackName = sackName;
            this.maxStack = maxStack;
            this.emptyName = emptyName;
        }

        boolean enabled(CheatUtilsConfig cfg) {
            return switch (this) {
                case PEARL -> cfg.isGfsPearls();
                case LEAP -> cfg.isGfsLeaps();
                case BOOM -> cfg.isGfsSuperbooms();
                case JERRY -> cfg.isGfsJerries();
            };
        }
    }

    private static final Set<RefillItem> emptySacks = EnumSet.noneOf(RefillItem.class);
    private static final Deque<RefillItem> pending = new ArrayDeque<>();
    private static long lastCheckMs = 0;
    private static long lastCommandMs = 0;
    private static Object lastLevel = null;
    private static String lastGate = null;

    private AutoGfsFeature() {
    }

    static void onChat(String plain) {
        for (RefillItem item : RefillItem.values()) {
            if (plain.equals("You have no " + item.emptyName + " in your Sacks!") && emptySacks.add(item)) {
                pending.remove(item);
                CheatUtils.LOGGER.info("[CheatUtils] AutoGFS sack empty for {} - skipping until world change", item.sackName);
            }
        }
    }

    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            emptySacks.clear();
            pending.clear();
            lastCheckMs = System.currentTimeMillis(); // give the new world one interval before the first check
        }
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        // Real bug found (2026-09-20, killer560: "it isn't doing anything at all"): this was hardcoded to
        // require DungeonState.isInDungeon(), which only matches a real Catacombs sidebar ("The Catacombs
        // (..."). Kuudra never matches that, so Superboom TNT / Inflatable Jerry - the two items this
        // feature exists to refill for Kuudra - could NEVER be topped up there; the log would have sat on
        // "not in dungeon" the entire fight. Replaced with an opt-in restriction (gfsDungeonKuudraOnly,
        // default off) using the mod's general island reader instead of the dungeon-only one, so by default
        // Auto GFS works anywhere on Hypixel/p3sim (matching the master SkyblockGate scope) and killer560
        // can additionally restrict it to Catacombs+Kuudra if he wants that.
        boolean containerOpen = ActionGate.containerScreenOpen(client);
        String gate = !cfg.isAutoGfsEnabled() ? "disabled"
                : client.player == null ? "no-player"
                : !CheatUtils.isOnDungeonServer(client) ? "not on hypixel/p3sim"
                : cfg.isGfsDungeonKuudraOnly() && !ScoreboardData.inIsland("Catacombs", "Kuudra") ? "not in a dungeon or Kuudra"
                : containerOpen ? "a container screen is open" : null;
        String gateLog = gate == null ? "active" : gate;
        if (!gateLog.equals(lastGate)) {
            lastGate = gateLog;
            CheatUtils.LOGGER.info("[CheatUtils] AutoGFS state: {}", gateLog);
        }
        if (gate != null) {
            // Real bug found (2026-09-20): this compared gate to the literal "screen open", which the gate
            // string above never actually produces (it says "a container screen is open") - so the compare
            // always failed and pending was wiped on EVERY gate hit, including a screen that closes a tick
            // later. In a real dungeon that is constantly: opening your own inventory, a chest, the sack
            // menu. Any one of those mid-cycle threw away a queued refill and forced a wait for the next
            // full gfsIntervalSec check (up to 20s) before it could even be reconsidered - which is very
            // plausibly why this "did nothing" in practice. Fixed to key off the actual boolean.
            if (!containerOpen) {
                pending.clear();
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (pending.isEmpty() && now - lastCheckMs >= cfg.getGfsIntervalSec() * 1000L) {
            lastCheckMs = now;
            for (RefillItem item : RefillItem.values()) {
                if (item.enabled(cfg) && !emptySacks.contains(item) && needed(client, cfg, item) > 0) {
                    pending.add(item);
                }
            }
        }
        if (pending.isEmpty() || now - lastCommandMs < COMMAND_SPACING_MS) {
            return;
        }
        // Gap found (2026-09-20): ActionGate.Actor.AUTO_GFS already existed (today's mod-wide automated-
        // action gate) but nothing ever called tryAct for it, so this command wasn't actually coordinated
        // with the mod's other auras/solvers - not what was making it "do nothing" (COMMAND has no screen
        // rules, and our own 3s spacing already dwarfs a single tick), but worth wiring up now that every
        // other automated action goes through this gate. A denial here just retries next tick; it can never
        // shrink the >=3s spacing above, only occasionally push a send back by a tick or two.
        if (!ActionGate.tryAct(ActionGate.Actor.AUTO_GFS)) {
            return;
        }
        RefillItem item = pending.poll();
        int amount = needed(client, cfg, item); // re-verify right before sending
        if (amount <= 0) {
            return;
        }
        lastCommandMs = now;
        String command = "gfs " + item.sackName + " " + amount;
        client.player.connection.sendCommand(command);
        CheatUtils.LOGGER.info("[CheatUtils] AutoGFS sent /{} (had {}/{}, threshold {}%)", command,
                count(client, item), item.maxStack, cfg.getGfsThresholdPercent());
        ModChat.send(CheatUtils.CHAT_TAG, ModChat.text("Auto GFS: "), ModChat.value("/" + command));
    }

    /** @return how many to pull, or 0 if no refill is due. */
    private static int needed(Minecraft client, CheatUtilsConfig cfg, RefillItem item) {
        int current = count(client, item);
        if (current == 0 && cfg.isGfsSkipIfNone()) {
            return 0;
        }
        if (current >= (cfg.getGfsThresholdPercent() / 100.0) * item.maxStack) {
            return 0;
        }
        int missing = item.maxStack - current;
        return missing >= 4 ? missing : 0;
    }

    private static int count(Minecraft client, RefillItem item) {
        if (client.player == null) {
            return 0;
        }
        Inventory inv = client.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (item.itemId.equals(CheatUtils.skyblockId(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
