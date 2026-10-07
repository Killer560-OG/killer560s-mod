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
 * <b>Exact API field used, and how it was established.</b> {@link SbProfile.Floor#fastestS()}, which
 * {@code SbProfile.floors()} reads from the real Hypixel {@code /v2/skyblock/profiles} response at
 * {@code members.<uuid>.dungeons.dungeon_types.catacombs.fastest_time_s.<floor>} (and
 * {@code ...master_catacombs.fastest_time_s.<floor>} for Master Mode) - already fetched, parsed and
 * displayed as the "fastest clear time" column on this mod's own Profile Viewer dungeons page
 * (see {@code ProfileViewerScreen}'s dungeons table). This is a field this codebase was already fetching
 * and rendering successfully before tonight, not a new guess - see this class's own doc for the one field
 * it deliberately does NOT use: {@code fastest_time_s_plus} (fastest S+-scored run) is frequently empty for
 * a player who hasn't chased S+ on every floor, and populating from an empty field would silently skip
 * floors that field never fired on while a real, slower clear time sits right next to it unused.
 * fastest_time_s is in MILLISECONDS (checked 2026-10-07 against real profiles: AntsRNG's F7 is 263003, a 4:23
 * clear), so it is converted to whole seconds, rounded up, for {@link AutoKickConfig#getTargetSeconds}. Read as
 * seconds, every floor used to clamp to {@link AutoKickConfig#MAX_TARGET_SECONDS}.
 * <p>
 * <b>Never overwrites a floor he already set.</b> {@link #populate} only fills a floor whose target is
 * still 0 - a deliberately-tuned value is never silently clobbered by a later populate for a different
 * player. Floors with no recorded clear time on the source profile are left at 0 (still "never kick").
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
            for (AutoKickConfig.Floor floor : AutoKickConfig.Floor.values()) {
                if (cfg.getTargetSeconds(floor) > 0) {
                    // He already set this one (for this player or a previous populate) - never clobber a
                    // deliberate choice. See this class's own doc.
                    alreadySet++;
                    continue;
                }
                boolean master = floor.name().charAt(0) == 'M';
                Map<Integer, SbProfile.Floor> floors = master ? profile.dungeons.master() : profile.dungeons.normal();
                // Floor enum tokens are exactly "F1".."F7"/"M1".."M7" - see AutoKickConfig.Floor's own
                // declaration - so the digit after the letter is the same 1-7 key SbProfile.floors() keys
                // its map by.
                int floorNum = Integer.parseInt(floor.name().substring(1));
                SbProfile.Floor f = floors.get(floorNum);
                long seconds = f == null ? 0L : targetSecondsFromMillis(f.fastestS());
                if (seconds > 0) {
                    cfg.setTargetSeconds(floor, (int) Math.min(seconds, AutoKickConfig.MAX_TARGET_SECONDS));
                    set++;
                } else {
                    // No recorded fastest_time_s for this floor (never S-cleared it, or Dungeons API off) -
                    // stays at 0, i.e. still "never kick on this floor". Never invent a number here.
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
