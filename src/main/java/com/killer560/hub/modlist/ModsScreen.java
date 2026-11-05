package com.killer560.hub.modlist;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Mods screen (killer560, 2026-11-04: "a button for mods that opens a browser to see all of their mods. essentially mod
 * menu built into our mod"), opened from the Themed Main Menu and the pause menu. Left: a search box (a live filter), the
 * Libraries switch and every loaded mod by name, children folded under their parent (the arrow and count at a row's
 * right open them). Right: the selected mod's icon, name, version, id, authors and description, Configure (its config
 * screen through {@link ModList#configScreen}) and its Website / Source / Issues links.
 * <p>
 * Nothing is laid out per frame: rows and their text are built when the filter changes, the description when the
 * selection does. Icons are read from each jar on Minecraft's IO pool only when their row is drawn, uploaded on the
 * render thread, and released when the screen closes (a late read after that is dropped).
 */
public final class ModsScreen extends Screen implements MenuThemedScreen {

    private static final Logger LOGGER = ModLog.get("killer560smod-modlist");
    private static final int PANEL_MAX_W = 620;
    private static final int PANEL_MAX_H = 360;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int ROW_H = 22;
    private static final int ICON = 16;
    private static final int BIG_ICON = 32;
    private static final int INDENT = 12;
    private static final int SCROLLBAR_W = 6;
    private static final int TOP = 24;
    private static final int FOLD_W = 30;
    private static final int MAX_ICON_BYTES = 2 << 20;

    private record Row(ModList.Entry entry, int depth, FormattedCharSequence name, FormattedCharSequence version,
                       FormattedCharSequence fold) {
    }

    private record Icon(Identifier id, int w, int h) {
    }

    /** Stands for "this mod has no usable icon" in {@link #icons}. */
    private static final Icon NONE = new Icon(null, 0, 0);

    private final Screen parent;
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Icon> icons = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private List<FormattedCharSequence> descLines = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listRows;
    private int detailX;
    private int detailW;
    private int scroll;
    private int descScroll;
    private int visibleDescLines;
    private int generation;
    private boolean open;
    private String query = "";
    private ModList.Entry selected;
    private CommitEditBox search;
    private SettingsButtonWidget libraries;
    private SettingsButtonWidget configure;
    private SettingsButtonWidget website;
    private SettingsButtonWidget source;
    private SettingsButtonWidget issues;
    private FormattedCharSequence countLine = FormattedCharSequence.EMPTY;
    private int countW;
    /** The selected mod's name, version, id and authors lines, fitted once per selection. */
    private FormattedCharSequence[] head = new FormattedCharSequence[0];

    public ModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        open = true;
        generation++;
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listX = panelX + PAD;
        listW = Math.max(140, panelW * 2 / 5);
        listY = panelY + TOP + 22;
        listRows = Math.max(1, (panelY + panelH - PAD - listY) / ROW_H);
        detailX = listX + listW + GAP + SCROLLBAR_W + GAP;
        detailW = panelX + panelW - PAD - detailX;

        int libW = 92;
        search = new ThemedEditBox(this.font, listX, panelY + TOP, listW - libW - GAP, 18, Component.literal("Search"))
                .hint("Search mods");
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(s -> {
            query = s.trim().toLowerCase(Locale.ROOT);
            scroll = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        libraries = addRenderableWidget(SettingsButtonWidget.builder(librariesLabel(), b -> {
            ModListConfig cfg = ModListConfig.getInstance();
            cfg.setShowLibraries(!cfg.isShowLibraries());
            cfg.save();
            b.setMessage(librariesLabel());
            rebuildRows();
        }).bounds(listX + listW - libW, panelY + TOP, libW, 18).build());

        int by = panelY + panelH - PAD - 18;
        int third = (detailW - 2 * GAP) / 3;
        configure = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Configure"), b -> configureSelected())
                .bounds(detailX, by - 22, detailW, 18).build());
        website = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Website"), b -> link(selected.homepage))
                .bounds(detailX, by, third, 18).build());
        source = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Source"), b -> link(selected.sources))
                .bounds(detailX + third + GAP, by, third, 18).build());
        issues = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Issues"), b -> link(selected.issues))
                .bounds(detailX + 2 * (third + GAP), by, detailW - 2 * (third + GAP), 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 52, panelY + 3, 44, 14).build());

        int n = ModList.count();
        String count = n + (n == 1 ? " mod" : " mods");
        countLine = seq(count);
        countW = this.font.width(count);
        rebuildRows();
        if (selected == null) {
            for (ModList.Entry e : ModList.top()) {
                if (ModList.OWN_ID.equals(e.id)) {
                    selected = e;
                }
            }
        }
        select(selected);
        setFocused(search);
        search.setFocused(true);
    }

    private static Component librariesLabel() {
        return Component.literal("Libraries: " + (ModListConfig.getInstance().isShowLibraries() ? "§aON" : "§cOFF"));
    }

    // ---------------------------------------------------------------- the list

    /** The rows for the current filter: a parent with a matching child is shown with that child opened under it. */
    private void rebuildRows() {
        rows.clear();
        boolean libs = ModListConfig.getInstance().isShowLibraries();
        for (ModList.Entry e : ModList.top()) {
            addRows(e, 0, libs);
        }
        scroll = Math.max(0, Math.min(scroll, rows.size() - listRows));
    }

    /** Adds {@code e} (and its open children) when it or a child passes the filter; returns whether anything was added. */
    private boolean addRows(ModList.Entry e, int depth, boolean libs) {
        if (e.library && !libs && query.isEmpty()) {
            return false;
        }
        int at = rows.size();
        rows.add(null); // placeholder, filled in below once the children are known
        boolean childShown = false;
        boolean searching = !query.isEmpty();
        if (searching || expanded.contains(e.id)) {
            for (ModList.Entry c : e.children) {
                childShown |= addRows(c, depth + 1, libs);
            }
        }
        if (!e.matches(query) && !childShown) {
            rows.remove(at);
            return false;
        }
        int textW = listW - 8 - depth * INDENT - ICON - 4 - (e.children.isEmpty() ? 0 : FOLD_W);
        String name = this.font.plainSubstrByWidth(e.name, textW);
        String version = this.font.plainSubstrByWidth(e.version, textW);
        FormattedCharSequence fold = e.children.isEmpty() ? null
                : seq((searching || expanded.contains(e.id) ? "▾ " : "▸ ") + e.children.size());
        rows.set(at, new Row(e, depth, seq(name), seq(version), fold));
        return true;
    }

    private static FormattedCharSequence seq(String s) {
        return Component.literal(s).getVisualOrderText();
    }

    private void select(ModList.Entry e) {
        selected = e;
        descScroll = 0;
        boolean has = e != null;
        configure.visible = has;
        configure.active = has && ModList.configurable(e);
        website.visible = has && e.homepage != null;
        source.visible = has && e.sources != null;
        issues.visible = has && e.issues != null;
        if (!has) {
            descLines = List.of();
            head = new FormattedCharSequence[0];
            return;
        }
        int tw = detailW - BIG_ICON - 16;
        head = new FormattedCharSequence[]{seq(this.font.plainSubstrByWidth(e.name, tw)),
                seq(this.font.plainSubstrByWidth(e.version, tw)), seq(this.font.plainSubstrByWidth(e.id, tw)),
                e.authors.isEmpty() ? null : seq(this.font.plainSubstrByWidth("By " + e.authors, detailW - 12))};
        StringBuilder text = new StringBuilder(e.description.isEmpty() ? "No description." : e.description);
        if (!e.children.isEmpty()) {
            text.append("\n\nIncludes ").append(e.children.size()).append(e.children.size() == 1 ? " mod." : " mods.");
        }
        descLines = this.font.split(Component.literal(text.toString()), detailW - 8);
    }

    private void configureSelected() {
        if (selected == null) {
            return;
        }
        Screen s = ModList.configScreen(selected, this);
        if (s != null) {
            McCompat.setScreen(Minecraft.getInstance(), s);
        }
    }

    private static void link(String url) {
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            ExternalOpen.uri(url);
        }
    }

    // ---------------------------------------------------------------- icons

    /** The icon of {@code e} once loaded; asks for it on the first call. */
    private Icon icon(ModList.Entry e) {
        Icon icon = icons.get(e.id);
        if (icon != null) {
            return icon;
        }
        icons.put(e.id, NONE); // until the read lands
        String path = e.iconPath();
        if (path == null) {
            return NONE;
        }
        int gen = generation;
        Util.ioPool().execute(() -> {
            NativeImage image = null;
            try {
                Path p = e.container.findPath(path).orElse(null);
                if (p != null && Files.size(p) <= MAX_ICON_BYTES) {
                    image = NativeImage.read(new ByteArrayInputStream(Files.readAllBytes(p)));
                }
            } catch (Exception ex) {
                LOGGER.warn("[ModList] icon of {} unreadable: {}", e.id, ex.toString());
            }
            NativeImage img = image;
            if (img != null) {
                Minecraft.getInstance().execute(() -> upload(e.id, img, gen));
            }
        });
        return NONE;
    }

    private void upload(String modId, NativeImage image, int gen) {
        if (!open || gen != generation) {
            image.close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("killer560smod",
                "modlist/" + modId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
        int w = image.getWidth();
        int h = image.getHeight();
        DynamicTexture texture = new DynamicTexture(() -> "killer560smod mod icon " + modId, image);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        icons.put(modId, new Icon(id, w, h));
    }

    private void releaseIcons() {
        for (Icon i : icons.values()) {
            if (i.id() != null) {
                Minecraft.getInstance().getTextureManager().release(i.id());
            }
        }
        icons.clear();
    }

    private void drawIcon(GuiGraphicsExtractor g, ModList.Entry e, int x, int y, int size) {
        Icon i = icon(e);
        if (i.id() != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, i.id(), x, y, 0f, 0f, size, size, i.w(), i.h(), i.w(), i.h());
            return;
        }
        g.fill(x, y, x + size, y + size, MenuStyle.OR_DARK);
        String letter = e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase(Locale.ROOT);
        ScreenChrome.centered(g, letter, x + size / 2, y + (size - 8) / 2, MenuStyle.TXT);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX + 3, panelY + 2, panelW - 6, 16, "Mods");
        ScreenChrome.text(g, countLine, panelX + panelW - 60 - countW, panelY + 6, MenuStyle.DIM);

        int rowW = listW;
        for (int i = 0; i < listRows && scroll + i < rows.size(); i++) {
            Row r = rows.get(scroll + i);
            int ry = listY + i * ROW_H;
            boolean hover = mouseX >= listX && mouseX < listX + rowW && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(g, listX, ry, rowW, ROW_H, hover, r.entry() == selected, i % 2 == 1);
            int x = listX + 4 + r.depth() * INDENT;
            drawIcon(g, r.entry(), x, ry + 3, ICON);
            ScreenChrome.text(g, r.name(), x + ICON + 4, ry + 2, MenuStyle.TXT);
            ScreenChrome.text(g, r.version(), x + ICON + 4, ry + 12, MenuStyle.DIM);
            if (r.fold() != null) {
                ScreenChrome.text(g, r.fold(), listX + rowW - FOLD_W + 2, ry + 7, MenuStyle.OR);
            }
        }
        if (rows.isEmpty()) {
            MenuStyle.text(g, "No mod matches.", listX + 4, listY + 4, MenuStyle.DIM);
        }
        if (rows.size() > listRows) {
            ScreenChrome.scrollbar(g, listX + listW + GAP, listY, listRows * ROW_H, scroll, rows.size() - listRows, listRows);
        }

        ScreenChrome.well(g, detailX, listY - 22, detailW, panelY + panelH - PAD - 22 - GAP - (listY - 22));
        if (selected != null && head.length == 4) {
            int x = detailX + 6;
            int y = listY - 16;
            drawIcon(g, selected, x, y, BIG_ICON);
            int tx = x + BIG_ICON + 6;
            ScreenChrome.text(g, head[0], tx, y + 1, MenuStyle.OR);
            ScreenChrome.text(g, head[1], tx, y + 12, MenuStyle.TXT);
            ScreenChrome.text(g, head[2], tx, y + 23, MenuStyle.DIM);
            int ly = y + BIG_ICON + 8;
            if (head[3] != null) {
                ScreenChrome.text(g, head[3], x, ly, MenuStyle.TXT_SOFT);
                ly += 12;
            }
            int bottom = configure.getY() - GAP;
            visibleDescLines = Math.max(0, (bottom - ly) / 10);
            for (int i = 0; i < visibleDescLines && descScroll + i < descLines.size(); i++) {
                ScreenChrome.text(g, descLines.get(descScroll + i), x, ly + i * 10, MenuStyle.TXT);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (x >= listX && x < listX + listW && y >= listY && y < listY + listRows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < rows.size()) {
                Row r = rows.get(idx);
                if (r.fold() != null && query.isEmpty() && (x >= listX + listW - FOLD_W || r.entry() == selected)) {
                    if (!expanded.remove(r.entry().id)) {
                        expanded.add(r.entry().id);
                    }
                    rebuildRows();
                }
                select(r.entry());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (mouseX >= detailX) {
            descScroll = Math.max(0, Math.min(descScroll - step, descLines.size() - visibleDescLines));
        } else {
            scroll = Math.max(0, Math.min(scroll - step, rows.size() - listRows));
        }
        return true;
    }

    @Override
    public void removed() {
        open = false;
        releaseIcons();
        super.removed();
    }

    @Override
    public void onClose() {
        McCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }
}
