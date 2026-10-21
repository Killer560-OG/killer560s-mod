package com.killer560.hub.enchantcolors;

import com.killer560.hub.util.ChromaWave;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Enchant Colours" - killer560, 2026-09-16: "Skyhanni custom enchant colors."; rewritten 2026-09-20 after
 * killer560 reported it "didn't work in the first place anyways" and asked for it to actually match SkyHanni:
 * "the color is based off of tier not enchant."
 * <p>
 * Recolours the enchantment names in Hypixel item lore so the enchants that matter stand out instead of a
 * maxed item being thirty identical blue lines. Ported in behaviour (not code) from SkyHanni's <b>Enchant
 * Parsing</b> feature ({@code inventory.enchantParsing}, {@code features/misc/items/enchants/EnchantParser}),
 * which is itself a port of SkyblockAddons' {@code EnchantManager} - credited here the same way this repo
 * credits QUOI, Odin, NoammAddons and Devonian.
 * <p>
 * <b>What was taken from SkyHanni</b>
 * <ul>
 *   <li>The NBT ground truth: nothing is recoloured unless Hypixel's own {@code ExtraAttributes.enchantments}
 *       compound is present on the stack. That single check is what makes lore matching safe - a random line
 *       of ability text can never be mistaken for an enchant block.</li>
 *   <li>The two-stage regex: an "is this line ONLY enchants" gate ({@link #ENCHANT_LINE}) before the
 *       per-enchant extractor ({@link #ENCHANT}), so description text is never touched.</li>
 *   <li>Matching against the re-flattened "§"-coded text rather than stripped text, because Hypixel emits
 *       real garbage like {@code §5§r§d§l§r§d§lUltimate Wise V} and the codes have to be consumed, not
 *       stripped - see {@link LegacyText}.</li>
 *   <li>Tolerating both roman and arabic levels, multiple comma-separated enchants per line, and the
 *       trailing {@code §8<number>} stacking-enchant counter.</li>
 *   <li>A whole-lore cache: SkyHanni's own comment says continuous hover ran at "1 fps" without one, and
 *       this runs on the same per-frame path ({@code Screen#getTooltipFromItem}).</li>
 *   <li><b>Colour by tier, not by enchant.</b> Decompiled (javap) from SkyHanni-7.48.0-mc26.1.jar's
 *       {@code Enchant.getStyle}: {@code level >= maxLevel} -&gt; Perfect; {@code goodLevel < level <
 *       maxLevel} -&gt; Great; {@code level == goodLevel} -&gt; Good; {@code level < goodLevel} -&gt; Poor.
 *       {@code goodLevel}/{@code maxLevel} per enchant are copied from SkyHanni's own repo data - see
 *       {@link EnchantColorsDefaults}.</li>
 *   <li>Ultimates are always distinct and always the ultimate colour regardless of level - decompiled from
 *       {@code Enchant$Ultimate.getStyle}, which never reads goodLevel/maxLevel - detected from the
 *       {@code ultimate_} NBT id prefix, the same signal SkyHanni's Ultimate Enchant Star uses.</li>
 * </ul>
 * <b>Where this deliberately diverges</b>
 * <ul>
 *   <li>No reordering, compressing or stacking of the enchant block, and no stacking-progress footer.
 *       SkyHanni rebuilds the block from a sorted set; this only ever recolours text in place, so it can't
 *       fight any other lore feature and can't lose a line. Stacking enchants (Absorb, Compact, ...) still
 *       get tier colours from their own goodLevel/maxLevel, just no footer.</li>
 *   <li>No "hide vanilla enchants" (SkyHanni cancels {@code ItemStack#addToTooltip} for the ENCHANTMENTS
 *       component). Worth adding later, but it is a separate mixin on a separate target.</li>
 *   <li>Full RGB colours rather than the 16 legacy chat colours, since this repo already has a real colour
 *       picker. The recoloured segment is rebuilt as a styled {@link Component}, not a "§" code.</li>
 * </ul>
 */
public final class EnchantColorsFeature {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** The Perfect tier's chroma (killer560, 2026-10-13: "waterfalls the color from top left to bottom right in a
     *  wavy manner"). A Perfect segment is styled with this spec's colour, and {@code ChromaGlyphColorMixin} colours
     *  each glyph from its own screen position as the tooltip is drawn, so every enchant line of a tooltip is one
     *  diagonal waterfall and the cached components never need rebuilding to animate. Speed and size follow the
     *  tab's Chroma Speed / Chroma Size, synced on every {@link #recolor}. */
    private static final ChromaWave.Spec PERFECT_CHROMA = ChromaWave.rainbowSpec(EnchantColorsDefaults.PERFECT);

    /** Gate: the whole line is nothing but {@code Name Level} entries, optionally comma-separated, optionally
     *  with a trailing {@code §8<number>} stacking counter. Adapted from SkyHanni's
     *  {@code enchantmentExclusivePattern}. Must NOT match e.g. {@code §7by §c10% §7per hit, capped at}. */
    private static final Pattern ENCHANT_LINE = Pattern.compile(
            "^(?:(?:§.)*[A-Za-z][A-Za-z '-]+ (?:[IVXLCDM]+|[0-9]+)"
                    + "(?:(?:§r)?, |$| (?:§r)?§8\\d{1,3}(?:[,.]\\d{1,3})*[kKmMbB]?))+$");

    /** Extractor, run only on lines the gate accepted. The name class {@code [A-Za-z][A-Za-z '-]+} covers
     *  "Bane of Arthropods", "Counter-Strike" and "Bobbin' Time"; the level is roman or arabic because
     *  Hypixel uses both. Adapted from SkyHanni's {@code enchants.new} pattern. */
    private static final Pattern ENCHANT = Pattern.compile(
            "(?<codes>(?:§.)*)(?<enchant>[A-Za-z][A-Za-z '-]+) (?<level>[IVXLCDM]+|[0-9]+)");

    /** Ultimate NBT ids whose lore name doesn't contain the id - Hypixel renamed the display text but not
     *  the id. Without these, ultimate auto-detection would silently miss them. */
    private static final Map<String, String> ULTIMATE_ID_ALIASES = Map.of(
            "reiterate", "duplex",
            "bobbin_time", "bobbin' time");

    /** NBT enchant id (lowercase) -&gt; the lore-normalised name Hypixel actually prints, for every id whose
     *  display name doesn't match the "id with underscores as spaces" guess. Sourced the same place
     *  {@link EnchantColorsDefaults#TIERS} is: SkyHanni's own repo constants (nbtName -&gt; loreName). Without
     *  this, e.g. the {@code dragon_hunter} enchant (prints as "Gravity") would build the guessed name
     *  "dragon hunter", which never appears in real lore text, so it would never match and never get tiered -
     *  that was a real, confirmed bug in the old per-enchant table this file replaces. */
    static final Map<String, String> ID_TO_LORE_NAME = buildIdAliases();

    /** Recoloured tooltips keyed by stack identity (ItemStack has no value equality), validated against the
     *  exact lore/custom-data instances the stack still holds. */
    private static final Map<ItemStack, CacheEntry> CACHE = new WeakHashMap<>();

    /** Set from the (possibly off-render-thread) disconnect event and from config saves; the render thread
     *  does the actual clear, since {@link WeakHashMap} isn't thread-safe. */
    private static volatile boolean clearRequested;

    /** Set the first time the tooltip mixin actually calls {@link #recolor}, regardless of whether the
     *  feature is enabled. The mixin config is {@code required:false} so a signature change after a
     *  Minecraft update can never stop the game booting - but that also means it could silently do nothing,
     *  which is exactly how killer560 found both this and Scrollable Tooltips completely dead in-game
     *  (2026-09-20). Copied from {@code ArmourDye.renderHookSeen} so the tab can say so out loud instead of
     *  leaving him to wonder. */
    private static volatile boolean renderHookSeen;

    public static boolean renderHookSeen() {
        return renderHookSeen;
    }

    private EnchantColorsFeature() {
    }

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearRequested = true);
    }

    public static void clearCache() {
        clearRequested = true;
    }

    /** "  §9Bane of Arthropods " -&gt; "bane of arthropods". Lower-cased and whitespace-collapsed so a hand-typed
     *  override, a config key and a lore hit all land on the same string. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String stripped = net.minecraft.ChatFormatting.stripFormatting(name);
        if (stripped == null) {
            stripped = name;
        }
        String collapsed = stripped.trim();
        // Pattern hoisted (2026-09-20, FPS pass): String.replaceAll compiles its regex on every call, and
        // this runs for every lore line of every tooltip, every frame that tooltip is on screen.
        return WHITESPACE_RUN.matcher(collapsed).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> buildIdAliases() {
        Map<String, String> m = new HashMap<>();
        m.put("counter_strike", "counter-strike");
        m.put("aiming", "dragon tracer");
        m.put("syphon", "drain");
        m.put("dragon_hunter", "gravity");
        m.put("pristine", "prismatic");
        m.put("magmarizer", "pyroclasm");
        m.put("triple_strike", "triple-strike");
        m.put("turbo_cactus", "turbo-cacti");
        m.put("turbo_coco", "turbo-cocoa");
        m.put("arcane", "woodsplitter");
        // 2026-09-27: hardened_mana/strong_mana/mana_vampire/ferocious_mana used to alias here to invented
        // names ("hardened vitality" etc.) that never appear in real lore - see EnchantColorsDefaults' class
        // doc. Their id already normalises straight to the real loreName (e.g. "hardened_mana" -> "hardened
        // mana"), so they need no alias at all - removed rather than fixed to a self-mapping no-op.
        return m;
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Called from the tooltip mixin with the lines vanilla is about to show. Returns {@code lines} itself
     * when nothing changed, so a non-Skyblock item costs one map lookup.
     */
    public static List<Component> recolor(ItemStack stack, List<Component> lines) {
        renderHookSeen = true;
        EnchantColorsConfig cfg = EnchantColorsConfig.getInstance();
        if (!cfg.isEnabled() || stack == null || stack.isEmpty() || lines == null || lines.isEmpty()) {
            return lines;
        }
        if (clearRequested) {
            clearRequested = false;
            CACHE.clear();
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return lines;
        }
        PERFECT_CHROMA.speed(cfg.getChromaSpeed()).size(cfg.getChromaSize());
        CacheEntry cached = CACHE.get(stack);
        // A chroma Perfect segment carries PERFECT_CHROMA's colour and is coloured per glyph as it is drawn (see
        // that field), so a cached result animates by itself: until 2026-10-13 it was rebuilt every 100 ms instead.
        if (cached != null && cached.lore == lore && cached.data == data && cached.size == lines.size()) {
            return cached.result == null ? lines : cached.result;
        }
        List<Component> result;
        try {
            result = apply(cfg, data, lines);
        } catch (Exception e) {
            result = null;
        }
        CACHE.put(stack, new CacheEntry(lore, data, lines.size(), result));
        return result == null ? lines : result;
    }

    /** The Perfect chroma spec (the testkit matches tooltip styles against its colour). */
    public static ChromaWave.Spec perfectChroma() {
        return PERFECT_CHROMA;
    }

    // ---------------------------------------------------------------- the work

    /** @return the recoloured lines, or null when nothing on this item needed recolouring. */
    private static List<Component> apply(EnchantColorsConfig cfg, CustomData data, List<Component> lines) {
        CompoundTag tag = data.copyTag();
        // Hypixel's ExtraAttributes arrives as the item's custom-data root (same place ItemRarityFeature
        // reads "id" and "petInfo" from, and DungeonChestValuer reads "enchantments" from). No enchantments
        // compound means this item simply has no enchants, and SkyHanni bails at exactly the same point
        // rather than guessing from text.
        CompoundTag enchants = tag.getCompoundOrEmpty("enchantments");
        if (enchants.isEmpty()) {
            return null;
        }

        // Lore name -> NBT level, for every enchant on the item (both the plain and, for ultimates, the
        // "ultimate ..." spelling Hypixel actually prints). Keyed by the REAL printed name via
        // ID_TO_LORE_NAME, not a guess - see that field's doc for why the guess used to be wrong.
        Map<String, Integer> levels = new HashMap<>();
        Map<String, Boolean> isUltimate = new HashMap<>();
        for (String id : enchants.keySet()) {
            String bare = id.toLowerCase(Locale.ROOT);
            int level = enchants.getIntOr(id, 1);
            if (bare.startsWith("ultimate_")) {
                String rest = bare.substring("ultimate_".length());
                String alias = ULTIMATE_ID_ALIASES.get(rest);
                String spaced = alias != null ? alias : normalize(rest.replace('_', ' '));
                putName(levels, isUltimate, spaced, level, true);
                putName(levels, isUltimate, "ultimate " + spaced, level, true);
            } else {
                String lore = ID_TO_LORE_NAME.get(bare);
                if (lore == null && bare.startsWith("turbo_")) {
                    // Every Turbo-Crop prints hyphenated ("Turbo-Wheat"), so the underscore-as-space guess below
                    // missed all but the two aliased above - none of them tiered, so Turbo VII never got chroma
                    // (killer560, 2026-10-13: "all turbo crop 7's are not chroma'd either like they should be").
                    lore = "turbo-" + bare.substring("turbo_".length());
                }
                String spaced = lore != null ? lore : normalize(bare.replace('_', ' '));
                putName(levels, isUltimate, spaced, level, false);
            }
        }

        List<Component> out = null;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String legacy = LegacyText.toLegacy(line);
            if (legacy.isEmpty() || !ENCHANT_LINE.matcher(legacy).matches()) {
                continue;
            }
            Component rebuilt = rebuildLine(cfg, line, legacy, levels, isUltimate);
            if (rebuilt == null) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(lines);
            }
            out.set(i, rebuilt);
        }
        return out;
    }

    private static void putName(Map<String, Integer> levels, Map<String, Boolean> isUltimate, String name,
                                 int level, boolean ultimate) {
        if (name.isEmpty()) {
            return;
        }
        levels.put(name, level);
        if (ultimate) {
            isUltimate.put(name, Boolean.TRUE);
        }
    }

    /** @return the recoloured line, or null if nothing on it was a recognised enchant. */
    private static Component rebuildLine(EnchantColorsConfig cfg, Component original, String legacy,
                                         Map<String, Integer> levels, Map<String, Boolean> isUltimate) {
        Matcher m = ENCHANT.matcher(legacy);
        MutableComponent rebuilt = Component.empty().withStyle(original.getStyle());
        int cursor = 0;
        boolean changed = false;

        while (m.find()) {
            String name = normalize(m.group("enchant"));
            Integer nbtLevel = levels.get(name);
            // Primary detection is the ultimate_ NBT id prefix (isUltimate); EnchantColorsDefaults.ULTIMATES
            // is a name-based safety net for the same case the old table's hardcoded list existed for - a
            // future Hypixel rename our id-to-lore guess doesn't know about yet, where the printed name still
            // matches one of the 26 real ultimates.
            boolean ultimate = cfg.isUltimateEnabled() && nbtLevel != null
                    && (Boolean.TRUE.equals(isUltimate.get(name)) || EnchantColorsDefaults.ULTIMATES.contains(name));

            Integer color;
            boolean bold;
            boolean chroma = false;
            if (ultimate) {
                color = cfg.getUltimateColor();
                bold = cfg.isUltimateBold();
            } else if (nbtLevel != null) {
                // Level to tier against is the NBT's own value, not the roman/arabic text just parsed - the
                // two always agree on a real item, and re-parsing roman numerals would just be a second,
                // riskier way to get the same number.
                int[] tier = EnchantColorsDefaults.TIERS.get(name);
                if (tier != null) {
                    int goodLevel = tier[0];
                    int maxLevel = tier[1];
                    // killer560, 2026-09-27: "something like gk5 being a t7" - maxLevel here is already the
                    // enchant's real ceiling (Giant Killer's is 7 via Dark Auction/Experiments, not the
                    // enchant-table cap of 5 - hypixelskyblock.minecraft.wiki, 2026-09-27), so a level at or
                    // above it is the rarest tier regardless of how far past the "normal" cap it sits.
                    // Gated behind trueMaxDetection rather than always-on: if that per-enchant ceiling table
                    // ever falls behind a future Hypixel cap raise, this is the escape hatch back to treating
                    // every level past goodLevel the same (an ordinary high level, never Perfect) instead of
                    // quietly mis-tiering something.
                    boolean perfect = cfg.isTrueMaxDetection() && nbtLevel >= maxLevel;
                    if (perfect && cfg.isPerfectChroma()) {
                        // killer560, 2026-09-27: SkyHanni's real Perfect default is an animated rainbow, not
                        // a fixed colour - see EnchantColorsDefaults' class doc. cfg.getPerfectColor() is
                        // only used as the static fallback while this toggle is off.
                        color = EnchantColorsDefaults.PERFECT;
                        chroma = true;
                    } else {
                        color = perfect ? cfg.getPerfectColor()
                                : nbtLevel > goodLevel ? cfg.getGreatColor()
                                : nbtLevel == goodLevel ? cfg.getGoodColor()
                                : cfg.getPoorColor();
                    }
                    bold = perfect && cfg.isPerfectBold();
                } else if (!cfg.isOnlyKnownEnchants()) {
                    // "Only Known Enchantments" off: recognised by the NBT (it IS an enchant on this item)
                    // but not in the tier table - a brand new enchant this table hasn't caught up with yet.
                    color = cfg.getUnknownColor();
                    bold = false;
                } else {
                    color = null;
                    bold = false;
                }
            } else {
                // Not one of this item's actual enchants at all (e.g. a lore line that merely LOOKS
                // enchant-shaped, like "Tier IX") - always left alone regardless of "Only Known Enchantments".
                color = null;
                bold = false;
            }
            if (color == null) {
                continue;
            }

            // Text before this enchant goes through untouched, but has to carry the codes that were in
            // effect there: once an earlier enchant on this line has been replaced with a styled component,
            // plain text would otherwise inherit the NEW colour instead of Hypixel's.
            appendRaw(rebuilt, legacy, cursor, m.start());
            Style style = Style.EMPTY
                    .withColor(chroma ? PERFECT_CHROMA.textColor() : TextColor.fromRgb(color & 0xFFFFFF))
                    .withItalic(Boolean.FALSE)
                    .withBold(bold ? Boolean.TRUE : Boolean.FALSE);
            rebuilt.append(Component.literal(m.group("enchant") + " " + m.group("level")).withStyle(style));
            cursor = m.end();
            changed = true;
        }
        if (!changed) {
            return null;
        }
        appendRaw(rebuilt, legacy, cursor, legacy.length());
        return rebuilt;
    }

    private static void appendRaw(MutableComponent into, String legacy, int from, int to) {
        if (from >= to) {
            return;
        }
        String chunk = legacy.substring(from, to);
        String active = LegacyText.activeCodesAt(legacy, from);
        // Only re-state the codes when the chunk doesn't already open with one of its own.
        into.append(Component.literal(chunk.startsWith("§") ? chunk : active + chunk));
    }

    private static final class CacheEntry {
        final ItemLore lore;
        final CustomData data;
        final int size;
        /** null = this stack needs no recolouring at all. */
        final List<Component> result;

        CacheEntry(ItemLore lore, CustomData data, int size, List<Component> result) {
            this.lore = lore;
            this.data = data;
            this.size = size;
            this.result = result;
        }
    }
}
