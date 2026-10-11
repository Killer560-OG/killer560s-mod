package com.killer560.hub.autoupdate;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** The one-time "Updated to <hash>" popup after Auto Update installed a new jar, with the release notes. */
public class UpdatedScreen extends Screen {

    private static final int ACCENT = 0xFFFF8C1A;
    private static final int PANEL_BG = 0xF0141010;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;

    private final Screen parent;
    private final String hash;
    private final String tag;
    private final String notes;
    private List<FormattedCharSequence> lines = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public UpdatedScreen(Screen parent, String hash, String tag, String notes) {
        super(Component.literal("Updated to " + hash));
        this.parent = parent;
        this.hash = hash == null ? "?" : hash;
        this.tag = tag == null ? "" : tag;
        this.notes = notes == null ? "" : notes;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, 420);
        panelH = Math.min(this.height - 20, 260);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int maxLines = Math.max(1, (panelH - 30 - 34) / 10);
        List<FormattedCharSequence> all = new ArrayList<>();
        for (String raw : plain(notes).split("\\R")) {
            if (raw.isBlank()) {
                all.add(FormattedCharSequence.EMPTY);
                continue;
            }
            all.addAll(this.font.split(Component.literal(raw), panelW - 16));
        }
        while (!all.isEmpty() && all.get(all.size() - 1) == FormattedCharSequence.EMPTY) {
            all.remove(all.size() - 1);
        }
        if (all.size() > maxLines) {
            all = new ArrayList<>(all.subList(0, maxLines - 1));
            all.add(Component.literal("... the full notes are on the mod's GitHub releases page.").getVisualOrderText());
        }
        lines = all;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("OK"), btn -> onClose())
                .bounds(panelX + panelW - 66, panelY + panelH - 24, 60, 18).build());
    }

    /** Release notes are Markdown; drop the markup that reads as noise in plain text. */
    static String plain(String md) {
        return md.replace("**", "").replace("`", "").replace("\r", "").replaceAll("(?m)^#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "- ");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, ACCENT);
        graphics.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ACCENT);
        graphics.text(this.font, "Killer560's Mod updated to " + hash, panelX + 10, panelY + 11, ACCENT, false);
        if (!tag.isEmpty()) {
            graphics.text(this.font, tag, panelX + panelW - 10 - this.font.width(tag), panelY + 11, DIM, false);
        }
        int y = panelY + 38;
        if (lines.isEmpty()) {
            graphics.text(this.font, "No release notes.", panelX + 8, y, DIM, false);
        }
        for (FormattedCharSequence line : lines) {
            graphics.text(this.font, line, panelX + 8, y, TEXT, false);
            y += 10;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }
}
