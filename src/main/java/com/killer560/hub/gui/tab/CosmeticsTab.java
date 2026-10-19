package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.copychat.CopyChatFeature;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mobskin.MobPickerScreen;
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
            "cosmetics", "toggle global cosmetics", "global cosmetics", "supporter names", "supporter sizes", "share if supporter",
            "share trail", "show others' trails", "trail", "ribbon trail", "ribbon", "custom names", "my name in f5", "player size",
            "my size", "others' size", "width", "height", "thickness", "held item", "play as mob", "my mob",
            "show others' mobs", "mob",
            "copy settings for global cosmetics", "reset cosmetics");

    private final NameChangerTab nameChangerTab = new NameChangerTab();
    private final HeldItemTab heldItemTab = new HeldItemTab();
    private final RibbonTrailTab ribbonTrailTab = new RibbonTrailTab(true);

    // Touched from the relay's own IO thread as well as the client thread.
    private volatile MeStatus status = MeStatus.CHECKING;
    private volatile boolean fetchInFlight;
    private volatile long lastFetchAtMs = -1L;
    private volatile long requestSeq;

    private String shareError;

    private boolean customNamesOpen = false;
    private boolean heldItemOpen = false;
    private boolean playAsMobOpen = false;
    private boolean ribbonTrailOpen = false;

    public CosmeticsTab() {
        super("Player Cosmetics");
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
        // Trails (killer560, 2026-10-13: "it should be toggleable to see in cosmetics for whether it is shared or not
        // and it can only be shared by a supporter"): Share Trail is greyed out, with the reason under it, until the
        // relay says this account is a linked supporter. Only the Ribbon Trail is shared (2026-10-14); the same two
        // switches are on the Ribbon Trail tab too.
        String trailBlocker = SupportersAutoShare.trailShareBlocker();
        boolean canShareTrail = SupportersAutoShare.isLinked();
        SettingsButtonWidget shareTrail = SettingsButtonWidget.builder(onOff("Share Trail", cfg.isShareTrail() && canShareTrail),
                btn -> {
                    cfg.setShareTrail(!cfg.isShareTrail());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half, 18).build();
        shareTrail.active = canShareTrail;
        widgets.add(shareTrail);
        widgets.add(SettingsButtonWidget.builder(onOff("Show Others' Trails", cfg.isShowOthersTrails()), btn -> {
                    cfg.setShowOthersTrails(!cfg.isShowOthersTrails());
                    cfg.save();
                    btn.setMessage(onOff("Show Others' Trails", cfg.isShowOthersTrails()));
                }).bounds(contentX + half + 4, y, contentWidth - half - 4, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Show Others' Mobs", cfg.isShowOthersMobs()), btn -> {
                    cfg.setShowOthersMobs(!cfg.isShowOthersMobs());
                    cfg.save();
                    btn.setMessage(onOff("Show Others' Mobs", cfg.isShowOthersMobs()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        if (trailBlocker != null && (cfg.isShareTrail() || !canShareTrail)) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7" + trailBlocker), font));
            y += 14;
        }
        // The status line wraps to the tab's width, so the whole sentence can be read (killer560, 2026-10-18).
        String status = statusLine();
        String colour = status.length() >= 2 && status.charAt(0) == '§' ? status.substring(0, 2) : "";
        String rest = status.substring(colour.length());
        while (!rest.isEmpty()) {
            String line = font.plainSubstrByWidth(rest, contentWidth);
            if (line.isEmpty()) {
                line = rest.substring(0, 1);
            }
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(colour + line), font));
            y += 12;
            rest = rest.substring(line.length());
        }
        y += 2;
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
        SupportersAutoShare.shareCurrent(err -> shareError = err);

        // My Name in F5 sits above Custom Names, outside it on purpose: it works with the Name Changer off too (your real name).
        widgets.add(SettingsButtonWidget.builder(onOff("My Name in F5", cfg.isOwnNameInF5()), btn -> {
                    cfg.setOwnNameInF5(!cfg.isOwnNameInF5());
                    cfg.save();
                    btn.setMessage(onOff("My Name in F5", cfg.isOwnNameInF5()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

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

        // --- Play as Mob (killer560, 2026-10-18: "Make an option to change your character to any ingame mob. Still have
        // scale sliders apply. If you are a supporter it can be shared.")
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Play as Mob", false, playAsMobOpen,
                () -> {
                    playAsMobOpen = !playAsMobOpen;
                    requestRebuild.run();
                });
        if (playAsMobOpen) {
            y = buildPlayAsMob(widgets, contentX, y, contentWidth, font, cfg);
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

        // --- Ribbon Trail (killer560, 2026-10-18: the shareable trail is adjusted here and nowhere else)
        y = CollapsibleSection.header(widgets, contentX, y, contentWidth, "Ribbon Trail", false, ribbonTrailOpen,
                () -> {
                    ribbonTrailOpen = !ribbonTrailOpen;
                    requestRebuild.run();
                });
        if (ribbonTrailOpen) {
            List<AbstractWidget> built = ribbonTrailTab.buildWidgets(contentX, y, contentWidth, requestRebuild);
            for (AbstractWidget w : built) {
                SettingTooltips.scope(w, ribbonTrailTab.name);
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
                    SupportersConfig.getInstance().setOwnMob("");
                    SupportersConfig.getInstance().save();
                    shareError = null;
                    requestRebuild.run();
                }).bounds(contentX + halfW + gap, y, contentWidth - halfW - gap, 20).build());

        return widgets;
    }

    private String statusLine() {
        return switch (status) {
            case CHECKING -> "§7Checking supporter status...";
            case NOT_LINKED -> "§7Not a linked supporter - your size, name and mob show only for you.";
            case RELAY_UNAVAILABLE -> "§cSupporter relay unavailable.";
            case LINKED -> "§aLinked supporter - your name, size and mob are shared while this is on.";
        };
    }

    private int buildPlayAsMob(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Font font,
                               SupportersConfig cfg) {
        widgets.add(SettingsButtonWidget.builder(onOff("Play as Mob", cfg.isPlayAsMob()), btn -> {
                    cfg.setPlayAsMob(!cfg.isPlayAsMob());
                    cfg.save();
                    btn.setMessage(onOff("Play as Mob", cfg.isPlayAsMob()));
                    SupportersAutoShare.shareCurrent(err -> shareError = err);
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        // The picker is its own menu (killer560, 2026-10-18): the search box and the list live there. Picking a mob
        // saves it, shares it as Share if Supporter does, and returns here, where the rebuilt button shows it.
        widgets.add(SettingsButtonWidget.builder(myMobLabel(cfg.getOwnMob()), btn -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreenAndShow(new MobPickerScreen(McCompat.screen(mc), cfg.getOwnMob(), id -> {
                        cfg.setOwnMob(id);
                        cfg.save();
                        SupportersAutoShare.shareCurrent(err -> shareError = err);
                    }));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        return y;
    }

    private static Component myMobLabel(String id) {
        return Component.literal("My Mob: " + com.killer560.hub.mobskin.MobSkins.displayName(id));
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

    /** Your display name as shared: its text without the colour (sent separately), with an {@code &l} in front when
     *  Bold Name is on - the relay accepts {@code &k-o} (SupporterNameValidator), so bold is shared with no relay change. */
    private String plainOwnName() {
        NameChangerConfig nc = NameChangerConfig.getInstance();
        String text = NameColor.migrate(nc.getOwnDisplayName()).text();
        return nc.isOwnBold() && !text.isEmpty() ? "&l" + text : text;
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
        SupportersAutoShare.setLinked(status == MeStatus.LINKED);
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
