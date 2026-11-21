package com.killer560.hub.clicksound.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sound Disabler's volume sliders set the instance's own volume before it reaches the engine. */
@Mixin(AbstractSoundInstance.class)
public interface SoundVolumeAccessor {

    @Accessor("volume")
    void killer560$setVolume(float volume);

    /** The raw field: getVolume() reads the resolved Sound, which is still null when the hook runs. */
    @Accessor("volume")
    float killer560$getVolume();

    @Accessor("pitch")
    float killer560$getPitch();
}
