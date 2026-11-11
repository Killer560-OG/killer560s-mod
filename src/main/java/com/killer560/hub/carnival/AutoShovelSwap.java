package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * Auto Shovel Swap (cheat build only, killer560 2026-10-25: "switches to whichever shovel type the next dig needs"):
 * while you play Fruit Digging, puts your Carnival Shovel in the mode the Fruit Digging Solver wants for the next dig
 * (Mines, Anchor or Treasure). A hotbar slot already holding a shovel in that mode is selected, as a number key would;
 * otherwise, holding the Carnival Shovel, it taps Use, which steps the shovel to its next mode (the wiki: "Dowsing Modes
 * can be cycled through by right-clicking"), until the mode is right - at most once per Swap Delay, and it gives up on a
 * pick after three taps that change nothing. After a Rum the shovel's ability is off for the next dig, so it leaves it.
 * One action a tick through {@link ActionGate}. Automation: can get you banned; keep it off on the main server.
 *
 * <p>The mode is read off the held item: its name naming the mode if it does, else its look - an iron shovel Mines, a
 * diamond one Anchor, a golden one Treasure (the wiki's icons). Unverified in game.
 */
public final class AutoShovelSwap {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final String NAME = "Auto Shovel Swap";
    private static long nextTick;
    private static String wantedFor;
    private static int fruitlessTaps;
    private static String modeBeforeTap;
    private static int swaps;

    private AutoShovelSwap() {
    }

    public static void register() {
        CarnivalInput.register();
        int bit = FeatureBits.register(NAME, () -> AutoFruitConfig.swap().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoShovelSwap.tick", bit, AutoShovelSwap::tick));
    }

    /** The dowsing mode a shovel is in ("Mines", "Anchor", "Treasure"), or null when it is not a shovel. */
    static String modeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!path.endsWith("_shovel")) {
            return null;
        }
        String name = ChatObserver.stripCodes(stack.getHoverName().getString());
        if (name != null) {
            for (String m : new String[]{"Mines", "Anchor", "Treasure"}) {
                if (name.contains(m)) {
                    return m;
                }
            }
        }
        return switch (path) {
            case "iron_shovel" -> "Mines";
            case "diamond_shovel" -> "Anchor";
            case "golden_shovel" -> "Treasure";
            default -> null;
        };
    }

    /** Whether the held shovel is in the mode the solver wants (true when it wants none, or after a Rum). */
    static boolean ready(LocalPlayer player) {
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        if (r == null || FruitDigging.lastFruit() == Fruit.RUM) {
            return true;
        }
        return r.shovel().equals(modeOf(player.getMainHandItem()));
    }

    private static void tick(Minecraft mc) {
        AutoFruitConfig cfg = AutoFruitConfig.swap();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !FruitDigging.active()) {
            return;
        }
        CarnivalInput.watchCorrections(NAME);
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        if (r == null || FruitDigging.lastFruit() == Fruit.RUM) {
            return;
        }
        String want = r.shovel();
        String key = r.label() + "/" + want;
        if (!key.equals(wantedFor)) {
            wantedFor = key;
            fruitlessTaps = 0;
        }
        String held = modeOf(player.getMainHandItem());
        if (want.equals(held)) {
            if (modeBeforeTap != null) {
                LOGGER.info("[{}] shovel now in {} mode", NAME, held);
                modeBeforeTap = null;
            }
            return;
        }
        long now = ActionGate.currentTick();
        if (now < nextTick) {
            return;
        }
        // A shovel already in that mode on the hotbar: select it.
        for (int i = 0; i < 9; i++) {
            if (i != player.getInventory().getSelectedSlot() && want.equals(modeOf(player.getInventory().getItem(i)))) {
                if (CarnivalInput.select(mc, player, i)) {
                    swaps++;
                    nextTick = now + ActionGate.delayTicks(cfg.getDelayMs());
                    LOGGER.info("[{}] selected slot {} ({} mode) for {}", NAME, i + 1, want, r.label());
                }
                return;
            }
        }
        if (held == null) {
            return; // not holding a shovel and none on the hotbar in that mode: nothing it can do
        }
        if (modeBeforeTap != null && modeBeforeTap.equals(held)) {
            fruitlessTaps++;
        }
        if (fruitlessTaps >= 3) {
            return;
        }
        if (CarnivalInput.tap(mc.options.keyUse)) {
            swaps++;
            modeBeforeTap = held;
            nextTick = now + ActionGate.delayTicks(cfg.getDelayMs());
            LOGGER.info("[{}] stepping the shovel from {} toward {} for {}", NAME, held, want, r.label());
        }
    }

    /** Harness: hotbar selects and mode taps made. */
    public static int swaps() {
        return swaps;
    }
}
