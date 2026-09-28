package com.killer560.hub.autokick;

import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * fastest_time_s is already in seconds, matching {@link AutoKickConfig#getTargetSeconds}'s own unit - no
 * conversion needed.
 * <p>
 * <b>Never overwrites a floor he already set.</b> {@link #populate} only fills a floor whose target is
 * still 0 - a deliberately-tuned value is never silently clobbered by a later populate for a different
 * player. Floors with no recorded clear time on the source profile are left at 0 (still "never kick").
 */
public final class AutoKickApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autokick");

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
        // SbProfile.parseAll already sorts the selected profile first (falling back to alphabetical when
        // none is marked selected) - see that method's own sort call - so index 0 is exactly "the profile
        // this player is actually playing on" whenever Hypixel reports one.
        SbProfile profile = profiles.isEmpty() ? null : profiles.get(0);

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
                long seconds = f == null ? 0L : f.fastestS();
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

        LOGGER.info("[AutoKick] Populate from {}: {} floor(s) set from their fastest recorded clear time, "
                        + "{} left at 0 (no recorded time), {} left untouched (already had a target).",
                playerName, set, noData, alreadySet);
        return new PopulateResult(playerName, set, noData, alreadySet);
    }
}
