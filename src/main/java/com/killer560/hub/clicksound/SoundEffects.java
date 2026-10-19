package com.killer560.hub.clicksound;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.slf4j.Logger;

/**
 * The Sound Effects tab's replace step (killer560, 2026-10-18). {@link ClickSounds#replace} hands every game sound that is
 * not the button click here; if it is an enabled {@link SoundEffect} with a choice other than Default, the chosen sound
 * is played where the game's one was - same position, pitch and volume - instead. Called from the same
 * {@code SoundManager.play} hook as the click, so it covers the same sounds on every screen and in the world.
 */
public final class SoundEffects {

    private static final Logger LOGGER = ModLog.get("killer560smod-soundeffects");

    private static volatile int replaced;
    private static volatile String lastEffect = "";

    private SoundEffects() {
    }

    /** The instance to play for {@code in}: the chosen effect sound, or {@code in} itself. */
    static SoundInstance replace(SoundInstance in) {
        SoundEffectConfig cfg = SoundEffectConfig.getInstance();
        if (!cfg.anyOn()) {
            return in;
        }
        SoundEffect effect = SoundEffect.of(in.getIdentifier());
        if (effect == null || !cfg.isOn(effect)) {
            return in;
        }
        String choice = cfg.getChoice(effect);
        if (ClickSounds.DEFAULT_ID.equals(choice)) {
            return in;
        }
        try {
            SoundInstance made = ClickSounds.make(choice, in.getVolume(), in);
            if (made == null) {
                return in;
            }
            replaced++;
            lastEffect = effect.key;
            return made;
        } catch (RuntimeException e) {
            LOGGER.warn("[SoundEffects] could not play the {} sound, using the game's", effect.key, e);
            return in;
        }
    }

    /** Game sounds replaced by an effect (testkit evidence). */
    public static int replaced() {
        return replaced;
    }

    /** The key of the last effect replaced, "" before any (testkit evidence). */
    public static String lastEffect() {
        return lastEffect;
    }
}
