package com.killer560.hub.autoleap;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7 boss-fight "Auto Leap Out" - killer560's "auto leap out option, player or to Mel" request. The
 * real trigger chat lines and the F7 boss-arena AABBs below are ported directly from QUOI's own
 * confirmed, compiling {@code AutoLeap.kt} (real F7 pad/relic/pre-Necron-P4 coordinates, real boss
 * dialogue lines), and the actual leap mechanism - right-click the Spirit Leap item to open a
 * container GUI titled "Spirit Leap" listing party members, then click whichever slot's item name
 * contains your target - is read directly from QUOI's real {@code LeapManager.kt}, reusing this
 * codebase's own already-proven container-slot-click pattern from the Terminal Solver
 * ({@code MultiPlayerGameMode.handleContainerInput}) rather than guessing at a new one.
 * <p>
 * <b>Deliberately scoped down from QUOI's own much larger version</b> to only the triggers detectable
 * with chat regexes and plain position checks, matching this mod's "no unconfirmed phase-tracking
 * system, no new packet/entity-position Mixins" caution elsewhere:
 * <ul>
 * <li>i4/"Pre4" device completion (the area right before Necron's P4 starts)
 * <li>Storm's death line
 * <li>Necron's "impressive trick" line (start of the middle/P4 approach)
 * <li>Picking up your relic (slot 8 display name check)
 * <li>The three "pad" leaps (green/yellow/purple) after Storm's crush lines
 * </ul>
 * Skipped: P1's crystal-charge count and the "predev" leap (both need real F7 phase-tracking this mod
 * doesn't have, unlike Odin/QUOI's own dedicated phase trackers - guessing the phase window risks
 * leaping at the wrong moment) and the PY-healer leap (needs real-time entity position tracking off raw
 * movement packets - more mixin/packet-parsing risk than this feature is worth on its own).
 */
public final class AutoLeapFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoleap");

    private static final AABB PRE4_BOX = new AABB(62, 127, 34, 65, 130, 37);
    private static final AABB GREEN_PAD_BOX = new AABB(24, 170, 4, 41, 172, 21);
    private static final AABB YELLOW_PAD_BOX = new AABB(24, 170, 86, 41, 172, 103);
    private static final AABB PURPLE_PAD_BOX = new AABB(95, 165, 86, 123, 172, 103);

    private static final Pattern DEVICE_DONE_REGEX = Pattern.compile("^(\\w+) completed a device! \\((.*?)\\)$");
    private static final Set<String> STORM_CRUSH_MESSAGES = Set.of("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!");
    private static final String STORM_DEATH_MESSAGE = "[BOSS] Storm: I should have known that I stood no chance.";
    private static final String MIDDLE_MESSAGE = "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.";

    private static int oofCount = 0;
    private static boolean pickedUpRelic = false;
    private static long pendingLeapRequestedAtMs = 0;
    private static boolean pendingLeap = false;
    private static boolean wasInDungeon = false;

    private AutoLeapFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void onChatMessage(Component message) {
        AutoLeapConfig cfg = AutoLeapConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isBossPhaseActive() || cfg.getTargetName().isBlank()) {
            return;
        }
        String raw = message.getString();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        if (cfg.isLeapOnI4Device()) {
            Matcher m = DEVICE_DONE_REGEX.matcher(raw);
            if (m.matches() && m.group(1).equals(client.player.getName().getString())
                    && PRE4_BOX.contains(client.player.position())) {
                requestLeap(cfg.getTargetName());
                return;
            }
        }

        if (cfg.isLeapOnStormDeath() && STORM_DEATH_MESSAGE.equals(raw)) {
            requestLeap(cfg.getTargetName());
            return;
        }

        if (cfg.isLeapOnMiddle() && MIDDLE_MESSAGE.equals(raw)) {
            requestLeap(cfg.getTargetName());
            return;
        }

        if (cfg.isLeapOnPads() && STORM_CRUSH_MESSAGES.contains(raw)) {
            oofCount++;
            var pos = client.player.position();
            if (oofCount == 1) {
                if (PURPLE_PAD_BOX.contains(pos) || GREEN_PAD_BOX.contains(pos)) {
                    requestLeap(cfg.getTargetName());
                }
            } else if (oofCount == 2 && YELLOW_PAD_BOX.contains(pos)) {
                requestLeap(cfg.getTargetName());
            }
        }
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            oofCount = 0;
            pickedUpRelic = false;
            pendingLeap = false;
        }
        wasInDungeon = inDungeon;

        AutoLeapConfig cfg = AutoLeapConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.player == null) {
            return;
        }

        if (cfg.isLeapOnRelic() && !pickedUpRelic && DungeonState.isBossPhaseActive() && !cfg.getTargetName().isBlank()) {
            ItemStack relicSlot = client.player.getInventory().getItem(8);
            if (relicSlot.getHoverName().getString().contains("Relic")) {
                pickedUpRelic = true;
                requestLeap(cfg.getTargetName());
            }
        }

        if (!pendingLeap) {
            return;
        }
        if (System.currentTimeMillis() - pendingLeapRequestedAtMs > 3000) {
            LOGGER.info("[AutoLeap] Leap menu never opened - giving up.");
            pendingLeap = false;
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("leap")) {
            return;
        }
        String target = AutoLeapConfig.getInstance().getTargetName().toLowerCase(Locale.ROOT);
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to search
        // screen.getMenu().slots in full, which - same real Hypixel container layout Terminal Solver's
        // own doc comment already confirms - always appends the player's own 36 inventory+hotbar slots
        // after the container's real rows. If the configured target name is typo'd, or the teammate
        // isn't actually listed in this leap menu, the search fell through into the player's OWN
        // inventory and could match an unrelated held item whose name happens to contain the target
        // substring - then PICKUP-clicked it, putting it on the cursor with nothing to place it back down
        // (real item-loss risk mid-boss-fight if the screen then closes). Bounded the same way Terminal
        // Solver already does, to the container's own real slots only.
        java.util.List<Slot> slots = screen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = slots.get(i);
            ItemStack item = slot.getItem();
            if (item.isEmpty()) {
                continue;
            }
            if (item.getHoverName().getString().toLowerCase(Locale.ROOT).contains(target)) {
                client.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 0,
                        ContainerInput.PICKUP, client.player);
                LOGGER.info("[AutoLeap] Clicked leap target \"{}\".", target);
                pendingLeap = false;
                return;
            }
        }
    }

    /** Switches to the Spirit Leap item (found by display name, not a guessed NBT id) and right-clicks
     *  it - the same real "open item -&gt; wait for the container -&gt; click the matching slot" flow
     *  QUOI's own {@code LeapManager} uses, minus its extra queueing/preset machinery this simpler
     *  version doesn't need. */
    private static void requestLeap(String targetName) {
        if (pendingLeap) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }
        int leapSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack item = client.player.getInventory().getItem(i);
            if (!item.isEmpty() && item.getHoverName().getString().contains("Spirit Leap")) {
                leapSlot = i;
                break;
            }
        }
        if (leapSlot < 0) {
            LOGGER.info("[AutoLeap] No Spirit Leap item found in hotbar - can't leap to \"{}\".", targetName);
            return;
        }
        client.player.getInventory().setSelectedSlot(leapSlot);
        client.player.connection.send(new ServerboundSetCarriedItemPacket(leapSlot));
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        pendingLeap = true;
        pendingLeapRequestedAtMs = System.currentTimeMillis();
        LOGGER.info("[AutoLeap] Requested leap to \"{}\".", targetName);
    }
}
