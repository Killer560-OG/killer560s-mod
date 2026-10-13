package com.killer560.hub.loadoutkeybinds;

import com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor;
import com.killer560.hub.compat.McCompat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.slf4j.Logger;

/**
 * A slot key set to "Open, Swap, Close" (killer560, 2026-10-13: "a toggle per keybind to have that keybind open the
 * menu and swap then close it or just only swap if you are in the menu"): pressed with no screen open, it sends
 * {@code /loadouts}, waits for Hypixel's "(N/M) Loadout" menu, clicks that slot's loadout, and closes the menu.
 * <p>
 * The {@code /loadouts} is his own key press, so it goes out at once and only tells {@link ActionGate} it happened
 * ({@link ActionGate#noteCommand}, as Pet Wheel's {@code /pets} does). The click and the close are automation: each asks
 * {@link ActionGate#tryAct} with the loadout screen as the screen it drives, so they are one per tick, never in the
 * same tick as each other, and never while any other screen is up. A container whose title is not the loadout menu is
 * never clicked or closed. If the menu has not opened within {@link #OPEN_TIMEOUT_MS} he is told so in chat.
 * <p>
 * Keys are polled at the START of the client tick with no screen open, so a key typed into chat (or any other screen)
 * never fires; a key still held when a screen closes does not fire either. Inside the loadout menu every slot key,
 * whatever its mode, is handled by {@link LoadoutKeybindsFeature}'s screen hook and just clicks.
 */
public final class LoadoutSwapper {

    private static final Logger LOGGER = ModLog.get("killer560smod-loadoutkeybinds");
    private static final String CHAT = "Loadout Keybinds";
    /** How long /loadouts has to bring the menu up. */
    static final long OPEN_TIMEOUT_MS = 5_000L;
    /** How long the click and the close may wait on the gate before giving up (without closing anything else). */
    private static final long STEP_TIMEOUT_MS = 3_000L;
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private enum Stage { IDLE, AWAIT_MENU, CLICK, CLOSE }

    private static Stage stage = Stage.IDLE;
    private static int slotIndex = -1;
    private static long phaseStartedMs = 0L;
    private static long startedMs = 0L;
    private static long clickTick = -1L;
    private static Object lastLevel = null;
    private static boolean[] wasDown = new boolean[12];

    private LoadoutSwapper() {
    }

