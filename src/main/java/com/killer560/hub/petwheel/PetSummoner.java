package com.killer560.hub.petwheel;

import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picking a wheel slice lands here: send {@code /pets}, wait for the real Pets menu, find the chosen pet's
 * slot by its own item uuid (paging with the real "Next Page" item if it isn't on the first page), click it,
 * close the menu. One state machine, one summon in flight at a time - a second selection while one is already
 * running is refused, matching {@code MaskSwapper.request}'s own "false means not this time, never loop" rule.
 * <p>
 * killer560, 2026-09-21, on why the click still goes through {@link ActionGate} despite this not being a
 * cheat-only feature ("it's just a reskin of the /pets menu, not real automation, so it ships identically in
 * both builds"): the click itself is still a client sending a container interaction with no matching mouse
 * event on the real screen, exactly the shape {@link ActionGate} exists to pace and de-collide - being legit
 * in intent doesn't change what the packet looks like on the wire, so it still takes a gate slot like every
 * other menu-clicking feature (Mask Swap, Auto Croesus) does. See this wave's staging notes for the two
 * {@code ActionGate.Actor} constants this needs ({@code PET_WHEEL_CMD}, {@code PET_WHEEL_MENU}) - not added
 * here, since {@code hub/util/ActionGate.java} is outside this package's scope for this wave.
 * <p>
 * Structure copied from this repo's own {@code maskinvincibility.MaskSwapper} (its {@code SEND_PETS}/
 * {@code AWAIT_PETS}/{@code CLICK_PET} stages): same real title regex, same "container slots = total - 36"
 * math, same "Next Page" item-name match, same {@code handleContainerInput} click call, same
 * "already summoned" lore check so re-picking your already-active pet never despawns it by mistake.
 */
public final class PetSummoner {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-petwheel-summon");
    private static final String CHAT = "Pet Wheel";

    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** Safety cap on Next Page clicks, same idea as {@code MaskSwapper.MAX_PET_PAGES} - a bit higher since a
     *  small wheel (4 slices) with a big pet collection can need more pages than a single "find Phoenix" scan. */
    private static final int MAX_PET_PAGES = 10;
    private static final long STEP_DELAY_MS = 400L;
    private static final long TIMEOUT_MS = 8_000L;

    private enum Stage { IDLE, SEND_PETS, AWAIT_PETS, CLICK_PET }

    private static Stage stage = Stage.IDLE;
    private static PetEntry target = null;
    private static long startedMs = 0L;
    private static long nextActionAtMs = 0L;
    private static long menuFirstSeenMs = 0L;
    private static String lastForeignTitle = null;
    private static int pagesTried = 0;
    private static Object lastLevel = null;

    private PetSummoner() {
    }

    /** Registered from {@link PetWheelFeature#register()} - this class has no entry point of its own. */
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(PetSummoner::tick);
    }

    public static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    /** Starts a summon. Refused (false, no side effects) while one is already running or another Hypixel
     *  container is already open - callers must not retry on false. */
    public static boolean request(PetEntry wanted) {
        if (wanted == null || stage != Stage.IDLE) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return false;
        }
        if (ActionGate.containerScreenOpen(client)) {
            LOGGER.info("[PetWheel] Summon of {} refused - a container screen is already open.", wanted.shortLabel());
            return false;
        }
        target = wanted;
        startedMs = System.currentTimeMillis();
        nextActionAtMs = startedMs + STEP_DELAY_MS;
        menuFirstSeenMs = 0L;
        lastForeignTitle = null;
        pagesTried = 0;
        stage = Stage.SEND_PETS;
        LOGGER.info("[PetWheel] Summoning {} (uuid {}).", wanted.shortLabel(), wanted.uuid());
        return true;
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            abort("world change");
            return;
        }
        if (stage == Stage.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            abort("no player");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startedMs > TIMEOUT_MS) {
            LOGGER.info("[PetWheel] Summon of {} timed out in stage {} after {}ms.", target.shortLabel(), stage, now - startedMs);
            closeOurMenu(client);
            ModChat.send(CHAT, ModChat.bad("Couldn't summon " + target.name() + " (timed out)."));
            abort("timeout");
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        switch (stage) {
            case SEND_PETS -> sendPets(player, now);
            case AWAIT_PETS -> awaitPets(client, now);
            case CLICK_PET -> clickPet(client, player, now);
            default -> abort("unreachable");
        }
    }

    private static void sendPets(LocalPlayer player, long now) {
        if (!ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_CMD)) {
            return;
        }
        player.connection.sendCommand("pets");
        stage = Stage.AWAIT_PETS;
        nextActionAtMs = now + STEP_DELAY_MS;
        LOGGER.info("[PetWheel] Sent /pets for {}.", target.shortLabel());
    }

    private static void awaitPets(Minecraft client, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle().getString();
        if (!PETS_TITLE.matcher(title).matches()) {
            if (!title.equals(lastForeignTitle)) {
                lastForeignTitle = title;
                LOGGER.info("[PetWheel] Waiting for the Pets menu - currently \"{}\".", title);
            }
            return;
        }
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
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
        if (menuFirstSeenMs == 0L) {
            menuFirstSeenMs = now;
            nextActionAtMs = now + STEP_DELAY_MS;
            return;
        }
        stage = Stage.CLICK_PET;
    }

    private static void clickPet(Minecraft client, LocalPlayer player, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || !PETS_TITLE.matcher(screen.getTitle().getString()).matches()) {
            return; // menu went away under us; the timeout ends this
        }
        String title = screen.getTitle().getString();
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        Matcher page = PETS_TITLE.matcher(title);
        int current = page.matches() && page.group(1) != null ? Integer.parseInt(page.group(1)) : 1;
        int total = page.matches() && page.group(2) != null ? Integer.parseInt(page.group(2)) : 1;

        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty() || !target.uuid().equals(ItemProtect.itemUuid(stack))) {
                continue;
            }
            if (alreadyOut(stack)) {
                LOGGER.info("[PetWheel] {} is already summoned (slot {} shows \"Click to despawn!\") - closing.", target.shortLabel(), i);
                closeOurMenu(client);
                ModChat.send(CHAT, ModChat.text(target.name() + " is already summoned."));
                abort("already summoned");
                return;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_MENU, screen)) {
                return;
            }
            client.gameMode.handleContainerInput(screen.getMenu().containerId, slots.get(i).index, 0, ContainerInput.PICKUP, player);
            LOGGER.info("[PetWheel] Clicked \"{}\" in \"{}\" (slot {}, page {}/{}, {}ms after /pets).",
                    target.shortLabel(), title, i, current, total, now - startedMs);
            closeOurMenu(client);
            ModChat.send(CHAT, ModChat.text("Summoned "), ModChat.value(target.name()));
            abort("done");
            return;
        }

        if (current < total && pagesTried < MAX_PET_PAGES) {
            for (int i = 0; i < containerSlots; i++) {
                if (slots.get(i).getItem().getHoverName().getString().contains("Next Page")) {
                    if (!ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_MENU, screen)) {
                        return;
                    }
                    pagesTried++;
                    menuFirstSeenMs = 0L;
                    startedMs = now; // fresh timeout window for the next page
                    client.gameMode.handleContainerInput(screen.getMenu().containerId, i, 0, ContainerInput.PICKUP, player);
                    LOGGER.info("[PetWheel] {} not on page {}/{} - clicked Next Page (slot {}).", target.shortLabel(), current, total, i);
                    nextActionAtMs = now + STEP_DELAY_MS;
                    return;
                }
            }
        }
        LOGGER.info("[PetWheel] {} not found in /pets (page {}/{}) - giving up, no retry.", target.shortLabel(), current, total);
        closeOurMenu(client);
        ModChat.send(CHAT, ModChat.bad("Couldn't find " + target.name() + " in /pets."));
        abort("not found");
    }

    private static boolean alreadyOut(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (var line : lore.lines()) {
            if (line.getString().contains("Click to despawn")) {
                return true;
            }
        }
        return false;
    }

    private static void closeOurMenu(Minecraft client) {
        if (client.screen instanceof AbstractContainerScreen<?> screen && PETS_TITLE.matcher(screen.getTitle().getString()).matches()) {
            client.screen.onClose();
        }
    }

    private static void abort(String why) {
        if (stage != Stage.IDLE) {
            LOGGER.debug("[PetWheel] Summon ended ({}).", why);
        }
        stage = Stage.IDLE;
        target = null;
        menuFirstSeenMs = 0L;
        lastForeignTitle = null;
    }
}
