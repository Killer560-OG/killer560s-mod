package com.killer560.hub.partyfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.rngmeter.HypixelApiKeyProvider;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real dungeon Party Finder stats display, ported from Odin's own {@code BetterPartyFinder.kt} - only
 * the real "Stats display"/"Send Kick Line" half. Odin's own "Auto Kick" (automatically kicks players
 * who don't meet configurable stat thresholds, no per-player confirmation) is deliberately NOT ported -
 * per killer560's own standing "keep skipping automation things we can do those later" instruction, and
 * this port's own kick line already covers the entire real, non-automated use case: a clickable chat
 * line that runs the exact same real {@code /party kick <name>} command, but only when killer560 himself
 * clicks it.
 * <p>
 * Real mechanic: the real "Party Finder > X joined the dungeon group! (...)" chat line fires when
 * someone joins your real dungeon party through the Party Finder. This looks up their real Hypixel
 * SkyBlock profile (Mojang name-to-UUID, then the same real Hypixel API key already embedded in this
 * mod for {@link com.killer560.hub.rngmeter.HypixelMarketPrices}'s own AH lookups - see
 * killer560's own correction that a key already existed here) and shows their real Catacombs level and
 * secret count. Deliberately scoped down from Odin's own much larger stats line (which also decodes
 * base64-gzip NBT armor/inventory data and pet/tuning info) to just these two headline numbers - the
 * rest would need a real NBT-item-decoding capability this codebase doesn't have a verified path for
 * yet, matching this session's own established risk bar of not shipping unverifiable new infrastructure.
 */
public final class BetterPartyFinderFeature {

    private static final java.util.regex.Pattern PARTY_FINDER_JOIN_REGEX =
            java.util.regex.Pattern.compile("^Party Finder > (?:\\[.{1,7}])? ?(.{1,16}) joined the dungeon group! \\(.*\\)$");

    // Real, stable Hypixel Catacombs/class leveling XP table (levels 1-50); every level past 50 costs a
    // flat real 200,000,000 XP - the same real table every Skyblock mod (Odin/QUOI/Noamm/SkyHanni) uses.
    private static final long[] DUNGEON_XP_TABLE = {
            50, 75, 110, 160, 230, 330, 470, 670, 950, 1340,
            1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000,
            27600, 38000, 52500, 71500, 97000, 132000, 180000,
            243000, 328000, 445000, 600000, 800000, 1065000,
            1410000, 1900000, 2500000, 3300000, 4300000, 5600000,
            7200000, 9200000, 12000000, 15000000, 19000000,
            24000000, 30000000, 38000000, 48000000, 60000000,
            75000000, 93000000, 116250000, 200000000
    };

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private record CacheEntry(String stats, long timestampMs) {
    }

    private static final Map<String, CacheEntry> STATS_CACHE = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BetterPartyFinderFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
    }

    private static void onChatMessage(Component message) {
        BetterPartyFinderConfig cfg = BetterPartyFinderConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        String raw = message.getString();
        var matcher = PARTY_FINDER_JOIN_REGEX.matcher(raw);
        if (!matcher.matches()) {
            return;
        }
        String name = matcher.group(1);
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || name.equalsIgnoreCase(client.player.getName().getString())) {
            return;
        }

        CacheEntry cached = STATS_CACHE.get(name.toLowerCase(Locale.ROOT));
        if (cached != null && System.currentTimeMillis() - cached.timestampMs() < CACHE_TTL_MS) {
            display(client, name, cached.stats(), cfg.isShowKickButton());
            return;
        }

        CompletableFuture.supplyAsync(() -> fetchStatsLine(name)).thenAccept(statsLine -> {
            if (statsLine == null) {
                return;
            }
            STATS_CACHE.put(name.toLowerCase(Locale.ROOT), new CacheEntry(statsLine, System.currentTimeMillis()));
            client.execute(() -> display(Minecraft.getInstance(), name, statsLine, cfg.isShowKickButton()));
        });
    }

    private static void display(Minecraft client, String name, String statsLine, boolean showKickButton) {
        if (client.player == null) {
            return;
        }
        Component message = Component.literal("§6[Party Finder] §b" + name + "§7: " + statsLine);
        if (showKickButton) {
            message = message.copy().append(Component.literal(" §c[Kick]").withStyle(style ->
                    style.withClickEvent(new ClickEvent.RunCommand("/party kick " + name))));
        }
        client.player.sendSystemMessage(message);
    }

    /** Runs on a background thread - real Mojang name-to-UUID lookup, then the real Hypixel SkyBlock
     *  profiles endpoint using the mod's existing embedded key. Returns null on any failure (no API
     *  key issue, private API, unknown name, network error) - a failed lookup just means no stats line
     *  is shown, never a crash or a stuck "loading" state. */
    private static String fetchStatsLine(String name) {
        try {
            JsonObject uuidResponse = getJson("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
            if (uuidResponse == null || !uuidResponse.has("id")) {
                return null;
            }
            String uuid = uuidResponse.get("id").getAsString();

            JsonObject profilesResponse = getJson("https://api.hypixel.net/v2/skyblock/profiles?key="
                    + HypixelApiKeyProvider.getKey() + "&uuid=" + uuid);
            if (profilesResponse == null || !profilesResponse.has("success")
                    || !profilesResponse.get("success").getAsBoolean() || !profilesResponse.has("profiles")) {
                return null;
            }
            JsonArray profiles = profilesResponse.getAsJsonArray("profiles");
            if (profiles == null) {
                return null;
            }
            JsonObject selected = null;
            for (JsonElement el : profiles) {
                JsonObject profile = el.getAsJsonObject();
                if (profile.has("selected") && profile.get("selected").getAsBoolean()) {
                    selected = profile;
                    break;
                }
            }
            if (selected == null) {
                return null;
            }
            JsonObject member = selected.getAsJsonObject("members").getAsJsonObject(uuid);
            if (member == null || !member.has("dungeons")) {
                return "§7No dungeon data (private API or new player)";
            }
            JsonObject dungeons = member.getAsJsonObject("dungeons");
            double cataXp = 0.0;
            if (dungeons.has("dungeon_types")) {
                JsonObject dungeonTypes = dungeons.getAsJsonObject("dungeon_types");
                if (dungeonTypes.has("catacombs")) {
                    JsonObject catacombs = dungeonTypes.getAsJsonObject("catacombs");
                    if (catacombs.has("experience")) {
                        cataXp = catacombs.get("experience").getAsDouble();
                    }
                }
            }
            long secrets = dungeons.has("secrets") ? dungeons.get("secrets").getAsLong() : 0;
            double cataLevel = calculateDungeonLevel(cataXp);
            return String.format(Locale.US, "§eCata %.1f §8| §aSecrets %,d", cataLevel, secrets);
        } catch (Exception e) {
            return null;
        }
    }

    private static double calculateDungeonLevel(double xp) {
        if (xp <= 0) {
            return 0.0;
        }
        double totalXp = 0.0;
        for (int level = 0; level < DUNGEON_XP_TABLE.length; level++) {
            long requiredXp = DUNGEON_XP_TABLE[level];
            if (xp < totalXp + requiredXp) {
                return level + (xp - totalXp) / requiredXp;
            }
            totalXp += requiredXp;
        }
        return DUNGEON_XP_TABLE.length + ((xp - totalXp) / 200_000_000.0);
    }

    private static JsonObject getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Killer560sMod-PartyFinder/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
