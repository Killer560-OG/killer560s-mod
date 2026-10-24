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
    /** The profile whose Rename was pressed and is being edited in place on its card, and the text typed so far. */
    private static String renaming;
    private static String renameText = "";
    /** How long a "Confirm" stays armed: past it (or on coming back to the page later) Delete asks again. */
    private static final long ARM_MS = 10_000L;

    private static final int CARD_MIN_W = 190;
    private static final int GAP = 8;
    private static final int HEADER_H = 58;
    private static final int NEW_H = 52;
    private static final int CARD_H = 58;
    private static final int BTN_H = 16;

    public ProfilesTab() {
        super("Profiles");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        MenuStyle.use(); // the name box takes the theme's text color and shadow when built
        List<AbstractWidget> widgets = new ArrayList<>();
        Font font = MenuStyle.font();
        int x = contentX;
        int w = contentWidth;
        int y = contentY;

        String active = ProfileManager.getActiveProfile();
        List<String> profiles = ProfileManager.listProfiles();
        if (renaming != null && !profiles.contains(renaming)) {
            renaming = null;
        }
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
        widgets.add(new PaintedPanel(x, y, w, HEADER_H,
                Component.literal("Active profile: " + (active != null ? active : "none")),
                (g, px, py, pw, ph, hov) -> paintHeader(g, px, py, pw, ph, active, importW)));
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
            int cx = x + (i % cols) * (cw + GAP);
            int cy = y + (i / cols) * (CARD_H + GAP);
            widgets.add(new PaintedPanel(cx, cy, cw, CARD_H, Component.literal(profile),
                    (g, px, py, pw, ph, hov) -> paintCard(g, px, py, pw, ph, hov, profile, isActive, profile.equals(renaming))));

            int bw = (cw - 16 - 2 * 5) / 3;
            int rowY = cy + CARD_H - BTN_H - 8;
            int b0 = cx + 8;
            if (profile.equals(renaming)) {
                // Rename in place (killer560, 2026-10-15: "make a way to edit the name of them"): the name becomes a box,
                // Enter or Save renames, Cancel (or Escape elsewhere) leaves it as it was.
                EditBox box = new EditBox(font, cx + 8 + 4, cy + 6, cw - 16 - 8, 12, Component.literal("New name"));
                box.setBordered(false);
                box.setMaxLength(32);
                box.setTextColor(MenuStyle.TXT);
                box.setTextShadow(MenuStyle.TEXT_SHADOW);
                box.setValue(renameText);
                box.setResponder(text -> renameText = text);
                widgets.add(box);
                int half = (cw - 16 - 5) / 2;
                widgets.add(SettingsButtonWidget.builder(Component.literal("Save"), btn -> {
                            ProfileManager.Result result = ProfileManager.renameProfile(profile, renameText);
                            if (!result.success()) {
                                ModOverlayMessage.show(result.message(), 4000);
                                return;
                            }
                            renaming = null;
                            requestRebuild.run();
                        }).bounds(b0, rowY, half, BTN_H).primary().build());
                widgets.add(SettingsButtonWidget.builder(Component.literal("Cancel"), btn -> {
                            renaming = null;
                            requestRebuild.run();
                        }).bounds(b0 + half + 5, rowY, half, BTN_H).build());
                continue;
            }
            if (!isActive) {
                widgets.add(SettingsButtonWidget.builder(Component.literal("Load"), btn -> {
                            armedDelete = null;
                            // The success line ("Applied N setting file(s) ...") is only logged now (killer560, 2026-10-15:
                            // "Dont show this text on screen whenver I profile swap"); a failure still shows.
                            ProfileManager.Result result = ProfileManager.applyProfile(profile);
                            if (!result.success()) {
                                ModOverlayMessage.show(result.message(), 5000);
                            }
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
            // After Delete so the card's widgets stay panel, Load, Export, Delete in that order (testkit 618 reads them so).
            widgets.add(SettingsButtonWidget.builder(Component.literal("Rename"), btn -> {
                        armedDelete = null;
                        renaming = profile;
                        renameText = profile;
                        requestRebuild.run();
                    }).bounds(b0, cy + 20, 54, 12).build());
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

    private static void paintHeader(GuiGraphicsExtractor g, int x, int y, int w, int h, String active, int importW) {
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
    }

    private static void paintHeading(GuiGraphicsExtractor g, int x, int y, int w, int h, int count) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.sectionBand(r, x, y, w, h, false);
        r.submit();
        MenuStyle.text(g, "SAVED PROFILES", x + 6, y + 4, MenuStyle.OR2);
        // The "3 profiles" count that sat on the right of this band was removed (killer560, 2026-10-18).
    }

    private static void paintEmpty(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        r.submit();
        MenuStyle.text(g, "No saved profiles yet.", x + 10, y + 9, MenuStyle.TXT);
        MenuStyle.flat(g, MenuStyle.ellipsize("Name one above and press Save Current As New, or drop a .zip in Import Profile's folder.",
                w - 20), x + 10, y + 22, MenuStyle.DIM);
    }

    private static void paintCard(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, String name,
                                  boolean isActive, boolean isRenaming) {
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
        if (isRenaming) {
            GuiRects well = GuiRects.begin(g);
            MenuStyle.well(well, x + 8, y + 4, w - 16, 16, MenuStyle.WELL_DEEP);
            well.submit();
            return;
        } else {
            MenuStyle.text(g, Component.literal(MenuStyle.ellipsize(name, nameMax - 4)).withStyle(ChatFormatting.BOLD),
                    x + 8, y + 8, hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
        }
        if (isActive) {
            int colour = MenuStyle.luminance(MenuStyle.OR) > 0.55f ? 0xFF160C02 : 0xFFFFFFFF;
            g.text(font, badge, x + w - 8 - badgeW + 5, y + 8, colour, false);
        }
        if (isActive) {
            // where Load sits on the other cards
            int bw = (w - 16 - 2 * 5) / 3;
            String in = "In use";
            MenuStyle.flat(g, in, x + 8 + (bw - font.width(in)) / 2, y + h - BTN_H - 8 + 4, MenuStyle.OR2);
        }
    }
}
