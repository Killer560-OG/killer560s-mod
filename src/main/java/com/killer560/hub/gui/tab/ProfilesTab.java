package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.profiles.ProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom settings profiles - killer560's "name them whatever I want, clicking between them changes
 *  which settings [are active], easy to share" request. See {@link ProfileManager}'s class doc for why
 *  switching needs a restart to fully apply, and for exactly which real files are deliberately excluded
 *  from every profile (session-login token, per-account proxies, RNG/storage caches). Export/import use
 *  {@code /killer560 profile export/import} since a real file-share flow needs a path, not just a click. */
public class ProfilesTab extends BaseTab {

    private static String newProfileName = "";

    public ProfilesTab() {
        super("Profiles");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        String active = ProfileManager.getActiveProfile();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Active profile: §e" + (active != null ? active : "(none - using default settings)")),
                Minecraft.getInstance().font));
        y += 16;
        y += 20;

        EditBox nameField = new EditBox(Minecraft.getInstance().font, contentX, y, 180, 18,
                Component.literal("Profile name"));
        nameField.setMaxLength(32);
        nameField.setValue(newProfileName);
        nameField.setHint(Component.literal("New profile name..."));
        nameField.setResponder(text -> newProfileName = text);
        widgets.add(nameField);

        widgets.add(SettingsButtonWidget.builder(Component.literal("Save Current As New"), btn -> {
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
                }).bounds(contentX + 188, y, 150, 18).build());
        y += 26;

        List<String> profiles = ProfileManager.listProfiles();
        if (profiles.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No saved profiles yet."), Minecraft.getInstance().font));
            y += 16;
        } else {
            for (String profile : profiles) {
                boolean isActive = profile.equals(active);
                widgets.add(new StringWidget(contentX, y + 3, 150, 12,
                        Component.literal((isActive ? "§a> " : "") + profile), Minecraft.getInstance().font));

                widgets.add(SettingsButtonWidget.builder(Component.literal("Load"), btn -> {
                            ProfileManager.Result result = ProfileManager.applyProfile(profile);
                            ModOverlayMessage.show(result.message(), 5000);
                            requestRebuild.run();
                        }).bounds(contentX + 150, y, 60, 18).build());

                widgets.add(SettingsButtonWidget.builder(Component.literal("Export"), btn -> {
                            ProfileManager.Result result = ProfileManager.exportProfile(profile);
                            ModOverlayMessage.show(result.message(), 6000);
                        }).bounds(contentX + 214, y, 65, 18).build());

                widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                            ProfileManager.Result result = ProfileManager.deleteProfile(profile);
                            ModOverlayMessage.show(result.message(), 3000);
                            requestRebuild.run();
                        }).bounds(contentX + 283, y, 65, 18).build());
                y += 22;
            }
        }

        y += 8;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7To share: Export, then send the .zip Minecraft tells you about."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7To receive one: drop the .zip in config/killer560smod-profiles/,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7then run \"/killer560 profile import <file.zip> <newName>\"."),
                Minecraft.getInstance().font));

        return widgets;
    }
}
