package com.killer560.hub.profileviewer.item;

import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.LevelTables;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pet head textures. The API pet list only has {@code type}/{@code tier}/{@code skin}, so the icon comes
 * from the NEU item repo (NotEnoughUpdates-REPO {@code items/<TYPE>;<rarity>.json} and
 * {@code items/PET_SKIN_<SKIN>.json}) - the texture blob is pulled out of the item's nbttag. Fetched lazily
 * off-thread, cached per id for the session; a bone is shown until it arrives (or if it never does).
 */
public final class PetIcons {

    private static final String NEU_ITEMS = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/";
    private static final Pattern TEXTURE = Pattern.compile("Value:\"([A-Za-z0-9+/=]{20,})\"");
    private static final Map<String, CompletableFuture<String>> TEXTURES = new ConcurrentHashMap<>();

    private PetIcons() {
    }

    /** @return the texture blob if already known, else null (and starts fetching it). */
    public static String texture(String type, String tier, String skin) {
        String key = skin != null && !skin.isEmpty()
                ? "PET_SKIN_" + skin.toUpperCase(Locale.ROOT)
                : type.toUpperCase(Locale.ROOT) + ";" + Math.min(5, LevelTables.rarityIndex(tier));
        CompletableFuture<String> f = TEXTURES.computeIfAbsent(key, k -> fetch(k, type));
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    public static ItemStack icon(String type, String tier, String skin) {
        String tex = texture(type, tier, skin);
        if (tex == null && skin != null && !skin.isEmpty()) {
            tex = texture(type, tier, null);
        }
        return tex == null ? new ItemStack(Items.BONE) : LegacyItems.skull(tex, null);
    }

    private static CompletableFuture<String> fetch(String key, String type) {
        return fetchOne(key).thenCompose(tex -> {
            if (tex != null || key.startsWith("PET_SKIN_")) {
                return CompletableFuture.completedFuture(tex);
            }
            // Some pets only exist at a few rarities in the repo - the head is the same at every rarity.
            CompletableFuture<String> chain = CompletableFuture.completedFuture(null);
            for (int r = 5; r >= 0; r--) {
                String alt = type.toUpperCase(Locale.ROOT) + ";" + r;
                if (alt.equals(key)) {
                    continue;
                }
                chain = chain.thenCompose(found -> found != null ? CompletableFuture.completedFuture(found) : fetchOne(alt));
            }
            return chain;
        });
    }

    private static CompletableFuture<String> fetchOne(String key) {
        String url = NEU_ITEMS + key.replace(";", "%3B") + ".json";
        return ProfileViewerApi.getKeylessJson(url).thenApply(PetIcons::extract);
    }

    private static String extract(JsonObject json) {
        if (json == null || !json.has("nbttag")) {
            return null;
        }
        Matcher m = TEXTURE.matcher(json.get("nbttag").getAsString());
        return m.find() ? m.group(1) : null;
    }
}
