package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.supporters.SupporterNameValidator;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.supporters.SupportersFeature;
import com.killer560.hub.supporters.SupportersSelfService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings for killer560's item 8.5 ("mod-wide custom IGNs for supporters") plus SUPPORTERS-CONTRACT-V2.md's
 * "Supporter self-service" section: a whitelisted player can now set their own name/scale from inside the
 * mod ({@code GET}/{@code POST}/{@code DELETE /supporters/me}, via {@link SupportersSelfService}) instead of
 * only through the staff-only Discord {@code /supporter} command.
 * <p>
 * "Toggle Custom Cosmetics" ships ON by default - killer560's own explicit spec for this feature (item 8.5
 * literally lists it as a requirement), the deliberate exception to every other New-tab feature's "ships OFF
 * until confirmed" rule.
 * <p>
 * This tab instance is built once and reused for the whole game session (see {@code ModScreen.tabs}), so its
 * own-account status ({@link #status}) and editor fields are plain instance state rather than anything
 * persisted - they come fresh from the relay every time, never from a settings file.
 */
public class SupportersTab extends BaseTab {

    private enum MeStatus {CHECKING, NOT_LINKED, RELAY_UNAVAILABLE, EDITOR}

    /** Re-open, unrelated rebuilds (scrolling, other widgets' callbacks) all re-run {@code buildWidgets} -
     *  this keeps a fresh-enough already-loaded status from re-hitting the relay on every one of those,
     *  while still catching up if the tab is left open a while (e.g. staff link the account while the
     *  player has the menu open and hits Refresh, or just reopens the menu later). */
    private static final long REFRESH_COOLDOWN_MS = 30_000L;

    private static final List<String> SEARCHABLE_LABELS = List.of(
            "custom cosmetics", "my supporter name", "refresh", "save", "clear");

    // Touched from the relay's own IO thread (see SupportersSelfService) as well as the client thread -
    // volatile rather than instance-plain for the fields a background callback can race the next
    // buildWidgets call on.
    private volatile MeStatus status = MeStatus.CHECKING;
    private volatile boolean fetchInFlight;
    private volatile long lastFetchAtMs = -1L;
    /** Bumped on every fetch/save/clear kicked off, so a callback from a request a newer action has already
     *  superseded (e.g. Refresh then immediately Save) is ignored instead of clobbering fresher state. */
    private volatile long requestSeq;

    // Editor state - client thread only (buildWidgets, EditBox responders and button presses all run there).
    private String nameInput = "";
    private double scaleInput = 1.0;
    private String errorMessage;
    private boolean saving;

    public SupportersTab() {
        super("Supporters");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        SupportersConfig cfg = SupportersConfig.getInstance();
        Font font = Minecraft.getInstance().font;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Cosmetics", cfg.isCustomCosmeticsEnabled()), btn -> {
                    cfg.setCustomCosmeticsEnabled(!cfg.isCustomCosmeticsEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Custom Cosmetics", cfg.isCustomCosmeticsEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        int count = SupportersFeature.supporterCount();
        String countText = count == 0
                ? "§7No supporters loaded yet."
                : "§7" + count + " supporter" + (count == 1 ? "" : "s") + " loaded.";
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(countText), font));
        y += 20;

        // Kicked off here rather than in a constructor/init hook: this tab instance is built once and
        // reused for the whole session (see class doc), so "on opening the tab" in practice means "the
        // first buildWidgets call, or the first one in a while" - never once per keystroke while the
        // search box scans this tab's widgets, since widgetsMatchSearch is overridden below specifically
        // to avoid calling into the real buildWidgets at all.
        boolean neverFetched = lastFetchAtMs < 0;
        boolean stale = !neverFetched && System.currentTimeMillis() - lastFetchAtMs > REFRESH_COOLDOWN_MS;
        if (!fetchInFlight && (neverFetched || stale)) {
            startFetch(requestRebuild);
        }

        int refreshW = 64;
        widgets.add(new StringWidget(contentX, y, contentWidth - refreshW - 6, 12,
                SectionHeaders.header("My Supporter Name", false), font));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> {
                    startFetch(requestRebuild);
                    requestRebuild.run();
                }).bounds(contentX + contentWidth - refreshW, y - 3, refreshW, 16).build());
        y += 18;

        switch (status) {
            case CHECKING -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§7Checking..."), font));
                y += 16;
            }
            case NOT_LINKED -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§7This account is not linked to a supporter - ask staff on Discord."),
                        font));
                y += 16;
            }
            case RELAY_UNAVAILABLE -> {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§cRelay unavailable."), font));
                y += 16;
            }
            case EDITOR -> y = buildEditor(widgets, contentX, y, contentWidth, font, requestRebuild);
        }

        return widgets;
    }

    private int buildEditor(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Font font,
                             Runnable requestRebuild) {
        EditBox nameBox = new EditBox(font, contentX, y, contentWidth, 18, Component.literal("Supporter name"));
        nameBox.setMaxLength(48);
        nameBox.setValue(nameInput);
        nameBox.setHint(Component.literal("§8&6Name with & color codes"));
        nameBox.setResponder(text -> {
            nameInput = text;
            errorMessage = null;
        });
        widgets.add(nameBox);
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Preview: ").append(Component.literal(SupportersFeature.colorize(nameInput))),
                font));
        y += 16;

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
        y += 24;

        int gap = 8;
        int halfW = (contentWidth - gap) / 2;
        widgets.add(SettingsButtonWidget.builder(Component.literal(saving ? "Saving..." : "Save"), btn -> {
                    if (saving) {
                        return;
                    }
                    String localError = SupporterNameValidator.validate(nameInput);
                    if (localError != null) {
                        errorMessage = localError;
                        requestRebuild.run();
                        return;
                    }
                    saving = true;
                    errorMessage = null;
                    requestRebuild.run();
                    long seq = ++requestSeq;
                    SupportersSelfService.save(nameInput, scaleInput).whenComplete((result, ignored) ->
                            Minecraft.getInstance().execute(() -> onSaveResult(seq, result, requestRebuild)));
                }).bounds(contentX, y, halfW, 20).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Clear"), btn -> {
                    if (saving) {
                        return;
                    }
                    saving = true;
                    errorMessage = null;
                    requestRebuild.run();
                    long seq = ++requestSeq;
                    SupportersSelfService.clear().whenComplete((result, ignored) ->
                            Minecraft.getInstance().execute(() -> onSaveResult(seq, result, requestRebuild)));
                }).bounds(contentX + halfW + gap, y, halfW, 20).build());
        y += 24;

        if (errorMessage != null) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§c" + errorMessage), font));
            y += 16;
        }

        return y;
    }

    /** Kicks off (or, if one is already in flight, no-ops) a {@code GET /supporters/me} - see
     *  {@link SupportersSelfService#fetchMe()}. Deliberately does not call {@code requestRebuild} itself:
     *  when triggered from inside {@code buildWidgets} the {@link #status} it just set to {@code CHECKING}
     *  is already reflected in the widget list this same call is building, and calling back into
     *  {@code requestRebuild} (which re-enters {@code buildWidgets}) from there would recurse. The explicit
     *  Refresh button calls {@code requestRebuild.run()} itself right after, since that call happens from a
     *  button press, not from inside a build. */
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
            return; // a newer fetch/save/clear already started; this reply is stale
        }
        if (!result.ok()) {
            status = MeStatus.RELAY_UNAVAILABLE;
        } else if (!result.whitelisted()) {
            status = MeStatus.NOT_LINKED;
        } else {
            status = MeStatus.EDITOR;
            nameInput = result.name() == null ? "" : result.name();
            scaleInput = result.scale();
        }
        requestRebuild.run();
    }

    private void onSaveResult(long seq, SupportersSelfService.SaveResult result, Runnable requestRebuild) {
        if (seq != requestSeq) {
            return; // superseded by a newer action
        }
        saving = false;
        if (result.ok()) {
            nameInput = result.name() == null ? "" : result.name();
            scaleInput = result.scale();
            errorMessage = null;
            // Show the change on THIS client's own nametag/tab list/chat right away instead of waiting up
            // to 5 minutes for the next scheduled GET /supporters (SUPPORTERS-CONTRACT-V2.md).
            SupportersFeature.refreshNow();
        } else if (result.httpStatus() == 403) {
            // Unlinked since the editor opened (or the token's UUID never was) - fall back to the same
            // state a fresh GET /supporters/me with whitelisted:false would have given.
            status = MeStatus.NOT_LINKED;
            errorMessage = null;
        } else {
            errorMessage = result.error() == null ? "Something went wrong." : result.error();
        }
        requestRebuild.run();
    }

    /** Overridden so the search box's per-keystroke label scan (see {@code BaseTab#widgetsMatchSearch})
     *  never calls the real {@code buildWidgets} for this tab - that would fire a live
     *  {@code GET /supporters/me} check for every tab on every character typed into search, whether or not
     *  this tab is even the one open. This tab's real widget labels are fixed to the set below, so matching
     *  against those directly gives identical search results without touching the network. */
    @Override
    protected boolean widgetsMatchSearch(String query) {
        String q = query.toLowerCase(Locale.US);
        for (String label : SEARCHABLE_LABELS) {
            if (label.contains(q)) {
                return true;
            }
        }
        return false;
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
