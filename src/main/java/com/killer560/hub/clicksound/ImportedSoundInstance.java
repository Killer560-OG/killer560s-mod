package com.killer560.hub.clicksound;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * A sound read from the import folder: a click (UI, no position) or a sound effect (positional, where the game played it).
 * It names a {@link Sound} the resource packs do not have; the sound engine's buffer lookup for that name is answered from
 * disk by {@code SoundBufferLibraryClickMixin} ({@link ClickSoundBuffers}).
 */
final class ImportedSoundInstance extends AbstractSoundInstance {

    private final Identifier fileId;

    /**
     * @param positional false for a click (master volume, no position, like {@code SimpleSoundInstance.forUI}); true for a
     *                   sound effect, which is heard from {@code x, y, z} and fades with distance like the sound it replaces.
     */
    ImportedSoundInstance(String key, SoundSource source, float volume, float pitch, double x, double y, double z,
                          boolean positional) {
        super(Identifier.fromNamespaceAndPath(ClickSounds.NAMESPACE, key), source, SoundInstance.createUnseededRandom());
        this.fileId = Identifier.fromNamespaceAndPath(ClickSounds.NAMESPACE, key);
        this.volume = volume;
        this.pitch = pitch;
        this.x = x;
        this.y = y;
        this.z = z;
        this.relative = !positional;
        this.attenuation = positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        WeighedSoundEvents events = new WeighedSoundEvents(this.fileId, null);
        this.sound = new Sound(this.fileId, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE,
                false, false, 16);
        events.addSound(this.sound);
        return events;
    }
}
