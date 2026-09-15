package com.killer560.hub.i4sensors;

import com.killer560.hub.i4sensors.I4SensorsConfig.DeathItem;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sharp Shooter "Auto Mask" - killer560 (2026-09-14): "add an auto swap mask version" + "an order tracker that
 * lets me select whether it goes to phoenix, spirit mask, or bonzo first". Cheat build only
 * ({@link I4SensorsConfig#isAutoMask()}), and only while Auto i4 is on.
 * <p>
 * <b>Timing</b> - NoammAddons AutoI4.kt (origin/26.1.2), counted from Storm's death line: rod 174, mask 244,
 * leap 307. Its {@code when} picks the FIRST matching branch, so with Auto Rod on the mask swaps at 244 and
 * with Auto Rod off (this mod has no rod swap) it swaps at 174. Here both 174 and 244 are swap points: each
 * one swaps to the first item in the chosen Order that hasn't popped (and isn't already equipped), so a pop
 * between 174 and 244 gets the next item on at 244. Like Noamm, a swap point is skipped when the player isn't
 * on the device, and nothing runs after the device completes or past tick 307. A swap point that lands inside
 * the Machine Gun Shortbow's Rapid Fire window waits until it ends (killer560: "not swap until it ends").
 * Ticks are CLIENT ticks since the Storm line ({@link I4SensorsFeature#ticksSinceStormDeath()}) - Noamm counts
 * server ticks; identical without lag.
 * <p>
 * <b>Masks</b> - Noamm PlayerUtils.changeMaskAction/quickSwapAction: send {@code /stats}, wait for the
 * "Stats &amp; Equipment" container, 350ms later left-click the mask (Skyblock id contains BONZO_MASK /
 * SPIRIT_MASK, which also covers STARRED_ variants) in the player-inventory part of the menu, close. 5s timeout.
 * <p>
 * <b>Phoenix</b> - a pet, so {@code /pets}: this mod's own screenshot-confirmed {@code GuardianPetSwapper}
 * flow - title "Pets" or "(N/M) Pets", the pet's lore ends "Left-click to summon!" (inactive) or "Click to
 * despawn!" (already out - never clicked), "Next Page" item for more pages. Noamm has no pet swap.
 * <p>
 * <b>Pops</b> (order advance) - real chat lines from Noamm MaskTimers.kt / Odin (Spirit's also on the wiki):
 * "Your [⚚ ]Bonzo's Mask saved your life!", "Second Wind Activated! Your Spirit Mask saved your life!",
 * "Your Phoenix Pet saved you from certain death!". An item counts as used while inside its cooldown: Bonzo
 * from the worn mask's own "Cooldown: Ns" lore line (Noamm's method, 180s fallback), Spirit 30s (wiki), Phoenix
 * 60s (wiki). Cleared on world change.
 */
public final class I4AutoMask {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoi4");
    private static final String TAG = "[AutoI4]";
    private static final String CHAT = "Sharp Shooter";

    private static final int MASK_TICK_FIRST = 174;
    private static final int MASK_TICK_SECOND = 244;
    private static final int LEAP_TICK = 307;
    private static final long MENU_TIMEOUT_MS = 5000L;
    private static final long MENU_SETTLE_MS = 350L;
    private static final int MAX_PET_PAGES = 6;

    private static final Pattern BONZO_POP = Pattern.compile("^Your (?:.+ )?Bonzo's Mask saved your life!");
    private static final Pattern SPIRIT_POP = Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!");
    private static final Pattern PHOENIX_POP = Pattern.compile("^Your Phoenix Pet saved you from certain death!");
    private static final Pattern LORE_COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s$");
    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");

    private enum Stage { IDLE, AWAIT_EQUIPMENT, AWAIT_PETS }

    private static final Map<DeathItem, Long> usedUntilMs = new EnumMap<>(DeathItem.class);
    private static final Set<DeathItem> missing = EnumSet.noneOf(DeathItem.class);
    private static boolean phoenixConfirmedOut = false;

    private static long timelineStormAtMs = 0L;
    private static boolean firstPointDone = false;
    private static boolean secondPointDone = false;
    private static String deferredPoint = null;
    private static String lastDeferReason = null;

    private static Stage stage = Stage.IDLE;
    private static DeathItem actionItem = null;
    private static long actionStartedMs = 0L;
    private static long menuSeenAtMs = 0L;
    private static int petPagesTried = 0;
    private static String lastForeignTitle = null;
    private static long verifyHelmetAtMs = 0L;
    private static Object lastLevel = null;

    private I4AutoMask() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ChatObserver.subscribe(I4AutoMask::onChat);
    }

    /** A menu swap is in progress - Auto i4 doesn't hotbar-swap meanwhile. */
    static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    // ------------------------------------------------------------------
    // Pops
    // ------------------------------------------------------------------

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        DeathItem popped = BONZO_POP.matcher(plain).find() ? DeathItem.BONZO
                : SPIRIT_POP.matcher(plain).find() ? DeathItem.SPIRIT
                : PHOENIX_POP.matcher(plain).find() ? DeathItem.PHOENIX : null;
        if (popped == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long cooldownMs = switch (popped) {
            case BONZO -> bonzoLoreCooldownMs(client.player);
            case SPIRIT -> 30_000L;
            case PHOENIX -> 60_000L;
        };
        usedUntilMs.put(popped, System.currentTimeMillis() + cooldownMs);
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        DeathItem next = nextInOrder(cfg.getMaskOrder());
        LOGGER.info("{} {} Auto Mask: {} POPPED (\"{}\") - counted as used for {}s. Order {} -> next is {}.", TAG,
                I4SensorsFeature.clock(), popped.label, plain, cooldownMs / 1000, I4SensorsConfig.orderLabel(cfg.getMaskOrder()),
                next == null ? "nothing (all used/missing)" : next.label);
    }

    private static long bonzoLoreCooldownMs(LocalPlayer player) {
        if (player != null) {
            ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
            ItemLore lore = head.get(DataComponents.LORE);
            if (lore != null && I4SensorsFeature.skyblockId(head).contains("BONZO_MASK")) {
                for (Component line : lore.lines()) {
                    Matcher m = LORE_COOLDOWN.matcher(ChatObserver.strip(line).trim());
                    if (m.matches()) {
                        try {
                            return Math.round(Double.parseDouble(m.group(1)) * 1000);
                        } catch (NumberFormatException ignored) {
                            break;
                        }
                    }
                }
            }
        }
        return 180_000L;
    }

    private static DeathItem nextInOrder(List<DeathItem> order) {
        long now = System.currentTimeMillis();
        for (DeathItem item : order) {
            if (usedUntilMs.getOrDefault(item, 0L) > now || missing.contains(item)) {
                continue;
            }
            return item;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Tick - timeline + menu state machine
    // ------------------------------------------------------------------

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            if (lastLevel != null && (!usedUntilMs.isEmpty() || !missing.isEmpty() || stage != Stage.IDLE)) {
                LOGGER.info("{} World changed - Auto Mask pop/missing tracking cleared.", TAG);
            }
            usedUntilMs.clear();
            missing.clear();
            phoenixConfirmedOut = false;
            stage = Stage.IDLE;
            deferredPoint = null;
            lastLevel = client.level;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        if (stage != Stage.IDLE) {
            tickMenu(client, player);
            return;
        }
        if (verifyHelmetAtMs > 0 && System.currentTimeMillis() >= verifyHelmetAtMs) {
            verifyHelmetAtMs = 0L;
            LOGGER.info("{} {} Auto Mask: helmet 1s after the swap click: {}", TAG, I4SensorsFeature.clock(),
                    I4SensorsFeature.itemDesc(player.getItemBySlot(EquipmentSlot.HEAD)));
        }

        long stormAt = I4SensorsFeature.stormDeathAtMs();
        if (stormAt != timelineStormAtMs) {
            timelineStormAtMs = stormAt;
            firstPointDone = false;
            secondPointDone = false;
            deferredPoint = null;
        }
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        int t = I4SensorsFeature.ticksSinceStormDeath();
        if (!cfg.isAutoMask() || !cfg.isAutoI4Enabled() || t < 0 || t >= LEAP_TICK) {
            if (deferredPoint != null) {
                LOGGER.info("{} {} Auto Mask: dropped deferred {} swap point (t={}, autoMask={}).", TAG,
                        I4SensorsFeature.clock(), deferredPoint, t, cfg.isAutoMask() && cfg.isAutoI4Enabled());
                deferredPoint = null;
                lastDeferReason = null;
            }
            return;
        }
        String point = null;
        if (!firstPointDone && t >= MASK_TICK_FIRST) {
            firstPointDone = true;
            point = "tick " + MASK_TICK_FIRST;
        } else if (!secondPointDone && t >= MASK_TICK_SECOND) {
            secondPointDone = true;
            point = "tick " + MASK_TICK_SECOND;
        }
        if (point == null) {
            point = deferredPoint;
        }
        if (point == null) {
            return;
        }
        deferredPoint = null;
        String deferReason = AutoI4Feature.isAbilityHoldActive() ? "Rapid Fire still active"
                : client.screen != null ? "a screen is open (\"" + client.screen.getTitle().getString() + "\")" : null;
        if (deferReason == null) {
            lastDeferReason = null;
        }
        if (!I4SensorsFeature.isOnDevice(player.position())) {
            LOGGER.info("{} {} Auto Mask: {} swap point skipped - not on the device (Noamm does the same).", TAG,
                    I4SensorsFeature.clock(), point);
            return;
        }
        if (AutoI4Feature.isDeviceCompleted()) {
            LOGGER.info("{} {} Auto Mask: {} swap point skipped - device already completed.", TAG, I4SensorsFeature.clock(), point);
            return;
        }
        if (deferReason != null) {
            if (!deferReason.equals(lastDeferReason)) {
                LOGGER.info("{} {} Auto Mask: {} swap point waiting - {}{}.", TAG, I4SensorsFeature.clock(), point,
                        deferReason, AutoI4Feature.isAbilityHoldActive() ? " (" + AutoI4Feature.abilityHoldRemainingMs() + "ms left)" : "");
                lastDeferReason = deferReason;
            }
            deferredPoint = point;
            return;
        }
        startSwap(client, player, cfg, point);
    }

    private static void startSwap(Minecraft client, LocalPlayer player, I4SensorsConfig cfg, String point) {
        List<DeathItem> order = cfg.getMaskOrder();
        DeathItem target = nextInOrder(order);
        String helmet = I4SensorsFeature.skyblockId(player.getItemBySlot(EquipmentSlot.HEAD));
        if (target == null) {
            LOGGER.info("{} {} Auto Mask: {} - every item in order {} is used or missing, nothing to swap to.", TAG,
                    I4SensorsFeature.clock(), point, I4SensorsConfig.orderLabel(order));
            return;
        }
        if ((target == DeathItem.BONZO && helmet.contains("BONZO_MASK"))
                || (target == DeathItem.SPIRIT && helmet.contains("SPIRIT_MASK"))
                || (target == DeathItem.PHOENIX && phoenixConfirmedOut)) {
            LOGGER.info("{} {} Auto Mask: {} - next in order is {}, already {} - no swap.", TAG, I4SensorsFeature.clock(),
                    point, target.label, target == DeathItem.PHOENIX ? "summoned" : "worn");
            return;
        }
        actionItem = target;
        actionStartedMs = System.currentTimeMillis();
        menuSeenAtMs = 0L;
        petPagesTried = 0;
        lastForeignTitle = null;
        if (target == DeathItem.PHOENIX) {
            stage = Stage.AWAIT_PETS;
            player.connection.sendCommand("pets");
        } else {
            stage = Stage.AWAIT_EQUIPMENT;
            player.connection.sendCommand("stats");
        }
        LOGGER.info("{} {} Auto Mask: {} - swapping to {} (order {}, helmet {}) - sent /{}.", TAG, I4SensorsFeature.clock(),
                point, target.label, I4SensorsConfig.orderLabel(order), helmet.isEmpty() ? "none" : helmet,
                target == DeathItem.PHOENIX ? "pets" : "stats");
    }

    private static void tickMenu(Minecraft client, LocalPlayer player) {
        long now = System.currentTimeMillis();
        if (now - actionStartedMs > MENU_TIMEOUT_MS) {
            LOGGER.info("{} {} Auto Mask: {} swap TIMED OUT after {}ms (screen now: {}).", TAG, I4SensorsFeature.clock(),
                    actionItem.label, now - actionStartedMs,
                    client.screen == null ? "none" : "\"" + client.screen.getTitle().getString() + "\"");
            if (client.screen instanceof AbstractContainerScreen<?> && isOurMenu(client.screen.getTitle().getString())) {
                client.screen.onClose();
            }
            ModChat.send(CHAT, ModChat.bad("Auto Mask couldn't swap to " + actionItem.label + " (menu timed out)."));
            finish();
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
            return;
        }
        String title = screen.getTitle().getString();
        if (!isOurMenu(title)) {
            if (!title.equals(lastForeignTitle)) {
                lastForeignTitle = title;
                LOGGER.info("{} {} Auto Mask: waiting for the {} menu - currently \"{}\".", TAG, I4SensorsFeature.clock(),
                        stage == Stage.AWAIT_PETS ? "Pets" : "Stats & Equipment", title);
            }
            return;
        }
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
        boolean loaded = false;
        for (int i = 0; i < containerSlots; i++) {
            if (!slots.get(i).getItem().isEmpty()) {
                loaded = true;
                break;
            }
        }
        if (!loaded) {
            return;
        }
        if (menuSeenAtMs == 0L) {
            menuSeenAtMs = now;
            return;
        }
        if (now - menuSeenAtMs < MENU_SETTLE_MS) {
            return;
        }
        int containerId = screen.getMenu().containerId;
        if (stage == Stage.AWAIT_EQUIPMENT) {
            String wanted = actionItem == DeathItem.BONZO ? "BONZO_MASK" : "SPIRIT_MASK";
            for (int i = containerSlots; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (I4SensorsFeature.skyblockId(slot.getItem()).contains(wanted)) {
                    client.gameMode.handleContainerInput(containerId, slot.index, 0, ContainerInput.PICKUP, player);
                    LOGGER.info("{} {} Auto Mask: clicked {} in \"{}\" (slot {}, {}ms after /stats) - closing.", TAG,
                            I4SensorsFeature.clock(), I4SensorsFeature.itemDesc(slot.getItem()), title, slot.index,
                            now - actionStartedMs);
                    screen.onClose();
                    ModChat.send(CHAT, ModChat.text("Auto Mask swapped to "), ModChat.value(actionItem.label + " Mask"));
                    verifyHelmetAtMs = now + 1000L;
                    finish();
                    return;
                }
            }
            LOGGER.info("{} {} Auto Mask: no {} in the inventory part of \"{}\" - marked missing for this world, closing.", TAG,
                    I4SensorsFeature.clock(), wanted, title);
            missing.add(actionItem);
            screen.onClose();
            ModChat.send(CHAT, ModChat.bad("Auto Mask: no " + actionItem.label + " Mask in your inventory."));
            finish();
            return;
        }
        // Pets
        Matcher page = PETS_TITLE.matcher(title);
        int current = page.matches() && page.group(1) != null ? Integer.parseInt(page.group(1)) : 1;
        int total = page.matches() && page.group(2) != null ? Integer.parseInt(page.group(2)) : 1;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty() || !stack.getHoverName().getString().contains("Phoenix")) {
                continue;
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            String joined = lore == null ? "" : String.join(" | ", lore.lines().stream().map(ChatObserver::strip).toList());
            if (joined.contains("Click to despawn")) {
                LOGGER.info("{} {} Auto Mask: Phoenix already summoned (slot {} shows \"Click to despawn!\") - closing.", TAG,
                        I4SensorsFeature.clock(), i);
                phoenixConfirmedOut = true;
                screen.onClose();
                finish();
                return;
            }
            client.gameMode.handleContainerInput(containerId, slots.get(i).index, 0, ContainerInput.PICKUP, player);
            LOGGER.info("{} {} Auto Mask: clicked \"{}\" in \"{}\" (slot {}, page {}/{}, {}ms after /pets) - closing.", TAG,
                    I4SensorsFeature.clock(), ChatObserver.strip(stack.getHoverName()), title, i, current, total,
                    now - actionStartedMs);
            phoenixConfirmedOut = true;
            screen.onClose();
            ModChat.send(CHAT, ModChat.text("Auto Mask summoned "), ModChat.value("Phoenix"));
            finish();
            return;
        }
        if (current < total && petPagesTried < MAX_PET_PAGES) {
            for (int i = 0; i < containerSlots; i++) {
                if (slots.get(i).getItem().getHoverName().getString().contains("Next Page")) {
                    petPagesTried++;
                    menuSeenAtMs = 0L;
                    actionStartedMs = now; // fresh timeout for the next page
                    client.gameMode.handleContainerInput(containerId, i, 0, ContainerInput.PICKUP, player);
                    LOGGER.info("{} {} Auto Mask: no Phoenix on pets page {}/{} - clicked Next Page (slot {}).", TAG,
                            I4SensorsFeature.clock(), current, total, i);
                    return;
                }
            }
        }
        LOGGER.info("{} {} Auto Mask: no Phoenix pet found (page {}/{}) - marked missing for this world, closing.", TAG,
                I4SensorsFeature.clock(), current, total);
        missing.add(DeathItem.PHOENIX);
        screen.onClose();
        ModChat.send(CHAT, ModChat.bad("Auto Mask: no Phoenix pet found in /pets."));
        finish();
    }

    private static boolean isOurMenu(String title) {
        if (stage == Stage.AWAIT_PETS) {
            return PETS_TITLE.matcher(title).matches();
        }
        // Noamm matches "stats & equipment" (lowercased); accept any title containing "equipment" in case the
        // real title differs slightly - the log line above shows the real one.
        return title.toLowerCase(Locale.ROOT).contains("equipment");
    }

    private static void finish() {
        stage = Stage.IDLE;
        actionItem = null;
        menuSeenAtMs = 0L;
    }
}
