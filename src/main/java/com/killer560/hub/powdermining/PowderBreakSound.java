package com.killer560.hub.powdermining;

import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Powder Break Sounds (killer560, 2026-11-03: "add custom sounds for blocks broken while powder mining or options to
 * mute them entirely"). The client plays a block's break sound from level event 2001; the LOCAL player's own breaks reach
 * {@code ClientLevel.levelEvent(Entity, ...)} with himself as the entity, while the server's events for other players
 * come with no entity, so only his own are touched. Mute keeps the break particles; Custom plays the chosen sound.
 */
public final class PowderBreakSound {

    private static final int LEVEL_EVENT_BREAK = 2001;
    private static final AtomicInteger MUTED = new AtomicInteger();
    private static final AtomicInteger CUSTOM = new AtomicInteger();

    private PowderBreakSound() {
    }

    /** Called from the mixin; true cancels vanilla's handling (the particles were already spawned here). */
    public static boolean handle(ClientLevel level, Entity who, int type, BlockPos pos, int data) {
        if (type != LEVEL_EVENT_BREAK || who == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || who != mc.player) {
            return false;
        }
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (!cfg.isBreakSound() || !PowderMining.activeRecently()) {
            return false;
        }
        BlockState state = Block.stateById(data);
        if (state.isAir()) {
            return false;
        }
        level.addDestroyBlockEffect(pos, state);
        if (cfg.isBreakSoundMute()) {
            MUTED.incrementAndGet();
        } else {
            CUSTOM.incrementAndGet();
            play(cfg);
        }
        return true;
    }

    /** The Test button and the Custom break. */
    public static void play(PowderMiningConfig cfg) {
        DungeonAlertsFeature.playSound(cfg.getBreakSoundChoice().sound(), cfg.getBreakSoundVolume(),
                cfg.getBreakSoundPitch());
    }

    /** Testkit: breaks silenced / replaced so far. */
    public static int mutedCount() {
        return MUTED.get();
    }

    public static int customCount() {
        return CUSTOM.get();
    }
}
