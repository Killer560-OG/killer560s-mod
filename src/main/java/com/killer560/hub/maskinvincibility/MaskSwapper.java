package com.killer560.hub.maskinvincibility;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod's one death-item swap mechanism: {@code /stats} + a click on the mask in the menu for Spirit and
 * Bonzo, a rod throw for Phoenix.
 * <p>
 * killer560 (2026-09-21): "instead of it being in hotbar it should run /stats and click on it in my inventory,
 * or throw a rod to swap to phoenix. make sure there is delay of some sort between each action. Also make the
 * order it should use them as spirit, phoenix, bonzo."
 * <p>
 * <b>Why a separate class.</b> {@link MaskInvincibilityFeature} (proc timers) and {@code i4sensors.I4AutoMask}
 * (the Sharp Shooter timeline) both want to put a different mask on your head, with the same menu flow and the
 * same anti-spam rules. This is the single implementation both call: {@link #request} takes a target and a
 * requester label, refuses while another swap is running, and everything below - the gate calls, the inter-step
 * delay, the "don't retry, mark it missing" rule - applies once, for whoever asked. {@code I4AutoMask} has
 * already dropped its own copy of this flow in favour of this class; it is still not this agent's file.
 * <p>
 * <b>The rod.</b> A Phoenix pet cannot be equipped from {@code /stats}, and {@code /pets} is another menu and
 * another command. killer560's own route is a Hypixel <i>Autopet rule</i> keyed to holding a rod ("throw a rod
 * to swap to phoenix"): select the rod in the hotbar, cast it, and Hypixel's own rule summons Phoenix. This
 * sends no command at all for Phoenix - it is a hotbar select plus one right-click, spaced by the same delay -
 * and stays the default ({@link MaskInvincibilityConfig.PhoenixRoute#ROD}).
 * <p>
 * <b>The /pets menu (regression fix, 2026-09-21).</b> Unifying the mod's two independent swap flows onto this
 * class dropped {@code i4sensors.I4AutoMask}'s old {@code /pets} walk - the one route that actually works
 * without an Autopet rule set up. It is ported back here, verbatim off the deleted class ({@code git show
 * <pre-unify commit>:.../I4AutoMask.java}: real menu title {@link #PETS_TITLE}, pet matched by "Phoenix" in its
 * hover name, "Click to despawn!" in its lore meaning it's already out, "Next Page" paging), as
 * {@link MaskInvincibilityConfig.PhoenixRoute#PETS} - a setting, not a second implementation. Two things use it:
 * killer560 can pick it directly in the tab, and {@link #request} falls back to it on its own whenever the rod
 * route can't find a rod, because a death-save silently doing nothing (his Autopet rule not being set up) is
 * worse than sending one extra command - it says so once in chat either way.
 * <p>
 * <b>Send rate.</b> A swap only ever starts from a real proc (or the i4 timeline), never from a poll, and it
 * never retries: one command per swap ({@code /stats} for a mask, {@code /pets} for the Phoenix menu route - the
 * rod route sends none), and a failure (menu never opened, item not in the menu) marks that target unavailable
 * until the world changes, so the same miss cannot loop. On top of that {@link #MIN_COMMAND_GAP_MS} is a hard
 * floor between any two commands this class sends, {@code /pets} included - so a mask swap and a Phoenix-via-
 * pets swap can never both slip a command past it for what is effectively the same fight moment. Worst case is
 * therefore <b>6 commands per minute</b>, and in practice it is bounded by the masks' own cooldowns (Spirit 30s,
 * Phoenix 60s, Bonzo 180s), i.e. about 2/min in the worst real fight.
 */
public final class MaskSwapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-maskswap");
    private static final String CHAT = "Mask";

    /** Hard floor between two commands this class sends, on top of everything else. Anti-mute, not anti-cheat. */
    // 4s, not 10s. A 10s floor read as safe in isolation, but Auto i4 legitimately wants a swap at two
    // points inside one device, and the second was being refused outright - the "anti-mute" measure was
    // quietly breaking the feature it protects. Two /stats a few seconds apart is unremarkable traffic; a
    // retry LOOP is what gets people muted, and there is still no retry anywhere in this class.
    public static final long MIN_COMMAND_GAP_MS = 4_000L;
    /** Give up on a swap this long after it started. One shot - see the class doc, there is no retry. */
    private static final long TIMEOUT_MS = 6_000L;

    /** Real title from the deleted {@code I4AutoMask}, ported verbatim: {@code "Pets"} or {@code "(N/M) Pets"}. */
    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");
    /** Ported from the deleted class - stop paging through /pets after this many "Next Page" clicks. */
    private static final int MAX_PET_PAGES = 6;

    /**
     * How long {@link #pickTarget} treats Phoenix as just handled, even though neither route can see it die
     * again afterward (a rod throw has no confirmation at all; {@code /pets} only tells us at the moment we
     * open it - see {@link #msSincePhoenixHandled()}). Long enough to bridge Auto i4's own two swap points
     * (3.5s apart) so it stops needing its own local "already thrown" flag; short enough that a genuine second
     * Phoenix pop later in the same fight - which the caller's own cooldown predicate already tracks
     * separately - is never the thing blocking a real retry.
     */
    private static final long PHOENIX_HANDLED_GUARD_MS = 5_000L;

    /** {@link #abort}'s {@code why} values that mean the swap actually put the target on/out, for
     *  {@link #lastCompletedSuccess()}. */
    private static final Set<String> SUCCESS_REASONS = Set.of("done", "already summoned", "rod thrown", "rod done");

    /**
     * ActionGate's own actors for this class's three kinds of action (the command, the menu click, the rod
     * cast) - {@link ActionGate.Actor#MASK_SWAP_CMD}/{@code MASK_SWAP_MENU}/{@code MASK_SWAP_ROD}.
     */
    private static final ActionGate.Actor CMD_ACTOR = ActionGate.Actor.MASK_SWAP_CMD;
    private static final ActionGate.Actor MENU_ACTOR = ActionGate.Actor.MASK_SWAP_MENU;
    private static final ActionGate.Actor WORLD_ACTOR = ActionGate.Actor.MASK_SWAP_ROD;

    /** What a swap can put on you. Ids are matched with {@code contains} so {@code STARRED_} variants count. */
    public enum Target {
        SPIRIT("Spirit Mask", "SPIRIT_MASK"),
        PHOENIX("Phoenix Pet", null),
        BONZO("Bonzo's Mask", "BONZO_MASK");

        private final String label;
        private final String skyblockId;

        Target(String label, String skyblockId) {
            this.label = label;
            this.skyblockId = skyblockId;
        }

        public String label() {
            return label;
        }

        /** Null for Phoenix - it is a pet, not a helmet. */
        public String skyblockId() {
            return skyblockId;
        }
    }

    /** killer560, 2026-09-21: "make the order it should use them as spirit, phoenix, bonzo". */
    public static final List<Target> DEFAULT_ORDER = List.of(Target.SPIRIT, Target.PHOENIX, Target.BONZO);

    private enum Stage {
        IDLE, SEND_STATS, AWAIT_MENU, CLICK_MASK, CLOSE_MENU, ROD_SELECT, ROD_CAST, ROD_RESTORE,
        SEND_PETS, AWAIT_PETS, CLICK_PET
    }

    private static final Set<Target> unavailable = EnumSet.noneOf(Target.class);

    private static Stage stage = Stage.IDLE;
    private static Target target = null;
    private static String requester = "";
    private static long startedMs = 0L;
    private static long nextActionAtMs = 0L;
    private static long lastCommandMs = Long.MIN_VALUE / 4;
    private static long menuFirstSeenMs = 0L;
    private static int previousSlot = -1;
    private static String lastForeignTitle = null;
    private static Object lastLevel = null;
    private static int petPagesTried = 0;
    /** Set the moment a rod is thrown or {@code /pets} confirms Phoenix is out - see {@link #msSincePhoenixHandled()}. */
    private static long lastPhoenixRodThrowMs = 0L;
    private static long lastPhoenixConfirmedSummonMs = 0L;
    /** Bumped by {@link #abort} every time a swap ends - see {@link #completionSeq()}. */
    private static long completionSeq = 0L;
    private static Target lastCompletedTarget = null;
    private static String lastCompletedRequester = null;
    private static boolean lastCompletedSuccess = false;

    private MaskSwapper() {
    }

    /** Registered from {@link MaskInvincibilityFeature#register()} - this class has no entry point of its own. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    // ------------------------------------------------------------------ api

    public static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    /** True once a swap to this target has failed in this world - callers should skip it instead of retrying. */
    public static boolean isUnavailable(Target t) {
        return unavailable.contains(t);
    }

    /**
     * First target in {@code order} that is worth swapping to: not on cooldown (the caller owns that knowledge -
     * the proc timers and the i4 tracker count it differently), not already on you, not known missing, and -
     * Phoenix only - not just handled (see {@link #msSincePhoenixHandled()}).
     *
     * @return null when there is nothing to swap to, which is a normal outcome and must not be retried.
     */
    public static Target pickTarget(List<Target> order, Predicate<Target> onCooldown) {
        Minecraft client = Minecraft.getInstance();
        String worn = client.player == null ? ""
                : orEmpty(CheatUtils.skyblockId(client.player.getItemBySlot(EquipmentSlot.HEAD)));
        for (Target t : order) {
            if (onCooldown.test(t) || unavailable.contains(t)) {
                continue;
            }
            if (t.skyblockId != null && worn.contains(t.skyblockId)) {
                continue;
            }
            if (t == Target.PHOENIX && msSincePhoenixHandled() < PHOENIX_HANDLED_GUARD_MS) {
                continue;
            }
            return t;
        }
        return null;
    }

    /**
     * Milliseconds since either Phoenix route last did something ({@code null}/never handled reads as a huge
     * number). Neither route can see the pet despawn again afterward, so this is a short anti-duplicate guard
     * (see {@link #PHOENIX_HANDLED_GUARD_MS}), not a durable "Phoenix is out" fact - the real per-fight cooldown
     * still belongs to whatever {@code onCooldown} predicate the caller passes {@link #pickTarget}.
     */
    public static long msSincePhoenixHandled() {
        long last = Math.max(lastPhoenixRodThrowMs, lastPhoenixConfirmedSummonMs);
        return last <= 0 ? Long.MAX_VALUE / 2 : System.currentTimeMillis() - last;
    }

    /**
     * Bumped once every time a swap this class started finishes, success or not - poll this each tick and
     * compare against a saved value to notice a swap you requested has ended, then read
     * {@link #lastCompletedTarget()}/{@link #lastCompletedRequester()}/{@link #lastCompletedSuccess()}.
     * Restores what {@code i4sensors.I4AutoMask} used to do with its own "log the helmet 1s after the click"
     * verification before the two swap flows were unified - it lost that when its own click was deleted, and
     * there was nowhere to hang the callback until now.
     */
    public static long completionSeq() {
        return completionSeq;
    }

    public static Target lastCompletedTarget() {
        return lastCompletedTarget;
    }

    public static String lastCompletedRequester() {
        return lastCompletedRequester;
    }

    /** True if the swap named by {@link #completionSeq()} actually put the target on/out (a click, a rod throw,
     *  or Phoenix already being out) rather than timing out or coming up empty. */
    public static boolean lastCompletedSuccess() {
        return lastCompletedSuccess;
    }

    /**
     * Starts a swap. Refused (returns false, no side effects) while another swap is running, while the target
     * is already known missing, or while the command floor has not elapsed. Callers must treat false as
     * "not this time" and never loop on it.
     */
    public static boolean request(Target wanted, String who) {
        if (wanted == null || stage != Stage.IDLE) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return false;
        }
        if (ActionGate.containerScreenOpen(client)) {
            // A terminal or any other Hypixel menu is already up: /stats would stack a second container on
            // top of it and the rod cast would be an interaction the server can see is impossible.
            LOGGER.info("[MaskSwap] {} asked for {} - refused, a container screen is already open.", who, wanted.label());
            return false;
        }
        if (unavailable.contains(wanted)) {
            LOGGER.info("[MaskSwap] {} asked for {} - refused, it was already marked missing this world.", who, wanted.label());
            return false;
        }
        // The rod route sends no command at all, so only a mask swap or a direct Phoenix-via-/pets request has
        // to clear the floor here. A rod route that ends up falling back to /pets (no rod found) checks the
        // same floor again at that point, in rodSelect() - it isn't known yet which command, if any, this
        // swap will send.
        boolean phoenixViaPets = wanted == Target.PHOENIX
                && MaskInvincibilityConfig.getInstance().getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.PETS;
        long now = System.currentTimeMillis();
        if ((wanted != Target.PHOENIX || phoenixViaPets) && now - lastCommandMs < MIN_COMMAND_GAP_MS) {
            LOGGER.info("[MaskSwap] {} asked for {} - refused, only {}ms since the last command (floor {}ms).",
                    who, wanted.label(), now - lastCommandMs, MIN_COMMAND_GAP_MS);
            return false;
        }
        target = wanted;
        requester = who;
        startedMs = now;
        nextActionAtMs = now + stepDelayMs();
        menuFirstSeenMs = 0L;
        lastForeignTitle = null;
        petPagesTried = 0;
        previousSlot = client.player.getInventory().getSelectedSlot();
        stage = wanted != Target.PHOENIX ? Stage.SEND_STATS : phoenixViaPets ? Stage.SEND_PETS : Stage.ROD_SELECT;
        LOGGER.info("[MaskSwap] {} requested {} - {} (step delay {}ms).", who, wanted.label(),
                wanted != Target.PHOENIX ? "/stats + menu click" : phoenixViaPets ? "/pets + menu click" : "rod throw",
                stepDelayMs());
        return true;
    }

    /** Cleared on world change so a mask you did have next run is tried again. */
    public static void resetWorldState() {
        unavailable.clear();
        lastPhoenixRodThrowMs = 0L;
        lastPhoenixConfirmedSummonMs = 0L;
        abort("world change");
    }

    // ------------------------------------------------------------------ state machine

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            unavailable.clear();
            lastPhoenixRodThrowMs = 0L;
            lastPhoenixConfirmedSummonMs = 0L;
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
            LOGGER.info("[MaskSwap] {} swap for {} TIMED OUT in stage {} after {}ms - marked missing, no retry.",
                    requester, target.label(), stage, now - startedMs);
            unavailable.add(target);
            closeOurMenu(client);
            ModChat.send(CHAT, ModChat.bad("Couldn't swap to " + target.label() + " (timed out)."));
            abort("timeout");
            return;
        }
        // Every step waits out the inter-step delay first; ActionGate's own one-action-per-tick pacing then
        // applies on top of it (killer560: "make sure there is delay of some sort between each action").
        if (now < nextActionAtMs) {
            return;
        }
        switch (stage) {
            case SEND_STATS -> sendStats(player, now);
            case AWAIT_MENU -> awaitMenu(client, now);
            case CLICK_MASK -> clickMask(client, player, now);
            case CLOSE_MENU -> {
                closeOurMenu(client);
                ModChat.send(CHAT, ModChat.text("Swapped to "), ModChat.value(target.label()));
                abort("done");
            }
            case ROD_SELECT -> rodSelect(player, now);
            case ROD_CAST -> rodCast(client, player, now);
            case ROD_RESTORE -> rodRestore(player);
            case SEND_PETS -> sendPets(player, now);
            case AWAIT_PETS -> awaitPets(client, now);
            case CLICK_PET -> clickPet(client, player, now);
            default -> abort("unreachable");
        }
    }

    private static void sendStats(LocalPlayer player, long now) {
        if (!ActionGate.tryAct(CMD_ACTOR)) {
            return;
        }
        lastCommandMs = now;
        startedMs = now; // the timeout is about the menu, so it starts when the command actually goes out
        player.connection.sendCommand("stats");
        stage = Stage.AWAIT_MENU;
        nextActionAtMs = now + stepDelayMs();
        LOGGER.info("[MaskSwap] Sent /stats for {} ({}).", target.label(), requester);
    }

    private static void awaitMenu(Minecraft client, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle().getString();
        if (!title.toLowerCase(Locale.ROOT).contains("equipment")) {
            if (!title.equals(lastForeignTitle)) {
                lastForeignTitle = title;
                LOGGER.info("[MaskSwap] Waiting for the Stats & Equipment menu - currently \"{}\".", title);
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
        if (menuFirstSeenMs == 0L) {
            // The menu appearing is itself an "action" - let it settle a full step before clicking in it.
            menuFirstSeenMs = now;
            nextActionAtMs = now + stepDelayMs();
            return;
        }
        stage = Stage.CLICK_MASK;
    }

    private static void clickMask(Minecraft client, LocalPlayer player, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("equipment")) {
            return; // the menu went away under us; the timeout will end this
        }
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
        // Only the player-inventory half of the menu: the container half is Hypixel's own equipment display,
        // and clicking the copy shown there does nothing.
        for (int i = containerSlots; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!orEmpty(CheatUtils.skyblockId(slot.getItem())).contains(target.skyblockId())) {
                continue;
            }
            if (!ActionGate.tryAct(MENU_ACTOR, screen)) {
                return;
            }
            client.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, player);
            LOGGER.info("[MaskSwap] Clicked {} in \"{}\" (slot {}, {}ms after /stats).", target.label(),
                    screen.getTitle().getString(), slot.index, now - startedMs);
            stage = Stage.CLOSE_MENU;
            nextActionAtMs = now + stepDelayMs();
            return;
        }
        LOGGER.info("[MaskSwap] No {} in the inventory half of the Stats menu - marked missing this world, no retry.",
                target.label());
        unavailable.add(target);
        closeOurMenu(client);
        ModChat.send(CHAT, ModChat.bad("No " + target.label() + " in your inventory."));
        abort("not found");
    }

    private static void rodSelect(LocalPlayer player, long now) {
        int slot = findRodSlot(player);
        if (slot < 0) {
            // killer560's own route relies on a Hypixel Autopet rule he might not have set up - a death-save
            // silently doing nothing is exactly the regression this whole change exists to fix, so fall back to
            // the /pets menu instead of just giving up. That's a second command, so it still owes the floor.
            if (now - lastCommandMs < MIN_COMMAND_GAP_MS) {
                LOGGER.info("[MaskSwap] No hotbar item matching \"{}\" for Phoenix, and the command floor hasn't "
                                + "cleared to fall back to /pets ({}ms left) - refusing this attempt, no retry.",
                        MaskInvincibilityConfig.getInstance().getPhoenixRodName(),
                        MIN_COMMAND_GAP_MS - (now - lastCommandMs));
                ModChat.send(CHAT, ModChat.bad("No rod for Phoenix, and /pets can't send yet (command floor)."));
                abort("no rod, floored");
                return;
            }
            LOGGER.info("[MaskSwap] No hotbar item matching \"{}\" for Phoenix - falling back to /pets.",
                    MaskInvincibilityConfig.getInstance().getPhoenixRodName());
            ModChat.send(CHAT, ModChat.text("No rod found for Phoenix - falling back to "), ModChat.value("/pets"));
            stage = Stage.SEND_PETS;
            nextActionAtMs = now + stepDelayMs();
            return;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            if (!ActionGate.tryAct(WORLD_ACTOR)) {
                return;
            }
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            LOGGER.info("[MaskSwap] Selected rod \"{}\" in hotbar slot {} (was {}).",
                    ChatObserver.strip(player.getInventory().getItem(slot).getHoverName()), slot, previousSlot);
        }
        stage = Stage.ROD_CAST;
        nextActionAtMs = now + stepDelayMs();
    }

    private static void rodCast(Minecraft client, LocalPlayer player, long now) {
        if (!ActionGate.tryAct(WORLD_ACTOR)) {
            return;
        }
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        lastPhoenixRodThrowMs = now;
        LOGGER.info("[MaskSwap] Threw the rod for Phoenix ({}). Hypixel's own Autopet rule does the summon.", requester);
        ModChat.send(CHAT, ModChat.text("Threw the rod for "), ModChat.value("Phoenix"));
        if (!MaskInvincibilityConfig.getInstance().isRodReturnToPreviousSlot() || previousSlot < 0) {
            abort("rod thrown");
            return;
        }
        stage = Stage.ROD_RESTORE;
        nextActionAtMs = now + stepDelayMs();
    }

    private static void rodRestore(LocalPlayer player) {
        if (previousSlot >= 0 && previousSlot < 9 && player.getInventory().getSelectedSlot() != previousSlot) {
            if (!ActionGate.tryAct(WORLD_ACTOR)) {
                return;
            }
            player.getInventory().setSelectedSlot(previousSlot);
            player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
            LOGGER.info("[MaskSwap] Returned to hotbar slot {}.", previousSlot);
        }
        abort("rod done");
    }

    // ------------------------------------------------------------------ /pets (Phoenix menu route)
    // Ported off the deleted i4sensors.I4AutoMask (pre-unify commit, its AWAIT_PETS/tickMenu pets branch) -
    // same title pattern, same "Phoenix" hover-name match, same "Click to despawn!" lore check, same paging.

    private static void sendPets(LocalPlayer player, long now) {
        if (!ActionGate.tryAct(CMD_ACTOR)) {
            return;
        }
        lastCommandMs = now;
        startedMs = now; // the timeout is about the menu, so it starts when the command actually goes out
        petPagesTried = 0;
        menuFirstSeenMs = 0L;
        lastForeignTitle = null;
        player.connection.sendCommand("pets");
        stage = Stage.AWAIT_PETS;
        nextActionAtMs = now + stepDelayMs();
        LOGGER.info("[MaskSwap] Sent /pets for Phoenix ({}).", requester);
    }

    private static void awaitPets(Minecraft client, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle().getString();
        if (!PETS_TITLE.matcher(title).matches()) {
            if (!title.equals(lastForeignTitle)) {
                lastForeignTitle = title;
                LOGGER.info("[MaskSwap] Waiting for the Pets menu - currently \"{}\".", title);
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
        if (menuFirstSeenMs == 0L) {
            // The menu appearing is itself an "action" - let it settle a full step before clicking in it.
            menuFirstSeenMs = now;
            nextActionAtMs = now + stepDelayMs();
            return;
        }
        stage = Stage.CLICK_PET;
    }

    private static void clickPet(Minecraft client, LocalPlayer player, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !PETS_TITLE.matcher(screen.getTitle().getString()).matches()) {
            return; // the menu went away under us; the timeout will end this
        }
        String title = screen.getTitle().getString();
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
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
                LOGGER.info("[MaskSwap] Phoenix already summoned (slot {} shows \"Click to despawn!\") - closing.", i);
                lastPhoenixConfirmedSummonMs = now;
                closeOurMenu(client);
                abort("already summoned");
                return;
            }
            if (!ActionGate.tryAct(MENU_ACTOR, screen)) {
                return;
            }
            client.gameMode.handleContainerInput(screen.getMenu().containerId, slots.get(i).index, 0, ContainerInput.PICKUP, player);
            LOGGER.info("[MaskSwap] Clicked \"{}\" in \"{}\" (slot {}, page {}/{}, {}ms after /pets).",
                    ChatObserver.strip(stack.getHoverName()), title, i, current, total, now - startedMs);
            lastPhoenixConfirmedSummonMs = now;
            closeOurMenu(client);
            ModChat.send(CHAT, ModChat.text("Summoned "), ModChat.value("Phoenix"));
            abort("done");
            return;
        }
        if (current < total && petPagesTried < MAX_PET_PAGES) {
            for (int i = 0; i < containerSlots; i++) {
                if (slots.get(i).getItem().getHoverName().getString().contains("Next Page")) {
                    if (!ActionGate.tryAct(MENU_ACTOR, screen)) {
                        return;
                    }
                    petPagesTried++;
                    menuFirstSeenMs = 0L;
                    startedMs = now; // fresh timeout for the next page, same as the deleted class did
                    client.gameMode.handleContainerInput(screen.getMenu().containerId, i, 0, ContainerInput.PICKUP, player);
                    LOGGER.info("[MaskSwap] No Phoenix on pets page {}/{} - clicked Next Page (slot {}).",
                            current, total, i);
                    nextActionAtMs = now + stepDelayMs();
                    return;
                }
            }
        }
        LOGGER.info("[MaskSwap] No Phoenix pet found in /pets (page {}/{}) - marked missing this world, no retry.",
                current, total);
        unavailable.add(Target.PHOENIX);
        closeOurMenu(client);
        ModChat.send(CHAT, ModChat.bad("No Phoenix pet found in /pets."));
        abort("not found");
    }

    // ------------------------------------------------------------------ helpers

    private static int findRodSlot(LocalPlayer player) {
        String needle = MaskInvincibilityConfig.getInstance().getPhoenixRodName().trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            needle = "rod";
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String name = ChatObserver.strip(stack.getHoverName()).toLowerCase(Locale.ROOT);
            if (name.contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static void closeOurMenu(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle().getString();
        if (title.toLowerCase(Locale.ROOT).contains("equipment") || PETS_TITLE.matcher(title).matches()) {
            client.screen.onClose();
        }
    }

    private static int stepDelayMs() {
        return MaskInvincibilityConfig.getInstance().getSwapStepDelayMs();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void abort(String why) {
        if (stage != Stage.IDLE) {
            LOGGER.debug("[MaskSwap] Swap ended ({}).", why);
            if (target != null) {
                lastCompletedTarget = target;
                lastCompletedRequester = requester;
                lastCompletedSuccess = SUCCESS_REASONS.contains(why);
                completionSeq++;
            }
        }
        stage = Stage.IDLE;
        target = null;
        requester = "";
        menuFirstSeenMs = 0L;
        previousSlot = -1;
        lastForeignTitle = null;
    }
}
