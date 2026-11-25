package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McItems;
import com.killer560.hub.itembrowser.NeuItemData;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * NEU Classic's Trophy Fish page (its TrophyFishPage over pv_trophy_fish_tab): Total Caught by a fishing rod at 20, 21; the
 * four hunter helmets from 15, 50 (10 apart) with "x/15" or "x/18" fish at that tier, or a check once the reward is claimed;
 * Thunder and Lord Jawbus kills at 16, 108 and 16, 120; the 18 fish on a diamond of slots on the right, the caught ones first
 * (most caught first) in a ring tinted by the best tier, the rest as gray dye. Hover: the fish's colored name, its description
 * from the item repo, then Diamond / Gold / Silver / Bronze counts and the total. Built once per profile, off the render thread.
 */
final class NeuTrophyPage {

    /** The original's fish table: internal name and name color, in its own insertion order (so the missing fish come in its order). */
    private static final Map<String, String> COLOR = new HashMap<>();
    static {
        String[] t = {"gusher", "§f", "flyfish", "§a", "moldfin", "§5", "vanille", "§9", "blobfish", "§f", "mana_ray", "§9",
                "slugfish", "§a", "soul_fish", "§5", "lava_horse", "§9", "golden_fish", "§6", "karate_fish", "§5", "skeleton_fish", "§5",
                "sulphur_skitter", "§f", "obfuscated_fish_1", "§f", "obfuscated_fish_2", "§a", "obfuscated_fish_3", "§9",
                "volcanic_stonefish", "§9", "steaming_hot_flounder", "§f"};
        for (int i = 0; i < t.length; i += 2) COLOR.put(t[i], t[i + 1]);
    }
    private static final int[][] SLOTS = {{277, 46}, {253, 58}, {301, 58}, {229, 70}, {325, 70}, {277, 70}, {253, 82}, {301, 82},
            {229, 94}, {325, 94}, {253, 106}, {301, 106}, {277, 118}, {229, 118}, {325, 118}, {253, 130}, {301, 130}, {277, 142}};
    private static final String[] RARITY = {"BRONZE", "SILVER", "GOLD", "DIAMOND"};
    /** The ring tint of each best tier (bronze, silver, gold, diamond). */
    private static final int[] RING = {0xFFFF8200, 0xFFC0C0C0, 0xFFFFD100, 0xFF1FD8F1};
    private static final String[][] HELMETS = {{"BRONZE_HUNTER_HELMET", "§aNovice Fisher"}, {"SILVER_HUNTER_HELMET", "§9Adept Fisher"},
            {"GOLD_HUNTER_HELMET", "§5Expert Fisher"}, {"DIAMOND_HUNTER_HELMET", "§6Master Fisher"}};
    private static final String CHECK_X = "§c✖", CHECK = "§a✔";

    /** One drawn fish: its name as the original keys it ("Soul_fish", or the internal name when missing), best tier (-1 = not
     *  caught), counts per tier (null = none), total, slot and icon. */
    private record Fish(String name, int best, Integer[] counts, int total, int x, int y, ItemStack icon) {
    }

    private static final class Model {
        boolean noData;
        long totalCaught;
        int thunder, jawbus;
        final List<Fish> fish = new ArrayList<>();
        final String[] helmetText = new String[4];
        final boolean[] helmetDone = new boolean[4];
        final ItemStack[] helmets = new ItemStack[4];
        @SuppressWarnings("unchecked")
        final List<Component>[] tips = new List[SLOTS.length];
        final boolean[] tipHasLore = new boolean[SLOTS.length];
        ItemStack thunderIcon = new ItemStack(Items.PLAYER_HEAD), jawbusIcon = new ItemStack(Items.PLAYER_HEAD);
        boolean thunderSkin, jawbusSkin;
    }

    private static final ItemStack ROD = new ItemStack(Items.FISHING_ROD);
    private final ProfileViewerScreen s;
    private SbProfile modelFor;
    private CompletableFuture<Model> model;

    NeuTrophyPage(ProfileViewerScreen s) {
        this.s = s;
    }

