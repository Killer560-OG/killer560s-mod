package com.killer560.hub.accadvisor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.chocolatefactory.ChocolateFactoryMenu;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.containercover.ContainerCover;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.itemrarity.ItemRarity;
import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accessory Advisor (accadvisor-1113, killer560 2026-11-13: "when i open my accessory bag i want a tracker to show what the
 * next talisman i should buy is"): beside the Accessory Bag a panel lists the next buys for the chosen
 * {@link AccessoryAdvisorConfig.Mode}, ranked by {@link AccessoryRanker}.
 * <p>
 * Data: the accessory list and base rarities come from Hypixel's public items resource (already fetched and cached by
 * {@link PriceData}); upgrade families and the never-suggested list from the NEU repo's {@code constants/misc.json}
 * ({@code talisman_upgrades}, {@code ignored_talisman}), fetched once a session on the profile viewer's pool; prices from
 * {@link PriceData#unitPrice} (Bazaar, then lowest BIN). Owned accessories are read from every bag page the player opens,
 * kept per profile (at most {@link #MAX_PROFILES}); the panel asks for the other pages until all were seen.
 * <p>
 * Per frame the panel only compares a few numbers and draws text built when something changed (bag contents, a setting,
 * prices, families); clicking its title cycles the mode (left forward, right back).
 */
public final class AccessoryAdvisor {

    private static final Logger LOGGER = ModLog.get("killer560smod-accadvisor");
    private static final Pattern TITLE = Pattern.compile("^Accessory Bag(?: \\((\\d{1,2})/(\\d{1,2})\\))?$");
    static final String MISC_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/misc.json";
    private static final int MAX_PROFILES = 6;
    private static final long FAMILY_RETRY_MS = 60_000L;
    private static final int PAD = 4;
    private static final int LINE = 10;

    /** One profile's bag: page number -> (id -> rarity shown), and the page count from the title. */
    private static final class Bag {
        final Map<Integer, Map<String, String>> pages = new HashMap<>();
        int pageCount = 1;
        int version;

        Map<String, String> owned() {
            Map<String, String> all = new HashMap<>();
            for (Map<String, String> p : pages.values()) {
                for (Map.Entry<String, String> e : p.entrySet()) {
                    all.merge(e.getKey(), e.getValue(), (a, b) -> AccessoryRanker.mp(e.getKey(), a)
                            >= AccessoryRanker.mp(e.getKey(), b) ? a : b);
                }
            }
            return all;
        }
    }

    private static final Map<String, Bag> BAGS = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bag> eldest) {
            return size() > MAX_PROFILES;
        }
    };

    // Upgrade families (NEU misc.json); replaced whole off-thread.
    private static volatile Map<String, String> familyOf = Map.of();
    private static volatile Set<String> ignored = Set.of();
    private static volatile boolean familiesLoaded;
    private static volatile int familiesVersion;
    private static volatile long familiesTriedAt;
    private static volatile boolean familiesLoading;

    // The catalog, rebuilt only when PriceData swaps its items map.
    private static Map<String, PriceData.ItemInfo> catalogFrom;
    private static Map<String, AccessoryRanker.Acc> catalog = Map.of();
    private static Map<String, AccessoryRanker.Acc> testCatalog;

    // The built panel, rebuilt only when one of its inputs moved.
    private static String builtKey = "";
    private static List<String> lines = List.of();
    private static int panelWidth;
    private static AccessoryRanker.Result lastResult;
    private static int titleX0, titleY0, titleX1, titleY1;
    private static long lastTouchMs;
    private static int readStateId = Integer.MIN_VALUE;
    private static AbstractContainerMenu readMenu;

    private AccessoryAdvisor() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(AccessoryAdvisor::onScreenInit);
    }

    /** Page and page count of an Accessory Bag title ({1, 1} for a bag with one page), or null for any other menu. */
    public static int[] page(String title) {
        Matcher m = TITLE.matcher(title);
        if (!m.matches()) {
            return null;
        }
        return m.group(1) == null ? new int[]{1, 1}
                : new int[]{Integer.parseInt(m.group(1)), Math.max(1, Integer.parseInt(m.group(2)))};
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> container) || page(screen.getTitle().getString()) == null) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            try {
                render(client, container, g);
            } catch (RuntimeException e) {
                LOGGER.error("[AccessoryAdvisor] render failed", e);
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!AccessoryAdvisorConfig.getInstance().isEnabled() || event.x() < titleX0 || event.x() >= titleX1
                    || event.y() < titleY0 || event.y() >= titleY1) {
                return true;
            }
            AccessoryAdvisorConfig cfg = AccessoryAdvisorConfig.getInstance();
            cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), McInput.button(event) == 1 ? -1 : 1));
            cfg.save();
            return false;
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        AccessoryAdvisorConfig cfg = AccessoryAdvisorConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTouchMs > 10_000L) {
            lastTouchMs = now;
            PriceData.touchFromOtherFeature();
            loadFamilies();
        }
        int[] page = page(screen.getTitle().getString());
        AbstractContainerMenu menu = screen.getMenu();
        Bag bag = bag();
        if (page != null && (menu != readMenu || menu.getStateId() != readStateId)) {
            readMenu = menu;
            readStateId = menu.getStateId();
            readPage(menu, page, bag);
        }
        String key = cfg.version() + "/" + bag.version + "/" + System.identityHashCode(bag) + "/" + PriceData.generation()
                + "/" + familiesVersion + "/" + System.identityHashCode(PriceData.items());
        if (!key.equals(builtKey)) {
            builtKey = key;
            build(client.font, cfg, bag);
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.killer560smod$getLeftPos();
        int top = acc.killer560smod$getTopPos();
        int h = lines.size() * LINE + PAD * 2;
        int x = left + 176 + 4;
        if (x + panelWidth > screen.width) {
            x = Math.max(0, left - panelWidth - 4);
        }
        g.nextStratum();
        g.fill(x, top, x + panelWidth, top + h, 0xC0101010);
        int border = ThemeAccent.accent(0xFFCC6600);
        g.fill(x, top, x + panelWidth, top + 1, border);
        g.fill(x, top + h - 1, x + panelWidth, top + h, border);
        g.fill(x, top, x + 1, top + h, border);
        g.fill(x + panelWidth - 1, top, x + panelWidth, top + h, border);
        int titleColor = ThemeAccent.onDark(ThemeAccent.light(0xFFFFAA00));
        for (int i = 0; i < lines.size(); i++) {
            g.text(client.font, lines.get(i), x + PAD, top + PAD + i * LINE, i == 0 ? titleColor : 0xFFFFFFFF, true);
        }
        titleX0 = x;
        titleY0 = top;
        titleX1 = x + panelWidth;
        titleY1 = top + PAD + LINE;
        ContainerCover.coverRect(screen, "accadvisor", x, top, x + panelWidth, top + h);
    }

    private static Bag bag() {
        return BAGS.computeIfAbsent(ProfileTracker.key(), k -> new Bag());
    }

    /** Reads one bag page: every slot of the menu's own rows holding an accessory id. */
    private static void readPage(AbstractContainerMenu menu, int[] page, Bag bag) {
        Map<String, String> found = new HashMap<>();
        int top = Math.max(0, menu.slots.size() - 36);
        for (int i = 0; i < top; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String id = ItemKeys.id(ItemNbt.view(stack));
            if (id == null || !id.equals(id.toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            ItemRarity r = ItemRarityFeature.getRarity(stack);
            found.put(id, r != null ? r.name() : tierOf(id));
        }
        // Hypixel fills the bag a moment after the menu opens: an empty read never wipes a page that held items.
        if (found.isEmpty() && bag.pages.containsKey(page[0])) {
            return;
        }
        if (!found.equals(bag.pages.get(page[0])) || bag.pageCount != page[1]) {
            bag.pages.put(page[0], found);
            bag.pageCount = page[1];
            bag.pages.keySet().removeIf(p -> p > page[1]);
            bag.version++;
        }
    }

    private static String tierOf(String id) {
        PriceData.ItemInfo info = PriceData.item(id);
        return info == null || info.accessory() == null ? "COMMON" : info.accessory().tier();
    }

    private static Map<String, AccessoryRanker.Acc> catalog() {
        if (testCatalog != null) {
            return testCatalog;
        }
        Map<String, PriceData.ItemInfo> items = PriceData.items();
        if (items != catalogFrom) {
            Map<String, AccessoryRanker.Acc> out = new HashMap<>();
            for (Map.Entry<String, PriceData.ItemInfo> e : items.entrySet()) {
                PriceData.Accessory a = e.getValue().accessory();
                if (a != null) {
                    out.put(e.getKey(), new AccessoryRanker.Acc(e.getKey(), a.name(), a.tier()));
                }
            }
            catalog = Map.copyOf(out);
            catalogFrom = items;
        }
        return catalog;
    }

    private static void build(Font font, AccessoryAdvisorConfig cfg, Bag bag) {
        List<String> out = new ArrayList<>();
        out.add("Next Accessory: " + cfg.getMode().label);
        Map<String, AccessoryRanker.Acc> cat = catalog();
        Map<String, String> owned = bag.owned();
        int ownedMp = 0;
        Map<String, Integer> fam = new HashMap<>();
        for (Map.Entry<String, String> e : owned.entrySet()) {
            fam.merge(familyOf.getOrDefault(e.getKey(), e.getKey()), AccessoryRanker.mp(e.getKey(), e.getValue()), Math::max);
        }
        for (int v : fam.values()) {
            ownedMp += v;
        }
        if (cat.isEmpty()) {
            out.add("§7Loading the accessory list...");
            lastResult = null;
        } else {
            out.add("§7" + owned.size() + " owned, " + ownedMp + " MP" + (familiesLoaded ? "" : " §8(families loading)"));
            if (bag.pages.size() < bag.pageCount) {
                out.add("§eOpen all bag pages (" + bag.pages.size() + "/" + bag.pageCount + " seen)");
            }
            AccessoryRanker.Result res = AccessoryRanker.rank(cat, familyOf, ignored, owned, PriceData::unitPrice,
                    cfg.getMode(), cfg.getRows());
            lastResult = res;
            if (res.rows().isEmpty()) {
                out.add(PriceData.pricesFetchedAt() == 0 ? "§7Loading prices..." : "§7Nothing to buy in this mode.");
            }
            int n = 1;
            for (AccessoryRanker.Row r : res.rows()) {
                out.add("§7" + (n++) + ". " + (r.upgrade() ? "§b" : "§a") + r.name() + " §d+" + r.mpGain() + " MP §6"
                        + ChocolateFactoryMenu.compact(Math.round(r.price())) + " §7(" + ChocolateFactoryMenu.compact(
                        Math.round(r.coinsPerMp())) + "/MP)");
            }
            if (res.unpriced() > 0) {
                out.add("§8" + res.unpriced() + " more have no price");
            }
        }
        out.add("§8Click the title to change mode");
        int w = 0;
        for (String s : out) {
            w = Math.max(w, font.width(s));
        }
        lines = List.copyOf(out);
        panelWidth = w + PAD * 2;
    }

    /** Fetches the upgrade families once (retried after a minute on failure), off the render thread. */
    private static void loadFamilies() {
        long now = System.currentTimeMillis();
        if (familiesLoaded || familiesLoading || now - familiesTriedAt < FAMILY_RETRY_MS) {
            return;
        }
        familiesLoading = true;
        familiesTriedAt = now;
        ProfileViewerApi.getKeylessJson(ModNet.url("github-raw", MISC_URL)).thenAcceptAsync(json -> {
            try {
                if (json != null && json.has("talisman_upgrades") && json.get("talisman_upgrades").isJsonObject()) {
                    Map<String, List<String>> upgrades = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("talisman_upgrades").entrySet()) {
                        List<String> to = new ArrayList<>();
                        if (e.getValue().isJsonArray()) {
                            e.getValue().getAsJsonArray().forEach(x -> to.add(x.getAsString()));
                        }
                        upgrades.put(e.getKey(), to);
                    }
                    Set<String> skip = new HashSet<>();
                    if (json.has("ignored_talisman") && json.get("ignored_talisman").isJsonArray()) {
                        json.getAsJsonArray("ignored_talisman").forEach(x -> skip.add(x.getAsString()));
                    }
                    setFamilies(upgrades, skip);
                } else {
                    LOGGER.warn("[AccessoryAdvisor] upgrade families unavailable, retrying in a minute");
                }
            } finally {
                familiesLoading = false;
            }
        }, ProfileViewerApi.EXECUTOR);
    }

    /** Joins every id with the ids it upgrades into (union-find), so a family key is shared by the whole chain. */
    public static void setFamilies(Map<String, List<String>> upgrades, Set<String> skip) {
        Map<String, String> parent = new HashMap<>();
        for (Map.Entry<String, List<String>> e : upgrades.entrySet()) {
            for (String to : e.getValue()) {
                String a = root(parent, e.getKey());
                String b = root(parent, to);
                if (!a.equals(b)) {
                    parent.put(b, a);
                }
            }
        }
        Map<String, String> fam = new HashMap<>();
        for (String id : new ArrayList<>(parent.keySet())) {
            fam.put(id, root(parent, id));
        }
        familyOf = Map.copyOf(fam);
        ignored = Set.copyOf(skip);
        familiesLoaded = true;
        familiesVersion++;
    }

    private static String root(Map<String, String> parent, String id) {
        String r = id;
        for (String p = parent.get(r); p != null && !p.equals(r); p = parent.get(r)) {
            r = p;
        }
        parent.putIfAbsent(id, r);
        if (!r.equals(id)) {
            parent.put(id, r);
        }
        return r;
    }

    // ------------------------------------------------------------------ testkit

    /** The rows the open panel shows (null before the first build). */
    public static AccessoryRanker.Result lastResultForTest() {
        return lastResult;
    }

    public static List<String> linesForTest() {
        return lines;
    }

    /** Ranks against the current bag of this profile, with the families loaded and {@link PriceData} prices. */
    public static AccessoryRanker.Result rankForTest(AccessoryAdvisorConfig.Mode mode, int limit) {
        return AccessoryRanker.rank(catalog(), familyOf, ignored, bag().owned(), PriceData::unitPrice, mode, limit);
    }

    /** Replaces the accessory list with "ID" -> "Name|TIER" entries (null puts the real list back). */
    public static void catalogForTest(Map<String, String> entries) {
        if (entries == null) {
            testCatalog = null;
        } else {
            Map<String, AccessoryRanker.Acc> out = new HashMap<>();
            entries.forEach((id, v) -> {
                int bar = v.indexOf('|');
                out.put(id, new AccessoryRanker.Acc(id, v.substring(0, bar), v.substring(bar + 1)));
            });
            testCatalog = Map.copyOf(out);
        }
        builtKey = "";
    }

    /** Forgets every bag, the built panel and the test catalog. */
    public static void clearForTest() {
        BAGS.clear();
        builtKey = "";
        readMenu = null;
        lastResult = null;
        lines = List.of();
        testCatalog = null;
    }
}
