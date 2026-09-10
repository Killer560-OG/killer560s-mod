package com.killer560.hub.rngmeter;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni-style overlay: when the player opens Hypixel's own in-game RNG Meter menus, draws the
 * coins-per-meter-point ranking for whatever floor/boss/area is selected directly over the game
 * world next to it - no separate mod screen to open.
 *
 * <p>Rendering is done via {@link com.killer560.hub.rngmeter.mixin.AbstractContainerScreenMixin}
 * hooking directly into every container screen's own render pass.
 *
 * <p>Items are discovered by scanning the menu's own slots live and parsing each reward's real
 * pity requirement straight out of its lore, instead of relying on a hand-typed per-floor table -
 * confirmed via a diagnostic dump against a real F6/F7 menu that every reward item's lore contains
 * either {@code "Dungeon Score: 5,000/2,105"} (unselected items - denominator is the exact real
 * pity requirement) or, for the one currently-selected item, a {@code "Progress: 62.5%"} line
 * immediately followed by a bare {@code "5,000/8k"} fraction line. Also confirmed from that same
 * dump: these reward-preview items carry NO {@code ExtraAttributes} NBT at all (unlike real held
 * items), so they can only be identified by display name, never by SkyBlock ID - see
 * {@link RngItemNames}.
 */
public final class RngMeterOverlay {

    // Every meter category labels its progress line differently - confirmed from real lore dumps
    // across dungeons ("Dungeon Score:"), Experimentation Table ("Experimental XP:"), slayers
    // ("Slayer XP:"), Crystal Nucleus ("Nucleus XP:"), and Frozen Corpses ("Frozen Corpse XP:").
    // All 5 end in "XP:" or "Score:" directly followed by "current/required", so matched generally
    // instead of hardcoding a full label per category (which kept missing whatever category hadn't
    // been tested yet). Safe against false positives like a gear tooltip's "Combat XP: 61.8M
    // §7/ 1M" line, since there "XP" trails the fraction rather than immediately preceding it with
    // a colon.
    private static final Pattern DUNGEON_SCORE_LINE = Pattern.compile("(?:XP|Score):\\s*[\\d,]+\\s*/\\s*([\\d,]+(?:\\.\\d+)?[kKmM]?)");
    private static final Pattern PROGRESS_LABEL_LINE = Pattern.compile("^Progress:");
    private static final Pattern BARE_FRACTION_LINE = Pattern.compile("^([\\d,]+)\\s*/\\s*([\\d,]+(?:\\.\\d+)?[kKmM]?)$");

    private static final String ELEMENT_ID = "rng_meter_ranking";
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-rngmeter-overlay");
    // Committed scan: only updated once a candidate scan has matched the previous frame's
    // candidate STABLE_FRAMES_REQUIRED times in a row (or MAX_UNSTABLE_FRAMES have passed without
    // stabilizing, as a worst-case bound). A single frame's slot data is unreliable right around a
    // screen transition - sometimes read before the real content has synced (undercounting, seen
    // going from 25 items down to 0 on a real meter re-open), sometimes still holding one stale
    // frame of the *previous* screen's items (overcounting - seen as 1-4 phantom "rewards" on
    // category-picker screens that should show nothing). Requiring stability across consecutive
    // frames instead of trusting frame 1 handles both directions without needing to special-case
    // picker screen titles at all.
    private static volatile String committedTitle = null;
    private static volatile List<RngItem> committedScan = List.of();
    private static volatile SelectedDropInfo committedSelected = null;
    private static String pendingTitle = null;
    private static List<RngItem> pendingScan = null;
    private static SelectedDropInfo pendingSelected = null;
    private static int pendingStableCount = 0;
    private static int pendingFrameCount = 0;
    private static final int STABLE_FRAMES_REQUIRED = 3;
    private static final int MAX_UNSTABLE_FRAMES = 40;
    private static final int VISIBLE_ROWS = 8;
    private static volatile int scrollOffset = 0;

