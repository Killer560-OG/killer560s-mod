package com.killer560.hub.sounddisabler;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Custom Sounds (sounddisabler-menu-1118, killer560 2026-11-18: "a custom menu where you can search through every sound in
 * the game and change its volume individually as well as mute it outright"): every registered sound event plus every id that
 * played this session and has no event (Hypixel's own), a live search, and per row a 0..200% slider, a Mute button and a
 * Preview button. Only the rows drawn are laid out (about 1,700 ids; the list is built once when the screen opens).
 * Changed sounds go in {@link SoundDisablerConfig}; the rest never are.
 */
public final class CustomSoundsScreen extends Screen implements MenuThemedScreen {

    /** One listed sound: its id, the shown text and the search text ({@code .}, {@code _} and {@code :} read as spaces). */
    private record Row(Identifier id, String shown, String key) {
    }

    private static final int PANEL_MAX_W = 380;
    private static final int PANEL_MAX_H = 300;
    private static final int ROW_H = 20;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int SCROLLBAR_W = 8;
    private static final int LIST_TOP = 70;
    private static final int FOOTER_H = 16;
    private static final int SLIDER_W = 96;
    private static final int MUTE_W = 40;
    private static final int PLAY_W = 18;
    private static final long CONFIRM_MS = 3000;

    private final Screen parent;
    private final List<Row> all = new ArrayList<>();
    private List<Row> shown = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;
    private int scroll;
    private int dragRow = -1;
    private boolean changedOnly;
    private long resetArmedAt;
    private ThemedEditBox search;
    private SettingsButtonWidget changedButton;
    private SettingsButtonWidget resetButton;

    public CustomSoundsScreen(Screen parent) {
        super(Component.literal("Custom Sounds"));
        this.parent = parent;
        TreeSet<Identifier> ids = new TreeSet<>(BuiltInRegistries.SOUND_EVENT.keySet());
        ids.addAll(SoundDisabler.unregisteredSeen());
        ids.addAll(SoundDisablerConfig.getInstance().changedIds());
        for (Identifier id : ids) {
            String text = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
            all.add(new Row(id, text, normalize(text)));
        }
        shown = all;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[._:/]+", " ").strip();
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listY = panelY + LIST_TOP;
        rows = Math.max(1, (panelH - LIST_TOP - FOOTER_H) / ROW_H);

        int inner = panelW - PAD * 2;
        String old = search == null ? "" : search.getValue();
        search = new ThemedEditBox(this.font, panelX + PAD, panelY + 24, inner, 18, Component.literal("Search sounds"))
                .hint("search sounds, e.g. note block or villager");
        search.setValue(old);
        search.setResponder(text -> refilter());
        addRenderableWidget(search);
        int half = (inner - GAP) / 2;
        changedButton = SettingsButtonWidget.builder(Component.literal(changedLabel()), b -> {
            changedOnly = !changedOnly;
            b.setMessage(Component.literal(changedLabel()));
            refilter();
        }).bounds(panelX + PAD, panelY + 46, half, 18).build();
        addRenderableWidget(changedButton);
        resetButton = SettingsButtonWidget.builder(Component.literal("Reset all"), b -> resetAll())
                .bounds(panelX + PAD + half + GAP, panelY + 46, inner - half - GAP, 18).build();
        addRenderableWidget(resetButton);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 52, panelY + 3, 44, 14).build());
        setFocused(search);
        search.setFocused(true);
        refilter();
    }

    private String changedLabel() {
        return "Changed only: " + (changedOnly ? "ON" : "OFF");
    }

    private void resetAll() {
        long now = System.currentTimeMillis();
        if (now - resetArmedAt > CONFIRM_MS) {
            resetArmedAt = now;
            resetButton.setMessage(Component.literal("Click again to confirm"));
            return;
        }
        resetArmedAt = 0;
        resetButton.setMessage(Component.literal("Reset all"));
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        cfg.clearSounds();
        cfg.save();
        refilter();
    }

    /** Rebuilds {@link #shown} from the search text and Changed only; the list is short enough to scan on a keystroke. */
    private void refilter() {
        String q = normalize(search == null ? "" : search.getValue());
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        List<Row> out = new ArrayList<>();
        for (Row r : all) {
            if ((q.isEmpty() || r.key().contains(q)) && (!changedOnly || cfg.sound(r.id()) != null)) {
                out.add(r);
            }
        }
        shown = out;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows)));
    }

    /** The rows currently listed, for the testkit. */
    public List<Identifier> listedIds() {
        List<Identifier> out = new ArrayList<>(shown.size());
        for (Row r : shown) {
            out.add(r.id());
        }
        return out;
    }

    private int rowW() {
        return panelW - PAD * 2 - SCROLLBAR_W - GAP;
    }

    private int sliderX() {
        return panelX + PAD + rowW() - PLAY_W - 2 - MUTE_W - 2 - SLIDER_W;
    }

    private int muteX() {
        return panelX + PAD + rowW() - PLAY_W - 2 - MUTE_W;
    }

    private int playX() {
        return panelX + PAD + rowW() - PLAY_W;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (resetArmedAt != 0 && System.currentTimeMillis() - resetArmedAt > CONFIRM_MS) {
            resetArmedAt = 0;
            resetButton.setMessage(Component.literal("Reset all"));
        }
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX + 3, panelY + 2, panelW - 6, 16, "Custom Sounds");
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        int rowW = rowW();
        int rx = panelX + PAD;
        String hovered = null;
        for (int i = 0; i < rows && scroll + i < shown.size(); i++) {
            Row r = shown.get(scroll + i);
            int ry = listY + i * ROW_H;
            boolean over = mouseX >= rx && mouseX < rx + rowW && mouseY >= ry && mouseY < ry + ROW_H;
            if (over) {
                hovered = r.id().toString();
            }
            ScreenChrome.row(g, rx, ry, rowW, ROW_H, over, false, i % 2 == 1);
            SoundDisablerConfig.Setting s = cfg.sound(r.id());
            boolean muted = s != null && s.muted();
            int pct = s == null ? 100 : s.percent();
            MenuStyle.text(g, ScreenChrome.readable(this.font.plainSubstrByWidth(r.shown(), sliderX() - rx - 6)), rx + 4,
                    ry + 6, muted ? MenuStyle.RED : MenuStyle.TXT);
            int sx = sliderX();
            ScreenChrome.progress(g, sx, ry + 7, SLIDER_W, 6, pct / (double) SoundDisablerConfig.MAX_PERCENT,
                    muted ? MenuStyle.DIM2 : MenuStyle.OR);
            g.fill(sx + SLIDER_W / 2, ry + 4, sx + SLIDER_W / 2 + 1, ry + 16, MenuStyle.DIM);
            String label = pct + "%";
            MenuStyle.text(g, label, sx + SLIDER_W - this.font.width(label) - 2, ry + 1, MenuStyle.TXT_SOFT);
            ScreenChrome.button(g, muteX(), ry + 2, MUTE_W, ROW_H - 4, muted ? "Muted" : "Mute",
                    mouseX >= muteX() && mouseX < muteX() + MUTE_W && over, muted);
            ScreenChrome.button(g, playX(), ry + 2, PLAY_W, ROW_H - 4, ">",
                    mouseX >= playX() && mouseX < playX() + PLAY_W && over, false);
        }
        if (shown.size() > rows) {
            ScreenChrome.scrollbar(g, panelX + panelW - PAD - SCROLLBAR_W, listY, rows * ROW_H, scroll, shown.size() - rows,
                    rows);
        }
        if (shown.isEmpty()) {
            MenuStyle.text(g, "No sounds match.", panelX + PAD, listY + 4, MenuStyle.DIM);
        }
        String foot = hovered != null ? hovered : shown.size() + " of " + all.size() + " sounds, " + cfg.soundCount()
                + " changed" + (cfg.isEnabled() ? "" : " (Sound Disabler is OFF)");
        MenuStyle.text(g, ScreenChrome.readable(this.font.plainSubstrByWidth(foot, panelW - PAD * 2)), panelX + PAD,
                panelY + panelH - 12, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** The visible row index under y, or -1. */
    private int rowAt(double y) {
        if (y < listY || y >= listY + rows * ROW_H) {
            return -1;
        }
        int idx = scroll + (int) ((y - listY) / ROW_H);
        return idx < shown.size() ? idx : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        int idx = rowAt(event.y());
        if (idx >= 0 && x >= panelX + PAD && x < panelX + PAD + rowW()) {
            SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
            Row r = shown.get(idx);
            SoundDisablerConfig.Setting s = cfg.sound(r.id());
            boolean muted = s != null && s.muted();
            int pct = s == null ? 100 : s.percent();
            if (x >= sliderX() && x < sliderX() + SLIDER_W) {
                dragRow = idx;
                setPercent(idx, x);
                return true;
            }
            if (x >= muteX() && x < muteX() + MUTE_W) {
                cfg.setSound(r.id(), !muted, pct);
                cfg.save();
                if (changedOnly) {
                    refilter();
                }
                return true;
            }
            if (x >= playX() && x < playX() + PLAY_W) {
                SoundDisabler.preview(r.id(), pct);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void setPercent(int idx, double x) {
        SoundDisablerConfig cfg = SoundDisablerConfig.getInstance();
        Row r = shown.get(idx);
        double t = Math.max(0, Math.min(1, (x - sliderX()) / SLIDER_W));
        int step = SoundDisablerConfig.STEP_PERCENT;
        int pct = (int) Math.round(t * SoundDisablerConfig.MAX_PERCENT / step) * step;
        SoundDisablerConfig.Setting s = cfg.sound(r.id());
        cfg.setSound(r.id(), s != null && s.muted(), pct);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragRow >= 0 && dragRow < shown.size()) {
            setPercent(dragRow, event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragRow >= 0) {
            dragRow = -1;
            SoundDisablerConfig.getInstance().save();
            if (changedOnly) {
                refilter();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * 3, Math.max(0, shown.size() - rows)));
        return true;
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
}
