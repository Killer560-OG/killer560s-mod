package com.killer560.hub.ahflip;

import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.ah.AhReskin;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.ItemValueCalc;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.pricetooltip.PriceTooltipConfig;
import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AH Price Buttons (killer560, 2026-11-03: "We used to have an auction helper that would auto copy the value of something.
 * Instead have it as buttons in the menu. It should work if you are using custom ah or normal. Have one for lbin one for
 * average and one for estimated value").
 *
 * <p>On Hypixel's "Create BIN Auction" menu, four buttons copy a price for the item in its item slot (13) to the clipboard
 * as plain digits (what Hypixel's price sign takes):
 * <ul>
 *   <li><b>Copy LBIN</b>: the lowest BIN of that exact item ({@link AhItemKey}: id, dungeon stars, Recombobulator, a pet's
 *       type/rarity/level) from the last full AH scan ({@link AhMarketIndex}), times the stack, less the Undercut.</li>
 *   <li><b>Copy Average</b>: the 1- or 3-day AVERAGE LOWEST BIN (the Price Tooltip's Average Days, 3 by default) from
 *       Skyblocker's public price API, hysky.de {@code /api/auctions/lowestbins/average?days=N}, via {@link PriceData},
 *       times the stack. It is not an average sale price: nothing keyless gives one per item.</li>
 *   <li><b>Copy Estimated Value</b>: the Price Tooltip's Est. Item Value ({@link ItemValueCalc}, its Value Mode) of the
 *       item with its modifiers, times the stack.</li>
 *   <li><b>Copy My Last Price</b>: the price you listed the same item at in the last 6 hours ({@link AhPriceCopier}),
 *       times the stack (the old copier's Use My Last Price, as a button).</li>
 * </ul>
 * A button with no price is grayed out and its tooltip says why. Drawn beside the chest in Hypixel's own menu, and in the
 * toolbar of the Auction House reskin ({@code AhReskin}). Clipboard ONLY: nothing here clicks, types or sends anything.
 */
public final class AhPriceButtons {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Price Buttons";
    /** How long a clicked button reads "Copied!". */
    private static final long FLASH_MS = 1500L;
    /** How long a computed set of quotes is reused (the menu draws every frame). */
    private static final long QUOTE_TTL_MS = 500L;

    public enum Kind {
        LBIN("Copy LBIN", "LBIN", "price-lbin"),
        AVERAGE("Copy Average", "Avg", "price-average"),
        VALUE("Copy Estimated Value", "Value", "price-value"),
        LAST("Copy My Last Price", "Last", "price-last");

        public final String label;
        public final String compact;
        public final String id;

        Kind(String label, String compact, String id) {
            this.label = label;
            this.compact = compact;
            this.id = id;
        }
    }

    /** One button's price: {@code coins > 0} when it can copy, else {@code why} says why not. */
    public record Quote(Kind kind, long coins, List<Component> tip, String source) {
        public boolean available() {
            return coins > 0;
        }
    }

    private static ItemStack quotedFor = ItemStack.EMPTY;
    private static long quotedAt;
    private static List<Quote> quoted = List.of();

    private static Kind flashKind;
    private static long flashAt;
    private static long lastCopied = -1;
    private static String lastCopiedKind = "";

    /** The vanilla panel's buttons of the last frame drawn: {x, y, w, h} per kind, in screen coordinates. */
    private static final int[][] RECTS = new int[Kind.values().length][];
    private static Screen rectsFor;

    private AhPriceButtons() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(AhPriceButtons::onScreenInit);
    }

    // ---- prices ---------------------------------------------------------------------------------------------------

    /** True for Hypixel's Create BIN Auction menu title (color codes stripped). */
    public static boolean isCreateBinTitle(String title) {
        return AhPriceCopier.CREATE_BIN_TITLE.equals(title);
    }

    /** The item being listed: slot 13 of the menu, or EMPTY. */
    public static ItemStack listedItem(AbstractContainerScreen<?> screen) {
        var slots = screen.getMenu().slots;
        if (slots.size() <= AhPriceCopier.ITEM_SLOT) {
            return ItemStack.EMPTY;
        }
        ItemStack s = slots.get(AhPriceCopier.ITEM_SLOT).getItem();
        return s.isEmpty() || ItemNbt.view(s) == null || ItemKeys.id(ItemNbt.view(s)) == null ? ItemStack.EMPTY : s;
    }

    /** The four quotes for {@code stack} (EMPTY: no item yet), in button order. Reused for {@link #QUOTE_TTL_MS}. */
    public static List<Quote> quotes(ItemStack stack) {
        long now = System.currentTimeMillis();
        if (now - quotedAt < QUOTE_TTL_MS && ItemStack.matches(stack, quotedFor) && !quoted.isEmpty()) {
            return quoted;
        }
        // Keep the price lists fetching even with the Price Tooltip off, and the AH scan indexed.
        PriceData.touchFromOtherFeature();
        AuctionHouseApi.ensureAutoScanStarted();
        AhMarketIndex.refreshIfStale();
        List<Quote> out = compute(stack);
        quotedFor = stack.copy();
        quotedAt = now;
        quoted = out;
        return out;
    }

    static List<Quote> compute(ItemStack stack) {
        List<Quote> out = new ArrayList<>(4);
        if (stack == null || stack.isEmpty()) {
            for (Kind k : Kind.values()) {
                out.add(off(k, "Put an item in the menu first."));
            }
            return out;
        }
        String name = BazaarFlipMenus.nameOf(stack);
        int count = Math.max(1, stack.getCount());
        String times = count > 1 ? " x" + count : "";
        CompoundTag tag = ItemNbt.view(stack);
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        boolean pricesLoaded = PriceData.lowestBinCount() > 0 || PriceData.bazaarCount() > 0;

        // Copy LBIN: our own AH scan, the exact item.
        String ahKey = AhItemKey.of(stack, name);
        long lbin = ahKey.isEmpty() ? -1 : AhMarketIndex.lowestBin(ahKey);
        if (lbin > 0) {
            long total = lbin * count;
            long under = cfg.getCopierUndercut();
            long coins = Math.max(1, total - under);
            out.add(new Quote(Kind.LBIN, coins, tip(Kind.LBIN, coins,
                    "Lowest BIN of this exact item in the last AH scan",
                    "(same id, stars and Recombobulator): " + DungeonChestValuer.formatCoins(lbin) + times
                            + (under > 0 ? ", less the Undercut (" + String.format(Locale.US, "%,d", under) + ")" : "")),
                    "lowest BIN"));
        } else if (ahKey.isEmpty()) {
            out.add(off(Kind.LBIN, "This item has no SkyBlock id."));
        } else if (AhMarketIndex.size() == 0) {
            int pct = AuctionHouseApi.getScanProgressPercent();
            out.add(off(Kind.LBIN, AuctionHouseApi.isScanning()
                    ? "The AH scan is still running" + (pct >= 0 ? " (" + pct + "%)" : "") + "."
                    : "The AH scan has not finished yet."));
        } else {
            out.add(off(Kind.LBIN, "No BIN of this exact item (same stars and", "Recombobulator) in the last AH scan."));
        }

        // Copy Average: hysky's N-day average lowest BIN.
        String priceKey = ItemKeys.priceKey(tag, stack.getHoverName().getString());
        int days = PriceTooltipConfig.getInstance().getAverageDays();
        Double avg = priceKey == null ? null : PriceData.average(priceKey, days);
        if (avg != null && avg > 0) {
            long coins = Math.max(1, Math.round(avg * count));
            out.add(new Quote(Kind.AVERAGE, coins, tip(Kind.AVERAGE, coins,
                    days + "-day average lowest BIN (Skyblocker's hysky.de",
                    "price list, the Price Tooltip's Average Days): " + DungeonChestValuer.formatCoins(Math.round(avg)) + times),
                    days + "-day average lowest BIN"));
        } else if (!pricesLoaded) {
            out.add(off(Kind.AVERAGE, "The price lists are still loading."));
        } else {
            out.add(off(Kind.AVERAGE, "No " + days + "-day average lowest BIN for this item",
                    "(a Bazaar item, or none listed lately)."));
        }

        // Copy Estimated Value: the Price Tooltip's Est. Item Value.
        PriceTooltipConfig.ValueMode mode = PriceTooltipConfig.getInstance().getValueMode();
        double value = priceKey == null ? 0 : ItemValueCalc.compute(tag, priceKey, mode).total();
        if (value > 0) {
            long coins = Math.max(1, Math.round(value * count));
            out.add(new Quote(Kind.VALUE, coins, tip(Kind.VALUE, coins,
                    "Est. Item Value with its modifiers (Price Tooltip,",
                    mode.name().charAt(0) + mode.name().substring(1).toLowerCase(Locale.ROOT) + " mode): "
                            + DungeonChestValuer.formatCoins(Math.round(value)) + times),
                    "estimated value"));
        } else if (!pricesLoaded) {
            out.add(off(Kind.VALUE, "The price lists are still loading."));
        } else {
            out.add(off(Kind.VALUE, "No price known for this item or its upgrades."));
        }

        // Copy My Last Price.
        long own = ahKey.isEmpty() ? -1 : AhPriceCopier.rememberedFresh(ahKey);
        if (own > 0) {
            long coins = own * count;
            out.add(new Quote(Kind.LAST, coins, tip(Kind.LAST, coins,
                    "The price you listed this item at in the last 6 hours:",
                    DungeonChestValuer.formatCoins(own) + (count > 1 ? " each" + times : "")), "your last listing"));
        } else {
            out.add(off(Kind.LAST, "You have not listed this item in the last 6 hours."));
        }
        return out;
    }

    private static List<Component> tip(Kind k, long coins, String line1, String line2) {
        return List.of(Component.literal("§6" + k.label),
                Component.literal("§7" + line1), Component.literal("§7" + line2),
                Component.literal("§eCopies §6" + String.format(Locale.US, "%,d", coins) + "§e to your clipboard."));
    }

    private static Quote off(Kind k, String... why) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§7" + k.label + " §8(no price)"));
        for (String w : why) {
            tip.add(Component.literal("§c" + w));
        }
        return new Quote(k, -1, List.copyOf(tip), "");
    }

    /** Writes {@code q}'s price to the clipboard and says so. Returns false (and does nothing) for a grayed-out button. */
    public static boolean copy(Quote q) {
        if (q == null || !q.available()) {
            return false;
        }
        String digits = Long.toString(q.coins());
        Minecraft.getInstance().keyboardHandler.setClipboard(digits);
        lastCopied = q.coins();
        lastCopiedKind = q.kind().name();
        flashKind = q.kind();
        flashAt = System.currentTimeMillis();
        LOGGER.info("[AhPriceButtons] Copied {} ({})", digits, q.source());
        ModChat.send(CHAT, ModChat.text("Copied "), ModChat.value(String.format(Locale.US, "%,d", q.coins())),
                ModChat.dim(" (" + q.source() + ")"));
        return true;
    }

    /** The button's label: "Copied!" for a moment after it was clicked. */
    public static String label(Kind k, boolean compact) {
        if (k == flashKind && System.currentTimeMillis() - flashAt < FLASH_MS) {
            return "Copied!";
        }
        return compact ? k.compact : k.label;
    }

    // ---- Hypixel's own menu ---------------------------------------------------------------------------------------

    private static final int PANEL_BG = 0xD0141008;
    private static final int PANEL_BORDER = 0xFF663D1A;
    private static final int BUTTON_BG = 0xFF2A1A0A;
    private static final int BUTTON_BG_HOVER = 0xFF3D2A14;
    private static final int BUTTON_BORDER = 0xFFCC6600;
    private static final int BUTTON_BORDER_OFF = 0xFF4A3A2A;
    private static final int BUTTON_H = 16;

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> cs) || !isCreateBinTitle(BazaarFlipMenus.titleOf(cs))) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
            try {
                drawVanilla(cs, g, mx, my);
            } catch (Exception e) {
                LOGGER.error("[AhPriceButtons] Render failed", e);
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (rectsFor != screen || com.killer560.hub.compat.McInput.button(event) != 0 || !visible(cs)) {
                return true;
            }
            Kind[] kinds = Kind.values();
            for (int i = 0; i < kinds.length; i++) {
                int[] r = RECTS[i];
                if (r != null && event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                    Quote q = quotes(listedItem(cs)).get(i);
                    if (copy(q)) {
                        client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    }
                    return false; // the press is ours, grayed or not: never reaches Hypixel's menu
                }
            }
            return true;
        });
    }

    private static boolean visible(AbstractContainerScreen<?> screen) {
        return AhFlipConfig.getInstance().isPriceButtonsEnabled() && McCompat.screen(Minecraft.getInstance()) == screen
                && !AhReskin.isHiding(screen);
    }

    private static void drawVanilla(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        java.util.Arrays.fill(RECTS, null);
        rectsFor = null;
        if (!visible(screen)) {
            return;
        }
        List<Quote> qs = quotes(listedItem(screen));
        Font font = Minecraft.getInstance().font;
        int bw = font.width("AH Price Buttons");
        for (Kind k : Kind.values()) {
            bw = Math.max(bw, font.width(k.label));
        }
        bw += 12;
        int panelW = bw + 8;
        int panelH = 4 + 10 + qs.size() * (BUTTON_H + 3) + 2;
        AbstractContainerScreenAccessor geo = (AbstractContainerScreenAccessor) screen;
        int leftPos = geo.killer560smod$getLeftPos();
        int topPos = geo.killer560smod$getTopPos();
        // Left of the chest (the Listing Helper's panel goes on the right); the right side when the left has no room.
        int x = leftPos - panelW - 6;
        if (x < 2) {
            x = Math.min(screen.width - panelW - 2, screen.width - leftPos + 6);
        }
        int y = (int) com.killer560.hub.inventorysearch.InventorySearchFeature.keepAbove(screen, x, Math.max(2, topPos),
                panelW, panelH); // clear of the search bar at the bottom middle
        com.killer560.hub.containercover.ContainerCover.coverRect(screen, "ahflip.pricebuttons", x, y, x + panelW, y + panelH);
        g.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        g.outline(x, y, panelW, panelH, PANEL_BORDER);
        g.text(font, "AH Price Buttons", x + 4, y + 4, 0xFFCC6600, true);
        int by = y + 15;
        int bx = x + 4;
        List<Component> tip = null;
        for (int i = 0; i < qs.size(); i++) {
            Quote q = qs.get(i);
            boolean on = q.available();
            boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + BUTTON_H;
            g.fill(bx, by, bx + bw, by + BUTTON_H, hover && on ? BUTTON_BG_HOVER : BUTTON_BG);
            g.outline(bx, by, bw, BUTTON_H, on ? BUTTON_BORDER : BUTTON_BORDER_OFF);
            String label = label(q.kind(), false);
            g.text(font, label, bx + (bw - font.width(label)) / 2, by + 4, on ? 0xFFFFFFFF : 0xFF6E6458, false);
            RECTS[i] = new int[]{bx, by, bw, BUTTON_H};
            if (hover) {
                tip = q.tip();
            }
            by += BUTTON_H + 3;
        }
        rectsFor = screen;
        if (tip != null) {
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mx, my);
        }
    }

    // ---- testkit --------------------------------------------------------------------------------------------------

    /** Testkit: the last value copied (-1: none) and which button copied it. */
    public static long lastCopiedForTest() {
        return lastCopied;
    }

    public static String lastCopiedKindForTest() {
        return lastCopiedKind;
    }

    /** Testkit: "KIND coins" per button for the item in the open Create BIN Auction menu (coins -1: grayed), fresh. */
    public static List<String> quotesForTest() {
        Screen s = McCompat.screen(Minecraft.getInstance());
        ItemStack stack = s instanceof AbstractContainerScreen<?> c ? listedItem(c) : ItemStack.EMPTY;
        quotedAt = 0;
        List<String> out = new ArrayList<>();
        for (Quote q : quotes(stack)) {
            StringBuilder tipText = new StringBuilder();
            for (Component c : q.tip()) {
                tipText.append(" | ").append(c.getString());
            }
            out.add(q.kind().name() + " " + q.coins() + tipText);
        }
        return out;
    }

    /** Testkit: the vanilla panel's button rects of the last frame, "KIND x y w h" (empty when not drawn). */
    public static List<String> vanillaRectsForTest() {
        List<String> out = new ArrayList<>();
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            int[] r = RECTS[i];
            if (r != null) {
                out.add(kinds[i].name() + " " + r[0] + " " + r[1] + " " + r[2] + " " + r[3]);
            }
        }
        return out;
    }

    public static void resetForTest() {
        lastCopied = -1;
        lastCopiedKind = "";
        flashKind = null;
        quotedAt = 0;
        quoted = List.of();
    }
}
