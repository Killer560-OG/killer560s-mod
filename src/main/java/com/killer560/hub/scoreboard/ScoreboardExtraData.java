package com.killer560.hub.scoreboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game data the Custom Scoreboard needs beyond the sidebar/tab list, cached and persisted to
 * {@code config/killer560smod-customscoreboard/data.json} so it survives restarts (SkyHanni keeps these in its
 * profile storage; one global cache here):
 * <ul>
 * <li><b>Mayor / Minister</b> - Hypixel's keyless {@code /v2/resources/skyblock/election} (SkyHanni's
 * {@code ElectionApi.hypixelElectionApiStatic}), refetched every 10 minutes while the Mayor line is on;</li>
 * <li><b>Cookie Buff</b> - {@code EffectsAPI} (SkyBlockAPI) / {@code BitsApi} (SkyHanni): the tab footer's "Cookie Buff"
 * block, the Active Effects tab widget, the SkyBlock Menu's Booster Cookie item and the "You consumed a Booster
 * Cookie!" chat line;</li>
 * <li><b>Maxwell power, Magical Power and tunings</b> - {@code MaxwellApi}: the "Your Bags" menu's Accessory Bag item,
 * the "Accessory Bag Thaumaturgy" and "Stats Tuning" menus, and the power-selected chat lines;</li>
 * <li><b>Quiver</b> - {@code QuiverApi}: the "Active Arrow: X (N)" / "Arrows Remaining: N" lore Hypixel puts on the
 * quiver preview item in your inventory. {@code quiver/QuiverDisplayFeature} scans the same lore but keeps its values
 * private, so the scan is mirrored here instead of reaching into it.</li>
 * </ul>
 */
public final class ScoreboardExtraData {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-customscoreboard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = CustomScoreboardConfig.DATA_DIR.resolve("data.json");
    private static final URI ELECTION_URI = URI.create("https://api.hypixel.net/v2/resources/skyblock/election");

    // ---- SkyBlock calendar (SkyHanni SkyBlockTime) ----
    private static final long SKYBLOCK_EPOCH_MS = 1559829300000L;
    private static final long SKYBLOCK_YEAR_MS = 124L * 60 * 60 * 1000;
    private static final long SKYBLOCK_MONTH_MS = SKYBLOCK_YEAR_MS / 12;
    private static final long SKYBLOCK_DAY_MS = SKYBLOCK_MONTH_MS / 31;
    /** New mayor takes office on Late Spring (month 3) 27th. */
    private static final long ELECTION_END_OFFSET_MS = 2 * SKYBLOCK_MONTH_MS + 26 * SKYBLOCK_DAY_MS;

    private static final long ELECTION_REFRESH_MS = 10 * 60 * 1000L;
    private static final long ELECTION_RETRY_MS = 2 * 60 * 1000L;

