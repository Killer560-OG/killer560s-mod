package com.killer560.hub.auction.ah;

import com.killer560.hub.auction.AuctionHouseConfig;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.packdisabler.ItemLooks;
import com.killer560.hub.util.ChatColors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The unified Auction House's drawing kit: one frame of the look both the API browser
 * ({@code auction/screen/AuctionHouseScreen}) and the reskin of Hypixel's real menus ({@link AhReskin}) are drawn in,
 * so moving between them never changes the frame - header with the category tabs, toolbar with search and filters,
 * recents rail on the left, content, and a bottom bar with only the actions that matter.
 * <p>
 * Everything is laid out in "layout units" ({@link #w} x {@link #h}); the caller applies Auto Scale's pose. Every text,
 * region and clickable element drawn is recorded ({@link #layout}: {@code region name x y w h},
 * {@code text region x y w 8 string}, {@code hotspot <slot|action> x y w h label}) so the testkit can prove nothing
 * overlaps or leaves its box, and every clickable element is a {@link Hotspot}: either a menu slot (with the stack drawn
 * for it, so a click is refused if the slot changed) or a UI action.
 * <p>
 * The backdrop is opaque: no HUD of this mod (or any) shows through while the AH is open.
 */
public final class AhUi {

    /** One clickable element: {@code slot >= 0} is a container slot click, otherwise {@code action} runs with the button. */
    public record Hotspot(int x, int y, int w, int h, int slot, ItemStack stack, IntConsumer action, String label) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A category tab: API tabs carry an action, container tabs a slot. */
    public record Tab(String label, ItemStack icon, boolean selected, int slot, ItemStack slotStack, IntConsumer action) {
    }

    /** A toolbar or bottom-bar button. */
    public record Button(String label, String compact, ItemStack icon, boolean on, int slot, ItemStack slotStack,
                         IntConsumer action, String id) {
    }

    /** The API browser's six tabs plus All: label, Hypixel's {@code category} value ("" = all, "*misc" = the rest). */
    public static final String[][] API_TABS = {
            {"All", ""}, {"Weapons", "weapon"}, {"Armor", "armor"}, {"Accessories", "accessories"},
            {"Consumables", "consumables"}, {"Blocks", "blocks"}, {"Tools & Misc", "*misc"}};

    public static final int HEADER_H = 16;
    public static final int TOOLBAR_H = 16;
    public static final int BAR_H = 20;

    public final GuiGraphicsExtractor g;
    public final Font font;
    public final AhTheme t;
    public final int w;
    public final int h;
    public final int mx;
    public final int my;
    public final List<String> layout;
    public final List<Hotspot> hotspots;

    // Geometry (layout units), fixed by the size.
    public final int panelX, panelY, panelW, panelH;
    public final int headerY, toolbarY, bodyY, bodyBottom, barY;
    public final int railX, railW, contentX, contentW;

    /** Set while drawing: the stack under the mouse (its tooltip is shown) and extra lines for it. */
    public ItemStack hoverStack = ItemStack.EMPTY;
    public final List<Component> hoverExtra = new ArrayList<>();
    /** A plain tooltip (no stack), used when {@link #hoverStack} is empty. */
    public List<Component> hoverLines;
    /** While set (scrolling content), only what lies wholly inside y0..y1 is recorded, hotspots are cut to it, and the
     *  mouse only hovers inside it. */
    public int clipY0 = Integer.MIN_VALUE;
    public int clipY1 = Integer.MAX_VALUE;

    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    public AhUi(GuiGraphicsExtractor g, int w, int h, int mx, int my, List<String> layout, List<Hotspot> hotspots) {
        this.g = g;
        this.font = Minecraft.getInstance().font;
        this.t = AhTheme.current();
        this.w = w;
        this.h = h;
        this.mx = mx;
        this.my = my;
        this.layout = layout;
        this.hotspots = hotspots;
        int margin = h < 300 ? 4 : 8;
        panelW = Math.max(120, Math.min(w - 2 * margin, 780));
        panelH = Math.max(120, h - 2 * margin);
        panelX = (w - panelW) / 2;
        panelY = margin;
        headerY = panelY + 5;
        toolbarY = headerY + HEADER_H + 4;
        barY = panelY + panelH - 5 - BAR_H;
        bodyY = toolbarY + TOOLBAR_H + 5;
        bodyBottom = barY - 5;
        railW = panelW >= 440 ? Math.max(92, Math.min(136, panelW / 6)) : 0;
        railX = panelX + 5;
        contentX = railW > 0 ? railX + railW + 5 : panelX + 5;
        contentW = panelX + panelW - 5 - contentX;
    }

    // ---- frame ----------------------------------------------------------------------------------------------------

    public void frame() {
        layout.add("frame " + w + " " + h);
        g.fill(0, 0, w, h, t.backdrop());
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, t.panel());
        g.outline(panelX, panelY, panelW, panelH, t.border());
        // A thin accent line under the header, the look's one strong stroke.
        g.fill(panelX + 1, toolbarY - 3, panelX + panelW - 1, toolbarY - 2, t.borderStrong());
        region("panel", panelX, panelY, panelW, panelH);
    }

    /**
     * Header: brand, tabs, and the right-hand button. Tabs fall back to icons (label in the tooltip) and then the brand
     * goes, so nothing ever overlaps at a small size.
     */
    public void header(List<Tab> tabs, String rightLabel, IntConsumer rightAction, String rightId, boolean busy) {
        int y = headerY;
        region("header", panelX + 4, y, panelW - 8, HEADER_H);
        int right = panelX + panelW - 5;
        int bw = rightLabel == null ? 0 : font.width(rightLabel) + 10;
        int rightEdge = right;
        if (rightLabel != null) {
            int bx = right - bw;
            pill(bx, y + 1, bw, HEADER_H - 2, false, rightLabel, t.dim(), "header");
            addHotspot(new Hotspot(bx, y + 1, bw, HEADER_H - 2, -1, ItemStack.EMPTY, rightAction, rightLabel));
            hotspot(rightId, bx, y + 1, bw, HEADER_H - 2, rightLabel);
            rightEdge = bx - 6;
        }
        String brand = "Auction House";
        int brandW = 18 + font.width(brand);
        int left = panelX + 6;
        int full = 0;
        for (Tab tab : tabs) {
            full += font.width(tab.label()) + 12 + 2;
        }
        int icons = tabs.size() * 20;
        boolean showBrand;
        boolean labels;
        if (left + brandW + 10 + full <= rightEdge) {
            showBrand = true;
            labels = true;
        } else if (left + full <= rightEdge) {
            showBrand = false;
            labels = true;
        } else if (left + brandW + 10 + icons <= rightEdge) {
            showBrand = true;
            labels = false;
        } else {
            showBrand = false;
            labels = false;
        }
        int x = left;
        if (showBrand) {
            g.item(new ItemStack(Items.GOLD_INGOT), x, y);
            text("header", brand, x + 18, y + 4, t.accentBright());
            x += brandW + 10;
        }
        if (busy) {
            // A small pulsing bar under the brand: the next menu is on its way.
            int pw = Math.max(10, brandW - 18);
            long ms = System.currentTimeMillis() % 1200L;
            int px = (int) (ms * (pw + 20) / 1200L) - 20;
            int bx0 = left + 18 + Math.max(0, px);
            int bx1 = left + 18 + Math.min(pw, px + 20);
            if (bx1 > bx0) {
                g.fill(bx0, y + HEADER_H - 1, bx1, y + HEADER_H, t.accent());
            }
        }
        for (Tab tab : tabs) {
            int tw = labels ? font.width(tab.label()) + 12 : 18;
            if (x + tw > rightEdge) {
                break;
            }
            boolean hover = in(x, y, tw, HEADER_H);
            int bg = tab.selected() ? t.selected() : hover ? t.hover() : 0;
            if (bg != 0) {
                g.fill(x, y, x + tw, y + HEADER_H, bg);
            }
            if (tab.selected()) {
                g.fill(x, y + HEADER_H - 2, x + tw, y + HEADER_H, t.accent());
            }
            if (labels) {
                text("header", tab.label(), x + 6, y + 4, tab.selected() ? t.accentBright() : hover ? t.text() : t.dim());
            } else {
                g.item(tab.icon(), x + 1, y);
                if (hover) {
                    hoverLines = List.of(Component.literal(tab.label()));
                }
            }
            if (tab.slot() >= 0) {
                addHotspot(new Hotspot(x, y, tw, HEADER_H, tab.slot(), tab.slotStack(), null, tab.label()));
                hotspot(String.valueOf(tab.slot()), x, y, tw, HEADER_H, tab.label());
                if (hover && labels) {
                    hoverStack = tab.slotStack();
                }
            } else {
                addHotspot(new Hotspot(x, y, tw, HEADER_H, -1, ItemStack.EMPTY, tab.action(), tab.label()));
                hotspot("tab:" + tab.label(), x, y, tw, HEADER_H, tab.label());
            }
            x += tw + 2;
        }
    }

    /**
     * Toolbar: a search field taking what room is left after the buttons, which shrink to their compact labels first.
     * Returns the search field's box {x, y, w, h}.
     */
    public int[] toolbar(String searchText, String hint, boolean focused, IntConsumer searchAction, String searchSlotId,
            int searchSlot, ItemStack searchStack, List<Button> buttons, String status) {
        int y = toolbarY;
        int left = panelX + 5;
        int right = panelX + panelW - 5;
        region("toolbar", left, y, right - left, TOOLBAR_H);
        int minSearch = 80;
        int statusW = status == null || status.isEmpty() ? 0 : font.width(status) + 8;
        boolean compact = false;
        int bwTotal = buttonsWidth(buttons, false);
        if (right - left - bwTotal - statusW < minSearch) {
            compact = true;
            bwTotal = buttonsWidth(buttons, true);
        }
        if (right - left - bwTotal - statusW < minSearch) {
            statusW = 0;
        }
        int searchW = Math.max(40, right - left - bwTotal - statusW);
        // the search field
        boolean hover = in(left, y, searchW, TOOLBAR_H);
        g.fill(left, y, left + searchW, y + TOOLBAR_H, t.surface());
        g.outline(left, y, searchW, TOOLBAR_H, focused ? t.accent() : hover ? t.borderStrong() : t.border());
        String icon = "⌕";
        text("toolbar", icon, left + 4, y + 4, t.faint());
        int tx = left + 6 + font.width(icon);
        int room = searchW - (tx - left) - 6;
        if (searchText == null || searchText.isEmpty()) {
            if (!focused) {
                text("toolbar", fit(hint, room), tx, y + 4, t.faint());
            }
        } else {
            text("toolbar", fitTail(searchText, room), tx, y + 4, t.text());
        }
        if (focused && (System.currentTimeMillis() / 500L) % 2 == 0) {
            int cx = tx + Math.min(room, font.width(fitTail(searchText == null ? "" : searchText, room)));
            g.fill(cx, y + 3, cx + 1, y + 13, t.accentBright());
        }
        if (searchSlot >= 0) {
            addHotspot(new Hotspot(left, y, searchW, TOOLBAR_H, searchSlot, searchStack, null, "Search"));
            hotspot(String.valueOf(searchSlot), left, y, searchW, TOOLBAR_H, "Search");
        } else if (searchAction != null) {
            addHotspot(new Hotspot(left, y, searchW, TOOLBAR_H, -1, ItemStack.EMPTY, searchAction, "Search"));
            hotspot(searchSlotId, left, y, searchW, TOOLBAR_H, "Search");
        }
        int x = left + searchW + 4;
        for (Button b : buttons) {
            String label = compact ? b.compact() : b.label();
            int bw = font.width(label) + 10;
            if (x + bw > right - statusW + 1) {
                break;
            }
            button(b, label, x, y, bw, TOOLBAR_H, "toolbar");
            x += bw + 3;
        }
        if (statusW > 0) {
            String s = fit(status, statusW - 6);
            text("toolbar", s, right - font.width(s), y + 4, t.faint());
        }
        return new int[]{left, y, searchW, TOOLBAR_H};
    }

    private int buttonsWidth(List<Button> buttons, boolean compact) {
        int total = 4;
        for (Button b : buttons) {
            total += font.width(compact ? b.compact() : b.label()) + 10 + 3;
        }
        return total;
    }

    /** One pill button: a slot button (hotspot on its slot) or an action button. */
    public void button(Button b, String label, int x, int y, int bw, int bh, String region) {
        boolean hover = in(x, y, bw, bh);
        int bg = b.on() ? t.selected() : hover ? t.hover() : t.surfaceAlt();
        g.fill(x, y, x + bw, y + bh, bg);
        g.outline(x, y, bw, bh, hover || b.on() ? t.accent() : t.border());
        int textX = x + 5;
        if (b.icon() != null && !b.icon().isEmpty() && bh >= 18) {
            g.item(b.icon(), x + 2, y + (bh - 16) / 2);
            textX = x + 20;
        }
        if (!label.isEmpty()) {
            text(region, label, textX, y + (bh - 8) / 2, b.on() ? t.accentBright() : hover ? t.text() : t.dim());
        }
        if (b.slot() >= 0) {
            addHotspot(new Hotspot(x, y, bw, bh, b.slot(), b.slotStack(), null, b.label()));
            hotspot(String.valueOf(b.slot()), x, y, bw, bh, b.label());
            if (hover) {
                hoverStack = b.slotStack();
            }
        } else {
            addHotspot(new Hotspot(x, y, bw, bh, -1, ItemStack.EMPTY, b.action(), b.label()));
            hotspot(b.id(), x, y, bw, bh, b.label());
        }
    }

    /** The bottom bar: buttons from the left (icons only when they do not fit), {@code status} on the right. */
    public void bottomBar(List<Button> buttons, String status, List<Button> rightButtons) {
        int y = barY;
        int left = panelX + 5;
        int right = panelX + panelW - 5;
        region("bar", left, y, right - left, BAR_H);
        g.fill(left, y - 3, right, y - 2, t.border());
        int rx = right;
        for (int i = rightButtons.size() - 1; i >= 0; i--) {
            Button b = rightButtons.get(i);
            int bw = font.width(b.label()) + 10;
            if (rx - bw < left + 40) {
                break;
            }
            button(b, b.label(), rx - bw, y, bw, BAR_H, "bar");
            rx -= bw + 3;
        }
        int total = 0;
        for (Button b : buttons) {
            total += (b.icon() == null || b.icon().isEmpty() ? 10 : 24) + font.width(b.label()) + 3;
        }
        int statusRoom = status == null || status.isEmpty() ? 0 : Math.min(font.width(status) + 8, 160);
        boolean iconsOnly = left + total + statusRoom > rx;
        int x = left;
        for (Button b : buttons) {
            boolean hasIcon = b.icon() != null && !b.icon().isEmpty();
            String label = iconsOnly && hasIcon ? "" : b.label();
            int bw = label.isEmpty() ? 20 : (hasIcon ? 24 : 10) + font.width(label);
            if (x + bw > rx) {
                break;
            }
            button(b, label, x, y, bw, BAR_H, "bar");
            if (label.isEmpty() && in(x, y, bw, BAR_H) && b.slot() < 0) {
                hoverLines = List.of(Component.literal(b.label()));
            }
            x += bw + 3;
        }
        if (status != null && !status.isEmpty() && rx - x > 20) {
            String s = fit(status, rx - x - 8);
            text("bar", s, rx - 4 - font.width(s), y + 6, t.faint());
        }
    }

    // ---- rail -----------------------------------------------------------------------------------------------------

    /** The recents rail: recent searches and recently viewed items. Does nothing when the panel is too narrow. */
    public void recentsRail(IntConsumerString onSearch, java.util.function.Consumer<AuctionHouseConfig.Viewed> onViewed,
            Runnable onClear) {
        if (railW <= 0) {
            return;
        }
        int x = railX;
        int y = bodyY;
        int hgt = bodyBottom - bodyY;
        g.fill(x, y, x + railW, y + hgt, t.surfaceAlt());
        g.outline(x, y, railW, hgt, t.border());
        region("rail", x, y, railW, hgt);
        AuctionHouseConfig cfg = AuctionHouseConfig.getInstance();
        List<String> searches = cfg.getRecentSearches();
        List<AuctionHouseConfig.Viewed> viewed = cfg.getRecentViewed();
        int ry = y + 4;
        int bottom = y + hgt - 3;
        text("rail", fit("RECENT SEARCHES", railW - 8), x + 5, ry, t.faint());
        ry += 11;
        if (searches.isEmpty()) {
            if (ry + 9 <= bottom) {
                text("rail", fit("None yet", railW - 10), x + 6, ry, t.faint());
            }
            ry += 11;
        }
        for (String s : searches) {
            if (ry + 11 > bottom) {
                break;
            }
            boolean hover = in(x + 1, ry - 1, railW - 2, 11);
            if (hover) {
                g.fill(x + 1, ry - 1, x + railW - 1, ry + 10, t.hover());
            }
            text("rail", fit(s, railW - 12), x + 6, ry + 1, hover ? t.text() : t.dim());
            String label = s;
            addHotspot(new Hotspot(x + 1, ry - 1, railW - 2, 11, -1, ItemStack.EMPTY, b -> onSearch.accept(label), s));
            hotspot("recent-search", x + 1, ry - 1, railW - 2, 11, s);
            ry += 11;
        }
        ry += 4;
        if (ry + 9 <= bottom) {
            text("rail", fit("RECENTLY VIEWED", railW - 8), x + 5, ry, t.faint());
        }
        ry += 11;
        if (viewed.isEmpty() && ry + 9 <= bottom) {
            text("rail", fit("Nothing yet", railW - 10), x + 6, ry, t.faint());
        }
        for (AuctionHouseConfig.Viewed v : viewed) {
            if (ry + 20 > bottom) {
                break;
            }
            boolean hover = in(x + 1, ry - 1, railW - 2, 20);
            if (hover) {
                g.fill(x + 1, ry - 1, x + railW - 1, ry + 19, t.hover());
            }
            ItemStack icon = iconFor(v.skyblockId());
            g.item(icon, x + 3, ry + 1);
            text("rail", fit(v.name(), railW - 26), x + 22, ry + 1, tierColor(v.tier(), hover ? t.text() : t.dim()));
            long lb = AhMarket.lowestBin(v.skyblockId());
            if (lb > 0) {
                text("rail", fit("Lowest BIN " + coins(lb), railW - 26), x + 22, ry + 10, t.gold());
            }
            addHotspot(new Hotspot(x + 1, ry - 1, railW - 2, 20, -1, ItemStack.EMPTY, b -> onViewed.accept(v), v.name()));
            hotspot("recent-viewed", x + 1, ry - 1, railW - 2, 20, v.name());
            ry += 21;
        }
        if (!searches.isEmpty() || !viewed.isEmpty()) {
            String clear = "Clear";
            int cw = font.width(clear) + 6;
            int cy = y + hgt - 13;
            if (cy > ry) {
                boolean hover = in(x + railW - cw - 3, cy, cw, 11);
                text("rail", clear, x + railW - cw, cy + 2, hover ? t.accentBright() : t.faint());
                addHotspot(new Hotspot(x + railW - cw - 3, cy, cw, 11, -1, ItemStack.EMPTY, b -> onClear.run(), clear));
                hotspot("recent-clear", x + railW - cw - 3, cy, cw, 11, clear);
            }
        }
    }

    /** A {@code Consumer<String>} that reads better at the call sites. */
    public interface IntConsumerString {
        void accept(String s);
    }

    // ---- content: cards -------------------------------------------------------------------------------------------

    /** Columns and card width for a grid in {@code width}. */
    public int[] gridColumns(int width, int minCard) {
        int gap = 5;
        int cols = Math.max(1, (width + gap) / (minCard + gap));
        int cardW = (width - gap * (cols - 1)) / cols;
        return new int[]{cols, cardW, gap};
    }

    /**
     * A listing card: rarity edge, icon, name (the item's own colours), price line, and a sub line. Records a hotspot:
     * a slot click when {@code slot >= 0}, else {@code action}. Clipped rows record nothing (they are not on screen).
     */
    public void listingCard(int x, int y, int cw, int ch, ItemStack icon, Component name, String price, int priceColor,
            String badge, int badgeColor, String sub, int edgeColor, boolean marked, int slot, IntConsumer action,
            String id, boolean record, List<Component> extra) {
        boolean hover = record && in(x, y, cw, ch);
        boolean whole = record && inClip(y, ch);
        g.fill(x, y, x + cw, y + ch, hover ? t.cardHover() : t.card());
        g.outline(x, y, cw, ch, hover ? t.accent() : marked ? t.cardGreen() : t.border());
        g.fill(x + 1, y + 1, x + 3, y + ch - 1, edgeColor);
        g.item(icon, x + 6, y + (ch - 16) / 2);
        g.itemDecorations(font, icon, x + 6, y + (ch - 16) / 2);
        int tx = x + 26;
        int right = x + cw - 5;
        int badgeW = badge == null || badge.isEmpty() ? 0 : font.width(badge) + 6;
        FormattedCharSequence n = fitComponent(name, right - tx - (badgeW > 0 ? badgeW + 4 : 0));
        componentText(whole, "card", n, tx, y + 4);
        if (badgeW > 0) {
            int bx = right - badgeW;
            g.fill(bx, y + 3, bx + badgeW, y + 13, AhTheme.mix(badgeColor, t.card(), 0.70f));
            plain(whole, "card", badge, bx + 3, y + 4, badgeColor);
        }
        plain(whole, "card", fit(price, right - tx), tx, y + 15, priceColor);
        if (ch >= 34 && sub != null && !sub.isEmpty()) {
            plain(whole, "card", fit(sub, right - tx), tx, y + 25, t.cardFaint());
        }
        if (whole) {
            region("card", x, y, cw, ch);
        }
        if (record) {
            addHotspot(new Hotspot(x, y, cw, ch, slot, slot >= 0 ? icon.copy() : ItemStack.EMPTY,
                    slot >= 0 ? null : action, name.getString()));
            hotspot(slot >= 0 ? String.valueOf(slot) : id, x, y, cw, ch, name.getString());
            if (hover) {
                hoverStack = icon;
                hoverExtra.clear();
                if (extra != null) {
                    hoverExtra.addAll(extra);
                }
            }
        }
    }

    /** What {@link #actionCard} needs to show the whole lore at {@code cw} (same spacing), at least 30. */
    public int actionCardHeight(ItemStack stack, int cw) {
        int hgt = 24;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            boolean lastBlank = true;
            for (Component line : lore.lines()) {
                if (line.getString().isBlank()) {
                    if (!lastBlank) {
                        hgt += 4;
                    }
                    lastBlank = true;
                    continue;
                }
                lastBlank = false;
                hgt += 10 * Math.max(1, font.split(line, cw - 12).size());
            }
        }
        return Math.max(30, hgt + 4);
    }

    /**
     * A menu button drawn as a card: icon, name, Hypixel's lore in its own colours (cut at the card; the tooltip has
     * all of it). {@code tone} tints the card for confirm (green) and cancel (red) buttons; 0 for none.
     */
    public void actionCard(int x, int y, int cw, int ch, ItemStack stack, int slot, int tone, String region) {
        boolean hover = in(x, y, cw, ch);
        boolean whole = inClip(y, ch);
        int base = tone != 0 ? AhTheme.mix(tone, t.card(), 0.78f) : t.card();
        g.fill(x, y, x + cw, y + ch, hover ? (tone != 0 ? AhTheme.mix(tone, t.card(), 0.6f) : t.cardHover()) : base);
        g.outline(x, y, cw, ch, hover ? (tone != 0 ? tone : t.accent()) : tone != 0 ? AhTheme.mix(tone, t.card(), 0.4f)
                : t.border());
        if (whole) {
            region(region, x, y, cw, ch);
        }
        g.item(stack, x + 5, y + 5);
        g.itemDecorations(font, stack, x + 5, y + 5);
        componentText(whole, region, fitComponent(stack.getHoverName(), cw - 30), x + 25, y + 9);
        int ly = y + 26;
        int bottom = y + ch - 3;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && ch > 32) {
            g.enableScissor(x + 1, y + 1, x + cw - 1, y + ch - 1);
            try {
                boolean lastBlank = true;
                for (Component line : lore.lines()) {
                    if (ly + 9 > bottom) {
                        break;
                    }
                    if (line.getString().isBlank()) {
                        if (!lastBlank) {
                            ly += 4;
                        }
                        lastBlank = true;
                        continue;
                    }
                    lastBlank = false;
                    for (FormattedCharSequence seq : font.split(line, cw - 12)) {
                        if (ly + 9 > bottom) {
                            break;
                        }
                        componentText(whole, region, seq, x + 6, ly);
                        ly += 10;
                    }
                }
            } finally {
                g.disableScissor();
            }
        }
        addHotspot(new Hotspot(x, y, cw, ch, slot, stack.copy(), null, stack.getHoverName().getString()));
        hotspot(String.valueOf(slot), x, y, cw, ch, stack.getHoverName().getString());
        if (hover) {
            hoverStack = stack;
        }
    }

    /** A big item with its name and up to four info lines (the auction or the item being listed). */
    public int hero(int x, int y, int cw, ItemStack stack, Component name, List<String[]> info, int slot) {
        int hgt = Math.max(42, 16 + 10 * Math.min(5, info.size()));
        region("hero", x, y, cw, hgt);
        boolean hover = in(x, y, Math.min(cw, 40), hgt);
        g.fill(x, y, x + 38, y + 38, t.card());
        g.outline(x, y, 38, 38, t.border());
        g.pose().pushMatrix();
        g.pose().translate(x + 3, y + 3);
        g.pose().scale(2f, 2f);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
        int tx = x + 46;
        int tw = x + cw - tx;
        componentText(true, "hero", fitComponent(name, tw), tx, y + 2);
        int ly = y + 14;
        for (String[] kv : info) {
            if (ly + 9 > y + hgt) {
                break;
            }
            String k = kv[0] + " ";
            int kw = font.width(k);
            if (kw >= tw) {
                break;
            }
            text("hero", k, tx, ly, t.faint());
            String v = fit(kv[1], tw - kw);
            if (!v.isEmpty()) {
                text("hero", v, tx + kw, ly, kv.length > 2 ? Integer.parseUnsignedInt(kv[2], 16) | 0xFF000000 : t.text());
            }
            ly += 10;
        }
        if (slot >= 0) {
            addHotspot(new Hotspot(x, y, 38, 38, slot, stack.copy(), null, name.getString()));
            hotspot(String.valueOf(slot), x, y, 38, 38, name.getString());
        }
        if (hover) {
            hoverStack = stack;
        }
        return y + hgt;
    }

    /** A market panel for one id: lowest BIN, counts, sparkline of hourly lowest BIN, recent sales. Returns its bottom. */
    public int marketPanel(int x, int y, int cw, int maxH, String skyblockId) {
        if (skyblockId == null || skyblockId.isEmpty() || maxH < 30) {
            return y;
        }
        AhMarket.Stats st = AhMarket.stats(skyblockId);
        List<AhMarket.Point> hist = AhMarket.binHistory(skyblockId);
        List<AhMarket.Point> sales = AhMarket.sales(skyblockId);
        if (st == null && hist.isEmpty() && sales.isEmpty()) {
            return y;
        }
        boolean spark0 = hist.size() >= 2 && cw >= 220;
        int hgt = Math.min(maxH, !sales.isEmpty() || spark0 ? 30 : 16);
        region("market", x, y, cw, hgt);
        g.fill(x, y, x + cw, y + hgt, t.surfaceAlt());
        g.outline(x, y, cw, hgt, t.border());
        text("market", "MARKET", x + 5, y + 4, t.faint());
        StringBuilder line = new StringBuilder();
        if (st != null && st.lowestBin() > 0) {
            line.append("Lowest BIN ").append(coins(st.lowestBin())).append(" · ").append(st.binCount()).append(" BIN");
            if (st.auctionCount() > 0) {
                line.append(", ").append(st.auctionCount()).append(" auction").append(st.auctionCount() == 1 ? "" : "s");
            }
        } else if (st != null) {
            line.append(st.auctionCount()).append(" auction").append(st.auctionCount() == 1 ? "" : "s").append(", no BIN");
        }
        int textRight = x + cw - 6;
        int sparkW = Math.min(110, cw / 3);
        boolean spark = hist.size() >= 2 && cw >= 220;
        if (spark) {
            textRight -= sparkW + 8;
        }
        int lx = x + 5 + font.width("MARKET") + 8;
        text("market", fit(line.toString(), textRight - lx), lx, y + 4, t.gold());
        if (!sales.isEmpty() && hgt >= 26) {
            long sum = 0;
            for (AhMarket.Point p : sales) {
                sum += p.price();
            }
            String s = "Recent sales: avg " + coins(sum / sales.size()) + " (" + sales.size() + ") · last "
                    + coins(sales.get(sales.size() - 1).price());
            text("market", fit(s, textRight - x - 10), x + 5, y + 16, t.dim());
        }
        if (spark) {
            sparkline(x + cw - 6 - sparkW, y + 5, sparkW, hgt - 10, hist);
        }
        return y + hgt;
    }

    private void sparkline(int x, int y, int sw, int sh, List<AhMarket.Point> pts) {
        region("spark", x, y, sw, sh);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (AhMarket.Point p : pts) {
            min = Math.min(min, p.price());
            max = Math.max(max, p.price());
        }
        long span = Math.max(1, max - min);
        int n = pts.size();
        int barW = Math.max(1, sw / Math.max(1, n));
        for (int i = 0; i < n; i++) {
            int bh = 2 + (int) ((sh - 2) * (pts.get(i).price() - min) / span);
            int bx = x + i * barW;
            g.fill(bx, y + sh - bh, bx + Math.max(1, barW - 1), y + sh, i == n - 1 ? t.accentBright() : t.borderStrong());
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    public boolean in(int x, int y, int bw, int bh) {
        return mx >= x && mx < x + bw && my >= y && my < y + bh && my >= clipY0 && my < clipY1;
    }

    /** Starts a scrolling area: drawing is scissored to it and only what lies wholly inside it is recorded. */
    public void beginClip(int x, int y0, int cw, int y1) {
        clipY0 = y0;
        clipY1 = y1;
        g.enableScissor(x, y0, x + cw, y1);
    }

    public void endClip() {
        g.disableScissor();
        clipY0 = Integer.MIN_VALUE;
        clipY1 = Integer.MAX_VALUE;
    }

    private boolean inClip(int y, int hgt) {
        return y >= clipY0 && y + hgt <= clipY1;
    }

    /** Adds a hotspot cut to the clip (dropped when nothing of it is visible). */
    public void addHotspot(Hotspot hs) {
        int y0 = Math.max(hs.y(), clipY0);
        int y1 = Math.min(hs.y() + hs.h(), clipY1);
        if (y1 <= y0) {
            return;
        }
        hotspots.add(y0 == hs.y() && y1 == hs.y() + hs.h() ? hs
                : new Hotspot(hs.x(), y0, hs.w(), y1 - y0, hs.slot(), hs.stack(), hs.action(), hs.label()));
    }

    /** A pill with a label (no hotspot of its own). */
    public void pill(int x, int y, int bw, int bh, boolean on, String label, int color, String region) {
        boolean hover = in(x, y, bw, bh);
        g.fill(x, y, x + bw, y + bh, on ? t.selected() : hover ? t.hover() : t.surfaceAlt());
        g.outline(x, y, bw, bh, hover ? t.accent() : t.border());
        text(region, label, x + 5, y + (bh - 8) / 2, hover ? t.text() : color);
    }

    public void text(String region, String s, int x, int y, int color) {
        if (s == null || s.isEmpty()) {
            return;
        }
        g.text(font, s, x, y, color, false);
        if (!inClip(y, 8)) {
            return;
        }
        layout.add("text " + region + " " + x + " " + y + " " + font.width(s) + " 8 " + s);
    }

    public void plain(boolean record, String region, String s, int x, int y, int color) {
        if (record) {
            text(region, s, x, y, color);
        } else if (s != null && !s.isEmpty()) {
            g.text(font, s, x, y, color, false);
        }
    }

    public void componentText(boolean record, String region, FormattedCharSequence seq, int x, int y) {
        g.text(font, seq, x, y, t.cardText(), false);
        if (record && inClip(y, 8)) {
            int sw = font.width(seq);
            if (sw > 0) {
                layout.add("text " + region + " " + x + " " + y + " " + sw + " 8 " + plainOf(seq));
            }
        }
    }

    public void region(String name, int x, int y, int rw, int rh) {
        if (!inClip(y, rh)) {
            return;
        }
        layout.add("region " + name + " " + x + " " + y + " " + rw + " " + rh);
    }

    public void hotspot(String id, int x, int y, int hw, int hh, String label) {
        int y0 = Math.max(y, clipY0);
        int y1 = Math.min(y + hh, clipY1);
        if (y1 <= y0) {
            return;
        }
        y = y0;
        hh = y1 - y0;
        layout.add("hotspot " + id + " " + x + " " + y + " " + hw + " " + hh + " " + label);
    }

    public static String plainOf(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    public FormattedCharSequence fitComponent(Component c, int width) {
        if (width <= 0) {
            return FormattedCharSequence.EMPTY;
        }
        if (font.width(c) <= width) {
            return c.getVisualOrderText();
        }
        List<FormattedCharSequence> lines = font.split(c, Math.max(1, width - font.width("...")));
        if (lines.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        }
        return FormattedCharSequence.composite(lines.get(0), FormattedCharSequence.forward("...", net.minecraft.network.chat.Style.EMPTY));
    }

    public String fit(String s, int width) {
        if (s == null || width <= 0) {
            return "";
        }
        if (font.width(s) <= width) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, width - font.width("...")));
        return cut.isEmpty() ? "" : cut + "...";
    }

    /** The END of a string that fits (a search field shows what was typed last). */
    public String fitTail(String s, int width) {
        if (s == null || width <= 0) {
            return "";
        }
        if (font.width(s) <= width) {
            return s;
        }
        int i = s.length();
        while (i > 0 && font.width(s.substring(i - 1)) <= width) {
            i--;
        }
        return s.substring(i);
    }

    /** "1.23M", "45.6k", "999" - compact coins. */
    public static String coins(long c) {
        if (c < 0) {
            return "-";
        }
        if (c >= 1_000_000_000L) {
            return trim(c / 1_000_000_000.0) + "B";
        }
        if (c >= 1_000_000L) {
            return trim(c / 1_000_000.0) + "M";
        }
        if (c >= 10_000L) {
            return trim(c / 1_000.0) + "k";
        }
        return String.format(Locale.US, "%,d", c);
    }

    private static String trim(double v) {
        String s = v >= 100 ? String.format(Locale.US, "%.0f", v) : v >= 10 ? String.format(Locale.US, "%.1f", v)
                : String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /** "2d 4h", "3h 12m", "5m 9s" from milliseconds. */
    public static String timeLeft(long ms) {
        if (ms <= 0) {
            return "Ended";
        }
        long s = ms / 1000;
        long d = s / 86400, hh = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) {
            return d + "d " + hh + "h";
        }
        if (hh > 0) {
            return hh + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + sec + "s";
        }
        return sec + "s";
    }

    /** ARGB of a Hypixel tier name ("LEGENDARY"), or {@code fallback}. */
    public static int tierColor(String tier, int fallback) {
        if (tier == null || tier.isEmpty()) {
            return fallback;
        }
        String code = SkyblockItemStackFactory.tierColorCode(tier.toUpperCase(Locale.ROOT).replace(' ', '_'));
        if (code == null || code.length() < 2) {
            return fallback;
        }
        ChatFormatting cf = ChatFormatting.getByCode(code.charAt(1));
        Integer rgb = cf == null ? null : ChatColors.color(cf);
        return rgb == null ? fallback : 0xFF000000 | rgb;
    }

    /** An icon for a SkyBlock id: the item catalog's, else the shared pre-pack look on a paper, else paper. Cached. */
    public static ItemStack iconFor(String skyblockId) {
        if (skyblockId == null || skyblockId.isEmpty()) {
            return new ItemStack(Items.PAPER);
        }
        synchronized (ICONS) {
            ItemStack cached = ICONS.get(skyblockId);
            if (cached != null) {
                return cached;
            }
        }
        ItemStack out;
        try {
            SkyblockItemEntry entry = SkyblockItemRepository.findById(skyblockId);
            out = entry != null ? SkyblockItemStackFactory.build(entry) : new ItemStack(Items.PAPER);
            if (entry == null) {
                ItemLooks.applyLook(out, skyblockId);
            }
        } catch (Exception e) {
            out = new ItemStack(Items.PAPER);
        }
        synchronized (ICONS) {
            if (ICONS.size() > 512) {
                ICONS.clear();
            }
            ICONS.put(skyblockId, out);
        }
        return out;
    }
}
