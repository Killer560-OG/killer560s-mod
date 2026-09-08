package com.killer560.hub.accounts;

import com.killer560.hub.accounts.core.AuthResult;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
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

        try {
            Object userApiService = readField(minecraft, "userApiService");
            Path keyCacheDir = minecraft.gameDirectory.toPath().resolve("cache").resolve("profilekeys");
            ProfileKeyPairManager newManager = ProfileKeyPairManager.create(
                    (com.mojang.authlib.minecraft.UserApiService) userApiService, newUser, keyCacheDir);
            setFinalField(minecraft, "profileKeyPairManager", newManager);
        } catch (Exception e) {
            // Chat signing keeps following the previous account if this fails; not fatal to the swap.
        }
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = Minecraft.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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
