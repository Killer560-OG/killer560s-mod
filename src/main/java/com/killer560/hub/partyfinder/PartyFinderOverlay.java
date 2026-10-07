package com.killer560.hub.partyfinder;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.JsonObject;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig.CompactMode;
import com.killer560.hub.partyfinder.PartyFinderParser.Party;
import com.killer560.hub.partyfinder.PartyFinderParser.Status;
import com.killer560.hub.partyfinder.PartyFinderStatsApi.PlayerStats;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ChatColors;

/**
 * Party Finder Overlay - a port of Devonian's Party Finder GUI features:
 * <ul>
 * <li><b>Highlight</b> ({@code PartyFinderHighlight.kt}): fills each party head green when you can join it and red when
 * you can't (low Catacombs level, low class level, previous floor not completed, or your selected class already in
 * the party). The first three reasons except "previous floor" can be ignored individually.
 * <li><b>Member Count</b> ({@code PartyFinderCount.kt}): the party's member count drawn on its head.
 * <li><b>Tooltip Stats</b> ({@code PartyFinderOverview.kt}): each member line gains Catacombs level, secrets, average
 * secrets and their S/S+ personal best for the party's floor (data from {@link PartyFinderStatsApi}), optionally in a
 * compact or custom format, plus a "Missing:" line listing the classes nobody has picked (yours in green).
 * </ul>
 * Your selected class comes from the Catacombs Gate menu ("Currently Selected: X", as Devonian) or the
 * "You have selected the X Dungeon Class!" chat line.
 * <p>
 * Custom style placeholders (Devonian's {@code /dv pfo help}): {@code $RoleColor $RoleSingle $RoleShort $Role
 * $RoleName $NameColor $Name $RoleLevel $Cata $Secrets $SecretsShort $SecretAvg $SecretShortAvg $PB}; '&amp;' is a
 * colour code prefix. Style 1 as a custom string:
 * {@code &8[$RoleColor$RoleSingle&8] $NameColor$Name &8[&e$RoleLevel &7| &6$Cata&8] &8[&3$SecretsShort &7| &b$SecretShortAvg&8] &8[$PB&8]}
 */
public final class PartyFinderOverlay {

