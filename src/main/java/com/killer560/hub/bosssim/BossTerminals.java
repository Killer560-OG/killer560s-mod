package com.killer560.hub.bosssim;

import com.killer560.hub.util.ModLog;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * F7's six terminals, served by the sim's server the way Hypixel serves them, so the mod's Terminal Solver, Auto Terminal,
 * Terminal Aura and Hover Terminal treat them as the real thing: the same titles (TerminalType's regexes), the same window
 * sizes and slot layouts (ADM's measurements of Hypixel's: Click in order 4 rows, slots 11-15 and 20-24, ten numbers since
 * Hypixel's 2026-10-06 update; Panes 5 rows, 11-15/20-24/29-33; Rubix 5 rows, the middle 3x3; Starts With 5 rows,
 * 10-16/19-25/28-34; Select 6 rows, 10-16 to 37-43; Melody 5 rows, three lanes since the update, Lock buttons in column 7),
 * a nameless black pane as filler, and a correct Starts With / Select pick glinting. A click never moves an item: it is
 * judged, the menu re-sent, and the finishing click closes the window on the next tick.
 *
 * <p>Rules ported from the testkit's server terminals ({@code hx/menu/HxTerminals}, built from the mod's own practice
 * screen and solver); layouts from ADM's measurements (facts, not code).
 */
public final class BossTerminals {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    public enum Type { NUMBERS, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }

    private static final String[] RUBIX = {"orange", "yellow", "green", "blue", "red"};
    /** Title color word, item name and wool (the solver's SELECT_PREFIXES know these names). */
    private static final String[][] SELECT = {
            {"RED", "Rose Red", "red_wool"}, {"GREEN", "Cactus Green", "green_wool"}, {"BLUE", "Lapis Lazuli", "blue_wool"},
            {"BROWN", "Cocoa Beans", "brown_wool"}, {"WHITE", "Bone Meal", "white_wool"}, {"BLACK", "Ink Sack", "black_wool"},
            {"YELLOW", "Dandelion Yellow", "yellow_wool"}, {"SILVER", "Silver Wool", "light_gray_wool"}};
    private static final String[][] STARTS_WITH_POOL = {
            {"Stick", "stick"}, {"String", "string"}, {"Saddle", "saddle"}, {"Snowball", "snowball"}, {"Sponge", "sponge"},
            {"Slimeball", "slime_ball"}, {"Shears", "shears"}, {"Sugar", "sugar"}, {"Feather", "feather"},
            {"Flint", "flint"}, {"Egg", "egg"}, {"Emerald", "emerald"}, {"Torch", "torch"}, {"Ladder", "ladder"},
            {"Bucket", "bucket"}, {"Bread", "bread"}, {"Bone", "bone"}, {"Coal", "coal"}, {"Compass", "compass"},
            {"Clock", "clock"}, {"Diamond", "diamond"}, {"Redstone", "redstone"}, {"Paper", "paper"},
            {"Pumpkin", "pumpkin"}, {"Melon", "melon_slice"}, {"Apple", "apple"}, {"Arrow", "arrow"},
            {"Gunpowder", "gunpowder"}, {"Book", "book"}, {"Wheat", "wheat"}, {"Obsidian", "obsidian"},
            {"Bowl", "bowl"}, {"Brick", "brick"}, {"Carrot", "carrot"}, {"Potato", "potato"}};
    private static final String[] LETTERS = {"S", "B", "C", "P", "A", "D", "E", "F", "M", "R", "T", "W", "O", "G", "L"};

    private static final Random RNG = new Random();
    private static int opened;
    private static int solved;
    /** A terminal keeps its type across reopens (every open still deals a fresh puzzle), as on Hypixel. */
    private static final java.util.Map<BossStations.Station, Type> TYPES = new java.util.HashMap<>();

    /** Forgets every terminal's type (a new fight). */
    static void resetTypes() {
        TYPES.clear();
    }

