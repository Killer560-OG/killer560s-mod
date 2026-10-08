package com.killer560.hub.accounts;

import com.killer560.hub.accounts.core.AuthResult;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reflectively swaps the running Minecraft instance's identity to a freshly authenticated
 * account, without restarting the client. Minecraft.user / .profileFuture / .profileKeyPairManager
 * are all `private final` - there's no public API for this, so this is inherently fragile across
 * game updates and is the one place in this mod that reaches past normal encapsulation.
 */
public final class AccountApplier {

    private AccountApplier() {
    }

    public static void apply(AuthResult result) {
        Minecraft minecraft = Minecraft.getInstance();
        User newUser = new User(
                result.name(),
                result.uuid(),
                result.minecraftAccessToken(),
                Optional.ofNullable(result.xuid()),
                Optional.empty()
        );

        setFinalField(minecraft, "user", newUser);
        setFinalField(minecraft, "profileFuture",
                CompletableFuture.completedFuture(new ProfileResult(new GameProfile(result.uuid(), result.name()))));

        // The chat-signing key pair must be fetched with the NEW account's token. Until 2026-10-08 the new key manager
        // was built on Minecraft's existing userApiService, which carries the LAUNCH account's access token, so every
        // swapped-to account was handed the launch account's Mojang certificate: a key Mojang signed for a different
        // UUID. The relay rejects exactly that ("that public key was not issued by Mojang for that UUID") - killer560's
        // Mod Chat failing on every account but his main - and Hypixel's secure chat sees the same mismatch.
        // So: a fresh UserApiService for this token (the same construction Minecraft's own constructor does), and the
        // key manager, the user-properties future and the field itself all follow it.
        try {
            YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(minecraft.getProxy());
            UserApiService userApiService = authService.createUserApiService(result.minecraftAccessToken());
            setFinalField(minecraft, "userApiService", userApiService);
            setFinalField(minecraft, "userPropertiesFuture", CompletableFuture.supplyAsync(() -> {
                try {
                    return userApiService.fetchProperties();
                } catch (Exception e) {
                    return UserApiService.OFFLINE_PROPERTIES;
                }
            }, Util.nonCriticalIoPool()));
            // gameDirectory, like Minecraft's constructor: the manager adds "profilekeys/<uuid>.json" itself.
            ProfileKeyPairManager newManager = ProfileKeyPairManager.create(
                    userApiService, newUser, minecraft.gameDirectory.toPath());
            setFinalField(minecraft, "profileKeyPairManager", newManager);
        } catch (Exception e) {
            // No chat key for this account then: Mod Chat says "Minecraft never issued this account a chat key",
            // which is true, instead of signing in with the previous account's key.
            setFinalField(minecraft, "profileKeyPairManager", ProfileKeyPairManager.EMPTY_KEY_MANAGER);
        }
        // A relay token issued to the previous account must never be reused for this one.
        com.killer560.hub.relay.RelayClient.forgetToken();
    }

    private static void setFinalField(Object target, String name, Object value) {
        try {
            Field field = Minecraft.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to apply account swap (field '" + name
                    + "' not found - Minecraft's internals may have changed in this version)", e);
        }
    }
}
