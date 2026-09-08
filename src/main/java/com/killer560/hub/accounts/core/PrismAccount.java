package com.killer560.hub.accounts.core;

/**
 * A Microsoft account as stored in Prism Launcher's accounts.json.
 * Only the fields needed to re-run the Microsoft/Xbox/Minecraft login flow are kept here;
 * the raw file (and its live tokens) is never held onto beyond the parse.
 */
public record PrismAccount(String uuid, String name, String msaClientId, String refreshToken) {

    public String displayName() {
        return name;
    }
}