    // ---- patterns (plain text unless noted) ----
    private static final Pattern DURATION_PART = Pattern.compile(
            "(\\d+)\\s*(years?|y|days?|d|hours?|h|minutes?|mins?|m|seconds?|secs?|s)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_WIDGET = Pattern.compile("\\s*Cookie Buff: (?<duration>.*)");
    private static final Pattern COOKIE_DURATION_LORE = Pattern.compile("\\s*Duration: (?<duration>.*)");
    private static final Pattern COOKIE_NOT_ACTIVE_LORE = Pattern.compile("\\s*Status: Not active!.*");
    private static final Pattern COOKIE_EATEN = Pattern.compile("You consumed a Booster Cookie!.*");
    private static final Pattern CHAT_POWER = Pattern.compile("You selected the (?<power>.+?) (?:power )?for your Accessory Bag!");
    private static final Pattern CHAT_POWER_UNLOCKED = Pattern.compile("Your selected power was set to (?<power>.+)!");
    private static final Pattern THAUMATURGY_TITLE = Pattern.compile("(?:\\(\\d+/\\d+\\) )?Accessory Bag Thaumaturgy");
    private static final Pattern YOUR_BAGS_TITLE = Pattern.compile("Your Bags");
    private static final Pattern STATS_TUNING_TITLE = Pattern.compile("Stats Tuning");
    private static final Pattern INVENTORY_POWER = Pattern.compile("Selected Power: (?<power>.+)");
    private static final Pattern INVENTORY_MP = Pattern.compile("Accessory Power: (?<mp>[\\d,]+)");
    private static final Pattern THAUMATURGY_MP = Pattern.compile("Total: (?<mp>[\\d.,]+) Accessory Power");
    private static final Pattern POWER_SELECTED = Pattern.compile("Power is selected!");
    private static final Pattern NO_POWER = Pattern.compile(".*Visit Maxwell in the Hub to learn.*");
    private static final Pattern TUNING_START = Pattern.compile("Your tuning:");
    /** Legacy-formatted lore. */
    private static final Pattern TUNING_DATA = Pattern.compile("(?:§.)*§(?<color>[0-9a-fA-F])\\+(?<amount>[^ ]+?)(?:§.)*(?<icon>[^\\s§]) (?:§.)*(?<name>.+)");
    /** Legacy-formatted lore. */
    private static final Pattern STATS_TUNING_DATA = Pattern.compile(".*You have: .+\\+ (?:§.)*§(?<color>[0-9a-fA-F])(?<amount>[\\d.,]+) (?:§.)*(?<icon>\\S)\\s*");
    private static final Pattern TUNING_NAME = Pattern.compile(". (?<name>.+)");
    private static final Pattern ACTIVE_ARROW = Pattern.compile("Active Arrow: (?<type>.*) \\((?<amount>[\\d,]+)\\)");
    private static final Pattern ARROWS_REMAINING = Pattern.compile("Arrows Remaining: (?<amount>[\\d,]+)");
    private static final Pattern ARROW_RAN_OUT = Pattern.compile("QUIVER! You have run out of (?<type>.+)s!");
    private static final Pattern QUIVER_CLEARED = Pattern.compile("Cleared your quiver!|Your quiver is now completely empty!");
    /** BitsApi.bitsAvailableMenuPattern (plain). */
    private static final Pattern BITS_AVAILABLE_LORE = Pattern.compile("\\s*Bits Available: (?<amount>[\\d,]+).*");
    /** BitsApi.bitsFromFameRankUpChatPattern (plain). */
    private static final Pattern BITS_AVAILABLE_CHAT = Pattern.compile("You gained (?<amount>[\\d,]+) Bits Available compounded from all your previously eaten cookies!.*");
    private static final Pattern FAME_RANK_MENU_TITLE = Pattern.compile("Community Shop|Booster Cookie");
    /** HotmData heart/reset item names and HotmApi.PowderType heartPattern/resetPattern (plain). */
    private static final Pattern HOTM_TITLE = Pattern.compile("Heart of the Mountain");
    private static final Pattern HOTM_HEART_POWDER = Pattern.compile(".*?(?<type>Mithril|Gemstone|Glacite) Powder: (?<powder>[\\d,]+).*");
    private static final Pattern HOTM_RESET_POWDER = Pattern.compile("\\s*- (?<powder>[\\d,]+) (?<type>Mithril|Gemstone|Glacite) Powder.*");
    /** CalendarApi.calendarGuiPattern / ElectionApi.mayorHeadPattern / perkpocalypsePerksPattern (plain). */
    private static final Pattern CALENDAR_TITLE = Pattern.compile("Calendar and Events");
    private static final Pattern PERKPOCALYPSE_PERKS = Pattern.compile("\\s*Perkpocalypse Perks:\\s*");
    static final String[] POWDER_TYPES = {"Mithril", "Gemstone", "Glacite"};
    private static final long SIX_HOURS_MS = 6L * 60 * 60 * 1000;

    public record Perk(String name, String description) {
    }

    public record Candidate(String name, List<Perk> perks) {
    }

    public record Tuning(String value, String color, String name, String icon) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static volatile Candidate mayor;
    private static volatile Candidate minister;
    private static volatile long electionFetchedAtMs = 0L;
    private static volatile String electionJson = null;
    private static volatile boolean electionInFlight = false;
    private static long electionAttemptAtMs = 0L;

    /** -1 unknown, 0 not active, else epoch ms the buff expires. */
    private static long cookieExpiresAtMs = -1L;
    private static String maxwellPower = null;
    private static int magicalPower = -1;
    /** null = never seen. */
    private static List<Tuning> tunings = null;
    private static String quiverArrow = null;
    private static int quiverAmount = -1;
    private static boolean hasBow = false;
    private static boolean wearingSkeletonMaster = false;
    /** -1 = never seen (SkyBlock Menu / Community Shop / Booster Cookie menus). */
    private static long bitsAvailable = -1L;
    private static long lastBits = -1L;
    /** Total powder per {@link #POWDER_TYPES} (available + spent in the tree), -1 unknown. */
    private static final long[] powderTotal = {-1L, -1L, -1L};
    private static final long[] lastPowder = {-1L, -1L, -1L};
    /** Perkpocalypse (Mayor Jerry) mayor name, or null, and when it expires. */
    private static String jerryMayor = null;
    private static long jerryMayorExpiresAtMs = 0L;

    private static boolean loaded = false;
    private static volatile boolean dirty = false;
    private static long lastSaveMs = 0L;
    private static int tickCounter = 0;

    private ScoreboardExtraData() {
    }

    static void register() {
        load();
        ChatObserver.subscribe(ScoreboardExtraData::onChat);
        ScoreboardPartyLeader.register();
    }

    // ---- tick ----

    /** Called from the Custom Scoreboard tick (every 5 ticks, only while enabled). */
    static void update(Minecraft client, boolean onSkyblock) {
        load();
        if (onSkyblock && client.player != null) {
            try {
                readTabCookie();
                readScreen(client);
                trackBits();
                trackPowder();
                if (++tickCounter >= 2) {
                    tickCounter = 0;
                    readInventory(client.player);
                }
                CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
                if (cfg.isEntryEnabled(ScoreboardEntry.MAYOR)) {
                    maybeFetchElection();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[CustomScoreboard] Extra data read failed: {}", e.toString());
            }
        }
        long now = System.currentTimeMillis();
        if (dirty && now - lastSaveMs > 5000L) {
            save();
        }
    }

    // ---- bits available / powder totals (SkyHanni BitsApi.updateBits / HotmApi.PowderType.setAmount) ----

    /** Bits gained on the sidebar come out of "Bits Available" (claimed from the cookie buff). */
    private static void trackBits() {
        String raw = ChunkedStat.BITS.raw();
        if (raw == null) {
            return;
        }
        long bits = parseLong(raw);
        if (bits < 0) {
            return;
        }
        if (lastBits >= 0 && bits > lastBits && bitsAvailable > 0) {
            bitsAvailable = Math.max(0L, bitsAvailable - (bits - lastBits));
            dirty = true;
        }
        lastBits = bits;
    }

    /** Powder gained while mining raises the total; spending it in the tree doesn't. */
    private static void trackPowder() {
        if (!ScoreboardData.inIsland("Dwarven Mines", "Crystal Hollows", "Mineshaft")) {
            return;
        }
        long[] current = ScoreboardEntry.powderAmounts();
        for (int i = 0; i < POWDER_TYPES.length; i++) {
            if (current[i] < 0) {
                continue;
            }
            if (lastPowder[i] >= 0 && current[i] > lastPowder[i] && powderTotal[i] >= 0) {
                powderTotal[i] += current[i] - lastPowder[i];
                dirty = true;
            }
            lastPowder[i] = current[i];
        }
    }

    private static void setBitsAvailable(long amount) {
        if (amount >= 0 && amount != bitsAvailable) {
            bitsAvailable = amount;
            dirty = true;
        }
    }

    /** Any item in the open menu carrying a "Bits Available: N" lore line. */
    private static void readBitsAvailable(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            for (String line : plainLore(slot.getItem())) {
                Matcher m = BITS_AVAILABLE_LORE.matcher(line);
                if (m.matches()) {
                    setBitsAvailable(parseLong(m.group("amount")));
                    return;
                }
            }
        }
    }

    /** HOTM tree: total = available (Heart of the Mountain item) + refundable (Reset Heart of the Mountain item). */
    private static void readHotm(List<Slot> slots) {
        long[] available = {-1L, -1L, -1L};
        long[] spent = {0L, 0L, 0L};
        boolean resetSeen = false;
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            String name = plainName(slot.getItem()).trim();
            boolean heart = name.equals("Heart of the Mountain");
            boolean reset = name.equals("Reset Heart of the Mountain");
            if (!heart && !reset) {
                continue;
            }
            resetSeen |= reset;
            for (String line : plainLore(slot.getItem())) {
                Matcher m = (heart ? HOTM_HEART_POWDER : HOTM_RESET_POWDER).matcher(line);
                if (m.matches()) {
                    int idx = powderIndex(m.group("type"));
                    long value = parseLong(m.group("powder"));
                    if (idx >= 0 && value >= 0) {
                        if (heart) {
                            available[idx] = value;
                        } else {
                            spent[idx] = value;
                        }
                    }
                }
            }
        }
        if (!resetSeen) {
            return;
        }
        for (int i = 0; i < POWDER_TYPES.length; i++) {
            if (available[i] >= 0 && powderTotal[i] != available[i] + spent[i]) {
                powderTotal[i] = available[i] + spent[i];
                lastPowder[i] = available[i];
                dirty = true;
            }
        }
    }