    void draw(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        if (modelFor != p || model == null) {
            modelFor = p;
            model = CompletableFuture.supplyAsync(() -> build(p), ProfileViewerApi.EXECUTOR);
        }
        Model m = model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (m == null) return;
        int l = s.panelX, t = s.panelY;
        if (m.noData) {
            String text = "§cNo data found";
            g.text(s.font(), text, l + 215 - s.font().width(text) / 2, t + 97, 0xFFFFFFFF, true);
            return;
        }
        if (!m.thunderSkin && NeuItemData.isSettled("THUNDER_SC")) {
            m.thunderSkin = true;
            m.thunderIcon = skinned("THUNDER_SC", m.thunderIcon);
        }
        if (!m.jawbusSkin && NeuItemData.isSettled("LORD_JAWBUS_SC")) {
            m.jawbusSkin = true;
            m.jawbusIcon = skinned("LORD_JAWBUS_SC", m.jawbusIcon);
        }
        g.item(m.thunderIcon, l + 16, t + 108);
        text(g, "§bThunder Kills: §f" + m.thunder, l + 36, t + 112);
        g.item(m.jawbusIcon, l + 16, t + 120);
        text(g, "§bLord Jawbus Kills: §f" + m.jawbus, l + 36, t + 124);
        g.item(ROD, l + 20, t + 21);
        text(g, "§bTotal Caught: §f" + m.totalCaught, l + 38, t + 25);
        List<Component> tip = null;
        for (int i = 0; i < m.fish.size(); i++) {
            Fish f = m.fish.get(i);
            int x = l + f.x, y = t + f.y;
            if (f.best >= 0) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, PvStyleChrome.NEU_INV_TAB, x - 2, y - 2, 20, 20, RING[f.best]);
                g.item(f.icon, x, y);
            } else {
                g.item(f.icon, x, y);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, PvStyleChrome.NEU_INV_TAB, x - 2, y - 2, 20, 20);
            }
            if (mx >= x && mx < x + 24 && my >= y && my <= y + 24 && !s.dropdownOpen) {
                tip = tip(m, i, f);
            }
        }
        for (int i = 0; i < 4; i++) {
            int x = l + 15, y = t + 50 + i * 10;
            g.item(m.helmets[i], x, y);
            text(g, HELMETS[i][1], x + 20, y + 4);
            text(g, m.helmetText[i], x + 100, y + (m.helmetDone[i] ? 2 : 4));
        }
        if (tip != null) s.pendingTooltip = tip;
    }

    private void text(GuiGraphicsExtractor g, String str, int x, int y) {
        g.text(s.font(), str, x, y, 0xFFFFFFFF, true);
    }

    /** The sea creature's skull from the item repo (the original's repo item), once it has answered; else the plain head. */
    private static ItemStack skinned(String id, ItemStack fallback) {
        NeuItemData.Info info = NeuItemData.get(id);
        return info == null || info.skin() == null ? fallback : LegacyItems.skull(info.skin(), null);
    }

    /** The fish's hover, rebuilt once its repo description arrives. */
    private static List<Component> tip(Model m, int i, Fish f) {
        String upper = f.name.toUpperCase(Locale.US).replace(" ", "_");
        NeuItemData.Info info = NeuItemData.get(upper + "_BRONZE");
        if (m.tips[i] != null && (m.tipHasLore[i] || info == null)) return m.tips[i];
        List<String> lines = new ArrayList<>();
        String color = COLOR.get(f.name.toLowerCase(Locale.US).replace(" ", "_"));
        lines.add((color == null ? "" : color) + words(f.name.replace("_", " ")));
        boolean found = false;
        for (String line : info == null ? List.<String>of() : info.lore()) {
            if (!found && line.startsWith("§7")) found = true;
            if (found && line.isEmpty()) break;
            if (found) lines.add(line);
        }
        lines.add(" ");
        if (f.counts == null) {
            lines.add("§c" + CHECK_X + " Not Discovered");
            lines.add(" ");
        }
        String[] tierColor = {"§8", "§7", "§6", "§b"};
        for (int r = 3; r >= 0; r--) {
            Integer n = f.counts == null ? null : f.counts[r];
            lines.add(tierColor[r] + words(RARITY[r].toLowerCase(Locale.US)) + ": " + (n == null ? CHECK_X : "§6" + String.format(Locale.US, "%,d", n)));
        }
        if (f.counts != null) {
            lines.add(" ");
            lines.add("§7Total: §6" + String.format(Locale.US, "%,d", f.total));
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String x : lines) out.add(Component.literal(x));
        m.tipHasLore[i] = info != null;
        return m.tips[i] = List.copyOf(out);
    }

    /** First letter of every space-separated word in capitals, the rest as given. */
    private static String words(String str) {
        StringBuilder b = new StringBuilder(str.length());
        boolean start = true;
        for (char c : str.toCharArray()) {
            b.append(start ? Character.toTitleCase(c) : c);
            start = c == ' ';
        }
        return b.toString();
    }

    private static Model build(SbProfile p) {
        Model m = new Model();
        JsonObject tf = p.memberObj("trophy_fish");
        if (tf == null) {
            m.noData = true;
            return m;
        }
        m.thunder = (int) p.memberNum("bestiary.kills.thunder_400");
        m.jawbus = (int) p.memberNum("bestiary.kills.lord_jawbus_600");
        NeuItemData.get("THUNDER_SC");
        NeuItemData.get("LORD_JAWBUS_SC");
        // As the original reads the object: per-tier counts by "Name" (first letter capital), the plain key is the fish's total.
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Integer[]> byName = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : tf.entrySet()) {
            String key = e.getKey();
            if (key.equalsIgnoreCase("rewards") || key.equalsIgnoreCase("last_caught")) continue;
            if (key.equalsIgnoreCase("total_caught")) {
                m.totalCaught = num(e.getValue());
                continue;
            }
            String[] parts = key.split("_");
            String type = key.startsWith("golden_fish_") && parts.length > 2 ? parts[2] : parts[parts.length - 1];
            int value = (int) num(e.getValue());
            int r = List.of(RARITY).indexOf(type.toUpperCase(Locale.US));
            if (r < 0) {
                totals.put(words(key), value);
                continue;
            }
            Integer[] counts = byName.computeIfAbsent(words(key.replace("_" + type, "")), k -> new Integer[4]);
            if (counts[r] == null) counts[r] = value;
        }
        List<Map.Entry<String, Integer[]>> caught = new ArrayList<>(new HashMap<>(byName).entrySet());
        caught.sort((a, b) -> Integer.compare(totals.getOrDefault(b.getKey(), 0), totals.getOrDefault(a.getKey(), 0)));
        for (Map.Entry<String, Integer[]> e : caught) {
            if (m.fish.size() >= SLOTS.length) break;
            int best = -1;
            for (int r = 0; r < 4; r++) if (e.getValue()[r] != null) best = r;
            String upper = e.getKey().toUpperCase(Locale.US).replace(" ", "_");
            int[] at = SLOTS[m.fish.size()];
            m.fish.add(new Fish(e.getKey(), best, e.getValue(), totals.getOrDefault(e.getKey(), 0), at[0], at[1],
                    ItemIcons.catalogIcon(upper + "_" + RARITY[Math.max(0, best)])));
            NeuItemData.get(upper + "_BRONZE");
        }
        List<String> missing = new ArrayList<>(COLOR.keySet());
        for (Map.Entry<String, Integer[]> e : caught) missing.remove(e.getKey().toLowerCase(Locale.US).replace(" ", "_"));
        for (String name : missing) {
            if (m.fish.size() >= SLOTS.length) break;
            int[] at = SLOTS[m.fish.size()];
            m.fish.add(new Fish(name, -1, null, 0, at[0], at[1], new ItemStack(McItems.GRAY_DYE)));
            NeuItemData.get(name.toUpperCase(Locale.US) + "_BRONZE");
        }
        int[] perTier = new int[4];
        for (String fish : COLOR.keySet()) {
            int best = 0;
            for (int r = 0; r < 4; r++) if (tf.has(fish + "_" + RARITY[r].toLowerCase(Locale.US))) best = r + 1;
            for (int r = 0; r < best; r++) perTier[r]++;
        }
        JsonArray rewards = tf.has("rewards") && tf.get("rewards").isJsonArray() ? tf.getAsJsonArray("rewards") : new JsonArray();
        for (int i = 0; i < 4; i++) {
            m.helmets[i] = ItemIcons.catalogIcon(HELMETS[i][0]);
            int need = i == 0 ? 15 : 18;
            m.helmetDone[i] = i < rewards.size() && !rewards.get(i).isJsonNull();
            m.helmetText[i] = m.helmetDone[i] ? CHECK : (perTier[i] == need ? "§a" : "§c") + perTier[i] + "/" + need;
        }
        return m;
    }

    private static long num(JsonElement e) {
        try {
            return e.isJsonPrimitive() ? e.getAsLong() : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
