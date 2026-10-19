package com.killer560.hub.lfg;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Custom Party Finder menu ({@code /cpf}). It opens on a start screen where he picks the activity to look at
 * (Dungeons, Diana, Fishing, Misc - killer560, 2026-10-18); inside an activity only its listings show, with a search box,
 * filters and a sort (kept in {@link LfgConfig}), a Join button each, a Back button to the start screen and a Create page
 * whose activity, party size, requirements and note start from what he last used there. The player's own listing's join
 * requests show on both screens: Accept only on a request that meets his requirements, a rejected one says why.
 */
public class LfgScreen extends Screen implements MenuThemedScreen {

    private static final int ROW_H = 24;
    private static final int INBOX_ROW_H = 20;
    private static final int INBOX_ROWS = 3;

    private final Screen parent;
    /** null: the start screen. */
    private LfgCategory tab;
    private boolean creating = false;
    private int page = 0;
    private int seenVersion = -1;
    private String message = "";
    private String query = "";

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int inboxY;
    private List<LfgListing> shown = List.of();
    private List<LfgFeature.InboxRow> inboxShown = List.of();
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private EditBox searchBox;

    /** The Create page's boxes, read when List Party is pressed. */
    private final Map<String, EditBox> reqBoxes = new LinkedHashMap<>();
    private EditBox noteBox;
    private EditBox activityBox;

    /** The start screen. */
    public LfgScreen(Screen parent) {
        this(parent, null);
    }

