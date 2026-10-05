package com.killer560.hub.petwheel;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Picking a wheel slice lands here: send {@code /pets}, wait for the real Pets menu, find the chosen pet's
 * slot by its own item uuid (paging with the real "Next Page" item if it isn't on the first page), click it,
 * close the menu. One state machine, one summon in flight at a time - a second selection while one is already
 * running is refused, matching {@code MaskSwapper.request}'s own "false means not this time, never loop" rule.
 * <p>
 * The click still goes through {@link ActionGate} (killer560, 2026-09-21): it is a client sending a container
 * interaction with no matching mouse event, exactly the shape the gate exists to pace, so it takes a gate slot
 * like Mask Swap and Auto Croesus do.
 * <p>
 * <b>Speed (2026-10-04, killer560: "it needs to be a lot faster").</b> The old machine waited on fixed 400 ms
 * timers: 400 before sending {@code /pets}, 400 after, 400 more once the menu had items, and 400 after every
 * page turn - about 1.2 s of pure waiting on top of the round trip for a first-page pet. Now every step reacts
 * to state instead: {@code /pets} goes out from {@link #request} itself (the frame the slice is picked) when the
 * gate allows, the menu is accepted the first tick its items are present, the pet is clicked that same tick if
 * it is on the page, and only a "not on this page" verdict waits {@link #NOT_FOUND_SETTLE_TICKS} unchanged ticks
 * before turning the page (so a page still filling in is never judged empty). A page turn is recognised by the
 * new menu object Hypixel's re-sent open-screen packet creates, not by a timer. Each phase logs its duration.
 * <p>
 * <b>No walking while it runs</b> (killer560, same day: "make it so I cannot walk while it is in progress").
 * {@link #suppressesMovement()} is read by {@code petwheel.mixin.PetWheelInputMixin}, which zeroes the
 * movement keys and move vector {@code KeyboardInput.tick} just built. The physical keys are never touched, so
 * a key still held when the summon ends simply resumes.
 * <p>
 * <b>Hidden menu</b> ("add an option to hide the pets menu while it is working"): with
 * {@link PetWheelConfig#isHideMenuWhileSummoning()} on, {@code PetWheelHideMenuMixin} cancels the
 * {@code setScreen} of the Pets screen this summon is waiting for, the same headless technique Fast Leap uses
 * ({@code fastleap.LeapManager#interceptLeapScreen}). Vanilla has already assigned {@code player.containerMenu}
 * (javap, 26.1.2 and 26.2: {@code MenuScreens$ScreenConstructor.fromPacket} does the putfield, then
 * setScreen), so slot packets still fill the menu and the click works; the screen is just never shown. Every
 * exit path closes the headless menu with a close packet. As a side effect the headless path is also faster:
 * no screen opens, so {@link ActionGate}'s three-tick screen-transition settle never applies to the click.
 */
public final class PetSummoner {

    private static final Logger LOGGER = ModLog.get("killer560smod-petwheel-summon");
    private static final String CHAT = "Pet Wheel";

    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** Safety cap on Next Page clicks, same idea as {@code MaskSwapper.MAX_PET_PAGES}. */
    private static final int MAX_PET_PAGES = 10;
    /** Ticks a loaded page must stay unchanged before "the pet is not on it" is believed and the page turned. A
     *  pet that IS present is clicked the first tick it is seen; only the negative verdict waits. */
    private static final int NOT_FOUND_SETTLE_TICKS = 2;
    /** Per-phase timeout (reset on each page turn): the whole summon gives up if one phase stalls this long. */
    private static final long TIMEOUT_MS = 5_000L;

    private enum Stage { IDLE, SEND_PETS, AWAIT_PETS, CLICK_PET }

    private static Stage stage = Stage.IDLE;
    private static PetEntry target = null;
    private static long startedMs = 0L;
    private static long phaseStartedMs = 0L;
    private static long cmdSentMs = 0L;
    private static int pagesTried = 0;
    private static Object lastLevel = null;
    /** True for the summon in flight when the hide option was on at request time. */
    private static boolean hideRequested = false;

    /** The headless Pets menu (hide mode) and its title, captured when its setScreen was cancelled. */
    private static AbstractContainerMenu hiddenMenu = null;
    private static String hiddenTitle = null;

    /** The menu a Next Page click was sent on; a different menu object means the new page has arrived. */
    private static AbstractContainerMenu pagedFrom = null;
    /** {@link #signature} of that page, so a page turn Hypixel delivers into the SAME window is noticed too. */
    private static int pagedFromSignature = 0;
    /** "Not found" settle bookkeeping: the menu and filled-slot count last seen, and for how many ticks. */
    private static AbstractContainerMenu settleMenu = null;
    private static int settleFilled = -1;
    private static int settleTicks = 0;

    private PetSummoner() {
    }

    /** Registered from {@link PetWheelFeature#register()} - this class has no entry point of its own. */
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("PetSummoner.tick", PetSummoner::tick));
    }

    public static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    /** Read every input tick by {@code PetWheelInputMixin}: true while a summon is in flight. */
    public static boolean suppressesMovement() {
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
        if (ActionGate.containerScreenOpen(client) || client.player.containerMenu != client.player.inventoryMenu) {
            LOGGER.info("[PetWheel] Summon of {} refused - a container is already open.", wanted.shortLabel());
            return false;
        }
        target = wanted;
        startedMs = System.currentTimeMillis();
        phaseStartedMs = startedMs;
        cmdSentMs = 0L;
        pagesTried = 0;
        hideRequested = PetWheelConfig.getInstance().isHideMenuWhileSummoning();
        hiddenMenu = null;
        hiddenTitle = null;
        pagedFrom = null;
        resetSettle();
        stage = Stage.SEND_PETS;
        // Send /pets right now, in the frame the slice was picked, rather than waiting for the next tick: a chat
        // command has no position or screen to be stale against, and the gate still decides whether this frame
        // may act. If it says no, tick() retries every tick.
        sendPets(client.player, startedMs);
        return true;
    }

    /**
     * Called from {@code PetWheelHideMenuMixin} at the HEAD of {@code setScreen}. Swallows the Pets screen this
     * summon is waiting for when the hide option is on.
     *
     * @return true to cancel {@code setScreen} (the menu stays open headless and {@link #tick} drives it)
     */
    public static boolean interceptPetsScreen(Minecraft client, Screen screen) {
        if (!hideRequested || (stage != Stage.AWAIT_PETS && stage != Stage.CLICK_PET)) {
            return false;
        }
        // Something else on screen (chat, pause): replacing it would close it, so show the menu normally there.
        if (McCompat.screen(client) != null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        LocalPlayer player = client.player;
        String title = screen.getTitle().getString();
        // fromPacket assigns containerMenu right before setScreen - this proves it is the open-screen packet path.
        if (player == null || player.containerMenu != container.getMenu() || !PETS_TITLE.matcher(title).matches()) {
            return false;
        }
        hiddenMenu = container.getMenu();
        hiddenTitle = title;
        return true;
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (stage != Stage.IDLE) {
                finish(client, "world change");
            }
            return;
        }
        if (stage == Stage.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            finish(client, "no player");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - phaseStartedMs > TIMEOUT_MS) {
            LOGGER.info("[PetWheel] Summon of {} timed out in stage {} after {}ms ({}ms total).",
                    target.shortLabel(), stage, now - phaseStartedMs, now - startedMs);
            ModChat.send(CHAT, ModChat.bad("Couldn't summon " + target.name() + " (timed out)."));
            finish(client, "timeout");
            return;
        }
        switch (stage) {
            case SEND_PETS -> sendPets(player, now);
            case AWAIT_PETS -> awaitPets(client, now);
            case CLICK_PET -> clickPet(client, player, now);
            default -> finish(client, "unreachable");
        }
    }

    private static void sendPets(LocalPlayer player, long now) {
        if (!ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_CMD)) {
            return;
        }
        player.connection.sendCommand("pets");
        cmdSentMs = now;
        LOGGER.info("[PetWheel] {}: /pets sent +{}ms after the pick.", target.shortLabel(), now - startedMs);
        stage = Stage.AWAIT_PETS;
        phaseStartedMs = now;
    }

    /** The Pets menu currently open - headless or on screen - or null. */
    private record PetsMenu(AbstractContainerMenu menu, String title, AbstractContainerScreen<?> screen) {
    }

    private static PetsMenu currentPetsMenu(Minecraft client) {
        LocalPlayer player = client.player;
        if (hiddenMenu != null && player != null && player.containerMenu == hiddenMenu
                && !(McCompat.screen(client) instanceof AbstractContainerScreen<?>)) {
            return new PetsMenu(hiddenMenu, hiddenTitle, null);
        }
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) {
            String title = screen.getTitle().getString();
            if (PETS_TITLE.matcher(title).matches()) {
                return new PetsMenu(screen.getMenu(), title, screen);
            }
        }
        return null;
    }

    private static int filledSlots(AbstractContainerMenu menu) {
        List<Slot> slots = menu.slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        int filled = 0;
        for (int i = 0; i < containerSlots; i++) {
            if (!slots.get(i).getItem().isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Cheap fingerprint of a page's contents (item names by slot), to tell one page from the next. */
    private static int signature(AbstractContainerMenu menu) {
        List<Slot> slots = menu.slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        int h = 1;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            h = 31 * h + (stack.isEmpty() ? 0 : stack.getHoverName().getString().hashCode());
        }
        return h;
    }

    private static void awaitPets(Minecraft client, long now) {
        PetsMenu pm = currentPetsMenu(client);
        if (pm == null || filledSlots(pm.menu()) == 0) {
            return;
        }
        LOGGER.info("[PetWheel] {}: Pets menu loaded {}ms after /pets ({}).",
                target.shortLabel(), now - cmdSentMs, pm.screen() == null ? "hidden" : "shown");
        stage = Stage.CLICK_PET;
        phaseStartedMs = now;
        clickPet(client, client.player, now); // same tick: the pet may already be clickable
    }

    private static void clickPet(Minecraft client, LocalPlayer player, long now) {
        PetsMenu pm = currentPetsMenu(client);
        if (pm == null) {
            return; // menu went away under us (or the next page is in flight); the timeout ends this
        }
        AbstractContainerMenu menu = pm.menu();
        if (pagedFrom != null) {
            if ((menu == pagedFrom && signature(menu) == pagedFromSignature) || filledSlots(menu) == 0) {
                return; // still the old page, or the new one has not filled yet
            }
            LOGGER.info("[PetWheel] {}: page {} loaded {}ms after Next Page.", target.shortLabel(), pagesTried + 1, now - phaseStartedMs);
            pagedFrom = null;
            phaseStartedMs = now;
        }
        recordSeen(menu);
        List<Slot> slots = menu.slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        Matcher page = PETS_TITLE.matcher(pm.title());
        boolean titled = page.matches();
        int current = titled && page.group(1) != null ? Integer.parseInt(page.group(1)) : 1;
        int total = titled && page.group(2) != null ? Integer.parseInt(page.group(2)) : 1;

        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty() || !target.uuid().equals(ItemProtect.itemUuid(stack))) {
                continue;
            }
            if (alreadyOut(stack)) {
                ModChat.send(CHAT, ModChat.text(target.name() + " is already summoned."));
                finish(client, "already summoned");
                return;
            }
            if (!gate(pm)) {
                return;
            }
            client.gameMode.handleContainerInput(menu.containerId, slots.get(i).index, 0, ContainerInput.PICKUP, player);
            LOGGER.info("[PetWheel] {}: clicked on page {} {}ms after it loaded; {}ms total from the pick.",
                    target.shortLabel(), current, now - phaseStartedMs, now - startedMs);
            ModChat.send(CHAT, ModChat.text("Summoned "), ModChat.value(target.name()));
            finish(client, "done");
            return;
        }

        // Not on this page. Only believe that once the page has sat unchanged for a couple of ticks, so a page
        // whose items are still arriving in separate slot packets is never judged empty.
        int filled = filledSlots(menu);
        if (settleMenu != menu || settleFilled != filled) {
            settleMenu = menu;
            settleFilled = filled;
            settleTicks = 0;
            return;
        }
        if (++settleTicks < NOT_FOUND_SETTLE_TICKS) {
            return;
        }

        if (current < total && pagesTried < MAX_PET_PAGES) {
            for (int i = 0; i < containerSlots; i++) {
                if (slots.get(i).getItem().getHoverName().getString().contains("Next Page")) {
                    if (!gate(pm)) {
                        return;
                    }
                    pagesTried++;
                    pagedFrom = menu;
                    pagedFromSignature = signature(menu);
                    resetSettle();
                    phaseStartedMs = now; // fresh timeout window for the next page
                    client.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, player);
                    LOGGER.info("[PetWheel] {}: not on page {}/{}, Next Page clicked.", target.shortLabel(), current, total);
                    return;
                }
            }
        }
        ModChat.send(CHAT, ModChat.bad("Couldn't find " + target.name() + " in /pets."));
        finish(client, "not found");
    }

    private static boolean gate(PetsMenu pm) {
        return pm.screen() != null
                ? ActionGate.tryAct(ActionGate.Actor.PET_WHEEL_MENU, pm.screen())
                : ActionGate.tryActHeadless(ActionGate.Actor.PET_WHEEL_MENU, pm.menu());
    }

    /** A headless page never reaches {@link PetsMenuScanner} (it reads the screen), so record its pets here. */
    private static void recordSeen(AbstractContainerMenu menu) {
        if (hiddenMenu != menu) {
            return;
        }
        List<Slot> slots = menu.slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        boolean changed = false;
        for (int i = 0; i < containerSlots; i++) {
            PetEntry entry = PetsMenuScanner.scanSlot(slots.get(i).getItem());
            if (entry != null && cfg.recordSeenPet(entry)) {
                changed = true;
            }
        }
        if (changed) {
            cfg.save();
        }
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

    /** Closes whichever Pets menu this summon opened - the headless one with a close packet and a drop back to
     *  the inventory menu (never {@code setScreen(null)}, which would also close a chat or pause screen the
     *  player opened meanwhile), or the visible one through its own {@code onClose}. */
    private static void closeOurMenu(Minecraft client) {
        LocalPlayer player = client.player;
        if (hiddenMenu != null && player != null && player.containerMenu == hiddenMenu) {
            player.connection.send(new ServerboundContainerClosePacket(hiddenMenu.containerId));
            player.containerMenu = player.inventoryMenu;
            return;
        }
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?> screen && PETS_TITLE.matcher(screen.getTitle().getString()).matches()) {
            screen.onClose();
        }
    }

    private static void resetSettle() {
        settleMenu = null;
        settleFilled = -1;
        settleTicks = 0;
    }

    /** The one exit: closes our menu if it is open, logs, and releases movement. */
    private static void finish(Minecraft client, String why) {
        try {
            if (stage == Stage.AWAIT_PETS || stage == Stage.CLICK_PET) {
                closeOurMenu(client);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[PetWheel] closing the Pets menu failed: {}", e.toString());
        }
        if (target != null) {
            LOGGER.info("[PetWheel] {}: finished ({}) after {}ms.", target.shortLabel(), why, System.currentTimeMillis() - startedMs);
        }
        stage = Stage.IDLE;
        target = null;
        hiddenMenu = null;
        hiddenTitle = null;
        pagedFrom = null;
        hideRequested = false;
        resetSettle();
        // Nothing to restore for movement: the input mixin only rewrote the input KeyboardInput computed each
        // tick and never touched the key mappings, so a key still held simply takes effect again next tick.
    }
}
