package com.killer560.hub.auction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The player's own Bazaar orders, read client-side from Hypixel's Manage Orders menu. Hypixel's public API has no
 * per-player Bazaar orders, so this is the only source: whenever the menu is open (titled {@code Your Bazaar Orders},
 * or {@code Co-op Bazaar Orders} on a co-op - SkyHanni's {@code BazaarApi.kt} and Skyblocker's {@code BazaarHelper}
 * both match exactly these), its order items are parsed by {@link BazaarOrderParser} and REPLACE the stored set for
 * the current profile, because that menu lists every order. The per-order {@code Order options} menu (title from
 * SkyHanni's list, unverified layout) only updates orders it shows and never removes one.
 * <p>
 * READ ONLY: nothing here clicks, and nothing is sent. A parse failure is swallowed per item, and the whole read is
 * inside a try, so it can never throw into the tick that calls it. Stored per profile ({@link ProfileTracker#key()})
 * in {@code config/killer560/skyblock/auction/killer560smod-bazaar-orders.json}.
 */
public final class BazaarOrders {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaar");
    private static final Path PATH = ModPaths.config("killer560smod-bazaar-orders.json");
    private static final Pattern ORDERS_TITLE = Pattern.compile("^(?:Your|Co-op) Bazaar Orders$");
    private static final Pattern OPTIONS_TITLE = Pattern.compile("^Order options$");

    /** What was last seen for one profile. */
    public record Snapshot(String profile, long seenAtMs, List<BazaarOrderParser.Order> orders) {
    }

    private static final Map<String, Snapshot> BY_PROFILE = new LinkedHashMap<>();
    private static boolean loaded;
    private static int ticks;
    private static String lastSignature = "";
    private static long lastSaveMs;

    private BazaarOrders() {
    }

    public static synchronized Snapshot current() {
        load();
        Snapshot s = BY_PROFILE.get(ProfileTracker.key());
        if (s == null && ProfileTracker.profileName().isEmpty()) {
            // Profile not announced yet this session (it is, on join): show the newest snapshot rather than nothing.
            for (Snapshot candidate : BY_PROFILE.values()) {
                if (s == null || candidate.seenAtMs() > s.seenAtMs()) {
                    s = candidate;
                }
            }
        }
        return s;
    }

    /** Called every client tick; reads the open container twice a second. */
    public static void tick(Minecraft client) {
        if (++ticks % 10 != 0) {
            return;
        }
        try {
            if (!(com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            String title = BazaarOrderParser.strip(screen.getTitle().getString());
            boolean full = ORDERS_TITLE.matcher(title).matches();
            if (!full && !OPTIONS_TITLE.matcher(title).matches()) {
                return;
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (!(slot.container instanceof Inventory)) {
                    stacks.add(slot.getItem());
                }
            }
            readContainer(title, stacks);
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] could not read the orders menu", e);
        }
    }

    /**
     * Parses one menu's items and stores the result. Public so a test can hand it a container's contents.
     *
     * @return the number of orders read, or -1 when the menu is not one of the orders menus or has not loaded yet.
     */
    public static synchronized int readContainer(String title, List<ItemStack> stacks) {
        boolean full = ORDERS_TITLE.matcher(title).matches();
        if (!full && !OPTIONS_TITLE.matcher(title).matches()) {
            return -1;
        }
        int nonEmpty = 0;
        List<BazaarOrderParser.Order> orders = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            nonEmpty++;
            BazaarOrderParser.Order o = BazaarOrderParser.parse(stack.getHoverName().getString(),
                    DungeonChestValuer.cleanLore(stack));
            if (o != null) {
                orders.add(o);
            }
        }
        if (nonEmpty == 0) {
            return -1; // the items arrive a packet after the window; an empty menu has not loaded
        }
        StringBuilder sig = new StringBuilder(title);
        for (BazaarOrderParser.Order o : orders) {
            sig.append(';').append(o);
        }
        load();
        String profile = ProfileTracker.key();
        if (full) {
            BY_PROFILE.put(profile, new Snapshot(profile, System.currentTimeMillis(), List.copyOf(orders)));
        } else {
            Snapshot old = BY_PROFILE.get(profile);
            Map<String, BazaarOrderParser.Order> merged = new LinkedHashMap<>();
            if (old != null) {
                old.orders().forEach(o -> merged.put(o.key(), o));
            }
            orders.forEach(o -> merged.put(o.key(), o));
            BY_PROFILE.put(profile, new Snapshot(profile, System.currentTimeMillis(), List.copyOf(merged.values())));
        }
        String signature = profile + "|" + sig;
        long now = System.currentTimeMillis();
        if (!signature.equals(lastSignature) || now - lastSaveMs > 60_000L) {
            lastSignature = signature;
            lastSaveMs = now;
            save();
        }
        return orders.size();
    }

    /** Status of one order against the live Bazaar: is it the best order on its side right now? */
    public enum Standing { TOP, MATCHED, OUTBID, UNKNOWN }

    public static Standing standing(BazaarOrderParser.Order o, BazaarProduct live) {
        if (live == null) {
            return Standing.UNKNOWN;
        }
        double best = o.type() == BazaarOrderParser.Type.BUY ? live.topBuyOrderPrice() : live.topSellOfferPrice();
        if (best <= 0) {
            return Standing.UNKNOWN;
        }
        double diff = o.type() == BazaarOrderParser.Type.BUY ? o.pricePerUnit() - best : best - o.pricePerUnit();
        if (diff > 0.05) {
            return Standing.TOP;
        }
        return diff > -0.05 ? Standing.MATCHED : Standing.OUTBID;
    }

    // ---- persistence --------------------------------------------------------------------------------------------

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!Files.exists(PATH)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("profiles").entrySet()) {
                JsonObject p = e.getValue().getAsJsonObject();
                List<BazaarOrderParser.Order> orders = new ArrayList<>();
                for (JsonElement el : p.getAsJsonArray("orders")) {
                    BazaarOrderParser.Order o = fromJson(el.getAsJsonObject());
                    if (o != null) {
                        orders.add(o);
                    }
                }
                BY_PROFILE.put(e.getKey(), new Snapshot(e.getKey(), p.get("seenAtMs").getAsLong(), List.copyOf(orders)));
            }
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] stored orders unreadable; starting empty", e);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject profiles = new JsonObject();
            for (Snapshot s : BY_PROFILE.values()) {
                JsonObject p = new JsonObject();
                p.addProperty("seenAtMs", s.seenAtMs());
                JsonArray arr = new JsonArray();
                s.orders().forEach(o -> arr.add(toJson(o)));
                p.add("orders", arr);
                profiles.add(s.profile(), p);
            }
            root.add("profiles", profiles);
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] could not save orders", e);
        }
    }

    private static JsonObject toJson(BazaarOrderParser.Order o) {
        JsonObject j = new JsonObject();
        j.addProperty("type", o.type().name());
        j.addProperty("name", o.productName());
        if (o.productId() != null) {
            j.addProperty("id", o.productId());
        }
        j.addProperty("amount", o.amount());
        j.addProperty("filled", o.filled());
        j.addProperty("full", o.full());
        j.addProperty("price", o.pricePerUnit());
        j.addProperty("claimItems", o.claimableItems());
        j.addProperty("claimCoins", o.claimableCoins());
        j.addProperty("claimable", o.claimable());
        j.addProperty("expired", o.expired());
        if (o.expiresIn() != null) {
            j.addProperty("expiresIn", o.expiresIn());
        }
        if (o.owner() != null) {
            j.addProperty("owner", o.owner());
        }
        return j;
    }

    private static BazaarOrderParser.Order fromJson(JsonObject j) {
        try {
            String name = j.get("name").getAsString();
            String id = j.has("id") ? j.get("id").getAsString() : BazaarCatalog.idForName(name);
            return new BazaarOrderParser.Order(BazaarOrderParser.Type.valueOf(j.get("type").getAsString()), name, id,
                    j.get("amount").getAsLong(), j.get("filled").getAsLong(), j.get("full").getAsBoolean(),
                    j.get("price").getAsDouble(), j.get("claimItems").getAsLong(), j.get("claimCoins").getAsDouble(),
                    j.get("claimable").getAsBoolean(), j.get("expired").getAsBoolean(),
                    j.has("expiresIn") ? j.get("expiresIn").getAsString() : null,
                    j.has("owner") ? j.get("owner").getAsString() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** For tests: forget everything in memory (the file stays). */
    public static synchronized void clearForTest() {
        BY_PROFILE.clear();
        lastSignature = "";
        loaded = true;
    }

    /** For tests and the screen: the orders of the current profile, or an empty list. */
    public static List<BazaarOrderParser.Order> currentOrders() {
        Snapshot s = current();
        return s == null ? List.of() : s.orders();
    }

    static Map<String, Snapshot> all() {
        return new HashMap<>(BY_PROFILE);
    }
}
