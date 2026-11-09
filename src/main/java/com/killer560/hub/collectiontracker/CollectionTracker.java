package com.killer560.hub.collectiontracker;

import com.google.gson.JsonObject;
import com.killer560.hub.hud.BasicHud;
import com.google.gson.JsonParser;
import com.killer560.hub.blessings.Blessing;
import com.killer560.hub.chocolatefactory.ChocolateFactoryMenu;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.ExtraTables;
import com.killer560.hub.profileviewer.data.ExtraTables.CollectionCategory;
import com.killer560.hub.profileviewer.data.ExtraTables.CollectionItem;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collection Tracker (killer560, 2026-11-04: "you select what collections you want and it shows up on your hud"). The
 * player picks any number of Skyblock collections in the menu; a movable HUD element shows, per pick, the amount, the tier,
 * the progress to the next tier and the gain per hour this session.
 *
 * <p>Sources, all on the client thread except the two downloads: the collection list is Hypixel's keyless
 * {@code /v2/resources/skyblock/collections} ({@link ExtraTables#collections()}, once a session), with a bundled copy
 * (assets/killer560smod/collectiontracker/collections.json, same shape) until it lands or when it fails. The amounts come
 * from the player's own profile through the Profile Viewer's fetch ({@link ProfileViewerApi#fetchProfiles}: his own key or
 * the SkyBlockPV backend, never a key in the jar) every {@link #API_REFRESH_MS}; between fetches a {@code [Sacks]} line's
 * hover ("+40 Wheat (Farming Sack)"), items picked up into the inventory with no screen open, and the Collection menu's
 * "Total Collected" lore move them. An API amount below what the tracker already counted is ignored (the API lags).
 *
 * <p>Everything is gated on the switch, the Skyblock gate and at least one pick; the HUD lines are rebuilt only when a count
 * or the picks change (and once a second for the rate). Per-pick state is bounded by {@link CollectionTrackerConfig#MAX_SELECTED};
 * the inventory snapshot is dropped on a world change.
 */
public final class CollectionTracker {

    private static final Logger LOGGER = ModLog.get("killer560smod-collectiontracker");
    public static final String HUD_ID = "collection_tracker";
    static final long API_REFRESH_MS = 5 * 60_000L;
    private static final long API_RETRY_MS = 60_000L;
    /** How long an inventory change can still be matched with the sack line that explains it (Hypixel batches 30 s). */
    private static final long PAIR_MS = 60_000L;

    /** One hover line of a [Sacks] message: "+1,234 Enchanted Wheat (Farming Sack)" / "-64 Wheat (Agronomy Sack, Farming Sack)". */
    private static final Pattern SACK_ITEM = Pattern.compile("^\\s*([+-][\\d,]{1,12}) ([^()]{1,48}?) \\(([^()]{1,80})\\)$");
    private static final Pattern SACKS_LINE = Pattern.compile("^\\[Sacks\\] [+-][\\d,]{1,12} items?.{0,40}\\(Last \\d{1,5}s\\.\\)$");
    private static final Pattern TOTAL = Pattern.compile("^Total [Cc]ollected: ([\\d,]{1,15})$");
    private static final Pattern MENU_TITLE = Pattern.compile("^[A-Za-z ]{1,30} Collections?$");
    private static final Pattern TIER_SUFFIX = Pattern.compile(" [IVXLC]{1,7}$");

    /** The collection list with its lookups, built once per list off the client thread. */
    public record Catalog(List<CollectionCategory> categories, Map<String, CollectionItem> byId, Map<String, String> idByName,
                          Map<String, String> categoryOf, boolean bundled) {
        static Catalog of(List<CollectionCategory> cats, boolean bundled) {
            Map<String, CollectionItem> byId = new HashMap<>();
            Map<String, String> byName = new HashMap<>();
            Map<String, String> cat = new HashMap<>();
            for (CollectionCategory c : cats) {
                for (CollectionItem it : c.items()) {
                    byId.put(it.id(), it);
                    byName.put(it.name().toLowerCase(Locale.ROOT), it.id());
                    cat.put(it.id(), c.name());
                }
            }
            return new Catalog(cats, Map.copyOf(byId), Map.copyOf(byName), Map.copyOf(cat), bundled);
        }
    }

    private static final class Track {
        long amount = -1;
        long gained;
        long since = System.currentTimeMillis();
        /** When the count last went up (ms); 0 = not this session. Memory only. */
        long lastGain;
        long invGain, invGainAt, invLoss, invLossAt;
    }

    private static volatile Catalog catalog;
    private static CompletableFuture<Catalog> catalogLoad;
    private static boolean remoteAsked;
    private static final Map<String, Track> TRACKS = new LinkedHashMap<>();
    /** Inventory counts of the picked ids at the last scan; null = take a fresh snapshot without counting. */
    private static Map<String, Long> invSnapshot;
    private static Object lastLevel;
    private static boolean screenSeen;
    private static int ticks;
    private static long apiAt, apiTriedAt;
    private static volatile boolean apiInFlight;
    private static volatile Map<String, Long> apiPending;
    private static boolean menuLoreLogged;
    private static int version;

    private CollectionTracker() {
    }

    public static void register() {
        HudElementRegistry.register(Hud.INSTANCE);
        ChatObserver.subscribe(CollectionTracker::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CollectionTracker", CollectionTracker::tick));
    }

    // ------------------------------------------------------------------ the collection list

    /** The list the picker and the HUD use, or null while the bundled copy is still being read (starts that read). */
    public static Catalog catalog() {
        Catalog c = catalog;
        if (c == null) {
            startCatalogLoad();
        }
        return c;
    }

    private static synchronized void startCatalogLoad() {
        if (catalogLoad != null) return;
        catalogLoad = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = CollectionTracker.class.getResourceAsStream("/assets/killer560smod/collectiontracker/collections.json")) {
                List<CollectionCategory> cats = in == null ? null : ExtraTables.parseCollections(
                        JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
                return cats == null ? null : Catalog.of(cats, true);
            } catch (Exception e) {
                LOGGER.warn("[CollectionTracker] bundled collection list unreadable: {}", e.toString());
                return null;
            }
        }, ProfileViewerApi.EXECUTOR).whenComplete((c, t) -> {
            if (c != null && catalog == null) {
                setCatalog(c);
            }
        });
    }

    /** Runs {@code r} on the client thread once the bundled list has been read (the picker's rebuild). */
    public static synchronized void whenCatalogLoaded(Runnable r) {
        startCatalogLoad();
        catalogLoad.thenRun(() -> Minecraft.getInstance().execute(r));
    }

    private static void setCatalog(Catalog c) {
        catalog = c;
        Minecraft.getInstance().execute(CollectionTracker::selectionChanged);
    }

    /** Asks Hypixel's list once (from the tick, never from the menu); it replaces the bundled one when it lands. */
    private static void askRemoteCatalog() {
        if (remoteAsked) return;
        remoteAsked = true;
        try {
            ExtraTables.collections().thenAccept(cats -> {
                if (cats != null) {
                    setCatalog(Catalog.of(cats, false));
                    LOGGER.info("[CollectionTracker] Hypixel's collection list loaded ({} categories)", cats.size());
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("[CollectionTracker] collection list request failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ state

    /** The picks or the settings changed: line up one track per pick (dropping the rest) and redraw. Client thread. */
    public static void selectionChanged() {
        List<String> sel = CollectionTrackerConfig.getInstance().getSelected();
        TRACKS.keySet().retainAll(sel);
        for (String id : sel) {
            TRACKS.computeIfAbsent(id, k -> new Track());
        }
        invSnapshot = null;
        version++;
    }

    private static boolean active() {
        CollectionTrackerConfig cfg = CollectionTrackerConfig.getInstance();
        return cfg.isEnabled() && !cfg.getSelected().isEmpty();
    }

    private static void add(String id, long n) {
        Track t = TRACKS.get(id);
        if (t == null || n == 0) return;
        if (t.amount >= 0) {
            t.amount = Math.max(0, t.amount + n);
        }
        t.gained += n;
        if (n > 0) t.lastGain = System.currentTimeMillis();
        version++;
    }

    /** An absolute amount (the API or the menu); {@code trusted} (the menu) may lower it, the API only raises it. */
    private static void setAmount(String id, long amount, boolean trusted) {
        Track t = TRACKS.get(id);
        if (t == null || amount < 0) return;
        if (t.amount < 0 || trusted) {
            t.amount = amount;
        } else if (amount > t.amount) {
            t.gained += amount - t.amount;
            t.lastGain = System.currentTimeMillis();
            t.amount = amount;
        } else {
            return;
        }
        version++;
    }

    // ------------------------------------------------------------------ tick: API, inventory, menu

    private static void tick(Minecraft client) {
        Map<String, Long> api = apiPending;
        if (api != null) {
            apiPending = null;
            for (String id : TRACKS.keySet()) {
                Long v = api.get(id);
                setAmount(id, v == null ? 0 : v, false);
            }
        }
        if (!active() || client.player == null) {
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            invSnapshot = null;
        }
        if (++ticks % 10 != 0) {
            return;
        }
        catalog();
        askRemoteCatalog();
        maybeFetchApi(client);
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) {
            readMenu(screen);
        }
        if (McCompat.screen(client) != null) {
            screenSeen = true;
            return;
        }
        if (screenSeen) {
            screenSeen = false;
            invSnapshot = null;
        }
        scanInventory(client);
    }

    private static void maybeFetchApi(Minecraft client) {
        long now = System.currentTimeMillis();
        if (apiInFlight || now - apiAt < API_REFRESH_MS || now - apiTriedAt < API_RETRY_MS) return;
        apiTriedAt = now;
        UUID self;
        try {
            self = client.getUser().getProfileId();
        } catch (RuntimeException e) {
            return;
        }
        if (self == null) return;
        apiInFlight = true;
        try {
            ProfileViewerApi.fetchProfiles(self, false)
                    .thenCompose(r -> profileCollections(r.profiles()))
                    .whenComplete((cols, err) -> {
                        apiInFlight = false;
                        if (err != null || cols == null) {
                            LOGGER.info("[CollectionTracker] collections not loaded: {}",
                                    err == null ? "no collection data (API setting off?)" : ProfileViewerApi.messageFor(err));
                            return;
                        }
                        apiAt = System.currentTimeMillis();
                        apiPending = cols;
                    });
        } catch (RuntimeException e) {
            apiInFlight = false;
        }
    }

    /** The selected profile's collection amounts (the co-op's total, as Hypixel counts them), or null. */
    private static CompletableFuture<Map<String, Long>> profileCollections(List<SbProfile> profiles) {
        SbProfile pick = null;
        for (SbProfile p : profiles) {
            if (p.selected) {
                pick = p;
                break;
            }
        }
        if (pick == null && !profiles.isEmpty()) {
            pick = profiles.get(0);
        }
        return pick == null ? CompletableFuture.completedFuture(null) : pick.extras().thenApply(ex -> ex.collections);
    }

    private static void scanInventory(Minecraft client) {
        Map<String, Long> now = new HashMap<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String id = com.killer560.hub.autoroutes.ItemIdentity.skyblockId(st);
            if (id != null && TRACKS.containsKey(id)) {
                now.merge(id, (long) st.getCount(), Long::sum);
            }
        }
        Map<String, Long> before = invSnapshot;
        invSnapshot = now;
        if (before == null) return;
        long t = System.currentTimeMillis();
        for (Map.Entry<String, Track> e : TRACKS.entrySet()) {
            long d = now.getOrDefault(e.getKey(), 0L) - before.getOrDefault(e.getKey(), 0L);
            Track tr = e.getValue();
            if (d > 0) {
                tr.invGain = (t - tr.invGainAt < PAIR_MS ? tr.invGain : 0) + d;
                tr.invGainAt = t;
                add(e.getKey(), d);
            } else if (d < 0) {
                tr.invLoss = (t - tr.invLossAt < PAIR_MS ? tr.invLoss : 0) - d;
                tr.invLossAt = t;
            }
        }
    }

    private static void readMenu(AbstractContainerScreen<?> screen) {
        Catalog cat = catalog;
        if (cat == null || !MENU_TITLE.matcher(screen.getTitle().getString()).matches()) return;
        var slots = screen.getMenu().slots;
        int top = Math.min(slots.size() - 36, 54);
        StringBuilder dump = menuLoreLogged ? null : new StringBuilder();
        for (int i = 0; i < top; i++) {
            ItemStack st = slots.get(i).getItem();
            if (st.isEmpty()) continue;
            String name = TIER_SUFFIX.matcher(ChatObserver.stripCodes(st.getHoverName().getString())).replaceFirst("");
            String id = cat.idByName().get(name.toLowerCase(Locale.ROOT));
            if (id == null) continue;
            List<String> lore = com.killer560.hub.croesus.DungeonChestValuer.cleanLore(st);
            if (dump != null && dump.length() < 3000) {
                dump.append("\n  ").append(name).append(" | ").append(String.join(" / ", lore));
            }
            for (String l : lore) {
                Matcher m = TOTAL.matcher(l.trim());
                if (m.matches()) {
                    setAmount(id, parse(m.group(1)), true);
                    break;
                }
            }
        }
        if (dump != null && dump.length() > 0) {
            menuLoreLogged = true;
            LOGGER.info("[CollectionTracker] collection menu lore (first open, codes stripped):{}", dump);
        }
    }

    // ------------------------------------------------------------------ sacks

    private static void onChat(Component message) {
        if (!active()) return;
        String plain = ChatObserver.strip(message);
        if (plain == null || !plain.startsWith("[Sacks]") || !SACKS_LINE.matcher(plain).matches()) return;
        Catalog cat = catalog;
        if (cat == null) return;
        List<Component> hovers = new ArrayList<>(2);
        message.<Void>visit((Style style, String s) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText show && !hovers.contains(show.value())) {
                hovers.add(show.value());
            }
            return Optional.empty();
        }, Style.EMPTY);
        long t = System.currentTimeMillis();
        for (Component h : hovers) {
            for (String line : ChatObserver.stripCodes(h.getString()).split("\n")) {
                Matcher m = SACK_ITEM.matcher(line);
                if (!m.matches()) continue;
                String id = cat.idByName().get(m.group(2).trim().toLowerCase(Locale.ROOT));
                Track tr = id == null ? null : TRACKS.get(id);
                if (tr == null) continue;
                long n = parse(m.group(1).substring(1));
                if (m.group(1).charAt(0) == '+') {
                    // Items put into the sack from the inventory were already counted when they were picked up.
                    long paired = t - tr.invLossAt < PAIR_MS ? Math.min(n, tr.invLoss) : 0;
                    tr.invLoss -= paired;
                    add(id, n - paired);
                } else {
                    // Taken out of the sack into the inventory: the inventory scan counted them, take that back.
                    long paired = t - tr.invGainAt < PAIR_MS ? Math.min(n, tr.invGain) : 0;
                    tr.invGain -= paired;
                    add(id, -paired);
                }
            }
        }
    }

    private static long parse(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ HUD text

    private static List<String> lines = List.of();
    private static int linesWidth;
    private static int builtVersion = -1;
    private static long builtAt;
    private static boolean recentView;

    /** The HUD lines, rebuilt only when a count or the picks changed, or once a second while the rate shows. */
    public static List<String> lines() {
        long now = System.currentTimeMillis();
        CollectionTrackerConfig cfg = CollectionTrackerConfig.getInstance();
        boolean rate = cfg.isShowRate();
        boolean recent = cfg.isOnlyRecent() && !HudVisibility.editorOpen();
        if (builtVersion == version && recentView == recent && (!(rate || recent) || now - builtAt < 1000)) {
            return lines;
        }
        builtVersion = version;
        recentView = recent;
        builtAt = now;
        long cutoff = now - cfg.getRecentSeconds() * 1000L;
        Catalog cat = catalog();
        List<String> out = new ArrayList<>(TRACKS.size() + 1);
        out.add("§6§lCollections");
        if (TRACKS.isEmpty()) {
            out.add("§7Pick collections in the menu");
        }
        for (Map.Entry<String, Track> e : TRACKS.entrySet()) {
            if (recent && e.getValue().lastGain < cutoff) continue;
            CollectionItem it = cat == null ? null : cat.byId().get(e.getKey());
            out.add(line(it == null ? e.getKey() : it.name(), it == null ? new long[0] : it.tiers(), e.getValue(), rate, now));
        }
        if (recent && out.size() == 1 && !TRACKS.isEmpty()) {
            out.add("§7Nothing gained recently");
        }
        lines = Collections.unmodifiableList(out);
        var font = Minecraft.getInstance().font;
        int w = 60;
        for (String s : out) {
            w = Math.max(w, font.width(s));
        }
        linesWidth = w;
        return lines;
    }

    static String line(String name, long[] tiers, Track t, boolean rate, long now) {
        StringBuilder sb = new StringBuilder("§e").append(name);
        if (t.amount < 0) {
            sb.append("§7: §8loading");
            if (t.gained != 0) sb.append(" §a+").append(String.format(Locale.US, "%,d", t.gained));
        } else {
            int tier = 0;
            while (tier < tiers.length && t.amount >= tiers[tier]) tier++;
            sb.append(' ').append("§6").append(tier == 0 ? "0" : Blessing.toRoman(tier))
                    .append("§7: §f").append(String.format(Locale.US, "%,d", t.amount));
            if (tiers.length == 0) {
                // No tier list for this id.
            } else if (tier >= tiers.length) {
                sb.append(" §a(Maxed)");
            } else {
                double pct = Math.floor(1000.0 * t.amount / tiers[tier]) / 10.0;
                sb.append(" §7(").append(String.format(Locale.US, "%.1f", pct)).append("% to ")
                        .append(Blessing.toRoman(tier + 1)).append(')');
            }
        }
        if (rate) {
            long ms = now - t.since;
            sb.append(" §a").append(ms < 60_000 || t.gained <= 0 ? "+0" : "+" + ChocolateFactoryMenu.compact(t.gained * 3_600_000L / ms))
                    .append("/h");
        }
        return sb.toString();
    }

    public static final class Hud extends BasicHud {
        public Hud() {
            super(HUD_ID, "Collection Tracker", 10, 140);
        }

        static final Hud INSTANCE = new Hud();

        @Override
        public int width() {
            lines();
            return linesWidth;
        }

        @Override
        public int height() { return lines().size() * 10; }

        @Override
        public boolean isEnabledInSettings() { return CollectionTrackerConfig.getInstance().isEnabledRaw(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if ((!CollectionTrackerConfig.getInstance().isEnabled() && !HudVisibility.editorOpen()) || HudVisibility.hidesHud()) return;
            var font = Minecraft.getInstance().font;
            int yy = y;
            for (String s : lines()) {
                graphics.text(font, s, x, yy, 0xFFFFFFFF, true);
                yy += 10;
            }
            HudSeen.markDrawn(HUD_ID);
        }
    }

    // ------------------------------------------------------------------ testkit

    /** Testkit: feeds a {@code /v2/resources/skyblock/collections} body through the same parser as the download. */
    public static int loadCatalogForTest(String body) {
        List<CollectionCategory> cats = ExtraTables.parseCollections(JsonParser.parseString(body).getAsJsonObject());
        catalog = Catalog.of(cats, false);
        selectionChanged();
        return catalog.byId().size();
    }

    /** Testkit: the bundled list, read on the calling thread; its item count. */
    public static int bundledCountForTest() throws Exception {
        startCatalogLoad();
        Catalog c = catalogLoad.get();
        return c == null ? -1 : c.byId().size();
    }

    /** Testkit: a {@code /v2/skyblock/profiles} body for {@code self}, through the same profile pick and parse as a fetch;
     *  applied at the next tick. Returns the amounts it read. */
    public static Map<String, Long> applyProfilesForTest(String body, UUID self) throws Exception {
        Map<String, Long> cols = profileCollections(SbProfile.parseAll(JsonParser.parseString(body).getAsJsonObject(), self)).get();
        apiAt = System.currentTimeMillis();
        apiPending = cols;
        return cols;
    }

    /** Testkit: forget every amount and gain, and stop the API fetch for a refresh period. */
    public static void resetForTest() {
        TRACKS.clear();
        apiAt = System.currentTimeMillis();
        apiPending = null;
        selectionChanged();
    }

    /** Testkit: the tracked amount for {@code id}, -1 when unknown, Long.MIN_VALUE when not picked. */
    public static long amountForTest(String id) {
        Track t = TRACKS.get(id);
        return t == null ? Long.MIN_VALUE : t.amount;
    }
}
