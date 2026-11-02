package com.killer560.hub.luck;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kuudra Paid Chests for How Lucky Are You. The chest's open button (a chest item in the "Paid Chest" menu) lists its
 * contents and its key in its lore: "Contents", the items, a blank line, "Cost", the key ("Infernal Kuudra Key"), "Click
 * to open!". A click on that button is a pending claim of the key's tier with those items, counted after 1.5 s unless
 * Hypixel answers "You cannot afford this!" or "Whoa! Slow down there!" - Odin's KuudraTracker reads the same lore and rolls
 * back on the same two lines. A button whose lore says "Already opened!" or "Can't open another chest!" is no claim, and
 * one menu counts once.
 */
final class LuckKuudra {

    private static final Logger LOGGER = ModLog.get("killer560smod-luck");
    static final String TITLE = "Paid Chest";
    private static final long CONFIRM_MS = 1_500;
    private static final Pattern KEY = Pattern.compile("^(?:(Hot|Burning|Fiery|Infernal) )?Kuudra Key$");
    private static final Pattern AMOUNT = Pattern.compile("^(.+?) x[\\d,]{1,9}$");

    private record Pending(String tier, List<String> items, int containerId, long atMs) {
    }

    private static Pending pending;
    private static int lastClaimedContainer = Integer.MIN_VALUE;
    /** Test hook. */
    static volatile String lastDecision = "";

    private LuckKuudra() {
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                try {
                    onClick(cs);
                } catch (RuntimeException e) {
                    LOGGER.error("[Luck] Kuudra click hook failed", e);
                }
                return true;
            });
        });
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(ChatObserver.raw(message))));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LuckKuudra.onTick", mc -> onTick()));
    }

    private static void onClick(AbstractContainerScreen<?> screen) {
        LuckConfig cfg = LuckConfig.getInstance();
        if (!cfg.isEnabled() || !TITLE.equals(DungeonChestValuer.strip(screen.getTitle().getString()).trim())) {
            return;
        }
        Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
        if (hovered == null || hovered.index >= screen.getMenu().slots.size() - 36) {
            return; // not a chest slot
        }
        int id = screen.getMenu().containerId;
        if (id == lastClaimedContainer || (pending != null && pending.containerId() == id)) {
            lastDecision = "this Paid Chest was already counted";
            return;
        }
        Pending p = parse(hovered.getItem(), id);
        if (p != null) {
            pending = p;
            lastDecision = "pending Kuudra " + p.tier() + " claim with " + p.items().size() + " items";
        }
    }

    /** The claim the button {@code stack} would make, or null when it is not a claimable Paid Chest button. */
    static Pending parse(ItemStack stack, int containerId) {
        List<String> lore = DungeonChestValuer.cleanLore(stack);
        String tier = null;
        boolean contents = false;
        List<String> items = new ArrayList<>();
        for (String line : lore) {
            if (line.equals("Already opened!") || line.equals("Can't open another chest!")) {
                return null;
            }
            Matcher key = KEY.matcher(line);
            if (key.matches()) {
                tier = key.group(1) == null ? "Basic" : key.group(1);
                continue;
            }
            if (line.equals("Contents")) {
                contents = true;
                continue;
            }
            if (line.isEmpty() || line.equals("Cost")) {
                contents = false;
                continue;
            }
            if (contents) {
                Matcher a = AMOUNT.matcher(line);
                items.add(a.matches() ? a.group(1).trim() : line);
            }
        }
        return tier == null || items.isEmpty() ? null : new Pending(tier, items, containerId, System.currentTimeMillis());
    }

    private static void onChat(String text) {
        if (pending == null || text == null) {
            return;
        }
        String t = text.trim();
        if (t.equals("You cannot afford this!") || t.equals("Whoa! Slow down there!")) {
            lastDecision = "Kuudra claim rolled back: " + t;
            LOGGER.info("[Luck] Kuudra Paid Chest not opened ({}) - not counted", t);
            pending = null;
        }
    }

    private static void onTick() {
        Pending p = pending;
        if (p == null || System.currentTimeMillis() - p.atMs() < CONFIRM_MS) {
            return;
        }
        pending = null;
        lastClaimedContainer = p.containerId();
        LuckDetector.onClaim("kuudra:" + p.tier(), p.items(), "kuudra");
        lastDecision = "counted Kuudra " + p.tier() + " claim";
    }

    static void resetForTest() {
        pending = null;
        lastClaimedContainer = Integer.MIN_VALUE;
        lastDecision = "";
    }
}
