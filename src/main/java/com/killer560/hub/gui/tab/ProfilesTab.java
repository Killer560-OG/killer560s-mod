package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.PaintedPanel;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.profiles.ProfileManager;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom settings profiles - killer560's "name them whatever I want, clicking between them changes
 *  which settings [are active], easy to share" request. See {@link ProfileManager}'s class doc for why
 *  switching needs a restart to fully apply, and for exactly which real files are deliberately excluded
 *  from every profile (session-login token, per-account proxies, RNG/storage caches). Import opens a drop folder
 *  whose zips are imported automatically; while a profile is active, setting changes are saved into it
 *  ({@link ProfileManager#syncActiveProfile}).
 *  <p>The page (killer560, 2026-10-13: "I dont like how this profile screen is set up"): an Active Profile card with
 *  Import as its action, a New Profile card holding the name box and both create buttons, then a card per saved profile
 *  (an Active badge on the loaded one, when it last changed, Load / Export / Delete with Delete asking for a second
 *  click). The cards are {@link PaintedPanel}s under the same real buttons as before, so every action runs the same
 *  {@link ProfileManager} call it always did. */
public class ProfilesTab extends BaseTab {

    private static String newProfileName = "";
    /** The profile whose Delete was pressed once and now reads "Confirm" (cleared by any other action on the page). */
    private static String armedDelete;
    private static long armedAt;
    /** How long a "Confirm" stays armed: past it (or on coming back to the page later) Delete asks again. */
    private static final long ARM_MS = 10_000L;

    private static final int CARD_MIN_W = 190;
    private static final int GAP = 8;
    private static final int HEADER_H = 58;
    private static final int NEW_H = 62;
    private static final int CARD_H = 58;
    private static final int BTN_H = 16;

    public ProfilesTab() {
        super("Profiles");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        MenuStyle.use(); // the name box takes the theme's text colour and shadow when built
        List<AbstractWidget> widgets = new ArrayList<>();
        Font font = MenuStyle.font();
        int x = contentX;
        int w = contentWidth;
        int y = contentY;

        String active = ProfileManager.getActiveProfile();
        List<String> profiles = ProfileManager.listProfiles();
        if (armedDelete != null && (!profiles.contains(armedDelete)
                || System.currentTimeMillis() - armedAt > ARM_MS)) {
            armedDelete = null;
        }

        // ---- Active profile ----------------------------------------------------------------------------------------
        // killer560 (2026-10-04): "add a button to import a new file that opens the file location then you can remove
        // the text about receiving one." The background poll in ProfileManager imports any .zip dropped there within
        // two seconds and refreshes this list.
        String importLabel = "Import Profile (Open Folder)";
        int importW = font.width(importLabel) + 16;
        ProfileManager.ProfileInfo activeInfo = active == null ? null : ProfileManager.info(active);
        widgets.add(new PaintedPanel(x, y, w, HEADER_H,
                Component.literal("Active profile: " + (active != null ? active : "none")),
                (g, px, py, pw, ph, hov) -> paintHeader(g, px, py, pw, ph, active, activeInfo, importW)));
        widgets.add(SettingsButtonWidget.builder(Component.literal(importLabel), btn -> {
                    armedDelete = null;
                    try {
                        ExternalOpen.path(ProfileManager.importFolder());
                    } catch (Exception e) {
                        ModOverlayMessage.show("§c[Profiles] Couldn't open the import folder: " + e.getMessage(), 4000);
                    }
                }).bounds(x + w - 10 - importW, y + 24, importW, 18).build());
        y += HEADER_H + 10;

        // ---- New profile -------------------------------------------------------------------------------------------
        String saveLabel = "Save Current As New";
        String blankLabel = "New Blank Profile";
        int saveW = font.width(saveLabel) + 18;
        int blankW = font.width(blankLabel) + 18;
        int buttonsW = saveW + 6 + blankW;
        boolean oneLine = w - 20 - buttonsW - 8 >= 120;
        int boxW = oneLine ? w - 20 - buttonsW - 8 : w - 20;
        int newH = oneLine ? NEW_H : NEW_H + 22;
        int boxY = y + 22;
        widgets.add(new PaintedPanel(x, y, w, newH, Component.literal("New profile"),
                (g, px, py, pw, ph, hov) -> paintNewCard(g, px, py, pw, ph, boxW)));
        EditBox nameField = new EditBox(font, x + 10 + 5, boxY + 5, boxW - 10, 10, Component.literal("Profile name"));
        nameField.setBordered(false);
        nameField.setMaxLength(32);
        nameField.setTextColor(MenuStyle.TXT);
        nameField.setTextShadow(MenuStyle.TEXT_SHADOW);
        nameField.setValue(newProfileName);
        // The hint is painted by the card (paintNewCard): EditBox draws its hint with a text shadow, which smears dark
        // text on the Light theme.
        nameField.setResponder(text -> newProfileName = text);
        widgets.add(nameField);
        int bx = oneLine ? x + 10 + boxW + 8 : x + 10;
        int by = oneLine ? boxY : boxY + 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal(saveLabel), btn -> {
                    armedDelete = null;
                    if (newProfileName.isBlank()) {
                        ModOverlayMessage.show("§cType a profile name first.", 2500);
                        return;
                    }
                    ProfileManager.Result result = ProfileManager.saveCurrentAsProfile(newProfileName);
                    ModOverlayMessage.show(result.message(), 4000);
                    if (result.success()) {
                        newProfileName = "";
                        requestRebuild.run();
                    }
                }).bounds(bx, by, saveW, 20).primary().build());
        // killer560 (2026-10-08): "make an option to make a profile with 0 settings on". Named from the box when
        // something is typed there, "Blank" otherwise; made beside the others, not switched to.
        widgets.add(SettingsButtonWidget.builder(Component.literal(blankLabel), btn -> {
                    armedDelete = null;
                    ProfileManager.Result result = ProfileManager.createBlankProfile(newProfileName);
                    ModOverlayMessage.show(result.message(), 5000);
                    if (result.success()) {
                        newProfileName = "";
                        requestRebuild.run();
                    }
                }).bounds(bx + saveW + 6, by, blankW, 20).build());
        y += newH + 12;

        // ---- Saved profiles ----------------------------------------------------------------------------------------
        int count = profiles.size();
        widgets.add(new PaintedPanel(x, y, w, 14, Component.literal("Saved profiles"),
                (g, px, py, pw, ph, hov) -> paintHeading(g, px, py, pw, ph, count)));
        y += 20;

        if (profiles.isEmpty()) {
            widgets.add(new PaintedPanel(x, y, w, 40, Component.literal("No saved profiles yet."),
                    (g, px, py, pw, ph, hov) -> paintEmpty(g, px, py, pw, ph)));
            y += 40;
            return widgets;
        }

        int cols = Math.max(1, Math.min(3, (w + GAP) / (CARD_MIN_W + GAP)));
        int cw = (w - (cols - 1) * GAP) / cols;
        for (int i = 0; i < profiles.size(); i++) {
            String profile = profiles.get(i);
            boolean isActive = profile.equals(active);
            ProfileManager.ProfileInfo info = ProfileManager.info(profile);
            int cx = x + (i % cols) * (cw + GAP);
            int cy = y + (i / cols) * (CARD_H + GAP);
            widgets.add(new PaintedPanel(cx, cy, cw, CARD_H, Component.literal(profile),
                    (g, px, py, pw, ph, hov) -> paintCard(g, px, py, pw, ph, hov, profile, isActive, info)));

            int bw = (cw - 16 - 2 * 5) / 3;
            int rowY = cy + CARD_H - BTN_H - 8;
            int b0 = cx + 8;
            if (!isActive) {
                widgets.add(SettingsButtonWidget.builder(Component.literal("Load"), btn -> {
                            armedDelete = null;
                            ProfileManager.Result result = ProfileManager.applyProfile(profile);
                            ModOverlayMessage.show(result.message(), 5000);
                            requestRebuild.run();
                        }).bounds(b0, rowY, bw, BTN_H).build());
            }
            widgets.add(SettingsButtonWidget.builder(Component.literal("Export"), btn -> {
                        armedDelete = null;
                        // Zipped into the share folder, which then opens (2026-10-08).
                        ProfileManager.Result result = ProfileManager.shareProfile(profile);
                        ModOverlayMessage.show(result.message(), 6000);
                    }).bounds(b0 + bw + 5, rowY, bw, BTN_H).build());
            boolean armed = profile.equals(armedDelete);
            widgets.add(SettingsButtonWidget.builder(Component.literal(armed ? "Confirm" : "Delete"), btn -> {
                        if (!profile.equals(armedDelete) || System.currentTimeMillis() - armedAt > ARM_MS) {
                            armedDelete = profile;
                            armedAt = System.currentTimeMillis();
                            requestRebuild.run();
                            return;
                        }
                        armedDelete = null;
                        ProfileManager.Result result = ProfileManager.deleteProfile(profile);
                        ModOverlayMessage.show(result.message(), 3000);
                        requestRebuild.run();
                    }).bounds(b0 + 2 * (bw + 5), rowY, bw, BTN_H).danger(armed).build());
        }
        return widgets;
    }

    // ---- painting -----------------------------------------------------------------------------------------------------

    /** A Home-style box: drop shadow, bevel, the title band with its heading. */
    private static void box(GuiRects r, int x, int y, int w, int h) {
        MenuStyle.dropShadow(r, x, y, w, h, 3);
        MenuStyle.bevel(r, x, y, w, h, MenuStyle.BOX_TOP, MenuStyle.BOX_BOTTOM);
        r.gradient(x + 1, y + 1, x + w - 1, y + 15, MenuStyle.TITLE_TOP, MenuStyle.TITLE_BOTTOM);
        r.fill(x + 1, y + 15, x + w - 1, y + 16, MenuStyle.BLACK);
    }

    private static void paintHeader(GuiGraphicsExtractor g, int x, int y, int w, int h, String active,
                                    ProfileManager.ProfileInfo info, int importW) {
        GuiRects r = GuiRects.begin(g);
        box(r, x, y, w, h);
        // the accent bar beside the name
        r.fill(x + 1, y + 16, x + 4, y + h - 1, active != null ? MenuStyle.OR : MenuStyle.DIM2);
        r.submit();
        MenuStyle.text(g, "ACTIVE PROFILE", x + 8, y + 4, MenuStyle.OR2);
        Font font = MenuStyle.font();
        int textMax = w - importW - 34;
        float scale = 1.5f;
        if (active != null) {
            String name = MenuStyle.ellipsize(active, (int) (textMax / scale));
            MenuStyle.gradientText(g, name, x + 12, y + 21, scale, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
            String sub = "Setting changes save into it" + changedText(info);
            MenuStyle.text(g, MenuStyle.ellipsize(sub, textMax), x + 12, y + 41, MenuStyle.DIM);
        } else {
            g.pose().pushMatrix();
            g.pose().translate(x + 12, y + 21);
            g.pose().scale(scale, scale);
            g.text(font, "Default settings", 0, 0, MenuStyle.TXT, MenuStyle.TEXT_SHADOW);
            g.pose().popMatrix();
            MenuStyle.text(g, MenuStyle.ellipsize("No profile loaded. Load one below, or save these settings as one.",
                    textMax), x + 12, y + 41, MenuStyle.DIM);
        }
    }

    private static void paintNewCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int boxW) {
        GuiRects r = GuiRects.begin(g);
        box(r, x, y, w, h);
        MenuStyle.well(r, x + 10, y + 22, boxW, 20, MenuStyle.WELL_DEEP);
        r.submit();
        if (newProfileName.isEmpty()) {
            MenuStyle.flat(g, "New profile name...", x + 15, y + 27, MenuStyle.DIM2);
        }
        MenuStyle.text(g, "NEW PROFILE", x + 8, y + 4, MenuStyle.OR2);
        MenuStyle.flat(g, MenuStyle.ellipsize("Save copies your current settings; Blank starts with every setting off. "
                + "Neither switches to it.", w - 20), x + 10, y + h - 14, MenuStyle.DIM);
    }

    private static void paintHeading(GuiGraphicsExtractor g, int x, int y, int w, int h, int count) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.sectionBand(r, x, y, w, h, false);
        r.submit();
        MenuStyle.text(g, "SAVED PROFILES", x + 6, y + 4, MenuStyle.OR2);
        String n = count == 1 ? "1 profile" : count + " profiles";
        MenuStyle.flat(g, n, x + w - 6 - MenuStyle.font().width(n), y + 4, MenuStyle.DIM2);
    }

    private static void paintEmpty(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        r.submit();
        MenuStyle.text(g, "No saved profiles yet.", x + 10, y + 9, MenuStyle.TXT);
        MenuStyle.flat(g, MenuStyle.ellipsize("Name one above and press Save Current As New, or drop a .zip into Import.",
                w - 20), x + 10, y + 22, MenuStyle.DIM);
    }

    private static void paintCard(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, String name,
                                  boolean isActive, ProfileManager.ProfileInfo info) {
        Font font = MenuStyle.font();
        String badge = "ACTIVE";
        int badgeW = font.width(badge) + 10;
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 3);
        if (isActive) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x50);
        }
        MenuStyle.bevel(r, x, y, w, h, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
        if (isActive) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.OR);
            MenuStyle.pill(r, x + w - 8 - badgeW, y + 6, badgeW, 12, true, false);
        } else if (hover) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.ACCENT_EDGE);
        }
        r.submit();
        int nameMax = w - 16 - (isActive ? badgeW + 6 : 0);
        MenuStyle.text(g, Component.literal(MenuStyle.ellipsize(name, nameMax - 4)).withStyle(ChatFormatting.BOLD),
                x + 8, y + 8, hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
        if (isActive) {
            int colour = MenuStyle.luminance(MenuStyle.OR) > 0.55f ? 0xFF160C02 : 0xFFFFFFFF;
            g.text(font, badge, x + w - 8 - badgeW + 5, y + 8, colour, false);
        }
        String sub = (info.lastChangedMs() > 0 ? "Saved " + ago(info.lastChangedMs()) : "Never saved")
                + " · " + (info.files() == 1 ? "1 file" : info.files() + " files");
        MenuStyle.flat(g, MenuStyle.ellipsize(sub, w - 16), x + 8, y + 21, MenuStyle.DIM);
        if (isActive) {
            // where Load sits on the other cards
            int bw = (w - 16 - 2 * 5) / 3;
            String in = "In use";
            MenuStyle.flat(g, in, x + 8 + (bw - font.width(in)) / 2, y + h - BTN_H - 8 + 4, MenuStyle.OR2);
        }
    }

    private static String changedText(ProfileManager.ProfileInfo info) {
        return info == null || info.lastChangedMs() <= 0 ? "" : " · last saved " + ago(info.lastChangedMs());
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or the date. */
    static String ago(long ms) {
        long s = Math.max(0L, (System.currentTimeMillis() - ms) / 1000L);
        if (s < 60) {
            return "just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86400) {
            return (s / 3600) + " h ago";
        }
        long d = s / 86400;
        if (d == 1) {
            return "yesterday";
        }
        if (d < 30) {
            return d + " days ago";
        }
        return new java.text.SimpleDateFormat("d MMM yyyy").format(new java.util.Date(ms));
    }
}
