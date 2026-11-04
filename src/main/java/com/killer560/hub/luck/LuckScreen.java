package com.killer560.hub.luck;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
import com.killer560.hub.gui.profit.ProfitScope;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /luck} (and {@code /profit luck}): How Lucky Are You. Headline cards (overall luck, drops got against expected,
 * how many drops are above and below their odds), the drop list on the left, and on the right the selected drop's odds
 * with a calculator (attempts and Magic Find steppers: left click adds, right click takes away) - or, with nothing
 * selected, the latest drops. Switches: This Profile / All Profiles / Other Mod Users, a group filter, the sort, and
 * which chest odds to use.
 */
public class LuckScreen extends Screen implements MenuThemedScreen {

    enum Scope { PROFILE, ALL, OTHERS }

    enum Group {
        ALL("All Drops"), CHESTS("Dungeon Chests"), SLAYERS("Slayers"), MOBS("Mobs"), SEA("Sea Creatures"),
        KUUDRA("Kuudra"), NUCLEUS("Crystal Nucleus"), EXPERIMENTS("Experiments"), DYES("Dyes"), OTHER("Other");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    enum Sort {
        RAREST("Rarest First"), EXPECTED("Most Expected"), LUCKY("Luckiest First"), UNLUCKY("Unluckiest First"),
        NAME("Name");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    private static final int ROW_H = 12;
    private static final long[] STEPS = {1, 10, 100, 1_000, 10_000};

    private final Screen parent;
    private Scope scope = Scope.PROFILE;
    private Group group = Group.ALL;
    private Sort sort = Sort.RAREST;
    private int scroll;
    private String selected;
    private int selectedOther = -1;
    private long calcAttempts = -1;
    private int calcMf = -1;
    private int stepIndex = 2;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int detailX;
    private int detailY;
    private int detailW;
    private int detailH;
    private int cardsY;

    public LuckScreen(Screen parent) {
        super(Component.literal("How Lucky Are You"));
        this.parent = parent;
    }

    private String profileFilter() {
        return scope == Scope.PROFILE ? ProfitScope.currentSkyblockProfile() : null;
    }

    List<LuckStore.Row> rows() {
        LuckConfig cfg = LuckConfig.getInstance();
        List<LuckStore.Row> out = new ArrayList<>();
        for (LuckStore.Row r : LuckStore.rows(profileFilter(), cfg.getChestOdds(), false)) {
            if (matches(r)) {
                out.add(r);
            }
        }
        Comparator<LuckStore.Row> cmp = switch (sort) {
            case RAREST -> Comparator.comparingDouble(r -> r.drop() == null ? 1.0 : chanceShown(r.drop()));
            case EXPECTED -> Comparator.comparingDouble(LuckStore.Row::expected).reversed();
            case LUCKY -> Comparator.comparingDouble(LuckStore.Row::percentile).reversed();
            case UNLUCKY -> Comparator.comparingDouble(LuckStore.Row::percentile);
            case NAME -> Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT));
        };
        out.sort(cmp.thenComparing(LuckStore.Row::key));
        return out;
    }

    private boolean matches(LuckStore.Row r) {
        String cat = r.drop() == null ? "other" : r.drop().category();
        return switch (group) {
            case ALL -> true;
            case CHESTS -> r.activity().startsWith("chest:");
            case SLAYERS -> r.activity().startsWith("slayer:");
            case MOBS -> r.activity().startsWith("mob:") && !"Sea Creatures".equals(groupOf(r));
            case SEA -> "Sea Creatures".equals(groupOf(r));
            case KUUDRA -> r.activity().startsWith("kuudra:");
            case NUCLEUS -> r.activity().equals("nucleus");
            case EXPERIMENTS -> r.activity().startsWith("experiment:");
            case DYES -> "dye".equals(cat) || r.name().endsWith(" Dye");
            case OTHER -> !r.activity().startsWith("chest:") && !r.activity().startsWith("slayer:")
                    && !r.activity().startsWith("mob:") && !DropTable.claimOnly(r.activity());
        };
    }

    private static String groupOf(LuckStore.Row r) {
        return DropTable.get().activity(r.activity()).group();
    }