    /** Picks from {@code weights} (index = value - {@code first}). */
    private static int weighted(int first, int... weights) {
        int sum = 0;
        for (int w : weights) {
            sum += w;
        }
        int r = RNG.nextInt(sum);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return first + i;
            }
        }
        return first + weights.length - 1;
    }

    private BossTerminals() {
    }

    /** How many terminals were opened / solved since the game started (the testkit reads them). */
    public static int openedCount() {
        return opened;
    }

    public static int solvedCount() {
        return solved;
    }

    static final class Term {
        final Type type;
        final BossStations.Station station;
        final int rows;
        final int[] grid;
        final String title;
        String letter;
        String[] color;
        int melodyRow;
        int melodyLime = 1;
        int melodyDir = 1;
        int melodyTarget = 1 + RNG.nextInt(5);
        int melodyLastStep;
        int melodyFrozen;
        int clicks;
        int limited;
        final java.util.ArrayDeque<Integer> clickTicks = new java.util.ArrayDeque<>();

        Term(Type type, BossStations.Station station, int rows, int[] grid, String title) {
            this.type = type;
            this.station = station;
            this.rows = rows;
            this.grid = grid;
            this.title = title;
        }

        boolean inGrid(int slot) {
            for (int g : grid) {
                if (g == slot) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The window: a chest whose items can never be taken. */
    static final class Menu extends ChestMenu {
        final Term term;
        final SimpleContainer items;
        final ServerPlayer owner;

        Menu(int id, Inventory inv, Term term, SimpleContainer items, ServerPlayer owner) {
            super(typeFor(term.rows), id, inv, items, term.rows);
            this.term = term;
            this.items = items;
            this.owner = owner;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player instanceof ServerPlayer sp) {
                onClick(this, sp, slot, button);
            }
            setCarried(ItemStack.EMPTY);
            broadcastFullState();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    static ItemStack stack(String id, int count) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
        return new ItemStack(item, count);
    }

    static ItemStack named(String id, String name) {
        ItemStack s = stack(id, 1);
        s.set(DataComponents.CUSTOM_NAME, BossChat.legacy("§a" + name));
        return s;
    }

    static ItemStack pane(String color, int count) {
        ItemStack s = stack(color + "_stained_glass_pane", count);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(""));
        return s;
    }

    static ItemStack filler() {
        return pane("black", 1);
    }

    static String id(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static int[] slots(int... ranges) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ranges.length; i += 2) {
            for (int s = ranges[i]; s <= ranges[i + 1]; s++) {
                out.add(s);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A right-click on a terminal's stand: a fresh puzzle of a random type, as every open on Hypixel is. */
    static void open(ServerPlayer player, BossStations.Station station) {
        // One player per terminal (coopsim-1109). Hypixel's rule for a terminal someone else has open is on record
        // nowhere here; the sim refuses the second opener rather than deal two puzzles on one terminal. The line is
        // ours, in Hypixel's red refusal style, not a captured Hypixel line.
        ServerPlayer user = userOf(player.level().getServer(), station);
        if (user != null && user != player) {
            player.sendSystemMessage(BossChat.legacy("§cThis Terminal is being used by someone else!"));
            return;
        }
        Type type = TYPES.computeIfAbsent(station, k -> Type.values()[RNG.nextInt(Type.values().length)]);
        String forced = System.getProperty("killer560.bosssim.terminal");
        if (forced != null && !forced.isBlank()) {
            try {
                type = Type.valueOf(forced.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // an unknown name keeps the random draw
            }
        }
        open(player, station, type);
    }

    static void open(ServerPlayer player, BossStations.Station station, Type type) {
        Term t;
        List<ItemStack> items = new ArrayList<>();
        switch (type) {
            case NUMBERS -> {
                t = new Term(type, station, 4, slots(11, 15, 20, 24), "Click in order!");
                fill(items, t.rows);
                List<Integer> order = shuffled(t.grid.length);
                for (int i = 0; i < t.grid.length; i++) {
                    items.set(t.grid[i], pane("red", order.get(i) + 1));
                }
            }
            case PANES -> {
                t = new Term(type, station, 5, slots(11, 15, 20, 24, 29, 33), "Correct all the panes!");
                fill(items, t.rows);
                int on = weighted(0, 8, 24, 36, 44, 40, 16, 5, 4, 3, 1);
                List<Integer> idx = shuffled(t.grid.length);
                for (int i = 0; i < t.grid.length; i++) {
                    items.set(t.grid[i], pane(idx.get(i) < on ? "lime" : "red", 1));
                }
            }
            case RUBIX -> {
                t = new Term(type, station, 5, new int[]{12, 13, 14, 21, 22, 23, 30, 31, 32}, "Change all to same color!");
                fill(items, t.rows);
                do {
                    for (int g : t.grid) {
                        items.set(g, pane(RUBIX[RNG.nextInt(5)], 1));
                    }
                } while (rubixSolved(items, t));
            }
            case STARTS_WITH -> {
                String letter = LETTERS[RNG.nextInt(LETTERS.length)];
                t = new Term(type, station, 5, slots(10, 16, 19, 25, 28, 34), "What starts with: '" + letter + "'?");
                t.letter = letter;
                fill(items, t.rows);
                List<String[]> match = new ArrayList<>();
                List<String[]> other = new ArrayList<>();
                for (String[] e : STARTS_WITH_POOL) {
                    (e[0].toUpperCase(Locale.ROOT).startsWith(letter) ? match : other).add(e);
                }
                Collections.shuffle(match, RNG);
                Collections.shuffle(other, RNG);
                int want = Math.max(1, Math.min(match.size(), weighted(2, 1, 5, 14, 21, 32, 43, 27, 20, 16, 3, 4)));
                List<String[]> chosen = new ArrayList<>(match.subList(0, want));
                for (int i = 0; chosen.size() < t.grid.length; i++) {
                    chosen.add(other.get(i % other.size()));
                }
                Collections.shuffle(chosen, RNG);
                for (int i = 0; i < t.grid.length; i++) {
                    items.set(t.grid[i], named(chosen.get(i)[1], chosen.get(i)[0]));
                }
            }
            case SELECT -> {
                String[] color = SELECT[RNG.nextInt(SELECT.length)];
                t = new Term(type, station, 6, slots(10, 16, 19, 25, 28, 34, 37, 43), "Select all the " + color[0] + " items!");
                t.color = color;
                fill(items, t.rows);
                List<String[]> families = new ArrayList<>(List.of(SELECT));
                families.remove(color);
                Collections.shuffle(families, RNG);
                families = new ArrayList<>(families.subList(0, 4));
                families.add(0, color);
                List<Integer> counts = new ArrayList<>(List.of(6, 6, 6, 5, 5));
                Collections.shuffle(counts, RNG);
                List<String[]> deal = new ArrayList<>();
                for (int f = 0; f < 5; f++) {
                    for (int k = 0; k < counts.get(f); k++) {
                        deal.add(families.get(f));
                    }
                }
                Collections.shuffle(deal, RNG);
                for (int i = 0; i < t.grid.length; i++) {
                    String[] c = deal.get(i);
                    items.set(t.grid[i], named(c[2], c[1]));
                }
            }
            default -> {
                t = new Term(type, station, 6, new int[]{16, 25, 34}, "Click the button on time!");
                for (int i = 0; i < t.rows * 9; i++) {
                    items.add(ItemStack.EMPTY);
                }
                drawMelody(t, items);
            }
        }
        SimpleContainer container = new SimpleContainer(t.rows * 9);
        for (int i = 0; i < items.size() && i < t.rows * 9; i++) {
            container.setItem(i, items.get(i));
        }
        final Term term = t;
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, term, container, player),
                Component.literal(t.title)));
        opened++;
        LOGGER.info("[BossSim] terminal {} {} opened: {} ({})", "S" + station.section(), station.label(), type, t.title);
    }

    private static void fill(List<ItemStack> items, int rows) {
        items.clear();
        for (int i = 0; i < rows * 9; i++) {
            items.add(filler());
        }
    }

    private static List<Integer> shuffled(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(i);
        }
        Collections.shuffle(l, RNG);
        return l;
    }

    private static void drawMelody(Term t, List<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, filler());
        }
        int bottom = (t.grid.length + 1) * 9;
        items.set(t.melodyTarget, pane("magenta", 1));
        items.set(bottom + t.melodyTarget, pane("magenta", 1));
        for (int r = 0; r < t.grid.length; r++) {
            for (int c = 1; c <= 5; c++) {
                int slot = (r + 1) * 9 + c;
                items.set(slot, pane(r != t.melodyRow ? "white" : c == t.melodyLime ? "lime" : "red", 1));
            }
            ItemStack button = r == t.melodyRow ? named("lime_terracotta", "Lock In Slot") : named("red_terracotta", "Row Not Active");
            button.set(DataComponents.LORE, new ItemLore(List.of(
                    BossChat.legacy("§7Click this button when the Green"), BossChat.legacy("§7lines up with the Purple!"))));
            items.set((r + 1) * 9 + 7, button);
        }
    }

    private static void redrawMelody(Menu m) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < m.items.getContainerSize(); i++) {
            items.add(ItemStack.EMPTY);
        }
        drawMelody(m.term, items);
        for (int i = 0; i < items.size(); i++) {
            m.items.setItem(i, items.get(i));
        }
        m.broadcastChanges();
    }

    /** Every open Melody steps its green pane every 10 ticks, bouncing between columns 1 and 5. */
    static void tick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof Menu m && m.term.type == Type.MELODY) {
                Term t = m.term;
                if (++t.melodyLastStep < 10) {
                    continue;
                }
                t.melodyLastStep = 0;
                if (t.melodyFrozen > 0) {
                    t.melodyFrozen--;
                } else {
                    if (t.melodyLime + t.melodyDir < 1 || t.melodyLime + t.melodyDir > 5) {
                        t.melodyDir = -t.melodyDir;
                    }
                    t.melodyLime += t.melodyDir;
                }
                redrawMelody(m);
            }
        }
    }

    private static boolean rubixSolved(List<ItemStack> items, Term t) {
        String first = id(items.get(t.grid[0]));
        for (int g : t.grid) {
            if (!id(items.get(g)).equals(first)) {
                return false;
            }
        }
        return true;
    }

    private static int rubixIndex(String id) {
        for (int i = 0; i < RUBIX.length; i++) {
            if (id.equals(RUBIX[i] + "_stained_glass_pane")) {
                return i;
            }
        }
        return -1;
    }

    private static void onClick(Menu m, ServerPlayer player, int slot, int button) {
        Term t = m.term;
        if (slot < 0 || slot >= t.rows * 9) {
            return;
        }
        int now = player.level().getServer().getTickCount();
        while (!t.clickTicks.isEmpty() && now - t.clickTicks.peekFirst() >= 10) {
            t.clickTicks.pollFirst();
        }
        if (t.clickTicks.size() >= 5) {
            t.limited++;
            return;
        }
        t.clickTicks.addLast(now);
        t.clicks++;
        ItemStack s = m.items.getItem(slot);
        String id = id(s);
        switch (t.type) {
            case PANES -> {
                if (!t.inGrid(slot)) {
                    return;
                }
                if (id.equals("red_stained_glass_pane")) {
                    m.items.setItem(slot, pane("lime", 1));
                } else if (id.equals("lime_stained_glass_pane")) {
                    m.items.setItem(slot, pane("red", 1));
                }
            }
            case RUBIX -> {
                int idx = rubixIndex(id);
                if (idx >= 0 && t.inGrid(slot)) {
                    m.items.setItem(slot, pane(RUBIX[Math.floorMod(idx + (button == 1 ? -1 : 1), 5)], 1));
                }
            }
            case NUMBERS -> {
                if (!t.inGrid(slot) || !id.equals("red_stained_glass_pane")) {
                    return;
                }
                int min = Integer.MAX_VALUE;
                for (int g : t.grid) {
                    ItemStack o = m.items.getItem(g);
                    if (id(o).equals("red_stained_glass_pane")) {
                        min = Math.min(min, o.getCount());
                    }
                }
                if (s.getCount() == min) {
                    m.items.setItem(slot, pane("lime", s.getCount()));
                }
            }
            case STARTS_WITH -> {
                if (t.inGrid(slot) && !s.isEmpty() && !s.hasFoil()
                        && s.getHoverName().getString().toUpperCase(Locale.ROOT).startsWith(t.letter)) {
                    ItemStack g = s.copy();
                    g.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    m.items.setItem(slot, g);
                }
            }
            case SELECT -> {
                if (t.inGrid(slot) && !s.isEmpty() && !s.hasFoil() && s.getHoverName().getString().equals(t.color[1])) {
                    ItemStack g = s.copy();
                    g.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    m.items.setItem(slot, g);
                }
            }
            case MELODY -> {
                if (slot % 9 != 7 || slot / 9 < 1 || slot / 9 > t.grid.length) {
                    return;
                }
                if (slot != (t.melodyRow + 1) * 9 + 7 || t.melodyLime != t.melodyTarget) {
                    t.melodyFrozen = 2;
                    return;
                }
                t.melodyRow++;
                int last = t.melodyTarget;
                do {
                    t.melodyTarget = 1 + RNG.nextInt(5);
                } while (t.melodyTarget == last);
                if (t.melodyRow < t.grid.length) {
                    redrawMelody(m);
                }
            }
        }
        if (solved(m)) {
            solved++;
            LOGGER.info("[BossSim] terminal S{} {} solved ({} after {} click(s), {} over the click limit)", t.station.section(),
                    t.station.label(), t.type, t.clicks, t.limited);
            int id2 = m.containerId;
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                if (player.containerMenu.containerId == id2) {
                    player.closeContainer();
                }
                BossStations.complete(level, t.station, player);
            });
        }
    }

    private static boolean solved(Menu m) {
        Term t = m.term;
        return switch (t.type) {
            case MELODY -> t.melodyRow >= t.grid.length;
            case RUBIX -> {
                String first = id(m.items.getItem(t.grid[0]));
                for (int g : t.grid) {
                    if (!id(m.items.getItem(g)).equals(first)) {
                        yield false;
                    }
                }
                yield true;
            }
            case PANES, NUMBERS -> {
                for (int g : t.grid) {
                    if (id(m.items.getItem(g)).equals("red_stained_glass_pane")) {
                        yield false;
                    }
                }
                yield true;
            }
            case STARTS_WITH -> {
                for (int g : t.grid) {
                    ItemStack s = m.items.getItem(g);
                    if (!s.isEmpty() && !s.hasFoil() && s.getHoverName().getString().toUpperCase(Locale.ROOT).startsWith(t.letter)) {
                        yield false;
                    }
                }
                yield true;
            }
            case SELECT -> {
                for (int g : t.grid) {
                    ItemStack s = m.items.getItem(g);
                    if (!s.isEmpty() && !s.hasFoil() && s.getHoverName().getString().equals(t.color[1])) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    /** The player who has {@code station}'s window open, or null. */
    static ServerPlayer userOf(MinecraftServer server, BossStations.Station station) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof Menu m && m.term.station == station) {
                return p;
            }
        }
        return null;
    }

    /** Closes every open terminal (the fight was stopped). */
    static void closeAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof Menu) {
                p.closeContainer();
            }
        }
    }
}
