package com.killer560.hub.lfg;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Custom Party Finder menu ({@code /cpf}): Dungeons / Diana / Fishing tabs of everyone's listings with a Join
 * button each, the player's own join requests waiting on his listing (Accept / Decline), and a Create page whose activity,
 * party size, requirements and note start from what he last used on that tab.
 */
public class LfgScreen extends Screen implements MenuThemedScreen {

    private static final int ROW_H = 24;
    private static final int INBOX_ROW_H = 20;
    private static final int INBOX_ROWS = 3;

    private final Screen parent;
    private LfgCategory tab;
    private boolean creating = false;
    private int page = 0;
    private int seenVersion = -1;
    private String message = "";

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int inboxY;
    private List<LfgListing> shown = List.of();
    private List<LfgListing.Request> inboxShown = List.of();

    /** The Create page's boxes, read when List Party is pressed. */
    private final Map<String, EditBox> reqBoxes = new LinkedHashMap<>();
    private EditBox noteBox;

    public LfgScreen(Screen parent) {
        super(Component.literal("Custom Party Finder"));
        this.parent = parent;
        this.tab = LfgConfig.getInstance().getLastTab();
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(340, Math.min((int) (this.width * 0.75), 560)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.85), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        LfgFeature.setScreen(true, tab);
        seenVersion = LfgFeature.version();

        // Tabs, then Create / Unlist on the right.
        int x = panelX + 6;
        int tabY = panelY + 28;
        int tabW = 58;
        for (LfgCategory c : LfgCategory.values()) {
            String label = (c == tab && !creating ? "§6§n" : "") + c.label;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(label), btn -> {
                tab = c;
                creating = false;
                page = 0;
                LfgConfig cfg = LfgConfig.getInstance();
                cfg.setLastTab(c);
                cfg.save();
                LfgFeature.setScreen(true, c);
                rebuildWidgets();
            }).bounds(x, tabY, tabW, 18).build());
            x += tabW + 4;
        }
        int rightX = panelX + panelW - 6;
        if (LfgFeature.isListed()) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§cUnlist"), btn -> {
                LfgFeature.unlist("unlisted");
                rebuildWidgets();
            }).bounds(rightX - 60, tabY, 60, 18).build());
            rightX -= 64;
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(creating ? "< Back" : "Create"), btn -> {
            creating = !creating;
            message = creating ? "New " + tab.label + " listing - it starts from what you used last." : "";
            rebuildWidgets();
        }).bounds(rightX - 60, tabY, 60, 18).build());

        listX = panelX + 6;
        listY = panelY + 52;
        listW = panelW - 12;
        boolean inbox = LfgFeature.isListed() && !creating;
        int bottom = panelY + panelH - 18;
        inboxY = inbox ? bottom - (14 + INBOX_ROWS * INBOX_ROW_H) : bottom;
        listH = Math.max(ROW_H, inboxY - 4 - listY - (creating ? 0 : 20));

        if (creating) {
            buildCreate();
        } else {
            buildList();
            if (inbox) {
                buildInbox();
            }
        }
    }

    // ---- browse ----------------------------------------------------------------------------------------------------

    private int rowsPerPage() {
        return Math.max(1, listH / ROW_H);
    }

    private void buildList() {
        List<LfgListing> all = LfgFeature.listings(tab);
        int per = rowsPerPage();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        shown = all.subList(Math.min(all.size(), page * per), Math.min(all.size(), (page + 1) * per));
        String self = this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getGameProfile().name() : "";
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
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(label), btn -> {
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
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Prev"), btn -> {
                page--;
                rebuildWidgets();
            }).bounds(listX, barY, 54, 16).build());
        }
        if (page < pages - 1) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Next >"), btn -> {
                page++;
                rebuildWidgets();
            }).bounds(listX + listW - 54, barY, 54, 16).build());
        }
    }

    private void buildInbox() {
        List<LfgListing.Request> all = LfgFeature.inbox();
        inboxShown = all.subList(0, Math.min(INBOX_ROWS, all.size()));
        for (int i = 0; i < inboxShown.size(); i++) {
            LfgListing.Request r = inboxShown.get(i);
            int rowY = inboxY + 14 + i * INBOX_ROW_H;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aAccept"), btn -> {
                String refused = LfgFeature.accept(r.id());
                message = refused == null ? "Invited " + r.joiner() + "." : refused;
                rebuildWidgets();
            }).bounds(listX + listW - 124, rowY + 1, 58, 18).build());
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
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Activity: §6" + d.activity), (btn, dir) -> {
            int at = Math.max(0, tab.activities.indexOf(d.activity));
            d.activity = tab.activities.get(SettingsButtonWidget.step(at, dir, tab.activities.size()));
            cfg.save();
            btn.setMessage(Component.literal("Activity: §6" + d.activity));
        }).bounds(listX, y, colW, 18).build());
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
            ThemedEditBox box = new ThemedEditBox(this.font, bx, by, colW, 18, Component.literal(f.label()))
                    .hint(f.label() + " (0 = none)");
            box.setMaxLength(7);
            Integer v = d.reqs.get(f.key());
            box.setValue(v == null || v <= 0 ? "" : String.valueOf(v));
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
        Map<String, Integer> reqs = new LinkedHashMap<>();
        for (LfgCategory.Field f : tab.fields) {
            EditBox box = reqBoxes.get(f.key());
            int v = 0;
            try {
                String digits = box == null ? "" : box.getValue().replaceAll("[^0-9]", "");
                v = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                // at most 7 characters, so at most 7 digits; cannot overflow
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
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Custom Party Finder");
        LfgListing mine = LfgFeature.myListing();
        String right = mine != null ? "Listed: " + mine.category().label + " " + mine.activity() + " " + mine.size() + "/" + mine.max()
                : LfgFeature.isListed() ? "Listing..." : "/cpf";
        ScreenChrome.headerRight(graphics, panelX, panelY, panelW, right, MenuStyle.DIM);

        ScreenChrome.well(graphics, listX - 1, listY - 1, listW + 2, listH + 2);
        if (!creating) {
            drawList(graphics);
            if (LfgFeature.isListed()) {
                drawInbox(graphics);
            }
        }
        String status = !message.isEmpty() ? message : LfgFeature.status();
        if (!status.isEmpty()) {
            ScreenChrome.text(graphics, this.font.plainSubstrByWidth(status, listW), listX, panelY + panelH - 13, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawList(GuiGraphicsExtractor g) {
        if (shown.isEmpty()) {
            ScreenChrome.text(g, "No " + tab.label + " listings right now. Create one, or check back soon.", listX + 4,
                    listY + 6, MenuStyle.DIM);
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
            for (LfgRequirements.Check c : LfgRequirements.check(l.category(), l.reqs(), selfName())) {
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
        List<LfgListing.Request> all = LfgFeature.inbox();
        ScreenChrome.text(g, "Join requests (" + all.size() + ")", listX, inboxY + 2, MenuStyle.OR);
        LfgListing mine = LfgFeature.myListing();
        for (int i = 0; i < inboxShown.size(); i++) {
            LfgListing.Request r = inboxShown.get(i);
            int y = inboxY + 14 + i * INBOX_ROW_H;
            StringBuilder line = new StringBuilder((r.verified() ? "§e" : "§7") + r.joiner() + "  ");
            if (mine != null) {
                for (LfgRequirements.Check c : LfgRequirements.check(mine.category(), mine.reqs(), r.joiner())) {
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
}
