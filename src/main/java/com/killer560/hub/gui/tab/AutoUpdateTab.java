package com.killer560.hub.gui.tab;

import com.killer560.hub.autoupdate.AutoUpdateConfig;
import com.killer560.hub.autoupdate.AutoUpdateFeature;
import com.killer560.hub.autoupdate.AutoUpdater;
import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Home's Auto Update section (2026-10-11): the channel and a Check Now button. See {@link AutoUpdateFeature}. */
public class AutoUpdateTab extends BaseTab {

    public AutoUpdateTab() {
        super("Auto Update");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        // Home draws every section inline with no accordion header, so each one draws its own heading.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Auto Update", false));
        y += 16;

        // Auto Update as a whole (killer560, 2026-10-13: "make a toggle for auto update"): first, so it is the card's
        // switch; on by default. Off, the updater follows Channel.OFF (AutoUpdateConfig#effectiveChannel) and the
        // channel row is hidden.
        AutoUpdateConfig ac = AutoUpdateConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(Component.literal("Auto Update: " + (ac.isEnabled() ? "§aON" : "§cOFF")),
                btn -> {
                    ac.setEnabled(!ac.isEnabled());
                    ac.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (ac.isEnabled()) {
            widgets.add(SettingsButtonWidget.cycling(channelText(), (btn, dir) -> {
                        AutoUpdateConfig cfg = AutoUpdateConfig.getInstance();
                        cfg.setChannel(AutoUpdateConfig.stepChannel(cfg.getChannel(), dir));
                        cfg.save();
                        btn.setMessage(channelText());
                    }).bounds(contentX, y, half, 20).build());
        }
        widgets.add(SettingsButtonWidget.builder(checkText(), btn -> {
                    if (AutoUpdateFeature.isChecking()) {
                        return;
                    }
                    AutoUpdateFeature.checkAsync(o -> btn.setMessage(checkText()));
                    btn.setMessage(checkText());
                }).bounds(contentX + half + gap, y, rightW, 20).build());
        y += 24;

        // The "updated to" popup's own Theme (2026-10-13; Theme Default follows the menu).
        widgets.add(com.killer560.hub.gui.ScreenThemes.button(com.killer560.hub.autoupdate.UpdatedScreen.THEME_ID, contentX, y, half, 20));
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7This build: "
                + BuildInfo.shortCommit() + " (" + BuildInfo.minecraft() + " " + BuildInfo.variant() + ")"),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component channelText() {
        return Component.literal("Channel: §a" + AutoUpdateConfig.getInstance().getChannel().label);
    }

    /** "Full Release - up to date": the channel and the last check's result, for the menu's Home page. */
    public static String statusLine() {
        AutoUpdateConfig.Channel c = AutoUpdateConfig.getInstance().effectiveChannel();
        String check = checkText().getString();
        int colon = check.indexOf(':');
        String status = colon > 0 ? check.substring(colon + 1).trim() : "not checked yet";
        return c.label + "  §8·  " + status;
    }

    /** Starts a check (as the Check Now button does); {@code done} runs on the result. */
    public static void checkNow(Runnable done) {
        if (!AutoUpdateFeature.isChecking()) {
            AutoUpdateFeature.checkAsync(o -> done.run());
        }
    }

    private static Component checkText() {
        if (AutoUpdateFeature.isChecking()) {
            return Component.literal("Check Now: §7checking...");
        }
        AutoUpdater.Outcome o = AutoUpdateFeature.lastOutcome();
        if (o == null) {
            return Component.literal("Check Now");
        }
        return Component.literal("Check Now: " + switch (o.status()) {
            case UP_TO_DATE -> "§aup to date";
            case STAGED, ALREADY_STAGED -> "§aready, installs on close";
            case BLOCKED -> "§7" + shortReason(o.detail());
            case NO_RELEASE, NO_ASSET -> "§7no update";
            case BAD_SOURCE, NO_CHECKSUM, CHECKSUM_MISMATCH -> "§crejected";
            case ERROR -> "§cfailed";
        });
    }

    private static String shortReason(String detail) {
        if (detail == null) {
            return "skipped";
        }
        if (detail.startsWith("instance managed")) {
            return "deploy-managed";
        }
        if (detail.startsWith("the mod is not a jar")) {
            return "not in mods/";
        }
        if (detail.startsWith("Auto Update is Off")) {
            return "off";
        }
        return "skipped";
    }
}