    static double chanceShown(DropTable.Drop d) {
        if (d.activity().startsWith("chest:") && LuckConfig.getInstance().getChestOdds() == LuckConfig.ChestOdds.MAX
                && !Double.isNaN(d.chanceMax())) {
            return d.chanceMax();
        }
        return d.chance();
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(380, Math.min((int) (this.width * 0.85), 680)));
        panelH = Math.min(this.height - 20, Math.max(240, Math.min((int) (this.height * 0.9), 380)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int x = panelX + 6;
        int y = panelY + 30;
        int w4 = (panelW - 12 - 3 * 4) / 4;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(scopeLabel()), (btn, dir) -> {
            scope = SettingsButtonWidget.step(scope, dir);
            scroll = 0;
            selected = null;
            selectedOther = -1;
            rebuildWidgets();
        }).bounds(x, y, w4, 16).build());
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(group.label), (btn, dir) -> {
            group = SettingsButtonWidget.step(group, dir);
            scroll = 0;
            rebuildWidgets();
        }).bounds(x + (w4 + 4), y, w4, 16).build());
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(sort.label), (btn, dir) -> {
            sort = SettingsButtonWidget.step(sort, dir);
            rebuildWidgets();
        }).bounds(x + 2 * (w4 + 4), y, w4, 16).build());
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Chests: "
                + LuckConfig.getInstance().getChestOdds().label), (btn, dir) -> {
            LuckConfig cfg = LuckConfig.getInstance();
            cfg.setChestOdds(SettingsButtonWidget.step(cfg.getChestOdds(), dir));
            cfg.save();
            rebuildWidgets();
        }).bounds(x + 3 * (w4 + 4), y, w4, 16).build());
        if (parent != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> onClose())
                    .bounds(panelX + panelW - 62, panelY + 4, 56, 16).build());
        }

        cardsY = y + 20;
        int bodyY = cardsY + 46 + 6;
        listX = panelX + 6;
        listY = bodyY;
        listW = (panelW - 12 - 6) * 11 / 20;
        listH = panelY + panelH - 8 - bodyY;
        detailX = listX + listW + 6;
        detailY = bodyY;
        detailW = panelW - 12 - 6 - listW;
        detailH = listH;

        if (scope != Scope.OTHERS && selected != null) {
            addCalculator();
        }
    }

    private String scopeLabel() {
        return switch (scope) {
            case PROFILE -> "This Profile (" + ProfitScope.currentSkyblockProfile() + ")";
            case ALL -> "All Profiles";
            case OTHERS -> "Other Mod Users";
        };
    }

    private LuckStore.Row selectedRow() {
        if (selected == null) {
            return null;
        }
        for (LuckStore.Row r : LuckStore.rows(profileFilter(), LuckConfig.getInstance().getChestOdds(), false)) {
            if (r.key().equals(selected)) {
                return r;
            }
        }
        return null;
    }

    /** The calculator's steppers under the selected drop's details. */
    private void addCalculator() {
        LuckStore.Row r = selectedRow();
        if (r == null || r.drop() == null || !r.drop().tracked()) {
            return;
        }
        if (calcAttempts < 0) {
            calcAttempts = Math.max(1, r.attempts());
        }
        if (calcMf < 0) {
            calcMf = LuckDetector.magicFind();
        }
        int by = detailY + detailH - 44;
        int half = (detailW - 16 - 4) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(String.format(Locale.US, "Attempts: %,d",
                calcAttempts)), btn -> {
            calcAttempts = Math.min(100_000_000L, calcAttempts + STEPS[stepIndex]);
            rebuildWidgets();
        }).secondaryPress(btn -> {
            calcAttempts = Math.max(1, calcAttempts - STEPS[stepIndex]);
            rebuildWidgets();
        }).bounds(detailX + 8, by, half, 16).build());
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(String.format(Locale.US, "Step: %,d",
                STEPS[stepIndex])), (btn, dir) -> {
            stepIndex = SettingsButtonWidget.step(stepIndex, dir, STEPS.length);
            rebuildWidgets();
        }).bounds(detailX + 8 + half + 4, by, half, 16).build());
        if (r.drop().mf()) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Magic Find: " + calcMf + "%"), btn -> {
                calcMf = Math.min(LuckConfig.MAX_MAGIC_FIND, calcMf + 10);
                rebuildWidgets();
            }).secondaryPress(btn -> {
                calcMf = Math.max(0, calcMf - 10);
                rebuildWidgets();
            }).bounds(detailX + 8, by + 20, half, 16).build());
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Use My Stats"), btn -> {
            calcAttempts = Math.max(1, r.attempts());
            calcMf = LuckDetector.magicFind();
            rebuildWidgets();
        }).bounds(detailX + 8 + half + 4, by + 20, half, 16).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.header(g, panelX, panelY, panelW, "How Lucky Are You");
        if (scope == Scope.OTHERS) {
            drawOthers(g, mouseX, mouseY);
        } else {
            drawMine(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawCards(GuiGraphicsExtractor g, List<Card> cards) {
        int gap = 6;
        int cw = (panelW - 12 - gap * (cards.size() - 1)) / cards.size();
        int cx = panelX + 6;
        for (Card c : cards) {
            ProfitPanels.themedCard(g, this.font, cx, cardsY, cw, 46, c.label, c.value, c.color, c.sub, c.color);
            cx += cw + gap;
        }
    }

    private record Card(String label, String value, int color, String sub) {
    }

    private void drawMine(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<LuckStore.Row> rows = rows();
        LuckStore.Summary s = LuckStore.summarize(rows);
        long attempts = 0;
        for (long a : LuckStore.attempts(profileFilter()).values()) {
            attempts += a;
        }
        int luckPct = (int) Math.round(s.meanPercentile() * 100);
        drawCards(g, List.of(
                new Card("Overall Luck", s.drops() == 0 ? "-" : luckPct + "%",
                        s.drops() == 0 ? ProfitPanels.DIM : luckPct >= 50 ? ProfitPanels.GOOD : ProfitPanels.BAD,
                        s.drops() == 0 ? "nothing tracked yet" : LuckMath.describe(s.meanPercentile())),
                new Card("Drops", s.got() + " / " + LuckMath.expected(s.expected()), ProfitPanels.TEXT,
                        "got / expected over " + s.drops() + " drops"),
                new Card("Above / Below Odds", s.above() + " / " + s.below(), ProfitPanels.ACCENT,
                        "drops ahead of / behind the odds"),
                new Card("Attempts", String.format(Locale.US, "%,d", attempts), ProfitPanels.TEXT,
                        "chests claimed + slayer kills")));

        ScreenChrome.well(g, listX, listY, listW, listH);
        MenuStyle.text(g, "DROP", listX + 8, listY + 6, MenuStyle.DIM);
        String head = "GOT / EXPECTED";
        MenuStyle.text(g, head, listX + listW - 8 - this.font.width(head), listY + 6, MenuStyle.DIM);
        int visible = (listH - 22) / ROW_H;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        int y = listY + 20;
        if (rows.isEmpty()) {
            MenuStyle.text(g, "Nothing yet: claim a dungeon chest or kill a slayer boss.", listX + 8, y, MenuStyle.DIM2);
        }
        for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
            LuckStore.Row r = rows.get(i);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y - 1 && mouseY < y + ROW_H - 1;
            if (hover || r.key().equals(selected)) {
                ScreenChrome.row(g, listX + 2, y - 2, listW - 4, ROW_H, hover, r.key().equals(selected), false);
            }
            String right = r.got() + " / " + LuckMath.expected(r.expected());
            int rw = this.font.width(right);
            String left = r.name() + "  " + activityShort(r.activity());
            MenuStyle.text(g, this.font.plainSubstrByWidth(left, listW - 24 - rw), listX + 8, y, MenuStyle.TXT);
            int color = r.expected() <= 0 ? ProfitPanels.DIM
                    : r.got() >= r.expected() ? ScreenChrome.good() : ScreenChrome.bad();
            MenuStyle.text(g, right, listX + listW - 8 - rw, y, color);
            y += ROW_H;
        }
        if (rows.size() > visible) {
            ScreenChrome.scrollbar(g, listX + listW - 5, listY + 20, listH - 24, scroll * ROW_H,
                    rows.size() * ROW_H, visible * ROW_H);
        }

        ScreenChrome.well(g, detailX, detailY, detailW, detailH);
        LuckStore.Row sel = selectedRow();
        if (sel == null) {
            drawRecent(g);
        } else {
            drawDetail(g, sel);
        }
    }

    static String activityShort(String activity) {
        if (activity.startsWith("chest:")) {
            String[] p = activity.split(":");
            return p.length == 3 ? p[1] + " " + p[2] : activity;
        }
        if (activity.startsWith("slayer:")) {
            return DropTable.get().activity(activity).label();
        }
        return "untracked".equals(activity) || "other".equals(activity) ? "" : activity;
    }

    private void drawRecent(GuiGraphicsExtractor g) {
        MenuStyle.text(g, "LATEST DROPS", detailX + 8, detailY + 6, MenuStyle.DIM);
        int y = detailY + 20;
        List<LuckStore.LogEntry> recent = LuckStore.recent(20);
        if (recent.isEmpty()) {
            for (String line : List.of("Drop lines, slayer kills and dye pickups", "are counted from now on. Dungeon chests",
                    "come from your Croesus claim log, so", "every chest you ever claimed counts.",
                    "", "Click a drop for its odds and a calculator.")) {
                MenuStyle.text(g, line, detailX + 8, y, MenuStyle.DIM2);
                y += 11;
            }
            return;
        }
        for (LuckStore.LogEntry e : recent) {
            if (y > detailY + detailH - 12) {
                break;
            }
            int bar = e.drop().lastIndexOf('|');
            String name = bar >= 0 ? e.drop().substring(bar + 1) : e.drop();
            String line = (e.amount() > 1 ? e.amount() + "x " : "") + name + "  " + ago(e.atMs());
            MenuStyle.text(g, this.font.plainSubstrByWidth(line, detailW - 16), detailX + 8, y, MenuStyle.TXT);
            y += 11;
        }
    }

    private static String ago(long atMs) {
        long s = Math.max(0, (System.currentTimeMillis() - atMs) / 1000);
        if (s < 120) {
            return s + "s ago";
        }
        if (s < 7200) {
            return s / 60 + "m ago";
        }
        if (s < 172_800) {
            return s / 3600 + "h ago";
        }
        return s / 86_400 + "d ago";
    }

    private void drawDetail(GuiGraphicsExtractor g, LuckStore.Row r) {
        int x = detailX + 8;
        int y = detailY + 6;
        int w = detailW - 16;
        MenuStyle.text(g, this.font.plainSubstrByWidth(r.name(), w), x, y, MenuStyle.OR);
        y += 12;
        DropTable.Drop d = r.drop();
        String where = DropTable.get().activity(r.activity()).label();
        MenuStyle.text(g, this.font.plainSubstrByWidth(where, w), x, y, MenuStyle.DIM);
        y += 14;
        if (d == null || !d.tracked()) {
            String text = d != null && !d.chanceText().isEmpty() ? d.chanceText() : "Not in the drop table yet.";
            for (var line : this.font.split(Component.literal(text), w)) {
                MenuStyle.text(g, line, x, y, MenuStyle.DIM2);
                y += 10;
            }
            MenuStyle.text(g, "Got: " + r.got() + "  (attempts not counted yet)", x, y + 4, MenuStyle.TXT);
            return;
        }
        double chance = chanceShown(d);
        List<String> lines = new ArrayList<>();
        lines.add("Chance: " + LuckMath.oneIn(chance) + String.format(Locale.US, " (%.4f%%)", chance * 100)
                + (d.mf() ? " + Magic Find" : ""));
        lines.add(String.format(Locale.US, "Attempts: %,d", r.attempts()));
        lines.add("Expected: " + LuckMath.expected(r.expected()) + "   Got: " + r.got() + "   " + LuckMath.ratio(r.got(), r.expected()));
        lines.add(LuckMath.describe(r.percentile()) + String.format(Locale.US, " (%.0f%%)", r.percentile() * 100));
        long half = LuckMath.attemptsFor(chance, 0.5);
        long ninety = LuckMath.attemptsFor(chance, 0.9);
        if (half > 0) {
            lines.add(String.format(Locale.US, "50%% have one by %,d; 90%% by %,d", half, ninety));
        }
        for (String line : lines) {
            MenuStyle.text(g, this.font.plainSubstrByWidth(line, w), x, y, MenuStyle.TXT);
            y += 11;
        }
        // Calculator
        y += 4;
        MenuStyle.text(g, "CALCULATOR", x, y, MenuStyle.DIM);
        y += 11;
        if (calcAttempts > 0) {
            double c = d.mf() ? LuckMath.withMagicFind(d, calcMf) : chance;
            String calc = String.format(Locale.US, "%,d tries: %s expected, %.1f%% for at least one", calcAttempts,
                    LuckMath.expected(c * calcAttempts), LuckMath.atLeastOne(c, calcAttempts) * 100);
            for (var line : this.font.split(Component.literal(calc), w)) {
                MenuStyle.text(g, line, x, y, MenuStyle.TXT);
                y += 10;
            }
        }
        MenuStyle.text(g, "Left click adds a step, right click takes one away.", x, detailY + detailH - 56,
                MenuStyle.DIM2);
    }

    private void drawOthers(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<LuckShare.Remote> others = LuckShare.others();
        String why = LuckShare.blockReason();
        drawCards(g, List.of(
                new Card("Mod Users Sharing", String.valueOf(others.size()), ProfitPanels.TEXT, "in your relay room"),
                new Card("Your Luck", why == null ? "Shared" : "Not Shared", why == null ? ProfitPanels.GOOD
                        : ProfitPanels.DIM, why == null ? "Share My Luck is on" : why),
                new Card("Seeing Others", com.killer560.hub.modusers.ModUserConfig.getInstance().isSeeOthers() ? "On" : "Off",
                        ProfitPanels.ACCENT, "See Other Mod Users (Mod User Tag)")));
        ScreenChrome.well(g, listX, listY, listW, listH);
        MenuStyle.text(g, "PLAYER", listX + 8, listY + 6, MenuStyle.DIM);
        int y = listY + 20;
        if (others.isEmpty()) {
            MenuStyle.text(g, "Nobody in your relay room has shared yet.", listX + 8, y, MenuStyle.DIM2);
        }
        for (int i = 0; i < others.size() && y < listY + listH - 12; i++) {
            LuckShare.Remote r = others.get(i);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y - 1 && mouseY < y + ROW_H - 1;
            if (hover || i == selectedOther) {
                ScreenChrome.row(g, listX + 2, y - 2, listW - 4, ROW_H, hover, i == selectedOther, false);
            }
            int pct = (int) Math.round(r.luck() * 100);
            String right = pct + "%  " + r.got() + " / " + LuckMath.expected(r.expected());
            MenuStyle.text(g, r.from(), listX + 8, y, MenuStyle.TXT);
            MenuStyle.text(g, right, listX + listW - 8 - this.font.width(right), y,
                    pct >= 50 ? ScreenChrome.good() : ScreenChrome.bad());
            y += ROW_H;
        }
        ScreenChrome.well(g, detailX, detailY, detailW, detailH);
        if (selectedOther < 0 || selectedOther >= others.size()) {
            MenuStyle.text(g, "Click a player to see their drops.", detailX + 8, detailY + 6, MenuStyle.DIM2);
            return;
        }
        LuckShare.Remote r = others.get(selectedOther);
        MenuStyle.text(g, r.from() + ": " + LuckMath.describe(r.luck()), detailX + 8, detailY + 6, MenuStyle.OR);
        int dy = detailY + 20;
        for (LuckShare.RemoteRow row : r.rows()) {
            if (dy > detailY + detailH - 12) {
                break;
            }
            String right = row.got() + " / " + LuckMath.expected(row.expected());
            MenuStyle.text(g, this.font.plainSubstrByWidth(row.name(), detailW - 24 - this.font.width(right)),
                    detailX + 8, dy, MenuStyle.TXT);
            MenuStyle.text(g, right, detailX + detailW - 8 - this.font.width(right), dy,
                    row.got() >= row.expected() ? ScreenChrome.good() : ScreenChrome.bad());
            dy += 11;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (McInput.button(event) == 0 && event.x() >= listX && event.x() < listX + listW && event.y() >= listY + 18
                && event.y() < listY + listH) {
            int index = (int) ((event.y() - (listY + 19)) / ROW_H);
            if (scope == Scope.OTHERS) {
                selectedOther = index;
                return true;
            }
            List<LuckStore.Row> rows = rows();
            int i = scroll + index;
            if (i >= 0 && i < rows.size()) {
                String key = rows.get(i).key();
                selected = key.equals(selected) ? null : key;
                calcAttempts = -1;
                calcMf = -1;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, scroll - (int) Math.round(scrollY * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public String themeId() {
        return "profit";
    }

}