    static int powderIndex(String type) {
        for (int i = 0; i < POWDER_TYPES.length; i++) {
            if (POWDER_TYPES[i].equals(type)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Perkpocalypse mayor (SkyHanni ElectionApi.jerryExtraMayor) ----

    /** Calendar's "Mayor Jerry" item: the first Perkpocalypse perk (2 lines under the header) names the extra mayor. */
    private static void readCalendar(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || !"Mayor Jerry".equals(plainName(slot.getItem()).trim())) {
                continue;
            }
            List<String> lore = plainLore(slot.getItem());
            for (int i = 0; i + 2 < lore.size(); i++) {
                if (!PERKPOCALYPSE_PERKS.matcher(lore.get(i)).matches()) {
                    continue;
                }
                String name = mayorFromPerk(lore.get(i + 2).trim());
                if (name == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                long nextMayor = now + timeUntilNextMayorMs();
                long lastMayor = nextMayor - SKYBLOCK_YEAR_MS;
                long expires = -1L;
                for (int k = 1; k <= 21; k++) {
                    long t = lastMayor + SIX_HOURS_MS * k;
                    if (t > now) {
                        expires = Math.min(t, nextMayor);
                        break;
                    }
                }
                if (expires > 0 && (!name.equals(jerryMayor) || expires != jerryMayorExpiresAtMs)) {
                    jerryMayor = name;
                    jerryMayorExpiresAtMs = expires;
                    dirty = true;
                }
                return;
            }
        }
    }

    /** SkyHanni {@code ElectionCandidate.getMayorFromPerk} for the mayors Perkpocalypse can pick. */
    static String mayorFromPerk(String perk) {
        return switch (perk) {
            case "SLASHED Pricing", "Slayer XP Buff", "Pathfinder" -> "Aatrox";
            case "Prospection", "Mining XP Buff", "Mining Fiesta", "Molten Forge" -> "Cole";
            case "Huntress' Intuition", "Mythological Ritual", "Pet XP Buff", "Sharing is Caring" -> "Diana";
            case "Volume Trading", "Shopping Spree", "Stock Exchange", "Long Term Investment" -> "Diaz";
            case "Pelt-pocalypse", "Grand Feast", "GOATed", "Blooming Business", "Pest Eradicator" -> "Finnegan";
            case "Sweet Benevolence", "A Time for Giving", "Chivalrous Carnival", "Extra Event (Mining)",
                 "Extra Event (Fishing)", "Extra Event (Spooky)" -> "Foxy";
            case "Fishing XP Buff", "Luck of the Sea 2.0", "Fishing Festival", "Double Trouble" -> "Marina";
            case "Marauder", "EZPZ", "Benediction" -> "Paul";
            case "Bribe", "Darker Auctions" -> "Scorpius";
            case "TURBO MINIONS!!!", "QUAD TAXES!!!", "DOUBLE MOBS HP!!!", "MOAR SKILLZ!!!" -> "Derpy";
            case "Fundraising", "Minion Union", "Universal Income", "Work Better", "Work Harder", "Work Smarter" -> "Aura";
            default -> null;
        };
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // ---- cookie buff ----

    private static void readTabCookie() {
        String footer = ScoreboardData.tabFooter();
        if (!footer.isEmpty()) {
            List<List<String>> chunks = new ArrayList<>();
            List<String> chunk = new ArrayList<>();
            for (String line : footer.split("\n")) {
                if (line.isBlank()) {
                    if (!chunk.isEmpty()) {
                        chunks.add(chunk);
                        chunk = new ArrayList<>();
                    }
                } else {
                    chunk.add(line.trim());
                }
            }
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            for (List<String> c : chunks) {
                int header = -1;
                for (int i = 0; i < c.size(); i++) {
                    if (c.get(i).equals("Cookie Buff")) {
                        header = i;
                        break;
                    }
                }
                if (header < 0 || header + 1 >= c.size()) {
                    continue;
                }
                String body = String.join(" ", c.subList(header + 1, c.size()));
                if (body.contains("Not active")) {
                    setCookieNotActive();
                } else {
                    long d = parseDuration(c.get(c.size() - 1));
                    if (d > 0) {
                        offerCookieDuration(d, false);
                    }
                }
                break;
            }
        }
        for (String line : ScoreboardData.tabPlain()) {
            Matcher m = COOKIE_WIDGET.matcher(line);
            if (m.matches()) {
                String duration = m.group("duration");
                if (duration.contains("Not active") || duration.contains("INACTIVE")) {
                    setCookieNotActive();
                } else {
                    long d = parseDuration(duration);
                    if (d > 0) {
                        offerCookieDuration(d, false);
                    }
                }
                break;
            }
        }
    }

    private static void setCookieNotActive() {
        if (cookieExpiresAtMs != 0L) {
            cookieExpiresAtMs = 0L;
            dirty = true;
        }
    }

    /** Coarse sources ("3 Days, 2 Hours") only replace a close existing value when they're later; exact ones always. */
    private static void offerCookieDuration(long durationMs, boolean exact) {
        long expires = System.currentTimeMillis() + durationMs;
        long old = cookieExpiresAtMs;
        if (exact || old <= 0 || expires > old || Math.abs(expires - old) > 70 * 60 * 1000L) {
            if (Math.abs(expires - old) > 1000L) {
                cookieExpiresAtMs = expires;
                dirty = true;
            }
        }
    }

    /** "3d 17h 2m", "3 Days, 17 Hours" ... in ms, or -1. */
    static long parseDuration(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = DURATION_PART.matcher(text);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            char unit = Character.toLowerCase(m.group(2).charAt(0));
            total += switch (unit) {
                case 'y' -> n * 365L * 86_400_000L;
                case 'd' -> n * 86_400_000L;
                case 'h' -> n * 3_600_000L;
                case 'm' -> n * 60_000L;
                default -> n * 1000L;
            };
            any = true;
        }
        return any ? total : -1;
    }

    // ---- chat ----

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain == null) {
            return;
        }
        plain = plain.trim();
        Matcher m;
        if (COOKIE_EATEN.matcher(plain).matches()) {
            long now = System.currentTimeMillis();
            cookieExpiresAtMs = Math.max(now, cookieExpiresAtMs) + 4L * 86_400_000L;
            dirty = true;
        } else if ((m = CHAT_POWER.matcher(plain)).matches() || (m = CHAT_POWER_UNLOCKED.matcher(plain)).matches()) {
            maxwellPower = m.group("power").trim();
            dirty = true;
        } else if ((m = ARROW_RAN_OUT.matcher(plain)).matches()) {
            if (quiverArrow != null && quiverArrow.equalsIgnoreCase(m.group("type"))) {
                quiverAmount = 0;
                dirty = true;
            }
        } else if (QUIVER_CLEARED.matcher(plain).matches()) {
            quiverAmount = 0;
            dirty = true;
        } else if ((m = BITS_AVAILABLE_CHAT.matcher(plain)).matches()) {
            long gained = parseLong(m.group("amount"));
            if (gained > 0) {
                bitsAvailable = Math.max(0L, bitsAvailable) + gained;
                dirty = true;
            }
        }
    }

