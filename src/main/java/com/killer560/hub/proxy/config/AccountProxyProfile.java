package com.killer560.hub.proxy.config;

/**
 * One account's saved proxy assignment - the same shape as {@link ProxyConfig}'s own fields, but
 * scoped to a single Microsoft account instead of being the one global active proxy. See
 * {@link AccountProxyStore}.
 */
public final class AccountProxyProfile {

    private ProxyType type = ProxyType.SOCKS5;
    private String host = "";
    private int port = 1080;
    private String username = "";
    private String password = "";

    public ProxyType getType() {
        return type;
    }

    public void setType(ProxyType type) {
        this.type = (type == null) ? ProxyType.SOCKS5 : type;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void setUsername(String username) {
        this.username = (username == null) ? "" : username;
    }

    public String getUsername() {
        return username;
    }

    public void setPassword(String password) {
        this.password = (password == null) ? "" : password;
    }

    public String getPassword() {
        return password;
    }

    public boolean hasValidAddress() {
        return host != null && !host.isBlank() && port >= 1 && port <= 65535;
    }

    public String getAddressString() {
        if (host == null || host.isBlank()) {
            return "";
        }
        return host + ":" + port;
    }

    /** Same "host:port", split-on-last-colon parsing {@link ProxyConfig#parseAndSetAddress} uses. */
    public void parseAndSetAddress(String input) {
        if (input == null) {
            return;
        }
        String trimmed = input.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon < 0) {
            this.host = trimmed;
            this.port = 1080;
            return;
        }
        this.host = trimmed.substring(0, colon).trim();
        String portPart = trimmed.substring(colon + 1).trim();
        try {
            int parsed = Integer.parseInt(portPart);
            this.port = (parsed >= 1 && parsed <= 65535) ? parsed : 1080;
        } catch (NumberFormatException e) {
            this.port = 1080;
        }
    }
}
