package com.killer560.hub.autoupdate;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** The one-time "Updated to <hash>" popup after Auto Update installed a new jar, with the release notes. */
public class UpdatedScreen extends Screen implements MenuThemedScreen {

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
        int maxLines = Math.max(1, (panelH - 44 - 34) / 10);
        List<FormattedCharSequence> all = new ArrayList<>();
        for (String raw : plain(notes).split("\\R")) {
            if (raw.isBlank()) {
                all.add(FormattedCharSequence.EMPTY);
                continue;
            }
            all.addAll(this.font.split(Component.literal(raw), panelW - 28));
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

    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "autoupdate";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Killer560's Mod updated to " + hash);
        if (!tag.isEmpty()) {
            ScreenChrome.headerRight(graphics, panelX, panelY, panelW, tag, MenuStyle.DIM);
        }
        // The notes sit in a recessed well between the header and the OK button.
        int wellY = panelY + 30;
        int wellH = panelH - 30 - 30;
        ScreenChrome.well(graphics, panelX + 6, wellY, panelW - 12, wellH);
        int y = wellY + 6;
        if (lines.isEmpty()) {
            ScreenChrome.text(graphics, "No release notes.", panelX + 14, y, MenuStyle.DIM);
        }
        for (FormattedCharSequence line : lines) {
            ScreenChrome.text(graphics, line, panelX + 14, y, MenuStyle.TXT);
            y += 10;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }
}
