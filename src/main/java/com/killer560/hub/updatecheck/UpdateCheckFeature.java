package com.killer560.hub.updatecheck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * "Check for Updates" - on click, and once per launch if the notice is on. Hits GitHub's
 * {@code /releases/latest} endpoint, which naturally excludes pre-releases and drafts, so this can never point
 * anyone at anything but a real full release, matching killer560's explicit requirement.
 *
 * <p>This was click-only, on his instruction that there be no background polling. The startup notice added
 * 2026-09-28 is ONE check on the first world join of a session and never again, which is not polling - and it
 * exists because the mod is going out beyond his friends, where the expensive failure is a stranger silently
 * running a months-old jar and reporting something already fixed. {@link UpdateCheckConfig} turns it off.
 */
public final class UpdateCheckFeature {

    private static final String MOD_ID = "killer560smod";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Killer560-OG/killer560s-mod/releases/latest";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private UpdateCheckFeature() {
    }

    /** One notice per launch, not per world join - he reconnects a lot and a repeat would be nagging. */
    private static boolean noticeShown;

    /**
     * Says once, on the first world join of the session, that a newer release exists.
     *
     * <p>On JOIN rather than at init because the message goes to chat, and at init there is no player to send it
     * to; a notice that fires into nothing is the same as no notice. Failures are swallowed on purpose - someone
     * playing offline, behind a filter, or while GitHub is down should get silence, not an error they cannot act
     * on and did not ask for.
     */
    public static void registerStartupNotice() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (noticeShown || !UpdateCheckConfig.getInstance().isNotifyOnStart()) {
                return;
            }
            noticeShown = true;
            checkForUpdateAsync(result -> {
                if (result == null || result.error() != null || !result.updateAvailable()) {
                    return;
                }
                ModChat.send("Killer560's Mod",
                        ModChat.text("Version "), ModChat.value(result.remoteVersion()),
                        ModChat.text(" is out - you are on "), ModChat.value(result.currentVersion()),
                        ModChat.dim(". " + result.releaseUrl()));
            });
        });
    }

    public record Result(boolean updateAvailable, String currentVersion, String remoteVersion,
                          String releaseUrl, String error) {
    }

    /** Runs the network check off the client thread; {@code callback} is always invoked back on the
     *  client thread, safe to touch widgets/Minecraft state from. */
    public static void checkForUpdateAsync(Consumer<Result> callback) {
        Thread thread = new Thread(() -> {
            Result result = checkForUpdateBlocking();
            Minecraft.getInstance().execute(() -> callback.accept(result));
        }, "killer560smod-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static Result checkForUpdateBlocking() {
        String currentVersionString = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Result(false, currentVersionString, null, null,
                        "GitHub API returned " + response.statusCode());
            }

            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            if ((obj.has("draft") && obj.get("draft").getAsBoolean())
                    || (obj.has("prerelease") && obj.get("prerelease").getAsBoolean())) {
                // /releases/latest already excludes these, but this is a cheap belt-and-suspenders
                // check given killer560's explicit requirement to only ever offer full releases.
                return new Result(false, currentVersionString, null, null, null);
            }

            String tag = obj.get("tag_name").getAsString();
            String remoteVersionString = tag.startsWith("v") ? tag.substring(1) : tag;
            String releaseUrl = obj.get("html_url").getAsString();

            // Typed as the base Version interface (not SemanticVersion) so this resolves to
            // Version#compareTo, not SemanticVersion's own deprecated covariant overload.
            Version current = SemanticVersion.parse(currentVersionString);
            Version remote = SemanticVersion.parse(remoteVersionString);
            boolean updateAvailable = remote.compareTo(current) > 0;

            return new Result(updateAvailable, currentVersionString, remoteVersionString, releaseUrl, null);
        } catch (Exception e) {
            return new Result(false, currentVersionString, null, null, e.getMessage());
        }
    }
}
