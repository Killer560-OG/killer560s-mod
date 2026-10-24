package com.killer560.hub.bazaarflip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.gui.profit.ProfitScope;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hypixel's daily cap on coins earned by selling to NPCs (killer560, 2026-10-16: "make sure it has detection for
 * hitting the npc sell limit").
 *
 * <p>hypixelskyblock.minecraft.wiki, read 2026-10-16: Shops - "Player's are limited to only selling up to 500,000,000
 * Coins per day, which resets at 00:00 UTC+0" (raised from 200M on Oct 15, 2025, "for all profiles"); Coins - "Players
 * can only generate 500,000,000 Coins every day, resetting at midnight GMT". Neither page, nor any of the 374 logs in
 * his Dungeons instance, gives the chat line Hypixel shows when it is reached, so {@link #LIMIT_LINE} is a guard, not a
 * transcription (see its doc).
 *
 * <p>Hypixel does not show how much of the cap is used, so this counts it: every "You sold &lt;item&gt; x&lt;n&gt; for
 * &lt;coins&gt; Coins!" line (the real shape, 360+ of them in his logs) adds its coins to today's total for the logged-in
 * account, saved so a restart keeps it. Sales made before this build ran, or from another client, are not seen; the
 * count is then low and the limit line or the refused sale is what stops it. Counted per ACCOUNT, all Skyblock
 * profiles together, because the wiki does not settle per player or per profile and the shared reading is the safe one.
 */
public final class BazaarFlipNpcLimit {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");
    private static final Path FILE = ModPaths.config("killer560smod-bazaarflip-npcsold.json");

    /** Coins a player can earn from NPC sales per UTC day (wiki, Shops and Coins, 2026-10-16). */
    public static final double DAILY_LIMIT = 500_000_000.0;

    /** "You sold Hard Stone x64 for 128 Coins!" - his logs, 2026-10. Item name bounded, counts and coins bounded. */
    public static final Pattern SOLD_LINE =
            Pattern.compile("^You sold (.{1,64}) x([\\d,]{1,7}) for ([\\d,]{1,15}(?:\\.\\d{1,2})?) Coins?!$");

    /**
     * Any SYSTEM line about a daily selling limit or cap. The exact text is not on record (see the class doc), so this
     * matches its likely shape loosely - but never a player's line: every player chat line (all, party, guild, DM) has
     * a "name: " before the message, so a colon anywhere rules a line out and nobody can type this into existence. It
     * only ever STOPS the flipper, the safe direction to be wrong in.
     */
    public static final Pattern LIMIT_LINE = Pattern.compile(
            "(?i)^(?=[^:]{0,200}\\b(?:sell|sold|selling)\\b)(?=[^:]{0,200}\\b(?:limit|cap|maximum)\\b)"
                    + "(?=[^:]{0,200}\\b(?:daily|today|per day|a day|each day)\\b)[^:]{8,200}$");

    private static String day = "";
    private static String account = "";
    private static double soldToday = 0;
    private static boolean loaded = false;

    private BazaarFlipNpcLimit() {
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static synchronized void roll() {
        if (!loaded) {
            loaded = true;
            load();
        }
        String now = today();
        String acc = ProfitScope.currentAccountKey();
        if (!now.equals(day) || !acc.equals(account)) {
            if (!acc.equals(account) && now.equals(day)) {
                // Another account today: its own count (the file keeps one account's day; a switch starts at 0).
                LOGGER.info("[BazaarFlip] NPC sell count is per account - starting {}'s at 0", acc);
            }
            day = now;
            account = acc;
            soldToday = 0;
            save();
        }
    }

    /** Coins counted from NPC sales today (UTC), this account. */
    public static synchronized double soldToday() {
        roll();
        return soldToday;
    }

    /** Coins the NPCs will still pay today, by this count. */
    public static synchronized double remaining() {
        return Math.max(0, DAILY_LIMIT - soldToday());
    }

    /** A "You sold" line: its coins count toward today. */
    public static synchronized void onSold(double coins) {
        roll();
        soldToday += Math.max(0, coins);
        save();
    }

    /** Hypixel said the limit is reached: nothing more today, whatever the count says. */
    public static synchronized void markReached() {
        roll();
        soldToday = Math.max(soldToday, DAILY_LIMIT);
        save();
    }

    /** "5h 12m" until 00:00 UTC, when the cap resets. */
    public static String resetsIn() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        Duration d = Duration.between(now, next);
        return String.format(Locale.US, "%dh %dm", d.toHours(), d.toMinutesPart());
    }

    /** Testkit: pretend {@code coins} were sold today on this account. */
    public static synchronized void setSoldTodayForTest(double coins) {
        if (!BazaarFlipScanner.testHooksAllowed()) {
            LOGGER.warn("[BazaarFlip] Ignored a test NPC-sale count outside a test harness");
            return;
        }
        roll();
        soldToday = Math.max(0, coins);
        save();
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            day = o.has("day") ? o.get("day").getAsString() : "";
            account = o.has("account") ? o.get("account").getAsString() : "";
            soldToday = o.has("soldCoins") ? Math.max(0, o.get("soldCoins").getAsDouble()) : 0;
        } catch (Exception e) {
            LOGGER.warn("[BazaarFlip] Couldn't read {} - counting today's NPC sales from 0", FILE, e);
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("day", day);
            o.addProperty("account", account);
            o.addProperty("soldCoins", soldToday);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[BazaarFlip] Couldn't save {}", FILE, e);
        }
    }
}
