package com.killer560.hub.menuthemes;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * One theme's music while the main menu is open: looping, not positional, on the master channel (so the game's Music
 * slider, which many Skyblock players keep at 0, does not silence a track he switched on; Theme Music Volume is its own
 * slider). Tickable, so a volume change is heard at once and {@link #finish} ends it on the next engine tick.
 * <p>Every track is a file (a theme's own loop downloaded by ThemeAssets since themedl-1104, or one imported through
 * {@link ThemeMusicFiles}): it names a streamed {@link Sound} no resource pack has, and the engine's stream request for it
 * is answered from the file; the engine streams and loops it exactly as it does vanilla's music.
 */
class ThemeMusicInstance extends com.killer560.hub.compat.McSoundInstance implements TickableSoundInstance {

    private volatile boolean stopped;
    /** Ticks since a menu track started; its volume ramps up over {@link #FADE_IN_TICKS} so it does not start abruptly. */
    private int ticksPlaying;
    /** Two seconds of ticks. Play Preview is not faded: he asked for the menu track to ease in as the menu boots. */
    private static final int FADE_IN_TICKS = 40;

    ThemeMusicInstance(Identifier event) {
        this(event, true);
    }

    /** {@code looping} false plays the track once (Play Preview). */
    ThemeMusicInstance(Identifier event, boolean looping) {
        super(event, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.looping = looping;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.volume = looping ? 0f : ThemeMusic.gain();
    }

    @Override
    protected WeighedSoundEvents resolveCustom(SoundManager soundManager) {
        Identifier id = getIdentifier();
        WeighedSoundEvents events = new WeighedSoundEvents(id, null);
        this.sound = new Sound(id, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, true, false, 16);
        events.addSound(this.sound);
        return events;
    }

    /** Starts even at volume 0 (a muted game, or the slider at 0%), so the track keeps its place and the volume can come
     *  back up without a restart. Vanilla's music does the same. */
    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public boolean isStopped() { return stopped; }

    @Override
    public void tick() {
        float fade = 1f;
        if (looping && ticksPlaying < FADE_IN_TICKS) {
            fade = ++ticksPlaying / (float) FADE_IN_TICKS;
        }
        this.volume = ThemeMusic.gain() * fade;
    }

    /** The volume this instance asks for, before the engine's own scaling ({@code getVolume} needs a resolved sound). */
    float rawVolume() { return volume; }

    /** Ends the track: the engine drops a stopped tickable on its next tick. */
    void finish() { stopped = true; }
}
