package com.killer560.hub.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.relay.mixin.MinecraftProfileKeysAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Proving to the relay who you are, without a password, an API key or a Hypixel key.
 * <p>
 * Minecraft hands every logged-in client an RSA key pair (the one that signs chat) plus Mojang's own signature
 * over its public key. The relay checks Mojang's signature against Mojang's published certificate keys, then
 * checks that we can sign a challenge it just made up with the matching private key - which together prove we
 * own that account right now. The relay then stamps the sender name on every message itself, so nobody can
 * speak as anyone else. See {@code killer560s-mod-relay/README.md} and {@code src/mojang.ts}.
 * <p>
 * Same approach NoammAddons uses for {@code ws.noamm.org} ({@code utils/network/ApiAuth.kt}).
 * <p>
 * <b>Everything that leaves the client here:</b> the account UUID, the IGN, the Mojang-issued public key with
 * Mojang's signature and its expiry, and a signature over the relay's own random challenge. No access token, no
 * session ID, no inventory, no position, nothing about the game at all.
 */
public final class RelayAuth {

    /** A relay bearer token and when it stops working (epoch ms, as the relay reports it). */
    public record Token(String value, long expiresAtMs) {
    }

    /** Thrown for every handshake failure, so the caller has one short human-readable reason to show. */
    public static final class RelayAuthException extends RuntimeException {
        public RelayAuthException(String message) {
            super(message);
        }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private RelayAuth() {
    }

    /**
     * Runs the whole handshake. Never blocks: every step is a {@link CompletableFuture} on the caller's
     * executor, so this is safe to start from the relay worker thread and is never on the render thread.
     */
    public static CompletableFuture<Token> authenticate(HttpClient http, String baseUrl) {
        final Minecraft client = Minecraft.getInstance();
        final User user = client == null ? null : client.getUser();
        if (user == null) {
            return CompletableFuture.failedFuture(new RelayAuthException("not signed in to Minecraft"));
        }
        final UUID uuid = user.getProfileId();
        final String name = user.getName();
        if (uuid == null || name == null || name.isBlank()) {
            return CompletableFuture.failedFuture(new RelayAuthException("no Microsoft account profile loaded"));
        }

        final ProfileKeyPairManager keys = keyPairManager(client);
        if (keys == null) {
            return CompletableFuture.failedFuture(new RelayAuthException("Minecraft has no profile key manager"));
        }

        CompletableFuture<ProfileKeyPair> keyPair = keys.prepareKeyPair().thenApply(optional -> {
            ProfileKeyPair pair = optional.orElse(null);
            if (pair == null) {
                // Offline/cracked launches and some auth-stripping mods never get one.
                throw new RelayAuthException("Minecraft never issued this account a chat key");
            }
            if (pair.publicKey().data().hasExpired()) {
                throw new RelayAuthException("your Minecraft chat key expired - restart the game");
            }
            return pair;
        });

        CompletableFuture<String> challenge = http.sendAsync(
                        HttpRequest.newBuilder(RelayEndpoint.challenge(baseUrl, uuid.toString()))
                                .timeout(REQUEST_TIMEOUT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RelayAuthException("relay refused the challenge request (HTTP " + response.statusCode() + ")");
                    }
                    String value = string(response.body(), "challenge");
                    if (value == null) {
                        throw new RelayAuthException("relay sent no challenge");
                    }
                    return value;
                });

        return keyPair.thenCombine(challenge, (pair, issued) -> body(uuid, name, pair, issued))
                .thenCompose(body -> http.sendAsync(
                        HttpRequest.newBuilder(RelayEndpoint.auth(baseUrl))
                                .timeout(REQUEST_TIMEOUT)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        String why = string(response.body(), "error");
                        throw new RelayAuthException(why != null
                                ? "relay rejected the login: " + why
                                : "relay rejected the login (HTTP " + response.statusCode() + ")");
                    }
                    String token = string(response.body(), "token");
                    if (token == null) {
                        throw new RelayAuthException("relay sent no token");
                    }
                    JsonObject obj = object(response.body());
                    long exp = obj != null && obj.has("exp") && obj.get("exp").isJsonPrimitive()
                            ? obj.get("exp").getAsLong()
                            : System.currentTimeMillis() + Duration.ofHours(1).toMillis();
                    return new Token(token, exp);
                });
    }

    /** The field, not {@code getProfileKeyPairManager()} - see {@link MinecraftProfileKeysAccessor}'s doc. */
    private static ProfileKeyPairManager keyPairManager(Minecraft client) {
        try {
            return ((MinecraftProfileKeysAccessor) (Object) client).killer560smod$getProfileKeyPairManager();
        } catch (Throwable ignored) {
            // The mixin config isn't registered, or the field was renamed: the public getter still works for
            // everyone who isn't running a key-stripping mod.
            return client.getProfileKeyPairManager();
        }
    }

    private static String body(UUID uuid, String name, ProfileKeyPair pair, String challenge) {
        ProfilePublicKey.Data data = pair.publicKey().data();
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString());
        json.addProperty("name", name);
        json.addProperty("publicKey", Base64.getEncoder().encodeToString(data.key().getEncoded()));
        json.addProperty("publicKeySignature", Base64.getEncoder().encodeToString(data.keySignature()));
        json.addProperty("expiresAt", data.expiresAt().toEpochMilli());
        json.addProperty("challenge", challenge);
        json.addProperty("challengeSignature", sign(pair.privateKey(), challenge));
        return json.toString();
    }

    /** The relay verifies this as SHA256withRSA over the challenge string's UTF-8 bytes, exactly as issued. */
    private static String sign(PrivateKey privateKey, String challenge) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(challenge.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new RelayAuthException("couldn't sign the relay challenge (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static JsonObject object(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(String body, String key) {
        JsonObject obj = object(body);
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }
}
