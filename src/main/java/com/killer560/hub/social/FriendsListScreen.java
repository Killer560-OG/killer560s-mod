package com.killer560.hub.social;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Our own Friends List menu (killer560's 8.7, reworked 2026-09-27) - opened by {@code /fl} when
 * {@link FriendsListConfig#isEnabled()} is on, or always via {@code /flcustom}. Same black + amber chrome as
 * {@link BestFriendsScreen} / {@code CroesusTrackerScreen}.
 * <p>
 * Every row here is a real Hypixel friend, mirrored by {@link FriendsListSync} - this screen never invents a
 * member of its own. "Add" sends the real {@code /f add <name>}; the "Remove" button on a selected friend
 * sends the real {@code /f remove <name>}. Neither one edits the list directly - both wait for the next real
 * {@code /fl} sync to confirm, so what's on screen can never silently diverge from Hypixel's own answer (see
 * {@link FriendsListSync}'s doc). A manual "Refresh" button re-syncs on demand, rate-limited the same way.
 * <p>
 * <b>Online status.</b> When {@link FriendsListSync} could tell Online/Offline apart in the last real
 * {@code /fl} (see that class's doc on why that isn't guaranteed for every possible real wording), that's
 * shown directly - the first source of REAL Hypixel presence this mod has ever had, instead of the old
 * tab-list-only "Nearby now" guess. When it couldn't, this falls back to that same "Nearby now"/"Unknown"
 * guess (same server instance as you right now) rather than claiming to know something it doesn't.
 */
public class FriendsListScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int ROW_H = 20;
    private static final int HEAD_SIZE = 16;

    private final Screen parent;
    private EditBox addBox;
    private EditBox noteBox;
    private List<FriendsListConfig.Friend> visible = List.of();
    private FriendsListConfig.Friend selected;
    private String statusMessage = "";
    private int statusColor = ModChat.DIM;

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int scroll = 0;

    public FriendsListScreen(Screen parent) {
        super(Component.literal("Friends List"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(340, Math.min((int) (this.width * 0.7), 520)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.82), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listX = panelX + 6;
        listY = panelY + 60;
        listW = panelW - 12;
        listH = panelH - 60 - (selected != null ? 44 : 8);

        // One rate-limited sync per open, same as the old refreshOnlineNames() call this replaces - see
        // FriendsListSync's doc on why this can never spam Hypixel (opening the menu is an explicit click).
        FriendsListSync.requestSync(true);

        int addBtnW = 70;
        int refreshBtnW = 62;
        int addBoxW = panelW - 12 - addBtnW - 4 - refreshBtnW - 4;
        addBox = new EditBox(this.font, panelX + 6, panelY + 34, addBoxW, 18, Component.literal("Add by name"));
        addBox.setMaxLength(16);
        addBox.setHint(Component.literal("Add by name (sends /f add)..."));
        addRenderableWidget(addBox);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add"), btn -> addByName())
                .bounds(panelX + 6 + addBoxW + 4, panelY + 34, addBtnW, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> {
                    if (!FriendsListSync.requestSync(false)) {
                        statusMessage = "Already just synced - give it a moment.";
                        statusColor = ModChat.DIM;
                    }
                }).bounds(panelX + panelW - 6 - refreshBtnW, panelY + 34, refreshBtnW, 18).build());

        refresh();

        if (selected != null) {
            int barY = panelY + panelH - 40;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Remove"), btn -> {
                FriendsListSync.requestRemove(selected.name);
                selected = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(panelX + 6, barY, 70, 18).build());
            int noteW = panelW - 12 - 70 - 70 - 8;
            noteBox = new EditBox(this.font, panelX + 6 + 74, barY, noteW, 18, Component.literal("Note"));
            noteBox.setMaxLength(64);
            noteBox.setHint(Component.literal("Note..."));
            noteBox.setValue(selected.note == null ? "" : selected.note);
            noteBox.setResponder(text -> {
                FriendsListConfig.getInstance().setNote(selected.name, text);
                selected.note = text;
            });
            addRenderableWidget(noteBox);
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
                FriendsListConfig.getInstance().save();
                selected = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(panelX + panelW - 70, barY, 64, 18).build());
        }
    }

    /** {@link FriendsListConfig#applyRealSync} reuses the SAME {@link FriendsListConfig.Friend} object across
     *  a re-sync whenever the name matches, so {@link #selected} (taken from an earlier {@link #visible})
     *  keeps reflecting live updates (a fresh {@code onlineHint}, a re-cased name) without needing to be
     *  re-fetched here - it only ever goes stale if that friend was actually removed, in which case it just
     *  keeps showing what it last knew until the player presses "< Back" or "Remove" themselves. */
    private void refresh() {
        visible = FriendsListConfig.getInstance().friends();
    }

    /** Sends the real {@code /f add <name>} - Hypixel resolves/validates the name itself, so unlike the old
     *  local-only list this never needs its own UUID lookup before it can act. */
    private void addByName() {
        String name = addBox == null ? "" : addBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        addBox.setValue("");
        if (FriendsListSync.requestAdd(name)) {
            statusMessage = "Sent /f add " + name + " - syncing shortly...";
            statusColor = ModChat.DIM;
        } else {
            statusMessage = "Please wait a moment before sending another request.";
            statusColor = ModChat.BAD;
        }
    }

    // ---- interaction -----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (selected == null && event.button() == 0) {
            FriendsListConfig.Friend hit = rowAt(event.x(), event.y());
            if (hit != null) {
                selected = hit;
                scroll = 0;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private FriendsListConfig.Friend rowAt(double mx, double my) {
        if (mx < listX || mx > listX + listW || my < listY || my >= listY + listH) {
            return null;
        }
        int i = (int) ((my - listY + scroll) / ROW_H);
        return i >= 0 && i < visible.size() ? visible.get(i) : null;
    }

    private int maxScroll() {
        return Math.max(0, visible.size() * ROW_H - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selected == null && mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- rendering -------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // A re-sync can land between renders (chat is async to the render loop) - re-read every frame so a
        // fresh /fl shows up without needing to close and reopen the screen.
        refresh();

        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 28, 0xFF000000);
        graphics.outline(panelX, panelY, panelW, 28, BORDER);
        graphics.fill(panelX, panelY + 27, panelX + panelW, panelY + 28, ACCENT);
        graphics.text(this.font, "Friends List", panelX + 10, panelY + 10, ACCENT, false);
        String right = FriendsListConfig.getInstance().isEnabled() ? "/fl opens this menu" : "/fl passes through to Hypixel";
        graphics.text(this.font, right, panelX + panelW - 10 - this.font.width(right), panelY + 10,
                0xFF000000 | ModChat.DIM, false);

        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF080808);
        graphics.outline(listX - 1, listY - 1, listW + 2, listH + 2, BORDER);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            if (selected != null) {
                drawDetail(graphics, selected);
            } else {
                drawList(graphics, mouseX, mouseY);
            }
        } finally {
            graphics.disableScissor();
        }
        if (selected == null && maxScroll() > 0) {
            int trackX = listX + listW - 3;
            graphics.fill(trackX, listY, trackX + 3, listY + listH, 0xFF1A1A1A);
            int contentH = Math.max(1, visible.size() * ROW_H);
            int thumbH = Math.max(10, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll();
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
        }
        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, statusMessage, panelX + 6, panelY + panelH - (selected != null ? 62 : 20),
                    0xFF000000 | statusColor, false);
        } else if (selected == null) {
            graphics.text(this.font, syncStatusLine(), panelX + 6, panelY + panelH - 20, 0xFF000000 | ModChat.DIM, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Honest sync status per this wave's brief: never claim the list is complete/current when it might not
     *  be. See {@link FriendsListSync}'s class doc for exactly what "never synced" and "truncated" mean. */
    private String syncStatusLine() {
        FriendsListConfig cfg = FriendsListConfig.getInstance();
        if (!cfg.isEverSynced()) {
            return "§cNever synced with your real /fl yet - press Refresh, or Hypixel's wording may differ from what this mod expects.";
        }
        if (cfg.isLastSyncTruncated()) {
            return "§6Hypixel's /fl may show more than this - the last sync looked cut short.";
        }
        return "§7" + visible.size() + " real friend(s), last synced " + secondsAgo(cfg.getLastSyncedAtMs()) + " ago.";
    }

    private static String secondsAgo(long epochMs) {
        if (epochMs <= 0) {
            return "a while";
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000L);
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (visible.isEmpty()) {
            String msg = FriendsListConfig.getInstance().isEverSynced()
                    ? "No friends on your real Hypixel list."
                    : "Not synced yet - press Refresh above.";
            graphics.text(this.font, msg, listX + 6, listY + 8, 0xFF000000 | ModChat.DIM, false);
            return;
        }
        FriendsListConfig.Friend hovered = rowAt(mouseX, mouseY);
        int first = Math.max(0, scroll / ROW_H);
        for (int i = first; i < visible.size(); i++) {
            int rowY = listY + i * ROW_H - scroll;
            if (rowY > listY + listH) {
                break;
            }
            FriendsListConfig.Friend f = visible.get(i);
            if (f == hovered) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF262626);
            } else if (i % 2 == 0) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF121212);
            }
            PlayerHeadRenderer.draw(graphics, f.uuid, f.name, listX + 3, rowY + (ROW_H - HEAD_SIZE) / 2, HEAD_SIZE);
            int textY = rowY + (ROW_H - 8) / 2;
            String status = onlineStatusText(f);
            int statusW = this.font.width(status);
            graphics.text(this.font, status, listX + listW - 6 - statusW, textY, 0xFFFFFFFF, false);
            String name = f.name == null || f.name.isBlank() ? "?" : f.name;
            int nameX = listX + 3 + HEAD_SIZE + 6;
            String label = name + (f.note != null && !f.note.isBlank() ? "  §7- " + f.note : "");
            int nameSpace = listW - (nameX - listX) - statusW - 10;
            graphics.text(this.font, this.font.plainSubstrByWidth(label, Math.max(10, nameSpace)), nameX, textY, 0xFFFFFFFF, false);
        }
    }

    /** Real Hypixel Online/Offline when the last sync could tell (see the class doc), else the old
     *  same-tab-list-instance guess. */
    private static String onlineStatusText(FriendsListConfig.Friend f) {
        if (f.onlineHint != null) {
            return f.onlineHint ? "§aOnline" : "§8Offline";
        }
        return PlayerLookup.isOnlineNow(f.uuid) ? "§aNearby now" : "§8Unknown";
    }

    /** Same status, spelled out for the detail page rather than a short list-row tag. */
    private static String detailStatusText(FriendsListConfig.Friend f) {
        if (f.onlineHint != null) {
            return f.onlineHint ? "§aOnline right now (from your real /fl)" : "§8Offline (from your real /fl)";
        }
        return PlayerLookup.isOnlineNow(f.uuid)
                ? "§aNearby now (on your tab list) - the last /fl sync didn't say Online/Offline"
                : "§8Unknown - not on your tab list, and the last /fl sync didn't say Online/Offline";
    }

    private void drawDetail(GuiGraphicsExtractor graphics, FriendsListConfig.Friend f) {
        PlayerHeadRenderer.draw(graphics, f.uuid, f.name, listX + 6, listY + 6, 32);
        int textX = listX + 6 + 32 + 10;
        String name = f.name == null || f.name.isBlank() ? "?" : f.name;
        graphics.text(this.font, "§l" + name, textX, listY + 6, 0xFFFFFFFF, false);
        graphics.text(this.font, detailStatusText(f), textX, listY + 18, 0xFFFFFFFF, false);
        graphics.text(this.font, "§7Known since " + formatDate(f.firstSeenAtMs), listX + 6, listY + 46, 0xFFFFFFFF, false);
        graphics.text(this.font, "§7Edit the note below (local only, never sent anywhere):", listX + 6, listY + 60, 0xFFFFFFFF, false);
    }

    private static String formatDate(long epochMs) {
        if (epochMs <= 0) {
            return "-";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMs));
    }

    @Override
    public void onClose() {
        FriendsListConfig.getInstance().save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
