package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.DigAim;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fruit Digging Triggerbot (cheat build only, carnival-1110): while you play Fruit Digging, the moment your crosshair is
 * on the tile the Fruit Digging Solver picks (and the shovel is in its mode, unless the pick needs none), it taps your
 * Attack key once, after Reaction Delay. You aim, it digs; it never turns you and never digs any other tile. A dig goes
 * only while the look the server last heard strikes that tile (GrimAC RotationBreak), and one tap waits for the tile to
 * change (or 1.5 s) before the next. Automation: can get you banned; keep it off on the main server. Off by default.
 */
public final class FruitDiggingTriggerbot {

    static final String NAME = "Fruit Digging Triggerbot";
    private static int onCell = -1;
    private static long onSince;
    private static int pending = -1;
    private static long pendingSince;
    private static int taps;

    private FruitDiggingTriggerbot() {
    }

    public static void register() {
        int bit = FeatureBits.register(NAME, () -> AutoFruitConfig.trigger().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("FruitDiggingTriggerbot.tick", bit,
                FruitDiggingTriggerbot::tick));
    }

    private static void tick(Minecraft mc) {
        AutoFruitConfig cfg = AutoFruitConfig.trigger();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !FruitDigging.active()
                || FruitDigging.gridOrigin() == null || FruitDigging.digs() >= FruitDiggingSolver.MAX_DIGS) {
            onCell = -1;
            return;
        }
        long now = ActionGate.currentTick();
        if (pending >= 0) {
            if (FruitDigging.cell(pending).diggable && now - pendingSince < 30) {
                return;
            }
            pending = -1;
        }
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        HitResult hit = mc.hitResult;
        if (r == null || !FruitDigging.cell(r.cell()).diggable || !(hit instanceof BlockHitResult bh)
                || hit.getType() != HitResult.Type.BLOCK || FruitDigging.cellAt(bh.getBlockPos()) != r.cell()) {
            onCell = -1;
            return;
        }
        if (onCell != r.cell()) {
            onCell = r.cell();
            onSince = now;
        }
        BlockPos pos = bh.getBlockPos();
        if (now - onSince < ActionGate.delayTicks(cfg.getDelayMs()) || !AutoShovelSwap.ready(player)
                || mc.options.keyAttack.isDown() || !DigAim.heardLookStrikes(player, pos)) {
            return;
        }
        if (CarnivalInput.tap(mc.options.keyAttack)) {
            taps++;
            pending = r.cell();
            pendingSince = now;
            ModChat.log(NAME, ModChat.text("Digging " + r.label() + " (" + r.shovel() + ")."));
        }
    }

    /** Harness: dig taps sent. */
    public static int taps() {
        return taps;
    }
}
