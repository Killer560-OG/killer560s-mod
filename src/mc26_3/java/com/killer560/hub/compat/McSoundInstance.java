package com.killer560.hub.compat;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Base for the mod's own sound instances that supply their own sound (an imported file) instead of a registered event.
 * 26.3 renamed {@code SoundInstance.resolve(SoundManager)} to {@code getOrResolve(SoundManager)} (and caches the result
 * in {@code soundEvent}, read back by {@code getSoundEvent()}), so an override cannot be written once in src/main: a
 * subclass overrides {@link #resolveCustom} instead, and returns null to fall back to the vanilla lookup.
 * Same public/protected signatures in every version's copy.
 *
 * <p>26.3 copy: overrides {@code getOrResolve} and records the result where vanilla does.
 */
public abstract class McSoundInstance extends AbstractSoundInstance {
    protected McSoundInstance(Identifier id, SoundSource source, RandomSource random) {
        super(id, source, random);
    }

    /** The sound events to play, or null for vanilla's own lookup of the identifier. */
    protected WeighedSoundEvents resolveCustom(SoundManager soundManager) {
        return null;
    }

    /** Resolves any instance (vanilla's or ours) so its Sound, volume and pitch can be read. */
    public static WeighedSoundEvents resolve(SoundInstance instance, SoundManager soundManager) {
        return instance.getOrResolve(soundManager);
    }

    @Override
    public WeighedSoundEvents getOrResolve(SoundManager soundManager) {
        WeighedSoundEvents custom = resolveCustom(soundManager);
        if (custom == null) {
            return super.getOrResolve(soundManager);
        }
        this.soundEvent = custom;
        return custom;
    }
}
