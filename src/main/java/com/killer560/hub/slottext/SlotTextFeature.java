package com.killer560.hub.slottext;

import com.killer560.hub.util.ItemNbt;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Slot Text" - small text drawn over the corner of an item in a container slot: a pet's level, an enchanted book's
 * level, a minion's tier, a skill's level in the Your Skills menu and so on, so the number is readable without hovering.
 * Skyblocker has the same idea; which menus and numbers it covers was read from its documentation and everything here
 * is written from scratch against this mod's own item helpers. Purely visual, and available on both builds.
 * <p>
 * {@link #compute} is pure (stack, menu title, slot index, which types are on) so it can be tested without a screen;
 * {@link #draw} caches per stack and paints. Each type has its own toggle ({@link SlotTextType}); the whole feature can
 * be always on, shown only while a key is held, or hidden while a key is held ({@link SlotTextConfig.Mode}).
 * Container slots only: the hotbar is not drawn on.
 */
public final class SlotTextFeature {

    public enum Pos { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** One piece of text for a slot: where it sits and its ARGB colour. */
    public record Entry(SlotTextType type, Pos pos, String text, int color) {
    }

    public static final int CREAM = 0xFFFFFFDD;
    public static final int GOLD = 0xFFFFAA00;
    public static final int PURPLE = 0xFFFF55FF;

    private static final Pattern PET_NAME = Pattern.compile("^⭐? ?\\[Lvl (\\d{1,3})\\].*");
    private static final Pattern MINION_NAME = Pattern.compile("^.* Minion ([IVXLCDM]+)$");
    private static final Pattern RANCHER = Pattern.compile("Current Speed Cap: (\\d{1,4}) ?(\\d{1,4})?");
    private static final Pattern COLLECTION_TITLE = Pattern.compile("^\\w+ Collections.*");
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[\\w -]+ ([IVXLCDM]+)$");
    private static final Pattern BESTIARY_NAME = Pattern.compile("^[\\w '-]+ ([IVXLCDM]+)$");
    private static final Pattern BESTIARY_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?(?:Bestiary|Fishing) ➜ .+");
    private static final Pattern TUNING_STAT = Pattern.compile("Stat has: (\\d{1,5}) points?");
    private static final Pattern TUNING_FREE = Pattern.compile("Unassigned Points: (\\d{1,5})!*");
    private static final Pattern PERK_LEVEL = Pattern.compile("^Level (\\d{1,4})(?:/\\d{1,4})?$");

    private static final Map<ItemStack, Cached> CACHE = new WeakHashMap<>();
    private static volatile int generation;

    private SlotTextFeature() {
    }

    /** Call after any setting that changes what is drawn; cached results are rebuilt on the next frame. */
    public static void settingsChanged() {
        generation++;
    }

    // ------------------------------------------------------------------ gating

    /** Whether text should be drawn this frame: feature on, Skyblock gate, and the key rule of the chosen mode. */
    public static boolean visible() {
        SlotTextConfig cfg = SlotTextConfig.getInstance();
        if (!cfg.isEnabledRaw()) {
            return false;
        }
        if (cfg.isSkyblockOnly() && !SkyblockGate.allows()) {
            return false;
        }
        SlotTextConfig.Mode mode = cfg.getMode();
        // Asked for every slot of an open menu every frame: in Always mode the key is never read, so it is not polled
        // (a GLFW call per slot) just to be ignored (mixin FPS pass, 2026-10-15).
        return mode == SlotTextConfig.Mode.ALWAYS || modeAllows(mode, keyHeld(cfg.getKeyCode()));
    }

    /** The key rule on its own, public for the tests. */
    public static boolean modeAllows(SlotTextConfig.Mode mode, boolean keyHeld) {
        return switch (mode) {
            case ALWAYS -> true;
            case HOLD_TO_SHOW -> keyHeld;
            case HOLD_TO_HIDE -> !keyHeld;
        };
    }

    /** Test hook (docs/TESTING-HOOKS.md): when non-null it replaces the polled key state, because a harness key press
     *  never reaches the GLFW poll. Always null in play. */
    public static volatile Boolean keyHeldForTest;

    private static boolean keyHeld(int code) {
        Boolean forced = keyHeldForTest;
        if (forced != null) {
            return forced;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc != null && KeyUtil.isBindDown(mc.getWindow(), code);
    }

    // ------------------------------------------------------------------ rendering

    /** Called from the slot mixin after the item and its count are drawn. {@code x}/{@code y} are slot-local. */
    public static void draw(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !visible()) {
            return;
        }
        String title = titleOf(screen);
        boolean playerSlot = slot.container instanceof Inventory;
        List<Entry> entries = entriesFor(stack, title, slot.index, playerSlot);
        if (entries.isEmpty()) {
            return;
        }
        paint(graphics, Minecraft.getInstance().font, entries, slot.x, slot.y);
    }

    private static Component lastTitle;
    private static String lastTitleText = "";

    /** The screen title's text, worked out once per title object: {@code getString} builds a new String, and this is
     *  asked for every slot of the menu every frame. Render thread only. */
    private static String titleOf(AbstractContainerScreen<?> screen) {
        Component title = screen.getTitle();
        if (title != lastTitle) {
            lastTitleText = title.getString();
            lastTitle = title;
        }
        return lastTitleText;
    }

    /** Paints entries into the 16x16 box at x,y. Text wider than the box is scaled down to fit it. Public for tests. */
    public static void paint(GuiGraphicsExtractor graphics, Font font, List<Entry> entries, int x, int y) {
        Matrix3x2fStack pose = graphics.pose();
        for (Entry e : entries) {
            if (e.text() == null) {
                continue; // vanilla's text(String) draws nothing for null
            }
            int width = font.width(e.text());
            float scale = width > 16 ? 16f / width : 1f;
            float drawW = width * scale;
            float drawH = font.lineHeight * scale;
            float ox = (e.pos() == Pos.TOP_RIGHT || e.pos() == Pos.BOTTOM_RIGHT) ? 16f - drawW : 0f;
            float oy = (e.pos() == Pos.BOTTOM_LEFT || e.pos() == Pos.BOTTOM_RIGHT) ? 18f - drawH : 0f;
            pose.pushMatrix();
            pose.translate(x + ox, y + oy);
            if (scale != 1f) {
                pose.scale(scale, scale);
            }
            // visual order kept between frames: every slot's text was a fresh ICU Bidi per frame (fps-gui-1015)
            graphics.text(font, com.killer560.hub.hud.HudTextCache.visualOrder(e.text()), 0, 0, e.color(), true);
            pose.popMatrix();
        }
    }

    /** {@link #compute} with the live per-type switches; ignores the show rule. Public for the tests. */
    public static List<Entry> textFor(ItemStack stack, String title, int slotIndex, boolean playerSlot) {
        return compute(stack, title, slotIndex, playerSlot, SlotTextConfig.getInstance()::isTypeEnabled);
    }

    private static List<Entry> entriesFor(ItemStack stack, String title, int slotIndex, boolean playerSlot) {
        SlotTextConfig cfg = SlotTextConfig.getInstance();
        int gen = generation;
        ItemLore lore = stack.get(DataComponents.LORE);
        Object data = stack.get(DataComponents.CUSTOM_DATA);
        Object name = stack.get(DataComponents.CUSTOM_NAME);
        Cached c = CACHE.get(stack);
        if (c != null && c.gen == gen && c.lore == lore && c.data == data && c.name == name
                && c.slotIndex == slotIndex && c.playerSlot == playerSlot && c.title.equals(title)) {
            return c.entries;
        }
        List<Entry> fresh;
        try {
            fresh = compute(stack, title, slotIndex, playerSlot, cfg::isTypeEnabled);
        } catch (Exception e) {
            fresh = List.of();
        }
        if (CACHE.size() > 2048) {
            CACHE.clear();
        }
        CACHE.put(stack, new Cached(gen, lore, data, name, slotIndex, playerSlot, title, fresh));
        return fresh;
    }

    private record Cached(int gen, ItemLore lore, Object data, Object name, int slotIndex, boolean playerSlot,
                          String title, List<Entry> entries) {
    }

    // ------------------------------------------------------------------ the text itself

    /**
     * Every piece of slot text for {@code stack}. {@code title} is the open menu's title; {@code playerSlot} is true for a
     * slot of the player's own inventory (menu-only types skip it). Public for the tests.
     */
    public static List<Entry> compute(ItemStack stack, String title, int slotIndex, boolean playerSlot,
                                      Predicate<SlotTextType> on) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String name = stack.getHoverName().getString();
        CompoundTag tag = ItemNbt.view(stack);
        String id = tag == null ? "" : tag.getStringOr("id", "");
        List<Entry> out = new ArrayList<>(2);

        if (on.test(SlotTextType.PET_LEVEL) && stack.is(Items.PLAYER_HEAD)) {
            Matcher m = PET_NAME.matcher(name);
            if (m.matches()) {
                out.add(new Entry(SlotTextType.PET_LEVEL, Pos.TOP_LEFT, m.group(1), CREAM));
            }
        }
        if (stack.is(Items.ENCHANTED_BOOK)) {
            bookText(tag, name, on, out);
        }
        if (on.test(SlotTextType.MINION_TIER) && stack.is(Items.PLAYER_HEAD)) {
            Matcher m = MINION_NAME.matcher(name);
            if (m.matches() && isRoman(m.group(1))) {
                out.add(new Entry(SlotTextType.MINION_TIER, Pos.TOP_RIGHT, String.valueOf(roman(m.group(1))), CREAM));
            }
        }
        if (on.test(SlotTextType.POTION_LEVEL) && tag != null && tag.contains("potion_level")
                && !name.contains("Healer") && !name.contains("Class Passives")) {
            int level = tag.getIntOr("potion_level", 0);
            if (level > 0) {
                out.add(new Entry(SlotTextType.POTION_LEVEL, Pos.BOTTOM_RIGHT, String.valueOf(level), CREAM));
            }
        }
        if (on.test(SlotTextType.RANCHER_SPEED) && "RANCHERS_BOOTS".equals(id)) {
            for (String line : loreLines(stack)) {
                Matcher m = RANCHER.matcher(line);
                if (m.find()) {
                    // The second number only appears when the cap is set past what the player can actually reach.
                    out.add(new Entry(SlotTextType.RANCHER_SPEED, Pos.BOTTOM_LEFT,
                            m.group(2) != null ? m.group(2) : m.group(1), CREAM));
                    break;
                }
            }
        }
        if (on.test(SlotTextType.ATTRIBUTE_LEVEL) && "ATTRIBUTE_SHARD".equals(id)) {
            CompoundTag attrs = tag.getCompoundOrEmpty("attributes");
            if (attrs.size() == 1) {
                String key = attrs.keySet().iterator().next();
                int level = attrs.getIntOr(key, 0);
                if (level > 0) {
                    out.add(new Entry(SlotTextType.ATTRIBUTE_LEVEL, Pos.BOTTOM_RIGHT, String.valueOf(level), CREAM));
                }
            }
        }
        if (!playerSlot) {
            menuText(stack, title, slotIndex, name, on, out);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void bookText(CompoundTag tag, String name, Predicate<SlotTextType> on, List<Entry> out) {
        boolean wantLevel = on.test(SlotTextType.ENCHANT_LEVEL);
        boolean wantAbbr = on.test(SlotTextType.ENCHANT_ABBREVIATION);
        if (!wantLevel && !wantAbbr) {
            return;
        }
        CompoundTag enchants = tag == null ? new CompoundTag() : tag.getCompoundOrEmpty("enchantments");
        if (enchants.size() == 1) {
            String key = enchants.keySet().iterator().next();
            int level = enchants.getIntOr(key, 0);
            if (wantLevel && level > 0) {
                out.add(new Entry(SlotTextType.ENCHANT_LEVEL, Pos.BOTTOM_LEFT, String.valueOf(level), CREAM));
            }
            if (wantAbbr) {
                out.add(new Entry(SlotTextType.ENCHANT_ABBREVIATION, Pos.TOP_LEFT, abbreviate(key),
                        key.startsWith("ultimate_") ? PURPLE : CREAM));
            }
        } else if (wantLevel && enchants.isEmpty()) {
            // Bazaar listings are books with no enchantments tag; the level is the numeral ending the name.
            int sp = name.lastIndexOf(' ');
            if (sp > 0 && isRoman(name.substring(sp + 1))) {
                out.add(new Entry(SlotTextType.ENCHANT_LEVEL, Pos.BOTTOM_LEFT, String.valueOf(roman(name.substring(sp + 1))), CREAM));
            }
        }
    }

    private static void menuText(ItemStack stack, String title, int slot, String name, Predicate<SlotTextType> on, List<Entry> out) {
        if (slot > 53) {
            return;
        }
        int row = slot / 9;
        if (on.test(SlotTextType.SKILL_LEVEL) && title.startsWith("Your Skills")) {
            if (row >= 1 && row <= 4 && !name.isBlank() && !name.equals("Dungeoneering") && !stack.is(com.killer560.hub.compat.McItems.BLACK_STAINED_GLASS_PANE)) {
                int sp = name.lastIndexOf(' ');
                String last = sp < 0 ? "" : name.substring(sp + 1);
                int level = isRoman(last) ? roman(last) : parseInt(last);
                boolean maxed = loreLines(stack).stream().anyMatch(l -> l.contains("Max Skill level reached!"));
                out.add(new Entry(SlotTextType.SKILL_LEVEL, Pos.BOTTOM_LEFT, String.valueOf(Math.max(level, 0)), maxed ? GOLD : CREAM));
            }
        }
        if (on.test(SlotTextType.COLLECTION_LEVEL) && COLLECTION_TITLE.matcher(title).matches()) {
            Matcher m = COLLECTION_NAME.matcher(name);
            if (m.matches() && isRoman(m.group(1))) {
                boolean inProgress = loreLines(stack).stream().anyMatch(l -> l.contains("Progress to "));
                out.add(new Entry(SlotTextType.COLLECTION_LEVEL, Pos.BOTTOM_RIGHT, String.valueOf(roman(m.group(1))), inProgress ? CREAM : GOLD));
            }
        }
        if (on.test(SlotTextType.BESTIARY_LEVEL) && BESTIARY_TITLE.matcher(title).matches()
                && ((slot >= 10 && slot <= 16) || (slot >= 19 && slot <= 25) || (slot >= 28 && slot <= 34) || (slot >= 37 && slot <= 43))) {
            Matcher m = BESTIARY_NAME.matcher(name);
            if (m.matches() && isRoman(m.group(1))) {
                boolean done = loreLines(stack).stream().anyMatch(l -> l.contains("Overall Progress: 100%"));
                out.add(new Entry(SlotTextType.BESTIARY_LEVEL, Pos.BOTTOM_RIGHT, String.valueOf(roman(m.group(1))), done ? GOLD : CREAM));
            }
        }
        if (on.test(SlotTextType.PERK_LEVEL) && (title.startsWith("Heart of the Mountain") || title.startsWith("Heart of the Forest"))) {
            for (String line : loreLines(stack)) {
                Matcher m = PERK_LEVEL.matcher(line.trim());
                if (m.matches()) {
                    out.add(new Entry(SlotTextType.PERK_LEVEL, Pos.BOTTOM_RIGHT, m.group(1), CREAM));
                    break;
                }
            }
        }
        if (on.test(SlotTextType.STATS_TUNING) && title.startsWith("Stats Tuning")) {
            boolean button = name.equals("Stats Tuning");
            Pattern wanted = button ? TUNING_FREE : TUNING_STAT;
            for (String line : loreLines(stack)) {
                Matcher m = wanted.matcher(line.trim());
                if (m.find()) {
                    if (button || !m.group(1).equals("0")) {
                        out.add(new Entry(SlotTextType.STATS_TUNING, Pos.BOTTOM_RIGHT, m.group(1), CREAM));
                    }
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> loreLines(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (var c : lore.lines()) {
            lines.add(c.getString());
        }
        return lines;
    }

    /** "bane_of_arthropods" -> "BA", "sharpness" -> "SHA", "ultimate_wise" -> "WIS": initials of the words, or the first
     *  three letters of a single word. Public for the tests. */
    public static String abbreviate(String enchantId) {
        String id = enchantId.startsWith("ultimate_") ? enchantId.substring("ultimate_".length()) : enchantId;
        List<String> words = new ArrayList<>();
        for (String w : id.split("_")) {
            if (!w.isEmpty() && !w.equals("of") && !w.equals("the")) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        if (words.size() == 1) {
            String w = words.get(0);
            sb.append(w, 0, Math.min(3, w.length()));
        } else {
            for (String w : words) {
                if (sb.length() < 3) {
                    sb.append(w.charAt(0));
                }
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether {@code s} is a non-empty Roman numeral made only of I V X L C D M. */
    public static boolean isRoman(String s) {
        return s != null && !s.isEmpty() && s.length() <= 15 && s.matches("[IVXLCDM]+");
    }

    /** Value of a Roman numeral (subtractive notation); 0 if {@code s} is not one. Public for the tests. */
    public static int roman(String s) {
        if (!isRoman(s)) {
            return 0;
        }
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                default -> 1000;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
