package com.killer560.hub.gui.tab;

import com.killer560.hub.collectiontracker.CollectionTracker;
import com.killer560.hub.collectiontracker.CollectionTrackerConfig;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.profileviewer.data.ExtraTables.CollectionCategory;
import com.killer560.hub.profileviewer.data.ExtraTables.CollectionItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Misc's Collection Tracker card ({@link CollectionTracker}): the switch, Show Rate, then the picker - a search box and one
 * switch per collection, grouped by Hypixel's categories (Farming, Mining, Combat, Foraging, Fishing, Rift). Typing in the
 * search hides the rows that do not match and closes the gaps, without a rebuild, so the box keeps the cursor.
 */
public class CollectionTrackerTab extends BaseTab {

    /** Search text: transient, kept across rebuilds of this tab. */
    private String search = "";

    public CollectionTrackerTab() {
        super("Collection Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        if (masterClosed(widgets, "Collection Tracker", () -> cfg().isEnabledRaw(), v -> cfg().setEnabled(v), () -> cfg().save(),
                requestRebuild, contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;
        widgets.add(onOffButton("Show Rate", () -> cfg().isShowRate(), v -> cfg().setShowRate(v), () -> cfg().save(), null,
                contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(onOffButton("Only Recent", () -> cfg().isOnlyRecent(), v -> cfg().setOnlyRecent(v), () -> cfg().save(),
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (cfg().isOnlyRecent() || isSearchScan()) {
            widgets.add(steppedSlider(contentX, y, contentWidth, 20, recentText(), CollectionTrackerConfig.RECENT_MIN,
                    CollectionTrackerConfig.RECENT_MAX, cfg().getRecentSeconds(), 5, v -> cfg().setRecentSeconds((int) Math.round(v)),
                    CollectionTrackerTab::recentText, () -> cfg().save()));
            y += 24;
        }
        y += 4;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Collections", false));
        y += 16;
        // The menu search builds every tab: it must not start the list's read.
        CollectionTracker.Catalog cat = isSearchScan() ? null : CollectionTracker.catalog();
        if (cat == null) {
            if (!isSearchScan()) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§7Loading the collection list..."), Minecraft.getInstance().font));
                CollectionTracker.whenCatalogLoaded(requestRebuild);
            }
            return widgets;
        }
        CommitEditBox box = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Search Collections"));
        box.setMaxLength(40);
        box.setHint(Component.literal("§8Search Collections"));
        box.setValue(search);
        widgets.add(box);
        y += 24;

        List<AbstractWidget> rows = new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        int gap = 4;
        int colW = (contentWidth - gap) / 2;
        for (CollectionCategory c : cat.categories()) {
            StringWidget header = SectionHeaders.row(contentX, y, contentWidth, c.name(), false);
            widgets.add(header);
            rows.add(header);
            keys.add(new String[]{c.name().toLowerCase(Locale.ROOT)});
            for (CollectionItem it : c.items()) {
                AbstractWidget b = onOffButton(it.name(), () -> cfg().isSelected(it.id()), v -> cfg().setSelected(it.id(), v),
                        () -> cfg().save(), null, contentX, y, colW, 20);
                SettingTooltips.live(b, () -> "Shows " + it.name() + " (" + c.name() + ", " + it.tiers().length
                        + " tiers) on the Collection Tracker HUD: amount, tier, progress to the next tier and gain per hour. Up to "
                        + CollectionTrackerConfig.MAX_SELECTED + " picks.");
                widgets.add(b);
                rows.add(b);
                keys.add(new String[]{it.name().toLowerCase(Locale.ROOT), c.name().toLowerCase(Locale.ROOT)});
            }
        }
        int top = y;
        Runnable layout = () -> place(rows, keys, search, contentX, top, colW + gap);
        box.setResponder(text -> {
            search = text;
            layout.run();
        });
        layout.run();
        return widgets;
    }

    private static Component recentText() {
        return Component.literal("Recent Window: " + cfg().getRecentSeconds() + " s");
    }

    /** Shows the headers and switches matching {@code query} (a category name shows all of it), two switches a row. */
    private static void place(List<AbstractWidget> rows, List<String[]> keys, String query, int x, int top, int colStep) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int y = top;
        int col = 0;
        boolean catHit = false;
        StringWidget pendingHeader = null;
        for (int i = 0; i < rows.size(); i++) {
            AbstractWidget w = rows.get(i);
            String[] k = keys.get(i);
            if (w instanceof StringWidget header) {
                if (col == 1) {
                    y += 24;
                    col = 0;
                }
                header.visible = false;
                pendingHeader = header;
                catHit = q.isEmpty() || k[0].contains(q);
                continue;
            }
            boolean show = catHit || k[0].contains(q);
            w.visible = show;
            if (!show) continue;
            if (pendingHeader != null) {
                pendingHeader.visible = true;
                pendingHeader.setY(y + (y == top ? 0 : 4));
                y += y == top ? 16 : 20;
                pendingHeader = null;
            }
            w.setX(x + col * colStep);
            w.setY(y);
            if (col == 1) {
                y += 24;
            }
            col ^= 1;
        }
    }

    /** Read on every click: a profile load replaces the instance. */
    private static CollectionTrackerConfig cfg() {
        return CollectionTrackerConfig.getInstance();
    }
}
