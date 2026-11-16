package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.PingTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * Auto Shovel Swap (cheat build only, killer560 2026-10-25: "switches to whichever shovel type the next dig needs"):
 * while you play Fruit Digging, puts your Carnival Shovel in the mode the Fruit Digging Solver wants for the next dig
 * (Mines, Anchor or Treasure). A hotbar slot already holding a shovel in that mode is selected, as a number key would;
 * otherwise, holding the Carnival Shovel, it taps Use, which steps the shovel to its next mode (the wiki: "Dowsing Modes
 * can be cycled through by right-clicking"), until the mode is right - at most once per Swap Delay, and it gives up on a
 * pick after three taps that change nothing. After a Rum the shovel's ability is off for the next dig, so it leaves it.
 * One action a tick through {@link ActionGate}. Automation: can get you banned; keep it off on the main server.
 *
 * <p>The mode is read off the held item's model component (carnival_shovel_iron Mines, _diamond Anchor, _gold Treasure;
 * confirmed from the Hypixel pack's model names in PackDisabler), else its name, else its base item.
 */
public final class AutoShovelSwap {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final String NAME = "Auto Shovel Swap";
    private static long nextTick;
    private static String wantedFor;
    private static int fruitlessTaps;
    private static String modeBeforeTap;
    private static long confirmByTick;
    private static long tapTick;
    /** The mode the server's last shovel click named, while the item model has not caught up, and when it came. */
    private static String heardMode;
    private static long heardTick;
    private static int heard;
    /** A heard mode stands in for the model this long; the model then wins. */
    static final int MODEL_WAIT_TICKS = 60;
    private static long gaveUpTick;
    private static int swaps;
    private static int lostTaps;
    /** After three taps that changed nothing it rests this long, then tries again (it used to stop for the whole pick). */
    static final int RETRY_TICKS = 40;

    private AutoShovelSwap() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalInput.stopRule(NAME, () -> AutoFruitConfig.swap().isEnabled() && FruitDigging.active());
        int bit = FeatureBits.register(NAME, () -> AutoFruitConfig.swap().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoShovelSwap.tick", bit, AutoShovelSwap::tick));
        // A tap left unconfirmed in one game must not count as fruitless in the next (it then never tapped again there).
        CarnivalState.onGameStart(g -> {
            wantedFor = null;
            modeBeforeTap = null;
            heardMode = null;
            fruitlessTaps = 0;
            nextTick = 0;
        });
    }

    /** The mode one tap steps {@code mode} to: Mines, Anchor, Treasure, Mines. */
    static String next(String mode) {
        return "Mines".equals(mode) ? "Anchor" : "Anchor".equals(mode) ? "Treasure" : "Mines";
    }

    /** The dowsing mode a Carnival Shovel is in ("Mines", "Anchor", "Treasure"), or null when it is not one. Hypixel's one
     *  Carnival Shovel is always an iron-shovel item; its mode shows only in the item MODEL component (hypixel_skyblock
     *  item/uncategorized/carnival_shovel_iron | _gold | _diamond, PackDisabler's dynamicModel), so the base item says
     *  nothing (11-11: it read every mode as Mines and Prevent Wrong Shovel refused a right Anchor dig). */
    public static String modeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        net.minecraft.resources.Identifier model = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        return modeOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), model == null ? null : model.getPath(),
                ChatObserver.stripCodes(stack.getHoverName().getString()));
    }

    /** {@link #modeOf(ItemStack)} from its parts: the base item's path, its model path (or null) and plain name (or null). */
    public static String modeOf(String itemPath, String modelPath, String name) {
        if (modelPath != null) {
            String last = modelPath.substring(modelPath.lastIndexOf('/') + 1);
            if (last.startsWith("carnival_shovel_")) {
                return switch (last) {
                    case "carnival_shovel_iron" -> "Mines";
                    case "carnival_shovel_diamond" -> "Anchor";
                    case "carnival_shovel_gold" -> "Treasure";
                    default -> null;
                };
            }
        }
        // A Carnival Shovel is one by its model above, or (no model) by a plain name saying so: a vanilla shovel named
        // anything else (his Aspect of the Void is a diamond shovel) is never one (11-14: "it keeps swapping to my aotv").
        if ((modelPath != null && !modelPath.equals(itemPath)) || !itemPath.endsWith("_shovel") || name == null || !name.contains("Carnival Shovel")) {
            return null;
        }
        for (String m : new String[]{"Mines", "Anchor", "Treasure"}) {
            if (name.contains(m)) {
                return m;
            }
        }
        return switch (itemPath) {
            case "iron_shovel" -> "Mines";
            case "diamond_shovel" -> "Anchor";
            case "golden_shovel" -> "Treasure";
            default -> null;
        };
    }

    /** Whether the held shovel is in the mode the solver wants (true when it wants none, or after a Rum). */
    static boolean ready(LocalPlayer player) {
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        if (r == null || FruitDigging.lastFruit() == Fruit.RUM) {
            return true;
        }
        return r.shovel().equals(modeOf(player.getMainHandItem()));
    }

    private static void tick(Minecraft mc) {
        AutoFruitConfig cfg = AutoFruitConfig.swap();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !FruitDigging.active()
                || CarnivalInput.stopped(NAME)) {
            return;
        }
        step(mc, player, cfg.getDelayMs());
    }

    /**
     * One step toward the shovel mode the solver wants: a hotbar select or a Use tap (at most one per {@code delayMs}),
     * nothing when the mode is right. Auto Fruit Digging calls it on the ticks it is not digging, so its swap and its dig
     * never share a tick (GrimAC PacketOrderE / BadPacketsJ: no hotbar or use action in a dig's tick).
     */
    static void step(Minecraft mc, LocalPlayer player, int delayMs) {
        CarnivalInput.watchCorrections(NAME);
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        if (r == null || FruitDigging.lastFruit() == Fruit.RUM) {
            return;
        }
        String want = r.shovel();
        String key = r.label() + "/" + want;
        if (!key.equals(wantedFor)) {
            wantedFor = key;
            fruitlessTaps = 0;
        }
        String held = modeOf(player.getMainHandItem());
        long now = ActionGate.currentTick();
        if (heardMode != null && (heardMode.equals(held) || now - heardTick > MODEL_WAIT_TICKS)) {
            if (!heardMode.equals(held)) {
                // The server clicked but the item never followed (his 11-14 19:07 game): the model is the truth.
                LOGGER.info("[{}] heard {} but the shovel still shows {} after {} ticks", NAME, heardMode, held, MODEL_WAIT_TICKS);
                if (++fruitlessTaps >= 3) {
                    gaveUpTick = now;
                }
            }
            heardMode = null;
        }
        if (want.equals(held)) {
            if (modeBeforeTap != null || heardMode != null) {
                LOGGER.info("[{}] shovel now in {} mode", NAME, held);
                modeBeforeTap = null;
                heardMode = null;
            }
            return;
        }
        if (want.equals(heardMode)) {
            return; // the server already switched (its click said so); the item model follows up to 2 s later
        }
        if (now < nextTick) {
            return;
        }
        // A shovel already in that mode on the hotbar: select it.
        for (int i = 0; i < 9; i++) {
            if (i != player.getInventory().getSelectedSlot() && want.equals(modeOf(player.getInventory().getItem(i)))) {
                if (CarnivalInput.select(mc, player, i)) {
                    swaps++;
                    nextTick = now + ActionGate.delayTicks(delayMs);
                    LOGGER.info("[{}] selected slot {} ({} mode) for {}", NAME, i + 1, want, r.label());
                }
                return;
            }
        }
        if (held == null) {
            return; // not holding a shovel and none on the hotbar in that mode: nothing it can do
        }
        if (modeBeforeTap != null) {
            if (heardMode != null && heardTick > tapTick || next(modeBeforeTap).equals(held)) {
                // The server took the tap: its lever click came (the pitch names the new mode) or the model moved. The
                // next step may go from the heard mode without waiting for the model, which lags 1-2 s: his pincarn6
                // game (23:15:38) timed out on the model alone, tapped again and stepped the shovel twice.
                modeBeforeTap = null;
            } else if (now < confirmByTick) {
                return;
            } else {
                // Neither a click nor a model change by a round trip plus a second: the tap never reached the server
                // (vanilla drops a use while it is destroying a block, or the click was eaten); tap again.
                lostTaps++;
                modeBeforeTap = null;
                LOGGER.info("[{}] tap lost (no click and no model change from the server); tapping again", NAME);
                if (++fruitlessTaps >= 3) {
                    gaveUpTick = now;
                }
            }
        }
        String from = heardMode != null ? heardMode : held;
        if (fruitlessTaps >= 3) {
            if (now - gaveUpTick < RETRY_TICKS) {
                return;
            }
            fruitlessTaps = 0;
        }
        // Vanilla's startUseItem does nothing while a block is being destroyed, so a tap then is lost.
        if (mc.gameMode != null && mc.gameMode.isDestroying()) {
            return;
        }
        if (CarnivalInput.tap(mc.options.keyUse)) {
            swaps++;
            modeBeforeTap = from;
            tapTick = now;
            confirmByTick = now + 20 + 2L * Math.max(0, PingTracker.automationMs()) / 50;
            // Never two taps the same distance apart: the Swap Delay plus a rolled reaction.
            nextTick = now + ActionGate.delayTicks(delayMs) + Human.ticks(2, 0, 8);
            LOGGER.info("[{}] stepping the shovel from {} toward {} for {}", NAME, from, want, r.label());
        }
    }

    /** Hypixel answers each shovel step with a lever click at the player whose pitch is the new mode (1.0 Mines, 8/7
     *  Anchor, 9/7 Treasure; his 11-14 logs), 1-2 s before the item model changes. Render thread, from the sound packet. */
    public static void onSound(net.minecraft.network.protocol.game.ClientboundSoundPacket p) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (modeBeforeTap == null && heardMode == null || player == null || !AutoFruitConfig.swap().isEnabled()
                // By id: an inline sound decodes to a direct holder, which has no registered name.
                || !p.getSound().value().location().equals(net.minecraft.sounds.SoundEvents.LEVER_CLICK.location())
                || player.distanceToSqr(p.getX(), p.getY(), p.getZ()) > 9) {
            return;
        }
        String mode = modeOfPitch(p.getPitch());
        if (mode != null) {
            heardMode = mode;
            heardTick = ActionGate.currentTick();
            heard++;
            LOGGER.info("[{}] server clicked the shovel to {} (pitch {})", NAME, mode, p.getPitch());
        }
    }

    /** The mode a shovel-step click's pitch names, or null. */
    static String modeOfPitch(float pitch) {
        return Math.abs(pitch - 1f) < 0.03f ? "Mines" : Math.abs(pitch - 8f / 7f) < 0.03f ? "Anchor"
                : Math.abs(pitch - 9f / 7f) < 0.03f ? "Treasure" : null;
    }

    /** Harness: shovel-step clicks heard from the server. */
    public static int heard() {
        return heard;
    }

    /** Harness: taps that sent no use packet and were made again. */
    public static int lostTaps() {
        return lostTaps;
    }

    /** Harness: hotbar selects and mode taps made. */
    public static int swaps() {
        return swaps;
    }
}