    private RngMeterOverlay() {
    }

    public static void register() {
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return ELEMENT_ID;
            }

            @Override
            public String displayName() {
                return "RNG Meter Ranking";
            }

            @Override
            public int defaultX() {
                return Minecraft.getInstance().getWindow().getGuiScaledWidth() - 230;
            }

            @Override
            public int defaultY() {
                return 20;
            }

            @Override
            public int width() {
                return 220;
            }

            @Override
            public int height() {
                return 100;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                // HUD position editor preview - no real menu is open, so show placeholder sample rows.
                List<RngMeterEngine.RankedItem> sample = List.of(
                        new RngMeterEngine.RankedItem(new RngItem("", "Example Item A", "", RngSource.BAZAAR, 10_000, false), 5_000_000L, 500.0, 1),
                        new RngMeterEngine.RankedItem(new RngItem("", "Example Item B", "", RngSource.BAZAAR, 20_000, false), 2_000_000L, 100.0, 2)
                );
                renderRanking(graphics, x, y, "RNG Meter", sample, new SelectedDropInfo("Example Item A", 42.0, 4_200, 10_000));
            }
        });

        RngItemLog.load();

        // Kept only for logging + a once-per-open price refresh; actual drawing happens in
        // AbstractContainerScreenMixin now.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            try {
                String title = screen.getTitle().getString();
                LOGGER.info("Screen opened, title=\"{}\" class={}", title, screen.getClass().getName());
                if (title.toLowerCase(Locale.US).contains("rng")) {
                    RngMeterEngine.PRICES.refreshIfStale();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to process screen open for RNG Meter overlay", e);
            }
        });
    }

    /** Called every frame from {@link com.killer560.hub.rngmeter.mixin.AbstractContainerScreenMixin}. */
    public static void onContainerScreenRender(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        try {
            if (!RngMeterConfig.getInstance().isEnabled()) {
                return;
            }
            String title = screen.getTitle().getString();
            if (!title.toLowerCase(Locale.US).contains("rng")) {
                return;
            }
            if (!title.equals(committedTitle)) {
                advanceScanStability(screen, title);
                // Don't render off an uncommitted (not-yet-stable) scan - it may still be a stale
                // frame left over from the previous screen. Renders nothing for a few frames right
                // after a menu opens, then the committed result takes over on its own.
                return;
            }
            List<RngItem> scanned = committedScan;
            if (scanned.isEmpty()) {
                // Genuinely a category-picker screen (e.g. "RNG Meter", "Catacombs RNG Meters") -
                // no reward items have a "Dungeon Score:" line, so correctly render nothing.
                return;
            }
            HudElement element = HudElementRegistry.all().stream()
                    .filter(e -> e.id().equals(ELEMENT_ID)).findFirst().orElseThrow();
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            renderRanking(graphics, 0, 0, title, withUnpricedDyesFirst(RngMeterEngine.rankAll(scanned)), committedSelected);
            graphics.pose().popMatrix();
        } catch (Exception e) {
            LOGGER.error("Failed to render RNG Meter overlay", e);
        }
    }

    /**
     * Scans this frame and checks it against the previous frame's candidate for this title.
     * Commits (updates {@link #committedTitle}/{@link #committedScan}) once the same result has
     * been seen {@link #STABLE_FRAMES_REQUIRED} frames in a row, or after {@link
     * #MAX_UNSTABLE_FRAMES} as a worst-case bound so a genuinely flaky menu doesn't scan forever.
     */
    private static void advanceScanStability(AbstractContainerScreen<?> screen, String title) {
        ScanResult scanResult = scanMenu(screen);
        List<RngItem> candidate = scanResult.items();
        if (!title.equals(pendingTitle) || !candidate.equals(pendingScan)) {
            pendingTitle = title;
            pendingScan = candidate;
            pendingSelected = scanResult.selected();
            pendingStableCount = 1;
            pendingFrameCount = 1;
        } else {
            pendingSelected = scanResult.selected();
            pendingStableCount++;
            pendingFrameCount++;
        }
        if (pendingStableCount >= STABLE_FRAMES_REQUIRED || pendingFrameCount >= MAX_UNSTABLE_FRAMES) {
            String groupKey = normalizeGroupKey(title);
            Map<String, Long> toMerge = new LinkedHashMap<>();
            for (RngItem item : candidate) {
                toMerge.put(item.name(), item.pityXp());
            }
            RngItemLog.merge(groupKey, toMerge);

            committedTitle = title;
            committedScan = itemsFromLog(groupKey);
            committedSelected = pendingSelected;
            scrollOffset = 0;
            LOGGER.info("Committed RNG Meter menu scan \"{}\" (group=\"{}\"): {} rewards on this page, {} total known for this menu: {}",
                    title, groupKey, candidate.size(), committedScan.size(),
                    committedScan.stream().map(i -> i.name() + "(req=" + i.pityXp() + ")").toList());

            // Diagnostic: an item with a resolved id but still no price is either a real Bazaar/AH
            // gap (rare drop, genuinely no current listing) or an id mismatch neither the items
            // registry nor a manual AH check caught. Logging exactly which ones and their id makes
            // that distinguishable next time instead of guessing.
            List<String> unpriced = new ArrayList<>();
            for (RngItem item : committedScan) {
                if (!item.id().isEmpty() && RngMeterEngine.PRICES.getPrice(item) == null) {
                    unpriced.add(item.name() + "(id=" + item.id() + ")");
                }
            }
            if (!unpriced.isEmpty()) {
                LOGGER.warn("Menu \"{}\": {} item(s) have a resolved id but no price yet: {}",
                        groupKey, unpriced.size(), unpriced);
            }
        }
    }

    private static final Pattern PAGE_PREFIX = Pattern.compile("^\\(\\d+/\\d+\\)\\s*");

    /** Strips a leading "(1/2) " pagination marker so every page of one floor/menu shares the same log key. */
    private static String normalizeGroupKey(String title) {
        return PAGE_PREFIX.matcher(title).replaceFirst("");
    }

    /** Rebuilds a live {@link RngItem} list from every item ever logged for this menu, across all its pages. */
    private static List<RngItem> itemsFromLog(String groupKey) {
        List<RngItem> result = new ArrayList<>();
        for (Map.Entry<String, Long> e : RngItemLog.get(groupKey).entrySet()) {
            String name = e.getKey();
            long score = e.getValue();
            String id = resolveId(name);
            result.add(new RngItem("", name, id != null ? id : "", RngSource.BAZAAR, score, false));
        }
        return result;
    }

    /**
     * Called from {@link com.killer560.hub.rngmeter.mixin.AbstractContainerScreenMixin} on every
     * scroll event while a container screen is open. Scrolls the ranking (there can be far more
     * than {@link #VISIBLE_ROWS} rewards, e.g. 28 on F7) if the cursor is over the overlay's box;
     * returns false (untouched) otherwise so the normal scroll behavior isn't swallowed elsewhere.
     */
    public static boolean handleScroll(double mouseX, double mouseY, double scrollY) {
        if (!RngMeterConfig.getInstance().isEnabled()) {
            return false;
        }
        if (committedScan.size() <= VISIBLE_ROWS || scrollY == 0) {
            return false;
        }
        HudElement element = HudElementRegistry.all().stream()
                .filter(e -> e.id().equals(ELEMENT_ID)).findFirst().orElse(null);
        if (element == null) {
            return false;
        }
        int[] pos = HudElementRegistry.resolvePosition(element);
        float scale = HudElementRegistry.resolveScale(element);
        int w = Math.round(element.width() * scale);
        int h = Math.round(element.height() * scale);
        if (mouseX < pos[0] || mouseX > pos[0] + w || mouseY < pos[1] || mouseY > pos[1] + h) {
            return false;
        }
        int maxOffset = Math.max(0, committedScan.size() - VISIBLE_ROWS);
        int delta = scrollY > 0 ? -1 : 1;
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + delta));
        return true;
    }

    /** The one currently-selected reward (if any) and live progress toward it. Not persisted - selection state is a live fact, not a discovered-item fact worth remembering across sessions. */
    public record SelectedDropInfo(String name, double percent, long current, long required) {
    }

    private record ScanResult(List<RngItem> items, SelectedDropInfo selected) {
    }

    /**
     * Scans every reward slot in an open RNG Meter menu (excluding the player's own inventory,
     * always the last 36 slots) and builds a live {@link RngItem} list from each one's real name
     * and pity requirement, parsed straight from its own lore. Also picks out the selected-drop
     * info from the menu's own category icon (present on every page, unlike the actual reward
     * item, which only shows up on whichever page happens to display it) - see
     * {@link #parseSelectedInfo}.
     */
    private static ScanResult scanMenu(AbstractContainerScreen<?> screen) {
        List<RngItem> found = new ArrayList<>();
        List<String> unparsedSamples = new ArrayList<>();
        SelectedDropInfo selected = null;
        var client = Minecraft.getInstance();
        var slots = screen.getMenu().slots;
        int topSlotCount = Math.max(0, slots.size() - 36);
        int nonEmptySeen = 0;
        for (Slot slot : slots) {
            if (slot.index >= topSlotCount) {
                break;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            nonEmptySeen++;
            List<String> lore = Screen.getTooltipFromItem(client, stack).stream()
                    .map(Component::getString)
                    .toList();
            String name = cleanItemName(stack.getHoverName().getString());
            if (selected == null) {
                selected = parseSelectedInfo(lore);
            }
            Long requiredScore = parseRequiredScore(lore);
            if (requiredScore == null) {
                // Only sample items with an actual name - most GUIs pad their layout with blank
                // filler panes, which would otherwise dominate the samples and hide the one or two
                // real (but unrecognized) reward items actually worth inspecting.
                if (unparsedSamples.size() < 5 && !name.isBlank()) {
                    unparsedSamples.add(name + " lore=" + lore);
                }
                continue;
            }
            if (!isRealReward(name) || isExcludedReward(name)) {
                continue;
            }
            String id = resolveId(name);
            if (id == null) {
                LOGGER.warn("No price mapping for RNG Meter reward \"{}\" (required score {})", name, requiredScore);
            }
            found.add(new RngItem("", name, id != null ? id : "", RngSource.BAZAAR, requiredScore, false));
        }
        // Diagnostic: this menu had real items but none matched the known "Dungeon Score:"/
        // "Progress:" lore anchors - likely a different reward-menu format not seen before (e.g.
        // Experimentation Table). Logs raw lore instead of guessing at a fix blind.
        if (found.isEmpty() && nonEmptySeen > 0 && !unparsedSamples.isEmpty()) {
            LOGGER.warn("Menu had {} non-empty item(s) but none matched a known score format - samples: {}",
                    nonEmptySeen, unparsedSamples);
        }
        return new ScanResult(found, selected);
    }

    private static final Pattern PROGRESS_PERCENT_LINE = Pattern.compile("^Progress:\\s*([\\d.]+)%$");

    /**
     * If this item's lore contains a standalone "SELECTED" line (Hypixel's marker for the one
     * reward currently being pitied), pulls its live progress from the same "Progress: X%" +
     * bare-fraction lore pair {@link #parseRequiredScore} already reads pity requirements from.
     */
    /**
     * Reads selected-drop info from a "Selected Drop" lore label (the menu's own category icon
     * shows this - real dump: {@code "..., Selected Drop, Divan's Alloy, , Progress: 3.1%, ...
     * 31,000/1M, ..."}). Originally read the reward-grid item's own standalone "SELECTED" line
     * instead, but that item only exists on whichever page happens to display it - the category
     * icon that carries this "Selected Drop" text is present on every page of a multi-page menu,
     * so switching pages no longer loses the selected-item display.
     */
    private static SelectedDropInfo parseSelectedInfo(List<String> loreLines) {
        for (int i = 0; i + 1 < loreLines.size(); i++) {
            if (!loreLines.get(i).trim().equals("Selected Drop")) {
                continue;
            }
            String dropName = cleanItemName(loreLines.get(i + 1));
            for (int j = i + 1; j < loreLines.size(); j++) {
                Matcher percentMatch = PROGRESS_PERCENT_LINE.matcher(loreLines.get(j).trim());
                if (percentMatch.find() && j + 1 < loreLines.size()) {
                    Matcher fracMatch = BARE_FRACTION_LINE.matcher(loreLines.get(j + 1).trim());
                    if (fracMatch.find()) {
                        double percent = Double.parseDouble(percentMatch.group(1));
                        long current = parseAbbreviatedNumber(fracMatch.group(1));
                        long required = parseAbbreviatedNumber(fracMatch.group(2));
                        return new SelectedDropInfo(dropName, percent, current, required);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Strips any leading decorative icon glyph(s) some real items render before their name (e.g.
     * the Flawless Gemstones' leading glyph is literally U+E007, a private-use codepoint Hypixel's
     * font renders as a gem icon - not whitespace at all, so {@code String.trim()} never touched
     * it). Only strips actual private-use codepoints (guaranteed to be custom-font icon glyphs,
     * never real text) plus the printable "◆" seen on Rune items - NOT a blanket
     * "any non-letter prefix" strip, since that broke "[Lvl 1] Guardian" by eating its structural
     * leading "[".
     */
    public static String cleanItemName(String raw) {
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            if (Character.getType(cp) == Character.PRIVATE_USE || cp == '◆') {
                i += Character.charCount(cp);
            } else {
                break;
            }
        }
        return raw.substring(i).trim();
    }

    /**
     * True unless {@code name} is itself a menu-title string, e.g. "Catacombs (F1) RNG Meter" -
     * the category-picker screens' own floor/category button icons, confirmed from real logs to
     * carry a "Dungeon Score:"-style summary line in their lore too (a legitimate real line, just
     * not describing an actual reward), which is why they were showing up as phantom ranked rows.
     * No real reward item is ever named like this.
     */
    private static boolean isRealReward(String name) {
        return !name.toLowerCase(Locale.US).contains("rng meter");
    }

    // Gemstone crystals become soulbound the moment they're obtained, so they can never actually
    // be sold - showing them (priced or "no price found") is misleading either way, per killer560's
    // explicit instruction to hide them entirely rather than show them unpriced.
    private static final java.util.Set<String> SOULBOUND_EXCLUDED = java.util.Set.of(
            "Ruby Crystal", "Jasper Crystal", "Opal Crystal", "Citrine Crystal",
            "Onyx Crystal", "Peridot Crystal", "Aquamarine Crystal",
            // Per killer560: doesn't exist on the AH at all.
            "Recall Potion",
            // Per killer560: hide entirely.
            "Prehistoric Egg"
    );

    /**
     * True for items that should never appear in the ranking or tooltip at all, regardless of
     * pricing - soulbound gemstone crystals (can never be sold) and all Rune items (per killer560's
     * explicit request to hide every Rune on every meter, not just price them when possible).
     */
    private static boolean isExcludedReward(String name) {
        return SOULBOUND_EXCLUDED.contains(name) || RUNE_NAME.matcher(name).find();
    }

    private static final Pattern ENCHANTED_BOOK_NAME = Pattern.compile("^Enchanted Book \\((.+) (I|II|III|IV|V|VI|VII|VIII|IX|X)\\)$");
    private static final Pattern BARE_ENCHANT_NAME = Pattern.compile("^(.+) (I|II|III|IV|V|VI|VII|VIII|IX|X)$");
    private static final Pattern RUNE_NAME = Pattern.compile("^(.+) Rune (I|II|III|IV|V|VI|VII|VIII|IX|X)$");
    private static final java.util.Map<String, Integer> ROMAN_NUMERALS = java.util.Map.ofEntries(
            java.util.Map.entry("I", 1), java.util.Map.entry("II", 2), java.util.Map.entry("III", 3), java.util.Map.entry("IV", 4),
            java.util.Map.entry("V", 5), java.util.Map.entry("VI", 6), java.util.Map.entry("VII", 7), java.util.Map.entry("VIII", 8),
            java.util.Map.entry("IX", 9), java.util.Map.entry("X", 10)
    );

    /**
     * Resolves a live menu item's SkyBlock ID: {@link RngItemNames} first, then Hypixel's known,
     * consistent enchanted-book ID scheme ({@code ENCHANTMENT_<NAME>_<TIER>}) for "Enchanted Book
     * (X)" items - the live display name ("Enchanted Book (Rejuvenate III)") never matched
     * RngItemNames' bare enchant names ("Rejuvenate I", ported from the old per-floor table),
     * silently failing every single enchanted book's price lookup.
     *
     * <p>Several enchants (Combo, Last Stand, Soul Eater, Bank, Wisdom, No Pain No Gain, One For
     * All, Swarm - confirmed against the real live Bazaar product list, not guessed) are shown in
     * the RNG Meter's compact reward text WITHOUT their real "Ultimate " prefix - e.g. the menu
     * shows "Enchanted Book (Wisdom II)" but the only real Bazaar product is
     * {@code ENCHANTMENT_ULTIMATE_WISDOM_2}, not {@code ENCHANTMENT_WISDOM_2}. Rather than
     * hardcode that specific list (which could miss others not yet seen), this checks which of the
     * two candidate IDs actually exists as a real Bazaar product at resolve time and prefers that
     * one - falls back to the bare id (existing behavior) if neither is known yet, e.g. before the
     * first price refresh completes.
     */
    private static String resolveId(String name) {
        String id = RngItemNames.BY_NAME.get(name);
        if (id != null) {
            return id;
        }
        // Runes must be checked before the enchant patterns below - "Snake Rune I" ends in a
        // roman numeral just like an enchant name does, and was being wrongly resolved to a
        // bogus "ENCHANTMENT_SNAKE_RUNE_1" id before this. Real rune AH auctions all share the
        // generic id "RUNE" with the type/level in a separate NBT field (see
        // HypixelMarketPrices.extractItemId) - synthesizes the matching "RUNE_<TYPE>_<LEVEL>" id,
        // confirmed against real data (e.g. "Endersnake Rune I" -> a real auction's rune type is
        // literally "ENDERSNAKE").
        Matcher rune = RUNE_NAME.matcher(name);
        if (rune.find()) {
            Integer tier = ROMAN_NUMERALS.get(rune.group(2));
            if (tier == null) {
                return null;
            }
            String runeType = rune.group(1).toUpperCase(Locale.US).replace(" ", "_").replace("'", "");
            return "RUNE_" + runeType + "_" + tier;
        }
        Matcher wrapped = ENCHANTED_BOOK_NAME.matcher(name);
        if (wrapped.find()) {
            return resolveEnchantId(wrapped.group(1), wrapped.group(2));
        }
        // Experimentation Table shows most enchant rewards bare, with no "Enchanted Book (...)"
        // wrapper at all (e.g. "Cleave VI", "Critical VII") - confirmed from a real lore dump, not
        // guessed. Safe as a last-resort fallback since it only ever runs on names already
        // confirmed to be real menu rewards (isRealReward already passed) that matched nothing
        // else; a wrong guess here just leaves the item unpriced, same as before this fallback.
        Matcher bare = BARE_ENCHANT_NAME.matcher(name);
        if (bare.find()) {
            return resolveEnchantId(bare.group(1), bare.group(2));
        }
        return null;
    }

    private static String resolveEnchantId(String enchantNameRaw, String romanTier) {
        Integer tier = ROMAN_NUMERALS.get(romanTier);
        if (tier == null) {
            return null;
        }
        // Hyphens (e.g. "Triple-Strike") must become underscores too, not just spaces - a real
        // Bazaar product is ENCHANTMENT_TRIPLE_STRIKE_5, and the un-replaced hyphen was building
        // ENCHANTMENT_TRIPLE-STRIKE_5, which doesn't exist.
        String enchantName = enchantNameRaw.toUpperCase(Locale.US).replace(" ", "_").replace("-", "_").replace("'", "");
        String bareId = "ENCHANTMENT_" + enchantName + "_" + tier;
        String ultimateId = "ENCHANTMENT_ULTIMATE_" + enchantName + "_" + tier;
        if (!RngMeterEngine.PRICES.hasBazaarProduct(bareId) && RngMeterEngine.PRICES.hasBazaarProduct(ultimateId)) {
            return ultimateId;
        }
        return bareId;
    }

    /**
     * Parses an item's real pity requirement directly out of its lore: either a
     * {@code "Dungeon Score: 5,000/2,105"} line (denominator = requirement), or - for the
     * currently-selected item - a {@code "Progress: X%"} line immediately followed by a bare
     * fraction line like {@code "5,000/8k"}. Returns null for items with neither (filler panes,
     * the meter's own info item, or - safely - any unrelated item like the player's own gear,
     * since those anchors don't appear in normal item lore).
     */
    public static Long parseRequiredScore(List<String> loreLines) {
        for (int i = 0; i < loreLines.size(); i++) {
            String line = loreLines.get(i);
            Matcher scoreMatch = DUNGEON_SCORE_LINE.matcher(line);
            if (scoreMatch.find()) {
                return parseAbbreviatedNumber(scoreMatch.group(1));
            }
            if (PROGRESS_LABEL_LINE.matcher(line.trim()).find() && i + 1 < loreLines.size()) {
                Matcher fracMatch = BARE_FRACTION_LINE.matcher(loreLines.get(i + 1).trim());
                if (fracMatch.find()) {
                    return parseAbbreviatedNumber(fracMatch.group(2));
                }
            }
        }
        return null;
    }

    private static long parseAbbreviatedNumber(String raw) {
        String cleaned = raw.replace(",", "");
        if (cleaned.endsWith("k") || cleaned.endsWith("K")) {
            return Math.round(Double.parseDouble(cleaned.substring(0, cleaned.length() - 1)) * 1_000);
        }
        if (cleaned.endsWith("m") || cleaned.endsWith("M")) {
            return Math.round(Double.parseDouble(cleaned.substring(0, cleaned.length() - 1)) * 1_000_000);
        }
        return Math.round(Double.parseDouble(cleaned));
    }

    /**
     * Builds the "coins/pt" tooltip line for one hovered reward item, given its display name and
     * the pity requirement already parsed from its own lore by the caller (see
     * {@link com.killer560.hub.rngmeter.mixin.AbstractContainerScreenMixin}). Returns null only if
     * this couldn't be a reward item at all; an unpriced or unmapped item still gets a line so it's
     * visibly flagged rather than silently missing.
     */
    public static String buildProfitLine(String name, long requiredScore) {
        if (!isRealReward(name) || isExcludedReward(name)) {
            return null;
        }
        String id = resolveId(name);
        if (id == null) {
            return "§c§lKiller560's Mod: §cUnknown item (no price mapping yet)";
        }
        Long price = RngMeterEngine.PRICES.getPrice(new RngItem("", name, id, RngSource.BAZAAR, requiredScore, false));
        if (price == null) {
            return "§c§lKiller560's Mod: §cNo price found";
        }
        double cpp = price / (double) requiredScore;
        return String.format(Locale.US, "§b§lKiller560's Mod: §b%,.1f coins/pt", cpp);
    }

    private static void renderRanking(GuiGraphicsExtractor graphics, int x, int y, String title, List<RngMeterEngine.RankedItem> rows, SelectedDropInfo selected) {
        var font = Minecraft.getInstance().font;
        int cursorY = y;

        String header = "§9§lRNG Meter";
        if (rows.size() > VISIBLE_ROWS) {
            int from = Math.min(scrollOffset, Math.max(0, rows.size() - 1)) + 1;
            int to = Math.min(rows.size(), from + VISIBLE_ROWS - 1);
            header += String.format(Locale.US, " §7(%d-%d of %d, scroll for more)", from, to, rows.size());
        }
        graphics.text(font, header, x, cursorY, 0xFFFFFFFF);
        cursorY += 12;

        if (selected != null) {
            String selectedLine = String.format(Locale.US, "§dSelected: %s §7(%.1f%%, %,d/%,d)",
                    selected.name(), selected.percent(), selected.current(), selected.required());
            graphics.text(font, selectedLine, x, cursorY, 0xFFFFFFFF);
            cursorY += 11;
        }

        HypixelMarketPrices prices = RngMeterEngine.PRICES;
        if (prices.isRefreshing()) {
            graphics.text(font, "§7Fetching live prices...", x, cursorY, 0xFFAAAAAA);
            cursorY += 11;
        } else if (prices.getLastRefreshedAtMs() == 0) {
            graphics.text(font, "§7No price data yet.", x, cursorY, 0xFFAAAAAA);
            cursorY += 11;
        }

        int start = Math.min(scrollOffset, Math.max(0, rows.size() - 1));
        int shown = 0;
        for (int i = start; i < rows.size() && shown < VISIBLE_ROWS; i++) {
            RngMeterEngine.RankedItem r = rows.get(i);
            String line;
            if (r.price() == null && isDye(r.item().name())) {
                // Dyes with zero current AH listings are flagged distinctly rather than just
                // shown red like a generic pricing gap - per killer560, this is common enough for
                // rare dyes that it's worth calling out specifically rather than looking broken.
                line = String.format(Locale.US, "§6%s: 0 listings", r.item().name());
            } else {
                String color = r.rank() == 1 ? "§a" : r.rank() == 0 ? "§c" : "§f";
                String cpp = r.coinsPerPity() != null ? String.format(Locale.US, "%,.1f", r.coinsPerPity()) : "N/A";
                line = String.format(Locale.US, "%s%s: %s coins/pt", color, r.item().name(), cpp);
            }
            graphics.text(font, line, x, cursorY, 0xFFFFFFFF);
            cursorY += 10;
            shown++;
        }
    }

    /** Reward names in this project are always literally "<Color> Dye". */
    private static boolean isDye(String name) {
        return name.endsWith(" Dye");
    }

    /**
     * Moves any dye with zero current AH listings to the very front of the ranking, ahead of
     * everything else regardless of price - per killer560's explicit request, so a rare dye isn't
     * buried at the bottom of the "no price" group where it looks the same as a normal pricing
     * gap. A dye that does have a price is left in its normal price-ranked position untouched.
     */
    private static List<RngMeterEngine.RankedItem> withUnpricedDyesFirst(List<RngMeterEngine.RankedItem> rows) {
        List<RngMeterEngine.RankedItem> unpricedDyes = new ArrayList<>();
        List<RngMeterEngine.RankedItem> rest = new ArrayList<>();
        for (RngMeterEngine.RankedItem r : rows) {
            if (r.price() == null && isDye(r.item().name())) {
                unpricedDyes.add(r);
            } else {
                rest.add(r);
            }
        }
        if (unpricedDyes.isEmpty()) {
            return rows;
        }
        List<RngMeterEngine.RankedItem> reordered = new ArrayList<>(unpricedDyes);
        reordered.addAll(rest);
        return reordered;
    }
}