    /** Straight into one activity (null: the start screen). */
    public LfgScreen(Screen parent, LfgCategory tab) {
        super(Component.literal("Custom Party Finder"));
        this.parent = parent;
        this.tab = tab;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(340, Math.min((int) (this.width * 0.75), 560)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.85), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        LfgFeature.setScreen(true, tab);
        seenVersion = LfgFeature.version();
        rowWidgets.clear();
        searchBox = null;

        listX = panelX + 6;
        listW = panelW - 12;
        int topY = panelY + 28;
        int rightX = panelX + panelW - 6;
        if (LfgFeature.isListed()) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§cUnlist"), btn -> {
                LfgFeature.unlist("unlisted");
                rebuildWidgets();
            }).bounds(rightX - 60, topY, 60, 18).build());
            rightX -= 64;
        }
        boolean inbox = LfgFeature.isListed() && !creating;
        int bottom = panelY + panelH - 18;
        inboxY = inbox ? bottom - (14 + INBOX_ROWS * INBOX_ROW_H) : bottom;

        if (tab == null) {
            buildStart(topY);
            listY = topY + 22;
            listH = Math.max(ROW_H, inboxY - 4 - listY);
            if (inbox) {
                buildInbox();
            }
            return;
        }

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
            tab = null;
            creating = false;
            page = 0;
            query = "";
            message = "";
            rebuildWidgets();
        }).bounds(panelX + 6, topY, 54, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(creating ? "< Listings" : "Create"), btn -> {
            creating = !creating;
            message = creating ? "New " + tab.label + " listing - it starts from what you used last." : "";
            rebuildWidgets();
        }).bounds(rightX - 64, topY, 64, 18).build());

        if (creating) {
            listY = topY + 24;
            listH = Math.max(ROW_H, inboxY - 4 - listY);
            buildCreate();
            return;
        }
        int filterY = topY + 22;
        buildFilters(filterY);
        listY = filterY + 22;
        listH = Math.max(ROW_H, inboxY - 4 - listY - 20);
        buildList();
        if (inbox) {
            buildInbox();
        }
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    private void buildStart(int topY) {
        LfgCategory[] cats = LfgCategory.values();
        int cols = 2;
        int bw = Math.min(160, (listW - 12) / cols);
        int bh = 28;
        int gridW = cols * bw + (cols - 1) * 8;
        int x0 = panelX + (panelW - gridW) / 2;
        int y0 = topY + 30;
        for (int i = 0; i < cats.length; i++) {
            LfgCategory c = cats[i];
            int bx = x0 + (i % cols) * (bw + 8);
            int by = y0 + (i / cols) * (bh + 8);
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(c.label), btn -> openTab(c))
                    .bounds(bx, by, bw, bh).build());
        }
    }

    private void openTab(LfgCategory c) {
        tab = c;
        creating = false;
        page = 0;
        query = "";
        message = "";
        LfgConfig cfg = LfgConfig.getInstance();
        cfg.setLastTab(c);
        cfg.save();
        LfgFeature.setScreen(true, c);
        rebuildWidgets();
    }

    // ---- browse ----------------------------------------------------------------------------------------------------

    private void buildFilters(int y) {
        LfgConfig cfg = LfgConfig.getInstance();
        int searchW = Math.max(90, listW / 3);
        searchBox = new ThemedEditBox(this.font, listX, y, searchW, 18, Component.literal("Search")).hint("Search name, activity, note");
        searchBox.setMaxLength(40);
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            page = 0;
            refreshList();
        });
        addRenderableWidget(searchBox);
        boolean floors = tab.activities.size() > 1;
        int n = floors ? 4 : 3;
        int x = listX + searchW + 4;
        int w = (listX + listW - x - (n - 1) * 4) / n;
        if (floors) {
            List<String> options = new ArrayList<>();
            options.add("");
            options.addAll(tab.activities);
            addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(activityLabel(cfg.getActivityFilter(tab))), (btn, dir) -> {
                int at = Math.max(0, options.indexOf(cfg.getActivityFilter(tab)));
                String next = options.get(SettingsButtonWidget.step(at, dir, options.size()));
                cfg.setActivityFilter(tab, next);
                cfg.save();
                btn.setMessage(Component.literal(activityLabel(next)));
                page = 0;
                refreshList();
            }).bounds(x, y, w, 18).build());
            x += w + 4;
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(cfg.isMeetsOnly() ? "§6I meet" : "Any reqs"), btn -> {
            cfg.setMeetsOnly(!cfg.isMeetsOnly());
            cfg.save();
            btn.setMessage(Component.literal(cfg.isMeetsOnly() ? "§6I meet" : "Any reqs"));
            page = 0;
            refreshList();
        }).bounds(x, y, w, 18).build());
        x += w + 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(cfg.isHideFull() ? "Full: hide" : "Full: show"), btn -> {
            cfg.setHideFull(!cfg.isHideFull());
            cfg.save();
            btn.setMessage(Component.literal(cfg.isHideFull() ? "Full: hide" : "Full: show"));
            page = 0;
            refreshList();
        }).bounds(x, y, w, 18).build());
        x += w + 4;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(cfg.getSort().label), (btn, dir) -> {
            LfgFilter.Sort[] all = LfgFilter.Sort.values();
            LfgFilter.Sort next = all[SettingsButtonWidget.step(cfg.getSort().ordinal(), dir, all.length)];
            cfg.setSort(next);
            cfg.save();
            btn.setMessage(Component.literal(next.label));
            page = 0;
            refreshList();
        }).bounds(x, y, listX + listW - x, 18).build());
    }

    private static String activityLabel(String a) {
        return a == null || a.isEmpty() ? "Any floor" : "§6" + a;
    }

    private int rowsPerPage() {
        return Math.max(1, listH / ROW_H);
    }

    /** The listings this page shows after search, filters and sort. */
    private List<LfgListing> filtered() {
        LfgConfig cfg = LfgConfig.getInstance();
        return LfgFilter.apply(LfgFeature.listings(tab), query, cfg.getActivityFilter(tab), cfg.isMeetsOnly(),
                cfg.isHideFull(), cfg.getSort(), selfName());
    }

    /** Rebuilds only the rows (so the search box keeps its focus while he types). */
    private void refreshList() {
        for (AbstractWidget w : rowWidgets) {
            removeWidget(w);
        }
        rowWidgets.clear();
        buildList();
    }

    private <T extends AbstractWidget> T addRow(T w) {
        rowWidgets.add(w);
        return addRenderableWidget(w);
    }

    private void buildList() {
        List<LfgListing> all = filtered();
        int per = rowsPerPage();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        shown = all.subList(Math.min(all.size(), page * per), Math.min(all.size(), (page + 1) * per));
        String self = selfName() == null ? "" : selfName();
        for (int i = 0; i < shown.size(); i++) {
            LfgListing l = shown.get(i);
            int rowY = listY + i * ROW_H;
            String state = LfgFeature.myRequestStateFor(l.id());
            String label;
            boolean active = false;
            if (l.leader().equalsIgnoreCase(self)) {
                label = "§7You";
            } else if ("accepted".equals(state)) {
                label = "§aAccepted";
            } else if ("declined".equals(state)) {
                label = "§cDeclined";
            } else if (state != null) {
                label = "§7Asked";
            } else {
                label = "Join";
                active = true;
            }
            boolean clickable = active;
            addRow(SettingsButtonWidget.builder(Component.literal(label), btn -> {
                if (!clickable) {
                    return;
                }
                String refused = LfgFeature.requestJoin(l);
                message = refused == null ? "Asked " + l.leader() + " to join." : refused;
                rebuildWidgets();
            }).bounds(listX + listW - 66, rowY + 3, 60, 18).build());
        }
        int barY = listY + listH + 2;
        if (page > 0) {
            addRow(SettingsButtonWidget.builder(Component.literal("< Prev"), btn -> {
                page--;
                refreshList();
            }).bounds(listX, barY, 54, 16).build());
        }
        if (page < pages - 1) {
            addRow(SettingsButtonWidget.builder(Component.literal("Next >"), btn -> {
                page++;
                refreshList();
            }).bounds(listX + listW - 54, barY, 54, 16).build());
        }
    }

    private void buildInbox() {
        List<LfgFeature.InboxRow> all = LfgFeature.inboxRows();
        inboxShown = all.subList(0, Math.min(INBOX_ROWS, all.size()));
        for (int i = 0; i < inboxShown.size(); i++) {
            LfgFeature.InboxRow row = inboxShown.get(i);
            LfgListing.Request r = row.request();
            int rowY = inboxY + 14 + i * INBOX_ROW_H;
            if (row.verdict() == LfgFeature.Verdict.REJECTED) {
                continue; // already turned away: nothing to click
            }
            if (row.verdict() == LfgFeature.Verdict.OK) {
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aAccept"), btn -> {
                    String refused = LfgFeature.accept(r.id());
                    message = refused == null ? "Invited " + r.joiner() + "." : refused;
                    rebuildWidgets();
                }).bounds(listX + listW - 124, rowY + 1, 58, 18).build());
            }
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§cDecline"), btn -> {
                LfgFeature.decline(r.id());
                rebuildWidgets();
            }).bounds(listX + listW - 62, rowY + 1, 58, 18).build());
        }
    }

    // ---- create ----------------------------------------------------------------------------------------------------

    private void buildCreate() {
        LfgConfig cfg = LfgConfig.getInstance();
        LfgConfig.Draft d = cfg.draft(tab);
        int colW = (listW - 8) / 2;
        int y = listY + 4;
        activityBox = null;
        if (tab == LfgCategory.MISC) {
            activityBox = new ThemedEditBox(this.font, listX, y, colW, 18, Component.literal("Activity")).hint("Activity, e.g. Slayer");
            activityBox.setMaxLength(24);
            activityBox.setValue(d.activity);
            addRenderableWidget(activityBox);
        } else {
            addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Activity: §6" + d.activity), (btn, dir) -> {
                int at = Math.max(0, tab.activities.indexOf(d.activity));
                d.activity = tab.activities.get(SettingsButtonWidget.step(at, dir, tab.activities.size()));
                cfg.save();
                btn.setMessage(Component.literal("Activity: §6" + d.activity));
            }).bounds(listX, y, colW, 18).build());
        }
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Party Size: §6" + d.max), (btn, dir) -> {
            int sizes = tab.maxParty - 1; // 2..max
            d.max = 2 + SettingsButtonWidget.step(d.max - 2, dir, sizes);
            cfg.save();
            btn.setMessage(Component.literal("Party Size: §6" + d.max));
        }).bounds(listX + colW + 8, y, colW, 18).build());
        y += 30;
        reqBoxes.clear();
        int i = 0;
        for (LfgCategory.Field f : tab.fields) {
            int bx = listX + (i % 2) * (colW + 8);
            int by = y + (i / 2) * 30;
            boolean pb = f.key().equals(LfgCategory.PB);
            ThemedEditBox box = new ThemedEditBox(this.font, bx, by, colW, 18, Component.literal(f.label()))
                    .hint(pb ? f.label() + " m:ss, at most (blank = none)" : f.label() + " (0 = none)");
            box.setMaxLength(7);
            Integer v = d.reqs.get(f.key());
            box.setValue(v == null || v <= 0 ? "" : pb ? LfgRequirements.mmSs(v) : String.valueOf(v));
            addRenderableWidget(box);
            reqBoxes.put(f.key(), box);
            i++;
        }
        y += ((tab.fields.size() + 1) / 2) * 30;
        noteBox = new ThemedEditBox(this.font, listX, y, listW, 18, Component.literal("Note"))
                .hint("Note, e.g. requirements in words (80 characters)");
        noteBox.setMaxLength(80);
        noteBox.setValue(d.note);
        addRenderableWidget(noteBox);
        y += 30;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aList Party"), btn -> listParty())
                .bounds(listX, y, colW, 20).build());
    }

    private void listParty() {
        LfgConfig cfg = LfgConfig.getInstance();
        LfgConfig.Draft d = cfg.draft(tab);
        if (activityBox != null) {
            String a = activityBox.getValue().trim();
            if (!LfgCategory.ACTIVITY.matcher(a).matches()) {
                message = "Type what the party is for (letters, numbers, spaces, + and -).";
                return;
            }
            d.activity = a;
        }
        Map<String, Integer> reqs = new LinkedHashMap<>();
        for (LfgCategory.Field f : tab.fields) {
            EditBox box = reqBoxes.get(f.key());
            String text = box == null ? "" : box.getValue();
            int v;
            if (f.key().equals(LfgCategory.PB)) {
                v = LfgRequirements.parsePb(text);
            } else {
                String digits = text.replaceAll("[^0-9]", "");
                v = digits.isEmpty() ? 0 : Integer.parseInt(digits); // at most 7 characters, so it cannot overflow
            }
            v = Math.max(0, Math.min(f.max(), v));
            d.reqs.put(f.key(), v);
            if (v > 0) {
                reqs.put(f.key(), v);
            }
        }
        d.note = LfgConfig.clampNote(noteBox == null ? "" : noteBox.getValue());
        cfg.save();
        LfgFeature.list(LfgFeature.spec(tab, d.activity, d.note, reqs, d.max, false));
        creating = false;
        message = "Listing " + tab.label + " " + d.activity + "...";
        rebuildWidgets();
    }

    // ---- frame -----------------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!creating && LfgFeature.version() != seenVersion) {
            if (searchBox != null && searchBox.isFocused()) {
                seenVersion = LfgFeature.version();
                refreshList(); // keep his typing; the rest catches up when he leaves the box
            } else {
                rebuildWidgets();
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, tab == null ? "Custom Party Finder" : "Custom Party Finder - " + tab.label);
        LfgListing mine = LfgFeature.myListing();
        String right = mine != null ? "Listed: " + mine.category().label + " " + mine.activity() + " " + mine.size() + "/" + mine.max()
                : LfgFeature.isListed() ? "Listing..." : "/cpf";
        ScreenChrome.headerRight(graphics, panelX, panelY, panelW, right, MenuStyle.DIM);

        if (tab == null) {
            ScreenChrome.text(graphics, "Pick an activity to see its parties.", listX + 4, panelY + 34, MenuStyle.DIM);
        } else {
            ScreenChrome.well(graphics, listX - 1, listY - 1, listW + 2, listH + 2);
            if (!creating) {
                drawList(graphics);
            }
        }
        if (LfgFeature.isListed() && !creating) {
            drawInbox(graphics);
        }
        String status = !message.isEmpty() ? message : LfgFeature.status();
        if (!status.isEmpty()) {
            ScreenChrome.text(graphics, this.font.plainSubstrByWidth(status, listW), listX, panelY + panelH - 13, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawList(GuiGraphicsExtractor g) {
        if (shown.isEmpty()) {
            boolean any = !LfgFeature.listings(tab).isEmpty();
            ScreenChrome.text(g, any ? "No " + tab.label + " listings match your search or filters."
                    : "No " + tab.label + " listings right now. Create one, or check back soon.", listX + 4, listY + 6, MenuStyle.DIM);
            return;
        }
        int textW = listW - 76;
        for (int i = 0; i < shown.size(); i++) {
            LfgListing l = shown.get(i);
            int y = listY + i * ROW_H;
            String head = (l.verified() ? "§e" : "§7") + l.leader() + " §f" + l.activity() + " §7"
                    + l.size() + "/" + l.max() + (l.source().equals("hypixel") ? " §8(Hypixel PF)" : "");
            ScreenChrome.text(g, this.font.plainSubstrByWidth(head, textW + 40), listX + 4, y + 3, MenuStyle.TXT);
            StringBuilder sub = new StringBuilder();
            for (LfgRequirements.Check c : LfgRequirements.check(l.category(), l.activity(), l.reqs(), selfName())) {
                sub.append(c.describe()).append("  ");
            }
            if (!l.note().isEmpty()) {
                sub.append("§7").append(l.note());
            }
            if (sub.length() > 0) {
                ScreenChrome.text(g, this.font.plainSubstrByWidth(sub.toString(), textW + 40), listX + 4, y + 13, MenuStyle.DIM);
            }
        }
    }

    private void drawInbox(GuiGraphicsExtractor g) {
        List<LfgFeature.InboxRow> all = LfgFeature.inboxRows();
        ScreenChrome.text(g, "Join requests (" + all.size() + ")", listX, inboxY + 2, MenuStyle.OR);
        LfgListing mine = LfgFeature.myListing();
        for (int i = 0; i < inboxShown.size(); i++) {
            LfgFeature.InboxRow row = inboxShown.get(i);
            LfgListing.Request r = row.request();
            int y = inboxY + 14 + i * INBOX_ROW_H;
            StringBuilder line = new StringBuilder((r.verified() ? "§e" : "§7") + r.joiner() + "  ");
            if (row.verdict() == LfgFeature.Verdict.REJECTED) {
                line.append("§cRejected - ").append(row.why());
            } else if (mine != null) {
                for (LfgRequirements.Check c : LfgRequirements.check(mine.category(), mine.activity(), mine.reqs(), r.joiner())) {
                    line.append(c.describe()).append(' ');
                }
            }
            ScreenChrome.text(g, this.font.plainSubstrByWidth(line.toString(), listW - 132), listX + 2, y + 6, MenuStyle.TXT);
        }
    }

    private String selfName() {
        return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getGameProfile().name() : null;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public void removed() {
        LfgFeature.setScreen(false, tab);
        super.removed();
    }

    @Override
    public void onClose() {
        LfgFeature.setScreen(false, tab);
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Test hook: the rows the list shows on this page. */
    public List<LfgListing> shownForTest() {
        return new ArrayList<>(shown);
    }

    /** Test hook: the activity shown, or null on the start screen. */
    public LfgCategory tabForTest() {
        return tab;
    }

    /** Test hook: the inbox rows drawn. */
    public List<LfgFeature.InboxRow> inboxForTest() {
        return new ArrayList<>(inboxShown);
    }
}
