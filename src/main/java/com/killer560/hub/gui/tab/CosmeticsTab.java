package com.killer560.hub.gui.tab;

import com.killer560.hub.copychat.CopyChatFeature;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameColor;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.supporters.SupportersAutoShare;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.supporters.SupportersSelfService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's cosmetics tab: "Create a custom cosmetic tab that houses all of your personal custom cosmetics and
 * whatnot and there you can toggle global cosmetics." Laid out per his 2026-10-04 list: the global toggle, then
 * three dropdowns - <b>Custom Names</b> (the Name Changer, delegated to {@link NameChangerTab} unchanged in how it
 * works), <b>Player Size</b>, and <b>Held Item</b> (delegated to {@link HeldItemTab}). Randomize Others lives in its
 * own {@link NickhiderTab}.
 * <p>
 * <b>Player Size.</b> "My Size" scales your own model locally and, while Share if Supporter is on and the account
 * is a linked supporter, is the scale shared through the relay - one toggle covers name and size ("there shouldn't
 * be separate toggles"). "Others' Size" is a local multiplier on every other real player's model, on top of any
 * supporter scale they share. Both run 0.05-2.0 with their own reset. See
 * {@code com.killer560.hub.supporters.PlayerNameDisplay#modelScaleFor} for how the two combine without your own
 * shared size ever being applied twice. Width/Height/Thickness stay as before: purely local, your own F5 model only.
 * <p>
 * This tab instance is built once and reused for the whole game session, so {@link #status} and the dropdown
 * open flags are plain instance state. The supporter status is fetched from the relay automatically (no Refresh
 * button - removed at his request) on first build and again once it is {@link #REFRESH_COOLDOWN_MS} old.
 */
public class CosmeticsTab extends BaseTab {

    private enum MeStatus {CHECKING, NOT_LINKED, RELAY_UNAVAILABLE, LINKED}

    private static final long REFRESH_COOLDOWN_MS = 30_000L;

    private static final List<String> SEARCHABLE_LABELS = List.of(
            "cosmetics", "toggle global cosmetics", "global cosmetics", "supporter names", "supporter sizes", "share if supporter", "custom names", "player size",
            "my size", "others' size", "width", "height", "thickness", "held item",
            "copy settings for global cosmetics", "reset cosmetics");

    private final NameChangerTab nameChangerTab = new NameChangerTab();
    private final HeldItemTab heldItemTab = new HeldItemTab();

    // Touched from the relay's own IO thread as well as the client thread.
    private volatile MeStatus status = MeStatus.CHECKING;
    private volatile boolean fetchInFlight;
    private volatile long lastFetchAtMs = -1L;
    private volatile long requestSeq;

    private String shareError;

    private boolean customNamesOpen = false;
    private boolean playerSizeOpen = false;
    private boolean heldItemOpen = false;

    public CosmeticsTab() {
        super("Cosmetics");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        Font font = Minecraft.getInstance().font;
        SupportersConfig cfg = SupportersConfig.getInstance();
        int y = contentY;

        // Two switches side by side (killer560, 2026-10-05: "two different things I flip one to hide names one for
        // sizes. Both hides everything"), on the one row the old single toggle used.
        int half = (contentWidth - 4) / 2;
        widgets.add(SettingsButtonWidget.builder(onOff("Supporter Names", cfg.isShowSupporterNames()),
                btn -> {
                    cfg.setShowSupporterNames(!cfg.isShowSupporterNames());
                    cfg.save();
                    btn.setMessage(onOff("Supporter Names", cfg.isShowSupporterNames()));
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Supporter Sizes", cfg.isShowSupporterSizes()),
                btn -> {
                    cfg.setShowSupporterSizes(!cfg.isShowSupporterSizes());
                    cfg.save();
                    btn.setMessage(onOff("Supporter Sizes", cfg.isShowSupporterSizes()));
                }).bounds(contentX + half + 4, y, contentWidth - half - 4, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("Share if Supporter", cfg.isShareIfSupporter()), btn -> {
                    cfg.setShareIfSupporter(!cfg.isShareIfSupporter());
                    cfg.save();
                    btn.setMessage(onOff("Share if Supporter", cfg.isShareIfSupporter()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(statusLine()), font));
        y += 14;
        if (shareError != null) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§c" + shareError), font));
            y += 14;
        }

        // Fetched here rather than on a constructor hook - see the class doc. widgetsMatchSearch never reaches
        // this, so typing in the search box never fires a live GET.
        boolean neverFetched = lastFetchAtMs < 0;
        boolean stale = !neverFetched && System.currentTimeMillis() - lastFetchAtMs > REFRESH_COOLDOWN_MS;
        if (!fetchInFlight && (neverFetched || stale)) {
            startFetch(requestRebuild);
        }
        SupportersAutoShare.maybeShare(cfg.isShareIfSupporter(), status == MeStatus.LINKED,
                plainOwnName(), NameChangerConfig.getInstance().getOwnColor(), cfg.getOwnScale(),
                err -> shareError = err);

        // --- Custom Names
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Custom Names", false, customNamesOpen,
                () -> {
                    customNamesOpen = !customNamesOpen;
                    requestRebuild.run();
                });
        if (customNamesOpen) {
            List<AbstractWidget> built = nameChangerTab.buildWidgets(contentX, y, contentWidth, requestRebuild);
            for (AbstractWidget w : built) {
                SettingTooltips.scope(w, nameChangerTab.name);
            }
            widgets.addAll(built);
            y = bottomOf(built, y) + 6;
        }

        // --- Player Size
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Player Size", false, playerSizeOpen,
                () -> {
                    playerSizeOpen = !playerSizeOpen;
                    requestRebuild.run();
                });
        if (playerSizeOpen) {
            y = buildPlayerSize(widgets, contentX, y, contentWidth, font, cfg, requestRebuild);
        }

        // --- Held Item
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Held Item", false, heldItemOpen,
                () -> {
                    heldItemOpen = !heldItemOpen;
                    requestRebuild.run();
                });
        if (heldItemOpen) {
            List<AbstractWidget> built = heldItemTab.buildWidgets(contentX, y, contentWidth, requestRebuild);
            for (AbstractWidget w : built) {
                SettingTooltips.scope(w, heldItemTab.name);
            }
            widgets.addAll(built);
            y = bottomOf(built, y) + 6;
        }
        y += 8;

        int gap = 8;
        int halfW = (contentWidth - gap) / 2;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Copy Settings for Global Cosmetics"),
                btn -> copySettingsForBot()).bounds(contentX, y, halfW, 20).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Cosmetics"), btn -> {
                    NameChangerConfig.getInstance().resetOwnNameCosmetics();
                    NameChangerConfig.getInstance().save();
                    SupportersConfig.getInstance().resetModelShape();
                    SupportersConfig.getInstance().save();
                    shareError = null;
                    requestRebuild.run();
                }).bounds(contentX + halfW + gap, y, contentWidth - halfW - gap, 20).build());

        return widgets;
    }

    private String statusLine() {
        return switch (status) {
            case CHECKING -> "§7Checking supporter status...";
            case NOT_LINKED -> "§7Not a linked supporter - your size and name show only for you.";
            case RELAY_UNAVAILABLE -> "§cSupporter relay unavailable.";
            case LINKED -> "§aLinked supporter - your name and size are shared while this is on.";
        };
    }

    private int buildPlayerSize(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Font font,
                                SupportersConfig cfg, Runnable requestRebuild) {
        int resetW = 60;
        int sliderW = contentWidth - resetW - 6;
        widgets.add(sizeSlider(contentX, y, sliderW, "My Size", cfg.getOwnScale(), cfg::setOwnScale, cfg));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset"), btn -> {
                    cfg.setOwnScale(1.0f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + sliderW + 6, y, resetW, 18).build());
        y += 22;
        widgets.add(sizeSlider(contentX, y, sliderW, "Others' Size", cfg.getOthersScale(), cfg::setOthersScale, cfg));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset"), btn -> {
                    cfg.setOthersScale(1.0f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + sliderW + 6, y, resetW, 18).build());
        y += 22;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Width", cfg.getModelWidth(), cfg::setModelWidth, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Height", cfg.getModelHeight(), cfg::setModelHeight, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Thickness", cfg.getModelThickness(), cfg::setModelThickness, cfg));
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Visual only - never touches hitboxes. Width/Height/Thickness are never shared."), font));
        return y + 18;
    }

    private ThemedSliderButton sizeSlider(int x, int y, int width, String label, float current,
                                          java.util.function.Consumer<Float> setter, SupportersConfig cfg) {
        float min = SupportersConfig.MIN_PLAYER_SIZE;
        float max = SupportersConfig.MAX_PLAYER_SIZE;
        double norm = (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, dimensionLabel(label, current),
                Math.max(0.0, Math.min(1.0, norm))) {
            @Override
            protected void updateMessage() {
                setMessage(dimensionLabel(label, snapSize(min + (float) (this.value * (max - min)))));
            }

            @Override
            protected void applyValue() {
                setter.accept(snapSize(min + (float) (this.value * (max - min))));
                cfg.save();
            }
        };
    }

    /** Hundredths, so the shared size is a tidy number and 0.05 and 1.00 can both be hit exactly. */
    private static float snapSize(float raw) {
        return Math.round(raw * 100f) / 100f;
    }

    private ThemedSliderButton dimensionSlider(int x, int y, int width, String label, float current,
                                               java.util.function.Consumer<Float> setter, SupportersConfig cfg) {
        float min = SupportersConfig.MIN_MODEL_DIMENSION;
        float max = SupportersConfig.MAX_MODEL_DIMENSION;
        double norm = (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, dimensionLabel(label, current),
                Math.max(0.0, Math.min(1.0, norm))) {
            @Override
            protected void updateMessage() {
                setMessage(dimensionLabel(label, min + (float) (this.value * (max - min))));
            }

            @Override
            protected void applyValue() {
                float raw = min + (float) (this.value * (max - min));
                float snapped = Math.round(raw / 0.05f) * 0.05f;
                setter.accept(snapped);
                cfg.save();
            }
        };
    }

    private static Component dimensionLabel(String label, float value) {
        return Component.literal(label + ": " + String.format(Locale.ROOT, "%.2fx", value));
    }

    /** Builds the same "name=...;scale=..." shape {@code POST /supporters/me} accepts, for staff's Discord bot
     *  route if the relay push isn't usable. */
    private void copySettingsForBot() {
        NameChangerConfig ncCfg = NameChangerConfig.getInstance();
        String plain = plainOwnName();
        int argb = ncCfg.getOwnColor();
        String rawName = plain.isEmpty() ? "" : (argb == NameColor.NONE ? "" : "&" + NameColor.codeFor(argb)) + plain;
        String text = "name=" + rawName + ";scale=" + SupportersConfig.getInstance().getOwnScale();
        CopyChatFeature.copyToClipboard(text);
        ModOverlayMessage.show("§a[Cosmetics] Copied - hand this to staff for the Discord bot route.", 3000);
    }

    private String plainOwnName() {
        return NameColor.migrate(NameChangerConfig.getInstance().getOwnDisplayName()).text();
    }

    private void startFetch(Runnable requestRebuild) {
        if (fetchInFlight) {
            return;
        }
        fetchInFlight = true;
        lastFetchAtMs = System.currentTimeMillis();
        status = MeStatus.CHECKING;
        long seq = ++requestSeq;
        SupportersSelfService.fetchMe().whenComplete((result, ignored) ->
                Minecraft.getInstance().execute(() -> onMeResult(seq, result, requestRebuild)));
    }

    private void onMeResult(long seq, SupportersSelfService.MeResult result, Runnable requestRebuild) {
        fetchInFlight = false;
        if (seq != requestSeq) {
            return; // a newer fetch already started; this reply is stale
        }
        if (result == null || !result.ok()) {
            status = MeStatus.RELAY_UNAVAILABLE;
        } else if (!result.whitelisted()) {
            status = MeStatus.NOT_LINKED;
        } else {
            status = MeStatus.LINKED;
        }
        requestRebuild.run();
    }

    private static int bottomOf(List<AbstractWidget> built, int fallbackY) {
        int bottom = fallbackY;
        for (AbstractWidget w : built) {
            bottom = Math.max(bottom, w.getY() + w.getHeight());
        }
        return bottom;
    }

    /** Overridden so the search box's per-keystroke label scan never fires a live {@code GET /supporters/me}. */
    @Override
    protected boolean widgetsMatchSearch(String query) {
        String q = query.toLowerCase(Locale.US);
        for (String label : SEARCHABLE_LABELS) {
            if (label.contains(q)) {
                return true;
            }
        }
        return nameChangerTab.matchesSearch(query) || heldItemTab.matchesSearch(query);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
