package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's own fairy soul menu and syncs the found-soul log to it.
 * <p>
 * The menu is the "Fairy Souls Guide" chest (SkyBlock Menu -&gt; Quest Log -&gt; Fairy Souls, also reachable from Tia
 * the Fairy): one item per island, named exactly as the island is named ("Hub", "The Farming Islands", "Spider's Den",
 * ...), whose lore carries a "Fairy Souls: found/total" line. Those two strings are the same ones SkyHanni matches in
 * {@code features/misc/FastFairySoulsPathfinder.kt} (inventory name {@code "Fairy Souls Guide"}, lore pattern
 * {@code "Fairy Souls: (?<found>.*)/(?<total>.*)"}), read with {@code IslandType.getByNameOrNull(stack.cleanName)}.
 * <p>
 * What each state does to the log (killer560's request):
 * <ul>
 * <li>found == total: every soul of that island is marked found.</li>
 * <li>found == 0: the island's per-soul records are cleared.</li>
 * <li>anything in between: per-soul records are kept and the island is flagged "partially known" - Hypixel only says
 * how many souls were found, never which ones, so the rest has to come from chat as you collect them.</li>
 * </ul>
 */
public final class FairySoulMenu {

    private static final String CHAT = "Fairy Souls";
    private static final Pattern LORE_COUNT = Pattern.compile("Fairy Souls:\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** SkyHanni matches exactly "Fairy Souls Guide"; this also accepts any other menu whose title mentions Fairy
     *  Souls, since the island-name + "Fairy Souls: x/y" lore check below is what actually identifies the items. */
    private static final String MENU_TITLE = "Fairy Soul";

    private static Screen lastScreen;
    private static int stableTicks;
    private static int lastSignature;
    private static boolean applied;

    private FairySoulMenu() {
    }

    /** Call once per client tick. */
    public static void tick(Minecraft client) {
        if (!PathfindingConfig.getInstance().isFairySouls()) {
            return;
        }
        Screen screen = client.screen;
        if (screen != lastScreen) {
            lastScreen = screen;
            stableTicks = 0;
            lastSignature = 0;
            applied = false;
        }
        if (applied || !(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        if (title == null || !title.contains(MENU_TITLE)) {
            return;
        }
        Map<String, int[]> counts = read(container);
        int signature = signature(counts);
        if (counts.isEmpty()) {
            return;
        }
        // Hypixel fills a chest GUI over a few ticks - wait until the contents stop changing.
        if (signature != lastSignature) {
            lastSignature = signature;
            stableTicks = 0;
            return;
        }
        if (++stableTicks < 5) {
            return;
        }
        applied = true;
        apply(counts);
    }

    /**
     * Content hash of the read counts. {@code Map#hashCode} cannot be used here: the values are {@code int[]}, whose
     * hashCode is identity-based, so a fresh read of the SAME menu hashed differently every tick and the "wait until
     * the contents stop changing" check below never settled - the sync never ran at all.
     */
    private static int signature(Map<String, int[]> counts) {
        int hash = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            hash += e.getKey().hashCode() * 31 + java.util.Arrays.hashCode(e.getValue());
        }
        return hash;
    }

    private static Map<String, int[]> read(AbstractContainerScreen<?> screen) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue; // the player's own inventory half of the chest GUI
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            String island = IslandDetector.byDisplayName(name);
            if (island == null) {
                continue;
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                continue;
            }
            for (Component line : lore.lines()) {
                String plain = ChatFormatting.stripFormatting(line.getString());
                if (plain == null) {
                    continue;
                }
                Matcher m = LORE_COUNT.matcher(plain);
                if (m.find()) {
                    try {
                        out.put(IslandDetector.soulMenuGroup(island),
                                new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
                    } catch (NumberFormatException ignored) {
                        // a weird lore line never breaks the rest of the menu
                    }
                    break;
                }
            }
        }
        return out;
    }

    private static void apply(Map<String, int[]> counts) {
        String profile = ProfileTracker.key();
        int complete = 0;
        int partial = 0;
        int none = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String group = e.getKey();
            int found = e.getValue()[0];
            int total = e.getValue()[1];
            List<IslandGraph> graphs = new ArrayList<>();
            for (String island : IslandDetector.graphsForSoulGroup(group)) {
                IslandGraph graph = GraphRepository.get(island);
                if (graph != null) {
                    graphs.add(graph);
                } else if (total > 0 && found >= total) {
                    // need the graph to mark the individual souls - fetch it so the next menu open (or island
                    // visit) can complete the sync
                    GraphRepository.ensureLoading(island, false);
                }
            }
            String result = FairySoulStore.applyMenu(profile, group, found, total, graphs);
            if (result.startsWith("complete")) {
                complete++;
            } else if (result.startsWith("none")) {
                none++;
            } else {
                partial++;
            }
        }
        ModChat.send(CHAT, ModChat.text("Synced "), ModChat.value(String.valueOf(counts.size())),
                ModChat.text(" islands from Hypixel's menu "),
                ModChat.dim("(" + complete + " complete, " + partial + " partial, " + none + " untouched, profile "
                        + ProfileTracker.displayName() + ")"));
    }
}
