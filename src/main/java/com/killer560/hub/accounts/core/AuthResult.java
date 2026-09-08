package com.killer560.hub.accounts.core;

import java.util.UUID;

/**
 * The outcome of a successful Microsoft -> Xbox Live -> Minecraft login,
 * with everything needed to apply the account to a running client.
 * skinTextureValue/Signature are null if the public skin lookup failed or the account has no skin set;
 * that's non-fatal, the swap still proceeds without a corrected skin.
 */
public record AuthResult(
        UUID uuid,
        String name,
        String minecraftAccessToken,
        String xuid,
        String skinTextureValue,
        String skinTextureSignature
) {
}