    // ---- menus ----

    private static void readScreen(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        if (title == null) {
            return;
        }
        title = title.trim();
        List<Slot> slots = screen.getMenu().slots;
        if (title.equals("SkyBlock Menu")) {
            readBitsAvailable(slots);
            for (Slot slot : slots) {
                if (slot.container instanceof Inventory || !"Booster Cookie".equals(plainName(slot.getItem()))) {
                    continue;
                }
                for (String line : plainLore(slot.getItem())) {
                    Matcher m = COOKIE_DURATION_LORE.matcher(line);
                    if (m.matches()) {
                        long d = parseDuration(m.group("duration"));
                        if (d > 0) {
                            offerCookieDuration(d, true);
                        }
                    } else if (COOKIE_NOT_ACTIVE_LORE.matcher(line).matches()) {
                        setCookieNotActive();
                    }
                }
            }
        } else if (THAUMATURGY_TITLE.matcher(title).matches()) {
            readThaumaturgy(slots);
        } else if (YOUR_BAGS_TITLE.matcher(title).matches()) {
            readYourBags(slots);
        } else if (STATS_TUNING_TITLE.matcher(title).matches()) {
            readStatsTuning(slots);
        } else if (FAME_RANK_MENU_TITLE.matcher(title).matches()) {
            readBitsAvailable(slots);
        } else if (HOTM_TITLE.matcher(title).matches()) {
            readHotm(slots);
        } else if (CALENDAR_TITLE.matcher(title).matches()) {
            readCalendar(slots);
        }
    }

