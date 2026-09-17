package com.killer560.hub.enchantcolors;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Enchant Colours" - killer560, 2026-09-16: "Skyhanni custom enchant colors."
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
 *   <li>Ultimates are always distinct (and bold by default), detected from the {@code ultimate_} NBT id
 *       prefix - the same signal SkyHanni's Ultimate Enchant Star uses.</li>
 * </ul>
 * <b>Where this deliberately diverges</b>
 * <ul>
 *   <li>No tier-by-level colouring. SkyHanni downloads {@code constants/Enchants.json} from its own
 *       repository to learn each enchant's {@code goodLevel}/{@code maxLevel} and colours poor/good/great/
 *       perfect from that. This mod fetches nothing, so the tiers are per-enchant instead of per-level,
 *       seeded from {@link EnchantColorsDefaults} and fully editable - which is also exactly what killer560
 *       asked for ("per-enchantment colour overrides... GUI to add/edit/remove an override").</li>
 *   <li>No reordering, compressing or stacking of the enchant block, and no stacking-progress footer.
 *       SkyHanni rebuilds the block from a sorted set; this only ever recolours text in place, so it can't
 *       fight any other lore feature and can't lose a line.</li>
 *   <li>No "hide vanilla enchants" (SkyHanni cancels {@code ItemStack#addToTooltip} for the ENCHANTMENTS
 *       component). Worth adding later, but it is a separate mixin on a separate target.</li>
 *   <li>Full RGB colours rather than the 16 legacy chat colours, since this repo already has a real colour
 *       picker. The recoloured segment is rebuilt as a styled {@link Component}, not a "§" code.</li>
 * </ul>
 */
public final class EnchantColorsFeature {

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

    /** Recoloured tooltips keyed by stack identity (ItemStack has no value equality), validated against the
     *  exact lore/custom-data instances the stack still holds. */
    private static final Map<ItemStack, CacheEntry> CACHE = new WeakHashMap<>();

    /** Set from the (possibly off-render-thread) disconnect event and from config saves; the render thread
     *  does the actual clear, since {@link WeakHashMap} isn't thread-safe. */
    private static volatile boolean clearRequested;

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
        return stripped.replace(' ', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Called from the tooltip mixin with the lines vanilla is about to show. Returns {@code lines} itself
     * when nothing changed, so a non-Skyblock item costs one map lookup.
     */
    public static List<Component> recolor(ItemStack stack, List<Component> lines) {
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
        CacheEntry cached = CACHE.get(stack);
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

    // ---------------------------------------------------------------- the work

    private static List<Component> apply(EnchantColorsConfig cfg, CustomData data, List<Component> lines) {
        CompoundTag tag = data.copyTag();
        // Hypixel's ExtraAttributes arrives as the item's custom-data root (same place ItemRarityFeature
        // reads "id" and "petInfo" from). No enchantments compound means this item simply has no enchants,
        // and SkyHanni bails at exactly the same point rather than guessing from text.
        CompoundTag enchants = tag.getCompoundOrEmpty("enchantments");
        if (enchants.isEmpty()) {
            return null;
        }

        Set<String> known = new HashSet<>();
        Set<String> ultimates = new HashSet<>();
        for (String id : enchants.keySet()) {
            String bare = id.toLowerCase(Locale.ROOT);
            if (bare.startsWith("ultimate_")) {
                String rest = bare.substring("ultimate_".length());
                addNameVariants(ultimates, rest);
                addNameVariants(known, rest);
                String alias = ULTIMATE_ID_ALIASES.get(rest);
                if (alias != null) {
                    ultimates.add(alias);
                    known.add(alias);
                }
            } else {
                addNameVariants(known, bare);
            }
        }

        List<Component> out = null;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String legacy = LegacyText.toLegacy(line);
            if (legacy.isEmpty() || !ENCHANT_LINE.matcher(legacy).matches()) {
                continue;
            }
            Component rebuilt = rebuildLine(cfg, line, legacy, known, ultimates);
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

    /** {@code "bane_of_arthropods"} -&gt; both {@code "bane of arthropods"} and, for ultimates, the
     *  {@code "ultimate ..."} spelling Hypixel actually prints for most of them. */
    private static void addNameVariants(Set<String> into, String bareId) {
        String spaced = normalize(bareId.replace('_', ' '));
        if (spaced.isEmpty()) {
            return;
        }
        into.add(spaced);
        into.add("ultimate " + spaced);
    }

    /** @return the recoloured line, or null if nothing on it was a recognised enchant. */
    private static Component rebuildLine(EnchantColorsConfig cfg, Component original, String legacy,
                                         Set<String> known, Set<String> ultimates) {
        Matcher m = ENCHANT.matcher(legacy);
        MutableComponent rebuilt = Component.empty().withStyle(original.getStyle());
        int cursor = 0;
        boolean changed = false;

        while (m.find()) {
            String name = normalize(m.group("enchant"));
            boolean isUltimate = cfg.isUltimateEnabled() && ultimates.contains(name);
            Integer override = cfg.getColor(name);
            // "Only Configured Enchantments": an enchant with no entry keeps Hypixel's own colour. Otherwise
            // anything the NBT or the table recognises gets at least the default colour. A name that is
            // neither (a lore line that merely LOOKS enchant-shaped, e.g. "Tier IX") is always left alone.
            boolean recognised = known.contains(name) || override != null;
            Integer color = isUltimate ? cfg.getUltimateColor()
                    : override != null ? override
                    : (!cfg.isOnlyConfigured() && recognised) ? cfg.getDefaultColor()
                    : null;
            if (color == null) {
                continue;
            }

            // Text before this enchant goes through untouched, but has to carry the codes that were in
            // effect there: once an earlier enchant on this line has been replaced with a styled component,
            // plain text would otherwise inherit the NEW colour instead of Hypixel's.
            appendRaw(rebuilt, legacy, cursor, m.start());
            Style style = Style.EMPTY
                    .withColor(TextColor.fromRgb(color & 0xFFFFFF))
                    .withItalic(Boolean.FALSE)
                    .withBold(isUltimate && cfg.isUltimateBold() ? Boolean.TRUE : Boolean.FALSE);
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
