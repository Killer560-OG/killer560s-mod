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
import com.killer560.hub.supporters.SupportersFeature;
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
 * killer560's cosmetics request, all in one place: "Create a custom cosmetic tab that houses all of your
 * personal custom cosmetics and whatnot and there you can toggle global cosmetics." Replaces the old
 * Supporters tab entirely (removed per his explicit "remove the supporter tab") and bundles in Name Changer
 * and Held Item Transform, moved here unchanged - "the ingame name changer worked though", "bundle the name
 * changer into the new cosmetics tab", "move held item transform into the new cosmetics tab" - by literally
 * delegating to their existing tab classes ({@link #nameChangerTab}/{@link #heldItemTab}) rather than
 * reimplementing either one, so nothing about how they already work changes.
 * <p>
 * What's actually NEW here (everything else is the old Supporters tab's own editor, automated, or a straight
 * move):
 * <ul>
 *   <li><b>Player Model</b> - Scale (existing item 8.5 mechanic, relay-shared, see below) plus brand new
 *       Width/Height/Thickness, which are PURE client-side {@code PoseStack} scale on your own F5 model only
 *       ({@code com.killer560.hub.supporters.mixin.CosmeticsModelShapeMixin}) - never your hitbox, collision
 *       or reach, and never sent to the server or the relay (the supporters contract has no field for them).
 *   <li><b>Fade Color</b> for your display name lives inside the delegated Name Changer section (its own
 *       "My Name Color" swatch grows a second one right next to it) rather than a separate field here - see
 *       {@code NameChangerTab}'s own doc for why that's the same setting, not a new one.
 *   <li><b>Share if Supporter</b> - the old Supporters tab needed a manual Save press to push your name/scale
 *       to the relay; this tab does it FOR you via {@link SupportersAutoShare} whenever this toggle is on and
 *       the account is confirmed linked, per killer560's "if the account is a supporter, its cosmetics should
 *       be shared with others automatically."
 *   <li><b>Copy Settings for Global Cosmetics</b> - the Discord-bot fallback he asked for in case the
 *       staff-only {@code /supporter} command route is still needed alongside (or instead of) the relay push.
 *   <li><b>Reset</b> - one bottom button that puts every cosmetic VALUE (display name/colour/fade, model
 *       shape) back to default. Deliberately does not flip any feature's own ON/OFF toggle, same distinction
 *       {@code HeldItemConfig#resetValues} already draws - Name Changer/Held Item Transform stay exactly as
 *       enabled or disabled as they were.
 * </ul>
 * <b>Scale vs Width/Height/Thickness</b>: Scale is the ORIGINAL item 8.5 mechanic - a relay-shared, per-UUID
 * multiplier ({@code SupportersScaleMixin}) that only ever visibly applies once this account is a confirmed,
 * sharing supporter (it's driven by the PUBLIC {@code /supporters} list, which only lists actual supporters).
 * Width/Height/Thickness are new, always-local additions that apply to your own view regardless of supporter
 * status. Both are genuinely separate mechanisms kept side by side deliberately, rather than merged into one
 * "local scale" field, to avoid double-applying scale once someone IS both a supporter and sharing (Scale
 * would then be applied twice - once locally, once via the relay's own PUBLIC list reflecting the same
 * number back at yourself).
 * <p>
 * This tab instance is built once and reused for the whole game session (see {@code ModScreen.tabs}, and
 * {@code SupportersTab}'s own old class doc for why), so {@link #status}/{@link #scaleInput}/{@link
 * #shareError} are plain instance state, not persisted settings - the linked/whitelisted status always comes
 * fresh from the relay, same as before.
 */
public class CosmeticsTab extends BaseTab {

    private enum MeStatus {CHECKING, NOT_LINKED, RELAY_UNAVAILABLE, LINKED}

    /** Same idea as the old Supporters tab's own cooldown - avoids re-hitting the relay on every rebuild
     *  (scrolling, an unrelated widget's callback) while still catching up if the tab is left open a while. */
    private static final long REFRESH_COOLDOWN_MS = 30_000L;

    private static final List<String> SEARCHABLE_LABELS = List.of(
            "cosmetics", "toggle global cosmetics", "share if supporter", "my cosmetics", "player model",
            "scale", "width", "height", "thickness", "copy settings for global cosmetics",
            "reset cosmetics", "refresh");

    private final NameChangerTab nameChangerTab = new NameChangerTab();
    private final HeldItemTab heldItemTab = new HeldItemTab();

    // Touched from the relay's own IO thread (SupportersSelfService/SupportersAutoShare) as well as the
    // client thread - volatile rather than instance-plain, same reasoning the old Supporters tab used.
    private volatile MeStatus status = MeStatus.CHECKING;
    private volatile boolean fetchInFlight;
    private volatile long lastFetchAtMs = -1L;
    private volatile long requestSeq;

    // Editor state - client thread only.
    private double scaleInput = 1.0;
    private String shareError;

    private boolean myCosmeticsOpen = true;
    private boolean playerModelOpen = true;

    public CosmeticsTab() {
        super("Cosmetics");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        Font font = Minecraft.getInstance().font;
        SupportersConfig cfg = SupportersConfig.getInstance();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Your own cosmetics, plus (if you're a supporter) sharing them."), font));
        y += 18;

        widgets.add(SettingsButtonWidget.builder(onOff("Toggle Global Cosmetics", cfg.isCustomCosmeticsEnabled()),
                btn -> {
                    cfg.setCustomCosmeticsEnabled(!cfg.isCustomCosmeticsEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Toggle Global Cosmetics", cfg.isCustomCosmeticsEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        int count = SupportersFeature.supporterCount();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(count == 0
                ? "§7No other supporters loaded yet."
                : "§7" + count + " other supporter" + (count == 1 ? "" : "s") + " loaded."), font));
        y += 20;

        // Kicked off here, not a constructor/init hook - see class doc (this tab instance persists for the
        // whole session; "on opening the tab" in practice means "the first buildWidgets call, or the first
        // one in a while"). widgetsMatchSearch below never reaches this real buildWidgets, so a live GET
        // never fires just from typing into the search box.
        boolean neverFetched = lastFetchAtMs < 0;
        boolean stale = !neverFetched && System.currentTimeMillis() - lastFetchAtMs > REFRESH_COOLDOWN_MS;
        if (!fetchInFlight && (neverFetched || stale)) {
            startFetch(requestRebuild);
        }
        SupportersAutoShare.maybeShare(cfg.isShareIfSupporter(), status == MeStatus.LINKED,
                plainOwnName(), NameChangerConfig.getInstance().getOwnColor(), scaleInput,
                err -> shareError = err);

        y = buildMyCosmeticsSection(widgets, contentX, y, contentWidth, font, cfg, requestRebuild);
        y += 6;
        y = buildPlayerModelSection(widgets, contentX, y, contentWidth, font, cfg, requestRebuild);
        y += 10;

        List<AbstractWidget> nameChangerWidgets = nameChangerTab.buildWidgets(contentX, y, contentWidth, requestRebuild);
        for (AbstractWidget w : nameChangerWidgets) {
            SettingTooltips.scope(w, nameChangerTab.name);
        }
        widgets.addAll(nameChangerWidgets);
        y = bottomOf(nameChangerWidgets, y) + 10;

        List<AbstractWidget> heldItemWidgets = heldItemTab.buildWidgets(contentX, y, contentWidth, requestRebuild);
        for (AbstractWidget w : heldItemWidgets) {
            SettingTooltips.scope(w, heldItemTab.name);
        }
        widgets.addAll(heldItemWidgets);
        y = bottomOf(heldItemWidgets, y) + 10;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Cosmetics"), btn -> {
                    NameChangerConfig.getInstance().resetOwnNameCosmetics();
                    NameChangerConfig.getInstance().save();
                    SupportersConfig.getInstance().resetModelShape();
                    SupportersConfig.getInstance().save();
                    scaleInput = 1.0;
                    shareError = null;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private int buildMyCosmeticsSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
                                        Font font, SupportersConfig cfg, Runnable requestRebuild) {
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "My Cosmetics", false, myCosmeticsOpen,
                () -> {
                    myCosmeticsOpen = !myCosmeticsOpen;
                    requestRebuild.run();
                });
        if (!myCosmeticsOpen) {
            return y;
        }

        int refreshW = 64;
        widgets.add(new StringWidget(contentX, y, contentWidth - refreshW - 6, 12,
                Component.literal("§7Supporter status"), font));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> {
                    startFetch(requestRebuild);
                    requestRebuild.run();
                }).bounds(contentX + contentWidth - refreshW, y - 3, refreshW, 16).build());
        y += 18;

        switch (status) {
            case CHECKING -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7Checking..."), font));
                y += 16;
            }
            case NOT_LINKED -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§7Not linked to a supporter - ask staff on Discord."), font));
                y += 16;
            }
            case RELAY_UNAVAILABLE -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§cRelay unavailable."), font));
                y += 16;
            }
            case LINKED -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§aLinked - sharing is automatic while Share if Supporter is on."), font));
                y += 16;
            }
        }

        double normalized = clampNormalized((scaleInput - 0.5) / 1.5);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, scaleLabel(scaleInput), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleLabel(0.5 + this.value * 1.5));
            }

            @Override
            protected void applyValue() {
                scaleInput = Math.round((0.5 + this.value * 1.5) * 100.0) / 100.0;
            }
        });
        y += 20;
        if (status != MeStatus.LINKED) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Scale only shows once this account is a linked supporter."), font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Share if Supporter", cfg.isShareIfSupporter()), btn -> {
                    cfg.setShareIfSupporter(!cfg.isShareIfSupporter());
                    cfg.save();
                    btn.setMessage(onOff("Share if Supporter", cfg.isShareIfSupporter()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Copy Settings for Global Cosmetics"), btn -> {
                    copySettingsForBot();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (shareError != null) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§c" + shareError), font));
            y += 16;
        }
        return y + 4;
    }

    private int buildPlayerModelSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
                                        Font font, SupportersConfig cfg, Runnable requestRebuild) {
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Player Model", false, playerModelOpen,
                () -> {
                    playerModelOpen = !playerModelOpen;
                    requestRebuild.run();
                });
        if (!playerModelOpen) {
            return y;
        }
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Width", cfg.getModelWidth(), cfg::setModelWidth, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Height", cfg.getModelHeight(), cfg::setModelHeight, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Thickness", cfg.getModelThickness(), cfg::setModelThickness, cfg));
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Visual only, your own F5 model only - never shared, never touches hitboxes."), font));
        return y + 16;
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

    /** Builds the same "name=...;scale=..." shape {@code POST /supporters/me} accepts, for staff's Discord
     *  bot route to parse if the relay push isn't usable right now - killer560: "If the Discord-bot route
     *  must stay, add a 'Copy settings for global cosmetics' button that copies a string the bot can parse." */
    private void copySettingsForBot() {
        NameChangerConfig ncCfg = NameChangerConfig.getInstance();
        String plain = plainOwnName();
        int argb = ncCfg.getOwnColor();
        String rawName = plain.isEmpty() ? "" : (argb == NameColor.NONE ? "" : "&" + NameColor.codeFor(argb)) + plain;
        String text = "name=" + rawName + ";scale=" + scaleInput;
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
        if (!result.ok()) {
            status = MeStatus.RELAY_UNAVAILABLE;
        } else if (!result.whitelisted()) {
            status = MeStatus.NOT_LINKED;
        } else {
            status = MeStatus.LINKED;
            scaleInput = result.scale();
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

    /** Overridden so the search box's per-keystroke label scan never fires a live {@code GET /supporters/me}
     *  for this tab (same reasoning the old Supporters tab's own override had) - delegates to the two bundled
     *  sub-tabs' own (side-effect-free) search matching for their labels. */
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

    private static double clampNormalized(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Component scaleLabel(double scale) {
        return Component.literal(String.format(Locale.ROOT, "Scale: %.2f", scale));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
