package com.killer560.hub.maskinvincibility;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mask Keybinds (legit; killer560, 2026-11-01: "if you are in /stats menu you can set a keybind to equip a mask. Itll
 * choose spirit first if spirit is on cooldown itll go to bonzo ... It should be for legit. Make an option for dungeons
 * only toggle and one for boss only toggle as well. For which it goes for just have it be spirit first or bonzo first").
 * <p>
 * Only while YOU have the {@code /stats} menu ("Your Equipment and Stats") open: one press of the bound key is one click
 * on a mask in your inventory half of that menu, which is how that menu equips a helmet (the same single click Auto
 * Mask's {@code MaskSwapper} sends; the worn helmet goes back into the inventory). It never sends a command, never
 * opens or closes a menu and never clicks twice for a press. The click goes through {@link ActionGate} like every menu
 * click the mod sends: a press the gate cannot take this tick waits for the next one (up to {@link #PENDING_TICKS}).
 * <p>
 * Every press reads the menu afresh. The mask is the first of Spirit/Bonzo (or Bonzo/Spirit) that is in the inventory
 * half of the menu right now and not on its proc cooldown ({@link MaskInvincibilityFeature#onCooldown}). A mask that is
 * there but on cooldown is the ONLY refusal, said once in chat ("Spirit Mask on cooldown: 12s"); killer560, 2026-11-02:
 * "it should only prevent that swap if it is on cooldown".
 * <p>
 * The worn helmet is deliberately NOT read ({@code player.getItemBySlot(HEAD)}). Until 2026-11-02 a mask counted as
 * "worn already" from that read, and his report was that alternating presses worked with both masks in the inventory
 * but stopped once one was on his head: the inventory half of the open menu is refreshed after each swap (that is what
 * makes the both-in-inventory case alternate), the helmet read evidently is not while /stats is open, so a stale helmet
 * hid the mask that had just come off. A mask in the inventory half is by definition not the one worn.
 */
public final class MaskKeybind {

    private static final Logger LOGGER = ModLog.get("killer560smod-maskkeybind");
    private static final String CHAT = "Mask Keybinds";
    /** How many ticks a press may wait for the gate before it is dropped. */
    static final int PENDING_TICKS = 10;

    private static int clicks = 0;
    private static String lastDecision = "";
    /** A press waiting for a gate tick: the screen it was pressed in, and ticks left. */
    private static AbstractContainerScreen<?> pendingScreen = null;
    private static int pendingTicks = 0;

    private MaskKeybind() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.allowKeyPress(screen).register(
                        (s, event) -> !onKey(s, com.killer560.hub.compat.McInput.key(event)));
                // A mouse-button Mask Keybind (2026-11-02): only a click whose button IS the bind is taken.
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                        !onKey(s, KeyUtil.codeForMouseButton(com.killer560.hub.compat.McInput.button(event))));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MaskKeybind.tick", MaskKeybind::tick));
    }

    /** True for Hypixel's /stats menu, "Your Equipment and Stats". */
    static boolean isStatsMenu(AbstractContainerScreen<?> screen) {
        String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("equipment");
    }

    /** @return true when the key was this feature's and the press must not reach the menu. */
    static boolean onKey(Object screen, int key) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (key == KeyUtil.NONE || key != cfg.getMaskKey() || !cfg.isMaskKeyEnabled()
                || !(screen instanceof AbstractContainerScreen<?> menu) || !isStatsMenu(menu)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (cfg.isMaskKeyDungeonsOnly() && !DungeonState.isInDungeon()) {
            decide("blocked: Dungeons Only");
            return true;
        }
        if (cfg.isMaskKeyBossOnly() && !LiveMapFeature.isInBoss()) {
            decide("blocked: Boss Only");
            return true;
        }
        pendingScreen = menu;
        pendingTicks = PENDING_TICKS;
        attempt(client);
        return true;
    }

    private static void tick(Minecraft client) {
        if (pendingScreen == null) {
            return;
        }
        if (McCompat.screen(client) != pendingScreen) {
            clearPending("menu closed before the click");
            return;
        }
        if (--pendingTicks < 0) {
            clearPending("no free tick");
            return;
        }
        attempt(client);
    }

    /** Reads the open menu now and clicks the mask to put on, if the gate gives this tick. */
    private static void attempt(Minecraft client) {
        AbstractContainerScreen<?> menu = pendingScreen;
        LocalPlayer player = client.player;
        if (menu == null || player == null || client.gameMode == null || McCompat.screen(client) != menu) {
            clearPending("menu gone");
            return;
        }
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        List<MaskSwapper.Target> order = cfg.isMaskKeyBonzoFirst()
                ? List.of(MaskSwapper.Target.BONZO, MaskSwapper.Target.SPIRIT)
                : List.of(MaskSwapper.Target.SPIRIT, MaskSwapper.Target.BONZO);
        List<Slot> slots = menu.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
        List<MaskSwapper.Target> onCooldown = new ArrayList<>();
        for (MaskSwapper.Target t : order) {
            Slot slot = find(slots, containerSlots, t);
            if (slot == null) {
                continue; // worn (it is not in the inventory half) or not owned
            }
            if (MaskInvincibilityFeature.onCooldown(t)) {
                onCooldown.add(t);
                continue;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.MASK_SWAP_MENU, menu)) {
                return; // pending: the next tick reads the menu again
            }
            client.gameMode.handleContainerInput(menu.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, player);
            clicks++;
            pendingScreen = null;
            decide("equip " + t.label());
            ModChat.log(CHAT, ModChat.text("Equipped "), ModChat.value(t.label()));
            return;
        }
        pendingScreen = null;
        if (!onCooldown.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (MaskSwapper.Target t : onCooldown) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(t.label()).append(" on cooldown: ").append(MaskInvincibilityFeature.cooldownSecondsLeft(t))
                        .append('s');
            }
            decide("cooldown: " + sb);
            ModChat.send(CHAT, ModChat.bad(sb.toString()));
            return;
        }
        decide("nothing to equip");
        ModChat.send(CHAT, ModChat.bad("No mask to swap to"), ModChat.dim(" (none in your inventory)"));
    }

    private static Slot find(List<Slot> slots, int from, MaskSwapper.Target t) {
        for (int i = from; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (idOf(slot.getItem()).contains(t.skyblockId())) {
                return slot;
            }
        }
        return null;
    }

    private static void clearPending(String why) {
        pendingScreen = null;
        decide("dropped: " + why);
    }

    private static String idOf(net.minecraft.world.item.ItemStack stack) {
        String id = ItemIdentity.skyblockId(stack);
        return id == null ? "" : id.toUpperCase(Locale.ROOT);
    }

    private static void decide(String what) {
        lastDecision = what;
        LOGGER.info("[MaskKeybind] {}", what);
    }

    /** For the testkit: clicks sent, and the last decision ("equip Spirit Mask", "blocked: Boss Only", ...). */
    public static int clicks() {
        return clicks;
    }

    public static String lastDecision() {
        return lastDecision;
    }
}
