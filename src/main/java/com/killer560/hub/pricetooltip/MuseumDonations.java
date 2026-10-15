package com.killer560.hub.pricetooltip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which items YOUR museum holds, for the "Museum (Combat): Donated" line. The museum is per profile and only the API
 * knows it, so this asks the Profile Viewer's own fetchers (your key if you set one, else the keyless SkyBlockPV
 * backend - no key is ever needed or sent anywhere new): your profile list for the selected profile, then that profile's
 * museum. The donated set is the museum's {@code items} keys, which are item ids and, for armor, the set id
 * ({@code museum_data.armor_set_donation_xp} names it).
 *
 * <p>Fetched lazily the first time a museum item is hovered, then at most every 10 minutes; until it arrives (or with
 * no data) the line shows only the category.
 */
public final class MuseumDonations {

    private static final Logger LOGGER = ModLog.get("killer560smod-pricetooltip");
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;

    private static volatile Set<String> donated = null;
    private static volatile long fetchedAt = 0;
    private static volatile long attemptAt = 0;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private MuseumDonations() {
    }

    /** True/false once your museum is known, null while it is not (yet). Never blocks. */
    public static Boolean isDonated(String id, PriceData.ItemInfo info) {
        maybeFetch();
        Set<String> set = donated;
        if (set == null) {
            return null;
        }
        if (set.contains(id)) {
            return true;
        }
        if (info != null) {
            for (String s : info.museumSets()) {
                if (set.contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void maybeFetch() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < REFRESH_MS || now - attemptAt < RETRY_MS || !inFlight.compareAndSet(false, true)) {
            return;
        }
        attemptAt = now;
        UUID self;
        try {
            self = Minecraft.getInstance().getUser().getProfileId();
        } catch (Throwable t) {
            inFlight.set(false);
            return;
        }
        if (self == null) {
            inFlight.set(false);
            return;
        }
        try {
            ProfileViewerApi.fetchProfiles(self, false).thenCompose(result -> {
                SbProfile selected = null;
                for (SbProfile p : result.profiles()) {
                    if (p.selected) {
                        selected = p;
                        break;
                    }
                }
                if (selected == null && !result.profiles().isEmpty()) {
                    selected = result.profiles().get(0);
                }
                if (selected == null) {
                    throw new IllegalStateException("no profile");
                }
                return ProfileViewerApi.fetchAux(ProfileViewerApi.AuxKind.MUSEUM, selected.profileId, false);
            }).whenComplete((res, err) -> {
                try {
                    if (err != null) {
                        LOGGER.info("[PriceTooltip] museum not loaded: {}", ProfileViewerApi.messageFor(err));
                        return;
                    }
                    JsonObject members = res == null ? null : res.data();
                    JsonElement me = members == null ? null : members.get(ProfileViewerApi.dashless(self));
                    JsonObject items = me != null && me.isJsonObject() && me.getAsJsonObject().get("items") instanceof JsonObject o ? o : null;
                    setDonated(items == null ? Set.of() : Set.copyOf(items.keySet()));
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (Throwable t) {
            inFlight.set(false);
        }
    }

    /** The museum as known now (testkit), or null. */
    public static Set<String> donated() {
        return donated;
    }

    /** Sets your museum's donated ids directly (the testkit, and the fetch above). */
    public static void setDonated(Set<String> ids) {
        donated = ids;
        fetchedAt = System.currentTimeMillis();
        PriceTooltipFeature.invalidate();
    }

    /** Testkit: forget the museum and stop fetching it for ten minutes. */
    public static void clearForTest() {
        donated = null;
        attemptAt = System.currentTimeMillis();
        PriceTooltipFeature.invalidate();
    }
}
