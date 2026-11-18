package com.killer560.hub.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

/**
 * A player-head profile that wears a skin shipped in the jar: the profile's skin patch names a {@code ClientAsset} id
 * ({@code killer560smod:entity/x} = {@code textures/entity/x.png}), which vanilla's texture manager loads the first time
 * the head is drawn and drops on a resource reload. Nothing is fetched. Used by the sim's wither essence and Pack
 * Disabler's bundled heads.
 */
public final class BundledSkins {

    private BundledSkins() {
    }

    /**
     * The profile {@code id}/{@code name} with {@code texture} as its skin, or null if the patch could not be added. Built
     * through {@code ResolvableProfile.CODEC} because the patch has no public factory: a resolved profile is encoded,
     * the patch's {@code texture} field added, and decoded.
     */
    public static ResolvableProfile profile(UUID id, String name, String texture) {
        ResolvableProfile plain = ResolvableProfile.createResolved(new GameProfile(id, name));
        try {
            Tag tag = ResolvableProfile.CODEC.encodeStart(NbtOps.INSTANCE, plain).result().orElse(null);
            if (tag instanceof CompoundTag compound) {
                compound.putString("texture", texture);
                return ResolvableProfile.CODEC.parse(NbtOps.INSTANCE, compound).result().orElse(null);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
