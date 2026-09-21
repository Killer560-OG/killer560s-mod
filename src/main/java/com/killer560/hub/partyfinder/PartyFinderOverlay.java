package com.killer560.hub.partyfinder;

import com.google.gson.JsonObject;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private PartyFinderOverlay() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PartyFinderOverlay::tick);
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
        if (!cfg.isEnabled() || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
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

        if (cfg.isTooltip() && (changed || --ticksUntilRequest <= 0)) {
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

        List<Component> out = new ArrayList<>(lines.size() + 1);
        boolean missingAdded = false;
        for (Component line : lines) {
            String text = line.getString();
            if (text.contains("Click to join!") || text.contains("Requires ")) {
                if (cfg.isShowMissing() && !missingAdded) {
                    out.add(literal(missingLine(party)));
                    missingAdded = true;
                }
                out.add(line);
                continue;
            }
            if (text.contains("Missing: ")) {
                continue;
            }
            Matcher m = PartyFinderParser.USER_ROLE.matcher(text);
            PlayerStats stats = m.matches() ? PartyFinderStatsApi.get(m.group(1)) : null;
            if (stats == null) {
                out.add(line);
                continue;
            }
            out.add(memberLine(cfg, party, line, m.group(1), DungeonClass.from(m.group(2)), m.group(3), stats));
        }
        return out;
    }

    private static String missingLine(Party party) {
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

    private static Component memberLine(PartyFinderOverlayConfig cfg, Party party, Component original, String name,
                                        DungeonClass role, String roleLevel, PlayerStats stats) {
        String[] pb = personalBest(cfg, party, stats);
        String pbTime = pb[0];
        String pbType = pb[1];
        String nameColor = role.colorCode;
        if (cfg.isRankNameColors()) {
            String rank = rankColorCode(original, name);
            if (rank != null) {
                nameColor = rank;
            }
        }
        String avg1 = String.format(Locale.US, "%.1f", stats.averageSecrets());
        String avg2 = String.format(Locale.US, "%.2f", stats.averageSecrets());
        String secretsShort = shortenNumber(stats.secrets());

        CompactMode mode = cfg.getCompactMode();
        return switch (mode) {
            case STYLE1 -> literal("&8[" + role.colorCode + role.letter + "&8] " + nameColor + name
                    + " &8[&e" + roleLevel + " &7| &6" + (int) stats.level() + "&8] &8[&3" + secretsShort + " &7| &b" + avg1 + "&8]"
                    + (pbTime == null ? " &8[&cNO PB&8]" : " &8[&a" + pbTime + "&8]"));
            case STYLE2 -> literal("&8[" + role.colorCode + role.letter + " &e" + roleLevel + "&8] " + nameColor + name
                    + " &8[&6" + (int) stats.level() + " &7| &3" + secretsShort + " &7| &b" + avg1 + "&8]"
                    + (pbTime == null ? " &cNO PB" : " &a" + pbTime));
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
                keys.put("Cata", String.valueOf((int) stats.level()));
                keys.put("Secrets", String.valueOf(stats.secrets()));
                keys.put("SecretsShort", secretsShort);
                keys.put("SecretAvg", avg2);
                keys.put("SecretShortAvg", avg1);
                keys.put("PB", pbTime == null ? "&cNO PB" : "&a" + pbTime);
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
                String suffix = " &8(&6" + (int) stats.level() + "&8) &8[&3"
                        + NumberFormat.getNumberInstance(Locale.US).format(stats.secrets()) + " &7| &b" + avg2 + "&8]";
                if (pbTime == null) {
                    suffix += " &8[&cNO PB&8]";
                } else if (cfg.getPbMode() == PartyFinderOverlayConfig.PbMode.BOTH) {
                    suffix += " &8[&a" + pbType + " " + pbTime + "&8]";
                } else {
                    suffix += " &8[&a" + pbTime + "&8]";
                }
                yield original.copy().append(literal(suffix));
            }
        };
    }

    /** @return {time or null, "S"/"S+"} for the party's floor, per the PB mode. */
    private static String[] personalBest(PartyFinderOverlayConfig cfg, Party party, PlayerStats stats) {
        JsonObject map = party.masterMode() ? stats.pbMaster() : stats.pbNormal();
        String floorKey = "floor_" + party.floor();
        String s = ConfigJson.getString(ConfigJson.getObject(map, "s"), floorKey, null);
        String sPlus = ConfigJson.getString(ConfigJson.getObject(map, "s_plus"), floorKey, null);
        return switch (cfg.getPbMode()) {
            case S -> new String[]{s, "S"};
            case S_PLUS -> new String[]{sPlus, "S+"};
            case BOTH -> sPlus != null ? new String[]{sPlus, "S+"} : new String[]{s, "S"};
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
                if (f.isColor() && f.getColor() != null && f.getColor() == color.getValue()) {
                    return Optional.of("&" + f.getChar());
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
     *  Fed through the exact same {@link #memberLine} the real tooltip uses, so the preview can never drift
     *  from - or paper over a bug in - what actually renders on a Party Finder head. */
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
            new PreviewPlayer("killer560", DungeonClass.HEALER, 50,
                    previewStats(50, 210_000, 62.4, pbEntry("4:15", "4:02"))),
            new PreviewPlayer("aut0balls", DungeonClass.TANK, 45,
                    previewStats(45, 150_000, 55.1, pbEntry("4:40", null))),
            new PreviewPlayer("agreencatgirl", DungeonClass.MAGE, 40,
                    previewStats(40, 95_000, 48.3, pbEntry("4:55", null))),
            new PreviewPlayer("femboy_recruiter", DungeonClass.BERSERK, 35,
                    previewStats(35, 52_000, 39.7, pbEntry(null, null))),
            new PreviewPlayer("latinomommy", DungeonClass.ARCHER, 30,
                    previewStats(30, 21_000, 30.2, pbEntry(null, null))));

    private static final Party PREVIEW_PARTY =
            new Party(0, 7, true, List.of(), List.of(), EnumSet.noneOf(Status.class));

    /** Number of sample rows {@link #renderPreviewLines} returns - lets the settings tab size its preview box
     *  without hard-coding the sample roster size a second time. */
    public static final int PREVIEW_LINE_COUNT = PREVIEW_PLAYERS.size();

    /** @return the fixed sample roster rendered with {@code cfg}'s current style/PB mode/rank colors/custom
     *  style - call fresh every frame (it's cheap) so a settings-tab preview widget tracks live edits with no
     *  rebuild needed. */
    public static List<Component> renderPreviewLines(PartyFinderOverlayConfig cfg) {
        List<Component> out = new ArrayList<>(PREVIEW_PLAYERS.size());
        for (PreviewPlayer p : PREVIEW_PLAYERS) {
            Component original = literal(" " + p.name() + ": " + p.role().displayName + " (" + p.level() + ")");
            out.add(memberLine(cfg, PREVIEW_PARTY, original, p.name(), p.role(), String.valueOf(p.level()), p.stats()));
        }
        return out;
    }
}