    private static final int SCAN_SLOTS = 46; // Devonian: container indices 0..45
    private static final int REQUEST_INTERVAL_TICKS = 20;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\w+)");

    private static DungeonClass currentRole;

    private static AbstractContainerScreen<?> scannedScreen;
    private static final ItemStack[] scannedStacks = new ItemStack[SCAN_SLOTS];
    private static final Party[] parties = new Party[SCAN_SLOTS];
    private static DungeonClass scannedRole;
    private static int ticksUntilRequest = 0;
    /** killer560 9.1: set once per screen-open (see {@link #tick}) rather than read every tick - it means a
     *  file read, and it can't change while the menu is already open. See {@link #rewriteTooltip} and
     *  {@link com.killer560.hub.interop.DetectedMods#isDevonianPartyFinderOverviewOn}. */
    private static boolean devonianConflict = false;

    private PartyFinderOverlay() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PartyFinderOverlay.tick", PartyFinderOverlay::tick));
        ChatObserver.subscribe(message -> {
            Matcher m = PartyFinderParser.CHAT_CLASS_SELECTED.matcher(ChatObserver.strip(message).trim());
            if (m.matches()) {
                currentRole = DungeonClass.from(m.group(1));
            }
        });
    }

    // ------------------------------------------------------------------------------------------ scanning

    private static void tick(Minecraft client) {
        if (client.player == null) {
            currentRole = null;
            clearScan();
            return;
        }
        PartyFinderOverlayConfig cfg = PartyFinderOverlayConfig.getInstance();
        if (!cfg.isEnabled() || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            clearScan();
            return;
        }
        String title = screen.getTitle().getString();
        if (title.equals(PartyFinderParser.CATACOMBS_GATE_TITLE)) {
            if (screen.getMenu().slots.size() > PartyFinderParser.GATE_CLASS_SLOT) {
                DungeonClass selected = PartyFinderParser.selectedClass(
                        screen.getMenu().getSlot(PartyFinderParser.GATE_CLASS_SLOT).getItem());
                if (selected != null) {
                    currentRole = selected;
                }
            }
            clearScan();
            return;
        }
        if (!title.equals(PartyFinderParser.PARTY_FINDER_TITLE)) {
            clearScan();
            return;
        }
        if (screen != scannedScreen || scannedRole != currentRole) {
            clearScan();
            scannedScreen = screen;
            scannedRole = currentRole;
            devonianConflict = DetectedMods.isDevonianPartyFinderOverviewOn();
        }

        boolean changed = false;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == client.player.getInventory() || slot.index < 0 || slot.index >= SCAN_SLOTS) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == scannedStacks[slot.index]) {
                continue;
            }
            scannedStacks[slot.index] = stack;
            parties[slot.index] = PartyFinderParser.parse(slot.index, stack, currentRole);
            changed = true;
        }

        // killer560 9.1: skip when devonianConflict - Devonian has already overwritten every member line with
        // its own overview text, so PartyFinderParser can't read a single real name out of it (see the
        // USER_ROLE comment); queuing names nobody will ever match to a rendered line just burns the stats
        // lookups for nothing every time this screen is open.
        if (cfg.isTooltip() && !devonianConflict && (changed || --ticksUntilRequest <= 0)) {
            ticksUntilRequest = REQUEST_INTERVAL_TICKS;
            Set<String> names = new LinkedHashSet<>();
            for (Party party : parties) {
                if (party != null) {
                    party.members().forEach(m -> names.add(m.name()));
                }
            }
            if (!names.isEmpty()) {
                PartyFinderStatsApi.request(names);
            }
        }
    }

    private static void clearScan() {
        if (scannedScreen == null) {
            return;
        }
        scannedScreen = null;
        scannedRole = null;
        devonianConflict = false;
        Arrays.fill(scannedStacks, null);
        Arrays.fill(parties, null);
        ticksUntilRequest = 0;
    }

    private static Party partyAt(AbstractContainerScreen<?> screen, Slot slot) {
        if (screen != scannedScreen || slot.index < 0 || slot.index >= SCAN_SLOTS) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && slot.container == client.player.getInventory()) {
            return null;
        }
        return parties[slot.index];
    }

    // ------------------------------------------------------------------------------------------ rendering

    /** Highlight: called right before the slot's item is drawn. */
    public static void drawSlotBackground(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        PartyFinderOverlayConfig cfg = PartyFinderOverlayConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHighlight()) {
            return;
        }
        Party party = partyAt(screen, slot);
        if (party == null) {
            return;
        }
        EnumSet<Status> blockers = EnumSet.noneOf(Status.class);
        blockers.addAll(party.blockers());
        if (cfg.isIgnoreCataRequirement()) {
            blockers.remove(Status.LOW_CATA);
        }
        if (cfg.isIgnoreRoleLevel()) {
            blockers.remove(Status.LOW_ROLE);
        }
        if (cfg.isIgnoreOwnRole()) {
            blockers.remove(Status.DUPE_CLASS);
        }
        int color = blockers.isEmpty() ? cfg.getJoinableColor() : cfg.getBlockedColor();
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
    }

    /** Member Count: called after the slot's item is drawn. */
    public static void drawSlotForeground(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        PartyFinderOverlayConfig cfg = PartyFinderOverlayConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isMemberCount()) {
            return;
        }
        Party party = partyAt(screen, slot);
        if (party == null) {
            return;
        }
        graphics.centeredText(Minecraft.getInstance().font, String.valueOf(party.members().size()),
                slot.x + 14, slot.y + 8, cfg.getCountColor());
    }

    // ------------------------------------------------------------------------------------------ tooltip

    /** @return the rewritten tooltip for a Party Finder party head, or null to leave it unchanged. */
    public static List<Component> rewriteTooltip(AbstractContainerScreen<?> screen, ItemStack stack, List<Component> lines) {
        PartyFinderOverlayConfig cfg = PartyFinderOverlayConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isTooltip() || lines == null || screen != scannedScreen) {
            return null;
        }
        Party party = PartyFinderParser.parse(-1, stack, currentRole);
        if (party == null) {
            return null;
        }

        List<Component> out = new ArrayList<>(lines.size() + 3);
        if (devonianConflict) {
            // killer560 9.1: the join-once chat warning (ModConflictWarnings) is easy to miss or scroll past,
            // and the menu stays broken for the whole session either way - putting it on every head's own
            // tooltip means the player actually sees why PBs/secrets never show up, every time they look.
            out.add(literal("&c⚠ Devonian's Party Finder Overview is on"));
            out.add(literal("&7It overwrites this menu, so our stats can't show - turn one off."));
        }
        List<String> withoutStats = new ArrayList<>();
        out.addAll(styleLines(cfg, party, lines, PartyFinderStatsApi::get, currentRole, withoutStats));
        if (!withoutStats.isEmpty()) {
            out.add(literal(statsStatusLine(withoutStats)));
        }
        return out;
    }

    /**
     * The one place a Party Finder tooltip's lines are styled - the real tooltip ({@link #rewriteTooltip}) and the
     * settings-tab preview ({@link #renderPreviewLines}) both come through here, so the preview cannot show a style
     * the real menu does not.
     *
     * <p>killer560 2026-10-07, "does not show their style like the preview says it will": member lines used to be
     * styled only once {@link PartyFinderStatsApi} had stats for that player, and left exactly as Hypixel sent them
     * otherwise. The preview always has stats (fixed sample data), so it always showed the style. On Hypixel the
     * stats service (api.docilelm.top/v2/dungeons) answers {@code {"result":{}}} for every name - checked by hand
     * the same day with real dungeon players - so no member line was ever styled (the stats now come from the Profile
     * Viewer's backend instead, see {@link PartyFinderStatsApi}). A member line is now always in
     * the chosen style, with {@code ?} for whatever stats have not arrived; {@code withoutStats} collects those
     * names so the caller can say why.
     *
     * @param statsFor      stats for a player name, or null when there are none (yet)
     * @param withoutStats  receives every member name rendered without stats; may be null
     */
    static List<Component> styleLines(PartyFinderOverlayConfig cfg, Party party, List<Component> lines,
                                      Function<String, PlayerStats> statsFor, DungeonClass role,
                                      List<String> withoutStats) {
        List<Component> out = new ArrayList<>(lines.size() + 1);
        boolean missingAdded = false;
        for (Component line : lines) {
            String text = line.getString();
            if (text.contains("Click to join!") || text.contains("Requires ")) {
                // A full party has nothing to list - an empty "Missing:" line was all it got.
                if (cfg.isShowMissing() && !missingAdded && !party.missing().isEmpty()) {
                    out.add(literal(missingLine(party, role)));
                    missingAdded = true;
                }
                out.add(line);
                continue;
            }
            if (text.contains("Missing: ")) {
                continue;
            }
            Matcher m = PartyFinderParser.USER_ROLE.matcher(text);
            if (!m.find()) {
                out.add(line);
                continue;
            }
            PlayerStats stats = statsFor.apply(m.group(1));
            if (stats == null && withoutStats != null) {
                withoutStats.add(m.group(1));
            }
            out.add(memberLine(cfg, party, line, m.group(1), DungeonClass.from(m.group(2)), m.group(3), stats));
        }
        return out;
    }

    /** Why some member lines show {@code ?}: still loading, or the lookup failed - naming the source that failed
     *  ("SkyBlockPV backend: HTTP 500 ...", "Mojang name lookup: ..."), so "?" never reads as a mod bug. */
    private static String statsStatusLine(List<String> withoutStats) {
        for (String name : withoutStats) {
            if (PartyFinderStatsApi.hasFailed(name)) {
                String why = PartyFinderStatsApi.failureReason(name);
                return "&8? = no stats for " + name + (why == null ? "" : " - " + why);
            }
        }
        return "&8? = loading stats...";
    }

    private static String missingLine(Party party, DungeonClass currentRole) {
        StringBuilder sb = new StringBuilder("&eMissing: ");
        for (int i = 0; i < party.missing().size(); i++) {
            DungeonClass c = party.missing().get(i);
            if (i > 0) {
                sb.append("&7, ");
            }
            sb.append(c == currentRole ? "&a" : "&7").append(c.displayName);
        }
        return sb.toString();
    }

    /** One member line in the chosen style. {@code stats} may be null: every stat then reads {@code ?} (never
     *  "NO PB", which would claim something we do not know). */
    private static Component memberLine(PartyFinderOverlayConfig cfg, Party party, Component original, String name,
                                        DungeonClass role, String roleLevel, PlayerStats stats) {
        boolean known = stats != null;
        String[] pb = known ? personalBest(cfg, party, stats) : new String[]{null, null};
        String pbTime = pb[0];
        String pbType = pb[1];
        // The PB as each style shows it: "&a4:12" (S+ or S), "&74:12" (Both, a clear below S - no S or S+ on this
        // floor), "&cNO PB" (never cleared it), or "&7?" while unknown.
        String pbCell = !known ? "&7?" : pbTime == null ? "&cNO PB" : (ANY.equals(pbType) ? "&7" : "&a") + pbTime;
        String nameColor = role.colorCode;
        if (cfg.isRankNameColors()) {
            String rank = rankColorCode(original, name);
            if (rank != null) {
                nameColor = rank;
            }
        }
        String cata = known ? String.valueOf((int) stats.level()) : "?";
        String secrets = known ? String.valueOf(stats.secrets()) : "?";
        String avg1 = known ? String.format(Locale.US, "%.1f", stats.averageSecrets()) : "?";
        String avg2 = known ? String.format(Locale.US, "%.2f", stats.averageSecrets()) : "?";
        String secretsShort = known ? shortenNumber(stats.secrets()) : "?";

        CompactMode mode = cfg.getCompactMode();
        return switch (mode) {
            case STYLE1 -> literal("&8[" + role.colorCode + role.letter + "&8] " + nameColor + name
                    + " &8[&e" + roleLevel + " &7| &6" + cata + "&8] &8[&3" + secretsShort + " &7| &b" + avg1 + "&8]"
                    + " &8[" + pbCell + "&8]");
            case STYLE2 -> literal("&8[" + role.colorCode + role.letter + " &e" + roleLevel + "&8] " + nameColor + name
                    + " &8[&6" + cata + " &7| &3" + secretsShort + " &7| &b" + avg1 + "&8]"
                    + " " + pbCell);
            case CUSTOM -> {
                Map<String, String> keys = new HashMap<>();
                keys.put("RoleColor", role.colorCode);
                keys.put("RoleSingle", String.valueOf(role.letter));
                keys.put("RoleShort", role.shortName);
                keys.put("Role", role.displayName);
                keys.put("RoleName", role.displayName);
                keys.put("NameColor", nameColor);
                keys.put("Name", name);
                keys.put("RoleLevel", roleLevel);
                keys.put("Cata", cata);
                keys.put("Secrets", secrets);
                keys.put("SecretsShort", secretsShort);
                keys.put("SecretAvg", avg2);
                keys.put("SecretShortAvg", avg1);
                keys.put("PB", pbCell);
                Matcher pm = PLACEHOLDER.matcher(cfg.getCustomStyle());
                StringBuilder sb = new StringBuilder();
                while (pm.find()) {
                    String value = keys.get(pm.group(1));
                    pm.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : pm.group()));
                }
                pm.appendTail(sb);
                yield literal(sb.toString());
            }
            case NONE -> {
                // killer560 7.2 "the current style does not work": NONE is the default/unselected style, and
                // it printed the raw fractional Cata level from the API ("45.87362...") instead of the whole
                // number Style 1/2 already cast to int - looked broken on every single party head. CUSTOM's
                // $Cata had the exact same bug (String.valueOf(stats.level()) below).
                String suffix = " &8(&6" + cata + "&8) &8[&3"
                        + (known ? NumberFormat.getNumberInstance(Locale.US).format(stats.secrets()) : "?")
                        + " &7| &b" + avg2 + "&8]";
                if (known && pbTime != null && cfg.getPbMode() == PartyFinderOverlayConfig.PbMode.BOTH) {
                    suffix += " &8[" + (ANY.equals(pbType) ? "&7" : "&a") + pbType + " " + pbTime + "&8]";
                } else {
                    suffix += " &8[" + pbCell + "&8]";
                }
                yield original.copy().append(literal(suffix));
            }
        };
    }

    /** The PB label for a clear below S (Hypixel's {@code fastest_time}), shown only in Both mode. */
    static final String ANY = "Any";

    /**
     * @return {time or null, "S"/"S+"/"Any"} for the party's floor, per the PB mode. Both is S+, then S, then the
     * floor's fastest clear at any score - real players with runs on a floor but no S on it otherwise read "NO PB",
     * which claims they never cleared it (killer560, 2026-10-07: "the vast majority still do not have any times").
     */
    private static String[] personalBest(PartyFinderOverlayConfig cfg, Party party, PlayerStats stats) {
        JsonObject map = party.masterMode() ? stats.pbMaster() : stats.pbNormal();
        String floorKey = "floor_" + party.floor();
        String s = ConfigJson.getString(ConfigJson.getObject(map, "s"), floorKey, null);
        String sPlus = ConfigJson.getString(ConfigJson.getObject(map, "s_plus"), floorKey, null);
        String any = ConfigJson.getString(ConfigJson.getObject(map, "any"), floorKey, null);
        return switch (cfg.getPbMode()) {
            case S -> new String[]{s, "S"};
            case S_PLUS -> new String[]{sPlus, "S+"};
            case BOTH -> sPlus != null ? new String[]{sPlus, "S+"} : s != null ? new String[]{s, "S"} : new String[]{any, ANY};
        };
    }

    /** The legacy colour code ("&b") of the styled segment holding {@code name} (the player's rank colour). */
    private static String rankColorCode(Component line, String name) {
        Optional<String> code = line.visit((style, text) -> {
            if (!text.contains(name)) {
                return Optional.empty();
            }
            TextColor color = style.getColor();
            if (color == null) {
                return Optional.empty();
            }
            for (ChatFormatting f : ChatFormatting.values()) {
                if (ChatColors.isColor(f) && ChatColors.RGB[f.ordinal()] == color.getValue()) {
                    return Optional.of("&" + ChatColors.code(f));
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return code.orElse(null);
    }

    /** Devonian {@code StringUtils.shortenNumber}. */
    static String shortenNumber(int num) {
        if (num < 0) {
            return "-" + shortenNumber(-num);
        }
        if (num < 1000) {
            return Integer.toString(num);
        }
        double n = num / 1000.0;
        if (n < 10.0) return String.format(Locale.US, "%.2fK", n);
        if (n < 100.0) return String.format(Locale.US, "%.1fK", n);
        if (n < 1000.0) return String.format(Locale.US, "%.0fK", n);
        n /= 1000.0;
        if (n < 10.0) return String.format(Locale.US, "%.2fM", n);
        if (n < 100.0) return String.format(Locale.US, "%.1fM", n);
        if (n < 1000.0) return String.format(Locale.US, "%.0fM", n);
        return String.format(Locale.US, "%.2fB", n / 1000.0);
    }

    private static Component literal(String text) {
        return Component.literal(text.replace('&', '§'));
    }

    /** Selected dungeon class as last seen, or null. */
    public static DungeonClass getCurrentRole() {
        return currentRole;
    }

    // ------------------------------------------------------------------------------------------ settings-tab preview

    /** killer560 7.2: "live preview of the selected style using killer560, aut0balls, agreencatgirl,
     *  femboy_recruiter, latinomommy at cata/class 50/45/40/35/30". Fixed sample data, one per dungeon class,
     *  spread across a floor 7 Master Mode party (a mix of S/S+/no PB so the preview shows every PB branch).
     *  Their lore goes through the same {@link PartyFinderParser} and {@link #styleLines} as a real head's (see
     *  {@link #renderPreviewLines}). Sharing only {@link #memberLine} was not enough: the real path skipped it
     *  whenever stats were missing, which is how the preview showed a style the menu never did (2026-10-07).
     *  <p>
     *  killer560 9.1: "make it so each of them but agreencatgirl have a pb that is something funny. Do the
     *  same for secret averages. Make agreencatgirl's a negative though." aut0balls is the Healer, killer560
     *  the Tank, agreencatgirl the Archer and latinomommy the Mage (femboy_recruiter keeps its Berserk/NO PB
     *  slot untouched - he wasn't named). The joke PBs/averages are meme numbers (69, 13:37, 4:20) that could
     *  never happen on a real floor 7 run; agreencatgirl's are negated so her row reads {@code -4:20} /
     *  {@code -42.00} as the joke the request asked for. */
    private record PreviewPlayer(String name, DungeonClass role, int level, PlayerStats stats) {
    }

    private static JsonObject pbEntry(String s, String sPlus) {
        if (s == null && sPlus == null) {
            return null;
        }
        JsonObject root = new JsonObject();
        if (s != null) {
            JsonObject sObj = new JsonObject();
            sObj.addProperty("floor_7", s);
            root.add("s", sObj);
        }
        if (sPlus != null) {
            JsonObject spObj = new JsonObject();
            spObj.addProperty("floor_7", sPlus);
            root.add("s_plus", spObj);
        }
        return root;
    }

    private static PlayerStats previewStats(double level, int secrets, double avg, JsonObject pbMaster) {
        return new PlayerStats(level, secrets, avg, pbMaster, pbMaster, System.currentTimeMillis());
    }

    private static final List<PreviewPlayer> PREVIEW_PLAYERS = List.of(
            new PreviewPlayer("killer560", DungeonClass.TANK, 50,
                    previewStats(50, 210_000, 133.7, pbEntry(null, "13:37"))),
            new PreviewPlayer("aut0balls", DungeonClass.HEALER, 45,
                    previewStats(45, 150_000, 69.0, pbEntry(null, "6:09"))),
            new PreviewPlayer("agreencatgirl", DungeonClass.ARCHER, 40,
                    previewStats(40, 95_000, -42.0, pbEntry(null, "-4:20"))),
            new PreviewPlayer("femboy_recruiter", DungeonClass.BERSERK, 35,
                    previewStats(35, 52_000, 39.7, pbEntry(null, null))),
            new PreviewPlayer("latinomommy", DungeonClass.MAGE, 30,
                    previewStats(30, 21_000, 42.0, pbEntry(null, "4:20"))));

    /** Rank colours for the sample names, so Rank Name Colors visibly does something in the preview too. */
    private static final ChatFormatting[] PREVIEW_RANKS = {
            ChatFormatting.GOLD, ChatFormatting.AQUA, ChatFormatting.GREEN, ChatFormatting.GRAY, ChatFormatting.AQUA};

    /** Number of sample rows {@link #renderPreviewLines} returns - lets the settings tab size its preview box
     *  without hard-coding the sample roster size a second time. */
    public static final int PREVIEW_LINE_COUNT = PREVIEW_PLAYERS.size();

    /** The sample party's member lines as Hypixel sends them on a Party Finder head: " Name: Class (level)",
     *  the name in its rank colour. */
    public static List<Component> previewMemberLore() {
        List<Component> out = new ArrayList<>(PREVIEW_PLAYERS.size());
        for (int i = 0; i < PREVIEW_PLAYERS.size(); i++) {
            PreviewPlayer p = PREVIEW_PLAYERS.get(i);
            out.add(Component.literal(" ")
                    .append(Component.literal(p.name()).withStyle(PREVIEW_RANKS[i % PREVIEW_RANKS.length]))
                    .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(p.role().displayName + " ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("(").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(String.valueOf(p.level())).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(")").withStyle(ChatFormatting.AQUA)));
        }
        return out;
    }

    /** Stats for a sample name, or null. */
    private static PlayerStats previewStatsFor(String name) {
        for (PreviewPlayer p : PREVIEW_PLAYERS) {
            if (p.name().equalsIgnoreCase(name)) {
                return p.stats();
            }
        }
        return null;
    }

    /** @return the fixed sample roster rendered with {@code cfg}'s current style/PB mode/rank colors/custom
     *  style - call fresh every frame (it's cheap) so a settings-tab preview widget tracks live edits with no
     *  rebuild needed. It is a real Party Finder head's lore (a floor 7 Master Mode party) put through the same
     *  {@link PartyFinderParser} and {@link #styleLines} as the real tooltip; only the stats source differs. */
    public static List<Component> renderPreviewLines(PartyFinderOverlayConfig cfg) {
        List<Component> members = previewMemberLore();
        List<String> lore = new ArrayList<>(members.size() + 2);
        lore.add("Dungeon: Master Mode The Catacombs");
        lore.add("Floor: Floor VII");
        members.forEach(c -> lore.add(c.getString()));
        Party party = PartyFinderParser.parse(-1, lore, null);
        return styleLines(cfg, party, members, PartyFinderOverlay::previewStatsFor, null, null);
    }
}
