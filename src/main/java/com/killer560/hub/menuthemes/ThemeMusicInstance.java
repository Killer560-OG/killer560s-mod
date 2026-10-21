package com.killer560.hub.menuthemes;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * One theme's music while the main menu is open: looping, not positional, on the master channel (so the game's Music
 * slider, which many Skyblock players keep at 0, does not silence a track he switched on; Theme Music Volume is its own
 * slider). Tickable, so a volume change is heard at once and {@link #finish} ends it on the next engine tick.
 * <p>A bundled track is a {@code killer560smod:theme_music_*} event with {@code "stream": true} in sounds.json: the engine
 * streams and loops it exactly as it does vanilla's music.
 */
class ThemeMusicInstance extends AbstractSoundInstance implements TickableSoundInstance {

    private volatile boolean stopped;

    ThemeMusicInstance(Identifier event) {
        super(event, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.volume = ThemeMusic.gain();
    }

    /** Starts even at volume 0 (a muted game, or the slider at 0%), so the track keeps its place and the volume can come
     *  back up without a restart. Vanilla's music does the same. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void tick() {
        this.volume = ThemeMusic.gain();
    }

    /** The volume this instance asks for, before the engine's own scaling ({@code getVolume} needs a resolved sound). */
    float rawVolume() {
        return volume;
    }

    /** Ends the track: the engine drops a stopped tickable on its next tick. */
    void finish() {
        stopped = true;
    }
}
