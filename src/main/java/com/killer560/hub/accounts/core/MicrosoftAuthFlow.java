package com.killer560.hub.accounts.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Re-runs the same Microsoft -> Xbox Live -> Minecraft login chain every launcher (including
 * Prism itself) uses, starting from a Prism account's long-lived MSA refresh token.
 * <p>
 * Nothing here is sent anywhere except Microsoft's/Mojang's own official endpoints, and nothing
 * is persisted to disk - the caller gets an in-memory {@link AuthResult} for the current session only.
 */
public final class MicrosoftAuthFlow {

    private static final String MSA_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SESSION_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AuthResult refresh(PrismAccount account) throws AuthException {
        try {
            String msaAccessToken = refreshMsaToken(account);
            XblResult xbl = authenticateXbl(msaAccessToken);
            XblResult xsts = authenticateXsts(xbl.token());
            String mcAccessToken = loginWithXbox(xsts.uhs(), xsts.token());
            McProfile profile = fetchMcProfile(mcAccessToken);
            SkinTexture skin = fetchSkinTexture(profile.uuid());
            return new AuthResult(
                    profile.uuid(),
                    profile.name(),
                    mcAccessToken,
                    xsts.xid(),
                    skin == null ? null : skin.value(),
                    skin == null ? null : skin.signature()
            );
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Unexpected error signing in as " + account.displayName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Signs in directly from an already-issued Minecraft session/access token, skipping the whole
     * Microsoft -> Xbox Live chain entirely - for a temporary/one-off login (not saved anywhere)
     * rather than one of Prism's own stored accounts. {@code fetchMcProfile} both validates the
     * token (a bad/expired one throws {@link AuthException} the same way it does for the normal
     * flow) and resolves the uuid/name needed to build a {@link AuthResult}. No XUID is available
     * from a bare token, so it's left null - the same as it would be for any account whose Xbox
     * Live claim happened to omit one.
     */
    public AuthResult loginWithSessionToken(String rawAccessToken) throws AuthException {
        String token = rawAccessToken.trim();
        try {
            McProfile profile = fetchMcProfile(token);
            SkinTexture skin = fetchSkinTexture(profile.uuid());
            return new AuthResult(profile.uuid(), profile.name(), token, null,
                    skin == null ? null : skin.value(), skin == null ? null : skin.signature());
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Unexpected error signing in with that session token: " + e.getMessage(), e);
        }
    }

    private String refreshMsaToken(PrismAccount account) throws Exception {
        String body = "client_id=" + urlEncode(account.msaClientId())
                + "&refresh_token=" + urlEncode(account.refreshToken())
                + "&grant_type=refresh_token"
                + "&scope=" + urlEncode("XboxLive.SignIn XboxLive.offline_access");

        HttpRequest request = HttpRequest.newBuilder(URI.create(MSA_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonObject json = sendJson(request, "refreshing Microsoft sign-in for " + account.displayName());
        String accessToken = optString(json, "access_token");
        if (accessToken == null) {
            throw new AuthException("Microsoft didn't return an access token for " + account.displayName()
                    + " - the saved login may have been revoked. Try re-adding this account in Prism Launcher.");
        }
        return accessToken;
    }

    private XblResult authenticateXbl(String msaAccessToken) throws Exception {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msaAccessToken);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        HttpRequest request = HttpRequest.newBuilder(URI.create(XBL_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonObject json = sendJson(request, "signing in to Xbox Live");
        return parseXblLikeResponse(json);
    }

    private XblResult authenticateXsts(String xblToken) throws Exception {
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        HttpRequest request = HttpRequest.newBuilder(URI.create(XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new AuthException(describeXstsError(response.body()));
        }
        if (response.statusCode() / 100 != 2) {
            throw new AuthException("Xbox Live token exchange failed (HTTP " + response.statusCode() + ")");
        }
        return parseXblLikeResponse(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private String loginWithXbox(String uhs, String xstsToken) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

        HttpRequest request = HttpRequest.newBuilder(URI.create(MC_LOGIN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonObject json = sendJson(request, "signing in to Minecraft services");
        String token = optString(json, "access_token");
        if (token == null) {
            throw new AuthException("Minecraft services didn't return an access token.");
        }
        return token;
    }

    private McProfile fetchMcProfile(String mcAccessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new AuthException("This Microsoft account doesn't own Minecraft: Java Edition.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new AuthException("Fetching the Minecraft profile failed (HTTP " + response.statusCode() + ")");
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String id = optString(json, "id");
        String name = optString(json, "name");
        if (id == null || name == null) {
            throw new AuthException("Minecraft profile response was missing id/name.");
        }
        return new McProfile(parseDashlessUuid(id), name);
    }

    /** Best-effort public skin texture lookup; failures here should never block the account swap. */
    private SkinTexture fetchSkinTexture(UUID uuid) {
        try {
            String dashless = uuid.toString().replace("-", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(SESSION_PROFILE_URL + dashless))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement propsEl = json.get("properties");
            if (propsEl == null || !propsEl.isJsonArray()) {
                return null;
            }
            for (JsonElement el : propsEl.getAsJsonArray()) {
                JsonObject prop = el.getAsJsonObject();
                if ("textures".equals(optString(prop, "name"))) {
                    return new SkinTexture(optString(prop, "value"), optString(prop, "signature"));
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private XblResult parseXblLikeResponse(JsonObject json) throws AuthException {
        String token = optString(json, "Token");
        JsonObject displayClaims = json.getAsJsonObject("DisplayClaims");
        if (token == null || displayClaims == null) {
            throw new AuthException("Unexpected response shape from Xbox Live.");
        }
        JsonArray xui = displayClaims.getAsJsonArray("xui");
        if (xui == null || xui.isEmpty()) {
            throw new AuthException("Xbox Live response had no user claims.");
        }
        JsonObject claim = xui.get(0).getAsJsonObject();
        String uhs = optString(claim, "uhs");
        String xid = optString(claim, "xid");
        if (uhs == null) {
            throw new AuthException("Xbox Live response was missing the user hash.");
        }
        return new XblResult(token, uhs, xid);
    }

    private String describeXstsError(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement xErrEl = json.get("XErr");
            long xErr = xErrEl == null ? -1 : xErrEl.getAsLong();
            if (xErr == 2148916233L) {
                return "This Microsoft account has no Xbox Live profile. Create one at xbox.com, then try again.";
            } else if (xErr == 2148916235L) {
                return "Xbox Live isn't available in this account's country/region.";
            } else if (xErr == 2148916236L || xErr == 2148916237L) {
                return "This Xbox Live account needs adult verification (South Korea).";
            } else if (xErr == 2148916238L) {
                return "This is a child account and needs to be added to a Microsoft family group.";
            } else {
                return "Xbox Live rejected the sign-in (XErr " + xErr + ").";
            }
        } catch (Exception e) {
            return "Xbox Live rejected the sign-in.";
        }
    }

    private JsonObject sendJson(HttpRequest request, String actionDescription) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new AuthException("Error " + actionDescription + " (HTTP " + response.statusCode() + ")");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static UUID parseDashlessUuid(String id) {
        if (id.contains("-")) {
            return UUID.fromString(id);
        }
        String dashed = id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(dashed);
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record XblResult(String token, String uhs, String xid) {
    }

    private record McProfile(UUID uuid, String name) {
    }

    private record SkinTexture(String value, String signature) {
    }
}
