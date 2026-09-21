package com.killer560.hub.cheatutils;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

/**
 * Auto GFS - refills sack items with {@code /gfs <sack_id> <amount>} while in a dungeon. Command syntax is
 * identical in both references: NoammAddons {@code AutoGFS.kt} ({@code "gfs $id $count"}, e.g.
 * {@code gfs ender_pearl 12}) and QUOI {@code PlayerUtils.fillItemFromSack} ({@code "gfs $sackName $missing"}).
 * <ul>
 * <li>Counts inventory stacks by Skyblock id (ENDER_PEARL / SPIRIT_LEAP / SUPERBOOM_TNT / INFLATABLE_JERRY,
 * max 16/16/64/64 - both refs).
 * <li>Refills when count &lt; threshold% of max (QUOI "Amount" mode), pulling {@code max - count} (both refs).
 * Noamm's {@code needed >= 4} floor and {@code current == 0 -> skip} are kept (the latter as a toggle).
 * <li>Checked every N seconds (Noamm "Check Delay", default 20s); commands spaced &gt;= 3s apart (Noamm's
 * {@code sendCommand(..., 3000)} queue spacing). Never with a screen open (both refs).
 * <li>"You have no X in your Sacks!" (QUOI) marks that item empty until world change.
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
        String gate = !cfg.isAutoGfsEnabled() ? "disabled"
                : client.player == null ? "no-player"
                : !CheatUtils.isOnDungeonServer(client) ? "not on hypixel/p3sim"
                : !DungeonState.isInDungeon() ? "not in dungeon"
                : com.killer560.hub.util.ActionGate.containerScreenOpen(client) ? "a container screen is open" : null;
        String gateLog = gate == null ? "active" : gate;
        if (!gateLog.equals(lastGate)) {
            lastGate = gateLog;
            CheatUtils.LOGGER.info("[CheatUtils] AutoGFS state: {}", gateLog);
        }
        if (gate != null) {
            if (!"screen open".equals(gate)) {
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
