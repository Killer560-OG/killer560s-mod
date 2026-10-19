package com.killer560.hub.itembrowser;

/**
 * Hypixel's own leather-armour {@code color} field, which the public item catalog writes as a string
 * {@code "r,g,b"} (for example {@code "139,0,0"} on the Arachne pieces). Packed here as the {@code 0xRRGGBB}
 * int that {@code DyedItemColor} takes, the same convention {@code LegacyItems} uses for a legacy NBT
 * {@code display.color}. Pure Java with no Minecraft types, so it can be checked on its own.
 */
public final class DyeColors {

    private DyeColors() {
    }

    /** @return packed {@code 0xRRGGBB}, or null when {@code raw} is null or is not three integers 0..255. */
    public static Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        int packed = 0;
        for (String part : parts) {
            int channel;
            try {
                channel = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (channel < 0 || channel > 255) {
                return null;
            }
            packed = (packed << 8) | channel;
        }
        return packed;
    }

    /** Inverse of {@link #parse}: {@code 0x8B0000} to {@code "139,0,0"}. */
    public static String format(int packed) {
        return ((packed >> 16) & 0xFF) + "," + ((packed >> 8) & 0xFF) + "," + (packed & 0xFF);
    }
}
