package com.killer560.hub.rngmeter;

import java.nio.charset.StandardCharsets;

/**
 * Holds Killer560's own permanent Hypixel API key for the RNG Meter's Auction House lookups, used by
 * every install of this mod instead of a per-user key - killer560's explicit choice (2026-09-06), made
 * after being told plainly that no amount of client-side obfuscation defeats a determined person who
 * actually decompiles the mod: this only stops it showing up as a plain readable string to a casual
 * {@code strings}/grep scan of the jar, nothing more. The key is split into two halves and XORed
 * against a repeating pad rather than kept as a single string constant, so the class file itself
 * contains no contiguous string that looks like a credential.
 */
final class HypixelApiKeyProvider {

    private static final byte[] PAD = {0x4B, 0x69, 0x6C, 0x6C, 0x65, 0x72, 0x35, 0x36, 0x30};
    private static final int[] CIPHER_PART_1 =
            {121, 15, 95, 94, 83, 20, 87, 6, 29, 125, 11, 92, 95, 72, 70, 81, 15, 0};
    private static final int[] CIPHER_PART_2 =
            {102, 80, 94, 90, 87, 95, 80, 82, 5, 124, 13, 94, 85, 80, 66, 12, 83, 8};

    private static String cachedKey;

    static synchronized String getKey() {
        if (cachedKey == null) {
            int[] cipher = new int[CIPHER_PART_1.length + CIPHER_PART_2.length];
            System.arraycopy(CIPHER_PART_1, 0, cipher, 0, CIPHER_PART_1.length);
            System.arraycopy(CIPHER_PART_2, 0, cipher, CIPHER_PART_1.length, CIPHER_PART_2.length);
            byte[] decoded = new byte[cipher.length];
            for (int i = 0; i < cipher.length; i++) {
                decoded[i] = (byte) (cipher[i] ^ PAD[i % PAD.length]);
            }
            cachedKey = new String(decoded, StandardCharsets.US_ASCII);
        }
        return cachedKey;
    }

    private HypixelApiKeyProvider() {
    }
}
