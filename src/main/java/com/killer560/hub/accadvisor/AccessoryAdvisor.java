package com.killer560.hub.accadvisor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.chocolatefactory.ChocolateFactoryMenu;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.hud.ContainerOverlayElement;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
    // Hit boxes in the panel's own units (x, y from its top-left), built with the list: the mode button, one box per name.
    private static int btnX0 = 100;
    private static int btnW = 70;
    private static String modeLabel = AccessoryAdvisorConfig.Mode.BEST_OVERALL.label;
    private static int[] rowY = new int[0];
    private static int[] rowX0 = new int[0];
    private static int[] rowX1 = new int[0];
    private static List<String> rowPlain = List.of();
    private static final List<String> SAMPLE = List.of("Next Accessory", "§7Open the Accessory Bag to see the list");
    private static final List<Component> TIP_COPY = List.of(Component.literal("Click to copy"));
    private static final List<Component> TIP_MODE = List.of(Component.literal("Click: next mode. Right click: previous."));
    private static final List<Component> TIP_DRAG = List.of(Component.literal("Drag to move"));
    private static final int OUT = -1, BODY = -2, HEADER = -3, BUTTON = -4;
    private static final String ELEMENT_ID = "accessory_advisor_panel";
    private static ContainerOverlayElement element;
    private static boolean dragging;
    private static boolean pressTaken;
    private static int grabDX, grabDY;
    private static int hover = OUT;
    private static long lastTouchMs;
    private static int readStateId = Integer.MIN_VALUE;
    private static AbstractContainerMenu readMenu;

    private AccessoryAdvisor() {
    }

    public static void register() {
        // Movable like every container panel: its place is a HUD element (Edit HUD), and the header drags it in the bag too.
        element = ContainerOverlayElement.register(ELEMENT_ID, "Accessory Advisor", 190, 60, 10, 30,
                () -> AccessoryAdvisorConfig.getInstance().isEnabled(), CONTENT)
                .previewSize(() -> new int[]{panelWidth > 0 ? panelWidth : 190, panelHeight()}).clampOnScreen();
        ScreenEvents.AFTER_INIT.register(AccessoryAdvisor::onScreenInit);
    }

    /** Puts the panel back beside the bag (forgets a dragged or Edit HUD spot). */
    public static void resetPosition() {
        HudConfig.getInstance().clearPosition(ELEMENT_ID);
        HudConfig.getInstance().save();
    }

    private static int panelHeight() {
        return (lines.isEmpty() ? SAMPLE : lines).size() * LINE + PAD * 2;
    }

    /** What a click at the screen point (mx, my) lands on: a row index, BUTTON, HEADER, BODY or OUT. */
    private static int hit(double mx, double my) {
        if (element == null) {
            return OUT;
        }
        float s = element.drawnScale();
        double lx = (mx - element.drawnX()) / s;
        double ly = (my - element.drawnY()) / s;
        if (lx < 0 || ly < 0 || lx >= (panelWidth > 0 ? panelWidth : 190) || ly >= panelHeight()) {
            return OUT;
        }
        if (ly < PAD + LINE) {
            return lx >= btnX0 && lx < btnX0 + btnW ? BUTTON : HEADER;
        }
        for (int i = 0; i < rowY.length; i++) {
            if (ly >= rowY[i] && ly < rowY[i] + LINE && lx >= rowX0[i] && lx < rowX1[i]) {
                return i;
            }
        }
        return BODY;
    }

    private static void copyName(int row) {
        Minecraft client = Minecraft.getInstance();
        String name = rowPlain.get(row);
        client.keyboardHandler.setClipboard(name);
        if (client.player != null) {
            client.player.sendSystemMessage(ModChat.line("Accessory Advisor", ModChat.text("Copied " + name + " to the clipboard!")));
        }
    }

    private static final ContainerOverlayElement.Drawer CONTENT = AccessoryAdvisor::drawPanel;

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String> ls = lines.isEmpty() ? SAMPLE : lines;
        int w = panelWidth > 0 ? panelWidth : 190;
        int h = ls.size() * LINE + PAD * 2;
        g.fill(x, y, x + w, y + h, 0xC0101010);
        int border = ThemeAccent.accent(0xFFCC6600);
        g.outline(x, y, w, h, border);
        int titleColor = ThemeAccent.onDark(ThemeAccent.light(0xFFFFAA00));
        for (int i = 0; i < ls.size(); i++) {
            g.text(font, ls.get(i), x + PAD, y + PAD + i * LINE, i == 0 ? titleColor : 0xFFFFFFFF, true);
        }
        g.fill(x + btnX0, y + 2, x + btnX0 + btnW, y + PAD + LINE - 1, hover == BUTTON ? 0xFF3C3C3C : 0xFF222222);
        g.outline(x + btnX0, y + 2, btnW, PAD + LINE - 3, border);
        g.text(font, modeLabel, x + btnX0 + 4, y + PAD, 0xFFFFFFFF, false);
        if (hover >= 0 && hover < rowY.length) {
            g.fill(x + rowX0[hover], y + rowY[hover] + LINE - 2, x + rowX1[hover], y + rowY[hover] + LINE - 1, titleColor);
        }
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
                render(client, container, g, mouseX, mouseY);
            } catch (RuntimeException e) {
                LOGGER.error("[AccessoryAdvisor] render failed", e);
            }
        });
        dragging = false;
        pressTaken = false;
        // A press on the panel never reaches the slots under it: the button steps the mode, a name copies, the header drags.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            int h = AccessoryAdvisorConfig.getInstance().isEnabled() ? hit(event.x(), event.y()) : OUT;
            if (h == OUT) {
                return true;
            }
            pressTaken = true;
            boolean right = McInput.button(event) == 1;
            if (h == BUTTON) {
                AccessoryAdvisorConfig cfg = AccessoryAdvisorConfig.getInstance();
                cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), right ? -1 : 1));
                cfg.save();
            } else if (h >= 0 && !right) {
                copyName(h);
            } else if (h == HEADER && !right) {
                dragging = true;
                grabDX = (int) event.x() - element.drawnX();
                grabDY = (int) event.y() - element.drawnY();
            }
            return false;
        });
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
            boolean mine = pressTaken;
            pressTaken = false;
            if (dragging) {
                dragging = false;
                HudConfig.getInstance().save();
            }
            return !mine;
        });
    }

    private static void render(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX,
                               int mouseY) {
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
        int x = left + 176 + 4;
        if (x + panelWidth > screen.width) {
            x = Math.max(0, left - panelWidth - 4);
        }
        if (dragging) {
            int nx = Math.max(0, Math.min(mouseX - grabDX, screen.width - Math.round(panelWidth * element.drawnScale())));
            int ny = Math.max(0, Math.min(mouseY - grabDY, screen.height - Math.round(panelHeight() * element.drawnScale())));
            int[] saved = HudElementRegistry.toSaved(nx, ny);
            HudConfig.getInstance().setPosition(ELEMENT_ID, saved[0], saved[1]);
        }
        hover = dragging ? HEADER : hit(mouseX, mouseY);
        g.nextStratum();
        element.draw(g, x, top, panelWidth, panelHeight(), CONTENT);
        if (!dragging && hover != OUT && hover != BODY) {
            g.setTooltipForNextFrame(client.font, hover >= 0 ? TIP_COPY : hover == BUTTON ? TIP_MODE : TIP_DRAG,
                    java.util.Optional.empty(), mouseX, mouseY);
        }
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

    /** Every accessory in the items resource (id -> name and tier); empty until it loaded. Render thread. */
    public static Map<String, AccessoryRanker.Acc> catalogView() {
        return catalog();
    }

    /** The upgrade family an accessory belongs to (its own id when it has none). Starts the family download once. */
    public static String familyKey(String id) {
        loadFamilies();
        return familyOf.getOrDefault(id, id);
    }

    /** Ids the upgrade table marks as not real bag accessories. */
    public static boolean isIgnored(String id) {
        return ignored.contains(id);
    }

    /** Bumped every time the families change, so a view built from them knows to rebuild. */
    public static int familiesVersion() {
        return familiesVersion;
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
        out.add("Next Accessory");
        modeLabel = cfg.getMode().label;
        List<String> plain = new ArrayList<>();
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
            rowY = rowX0 = rowX1 = new int[0];
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
            rowX0 = new int[res.rows().size()];
            rowX1 = new int[rowX0.length];
            rowY = new int[rowX0.length];
            for (AccessoryRanker.Row r : res.rows()) {
                rowY[n - 1] = PAD + out.size() * LINE;
                rowX0[n - 1] = PAD + font.width("§7" + n + ". ");
                rowX1[n - 1] = rowX0[n - 1] + font.width(r.name());
                plain.add(ChatFormatting.stripFormatting(r.name()));
                out.add("§7" + (n++) + ". " + (r.upgrade() ? "§b" : "§a") + r.name() + " §d+" + r.mpGain() + " MP §6"
                        + ChocolateFactoryMenu.compact(Math.round(r.price())) + " §7(" + ChocolateFactoryMenu.compact(
                        Math.round(r.coinsPerMp())) + "/MP)");
            }
            if (res.unpriced() > 0) {
                out.add("§8" + res.unpriced() + " more have no price");
            }
        }
        out.add("§8Drag the title to move, click a name to copy");
        int w = font.width(out.get(0)) + 8 + (btnW = font.width(modeLabel) + 8);
        for (String s : out) {
            w = Math.max(w, font.width(s));
        }
        lines = List.copyOf(out);
        rowPlain = List.copyOf(plain);
        panelWidth = w + PAD * 2;
        btnX0 = panelWidth - PAD - btnW;
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
        rowY = rowX0 = rowX1 = new int[0];
        rowPlain = List.of();
        dragging = pressTaken = false;
    }

    /** The panel's box as last drawn: {x, y, w, h} in screen px (null before it drew). */
    public static int[] panelRectForTest() {
        float s = element.drawnScale();
        return lines.isEmpty() ? null : new int[]{element.drawnX(), element.drawnY(), Math.round(panelWidth * s),
                Math.round(panelHeight() * s)};
    }

    /** The mode button's screen center {x, y}. */
    public static int[] buttonCenterForTest() {
        float s = element.drawnScale();
        return new int[]{element.drawnX() + Math.round((btnX0 + btnW / 2f) * s), element.drawnY() + Math.round(6 * s)};
    }

    /** The screen center {x, y} of name row {@code i} (-1: none), and the header's drag point left of the button. */
    public static int[] rowCenterForTest(int i) {
        if (i >= rowY.length) {
            return null;
        }
        float s = element.drawnScale();
        return new int[]{element.drawnX() + Math.round((rowX0[i] + rowX1[i]) / 2f * s), element.drawnY() + Math.round((rowY[i] + 4) * s)};
    }

    public static int[] headerPointForTest() {
        return new int[]{element.drawnX() + 3, element.drawnY() + 6};
    }

    public static int hoverForTest() {
        return hover;
    }

    public static List<String> rowNamesForTest() {
        return rowPlain;
    }
}
