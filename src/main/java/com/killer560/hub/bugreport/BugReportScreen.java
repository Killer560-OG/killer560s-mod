package com.killer560.hub.bugreport;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * /bugreport's screen (bugreportmenu-1122, killer560 2026-11-22: "a menu that you can type the title into for what it is
 * for, then a description as well ... They should have an option to save the bug report or save and make the post"): a
 * Title box, a multi-line Description box, the list of what the zip holds, and Save / Save and Post / Close. Save writes
 * the zip as /bugreport always did; Save and Post also sends it through the relay to the Discord's bug report forum
 * ({@link BugReportPost}). The status line shows each step and the result (the post's link or why it was not posted);
 * chat says the same. Drawn in the menu theme through {@link ScreenChrome}.
 */
public final class BugReportScreen extends Screen implements MenuThemedScreen {

    private static final int PAD = 10;
    private static final int LINE_H = 10;
    static final int TITLE_MAX = 100;
    static final int DESCRIPTION_MAX = 3000;
    private static final int TITLE_MIN_FOR_POST = 4;

    private final Screen parent;
    private String titleText = "";
    private String descText = "";
    private String status = "";
    private int statusColour;
    private boolean busy;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int titleY;
    private int descY;
    private int descH;
    private int listY;
    private int statusY;
    private ThemedEditBox titleBox;
    private MultiLineEditBox descBox;
    private SettingsButtonWidget save;
    private SettingsButtonWidget post;

    public BugReportScreen(Screen parent) {
        super(Component.literal("Bug Report"));
        this.parent = parent;
    }

    /** Starts with {@code title} in the Title box (/bugreport &lt;text&gt;). */
    public BugReportScreen title(String title) {
        this.titleText = title == null ? "" : title.strip();
        return this;
    }

    @Override
    protected void init() {
        panelW = Math.min(470, this.width - 8);
        panelH = Math.min(340, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        int x = panelX + PAD;
        int w = panelW - 2 * PAD;
        titleY = panelY + ScreenChrome.HEADER_H + 8;
        titleBox = new ThemedEditBox(this.font, x, titleY + 11, w, 20, Component.literal("Title"))
                .hint("What is wrong, in a few words");
        titleBox.setMaxLength(TITLE_MAX);
        titleBox.setValue(titleText);
        titleBox.setResponder(v -> titleText = v);
        titleBox.setTooltip(Tooltip.create(Component.literal("A short name for the problem. It becomes the post's title in the "
                + "Discord. Enter moves to the description.")));
        addRenderableWidget(titleBox);

        int buttonY = panelY + panelH - PAD - 20;
        statusY = buttonY - 13;
        int listH = LINE_H * (BugReportFeature.CONTENTS.size() + 1);
        listY = statusY - 6 - listH;
        descY = titleY + 11 + 20 + 8;
        descH = Math.max(30, listY - 8 - (descY + 11));
        descBox = MultiLineEditBox.builder().setX(x + 2).setY(descY + 13)
                .setPlaceholder(Component.literal("What happened, what you expected, and the steps to make it happen again. "
                        + "Add as much as you can."))
                .setTextColor(MenuStyle.TXT).setCursorColor(MenuStyle.OR).setTextShadow(MenuStyle.TEXT_SHADOW)
                .setShowBackground(false)
                .build(this.font, w - 4, descH - 4, Component.literal("Description"));
        descBox.setCharacterLimit(DESCRIPTION_MAX);
        descBox.setValue(descText);
        descBox.setValueListener(v -> descText = v);
        addRenderableWidget(descBox);
        titleBox.onEnter(() -> setFocused(descBox));

        int bw = Math.min(130, (w - 2 * 8) / 3);
        int cx = panelX + panelW / 2;
        save = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save"), b -> start(false))
                .bounds(cx - bw / 2 - 8 - bw, buttonY, bw, 20).build());
        save.setTooltip(Tooltip.create(Component.literal("Saves the zip in your bug-reports folder and opens it, so you can "
                + "drag it into Discord yourself.")));
        post = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save and Post"), b -> start(true)).primary()
                .bounds(cx - bw / 2, buttonY, bw, 20).build());
        post.setTooltip(Tooltip.create(Component.literal("Saves the zip, then posts it with your title and description in the "
                + "Discord's bug report forum under your Minecraft name.")));
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx + bw / 2 + 8, buttonY, bw, 20).build());
        save.active = post.active = !busy;
        McInput.textInputWhileOpen(this);
        setInitialFocus(titleText.isEmpty() ? titleBox : descBox);
    }

    private void start(boolean andPost) {
        if (busy) {
            return;
        }
        if (andPost && titleText.strip().length() < TITLE_MIN_FOR_POST) {
            setStatus("Type a title (at least " + TITLE_MIN_FOR_POST + " characters) to post it.", ScreenChrome.bad());
            setFocused(titleBox);
            return;
        }
        busy = true;
        save.active = post.active = false;
        BugReportFeature.save(titleText, descText, andPost, (text, done, ok) -> {
            setStatus(text, done ? (ok ? ScreenChrome.good() : ScreenChrome.bad()) : MenuStyle.DIM);
            if (done) {
                busy = false;
                save.active = post.active = true;
            }
        });
    }

    private void setStatus(String text, int colour) {
        status = text;
        statusColour = colour;
    }

    /** What the status line says now, for the testkit. */
    public String status() {
        return status;
    }

    public boolean busy() {
        return busy;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.header(g, panelX, panelY, panelW, "Bug Report");
        int x = panelX + PAD;
        int w = panelW - 2 * PAD;
        ScreenChrome.text(g, "Title", x, titleY, MenuStyle.TXT_SOFT);
        ScreenChrome.text(g, "Description", x, descY, MenuStyle.TXT_SOFT);
        String count = descText.length() + " / " + DESCRIPTION_MAX;
        ScreenChrome.text(g, count, x + w - MenuStyle.font().width(count), descY, MenuStyle.DIM);
        ScreenChrome.well(g, x, descY + 11, w, descH);
        ScreenChrome.text(g, "The zip will hold:", x, listY, MenuStyle.TXT_SOFT);
        for (int i = 0; i < BugReportFeature.CONTENTS.size(); i++) {
            ScreenChrome.text(g, MenuStyle.ellipsize(BugReportFeature.CONTENTS.get(i), w - 8), x + 8, listY + LINE_H * (i + 1),
                    MenuStyle.DIM);
        }
        if (!status.isEmpty()) {
            ScreenChrome.text(g, MenuStyle.ellipsize(status, w), x, statusY, statusColour);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
