package com.killer560.hub.accounts.core;

/** The persisted form of a HypixelBanStatus - plain data, timestamped for display as "checked X ago". */
public record StoredBanStatus(
        HypixelBanStatus.Status status,
        String rawMessage,
        Long estimatedExpiryEpochMillis,
        long recordedAtEpochMillis
) {
}
