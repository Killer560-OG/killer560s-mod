package com.killer560.hub.maskinvincibility;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Mask Keybinds (legit; killer560, 2026-11-01: "if you are in /stats menu you can set a keybind to equip a mask. Itll
 * choose spirit first if spirit is on cooldown itll go to bonzo ... It should be for legit. Make an option for dungeons
 * only toggle and one for boss only toggle as well. For which it goes for just have it be spirit first or bonzo first").
 * <p>
 * Only while YOU have the {@code /stats} menu ("Your Equipment and Stats") open: one press of the bound key is one click
 * on a mask in your inventory half of that menu, which is how that menu equips a helmet (the same single click Auto
 * Mask's {@code MaskSwapper} sends). It never sends a command, never opens or closes a menu and never clicks twice for a
 * press. The mask is the first of Spirit/Bonzo (or Bonzo/Spirit) that is not worn already, not on its cooldown from its
 * last proc ({@link MaskInvincibilityFeature#onCooldown}) and is in the inventory.
 */
public final class MaskKeybind {

    private static final Logger LOGGER = ModLog.get("killer560smod-maskkeybind");
    private static final String CHAT = "Mask Keybinds";

    private static int clicks = 0;
    private static String lastDecision = "";

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
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
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
        List<MaskSwapper.Target> order = cfg.isMaskKeyBonzoFirst()
                ? List.of(MaskSwapper.Target.BONZO, MaskSwapper.Target.SPIRIT)
                : List.of(MaskSwapper.Target.SPIRIT, MaskSwapper.Target.BONZO);
        String worn = idOf(player.getItemBySlot(EquipmentSlot.HEAD));
        List<Slot> slots = menu.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
        for (MaskSwapper.Target t : order) {
            if (worn.contains(t.skyblockId()) || MaskInvincibilityFeature.onCooldown(t)) {
                continue;
            }
            for (int i = containerSlots; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (!idOf(slot.getItem()).contains(t.skyblockId())) {
                    continue;
                }
                client.gameMode.handleContainerInput(menu.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, player);
                clicks++;
                decide("equip " + t.label());
                ModChat.log(CHAT, ModChat.text("Equipped "), ModChat.value(t.label()));
                return true;
            }
        }
        decide("nothing to equip");
        ModChat.send(CHAT, ModChat.bad("No mask to equip"),
                ModChat.dim(" (worn, on cooldown, or not in your inventory)"));
        return true;
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
