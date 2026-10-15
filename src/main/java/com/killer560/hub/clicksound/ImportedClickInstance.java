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
 * A click sound read from the import folder. It names a {@link Sound} the resource packs do not have; the sound engine's
 * buffer lookup for that name is answered from disk by {@code SoundBufferLibraryClickMixin} ({@link ClickSoundBuffers}).
 * Plays like {@code SimpleSoundInstance.forUI}: master volume, no position.
 */
final class ImportedClickInstance extends AbstractSoundInstance {

    private final Identifier fileId;

    ImportedClickInstance(String key, float volume) {
        super(Identifier.fromNamespaceAndPath(ClickSounds.NAMESPACE, key), SoundSource.MASTER,
                SoundInstance.createUnseededRandom());
        this.fileId = Identifier.fromNamespaceAndPath(ClickSounds.NAMESPACE, key);
        this.volume = volume;
        this.pitch = 1.0f;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
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