    static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("LoadoutSwapper.tick", LoadoutSwapper::tick));
    }

    public static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (stage != Stage.IDLE) {
                finish("world change");
            }
        }
        pollKeys(client);
        if (stage == Stage.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            finish("no player");
            return;
        }
        long now = System.currentTimeMillis();
        switch (stage) {
            case AWAIT_MENU -> awaitMenu(client, now);
            case CLICK -> click(client, player, now);
            case CLOSE -> close(client, now);
            default -> finish("unreachable");
        }
    }

    /** Edge-detects every "Open, Swap, Close" key, only while no screen is open. */
    private static void pollKeys(Minecraft client) {
        LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();
        boolean live = cfg.isEnabled() && client.player != null && client.getWindow() != null;
        boolean noScreen = McCompat.screen(client) == null;
        for (int i = 0; i < wasDown.length; i++) {
            int key = cfg.getSlotKey(i);
            boolean osc = live && cfg.getSlotMode(i) == LoadoutKeybindsConfig.Mode.OPEN_SWAP_CLOSE && key != KeyUtil.NONE;
            boolean down = osc && KeyUtil.isBindDown(client.getWindow(), key);
            // The key is the loadout's: eat vanilla's queued clicks for it (a hotbar slot on the default 1-0), here at
            // START_CLIENT_TICK, before handleKeybinds reads them later this tick. A click found here is a press too,
            // which also catches a tap shorter than a tick that the poll above missed.
            boolean clicked = osc && noScreen && consumeVanillaClicks(client, key);
            // Under a screen the state is tracked but never acted on, so a key held as the screen closes is not a press.
            if (((down && !wasDown[i]) || clicked) && noScreen && stage == Stage.IDLE) {
                request(client, i);
            }
            wasDown[i] = down;
        }
    }

    /**
     * Drains the queued clicks of every vanilla key mapping bound to {@code code} (killer560's call, 2026-10-13: an
     * "Open, Swap, Close" key must not also switch the hotbar slot). Only clicks are taken: a held mapping's down state
     * is left alone. True if any click was queued.
     */
    private static boolean consumeVanillaClicks(Minecraft client, int code) {
        boolean mouse = KeyUtil.isMouseCode(code);
        int value = mouse ? KeyUtil.mouseButton(code) : code;
        boolean any = false;
        for (KeyMapping mapping : client.options.keyMappings) {
            InputConstants.Key bound = ((KeyMappingKeyAccessor) (Object) mapping).killer560smod$getKey();
            if (bound == null || bound.getValue() != value
                    || bound.getType() != (mouse ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM)) {
                continue;
            }
            while (mapping.consumeClick()) {
                any = true;
            }
        }
        return any;
    }

    /** Starts a swap to slot {@code index} (0-11). False, with nothing sent, while one is running or a menu is open. */
    static boolean request(Minecraft client, int index) {
        LocalPlayer player = client.player;
        if (stage != Stage.IDLE || player == null || index < 0 || index >= LoadoutKeybindsFeature.LOADOUT_SLOTS.length) {
            return false;
        }
        if (ActionGate.containerScreenOpen(client) || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        if (!ServerCommands.toServer("loadouts")) {
            return false;
        }
        ActionGate.noteCommand(ActionGate.Actor.LOADOUT_SWAP_CMD);
        slotIndex = index;
        startedMs = System.currentTimeMillis();
        phaseStartedMs = startedMs;
        clickTick = -1L;
        stage = Stage.AWAIT_MENU;
        LOGGER.info("[LoadoutKeybinds] slot {}: /loadouts sent.", index + 1);
        return true;
    }

    /** The loadout menu on screen with its items in, or null. Any other screen (another menu, chat) is never ours. */
    private static AbstractContainerScreen<?> loadoutScreen(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!LoadoutKeybindsFeature.isLoadoutScreen(screen)) {
            return null;
        }
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
        return hasItems(container.getMenu()) ? container : null;
    }

    private static boolean hasItems(AbstractContainerMenu menu) {
        int containerSlots = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
        for (int i = 0; i < containerSlots; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void awaitMenu(Minecraft client, long now) {
        if (loadoutScreen(client) != null) {
            LOGGER.info("[LoadoutKeybinds] slot {}: menu open {}ms after /loadouts.", slotIndex + 1, now - startedMs);
            stage = Stage.CLICK;
            phaseStartedMs = now;
            return;
        }
        if (now - phaseStartedMs > OPEN_TIMEOUT_MS) {
            ModChat.send(CHAT, ModChat.bad("The Loadout menu didn't open, so slot " + (slotIndex + 1)
                    + " wasn't swapped to (timed out)."));
            finish("menu never opened");
        }
    }

    private static void click(Minecraft client, LocalPlayer player, long now) {
        AbstractContainerScreen<?> screen = loadoutScreen(client);
        if (screen == null) {
            // Closed, or replaced by something that is not the loadout menu: nothing of ours left to click or close.
            finish("menu went away before the click");
            return;
        }
        if (now - phaseStartedMs > STEP_TIMEOUT_MS) {
            finish("click never got a tick");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.LOADOUT_SWAP_MENU, screen)) {
            return;
        }
        int slot = LoadoutKeybindsFeature.LOADOUT_SLOTS[slotIndex];
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, player);
        clickTick = ActionGate.currentTick();
        stage = Stage.CLOSE;
        phaseStartedMs = now;
        LOGGER.info("[LoadoutKeybinds] slot {}: clicked menu slot {} {}ms after /loadouts.", slotIndex + 1, slot,
                now - startedMs);
    }

    private static void close(Minecraft client, long now) {
        Screen screen = McCompat.screen(client);
        if (!LoadoutKeybindsFeature.isLoadoutScreen(screen)) {
            // Hypixel closed it, or he opened something else: that is not ours to close.
            finish("menu already gone");
            return;
        }
        if (now - phaseStartedMs > STEP_TIMEOUT_MS) {
            finish("close never got a tick");
            return;
        }
        if (ActionGate.currentTick() <= clickTick || !ActionGate.tryAct(ActionGate.Actor.LOADOUT_SWAP_MENU, screen)) {
            return;
        }
        screen.onClose();
        finish("swapped and closed");
    }

    private static void finish(String why) {
        if (stage != Stage.IDLE) {
            LOGGER.info("[LoadoutKeybinds] slot {}: finished ({}) after {}ms.", slotIndex + 1, why,
                    System.currentTimeMillis() - startedMs);
        }
        stage = Stage.IDLE;
        slotIndex = -1;
        clickTick = -1L;
    }
}
