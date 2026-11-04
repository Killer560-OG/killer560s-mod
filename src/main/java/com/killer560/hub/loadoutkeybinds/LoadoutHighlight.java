package com.killer560.hub.loadoutkeybinds;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlight Selected Loadout (killer560, 2026-11-03: "also in loadout if one is selected have it highlighted"): in Hypixel's
 * "(N/M) Loadouts" menu the loadout he has on gets a green slot background and border, behind the item (drawn from
 * {@code miningqol/mixin/CommissionMenuSlotMixin}). Draw-only: no click, no packet.
 *
 * <p>Which loadout is on: a loadout slot whose lore says so ("Selected", "Equipped", "Currently selected", "Click to
 * unequip"... a whole line, never "Click to equip"); when no slot's lore says it, the last "You equipped Loadout N!" line
 * Hypixel sent this session (his chat, 2026-11-02), placed on its page: twelve loadouts a page in
 * {@link LoadoutKeybindsFeature#LOADOUT_SLOTS} order. A RENAMED loadout is announced by its name ("You equipped Dwarven
 * Coms!", 2026-11-04): that name is matched to the slot whose item name equals it, else nothing is highlighted. The chat
 * lines are anchored, so a player's message cannot set them.
 */
public final class LoadoutHighlight {

    // Owner 2026-11-03: the old 0x60 fill under the item was "very dim". A strong fill, and a 2 px border on the slot's
    // edge (x-1..x+17), outside the 16 px the item icon covers.
    private static final int FILL = 0xB055FF55;
    private static final int BORDER = 0xFF55FF55;
    /** Hypixel's line; anchored with a bounded number, so "[VIP] Bob: You equipped Loadout 3!" never matches. */
    private static final Pattern EQUIPPED = Pattern.compile("^You equipped Loadout (\\d{1,2})!$");
    /** A renamed loadout's line: "You equipped Dwarven Coms!" (anchored and bounded like the numbered one). */
    private static final Pattern EQUIPPED_NAMED = Pattern.compile("^You equipped (.{1,32})!$");
    private static final Logger LOGGER = ModLog.get("killer560smod-loadoutkeybinds");
    private static final Set<String> SELECTED_LINES = Set.of("selected", "equipped", "active", "currently selected",
            "currently equipped", "currently active", "this loadout is selected", "this loadout is equipped",
            "this loadout is active", "you are using this loadout");

    /** The loadout number Hypixel last said he equipped, or 0. */
    private static volatile int lastEquipped = 0;
    /** The name Hypixel last said he equipped (a renamed loadout), or null; excludes {@link #lastEquipped}. */
    private static volatile String lastName = null;
    /** Bumped by every equip line, so the slot cache knows to look again. */
    private static volatile int equipVersion = 0;
    private static boolean loreLogged;

    private static Object cachedScreen;
    private static ItemStack[] cachedStacks = new ItemStack[0];
    private static int cachedSlot = -1;
    private static int cachedFor = -1;

    private LoadoutHighlight() {
    }

    static void register() {
        ChatObserver.subscribe(message -> {
            String plain = ChatObserver.strip(message).trim();
            Matcher m = EQUIPPED.matcher(plain);
            if (m.matches()) {
                setEquipped(Integer.parseInt(m.group(1)), null);
            } else if ((m = EQUIPPED_NAMED.matcher(plain)).matches()) {
                setEquipped(0, m.group(1).trim());
            }
        });
    }

    private static void setEquipped(int number, String name) {
        lastEquipped = number;
        lastName = name;
        equipVersion++;
    }

    /** Whether lore lines (codes stripped) mark this loadout as the one on (also the testkit's hook). */
    public static boolean selected(List<String> lore) {
        for (String raw : lore) {
            String l = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[!.]+$", "").trim();
            if (l.contains("click to equip")) {
                continue;
            }
            if (SELECTED_LINES.contains(l) || l.contains("click to unequip")) {
                return true;
            }
        }
        return false;
    }

    /** The menu slot index of the loadout that is on in {@code screen}, or -1. */
    public static int selectedSlot(AbstractContainerScreen<?> screen) {
        LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHighlightSelected()) {
            return -1;
        }
        Matcher title = LoadoutKeybindsFeature.LOADOUT_TITLE.matcher(screen.getTitle().getString());
        if (!title.find()) {
            return -1;
        }
        var slots = screen.getMenu().slots;
        boolean changed = cachedScreen != screen || cachedFor != equipVersion;
        if (cachedStacks.length != LoadoutKeybindsFeature.LOADOUT_SLOTS.length) {
            cachedStacks = new ItemStack[LoadoutKeybindsFeature.LOADOUT_SLOTS.length];
        }
        for (int i = 0; i < LoadoutKeybindsFeature.LOADOUT_SLOTS.length && !changed; i++) {
            int s = LoadoutKeybindsFeature.LOADOUT_SLOTS[i];
            if (s < slots.size() && slots.get(s).getItem() != cachedStacks[i]) {
                changed = true;
            }
        }
        if (!changed) {
            return cachedSlot;
        }
        cachedScreen = screen;
        cachedFor = equipVersion;
        int found = -1;
        for (int i = 0; i < LoadoutKeybindsFeature.LOADOUT_SLOTS.length; i++) {
            int s = LoadoutKeybindsFeature.LOADOUT_SLOTS[i];
            ItemStack stack = s < slots.size() ? slots.get(s).getItem() : ItemStack.EMPTY;
            cachedStacks[i] = stack;
            if (found < 0 && !stack.isEmpty()) {
                List<String> lore = DungeonChestValuer.cleanLore(stack);
                if (!loreLogged) {
                    loreLogged = true;
                    LOGGER.info("[LoadoutHighlight] first loadout slot lore read: slot {} \"{}\" {}", s,
                            DungeonChestValuer.strip(stack.getHoverName().getString()), lore);
                }
                if (selected(lore)) {
                    found = s;
                }
            }
        }
        String name = lastName;
        if (found < 0 && name != null) {
            for (int s : LoadoutKeybindsFeature.LOADOUT_SLOTS) {
                if (s < slots.size() && name.equals(DungeonChestValuer.strip(slots.get(s).getItem().getHoverName().getString()).trim())) {
                    found = s;
                    break;
                }
            }
        } else if (found < 0 && lastEquipped > 0) {
            int page = Integer.parseInt(title.group(1));
            int i = lastEquipped - 1 - (page - 1) * LoadoutKeybindsFeature.LOADOUT_SLOTS.length;
            if (i >= 0 && i < LoadoutKeybindsFeature.LOADOUT_SLOTS.length) {
                int s = LoadoutKeybindsFeature.LOADOUT_SLOTS[i];
                if (s < slots.size() && !slots.get(s).getItem().isEmpty()) {
                    found = s;
                }
            }
        }
        cachedSlot = found;
        return found;
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
            return;
        }
        int s = selectedSlot(screen);
        if (s >= 0 && slot.index == s) {
            int x = slot.x;
            int y = slot.y;
            graphics.fill(x, y, x + 16, y + 16, FILL);
            graphics.fill(x - 1, y - 1, x + 17, y + 1, BORDER);
            graphics.fill(x - 1, y + 15, x + 17, y + 17, BORDER);
            graphics.fill(x - 1, y + 1, x + 1, y + 15, BORDER);
            graphics.fill(x + 15, y + 1, x + 17, y + 15, BORDER);
            highlightDraws++;
        }
    }

    private static long highlightDraws = 0;

    public static long highlightDraws() {
        return highlightDraws;
    }

    public static int lastEquippedLoadout() {
        return lastEquipped;
    }

    public static void setLastEquippedForTest(int n) {
        setEquipped(n, null);
    }

    /** Testkit: as if Hypixel said "You equipped {name}!". */
    public static void setLastEquippedNameForTest(String name) {
        setEquipped(0, name);
    }
}
