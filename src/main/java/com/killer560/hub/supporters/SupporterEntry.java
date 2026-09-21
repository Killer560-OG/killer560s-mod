package com.killer560.hub.supporters;

import java.util.UUID;

/**
 * One row of {@code GET /supporters} - see {@code SUPPORTERS-CONTRACT.md}. {@code rawName} is exactly what the
 * relay sent (plain text, {@code &} colour codes, up to 32 visible chars) - unfiltered and un-colourised;
 * {@link SupportersFeature} is what turns it into something safe to paint. {@code scale} is always clamped
 * into the contract's 0.5-2.0 range here so nothing downstream has to re-check it.
 */
record SupporterEntry(UUID uuid, String rawName, float scale) {

    SupporterEntry {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid");
        }
        rawName = rawName == null ? "" : rawName;
        if (!Float.isFinite(scale) || scale < 0.5f || scale > 2.0f) {
            scale = 1.0f;
        }
    }
}
