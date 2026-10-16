package com.killer560.hub.maskinvincibility;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;

/**
 * Auto Mask's two modes, its swap order and its location gates (killer560, 2026-10-16: "for auto mask have a swap
 * keybind and a full auto mode. It should have an option of swap mask first or swap phoenix first. If the keybind is
 * used then on keybind press it should swap to a mask or swap masks. on auto its just once one gets procced. Also have
 * a dungeons only toggle, a boss only toggle").
 * <p>
 * This decides WHEN and TO WHAT; {@link MaskSwapper} is still the one thing that swaps (the {@code /stats} menu click
 * for a mask, the rod or {@code /pets} for Phoenix), with its one-interaction-per-tick {@code ActionGate} actors, its
 * step delay and its no-retry rule. Cheat build only: everything here is behind
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}, which javac folds to nothing in the legit jar.
 * <ul>
 *   <li><b>Keybind</b>: a press of {@link MaskInvincibilityConfig#getSwapKey()} (no screen open) picks the first usable
 *       target in the order - a mask when none is worn, the other mask when one is, or Phoenix where the order says.</li>
 *   <li><b>Full Auto</b>: {@link #onProc} from Mask Invincibility's own proc detection swaps once per proc. A proc that
 *       lands while a screen is open or another swap is running is kept for {@link #PENDING_TICKS} and started on the
 *       first tick it can be - never under a screen.</li>
 * </ul>
 * Nothing on cooldown, already worn, not owned (Mask Invincibility's Spirit/Bonzo/Phoenix switches) or already summoned
 * is picked; with nothing left it says so in chat once, for that press or that proc.
 */
public final class AutoMask {

    private static final Logger LOGGER = ModLog.get("killer560smod-automask");
    private static final String CHAT = "Auto Mask";
    /** How long a Full Auto proc that could not start at once (screen open, swap running, command floor) is kept. */
    static final int PENDING_TICKS = 100;

    private static final List<MaskSwapper.Target> MASK_FIRST =
            List.of(MaskSwapper.Target.SPIRIT, MaskSwapper.Target.BONZO, MaskSwapper.Target.PHOENIX);
    private static final List<MaskSwapper.Target> PHOENIX_FIRST =
            List.of(MaskSwapper.Target.PHOENIX, MaskSwapper.Target.SPIRIT, MaskSwapper.Target.BONZO);

    private static long tickNo = 0L;
    private static long pendingUntilTick = -1L;
    private static boolean keyWasDown = true;
    /** The last thing Auto Mask decided, for the log and the testkit ("swap Spirit Mask (key)", "blocked: Boss Only"...). */
    private static String lastDecision = "";
    private static long decisionSeq = 0L;

    private AutoMask() {
    }

    /** From {@link MaskInvincibilityFeature#register()}, after {@link MaskSwapper#register()}: the swapper has counted
     *  this START tick already, so a press's first step goes out exactly Swap Step Delay ticks after the press tick. */
    static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoMask", AutoMask::tick));
    }

    /** The order for the current Swap Order setting. */
    public static List<MaskSwapper.Target> order() {
        return MaskInvincibilityConfig.getInstance().getSwapOrder() == MaskInvincibilityConfig.SwapOrder.PHOENIX_FIRST
                ? PHOENIX_FIRST : MASK_FIRST;
    }

    public static String lastDecision() {
        return lastDecision;
    }

    public static long decisionSeq() {
        return decisionSeq;
    }

    /** Null when Dungeons Only / Boss Only allow acting here, otherwise the setting that refuses. */
    public static String gateRefusal() {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();
        if (cfg.isAutoMaskBossOnly() && !(inDungeon && LiveMapFeature.isInBoss())) {
            return "Boss Only";
        }
        if (cfg.isAutoMaskDungeonsOnly() && !inDungeon) {
            return "Dungeons Only";
        }
        return null;
    }

    /** Full Auto: Mask Invincibility saw the worn mask (or Phoenix) proc. */
    static void onProc(String label) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (!cfg.isAutoSwapEnabled() || cfg.getAutoMaskMode() != MaskInvincibilityConfig.AutoMaskMode.FULL_AUTO) {
            return;
        }
        String gate = gateRefusal();
        if (gate != null) {
            decide("blocked: " + gate + " (" + label + " proc)");
            return;
        }
        pendingUntilTick = tickNo + PENDING_TICKS;
        tryPending(Minecraft.getInstance());
    }

    private static void tick(Minecraft client) {
        tickNo++;
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (!cfg.isAutoSwapEnabled() || !cfg.isEnabled() || client.player == null) {
            pendingUntilTick = -1L;
            keyWasDown = true;
            return;
        }
        if (pendingUntilTick >= 0) {
            if (tickNo > pendingUntilTick || cfg.getAutoMaskMode() != MaskInvincibilityConfig.AutoMaskMode.FULL_AUTO) {
                if (tickNo > pendingUntilTick) {
                    decide("dropped: the proc's swap could not start in " + PENDING_TICKS + " ticks");
                }
                pendingUntilTick = -1L;
            } else {
                tryPending(client);
            }
        }
        if (cfg.getAutoMaskMode() != MaskInvincibilityConfig.AutoMaskMode.KEYBIND) {
            keyWasDown = true;
            return;
        }
        // Under a screen the key is counted as held, so a press must start after the screen closed: typing the bound
        // letter in chat, or the press that closed the screen, never swaps.
        boolean down = McCompat.screen(client) != null || KeyUtil.isBindDown(client.getWindow(), cfg.getSwapKey());
        if (down && !keyWasDown && McCompat.screen(client) == null) {
            onKeyPress(client);
        }
        keyWasDown = down;
    }

    private static void onKeyPress(Minecraft client) {
        String gate = gateRefusal();
        if (gate != null) {
            ModChat.send(CHAT, ModChat.bad(gate + " is on - not swapping here."));
            decide("blocked: " + gate + " (key)");
            return;
        }
        if (MaskSwapper.isBusy()) {
            ModChat.send(CHAT, ModChat.bad("A swap is already running."));
            decide("busy (key)");
            return;
        }
        MaskSwapper.Target target = pick();
        if (target == null) {
            nothingAvailable("key");
            return;
        }
        if (MaskSwapper.request(target, CHAT)) {
            decide("swap " + target.label() + " (key)");
        } else {
            ModChat.send(CHAT, ModChat.bad("Couldn't start the swap to " + target.label() + " yet."));
            decide("refused " + target.label() + " (key)");
        }
    }

    private static void tryPending(Minecraft client) {
        if (client.player == null || McCompat.screen(client) != null || MaskSwapper.isBusy()) {
            return; // kept; started on the first tick nothing is in the way
        }
        MaskSwapper.Target target = pick();
        if (target == null) {
            pendingUntilTick = -1L;
            nothingAvailable("proc");
            return;
        }
        if (MaskSwapper.request(target, CHAT)) {
            pendingUntilTick = -1L;
            decide("swap " + target.label() + " (proc)");
        }
        // false (the command floor): kept, asked again next tick - a local check, nothing is sent.
    }

    private static MaskSwapper.Target pick() {
        return MaskSwapper.pickTarget(order(), MaskInvincibilityFeature::unusable);
    }

    private static void nothingAvailable(String why) {
        ModChat.send(CHAT, ModChat.bad("Nothing to swap to - every mask and Phoenix is on cooldown, worn, out or not owned."));
        decide("nothing available (" + why + ")");
    }

    private static void decide(String what) {
        lastDecision = what;
        decisionSeq++;
        LOGGER.info("[AutoMask] {}", what);
    }
}
