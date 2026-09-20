package com.killer560.hub.relay.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for {@code Minecraft.profileKeyPairManager}, the source of the RSA key pair the relay
 * handshake signs with (see {@link com.killer560.hub.relay.RelayAuth}).
 * <p>
 * {@code Minecraft#getProfileKeyPairManager()} is public and would do, but No Chat Reports and friends hook
 * that getter and hand back {@code EMPTY_KEY_MANAGER}, which would leave Mod Chat permanently unable to
 * authenticate for anyone running one of those (a very common mod on this modlist). Reading the field directly
 * sidesteps that - NoammAddons' {@code IMinecraft} does exactly the same thing for exactly the same reason.
 * Injects no behaviour; {@link com.killer560.hub.relay.RelayAuth} still falls back to the public getter if the
 * mixin somehow did not apply.
 */
@Mixin(Minecraft.class)
public interface MinecraftProfileKeysAccessor {

    @Accessor("profileKeyPairManager")
    ProfileKeyPairManager killer560smod$getProfileKeyPairManager();
}
