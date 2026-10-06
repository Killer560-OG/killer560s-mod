package com.killer560.hub.i4sensors;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.i4sensors.I4SensorsConfig.DeathItem;
import com.killer560.hub.maskinvincibility.MaskSwapper;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Sharp Shooter "Auto Mask" - killer560 (2026-09-14): "add an auto swap mask version" + "an order tracker that
 * lets me select whether it goes to phoenix, spirit mask, or bonzo first". Cheat build only
 * ({@link I4SensorsConfig#isAutoMask()}), and only while Auto i4 is on.
 * <p>
 * <b>This class decides WHEN and WHAT; it no longer decides HOW.</b> Rewritten 2026-09-21: it used to carry its
 * own copy of the swap flow (its own {@code /stats} + menu click and a {@code /pets} walk), which meant two
 * independent things in this mod could send {@code /stats} without either one seeing the other's sends - they
 * could race, double-send, and get him muted. The single mechanism now lives in
 * {@link MaskSwapper}: this asks for a target and {@code MaskSwapper} owns the command, the menu, the delay
 * between every step, the one-attempt-no-retry rule and the {@link MaskSwapper#MIN_COMMAND_GAP_MS} floor that
 * both callers share. A {@code false} from {@link MaskSwapper#request} is a normal answer and is never retried.
 * <p>
 * <b>Order</b> - killer560 (2026-09-21): "the mask swap also will always prioritize the phoenix first then
 * going to the other mask by using the /stats as well." Phoenix is pinned to the front of the order this class
 * passes in ({@link #swapOrder}); Mask Invincibility's proc timers keep their own Spirit, Phoenix, Bonzo
 * default. {@link MaskSwapper#pickTarget} takes the order from the caller exactly so the two can differ.
 * <p>
 * <b>Timing</b> - on the proc itself (killer560, 2026-10-06: "auto mask swap for i4 just needs to swap after it
 * procs so no real timing needed"). NoammAddons 1.2.9 still swaps on a schedule (70/140 ticks after Goldor's line, its
 * leap at 203); this one does not, so only its anchor fix carried over (the prefire window). Until then it swapped at
 * NoammAddons' fixed 174/244 ticks after Storm's death,
 * which SkyBlock 0.27.2's faster Goldor transition (Storm's death to Goldor's line ~3 s instead of 5.2 s) left ~2 s
 * late. Now a pop line (below) while he is on the device, after Storm's death this world and before the device
 * completes, asks for the next item in the order that has not popped. A proc that lands inside the Machine Gun
 * Shortbow's Rapid Fire window waits until it ends (killer560: "not swap until it ends"), and so does one that lands
 * while a container menu is open or while a swap is already running.
 * <p>
 * <b>Pops</b> (order advance) - real chat lines from Noamm MaskTimers.kt / Odin (Spirit's also on the wiki):
 * "Your [⚚ ]Bonzo's Mask saved your life!", "Second Wind Activated! Your Spirit Mask saved your life!",
 * "Your Phoenix Pet saved you from certain death!". An item counts as used while inside its cooldown: Bonzo
 * from the worn mask's own "Cooldown: Ns" lore line (Noamm's method, 180s fallback), Spirit 30s (wiki), Phoenix
 * 60s (wiki). Cleared on world change. "Already on your head" and "not in your inventory this world" are
 * {@code MaskSwapper}'s business, not tracked twice here.
 */
public final class I4AutoMask {

    /** The label {@link MaskSwapper} logs and reports this caller under. */
    private static final String REQUESTER = "Auto i4";

    private static final Pattern BONZO_POP = Pattern.compile("^Your (?:.+ )?Bonzo's Mask saved your life!");
    private static final Pattern SPIRIT_POP = Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!");
    private static final Pattern PHOENIX_POP = Pattern.compile("^Your Phoenix Pet saved you from certain death!");
    private static final Pattern LORE_COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s$");

    private static final Map<DeathItem, Long> usedUntilMs = new EnumMap<>(DeathItem.class);

    private static long timelineStormAtMs = 0L;
    /** A proc waiting for its swap (cleared once the swap is asked for, or the timeline ends). */
    private static String pendingProc = null;
    /** See {@link #onCooldown} - at most one rod throw per Storm-death timeline. */
    private static boolean phoenixRequestedThisTimeline = false;
    private static Object lastLevel = null;

    private I4AutoMask() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("I4AutoMask", client -> tick()));
        ChatObserver.subscribe(I4AutoMask::onChat);
    }

    /** A swap is in progress - Auto i4 doesn't hotbar-swap meanwhile. Now the shared swapper's state. */
    static boolean isBusy() {
        return MaskSwapper.isBusy();
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
        // The swap itself: next tick, after the gates in tick(). Only in an i4 timeline (Storm's death seen).
        if (I4SensorsFeature.stormDeathAtMs() > 0) {
            pendingProc = popped.name().toLowerCase(java.util.Locale.ROOT) + " proc";
        }
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

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    /**
     * The order this feature hands {@link MaskSwapper#pickTarget}: Phoenix, then whichever mask the Order
     * setting puts first. Phoenix is re-pinned here rather than trusted from the config, so a hand-edited
     * {@code maskOrderIndex} still cannot demote it.
     */
    static List<MaskSwapper.Target> swapOrder(List<DeathItem> configured) {
        List<MaskSwapper.Target> order = new ArrayList<>(3);
        order.add(MaskSwapper.Target.PHOENIX);
        for (DeathItem item : configured) {
            MaskSwapper.Target target = target(item);
            if (target != MaskSwapper.Target.PHOENIX) {
                order.add(target);
            }
        }
        return List.copyOf(order);
    }

    /** True when this feature considers the target spent. "Already worn"/"missing" is MaskSwapper's own test. */
    private static boolean onCooldown(MaskSwapper.Target target, long now) {
        DeathItem item = deathItem(target);
        if (usedUntilMs.getOrDefault(item, 0L) > now) {
            return true;
        }
        // MaskSwapper can see a mask already on your head, but nothing client-side can see whether the Phoenix
        // pet is already summoned - the old /pets walk read that off the pet's lore, and that route is gone.
        // So the rod goes out at most once per Storm-death timeline; without this a second proc would throw it
        // again.
        return target == MaskSwapper.Target.PHOENIX && phoenixRequestedThisTimeline;
    }

    private static MaskSwapper.Target target(DeathItem item) {
        return switch (item) {
            case BONZO -> MaskSwapper.Target.BONZO;
            case SPIRIT -> MaskSwapper.Target.SPIRIT;
            case PHOENIX -> MaskSwapper.Target.PHOENIX;
        };
    }

    private static DeathItem deathItem(MaskSwapper.Target target) {
        return switch (target) {
            case BONZO -> DeathItem.BONZO;
            case SPIRIT -> DeathItem.SPIRIT;
            case PHOENIX -> DeathItem.PHOENIX;
        };
    }

    // ------------------------------------------------------------------
    // Tick - a proc's swap, once nothing blocks it. The swap itself belongs to MaskSwapper.
    // ------------------------------------------------------------------

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            usedUntilMs.clear();
            phoenixRequestedThisTimeline = false;
            pendingProc = null;
            lastLevel = client.level;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        long stormAt = I4SensorsFeature.stormDeathAtMs();
        if (stormAt != timelineStormAtMs) {
            timelineStormAtMs = stormAt;
            phoenixRequestedThisTimeline = false;
        }
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        if (!cfg.isAutoMask() || !cfg.isAutoI4Enabled() || stormAt <= 0) {
            pendingProc = null;
            return;
        }
        String point = pendingProc;
        if (point == null) {
            return;
        }
        // A swap already running is a wait, not a skip: MaskSwapper.request would refuse, and refusals are
        // never retried, so burning the swap point on one would silently lose it.
        String deferReason = AutoI4Feature.isAbilityHoldActive() ? "Rapid Fire still active"
                : MaskSwapper.isBusy() ? "another swap is already running"
                : ActionGate.containerScreenOpen(client)
                        ? "a container menu is open (\"" + McCompat.screen(client).getTitle().getString() + "\")" : null;
        if (!I4SensorsFeature.isOnDevice(player.position()) || AutoI4Feature.isDeviceCompleted()) {
            pendingProc = null;
            return;
        }
        if (deferReason != null) {
            return; // pendingProc stays: the swap waits for the hold / swap / menu to end
        }
        pendingProc = null;
        startSwap(player, cfg, point);
    }

    /** Picks the target and hands it over. Exactly one {@code request} per proc, refusal included. */
    private static void startSwap(LocalPlayer player, I4SensorsConfig cfg, String point) {
        long now = System.currentTimeMillis();
        List<MaskSwapper.Target> order = swapOrder(cfg.getMaskOrder());
        MaskSwapper.Target target = MaskSwapper.pickTarget(order, t -> onCooldown(t, now));
        if (target == null) {
            return;
        }
        boolean started = MaskSwapper.request(target, REQUESTER);
        if (started && target == MaskSwapper.Target.PHOENIX) {
            phoenixRequestedThisTimeline = true;
        }
    }
}
