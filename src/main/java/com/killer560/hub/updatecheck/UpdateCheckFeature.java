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

/** Manual "Check for Updates" support - purely on-click, no background polling. Hits GitHub's
 *  {@code /releases/latest} endpoint, which naturally excludes pre-releases and drafts, so this can
 *  never point killer560 at anything but a real full release, matching his explicit requirement. */
public final class UpdateCheckFeature {

    private static final String MOD_ID = "killer560smod";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Killer560-OG/killer560s-mod/releases/latest";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private UpdateCheckFeature() {
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
