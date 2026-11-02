package com.killer560.hub.accounts.core;

/**
 * A Microsoft account a launcher has stored: Prism Launcher's accounts.json, or the Modrinth App's app.db when the game
 * runs under the Modrinth App ({@link LauncherAccounts}). Only the fields needed to sign in are kept here; the raw file
 * (and its other tokens) is never held onto beyond the parse.
 * <p>
 * A Prism account carries the MSA refresh token and client id that re-run the Microsoft/Xbox/Minecraft chain. A Modrinth
 * account carries the Minecraft access token the Modrinth App last fetched and when it expires: the app's refresh token
 * is bound to its own device key (Xbox SISU), so the mod uses the app's current token instead of refreshing it.
 */
public record PrismAccount(String uuid, String name, String msaClientId, String refreshToken,
                           Source source, String accessToken, long accessTokenExpiresEpochSeconds) {

    /** Which launcher this account was read from. */
    public enum Source {
        PRISM("Prism Launcher"),
        MODRINTH("Modrinth App");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    /** A Prism Launcher account. */
    public PrismAccount(String uuid, String name, String msaClientId, String refreshToken) {
        this(uuid, name, msaClientId, refreshToken, Source.PRISM, null, 0L);
    }

    /** A Modrinth App account: its current Minecraft access token and that token's expiry (Unix seconds). */
    public static PrismAccount modrinth(String uuid, String name, String accessToken, long expiresEpochSeconds) {
        return new PrismAccount(uuid, name, null, null, Source.MODRINTH, accessToken, expiresEpochSeconds);
    }

    public String displayName() {
        return name;
    }

    /** Never prints a token (the record's generated toString would). */
    @Override
    public String toString() {
        return "PrismAccount[" + name + ", " + uuid + ", " + source + "]";
    }
}
