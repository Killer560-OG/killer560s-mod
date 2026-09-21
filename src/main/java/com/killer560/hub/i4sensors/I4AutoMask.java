package com.killer560.hub.i4sensors;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <b>Timing</b> - NoammAddons AutoI4.kt (origin/26.1.2), counted from Storm's death line: rod 174, mask 244,
 * leap 307. Its {@code when} picks the FIRST matching branch, so with Auto Rod on the mask swaps at 244 and
 * with Auto Rod off (this mod has no rod swap) it swaps at 174. Here both 174 and 244 are swap points: each
 * one asks for the first item in the order that hasn't popped (and isn't already on), so a pop between 174 and
 * 244 gets the next item on at 244. Like Noamm, a swap point is skipped when the player isn't on the device,
 * and nothing runs after the device completes or past tick 307. A swap point that lands inside the Machine Gun
 * Shortbow's Rapid Fire window waits until it ends (killer560: "not swap until it ends"), and so does one that
 * lands while a container menu is open or while a swap is already running. Ticks are CLIENT ticks since the
 * Storm line ({@link I4SensorsFeature#ticksSinceStormDeath()}) - Noamm counts server ticks; identical without lag.
 * <p>
 * <b>Pops</b> (order advance) - real chat lines from Noamm MaskTimers.kt / Odin (Spirit's also on the wiki):
 * "Your [⚚ ]Bonzo's Mask saved your life!", "Second Wind Activated! Your Spirit Mask saved your life!",
 * "Your Phoenix Pet saved you from certain death!". An item counts as used while inside its cooldown: Bonzo
 * from the worn mask's own "Cooldown: Ns" lore line (Noamm's method, 180s fallback), Spirit 30s (wiki), Phoenix
 * 60s (wiki). Cleared on world change. "Already on your head" and "not in your inventory this world" are
 * {@code MaskSwapper}'s business, not tracked twice here.
 */
public final class I4AutoMask {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoi4");
    private static final String TAG = "[AutoI4]";
    /** The label {@link MaskSwapper} logs and reports this caller under. */
    private static final String REQUESTER = "Auto i4";

    private static final int MASK_TICK_FIRST = 174;
    private static final int MASK_TICK_SECOND = 244;
    private static final int LEAP_TICK = 307;

    private static final Pattern BONZO_POP = Pattern.compile("^Your (?:.+ )?Bonzo's Mask saved your life!");
    private static final Pattern SPIRIT_POP = Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!");
    private static final Pattern PHOENIX_POP = Pattern.compile("^Your Phoenix Pet saved you from certain death!");
    private static final Pattern LORE_COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s$");

    private static final Map<DeathItem, Long> usedUntilMs = new EnumMap<>(DeathItem.class);

    private static long timelineStormAtMs = 0L;
    private static boolean firstPointDone = false;
    private static boolean secondPointDone = false;
    private static String deferredPoint = null;
    private static String lastDeferReason = null;
    /** See {@link #onCooldown} - at most one rod throw per Storm-death timeline. */
    private static boolean phoenixRequestedThisTimeline = false;
    private static Object lastLevel = null;

    private I4AutoMask() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
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
        List<MaskSwapper.Target> order = swapOrder(I4SensorsConfig.getInstance().getMaskOrder());
        MaskSwapper.Target next = MaskSwapper.pickTarget(order, t -> onCooldown(t, System.currentTimeMillis()));
        LOGGER.info("{} {} Auto Mask: {} POPPED (\"{}\") - counted as used for {}s. Order {} -> next is {}.", TAG,
                I4SensorsFeature.clock(), popped.label, plain, cooldownMs / 1000, label(order),
                next == null ? "nothing (all used/missing)" : next.label());
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
        // So the rod goes out at most once per Storm-death timeline; without this the second swap point would
        // throw it again 3.5s after the first.
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

    /** Short form ("Phoenix > Spirit > Bonzo") for the logs, matching the tab's Order button. */
    static String label(List<MaskSwapper.Target> order) {
        StringBuilder sb = new StringBuilder();
        for (MaskSwapper.Target target : order) {
            sb.append(sb.length() == 0 ? "" : " > ").append(deathItem(target).label);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tick - the timeline. The swap itself belongs to MaskSwapper.
    // ------------------------------------------------------------------

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            if (lastLevel != null && !usedUntilMs.isEmpty()) {
                LOGGER.info("{} World changed - Auto Mask pop tracking cleared.", TAG);
            }
            usedUntilMs.clear();
            phoenixRequestedThisTimeline = false;
            deferredPoint = null;
            lastLevel = client.level;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        long stormAt = I4SensorsFeature.stormDeathAtMs();
        if (stormAt != timelineStormAtMs) {
            timelineStormAtMs = stormAt;
            firstPointDone = false;
            secondPointDone = false;
            deferredPoint = null;
            phoenixRequestedThisTimeline = false;
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
        // A swap already running is a wait, not a skip: MaskSwapper.request would refuse, and refusals are
        // never retried, so burning the swap point on one would silently lose it.
        String deferReason = AutoI4Feature.isAbilityHoldActive() ? "Rapid Fire still active"
                : MaskSwapper.isBusy() ? "another swap is already running"
                : ActionGate.containerScreenOpen(client)
                        ? "a container menu is open (\"" + client.screen.getTitle().getString() + "\")" : null;
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
        startSwap(player, cfg, point);
    }

    /** Picks the target and hands it over. Exactly one {@code request} per swap point, refusal included. */
    private static void startSwap(LocalPlayer player, I4SensorsConfig cfg, String point) {
        long now = System.currentTimeMillis();
        List<MaskSwapper.Target> order = swapOrder(cfg.getMaskOrder());
        MaskSwapper.Target target = MaskSwapper.pickTarget(order, t -> onCooldown(t, now));
        if (target == null) {
            LOGGER.info("{} {} Auto Mask: {} - nothing in order {} is available (used, missing or already on).",
                    TAG, I4SensorsFeature.clock(), point, label(order));
            return;
        }
        String helmet = I4SensorsFeature.skyblockId(player.getItemBySlot(EquipmentSlot.HEAD));
        boolean started = MaskSwapper.request(target, REQUESTER);
        if (started && target == MaskSwapper.Target.PHOENIX) {
            phoenixRequestedThisTimeline = true;
        }
        LOGGER.info("{} {} Auto Mask: {} - {} {} (order {}, helmet {}).", TAG, I4SensorsFeature.clock(), point,
                started ? "handed a swap to" : "MaskSwapper refused a swap to", target.label(), label(order),
                helmet.isEmpty() ? "none" : helmet);
    }
}
