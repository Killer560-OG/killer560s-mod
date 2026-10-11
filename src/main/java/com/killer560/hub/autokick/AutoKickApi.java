package com.killer560.hub.autokick;

import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fills {@link AutoKickConfig}'s per-floor target times from a real Hypixel dungeon clear time, instead of
 * killer560 having to invent a number for every floor. His own request tonight (2026-09-27), verbatim, when
 * asked what the per-floor times should be: "the auto kick time the player wants to let into the party
 * they should be able to choose, it should populate them from someone else via their api. Default it to
 * being 0 though." So the number is still his to set - this only offers a real, API-verified starting point
 * instead of a guess, for whichever floors he hasn't already touched.
 * <p>
 * <b>No new HTTP client, no new key storage.</b> Reuses {@link ProfileViewerApi} - this mod's one Hypixel /
 * SkyBlockPV-backend client, with its own already-working key fallback ({@code ProfileViewerConfig}'s
 * stored API key, then the keyless backend) - and {@link SbProfile}'s already-working dungeon parsing.
 * Nothing here talks to the network directly.
 * <p>
 * <b>Field used (since 2026-10-09):</b> {@link SbProfile.Floor#fastestSPlus()} - Hypixel's
 * {@code fastest_time_s_plus}, in MILLISECONDS, rounded up to whole seconds - because Auto Kick now judges Party Finder
 * joiners by their timed (S+) completion, and the number it compares against should come from the same field. Every
 * floor the profile has an S+ time on is set (clamped into that floor's 30 s - 4/6/7 min range); a floor without one is
 * left as it was. It is an explicit button press or command, so it overwrites.
 */
public final class AutoKickApi {

    private AutoKickApi() {
    }

    /** One populate run's outcome, for the command/button to report back to him. */
    public record PopulateResult(String playerName, int floorsSet, int floorsNoData, int floorsAlreadySet) {
    }

    /** @param nameOrBlank a player's IGN, or blank/null for "look up my own profile". */
    public static CompletableFuture<PopulateResult> populate(String nameOrBlank) {
        String trimmed = nameOrBlank == null ? "" : nameOrBlank.trim();
        CompletableFuture<ProfileViewerApi.ResolvedPlayer> resolved;
        if (trimmed.isEmpty()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return CompletableFuture.failedFuture(new ProfileViewerApi.ApiException("Not logged into a world - can't tell who \"you\" are."));
            }
            // No network round-trip needed to know your own name/uuid - same as AutoKickFeature's own
            // "never kick the local player" check reads it.
            resolved = CompletableFuture.completedFuture(
                    new ProfileViewerApi.ResolvedPlayer(client.player.getUUID(), client.player.getGameProfile().name()));
        } else {
            resolved = ProfileViewerApi.resolve(trimmed);
        }
        return resolved.thenCompose(player -> ProfileViewerApi.fetchProfiles(player.uuid(), false)
                .thenApply(result -> apply(player.name(), result)));
    }

    private static PopulateResult apply(String playerName, ProfileViewerApi.ProfilesResult result) {
        List<SbProfile> profiles = result.profiles();
        // The selected profile, or the one with the most Catacombs XP when none is selected or the selected one
        // has no dungeon data - the same rule the Party Finder stats use (SbProfile.dungeonProfile).
        SbProfile profile = SbProfile.dungeonProfile(profiles);

        AutoKickConfig cfg = AutoKickConfig.getInstance();
        int set = 0;
        int noData = 0;
        int alreadySet = 0;

        if (profile == null) {
            noData = AutoKickConfig.Floor.values().length;
        } else {
            // 2026-10-09: every floor now always has a time (30 s up to its own top), so there are no blanks to fill;
            // a populate is an explicit press and sets every floor the profile has an S+ time on, clamped into that
            // floor's range. A floor with no S+ time is left as it was.
            for (AutoKickConfig.Floor floor : AutoKickConfig.Floor.values()) {
                Map<Integer, SbProfile.Floor> floors = floor.master() ? profile.dungeons.master() : profile.dungeons.normal();
                SbProfile.Floor f = floors.get(floor.number());
                long seconds = f == null ? 0L : targetSecondsFromMillis(f.fastestSPlus());
                if (seconds > 0) {
                    cfg.setTargetSeconds(floor, (int) Math.min(seconds, floor.maxSeconds()));
                    set++;
                } else {
                    noData++;
                }
            }
            if (set > 0) {
                cfg.save();
            }
        }

        return new PopulateResult(playerName, set, noData, alreadySet);
    }

    /** A profile's {@code fastest_time_s} (milliseconds) as whole seconds, rounded up; 0 stays 0 (no clear). */
    public static long targetSecondsFromMillis(long ms) {
        return ms <= 0 ? 0L : (ms + 999) / 1000;
    }
}
