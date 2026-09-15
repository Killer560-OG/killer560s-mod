package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.ExtraTables;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.Networth;
import com.killer560.hub.profileviewer.data.ProfileExtras;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.profileviewer.item.LegacyItems;
import com.killer560.hub.profileviewer.item.LegacyText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.ACCENT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.BAD;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.BAR_BG;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.DIM;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.MAXED;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.TEXT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.VALUE;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.commas;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.inside;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.shorten;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.style;

/**
 * The Profile Viewer pages beyond Basic Info / Dungeons / Inventories / Pets, modelled on skyblock-pv's
 * Collections, Mining (+ Glacite, Foraging), Bestiary, Crimson Isle, Museum, Rift, Farming and networth
 * displays. Drawn immediate-mode with {@link ProfileViewerScreen}'s helpers; every remote piece
 * (museum / garden / player endpoints, repo tables, prices) is an async future polled per frame.
 */
final class ExtraPages {

    private static final int GOOD = 0xFF55FF55;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.US);

    private final ProfileViewerScreen s;

    private final Map<String, CompletableFuture<ProfileViewerApi.AuxResult>> aux = new HashMap<>();
    private boolean bypassAux = false;
    private final Map<SbProfile, CompletableFuture<Networth.Result>> networth = new IdentityHashMap<>();
    private final Map<SbProfile, Boolean> networthHasMuseum = new IdentityHashMap<>();
    private final Map<JsonObject, CompletableFuture<MuseumView>> museumViews = new IdentityHashMap<>();
    private final Map<SbProfile, CompletableFuture<RiftInventory>> riftInventories = new IdentityHashMap<>();

    private record MuseumView(double value, boolean appraised, Map<String, List<ItemStack>> items, Set<String> borrowing,
                              List<ItemStack> special) {
        List<ItemStack> allOwned() {
            List<ItemStack> out = new ArrayList<>();
            for (Map.Entry<String, List<ItemStack>> e : items.entrySet()) {
                if (!borrowing.contains(e.getKey())) {
                    out.addAll(e.getValue());
                }
            }
            out.addAll(special);
            return out;
        }
    }

    private record RiftInventory(List<ItemStack> inventory, List<ItemStack> armor, List<ItemStack> equipment,
                                 List<List<ItemStack>> enderChest) {
    }

    ExtraPages(ProfileViewerScreen screen) {
        this.s = screen;
    }

    /** Called on every (re)load; a forced refresh also bypasses the museum/garden/player caches once. */
    void reset(boolean force) {
        aux.clear();
        networth.clear();
        networthHasMuseum.clear();
        museumViews.clear();
        riftInventories.clear();
        bypassAux = force;
    }

    void draw(GuiGraphicsExtractor g, int page, SbProfile p, int mx, int my) {
        switch (page) {
            case ProfileViewerScreen.PAGE_COLLECTIONS -> drawCollections(g, p, mx, my);
            case ProfileViewerScreen.PAGE_MINING -> drawMining(g, p, mx, my);
            case ProfileViewerScreen.PAGE_BESTIARY -> drawBestiary(g, p, mx, my);
            case ProfileViewerScreen.PAGE_CRIMSON -> drawCrimson(g, p, mx, my);
            case ProfileViewerScreen.PAGE_MUSEUM -> drawMuseum(g, p, mx, my);
            case ProfileViewerScreen.PAGE_RIFT -> drawRift(g, p, mx, my);
            case ProfileViewerScreen.PAGE_FARMING -> drawFarming(g, p, mx, my);
            case ProfileViewerScreen.PAGE_NETWORTH -> drawNetworth(g, p, mx, my);
            default -> drawMisc(g, p, mx, my);
        }
    }

    // ------------------------------------------------------------------ shared helpers

    private Font f() {
        return s.font();
    }

    private void text(GuiGraphicsExtractor g, String str, int x, int y, int color) {
        g.text(f(), str, x, y, color, false);
    }

    private void title(GuiGraphicsExtractor g, String str, int x, int y) {
        g.text(f(), str, x, y, ACCENT, false);
    }

    private void rightText(GuiGraphicsExtractor g, String str, int right, int y, int color) {
        g.text(f(), str, right - f().width(str), y, color, false);
    }

    private static Component line(String str, int color) {
        return Component.literal(str).withStyle(style(color));
    }

    private void tip(List<Component> lines) {
        if (!s.dropdownOpen) {
            s.pendingTooltip = lines;
        }
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return inside(mx, my, x, y, w, h) && !s.dropdownOpen;
    }

    private void center(GuiGraphicsExtractor g, String str, int x, int w, int color) {
        s.centeredIn(g, str, x, w, s.contentY + s.contentH / 2 - 4, color);
    }

    /** Parsed extras, or null after drawing the loading / failure state. */
    private ProfileExtras extras(GuiGraphicsExtractor g, SbProfile p) {
        CompletableFuture<ProfileExtras> f = p.extras();
        if (!f.isDone()) {
            center(g, "Loading" + s.dots(), s.contentX, s.contentW, VALUE);
            return null;
        }
        ProfileExtras ex = f.isCompletedExceptionally() ? null : f.getNow(null);
        if (ex == null) {
            center(g, "Couldn't read this profile's data.", s.contentX, s.contentW, BAD);
        }
        return ex;
    }

    private CompletableFuture<ProfileViewerApi.AuxResult> aux(ProfileViewerApi.AuxKind kind, String id) {
        return aux.computeIfAbsent(kind.name() + ":" + id, k -> ProfileViewerApi.fetchAux(kind, id, bypassAux));
    }

    /** Draws a vertical list of view buttons; returns the selected index. */
    private int nav(GuiGraphicsExtractor g, String[] views, int x, int y, int w, int mx, int my) {
        s.subView = Math.max(0, Math.min(s.subView, views.length - 1));
        for (int i = 0; i < views.length; i++) {
            final int idx = i;
            s.button(g, x, y + i * 19, w, 16, views[i], mx, my, i == s.subView, () -> {
                if (s.subView != idx) {
                    s.subView = idx;
                    s.pageScroll = 0;
                    s.subPage = 0;
                }
            });
        }
        return s.subView;
    }

    /** "< title n/count >" pager; returns the wrapped index. */
    private int pager(GuiGraphicsExtractor g, String label, int x, int y, int w, int count, int mx, int my) {
        if (count <= 0) {
            return 0;
        }
        int before = s.subPage;
        s.subPage = Math.floorMod(s.subPage, count);
        if (before != s.subPage && count > 1) {
            s.pageScroll = 0;
        }
        title(g, f().plainSubstrByWidth(label, w - 90), x + 6, y + 5);
        if (count > 1) {
            String n = (s.subPage + 1) + " / " + count;
            int right = x + w - 4;
            s.button(g, right - 16, y + 1, 16, 14, ">", mx, my, false, () -> {
                s.subPage++;
                s.pageScroll = 0;
            });
            g.text(f(), n, right - 22 - f().width(n), y + 5, VALUE, false);
            s.button(g, right - 42 - f().width(n), y + 1, 16, 14, "<", mx, my, false, () -> {
                s.subPage--;
                s.pageScroll = 0;
            });
        }
        return s.subPage;
    }

    private int clampScroll(int totalRows, int visibleRows) {
        int max = Math.max(0, totalRows - visibleRows);
        s.pageScroll = Math.max(0, Math.min(s.pageScroll, max));
        return s.pageScroll;
    }

    private void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int totalRows, int visibleRows) {
        if (totalRows <= visibleRows) {
            return;
        }
        g.fill(x, y, x + 2, y + h, 0xFF1A1A1A);
        int thumbH = Math.max(8, h * visibleRows / totalRows);
        int thumbY = y + (h - thumbH) * s.pageScroll / Math.max(1, totalRows - visibleRows);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, ACCENT);
    }

    private void progressBar(GuiGraphicsExtractor g, int x, int y, int w, double progress, boolean maxed) {
        g.fill(x, y, x + w, y + 3, BAR_BG);
        g.fill(x, y, x + (int) Math.round(w * (maxed ? 1 : Math.max(0, Math.min(1, progress)))), y + 3, maxed ? MAXED : ACCENT);
    }

    private static String date(long ms) {
        return ms <= 0 ? "-" : DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String dateTime(long ms) {
        return ms <= 0 ? "-" : DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String duration(long seconds) {
        long d = seconds / 86400, h = seconds / 3600 % 24, m = seconds / 60 % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m " + (seconds % 60) + "s";
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    /** Level from a "level n starts at table[n-1]" threshold table (HOTM / HOTF / garden). */
    private static LevelTables.Level thresholdLevel(long[] table, long xp) {
        return LevelTables.cumulative(table, xp, table.length);
    }

    private static int countAtOrBelow(long[] thresholds, long amount) {
        int n = 0;
        for (long t : thresholds) {
            if (amount >= t) {
                n++;
            }
        }
        return n;
    }

    private static String perkName(String id) {
        return SbProfile.titleCase(id);
    }

    // ------------------------------------------------------------------ collections

    private void drawCollections(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        if (ex.collections == null) {
            center(g, "Collections API is disabled.", s.contentX, s.contentW, DIM);
            return;
        }
        CompletableFuture<List<ExtraTables.CollectionCategory>> tf = ExtraTables.collections();
        List<ExtraTables.CollectionCategory> cats = ExtraTables.now(tf);
        if (cats == null) {
            center(g, tf.isDone() ? "Couldn't load collection tiers." : "Loading collection tiers" + s.dots(), s.contentX, s.contentW, tf.isDone() ? BAD : VALUE);
            return;
        }
        int navW = 84;
        s.box(g, s.contentX, s.contentY, navW, s.contentH);
        String[] names = cats.stream().map(ExtraTables.CollectionCategory::name).toArray(String[]::new);
        int sel = nav(g, names, s.contentX + 4, s.contentY + 4, navW - 8, mx, my);
        int totalMaxed = 0;
        int totalCount = 0;
        for (ExtraTables.CollectionCategory c : cats) {
            for (ExtraTables.CollectionItem it : c.items()) {
                totalCount++;
                if (it.tiers().length > 0 && ex.collections.getOrDefault(it.id(), 0L) >= it.tiers()[it.tiers().length - 1]) {
                    totalMaxed++;
                }
            }
        }
        int ny = s.contentY + 8 + names.length * 19;
        text(g, "Maxed", s.contentX + 6, ny, DIM);
        ny += 10;
        text(g, totalMaxed + " / " + totalCount, s.contentX + 6, ny, totalMaxed == totalCount ? MAXED : VALUE);

        ExtraTables.CollectionCategory cat = cats.get(sel);
        int rx = s.contentX + navW + 6;
        int rw = s.contentW - navW - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        int maxed = 0;
        for (ExtraTables.CollectionItem it : cat.items()) {
            if (it.tiers().length > 0 && ex.collections.getOrDefault(it.id(), 0L) >= it.tiers()[it.tiers().length - 1]) {
                maxed++;
            }
        }
        title(g, cat.name(), rx + 6, s.contentY + 5);
        rightText(g, "Maxed " + maxed + " / " + cat.items().size(), rx + rw - 8, s.contentY + 5, VALUE);

        int rowH = 22;
        int top = s.contentY + 18;
        int visible = Math.max(1, (s.contentH - 22) / rowH);
        int rows = (cat.items().size() + 1) / 2;
        int scroll = clampScroll(rows, visible);
        int colW = (rw - 20) / 2;
        for (int i = scroll * 2; i < cat.items().size(); i++) {
            int row = i / 2 - scroll;
            if (row >= visible) {
                break;
            }
            ExtraTables.CollectionItem it = cat.items().get(i);
            int x = rx + 6 + (i % 2) * (colW + 6);
            int y = top + row * rowH;
            long amount = ex.collections.getOrDefault(it.id(), 0L);
            long[] tiers = it.tiers();
            int tier = countAtOrBelow(tiers, amount);
            int max = Math.max(tiers.length, it.maxTiers());
            boolean isMaxed = tiers.length > 0 && tier >= tiers.length;
            long prev = tier == 0 ? 0 : tiers[Math.min(tier, tiers.length) - 1];
            long next = isMaxed || tiers.length == 0 ? prev : tiers[tier];
            double progress = isMaxed ? 1 : next <= prev ? 0 : (double) (amount - prev) / (next - prev);

            g.item(ItemIcons.forId(it.id()), x, y + 1);
            String tierText = tier + "/" + max;
            int tx = x + 19;
            int tw = colW - 19;
            text(g, f().plainSubstrByWidth(it.name(), tw - f().width(tierText) - 4), tx, y, amount > 0 ? TEXT : DIM);
            rightText(g, tierText, tx + tw, y, isMaxed ? MAXED : VALUE);
            progressBar(g, tx, y + 11, tw, progress, isMaxed);
            if (hover(mx, my, x, y, colW, rowH - 2)) {
                List<Component> t = new ArrayList<>();
                t.add(line(it.name(), VALUE));
                t.add(line("Collected: " + commas(amount), TEXT));
                t.add(line("Tier: " + tier + " / " + max, TEXT));
                if (!isMaxed && tiers.length > 0) {
                    t.add(line("Next tier: " + commas(amount) + " / " + commas(next) + " (" + pct(progress) + ")", DIM));
                    t.add(line("Max tier: " + pct(Math.min(1, (double) amount / tiers[tiers.length - 1])), DIM));
                } else if (isMaxed) {
                    t.add(line("MAXED", MAXED));
                }
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, rows, visible);
    }

    // ------------------------------------------------------------------ mining

    private static final String[] CRYSTALS = {"jade_crystal", "amethyst_crystal", "topaz_crystal", "sapphire_crystal", "amber_crystal",
            "ruby_crystal", "jasper_crystal", "opal_crystal", "aquamarine_crystal", "citrine_crystal", "peridot_crystal", "onyx_crystal"};
    private static final Item[] CRYSTAL_ICONS = {Items.LIME_STAINED_GLASS, Items.PURPLE_STAINED_GLASS, Items.YELLOW_STAINED_GLASS,
            Items.LIGHT_BLUE_STAINED_GLASS, Items.ORANGE_STAINED_GLASS, Items.RED_STAINED_GLASS, Items.MAGENTA_STAINED_GLASS,
            Items.WHITE_STAINED_GLASS, Items.BLUE_STAINED_GLASS, Items.BROWN_STAINED_GLASS, Items.GREEN_STAINED_GLASS, Items.BLACK_STAINED_GLASS};
    private static final long[] ROCK_BRACKETS = {2500, 7500, 20000, 100000, 250000};
    private static final String[] ROCK_RARITIES = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};

    private void drawMining(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        int navW = 84;
        s.box(g, s.contentX, s.contentY, navW, s.contentH);
        int view = nav(g, new String[]{"Mountain", "Glacite", "Foraging"}, s.contentX + 4, s.contentY + 4, navW - 8, mx, my);
        int rx = s.contentX + navW + 6;
        int rw = s.contentW - navW - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        switch (view) {
            case 0 -> drawHotm(g, ex.mining, rx, rw, mx, my);
            case 1 -> drawGlacite(g, ex.mining, rx, rw, mx, my);
            default -> drawForaging(g, ex.foraging, rx, rw, mx, my);
        }
    }

    private void drawHotm(GuiGraphicsExtractor g, ProfileExtras.Mining m, int rx, int rw, int mx, int my) {
        if (!m.present()) {
            center(g, "No Heart of the Mountain data.", rx, rw, DIM);
            return;
        }
        int x = rx + 6;
        int w = 160;
        int y = s.contentY + 5;
        long[] table = ExtraTables.longs(ExtraTables.bundled().get("hotm_levels"));
        LevelTables.Level lvl = thresholdLevel(table, m.hotmXp());
        s.bar(g, x, y, w, new ItemStack(Items.DIAMOND_PICKAXE), "Heart of the Mountain", lvl, mx, my, m.hotmXp());
        y += 22;
        title(g, "Powder", x, y);
        rightText(g, "Available / Total", x + w, y, DIM);
        y += 11;
        String[] names = {"Mithril", "Gemstone", "Glacite"};
        int[] colors = {0xFF00AA00, 0xFFFF55FF, 0xFF55FFFF};
        for (int i = 0; i < 3; i++) {
            text(g, names[i], x, y, colors[i]);
            rightText(g, shorten(m.powderAvailable()[i]) + " / " + shorten(m.powderTotal()[i]), x + w, y, VALUE);
            if (hover(mx, my, x, y, w, 10)) {
                tip(List.of(line(names[i] + " Powder", colors[i]), line("Available: " + commas(m.powderAvailable()[i]), TEXT),
                        line("Total: " + commas(m.powderTotal()[i]), TEXT)));
            }
            y += 10;
        }
        y += 4;
        int runs = Integer.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            ProfileExtras.Crystal c = m.crystals().get(CRYSTALS[i]);
            runs = Math.min(runs, c == null ? 0 : c.placed());
        }
        y = s.stat2(g, x, y, w, "Nucleus Runs", commas(runs == Integer.MAX_VALUE ? 0 : runs));
        int rock = countAtOrBelow(ROCK_BRACKETS, m.oresMined());
        int rockY = y;
        y = s.stat2(g, x, y, w, "Rock Pet", rock == 0 ? "None" : ROCK_RARITIES[rock - 1]);
        if (hover(mx, my, x, rockY, w, 10)) {
            tip(List.of(line("Ores Mined: " + commas(m.oresMined()), TEXT)));
        }
        if (m.selectedAbility() != null && !m.selectedAbility().isEmpty()) {
            y = s.stat2(g, x, y, w, "Ability", f().plainSubstrByWidth(perkName(m.selectedAbility()), 90));
        }
        y += 4;
        title(g, "Forge", x, y);
        y += 11;
        if (m.forge().isEmpty()) {
            text(g, "Empty", x, y, DIM);
        }
        for (ProfileExtras.ForgeSlot slot : m.forge()) {
            if (y > s.contentY + s.contentH - 10) {
                break;
            }
            String label = "Slot " + slot.slot();
            text(g, label, x, y, DIM);
            String item = f().plainSubstrByWidth(SbProfile.titleCase(slot.id()), w - f().width(label) - 6);
            rightText(g, item, x + w, y, VALUE);
            if (hover(mx, my, x, y, w, 10)) {
                tip(List.of(line(SbProfile.titleCase(slot.id()), VALUE), line("Started: " + dateTime(slot.startTime()), DIM)));
            }
            y += 10;
        }

        // Crystals + perks
        int cx = rx + 6 + w + 12;
        int cw = rx + rw - 6 - cx;
        int cy = s.contentY + 5;
        title(g, "Crystals", cx, cy);
        cy += 11;
        int perRow = Math.max(1, Math.min(6, cw / 20));
        for (int i = 0; i < CRYSTALS.length; i++) {
            int ix = cx + (i % perRow) * 20;
            int iy = cy + (i / perRow) * 20;
            ProfileExtras.Crystal c = m.crystals().get(CRYSTALS[i]);
            String state = c == null ? "NOT_FOUND" : c.state();
            boolean have = state.equals("FOUND") || state.equals("PLACED");
            g.fill(ix, iy, ix + 18, iy + 18, 0xFF1C1C1C);
            g.outline(ix, iy, 18, 18, have ? ACCENT : 0xFF2E2E2E);
            g.item(new ItemStack(CRYSTAL_ICONS[i]), ix + 1, iy + 1);
            g.itemDecorations(f(), new ItemStack(CRYSTAL_ICONS[i]), ix + 1, iy + 1, have ? "§a✔" : "§c✘");
            if (hover(mx, my, ix, iy, 18, 18)) {
                tip(List.of(line(SbProfile.titleCase(CRYSTALS[i]), VALUE), line("State: " + SbProfile.titleCase(state), TEXT),
                        line("Found: " + commas(c == null ? 0 : c.found()), DIM), line("Placed: " + commas(c == null ? 0 : c.placed()), DIM)));
            }
        }
        cy += ((CRYSTALS.length + perRow - 1) / perRow) * 20 + 4;
        perkList(g, "Perks", m.nodes(), cx, cy, cw, mx, my);
    }

    private void perkList(GuiGraphicsExtractor g, String label, Map<String, Integer> nodes, int x, int y, int w, int mx, int my) {
        title(g, label, x, y);
        rightText(g, String.valueOf(nodes.size()), x + w, y, VALUE);
        y += 11;
        int bottom = s.contentY + s.contentH - 4;
        int visible = Math.max(1, (bottom - y) / 10);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(nodes.entrySet());
        int scroll = clampScroll(list.size(), visible);
        for (int i = scroll; i < list.size() && i - scroll < visible; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            int ly = y + (i - scroll) * 10;
            String lv = String.valueOf(e.getValue());
            text(g, f().plainSubstrByWidth(perkName(e.getKey()), w - f().width(lv) - 8), x, ly, TEXT);
            rightText(g, lv, x + w - 4, ly, VALUE);
        }
        if (list.isEmpty()) {
            text(g, "None", x, y, DIM);
        }
        scrollbar(g, x + w - 2, y, visible * 10, list.size(), visible);
    }

    private void drawGlacite(GuiGraphicsExtractor g, ProfileExtras.Mining m, int rx, int rw, int mx, int my) {
        int x = rx + 6;
        int w = (rw - 24) / 2;
        int y = s.contentY + 5;
        title(g, "Glacite Tunnels", x, y);
        y += 12;
        y = s.stat2(g, x, y, w, "Mineshafts Entered", commas(m.mineshafts()));
        List<Map<String, Integer>> milestones = new ArrayList<>();
        for (JsonElement el : ExtraTables.bundled().has("corpse_milestones") ? ExtraTables.bundled().getAsJsonArray("corpse_milestones") : new com.google.gson.JsonArray()) {
            Map<String, Integer> req = new LinkedHashMap<>();
            if (el.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    req.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            milestones.add(req);
        }
        int milestone = 0;
        for (int i = 0; i < milestones.size(); i++) {
            boolean ok = true;
            for (Map.Entry<String, Integer> e : milestones.get(i).entrySet()) {
                if (m.corpses().getOrDefault(e.getKey(), 0) < e.getValue()) {
                    ok = false;
                }
            }
            if (ok) {
                milestone = i + 1;
            }
        }
        y = s.stat2(g, x, y, w, "Corpse Milestone", milestone + " / " + milestones.size());
        y += 4;
        title(g, "Corpses Looted", x, y);
        y += 11;
        for (String corpse : List.of("lapis", "umber", "tungsten", "vanguard")) {
            y = s.stat2(g, x, y, w, SbProfile.titleCase(corpse), commas(m.corpses().getOrDefault(corpse, 0)));
        }

        int fx = x + w + 12;
        int fy = s.contentY + 5;
        JsonObject fossils = ExtraTables.section("fossils");
        Set<String> donated = new LinkedHashSet<>(m.fossils());
        title(g, "Fossils", fx, fy);
        rightText(g, donated.size() + " / " + fossils.size(), fx + w, fy, VALUE);
        fy += 12;
        for (Map.Entry<String, JsonElement> e : fossils.entrySet()) {
            JsonObject fo = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
            boolean have = donated.contains(e.getKey());
            String name = fo.has("name") ? fo.get("name").getAsString() : SbProfile.titleCase(e.getKey());
            text(g, name, fx, fy, have ? TEXT : DIM);
            rightText(g, have ? "✔" : "✘", fx + w, fy, have ? GOOD : BAD);
            if (hover(mx, my, fx, fy, w, 10) && fo.has("pet")) {
                tip(List.of(line(name, VALUE), line("Pet: " + SbProfile.titleCase(fo.get("pet").getAsString()), DIM)));
            }
            fy += 10;
        }
    }

    private void drawForaging(GuiGraphicsExtractor g, ProfileExtras.Foraging fo, int rx, int rw, int mx, int my) {
        if (!fo.present()) {
            center(g, "No Heart of the Forest data.", rx, rw, DIM);
            return;
        }
        int x = rx + 6;
        int w = 160;
        int y = s.contentY + 5;
        long[] table = ExtraTables.longs(ExtraTables.bundled().get("hotf_levels"));
        LevelTables.Level lvl = thresholdLevel(table, fo.hotfXp());
        s.bar(g, x, y, w, new ItemStack(Items.OAK_SAPLING), "Heart of the Forest", lvl, mx, my, fo.hotfXp());
        y += 22;
        title(g, "Whispers", x, y);
        rightText(g, "Available / Total", x + w, y, DIM);
        y += 11;
        y = s.stat2(g, x, y, w, "Forest", shorten(fo.forestAvailable()) + " / " + shorten(fo.forestTotal()));
        y = s.stat2(g, x, y, w, "Desert", shorten(fo.desertAvailable()) + " / " + shorten(fo.desertTotal()));
        if (!fo.treeGifts().isEmpty()) {
            y += 4;
            title(g, "Tree Gifts", x, y);
            y += 11;
            for (Map.Entry<String, Integer> e : fo.treeGifts().entrySet()) {
                y = s.stat2(g, x, y, w, SbProfile.titleCase(e.getKey()), commas(e.getValue()));
            }
        }
        int cx = rx + 6 + w + 12;
        perkList(g, "Perks", fo.nodes(), cx, s.contentY + 5, rx + rw - 6 - cx, mx, my);
    }

    // ------------------------------------------------------------------ bestiary

    private void drawBestiary(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        if (ex.bestiaryKills == null) {
            center(g, "No bestiary data on this profile.", s.contentX, s.contentW, DIM);
            return;
        }
        CompletableFuture<List<ExtraTables.BestiaryCategory>> tf = ExtraTables.bestiary();
        List<ExtraTables.BestiaryCategory> cats = ExtraTables.now(tf);
        if (cats == null) {
            center(g, tf.isDone() ? "Couldn't load bestiary data." : "Loading bestiary data" + s.dots(), s.contentX, s.contentW, tf.isDone() ? BAD : VALUE);
            return;
        }
        int totalTiers = 0;
        int maxed = 0;
        int families = 0;
        long kills = 0;
        for (long k : ex.bestiaryKills.values()) {
            kills += k;
        }
        for (ExtraTables.BestiaryCategory c : cats) {
            for (ExtraTables.BestiaryFamily fam : c.families()) {
                families++;
                int tier = countAtOrBelow(toLongs(fam.tiers()), familyKills(ex, fam));
                totalTiers += tier;
                if (fam.tiers().length > 0 && tier >= fam.tiers().length) {
                    maxed++;
                }
            }
        }
        int lw = 130;
        s.box(g, s.contentX, s.contentY, lw, s.contentH);
        int x = s.contentX + 6;
        int y = s.contentY + 5;
        title(g, "Bestiary", x, y);
        y += 12;
        y = s.stat2(g, x, y, lw - 12, "Milestone", String.valueOf(totalTiers / 10));
        y = s.stat2(g, x, y, lw - 12, "Family Tiers", commas(totalTiers));
        y = s.stat2(g, x, y, lw - 12, "Maxed", maxed + " / " + families);
        s.stat2(g, x, y, lw - 12, "Total Kills", shorten(kills));

        int rx = s.contentX + lw + 6;
        int rw = s.contentW - lw - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        int idx = pager(g, cats.get(Math.floorMod(s.subPage, cats.size())).name(), rx, s.contentY + 1, rw, cats.size(), mx, my);
        ExtraTables.BestiaryCategory cat = cats.get(idx);
        int cell = 20;
        int cols = Math.max(1, (rw - 16) / cell);
        int top = s.contentY + 20;
        int visible = Math.max(1, (s.contentH - 24) / cell);
        int rows = (cat.families().size() + cols - 1) / cols;
        int scroll = clampScroll(rows, visible);
        int startX = rx + (rw - cols * cell) / 2;
        for (int i = scroll * cols; i < cat.families().size(); i++) {
            int row = i / cols - scroll;
            if (row >= visible) {
                break;
            }
            ExtraTables.BestiaryFamily fam = cat.families().get(i);
            int ix = startX + (i % cols) * cell;
            int iy = top + row * cell;
            long k = familyKills(ex, fam);
            long[] tiers = toLongs(fam.tiers());
            int tier = countAtOrBelow(tiers, k);
            boolean isMaxed = tiers.length > 0 && tier >= tiers.length;
            ItemStack icon = k == 0 ? new ItemStack(Items.GRAY_DYE) : familyIcon(fam);
            g.fill(ix + 1, iy + 1, ix + cell - 1, iy + cell - 1, 0xFF1A1A1A);
            g.outline(ix + 1, iy + 1, cell - 2, cell - 2, isMaxed ? MAXED : 0xFF2E2E2E);
            g.item(icon, ix + 2, iy + 2);
            g.itemDecorations(f(), icon, ix + 2, iy + 2, (isMaxed ? "§6" : "§f") + tier);
            if (hover(mx, my, ix, iy, cell, cell)) {
                List<Component> t = new ArrayList<>();
                t.add(line(fam.name(), VALUE));
                t.add(line("Tier: " + tier + " / " + tiers.length, TEXT));
                if (isMaxed || tiers.length == 0) {
                    t.add(line("Kills: " + commas(k) + (isMaxed ? "  MAXED" : ""), isMaxed ? MAXED : TEXT));
                } else {
                    t.add(line("Kills: " + commas(k) + " / " + commas(tiers[tiers.length - 1]), TEXT));
                    t.add(line("Next tier: " + commas(k) + " / " + commas(tiers[tier]), DIM));
                }
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * cell, rows, visible);
    }

    private static long familyKills(ProfileExtras ex, ExtraTables.BestiaryFamily fam) {
        long k = 0;
        for (String mob : fam.mobs()) {
            k += ex.bestiaryKills.getOrDefault(mob, 0L);
        }
        return k;
    }

    private static long[] toLongs(int[] arr) {
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[i];
        }
        return out;
    }

    private final Map<ExtraTables.BestiaryFamily, ItemStack> familyIcons = new IdentityHashMap<>();

    private ItemStack familyIcon(ExtraTables.BestiaryFamily fam) {
        return familyIcons.computeIfAbsent(fam, f -> {
            if (f.texture() != null) {
                return LegacyItems.skull(f.texture(), null);
            }
            return ItemIcons.forItem(f.icon());
        });
    }

    // ------------------------------------------------------------------ crimson isle

    private void drawCrimson(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        ProfileExtras.Crimson c = ex.crimson;
        if (!c.present()) {
            center(g, "No Crimson Isle data on this profile.", s.contentX, s.contentW, DIM);
            return;
        }
        JsonObject tables = ExtraTables.section("crimson_isle");
        int colW = (s.contentW - 12) / 3;
        int x0 = s.contentX;

        // Reputation
        s.box(g, x0, s.contentY, colW, s.contentH);
        int x = x0 + 6;
        int w = colW - 12;
        int y = s.contentY + 5;
        title(g, "Reputation", x, y);
        y += 12;
        JsonObject factionNames = tables.has("faction_names") ? tables.getAsJsonObject("faction_names") : new JsonObject();
        String faction = c.selectedFaction().isEmpty() ? "None"
                : factionNames.has(c.selectedFaction()) ? factionNames.get(c.selectedFaction()).getAsString() : SbProfile.titleCase(c.selectedFaction());
        y = s.stat2(g, x, y, w, "Faction", faction);
        y += 2;
        int[] reps = {c.magesRep(), c.barbariansRep()};
        String[] names = {"Mage", "Barbarian"};
        int[] colors = {0xFFAA00AA, 0xFFFF5555};
        for (int i = 0; i < 2; i++) {
            text(g, names[i], x, y, colors[i]);
            rightText(g, commas(reps[i]), x + w, y, VALUE);
            y += 10;
            rightText(g, ExtraTables.threshold(tables.get("faction_reputation"), "name", reps[i]), x + w, y, DIM);
            y += 11;
        }
        int best = Math.max(reps[0], reps[1]);
        String highest = "None";
        JsonObject req = tables.has("kuudra_requirements") ? tables.getAsJsonObject("kuudra_requirements") : new JsonObject();
        JsonObject kNames = tables.has("kuudra_names") ? tables.getAsJsonObject("kuudra_names") : new JsonObject();
        int bestReq = -1;
        for (Map.Entry<String, JsonElement> e : req.entrySet()) {
            int r = e.getValue().getAsInt();
            if (r <= best && r > bestReq) {
                bestReq = r;
                highest = kNames.has(e.getKey()) ? kNames.get(e.getKey()).getAsString() : SbProfile.titleCase(e.getKey());
            }
        }
        y = s.stat2(g, x, y, w, "Kuudra Unlocked", highest);
        y += 6;
        title(g, "Matriarch", x, y);
        y += 12;
        y = s.stat2(g, x, y, w, "Pearls Collected", commas(c.matriarchPearls()));
        s.stat2(g, x, y, w, "Last Attempt", date(c.matriarchLastAttempt()));

        // Kuudra
        int kx0 = x0 + colW + 6;
        s.box(g, kx0, s.contentY, colW, s.contentH);
        x = kx0 + 6;
        y = s.contentY + 5;
        title(g, "Kuudra", x, y);
        y += 12;
        int total = 0;
        long collection = 0;
        int tierIndex = 0;
        int bestWave = 0;
        for (Map.Entry<String, Integer> e : c.kuudraCompletions().entrySet()) {
            tierIndex++;
            total += e.getValue();
            collection += (long) e.getValue() * tierIndex;
            int wave = c.kuudraHighestWave().getOrDefault(e.getKey(), 0);
            bestWave = Math.max(bestWave, wave);
            String name = kNames.has(e.getKey()) ? kNames.get(e.getKey()).getAsString() : SbProfile.titleCase(e.getKey());
            int ly = y;
            y = s.stat2(g, x, y, w, name, commas(e.getValue()));
            if (hover(mx, my, x, ly, w, 10)) {
                tip(List.of(line(name + " Kuudra", VALUE), line("Completions: " + commas(e.getValue()), TEXT),
                        line("Highest Wave: " + commas(wave), DIM)));
            }
        }
        y += 4;
        y = s.stat2(g, x, y, w, "Total Runs", commas(total));
        long[] colTiers = ExtraTables.longs(tables.get("kuudra_collection"));
        y = s.stat2(g, x, y, w, "Collection", commas(collection));
        y = s.stat2(g, x, y, w, "Collection Tier", countAtOrBelow(colTiers, collection) + " / " + colTiers.length);
        s.stat2(g, x, y, w, "Highest Wave", commas(bestWave));

        // Dojo
        int dx0 = kx0 + colW + 6;
        int dw = s.contentX + s.contentW - dx0;
        s.box(g, dx0, s.contentY, dw, s.contentH);
        x = dx0 + 6;
        w = dw - 12;
        y = s.contentY + 5;
        title(g, "Dojo", x, y);
        y += 12;
        JsonObject dojoNames = tables.has("dojo_names") ? tables.getAsJsonObject("dojo_names") : new JsonObject();
        int points = 0;
        boolean anyDojo = false;
        for (Map.Entry<String, Integer> e : c.dojoPoints().entrySet()) {
            int pts = e.getValue();
            String name = dojoNames.has(e.getKey()) ? dojoNames.get(e.getKey()).getAsString() : SbProfile.titleCase(e.getKey());
            String grade = pts < 0 ? "-" : ExtraTables.threshold(tables.get("dojo_grades"), "grade", pts);
            text(g, name, x, y, pts < 0 ? DIM : TEXT);
            String val = pts < 0 ? "-" : commas(pts);
            rightText(g, grade, x + w, y, gradeColor(grade));
            rightText(g, val, x + w - 12, y, VALUE);
            if (pts >= 0) {
                anyDojo = true;
                points += pts;
                int time = c.dojoTimes().getOrDefault(e.getKey(), -1);
                if (hover(mx, my, x, y, w, 10)) {
                    tip(List.of(line("Test of " + name, VALUE), line("Points: " + commas(pts) + " (" + grade + ")", TEXT),
                            line("Time: " + (time < 0 ? "-" : String.format(Locale.ROOT, "%.1fs", time / 1000.0)), DIM)));
                }
            }
            y += 10;
        }
        y += 4;
        y = s.stat2(g, x, y, w, "Total Points", anyDojo ? commas(points) : "-");
        s.stat2(g, x, y, w, "Belt", anyDojo ? ExtraTables.threshold(tables.get("dojo_belts"), "belt", points) : "None");
    }

    private static int gradeColor(String grade) {
        return switch (grade) {
            case "S" -> 0xFFFFAA00;
            case "A" -> 0xFFFF55FF;
            case "B" -> 0xFF5555FF;
            case "C", "D" -> 0xFF55FF55;
            case "F" -> 0xFFFF5555;
            default -> DIM;
        };
    }

    // ------------------------------------------------------------------ museum

    private void drawMuseum(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        CompletableFuture<ProfileViewerApi.AuxResult> fut = aux(ProfileViewerApi.AuxKind.MUSEUM, p.profileId);
        if (!fut.isDone()) {
            center(g, "Loading museum" + s.dots(), s.contentX, s.contentW, VALUE);
            return;
        }
        if (fut.isCompletedExceptionally()) {
            String msg = "";
            try {
                fut.join();
            } catch (Exception e) {
                msg = ProfileViewerApi.messageFor(e);
            }
            auxError(g, "Couldn't load the museum.", msg, ProfileViewerApi.AuxKind.MUSEUM, p.profileId, mx, my);
            return;
        }
        ProfileViewerApi.AuxResult res = fut.getNow(null);
        JsonObject member = res == null || res.data() == null ? null : SbProfile.asObjPublic(res.data().get(ProfileViewerApi.dashless(p.member)));
        if (member == null) {
            center(g, "Museum API is disabled or nothing is donated.", s.contentX, s.contentW, DIM);
            return;
        }
        CompletableFuture<MuseumView> vf = museumView(member);
        MuseumView view = ExtraTables.now(vf);
        if (view == null) {
            center(g, vf.isDone() ? "Couldn't decode the museum items." : "Decoding items" + s.dots(), s.contentX, s.contentW, vf.isDone() ? BAD : VALUE);
            return;
        }
        ExtraTables.MuseumTables tables = ExtraTables.now(ExtraTables.museum());

        List<String> catIds = new ArrayList<>();
        if (tables != null) {
            catIds.addAll(tables.categories().keySet());
            catIds.remove("special");
        } else {
            catIds.add("all");
        }
        catIds.add("special");

        int lw = 140;
        s.box(g, s.contentX, s.contentY, lw, s.contentH);
        int x = s.contentX + 6;
        int w = lw - 12;
        int y = s.contentY + 5;
        title(g, "Museum", x, y);
        y += 12;
        y = s.stat2(g, x, y, w, "Value", shorten(view.value()));
        y = s.stat2(g, x, y, w, "Appraised", view.appraised() ? "Yes" : "No");
        y = s.stat2(g, x, y, w, "Donated", commas(view.items().size()));
        y = s.stat2(g, x, y, w, "Special", commas(view.special().size()));
        if (!view.borrowing().isEmpty()) {
            y = s.stat2(g, x, y, w, "Borrowing", commas(view.borrowing().size()));
        }
        y += 4;
        s.subView = Math.max(0, Math.min(s.subView, catIds.size() - 1));
        for (int i = 0; i < catIds.size(); i++) {
            String id = catIds.get(i);
            int donated = countDonated(view, tables, id);
            Integer max = tables == null ? null : tables.maxValues().get(id);
            String label = SbProfile.titleCase(id) + " " + donated + (max != null && !id.equals("special") ? "/" + max : "");
            final int idx = i;
            if (y + 16 > s.contentY + s.contentH) {
                break;
            }
            s.button(g, x - 2, y, w + 4, 15, label, mx, my, i == s.subView, () -> {
                s.subView = idx;
                s.pageScroll = 0;
            });
            y += 17;
        }

        int rx = s.contentX + lw + 6;
        int rw = s.contentW - lw - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        String cat = catIds.get(s.subView);
        List<ItemStack> stacks = new ArrayList<>();
        if (cat.equals("special")) {
            stacks.addAll(view.special());
        } else if (tables == null) {
            for (List<ItemStack> l : view.items().values()) {
                stacks.addAll(l);
            }
        } else {
            for (String id : tables.categories().getOrDefault(cat, List.of())) {
                List<ItemStack> l = view.items().get(id);
                if (l != null) {
                    stacks.addAll(l);
                }
            }
        }
        title(g, SbProfile.titleCase(cat), rx + 6, s.contentY + 5);
        rightText(g, stacks.size() + " items", rx + rw - 8, s.contentY + 5, VALUE);
        if (stacks.isEmpty()) {
            center(g, "Nothing donated.", rx, rw, DIM);
            return;
        }
        int cols = Math.max(1, (rw - 16) / 18);
        int top = s.contentY + 18;
        int visible = Math.max(1, (s.contentH - 22) / 18);
        int rows = (stacks.size() + cols - 1) / cols;
        int scroll = clampScroll(rows, visible);
        int startX = rx + (rw - cols * 18) / 2;
        for (int i = scroll * cols; i < stacks.size(); i++) {
            int row = i / cols - scroll;
            if (row >= visible) {
                break;
            }
            s.slot(g, startX + (i % cols) * 18, top + row * 18, stacks.get(i), mx, my, false);
        }
        scrollbar(g, rx + rw - 5, top, visible * 18, rows, visible);
    }

    private static int countDonated(MuseumView view, ExtraTables.MuseumTables tables, String cat) {
        if (cat.equals("special")) {
            return view.special().size();
        }
        if (tables == null) {
            return view.items().size();
        }
        int n = 0;
        for (String id : tables.categories().getOrDefault(cat, List.of())) {
            if (view.items().containsKey(id)) {
                n++;
            }
        }
        return n;
    }

    private void auxError(GuiGraphicsExtractor g, String headline, String detail, ProfileViewerApi.AuxKind kind, String id, int mx, int my) {
        int y = s.contentY + s.contentH / 2 - 20;
        s.centeredIn(g, headline, s.contentX, s.contentW, y, BAD);
        y += 14;
        for (String l : detail.split("\n")) {
            for (var seq : f().split(Component.literal(l), s.contentW - 20)) {
                g.centeredText(f(), seq, s.contentX + s.contentW / 2, y, TEXT);
                y += 10;
            }
        }
        s.button(g, s.contentX + s.contentW / 2 - 30, y + 4, 60, 16, "Retry", mx, my, false, () -> {
            aux.remove(kind.name() + ":" + id);
            networth.clear();
        });
    }

    private CompletableFuture<MuseumView> museumView(JsonObject member) {
        return museumViews.computeIfAbsent(member, m -> CompletableFuture.supplyAsync(() -> {
            Map<String, List<ItemStack>> items = new LinkedHashMap<>();
            Set<String> borrowing = new LinkedHashSet<>();
            JsonObject obj = SbProfile.asObjPublic(m.get("items"));
            if (obj != null) {
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    JsonObject entry = SbProfile.asObjPublic(e.getValue());
                    if (entry == null) {
                        continue;
                    }
                    List<ItemStack> stacks = new ArrayList<>();
                    for (ItemStack st : LegacyItems.decodeInventory(entry.get("items"))) {
                        if (!st.isEmpty()) {
                            stacks.add(st);
                        }
                    }
                    items.put(e.getKey(), stacks);
                    if (entry.has("borrowing") && entry.get("borrowing").isJsonPrimitive() && entry.get("borrowing").getAsBoolean()) {
                        borrowing.add(e.getKey());
                    }
                }
            }
            List<ItemStack> special = new ArrayList<>();
            if (m.has("special") && m.get("special").isJsonArray()) {
                for (JsonElement el : m.getAsJsonArray("special")) {
                    JsonObject so = SbProfile.asObjPublic(el);
                    if (so != null) {
                        for (ItemStack st : LegacyItems.decodeInventory(so.get("items"))) {
                            if (!st.isEmpty()) {
                                special.add(st);
                            }
                        }
                    }
                }
            }
            double value = 0;
            boolean appraised = false;
            try {
                value = m.has("value") ? m.get("value").getAsDouble() : 0;
                appraised = m.has("appraisal") && m.get("appraisal").getAsBoolean();
            } catch (Exception ignored) {
            }
            return new MuseumView(value, appraised, items, borrowing, special);
        }, ProfileViewerApi.EXECUTOR).exceptionally(t -> null));
    }

    // ------------------------------------------------------------------ rift

    private void drawRift(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        ProfileExtras.Rift r = ex.rift;
        if (!r.present()) {
            center(g, "This profile hasn't visited the Rift.", s.contentX, s.contentW, DIM);
            return;
        }
        int navW = 84;
        s.box(g, s.contentX, s.contentY, navW, s.contentH);
        int view = nav(g, new String[]{"Information", "Inventory", "Ender Chest"}, s.contentX + 4, s.contentY + 4, navW - 8, mx, my);
        int rx = s.contentX + navW + 6;
        int rw = s.contentW - navW - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        JsonObject tables = ExtraTables.section("rift");
        if (view == 0) {
            int x = rx + 6;
            int w = 170;
            int y = s.contentY + 5;
            title(g, "Information", x, y);
            y += 12;
            y = s.stat2(g, x, y, w, "Motes", commas(r.motes()));
            y = s.stat2(g, x, y, w, "Lifetime Motes", commas(r.lifetimeMotes()));
            y = s.stat2(g, x, y, w, "Visits", commas(r.visits()));
            y = s.stat2(g, x, y, w, "Time Sitting", duration(r.secondsSitting()));
            int souls = tables.has("enigma_souls") ? tables.get("enigma_souls").getAsInt() : 52;
            y = s.stat2(g, x, y, w, "Enigma Souls", r.souls().size() + " / " + souls);
            List<String> cats = ExtraTables.strings(tables.get("montezuma"));
            y = listStat(g, x, y, w, "Montezuma Cats", r.cats(), cats, "Missing", mx, my);
            List<String> eyes = ExtraTables.strings(tables.get("eyes"));
            y = listStat(g, x, y, w, "Unlocked Eyes", r.eyes(), eyes, "Locked", mx, my);
            int grub = tables.has("grubber_max") ? tables.get("grubber_max").getAsInt() : 5;
            s.stat2(g, x, y, w, "Grubber Stacks", r.grubberStacks() + " / " + grub);

            int tx = x + w + 14;
            int tw = rx + rw - 6 - tx;
            int ty = s.contentY + 5;
            com.google.gson.JsonArray trophies = tables.has("trophies") ? tables.getAsJsonArray("trophies") : new com.google.gson.JsonArray();
            title(g, "Timecharms", tx, ty);
            rightText(g, r.trophies().size() + " / " + trophies.size(), tx + tw, ty, VALUE);
            ty += 12;
            for (JsonElement el : trophies) {
                JsonObject t = el.getAsJsonObject();
                String id = t.get("id").getAsString();
                String name = t.get("name").getAsString();
                ProfileExtras.Trophy got = null;
                for (ProfileExtras.Trophy tr : r.trophies()) {
                    if (tr.type().equals(id)) {
                        got = tr;
                    }
                }
                g.item(new ItemStack(got != null ? Items.CLOCK : Items.GRAY_DYE), tx, ty);
                text(g, f().plainSubstrByWidth(name, tw - 20), tx + 19, ty + 4, got != null ? TEXT : DIM);
                if (hover(mx, my, tx, ty, tw, 17)) {
                    tip(got == null ? List.of(line(name, DIM), line("Not found", BAD))
                            : List.of(line(name, VALUE), line("Found after " + got.visits() + " visits", TEXT), line(dateTime(got.timestamp()), DIM)));
                }
                ty += 18;
            }
            return;
        }
        if (r.inventory() == null) {
            center(g, "Rift inventory API is disabled.", rx, rw, DIM);
            return;
        }
        CompletableFuture<RiftInventory> inf = riftInventories.computeIfAbsent(p, prof -> CompletableFuture.supplyAsync(() -> {
            JsonObject inv = r.inventory();
            List<ItemStack> ender = LegacyItems.decodeInventory(inv.get("ender_chest_contents"));
            List<List<ItemStack>> pages = new ArrayList<>();
            for (int i = 0; i < ender.size(); i += 45) {
                pages.add(new ArrayList<>(ender.subList(i, Math.min(ender.size(), i + 45))));
            }
            return new RiftInventory(LegacyItems.decodeInventory(inv.get("inv_contents")), LegacyItems.decodeInventory(inv.get("inv_armor")),
                    LegacyItems.decodeInventory(inv.get("equipment_contents")), pages);
        }, ProfileViewerApi.EXECUTOR).exceptionally(t -> null));
        RiftInventory inv = ExtraTables.now(inf);
        if (inv == null) {
            center(g, inf.isDone() ? "Couldn't decode the Rift items." : "Decoding items" + s.dots(), rx, rw, inf.isDone() ? BAD : VALUE);
            return;
        }
        int gridX = rx + (rw - 9 * 18) / 2;
        int top = s.contentY + 22;
        if (view == 1) {
            title(g, "Rift Inventory", rx + 6, s.contentY + 6);
            int sideX = rx + 8;
            for (int i = 0; i < 4; i++) {
                s.slot(g, sideX, top + i * 18, inv.armor().size() == 4 ? inv.armor().get(3 - i) : ItemStack.EMPTY, mx, my, false);
                s.slot(g, sideX + 20, top + i * 18, ProfileViewerScreen.get(inv.equipment(), i), mx, my, false);
            }
            int mainX = Math.max(sideX + 46, gridX);
            for (int i = 9; i < 36; i++) {
                s.slot(g, mainX + ((i - 9) % 9) * 18, top + ((i - 9) / 9) * 18, ProfileViewerScreen.get(inv.inventory(), i), mx, my, false);
            }
            for (int i = 0; i < 9; i++) {
                s.slot(g, mainX + i * 18, top + 3 * 18 + 6, ProfileViewerScreen.get(inv.inventory(), i), mx, my, false);
            }
        } else {
            if (inv.enderChest().isEmpty()) {
                title(g, "Rift Ender Chest", rx + 6, s.contentY + 6);
                center(g, "Empty.", rx, rw, DIM);
                return;
            }
            int idx = pager(g, "Rift Ender Chest", rx, s.contentY + 1, rw, inv.enderChest().size(), mx, my);
            s.grid(g, inv.enderChest().get(idx), gridX, top, mx, my, -1);
        }
    }

    private int listStat(GuiGraphicsExtractor g, int x, int y, int w, String label, List<String> have, List<String> all,
                         String missingLabel, int mx, int my) {
        int ly = y;
        y = s.stat2(g, x, y, w, label, have.size() + " / " + all.size());
        if (hover(mx, my, x, ly, w, 10)) {
            List<Component> t = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String a : all) {
                if (!have.contains(a)) {
                    missing.add(a);
                }
            }
            t.add(line(missing.isEmpty() ? "All found" : missingLabel + " (" + missing.size() + ")", missing.isEmpty() ? GOOD : TEXT));
            for (String m : missing) {
                t.add(line(SbProfile.titleCase(m), DIM));
            }
            tip(t);
        }
        return y;
    }

    // ------------------------------------------------------------------ farming / garden

    private static final Map<String, String> CROP_NAMES = Map.of("INK_SACK:3", "Cocoa Beans", "CARROT_ITEM", "Carrot",
            "POTATO_ITEM", "Potato", "MUSHROOM_COLLECTION", "Mushroom", "NETHER_STALK", "Nether Wart", "DOUBLE_PLANT", "Sunflower",
            "MELON", "Melon");
    private static final List<String> MEDALS = List.of("diamond", "platinum", "gold", "silver", "bronze");

    private void drawFarming(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        ProfileExtras.Farming fa = ex.farming;
        JsonObject gardenTables = ExtraTables.section("garden");
        CompletableFuture<ProfileViewerApi.AuxResult> gf = aux(ProfileViewerApi.AuxKind.GARDEN, p.profileId);
        JsonObject garden = gf.isDone() && !gf.isCompletedExceptionally() && gf.getNow(null) != null ? gf.getNow(null).data() : null;

        int lw = 150;
        s.box(g, s.contentX, s.contentY, lw, s.contentH);
        int x = s.contentX + 6;
        int w = lw - 12;
        int y = s.contentY + 5;
        title(g, "Garden", x, y);
        y += 12;
        if (garden != null) {
            long xp = (long) SbProfile.numPublic(garden.get("garden_experience"));
            long[] levels = ExtraTables.cumulative(ExtraTables.longs(gardenTables.get("garden_level")));
            s.bar(g, x, y, w, new ItemStack(Items.SUNFLOWER), "Garden Level", thresholdLevel(levels, xp), mx, my, xp);
            y += 21;
            JsonObject comm = SbProfile.asObjPublic(garden.get("commission_data"));
            y = s.stat2(g, x, y, w, "Visitors Served", commas(comm == null ? 0 : SbProfile.numPublic(comm.get("unique_npcs_served"))));
            y = s.stat2(g, x, y, w, "Offers Accepted", commas(comm == null ? 0 : SbProfile.numPublic(comm.get("total_completed"))));
        } else if (!gf.isDone()) {
            text(g, "Loading garden" + s.dots(), x, y, VALUE);
            y += 11;
        } else {
            int ly = y;
            text(g, gf.isCompletedExceptionally() ? "Garden unavailable" : "No garden", x, y, DIM);
            if (gf.isCompletedExceptionally() && hover(mx, my, x, ly, w, 10)) {
                String msg = "";
                try {
                    gf.join();
                } catch (Exception e) {
                    msg = ProfileViewerApi.messageFor(e);
                }
                List<Component> t = new ArrayList<>();
                for (String l : msg.split("\n")) {
                    t.add(line(l, DIM));
                }
                t.add(line("Click to retry", ACCENT));
                tip(t);
            }
            if (gf.isCompletedExceptionally()) {
                s.hotspots.add(new ProfileViewerScreen.Hotspot(x, ly, w, 10, () -> aux.remove(ProfileViewerApi.AuxKind.GARDEN.name() + ":" + p.profileId)));
            }
            y += 11;
        }
        y = s.stat2(g, x, y, w, "Copper", commas(fa.copper()));
        int larvaMax = gardenTables.has("max_larva_consumed") ? gardenTables.get("max_larva_consumed").getAsInt() : 5;
        y = s.stat2(g, x, y, w, "Larva Consumed", fa.larvaConsumed() + " / " + larvaMax);
        y += 4;
        title(g, "Jacob's Contests", x, y);
        y += 12;
        if (!fa.present()) {
            text(g, "No contest data", x, y, DIM);
        } else {
            y = s.stat2(g, x, y, w, "Contests Entered", commas(fa.contests()));
            int my0 = y;
            y = s.stat2(g, x, y, w, "Medals", fa.medalInventory().get("gold") + "G " + fa.medalInventory().get("silver") + "S "
                    + fa.medalInventory().get("bronze") + "B");
            if (hover(mx, my, x, my0, w, 10)) {
                List<Component> t = new ArrayList<>();
                t.add(line("Medal inventory", VALUE));
                for (Map.Entry<String, Integer> e : fa.medalInventory().entrySet()) {
                    t.add(line(SbProfile.titleCase(e.getKey()) + ": " + e.getValue(), TEXT));
                }
                t.add(line("Medals earned", VALUE));
                for (Map.Entry<String, Integer> e : fa.medalsEarned().entrySet()) {
                    t.add(line(SbProfile.titleCase(e.getKey()) + ": " + commas(e.getValue()), DIM));
                }
                tip(t);
            }
            y = s.stat2(g, x, y, w, "Double Drops", fa.doubleDrops() + " / 15");
            y = s.stat2(g, x, y, w, "Farming Level Cap", fa.levelCapPerk() + " / 10");
            s.stat2(g, x, y, w, "Personal Bests", fa.personalBestsPerk() ? "Unlocked" : "Locked");
        }

        // Crops table
        int rx = s.contentX + lw + 6;
        int rw = s.contentW - lw - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        JsonObject milestonesObj = gardenTables.has("crop_milestones") ? gardenTables.getAsJsonObject("crop_milestones") : new JsonObject();
        JsonObject collected = garden == null ? null : SbProfile.asObjPublic(garden.get("resources_collected"));
        int[] cols = {rx + 22, rx + (int) (rw * 0.33), rx + (int) (rw * 0.52), rx + (int) (rw * 0.69), rx + (int) (rw * 0.86)};
        int ty = s.contentY + 5;
        String[] headers = {"Crop", "Milestone", "Contests", "Bracket", "Best"};
        for (int i = 0; i < headers.length; i++) {
            text(g, headers[i], cols[i], ty, ACCENT);
        }
        ty += 12;
        int rowH = Math.max(11, Math.min(15, (s.contentH - 20) / Math.max(1, milestonesObj.size())));
        int row = 0;
        for (Map.Entry<String, JsonElement> e : milestonesObj.entrySet()) {
            String crop = e.getKey();
            if (ty + rowH > s.contentY + s.contentH) {
                break;
            }
            if (row++ % 2 == 0) {
                g.fill(rx + 2, ty - 2, rx + rw - 2, ty + rowH - 2, 0xFF161616);
            }
            String name = CROP_NAMES.getOrDefault(crop, SbProfile.titleCase(crop));
            ItemStack icon = ItemIcons.forId(crop);
            g.pose().pushMatrix();
            g.pose().translate(rx + 5, ty - 2);
            g.pose().scale(0.7f, 0.7f);
            g.item(icon, 0, 0);
            g.pose().popMatrix();
            text(g, f().plainSubstrByWidth(name, cols[1] - cols[0] - 4), cols[0], ty, TEXT);
            long[] cum = ExtraTables.cumulative(ExtraTables.longs(e.getValue()));
            long amount = collected == null ? -1 : (long) SbProfile.numPublic(collected.get(crop));
            int milestone = amount < 0 ? -1 : countAtOrBelow(cum, amount);
            boolean maxed = milestone >= cum.length && cum.length > 0;
            text(g, milestone < 0 ? "-" : milestone + "/" + cum.length, cols[1], ty, milestone < 0 ? DIM : maxed ? MAXED : VALUE);
            text(g, commas(fa.contestsPerCrop().getOrDefault(crop, 0)), cols[2], ty, VALUE);
            String bracket = "-";
            for (String medal : MEDALS) {
                List<String> crops = fa.uniqueBrackets().get(medal);
                if (crops != null && crops.contains(crop)) {
                    bracket = SbProfile.titleCase(medal);
                    break;
                }
            }
            text(g, bracket, cols[3], ty, medalColor(bracket));
            long pb = fa.personalBests().getOrDefault(crop, 0L);
            text(g, pb > 0 ? shorten(pb) : "-", cols[4], ty, pb > 0 ? VALUE : DIM);
            if (hover(mx, my, rx + 2, ty - 2, rw - 4, rowH)) {
                List<Component> t = new ArrayList<>();
                t.add(line(name, VALUE));
                if (amount >= 0) {
                    t.add(line("Collected: " + commas(amount), TEXT));
                    if (!maxed && cum.length > 0) {
                        long prev = milestone == 0 ? 0 : cum[milestone - 1];
                        t.add(line("Next milestone: " + commas(amount - prev) + " / " + commas(cum[milestone] - prev), DIM));
                    }
                }
                t.add(line("Personal best: " + commas(pb), DIM));
                tip(t);
            }
            ty += rowH;
        }
    }

    private static int medalColor(String medal) {
        return switch (medal) {
            case "Diamond" -> 0xFF55FFFF;
            case "Platinum" -> 0xFF00AAAA;
            case "Gold" -> 0xFFFFAA00;
            case "Silver" -> 0xFFAAAAAA;
            case "Bronze" -> 0xFFFF5555;
            default -> DIM;
        };
    }

    // ------------------------------------------------------------------ networth

    private void drawNetworth(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        CompletableFuture<Map<String, Double>> bins = ExtraTables.lowestBins();
        CompletableFuture<SbProfile.Inventories> inv = p.inventoryApi ? p.inventories() : null;
        CompletableFuture<ProfileViewerApi.AuxResult> museum = aux(ProfileViewerApi.AuxKind.MUSEUM, p.profileId);
        CompletableFuture<MuseumView> museumView = null;
        boolean museumSettled = museum.isDone();
        if (museum.isDone() && !museum.isCompletedExceptionally() && museum.getNow(null) != null && museum.getNow(null).data() != null) {
            JsonObject member = SbProfile.asObjPublic(museum.getNow(null).data().get(ProfileViewerApi.dashless(p.member)));
            if (member != null) {
                museumView = museumView(member);
                museumSettled = museumView.isDone();
            }
        }
        if (!bins.isDone() || (inv != null && !inv.isDone()) || !museumSettled) {
            center(g, "Calculating networth" + s.dots(), s.contentX, s.contentW, VALUE);
            return;
        }
        MuseumView mv = museumView == null ? null : ExtraTables.now(museumView);
        boolean withMuseum = mv != null;
        CompletableFuture<Networth.Result> nf = networth.get(p);
        if (nf == null || (withMuseum && !networthHasMuseum.getOrDefault(p, false))) {
            SbProfile.Inventories inventories = inv == null || inv.isCompletedExceptionally() ? null : inv.getNow(null);
            Map<String, Double> lbin = ExtraTables.now(bins);
            List<ItemStack> museumItems = mv == null ? null : mv.allOwned();
            nf = CompletableFuture.supplyAsync(() -> Networth.calculate(p, inventories, ex, lbin, museumItems), ProfileViewerApi.EXECUTOR)
                    .exceptionally(t -> null);
            networth.put(p, nf);
            networthHasMuseum.put(p, withMuseum);
        }
        Networth.Result r = ExtraTables.now(nf);
        if (r == null) {
            center(g, nf.isDone() ? "Couldn't calculate networth." : "Calculating networth" + s.dots(), s.contentX, s.contentW, nf.isDone() ? BAD : VALUE);
            return;
        }

        int lw = 150;
        s.box(g, s.contentX, s.contentY, lw, s.contentH);
        int x = s.contentX + 6;
        int w = lw - 12;
        int y = s.contentY + 5;
        title(g, "Networth (estimate)", x, y);
        y += 13;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.5f, 1.5f);
        g.text(f(), shorten(r.total()), 0, 0, MAXED, false);
        g.pose().popMatrix();
        if (hover(mx, my, x, y, w, 13)) {
            tip(List.of(line(commas(r.total()) + " coins", VALUE)));
        }
        y += 18;
        y = s.stat2(g, x, y, w, "Priced at", "base value");
        y = s.stat2(g, x, y, w, "Bazaar Prices", commas(r.bazaarPrices()));
        y = s.stat2(g, x, y, w, "Lowest BIN Prices", commas(r.binPrices()));
        y = s.stat2(g, x, y, w, "Museum", r.museumIncluded() ? "Included" : "Not included");
        s.stat2(g, x, y, w, "Inventory API", p.inventoryApi ? "On" : "Off");

        int rx = s.contentX + lw + 6;
        int rw = s.contentW - lw - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        title(g, "Categories", rx + 6, s.contentY + 5);
        int top = s.contentY + 18;
        int rowH = 16;
        int visible = Math.max(1, (s.contentH - 22) / rowH);
        List<Networth.Category> cats = r.categories();
        int scroll = clampScroll(cats.size(), visible);
        long maxCat = cats.isEmpty() ? 1 : Math.max(1, cats.get(0).total());
        for (int i = scroll; i < cats.size() && i - scroll < visible; i++) {
            Networth.Category c = cats.get(i);
            int cy = top + (i - scroll) * rowH;
            int cw = rw - 20;
            text(g, c.name(), rx + 6, cy, c.total() > 0 ? TEXT : DIM);
            String val = shorten(c.total()) + (r.total() > 0 ? String.format(Locale.ROOT, "  %.1f%%", 100.0 * c.total() / r.total()) : "");
            rightText(g, val, rx + 6 + cw, cy, VALUE);
            progressBar(g, rx + 6, cy + 10, (int) Math.max(0, (long) cw * c.total() / maxCat), 1, false);
            if (hover(mx, my, rx + 4, cy - 1, cw + 4, rowH)) {
                List<Component> t = new ArrayList<>();
                t.add(line(c.name() + ": " + commas(c.total()), VALUE));
                for (Map.Entry<String, Long> e : c.top()) {
                    t.add(line(f().plainSubstrByWidth(LegacyText.strip(e.getKey()), 180) + "  " + shorten(e.getValue()), DIM));
                }
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, cats.size(), visible);
    }

    // ------------------------------------------------------------------ misc

    private void drawMisc(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        ProfileExtras ex = extras(g, p);
        if (ex == null) {
            return;
        }
        ProfileExtras.Misc m = ex.misc;
        int navW = 84;
        s.box(g, s.contentX, s.contentY, navW, s.contentH);
        int view = nav(g, new String[]{"General", "Kills", "Deaths", "Sacks"}, s.contentX + 4, s.contentY + 4, navW - 8, mx, my);
        int rx = s.contentX + navW + 6;
        int rw = s.contentW - navW - 6;
        s.box(g, rx, s.contentY, rw, s.contentH);
        switch (view) {
            case 0 -> drawGeneral(g, p, m, rx, rw, mx, my);
            case 1 -> drawCounts(g, "Kills", m.kills(), rx, rw, false, mx, my);
            case 2 -> drawCounts(g, "Deaths", m.deaths(), rx, rw, false, mx, my);
            default -> {
                if (!m.sacksApi()) {
                    center(g, "Inventory API is disabled.", rx, rw, DIM);
                } else {
                    drawCounts(g, "Sacks", m.sacks(), rx, rw, true, mx, my);
                }
            }
        }
    }

    private void drawGeneral(GuiGraphicsExtractor g, SbProfile p, ProfileExtras.Misc m, int rx, int rw, int mx, int my) {
        int x = rx + 6;
        int w = (rw - 24) / 2;
        int y = s.contentY + 5;
        title(g, "Player", x, y);
        y += 12;
        text(g, "Rank", x, y, DIM);
        CompletableFuture<ProfileViewerApi.AuxResult> pf = aux(ProfileViewerApi.AuxKind.PLAYER, p.member.toString());
        if (!pf.isDone()) {
            rightText(g, "..." , x + w, y, DIM);
        } else if (pf.isCompletedExceptionally() || pf.getNow(null) == null) {
            rightText(g, "Unavailable", x + w, y, DIM);
        } else {
            Component rank = rank(pf.getNow(null).data());
            g.text(f(), rank, x + w - f().width(rank), y, 0xFFFFFFFF, false);
        }
        y += 11;
        y = s.stat2(g, x, y, w, "First Join", date(p.firstJoin));
        y = s.stat2(g, x, y, w, "SkyBlock XP", commas(m.sbXp()));
        y = s.stat2(g, x, y, w, "Cookie Buff", m.cookieBuff() ? "Active" : "Inactive");
        long kills = m.kills().getOrDefault("total", m.kills().values().stream().mapToLong(Long::longValue).max().orElse(0));
        long deaths = m.deaths().getOrDefault("total", m.deaths().values().stream().mapToLong(Long::longValue).max().orElse(0));
        y = s.stat2(g, x, y, w, "Kills", commas(kills));
        y = s.stat2(g, x, y, w, "Deaths", commas(deaths));
        y = s.stat2(g, x, y, w, "K/D", deaths == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) kills / deaths));
        y = s.stat2(g, x, y, w, "Highest Crit", shorten(m.highestCrit()));
        y = s.stat2(g, x, y, w, "Items Fished", commas(m.itemsFished()));
        y = s.stat2(g, x, y, w, "Mythos Kills", commas(m.mythosKills()));
        y = s.stat2(g, x, y, w, "Magical Power", commas(m.magicalPower()));
        if (!m.selectedPower().isEmpty()) {
            y = s.stat2(g, x, y, w, "Power", SbProfile.titleCase(m.selectedPower()));
        }
        y = s.stat2(g, x, y, w, "Minions Crafted", commas(m.minionsCrafted()));
        y = s.stat2(g, x, y, w, "Fairy Exchanges", commas(m.fairyExchanges()));
        int giftsY = y;
        y = s.stat2(g, x, y, w, "Gifts Given", commas(m.giftsGiven()));
        if (hover(mx, my, x, giftsY, w, 10)) {
            tip(List.of(line("Gifts received: " + commas(m.giftsReceived()), TEXT)));
        }

        int ex0 = x + w + 12;
        int ey = s.contentY + 5;
        title(g, "Essence", ex0, ey);
        ey += 12;
        if (m.essence().isEmpty()) {
            text(g, "None", ex0, ey, DIM);
            ey += 11;
        }
        for (Map.Entry<String, Long> e : m.essence().entrySet()) {
            ey = s.stat2(g, ex0, ey, w, SbProfile.titleCase(e.getKey()), commas(e.getValue()));
        }
        ey += 4;
        title(g, "Auctions", ex0, ey);
        ey += 12;
        if (m.auctions().isEmpty()) {
            text(g, "None", ex0, ey, DIM);
        }
        for (String key : List.of("created", "completed", "bids", "won", "gold_spent", "gold_earned", "highest_bid")) {
            if (ey > s.contentY + s.contentH - 11) {
                break;
            }
            Long v = m.auctions().get(key);
            if (v != null) {
                boolean coins = key.startsWith("gold") || key.equals("highest_bid");
                ey = s.stat2(g, ex0, ey, w, SbProfile.titleCase(key), coins ? shorten(v) : commas(v));
            }
        }
    }

    private void drawCounts(GuiGraphicsExtractor g, String label, Map<String, Long> counts, int rx, int rw, boolean icons, int mx, int my) {
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (!e.getKey().equals("total") && e.getValue() > 0) {
                list.add(e);
            }
        }
        title(g, label, rx + 6, s.contentY + 5);
        Long total = counts.get("total");
        rightText(g, total != null ? "Total " + commas(total) : list.size() + " entries", rx + rw - 8, s.contentY + 5, VALUE);
        if (list.isEmpty()) {
            center(g, "None.", rx, rw, DIM);
            return;
        }
        int rowH = icons ? 18 : 11;
        int top = s.contentY + 18;
        int visible = Math.max(1, (s.contentH - 22) / rowH);
        int rows = (list.size() + 1) / 2;
        int scroll = clampScroll(rows, visible);
        int colW = (rw - 24) / 2;
        for (int i = scroll * 2; i < list.size(); i++) {
            int row = i / 2 - scroll;
            if (row >= visible) {
                break;
            }
            Map.Entry<String, Long> e = list.get(i);
            int x = rx + 6 + (i % 2) * (colW + 8);
            int y = top + row * rowH;
            int tx = x;
            if (icons) {
                g.item(ItemIcons.forId(e.getKey()), x, y);
                tx += 19;
                y += 4;
            }
            String val = shorten(e.getValue());
            String name = SbProfile.titleCase(e.getKey());
            text(g, f().plainSubstrByWidth(name, x + colW - tx - f().width(val) - 6), tx, y, TEXT);
            rightText(g, val, x + colW, y, VALUE);
            if (hover(mx, my, x, top + row * rowH, colW, rowH)) {
                tip(List.of(line(name, VALUE), line(commas(e.getValue()), TEXT)));
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, rows, visible);
    }

    /** Hypixel network rank prefix from {@code /v2/player} (same precedence Hypixel uses). */
    private static Component rank(JsonObject player) {
        if (player == null) {
            return LegacyText.parse("§7None");
        }
        String prefix = str(player, "prefix");
        if (prefix != null && !prefix.isBlank()) {
            return LegacyText.parse(prefix);
        }
        String special = str(player, "rank");
        if (special != null && !special.equals("NORMAL")) {
            String s = switch (special) {
                case "ADMIN" -> "§c[ADMIN]";
                case "GAME_MASTER" -> "§2[GM]";
                case "MODERATOR" -> "§2[MOD]";
                case "HELPER" -> "§9[HELPER]";
                case "YOUTUBER" -> "§c[§fYOUTUBE§c]";
                default -> "§7[" + special + "]";
            };
            return LegacyText.parse(s);
        }
        String plus = colorCode(str(player, "rankPlusColor"), "§c");
        if ("SUPERSTAR".equals(str(player, "monthlyPackageRank"))) {
            String base = "AQUA".equals(str(player, "monthlyRankColor")) ? "§b" : "§6";
            return LegacyText.parse(base + "[MVP" + plus + "++" + base + "]");
        }
        String pkg = str(player, "newPackageRank");
        if (pkg == null || pkg.equals("NONE")) {
            pkg = str(player, "packageRank");
        }
        String s = pkg == null ? "§7None" : switch (pkg) {
            case "MVP_PLUS" -> "§b[MVP" + plus + "+§b]";
            case "MVP" -> "§b[MVP]";
            case "VIP_PLUS" -> "§a[VIP§6+§a]";
            case "VIP" -> "§a[VIP]";
            default -> "§7None";
        };
        return LegacyText.parse(s);
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String colorCode(String name, String def) {
        if (name == null) {
            return def;
        }
        net.minecraft.ChatFormatting fmt = net.minecraft.ChatFormatting.getByName(name.toLowerCase(Locale.ROOT));
        return fmt == null || !fmt.isColor() ? def : "§" + fmt.getChar();
    }
}
