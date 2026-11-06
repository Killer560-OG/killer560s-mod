package com.killer560.hub.invsort;

import com.killer560.hub.autopuzzles.AutoPuzzleUtil;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.ItemCell;
import com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Hypixel Loadout picker (killer560, 2026-11-04: "make the loadout selection in invsort open the loadout menu and have you
 * click an option"; 2026-11-05: "the loadout thing doesnt show the actual loadout"): a copy of a /loadouts page as
 * {@link InvSortLoadoutCache} last saw it (the same items, Hypixel's tooltips on hover, each loadout's number on its
 * slot), plus a None button. A click on a loadout sets the layout's loadout, saves it and returns to the layouts menu.
 * The page's own Next Page / Previous Page arrows turn the page (loadout1-1105: "the next page button their doesnt work");
 * its Close barrier is left out ("remove the close barrier block button"), and the loadout Hypixel shows without "Left-click
 * to equip!" is the equipped one and gets a green mark. A page not seen yet is 12 number buttons, a hint and "<" / ">".
 * Nothing is sent to the server.
 */
public final class InvSortLoadoutPick extends Screen implements MenuThemedScreen {

    private static final int COLS = 6;
    private static final int CELL = 28;
    private static final int PAD = 12;
    private static final int ICON = 24;
    private static final int PANEL_W = 9 * ICON + 2 * PAD;
    private static final int PANEL_H = ScreenChrome.HEADER_H + 4 + 6 * ICON + 28 + 2 * PAD;
    private static final int PER_PAGE = 12;
    private static final int CLOSE_SLOT = 49;
    private static final int NAV_W = 20;
    /** {@link #choiceAt} answers for the page arrows. */
    public static final int NEXT = -2;
    public static final int PREVIOUS = -3;
    private static final int EQUIPPED = 0xFF55FF55;

    private final InventorySorterScreen parent;
    private final int current;
    private int page;
    private int panelX;
    private int panelY;
    /** The page the equipped slot was worked out for (by identity) and that slot, so lore is read once per page. */
    private List<ItemStack> markedFor;
    private int equippedSlot = -1;

    InvSortLoadoutPick(InventorySorterScreen parent, int current) {
        super(Component.literal("Hypixel Loadout"));
        this.parent = parent;
        this.current = current;
        this.page = current > 0 ? (current - 1) / PER_PAGE + 1 : 1;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
    }

    /** The /loadouts page shown now (1-based). Public for tests. */
    public int page() {
        return page;
    }

    private int first() {
        return (page - 1) * PER_PAGE;
    }

    private int slotX(int i) {
        return panelX + PAD + (i % COLS) * CELL + (PANEL_W - 2 * PAD - COLS * CELL) / 2;
    }

    private int slotY(int i) {
        return panelY + ScreenChrome.HEADER_H + 4 + PAD + (i / COLS) * CELL;
    }

    private int noneY() {
        return panelY + PANEL_H - PAD - 18;
    }

    private int cellX(int s) {
        return panelX + PAD + (s % 9) * ICON;
    }

    private int cellY(int s) {
        return panelY + ScreenChrome.HEADER_H + 4 + PAD + (s / 9) * ICON;
    }

    /** The page arrows beside None, shown only while the page is not cached (a cached page draws its own). */
    private boolean navPrevious() {
        return page > 1;
    }

    private boolean navNext() {
        return page < InvSortLoadoutCache.pages();
    }

    private int noneX() {
        return panelX + PAD + (navPrevious() ? NAV_W + 2 : 0);
    }

    private int noneW() {
        return PANEL_W - 2 * PAD - (navPrevious() ? NAV_W + 2 : 0) - (navNext() ? NAV_W + 2 : 0);
    }

    /** The cached page's slot under the point, or -1. */
    private int cellAt(double mx, double my, List<ItemStack> cached) {
        int cx = (int) Math.floor((mx - (panelX + PAD)) / ICON);
        int cy = (int) Math.floor((my - (panelY + ScreenChrome.HEADER_H + 4 + PAD)) / ICON);
        int s = cy * 9 + cx;
        return cx >= 0 && cx < 9 && cy >= 0 && s < Math.min(54, cached.size()) ? s : -1;
    }

    /** The choice under the point: a loadout number, 0 for None, {@link #NEXT} / {@link #PREVIOUS}, -1 for nothing. */
    private int choiceAt(double mx, double my) {
        List<ItemStack> cached = InvSortLoadoutCache.get(page);
        boolean inNone = my >= noneY() && my < noneY() + 18;
        if (cached != null) {
            int cell = cellAt(mx, my, cached);
            if (cell == LoadoutKeybindsFeature.NEXT_PAGE_SLOT && !cached.get(cell).isEmpty()) {
                return NEXT;
            }
            if (cell == LoadoutKeybindsFeature.PREVIOUS_PAGE_SLOT && !cached.get(cell).isEmpty()) {
                return PREVIOUS;
            }
            int n = cell < 0 ? 0 : LoadoutKeybindsFeature.loadoutAt(cell);
            if (n > 0) {
                return first() + n;
            }
            return inNone && mx >= panelX + PAD && mx < panelX + PANEL_W - PAD ? 0 : -1;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            if (mx >= slotX(i) && mx < slotX(i) + CELL - 2 && my >= slotY(i) && my < slotY(i) + CELL - 2) {
                return first() + i + 1;
            }
        }
        if (inNone && navPrevious() && mx >= panelX + PAD && mx < panelX + PAD + NAV_W) {
            return PREVIOUS;
        }
        if (inNone && navNext() && mx >= panelX + PANEL_W - PAD - NAV_W && mx < panelX + PANEL_W - PAD) {
            return NEXT;
        }
        return inNone && mx >= noneX() && mx < noneX() + noneW() ? 0 : -1;
    }

    /** Test hook: the screen point in the middle of choice {@code n} as drawn now (a loadout on the shown page, 0 for None,
     *  {@link #NEXT} / {@link #PREVIOUS} for the page arrows). */
    public int[] centerOf(int n) {
        List<ItemStack> cached = InvSortLoadoutCache.get(page);
        if (n == 0) {
            return new int[]{cached != null ? panelX + PANEL_W / 2 : noneX() + noneW() / 2, noneY() + 9};
        }
        if (n == NEXT || n == PREVIOUS) {
            if (cached != null) {
                int s = n == NEXT ? LoadoutKeybindsFeature.NEXT_PAGE_SLOT : LoadoutKeybindsFeature.PREVIOUS_PAGE_SLOT;
                return new int[]{cellX(s) + ICON / 2, cellY(s) + ICON / 2};
            }
            return new int[]{n == NEXT ? panelX + PANEL_W - PAD - NAV_W / 2 : panelX + PAD + NAV_W / 2, noneY() + 9};
        }
        int i = (n - 1) % PER_PAGE;
        if (cached == null) {
            return new int[]{slotX(i) + CELL / 2 - 1, slotY(i) + CELL / 2 - 1};
        }
        int s = LoadoutKeybindsFeature.slotOf(i + 1);
        return new int[]{cellX(s) + ICON / 2, cellY(s) + ICON / 2};
    }

    /** Test hook: the menu slot of the loadout marked equipped on the shown page, or -1. */
    public int equippedSlot() {
        List<ItemStack> cached = InvSortLoadoutCache.get(page);
        if (cached != markedFor) {
            markedFor = cached;
            equippedSlot = -1;
            for (int i = 1; cached != null && i <= PER_PAGE; i++) {
                ItemStack s = cached.get(LoadoutKeybindsFeature.slotOf(i));
                // Hypixel offers "Left-click to equip!" on every loadout but the one he wears; both say "Right-click to edit".
                if (AutoPuzzleUtil.loreContains(s, "Right-click to edit") && !AutoPuzzleUtil.loreContains(s, "Left-click to equip")) {
                    equippedSlot = LoadoutKeybindsFeature.slotOf(i);
                }
            }
        }
        return equippedSlot;
    }

    /** Sets the loadout and goes back to the layouts menu. Public for tests. */
    public void choose(int loadout) {
        parent.setLoadout(loadout);
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int c = McInput.button(event) == 0 ? choiceAt(event.x(), event.y()) : -1;
        if (c == NEXT || c == PREVIOUS) {
            page += c == NEXT ? 1 : -1;
            return true;
        }
        if (c >= 0) {
            choose(c);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, width, height);
        ScreenChrome.frame(g, panelX, panelY, PANEL_W, PANEL_H);
        ScreenChrome.header(g, panelX, panelY, PANEL_W, "Hypixel Loadout");
        ScreenChrome.headerRight(g, panelX, panelY, PANEL_W, "click a loadout", ScreenChrome.readable(0xFFAAAAAA));
        int hover = choiceAt(mouseX, mouseY);
        List<ItemStack> cached = InvSortLoadoutCache.get(page);
        if (cached == null) {
            for (int i = 0; i < PER_PAGE; i++) {
                int n = first() + i + 1;
                ScreenChrome.slot(g, slotX(i), slotY(i), CELL - 2, current == n, hover == n);
                ScreenChrome.centered(g, String.valueOf(n), slotX(i) + (CELL - 2) / 2, slotY(i) + (CELL - 2) / 2 - 4,
                        ScreenChrome.readable(0xFFFFFFFF));
            }
            ScreenChrome.centered(g, page == 1 ? "Open /loadouts once to see your loadouts here"
                    : "Open /loadouts page " + page + " once to see it here", panelX + PANEL_W / 2, noneY() - 12,
                    ScreenChrome.readable(0xFFAAAAAA));
            if (navPrevious()) {
                ScreenChrome.button(g, panelX + PAD, noneY(), NAV_W, 18, "<", hover == PREVIOUS, false);
            }
            if (navNext()) {
                ScreenChrome.button(g, panelX + PANEL_W - PAD - NAV_W, noneY(), NAV_W, 18, ">", hover == NEXT, false);
            }
            ScreenChrome.button(g, noneX(), noneY(), noneW(), 18, "None", hover == 0, current == 0);
        } else {
            int equipped = equippedSlot();
            ItemStack tip = ItemStack.EMPTY;
            for (int s = 0; s < Math.min(54, cached.size()); s++) {
                if (s == CLOSE_SLOT) {
                    ScreenChrome.slot(g, cellX(s), cellY(s), ICON - 1, false, false);
                    continue;
                }
                int k = LoadoutKeybindsFeature.loadoutAt(s);
                int n = k > 0 ? first() + k : 0;
                boolean arrow = (s == LoadoutKeybindsFeature.NEXT_PAGE_SLOT && hover == NEXT)
                        || (s == LoadoutKeybindsFeature.PREVIOUS_PAGE_SLOT && hover == PREVIOUS);
                ScreenChrome.slot(g, cellX(s), cellY(s), ICON - 1, n > 0 && n == current, (n > 0 && n == hover) || arrow);
                ItemCell.draw(g, font, cached.get(s), cellX(s) + 1, cellY(s) + 1, ICON - 3);
                if (s == equipped) {
                    g.fill(cellX(s) + 1, cellY(s) + ICON - 3, cellX(s) + ICON - 2, cellY(s) + ICON - 2, EQUIPPED);
                }
                if (n > 0) {
                    ScreenChrome.text(g, String.valueOf(n), cellX(s) + 3, cellY(s) + 3,
                            ScreenChrome.readable(s == equipped ? EQUIPPED : 0xFFFFFFFF));
                }
                if (mouseX >= cellX(s) && mouseX < cellX(s) + ICON && mouseY >= cellY(s) && mouseY < cellY(s) + ICON) {
                    tip = cached.get(s);
                }
            }
            if (!tip.isEmpty()) {
                g.setTooltipForNextFrame(font, Screen.getTooltipFromItem(minecraft, tip), Optional.empty(), mouseX, mouseY);
            }
            ScreenChrome.button(g, panelX + PAD, noneY(), PANEL_W - 2 * PAD, 18, "None", hover == 0, current == 0);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, PANEL_W, PANEL_H};
    }
}
