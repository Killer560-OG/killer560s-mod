package com.killer560.hub.powdermining;

import com.killer560.hub.dungeonalerts.DungeonAlertsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * Powder Break Sounds (killer560, 2026-11-03: "add custom sounds for blocks broken while powder mining or options to
 * mute them entirely"). On Hypixel the SERVER breaks a mined block (mining fatigue), so its break sound reaches the client
 * as the server's level event 2001 or a sound packet, not as a client-side break of his, and the hit sounds while he digs
 * come from {@code ClientLevel.addBreakingBlockEffects}. All of them pass {@code SoundManager.play}, where
 * {@code ClickSounds.replace} calls this: a block break or hit sound within {@link #RANGE} blocks of him while powder
 * mining is dropped (Mute) or, for a break, swapped for the chosen sound (Custom). The break particles stay.
 */
public final class PowderBreakSound {

    private static final double RANGE = 6.0;

    private PowderBreakSound() {
    }

    /** Every sound handed to SoundManager.play: the same instance, a replacement, or null to drop it. */
    public static SoundInstance replace(SoundInstance in) {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (!cfg.isBreakSound() || in.isRelative()) {
            return in;
        }
        Identifier id = in.getIdentifier();
        String path = id == null ? "" : id.getPath();
        boolean isBreak = path.endsWith(".break");
        if (!path.startsWith("block.") || !(isBreak || path.endsWith(".hit")) || !PowderMining.activeRecently()) {
            return in;
        }
        Player p = Minecraft.getInstance().player;
        if (p == null || p.distanceToSqr(in.getX(), in.getY(), in.getZ()) > RANGE * RANGE) {
            return in;
        }
        return isBreak && !cfg.isBreakSoundMute() ? SimpleSoundInstance.forUI(cfg.getBreakSoundChoice().sound(),
                cfg.getBreakSoundPitch(), cfg.getBreakSoundVolume()) : null;
    }

    /** The Test button. */
    public static void play(PowderMiningConfig cfg) {
        DungeonAlertsFeature.playSound(cfg.getBreakSoundChoice().sound(), cfg.getBreakSoundVolume(),
                cfg.getBreakSoundPitch());
    }
}
