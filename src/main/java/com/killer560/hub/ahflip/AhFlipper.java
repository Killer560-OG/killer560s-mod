package com.killer560.hub.ahflip;

import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AH Flipper (killer560, 2026-11-01, to-do Doc "AH FLIPPER": "scan the full ah and find the selling price of stuff then
 * snipe flips and relist them ... Make one that is a macro and the other sends a chat message about the flip and you click
 * on it to go to that ah page and buy it manually. The buy manually one shouldnt be a cheat").
 *
 * <p>How it finds flips: the full Auction House scan ({@code AuctionHouseApi}, Hypixel's keyless
 * {@code /v2/skyblock/auctions}, every five minutes) gives each item's cheapest BINs ({@link AhMarketIndex}); between scans
 * this polls page 0 (the newest listings) every {@link #POLL_MS} and judges each BIN it has not seen with
 * {@link AhFlipPricing#evaluate}. The API needs no key, so nothing goes through the relay.
 *
 * <p>What it does with one: in <b>Background</b> (legit) it says so in chat with a clickable [Open] that runs
 * {@code /viewauction <id>} - your click, your purchase. In <b>Main</b> (cheat build) it hands the flip to
 * {@link AhFlipMacro}, which buys it.
 */
public final class AhFlipper {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Flipper";
    /** Page 0 poll interval. Hypixel rebuilds the snapshot about once a minute; an unchanged one is skipped. */
    public static final long POLL_MS = 15_000L;
    /** A flip older than this is not offered (somebody else has bought it by then). */
    public static final long FLIP_MAX_AGE_MS = 90_000L;
    private static final int SEEN_CAP = 50_000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-ahflip-poll");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    private static final ConcurrentLinkedQueue<Found> FOUND = new ConcurrentLinkedQueue<>();
    /** Auctions already judged, newest last; guarded by itself. */
    private static final Map<UUID, Boolean> SEEN = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > SEEN_CAP;
        }
    };

    private static volatile long lastPollMs;
    private static volatile long lastUpdated;
    private static volatile UUID self;
    private static volatile long purse;
    private static volatile int found;

    /** One flip and when it was found. */
    public record Found(AhFlipPricing.Flip flip, long atMs) {
    }

    private AhFlipper() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.end("AhFlipper.tick", AhFlipper::tick));
        AhFlipMacro.register();
        AhPriceCopier.register();
        AhPriceButtons.register();
        AhMenuRecorder.register();
    }

    private static void tick(Minecraft client) {
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        boolean copier = cfg.isPriceButtonsEnabled();
        boolean flipper = cfg.isFlipperEnabled();
        if (!copier && !flipper) {
            FOUND.clear();
            return;
        }
        if (client.player == null) {
            return;
        }
        // Both read the full scan: make sure it runs even with the Auction House browser off.
        AuctionHouseApi.ensureAutoScanStarted();
        AhMarketIndex.refreshIfStale();
        if (!flipper) {
            FOUND.clear();
            return;
        }
        self = client.player.getUUID();
        Double p = com.killer560.hub.bazaarflip.BazaarFlipFeature.readPurse();
        purse = p == null ? 0L : (long) Math.floor(p);
        long now = System.currentTimeMillis();
        if (now - lastPollMs >= POLL_MS && AhMarketIndex.size() > 0 && polling.compareAndSet(false, true)) {
            lastPollMs = now;
            AhFlipPricing.Settings s = AhFlipPricing.Settings.of(cfg);
            WORKER.execute(() -> {
                try {
                    AuctionHouseApi.PageZero page = AuctionHouseApi.fetchPageZero();
                    if (page != null && page.lastUpdated() != lastUpdated) {
                        lastUpdated = page.lastUpdated();
                        judge(page.listings(), s, purse, self, System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    LOGGER.warn("[AhFlip] Page 0 poll failed: {}", e.toString());
                } finally {
                    polling.set(false);
                }
            });
        }
        drain(client, cfg, now);
    }

    /** Judges every BIN not seen before; queues the flips. Returns how many it found. */
    static int judge(List<AuctionListing> listings, AhFlipPricing.Settings s, long purse, UUID self, long now) {
        int n = 0;
        for (AuctionListing l : listings) {
            if (!l.bin() || !l.decoded()) {
                continue;
            }
            synchronized (SEEN) {
                if (SEEN.put(l.uuid(), Boolean.TRUE) != null) {
                    continue;
                }
            }
            if (self != null && self.equals(l.auctioneer())) {
                continue; // his own listing
            }
            String key = AhItemKey.of(l.icon(), l.itemName());
            if (key.isEmpty() || AhItemKey.isPet(key)) {
                continue;
            }
            AhFlipPricing.Flip flip = AhFlipPricing.evaluate(l.uuid(), key, l.itemName(), l.startingBid(),
                    AhMarketIndex.market(key), s, purse);
            if (flip != null) {
                FOUND.add(new Found(flip, now));
                found++;
                n++;
                LOGGER.info("[AhFlip] Flip: {} ({}) for {}, sells ~{}, +{} ({}%), {} other BINs", flip.name(), key,
                        flip.price(), flip.sellAt(), flip.profit(), String.format(Locale.US, "%.1f", flip.percent()),
                        flip.listings());
            }
        }
        return n;
    }

    private static void drain(Minecraft client, AhFlipConfig cfg, long now) {
        Found f;
        while ((f = FOUND.poll()) != null) {
            if (now - f.atMs() > FLIP_MAX_AGE_MS) {
                continue;
            }
            announce(f.flip());
            if (cfg.isMacroActive()) {
                AhFlipMacro.offer(f);
            }
        }
    }

    /** The chat line: the item, its price, the sale it should make, and a clickable [Open] that runs /viewauction. */
    static void announce(AhFlipPricing.Flip flip) {
        String command = "/viewauction " + undashed(flip.uuid());
        MutableComponent open = Component.literal("[Open]").withStyle(st -> st.withColor(ChatFormatting.GOLD)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
        lastOpen = open;
        ModChat.send(CHAT, ModChat.value(flip.name()), ModChat.text(" for "),
                ModChat.value(DungeonChestValuer.formatCoins(flip.price())),
                ModChat.dim(String.format(Locale.US, " - sells ~%s, +%s (%.1f%%), %d listed ",
                        DungeonChestValuer.formatCoins(flip.sellAt()), DungeonChestValuer.formatCoins(flip.profit()),
                        flip.percent(), flip.listings())), open);
    }

    /** Hypixel's auction id form (32 hex digits, no dashes), as the API gives it and /viewauction takes it. */
    public static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** Testkit: judges one {@code /v2/skyblock/auctions} page body now against the current index; returns the flips found. */
    public static int judgePageForTest(String body, long purseCoins) {
        AuctionHouseApi.PageZero page = AuctionHouseApi.decodePage(
                com.google.gson.JsonParser.parseString(body).getAsJsonObject());
        return judge(page.listings(), AhFlipPricing.Settings.of(AhFlipConfig.getInstance()), purseCoins, null,
                System.currentTimeMillis());
    }

    /** Testkit: forget the auctions already judged and the queue. */
    public static void resetForTest() {
        synchronized (SEEN) {
            SEEN.clear();
        }
        FOUND.clear();
        found = 0;
        lastOpen = null;
    }

    /** The [Open] part of the last flip announced (testkit: its click event). */
    private static volatile Component lastOpen;

    public static Component lastOpenForTest() {
        return lastOpen;
    }

    /** The queued flips (testkit: what the next tick will announce). */
    public static List<Found> queued() {
        return new ArrayList<>(FOUND);
    }
}