    private static void readThaumaturgy(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            List<String> lore = plainLore(slot.getItem());
            if (!lore.isEmpty() && POWER_SELECTED.matcher(lore.get(lore.size() - 1)).matches()) {
                String name = plainName(slot.getItem()).trim();
                if (!name.isEmpty() && !name.equals(maxwellPower)) {
                    maxwellPower = name;
                    dirty = true;
                }
            }
        }
        ItemStack mpItem = containerItem(slots, 48);
        if (mpItem != null) {
            for (String line : plainLore(mpItem)) {
                Matcher m = THAUMATURGY_MP.matcher(line);
                if (m.matches()) {
                    setMagicalPower(parseInt(m.group("mp")));
                }
            }
        }
        // Rounded values: only used while nothing better (the Stats Tuning menu) has been seen.
        ItemStack tuningItem = containerItem(slots, 51);
        if (tuningItem != null && (tunings == null || tunings.isEmpty())) {
            List<Tuning> found = new ArrayList<>();
            boolean active = false;
            for (Component line : lore(tuningItem)) {
                String plain = strip(line);
                if (TUNING_START.matcher(plain.trim()).matches()) {
                    active = true;
                    continue;
                }
                if (!active) {
                    continue;
                }
                if (plain.isBlank()) {
                    break;
                }
                Matcher m = TUNING_DATA.matcher(ScoreboardData.removeResets(ScoreboardData.formatted(line, true)).trim());
                if (m.matches()) {
                    found.add(new Tuning(m.group("amount"), "§" + m.group("color").toLowerCase(Locale.ROOT),
                            m.group("name").replaceAll("§.", "").trim(), m.group("icon")));
                }
            }
            if (active) {
                tunings = List.copyOf(found);
                dirty = true;
            }
        }
    }

    private static void readYourBags(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || !"Accessory Bag".equals(plainName(slot.getItem()))) {
                continue;
            }
            boolean foundMp = false;
            for (String line : plainLore(slot.getItem())) {
                String t = line.trim();
                if (NO_POWER.matcher(t).matches()) {
                    setPower("No Power");
                }
                Matcher m = INVENTORY_MP.matcher(t);
                if (m.matches()) {
                    foundMp = true;
                    // Magical Power is boosted inside dungeons.
                    if (!ScoreboardData.inIsland("Catacombs")) {
                        setMagicalPower(parseInt(m.group("mp")));
                    }
                }
                m = INVENTORY_POWER.matcher(t);
                if (m.matches()) {
                    setPower(m.group("power").trim());
                }
            }
            if (!foundMp && magicalPower != 0) {
                magicalPower = 0;
                tunings = List.of();
                dirty = true;
            }
        }
    }

    private static void readStatsTuning(List<Slot> slots) {
        List<Tuning> found = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            Matcher nameMatcher = TUNING_NAME.matcher(plainName(slot.getItem()).trim());
            for (Component line : lore(slot.getItem())) {
                Matcher m = STATS_TUNING_DATA.matcher(ScoreboardData.removeResets(ScoreboardData.formatted(line, true)));
                if (m.matches()) {
                    String name = nameMatcher.matches() ? nameMatcher.group("name") : "<missing>";
                    found.add(new Tuning(m.group("amount"), "§" + m.group("color").toLowerCase(Locale.ROOT), name, m.group("icon")));
                }
            }
        }
        if (!found.isEmpty() && !found.equals(tunings)) {
            tunings = List.copyOf(found);
            dirty = true;
        }
    }

    private static ItemStack containerItem(List<Slot> slots, int index) {
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        Slot slot = slots.get(index);
        return slot.container instanceof Inventory || slot.getItem().isEmpty() ? null : slot.getItem();
    }

    private static void setPower(String power) {
        if (!power.equals(maxwellPower)) {
            maxwellPower = power;
            dirty = true;
        }
    }

    private static void setMagicalPower(int mp) {
        if (mp >= 0 && mp != magicalPower) {
            magicalPower = mp;
            dirty = true;
        }
    }

    // ---- inventory (quiver) ----

    private static void readInventory(Player player) {
        boolean bow = false;
        String arrow = null;
        int amount = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == Items.BOW) {
                bow = true;
            }
            if (arrow != null) {
                continue;
            }
            for (String line : plainLore(stack)) {
                Matcher m = ACTIVE_ARROW.matcher(line.trim());
                if (m.matches()) {
                    arrow = m.group("type").trim();
                    amount = parseInt(m.group("amount"));
                    break;
                }
                m = ARROWS_REMAINING.matcher(line.trim());
                if (m.matches()) {
                    arrow = plainName(stack).trim();
                    amount = parseInt(m.group("amount"));
                    break;
                }
            }
        }
        hasBow = bow;
        wearingSkeletonMaster = plainName(player.getItemBySlot(EquipmentSlot.CHEST)).contains("Skeleton Master Chestplate");
        if (arrow != null && !arrow.isEmpty() && amount >= 0 && (!arrow.equals(quiverArrow) || amount != quiverAmount)) {
            quiverArrow = arrow;
            quiverAmount = amount;
            dirty = true;
        }
    }

    // ---- election ----

    private static void maybeFetchElection() {
        long now = System.currentTimeMillis();
        if (electionInFlight) {
            return;
        }
        boolean stale = mayor == null || now - electionFetchedAtMs > ELECTION_REFRESH_MS;
        if (!stale || now - electionAttemptAtMs < ELECTION_RETRY_MS) {
            return;
        }
        electionAttemptAtMs = now;
        electionInFlight = true;
        HttpRequest request = HttpRequest.newBuilder(ELECTION_URI)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "killer560smod")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                if (error != null || response == null || response.statusCode() != 200) {
                    LOGGER.debug("[CustomScoreboard] Election fetch failed: {}",
                            error != null ? error.toString() : response == null ? "no response" : "HTTP " + response.statusCode());
                    return;
                }
                if (parseElection(response.body())) {
                    electionJson = response.body();
                    electionFetchedAtMs = System.currentTimeMillis();
                    dirty = true;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[CustomScoreboard] Election parse failed: {}", e.toString());
            } finally {
                electionInFlight = false;
            }
        });
    }

    private static boolean parseElection(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!ConfigJson.getBool(root, "success", false)) {
            return false;
        }
        JsonObject mayorObj = ConfigJson.getObject(root, "mayor");
        if (mayorObj == null) {
            return false;
        }
        List<Perk> perks = new ArrayList<>();
        JsonArray perksArray = ConfigJson.getArray(mayorObj, "perks");
        if (perksArray != null) {
            for (JsonElement el : perksArray) {
                if (el.isJsonObject()) {
                    perks.add(perk(el.getAsJsonObject()));
                }
            }
        }
        Candidate newMayor = new Candidate(ConfigJson.getString(mayorObj, "name", "Unknown"), List.copyOf(perks));
        Candidate newMinister = null;
        JsonObject ministerObj = ConfigJson.getObject(mayorObj, "minister");
        if (ministerObj != null) {
            JsonObject perkObj = ConfigJson.getObject(ministerObj, "perk");
            newMinister = new Candidate(ConfigJson.getString(ministerObj, "name", "Unknown"),
                    perkObj == null ? List.of() : List.of(perk(perkObj)));
        }
        mayor = newMayor;
        minister = newMinister;
        return true;
    }

    private static Perk perk(JsonObject obj) {
        return new Perk(ConfigJson.getString(obj, "name", "?"), ConfigJson.getString(obj, "description", ""));
    }

    /** Milliseconds until the next mayor takes office (Late Spring 27th). */
    static long timeUntilNextMayorMs() {
        long now = System.currentTimeMillis();
        long intoYear = Math.floorMod(now - SKYBLOCK_EPOCH_MS, SKYBLOCK_YEAR_MS);
        long delta = ELECTION_END_OFFSET_MS - intoYear;
        return delta <= 0 ? delta + SKYBLOCK_YEAR_MS : delta;
    }

    // ---- persistence ----

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(DATA_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(DATA_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cookieExpiresAtMs = ConfigJson.getLong(obj, "cookieExpiresAtMs", -1L);
            maxwellPower = ConfigJson.getString(obj, "maxwellPower", null);
            magicalPower = ConfigJson.getInt(obj, "magicalPower", -1);
            JsonArray tuningArray = ConfigJson.getArray(obj, "tunings");
            if (tuningArray != null) {
                List<Tuning> list = new ArrayList<>();
                for (JsonElement el : tuningArray) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject t = el.getAsJsonObject();
                    list.add(new Tuning(ConfigJson.getString(t, "value", "0"), ConfigJson.getString(t, "color", "§f"),
                            ConfigJson.getString(t, "name", "?"), ConfigJson.getString(t, "icon", "")));
                }
                tunings = List.copyOf(list);
            }
            quiverArrow = ConfigJson.getString(obj, "quiverArrow", null);
            quiverAmount = ConfigJson.getInt(obj, "quiverAmount", -1);
            bitsAvailable = ConfigJson.getLong(obj, "bitsAvailable", -1L);
            JsonArray powderArray = ConfigJson.getArray(obj, "powderTotal");
            if (powderArray != null) {
                for (int i = 0; i < powderTotal.length && i < powderArray.size(); i++) {
                    try {
                        powderTotal[i] = powderArray.get(i).getAsLong();
                    } catch (RuntimeException ignored) {
                        powderTotal[i] = -1L;
                    }
                }
            }
            jerryMayor = ConfigJson.getString(obj, "jerryMayor", null);
            jerryMayorExpiresAtMs = ConfigJson.getLong(obj, "jerryMayorExpiresAtMs", 0L);
            String election = ConfigJson.getString(obj, "electionJson", null);
            if (election != null) {
                try {
                    if (parseElection(election)) {
                        electionJson = election;
                        electionFetchedAtMs = ConfigJson.getLong(obj, "electionFetchedAtMs", 0L);
                    }
                } catch (RuntimeException ignored) {
                    // stale/garbled cache: refetch
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomScoreboard] Couldn't read {}: {}", DATA_PATH.getFileName(), e.toString());
        }
    }

    private static void save() {
        dirty = false;
        lastSaveMs = System.currentTimeMillis();
        try {
            Files.createDirectories(DATA_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("cookieExpiresAtMs", cookieExpiresAtMs);
            if (maxwellPower != null) {
                obj.addProperty("maxwellPower", maxwellPower);
            }
            obj.addProperty("magicalPower", magicalPower);
            if (tunings != null) {
                JsonArray array = new JsonArray();
                for (Tuning t : tunings) {
                    JsonObject o = new JsonObject();
                    o.addProperty("value", t.value());
                    o.addProperty("color", t.color());
                    o.addProperty("name", t.name());
                    o.addProperty("icon", t.icon());
                    array.add(o);
                }
                obj.add("tunings", array);
            }
            if (quiverArrow != null) {
                obj.addProperty("quiverArrow", quiverArrow);
            }
            obj.addProperty("quiverAmount", quiverAmount);
            obj.addProperty("bitsAvailable", bitsAvailable);
            JsonArray powderArray = new JsonArray();
            for (long total : powderTotal) {
                powderArray.add(total);
            }
            obj.add("powderTotal", powderArray);
            if (jerryMayor != null) {
                obj.addProperty("jerryMayor", jerryMayor);
                obj.addProperty("jerryMayorExpiresAtMs", jerryMayorExpiresAtMs);
            }
            String election = electionJson;
            if (election != null) {
                obj.addProperty("electionJson", election);
                obj.addProperty("electionFetchedAtMs", electionFetchedAtMs);
            }
            Files.writeString(DATA_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[CustomScoreboard] Couldn't save {}: {}", DATA_PATH.getFileName(), e.toString());
        }
    }

    // ---- item helpers ----

    private static List<Component> lore(ItemStack stack) {
        ItemLore lore = stack == null ? null : stack.get(DataComponents.LORE);
        return lore == null ? Collections.emptyList() : lore.lines();
    }

    private static List<String> plainLore(ItemStack stack) {
        List<Component> lines = lore(stack);
        List<String> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            out.add(strip(line));
        }
        return out;
    }

    private static String plainName(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : strip(stack.getHoverName());
    }

    private static String strip(Component c) {
        String s = ChatFormatting.stripFormatting(c.getString());
        return s == null ? "" : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replace(",", "").replace(".", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- accessors ----

    public static Candidate mayor() {
        return mayor;
    }

    public static Candidate minister() {
        return minister;
    }

    /** -1 unknown, 0 not active, else epoch ms. */
    public static long cookieExpiresAtMs() {
        return cookieExpiresAtMs;
    }

    public static String maxwellPower() {
        return maxwellPower;
    }

    public static int magicalPower() {
        return magicalPower;
    }

    public static List<Tuning> tunings() {
        return tunings;
    }

    public static String quiverArrow() {
        return quiverArrow;
    }

    public static int quiverAmount() {
        return quiverAmount;
    }

    public static boolean hasBow() {
        return hasBow;
    }

    public static boolean wearingSkeletonMasterChestplate() {
        return wearingSkeletonMaster;
    }

    /** Unclaimed bits from the cookie buff, -1 unknown. */
    public static long bitsAvailable() {
        return bitsAvailable;
    }

    /** Total Mithril/Gemstone/Glacite powder ({@link #POWDER_TYPES} index), -1 unknown. */
    public static long powderTotal(int index) {
        return index >= 0 && index < powderTotal.length ? powderTotal[index] : -1L;
    }

    /** The Perkpocalypse mayor while Jerry is mayor and it hasn't expired, else null. */
    public static String jerryMayor() {
        return jerryMayor != null && jerryMayorExpiresAtMs > System.currentTimeMillis() ? jerryMayor : null;
    }

    public static long jerryMayorExpiresAtMs() {
        return jerryMayorExpiresAtMs;
    }
}
