package com.killer560.hub.gui.tab;

import com.killer560.hub.chunkcache.ChunkCacheConfig;
import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chunk Cache settings - see {@link ChunkCacheManager} for what the cache actually does and what it deliberately
 *  leaves alone (rendering, lighting, entities). */
public class ChunkCacheTab extends BaseTab {

    public ChunkCacheTab() {
        super("Chunk Cache");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChunkCacheConfig cfg = ChunkCacheConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Chunk Cache", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(label(contentX, y, contentWidth, "§7Keeps every chunk you have loaded readable for the rest of the"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7world, so the Interactive Map, secret waypoints and solvers still"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7see rooms you walked away from. Cleared on every world change."));
        y += 16;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Memory", false), Minecraft.getInstance().font));
        y += 16;

        int range = ChunkCacheConfig.MAX_CHUNKS - ChunkCacheConfig.MIN_CHUNKS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, maxChunksText(),
                (cfg.getMaxChunks() - ChunkCacheConfig.MIN_CHUNKS) / (double) range) {
            @Override
            protected void updateMessage() {
                setMessage(maxChunksText());
            }

            @Override
            protected void applyValue() {
                // 100-chunk steps
                int chunks = (int) Math.round((ChunkCacheConfig.MIN_CHUNKS + this.value * range) / 100.0) * 100;
                ChunkCacheConfig c = ChunkCacheConfig.getInstance();
                c.setMaxChunks(chunks);
                c.save();
            }
        });
        y += 24;

        widgets.add(label(contentX, y, contentWidth, statusText()));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Clear Cache"), btn -> {
                    ChunkCacheManager.clearCache();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(label(contentX, y, contentWidth, "§7Oldest chunks are dropped first once the limit is reached."));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7A cached chunk is a snapshot: the server sends no block updates"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7for chunks it no longer tracks, so it refreshes when you return."));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7Nothing extra is rendered - cached chunks are for reading only."));

        return widgets;
    }

    private static String statusText() {
        int count = ChunkCacheManager.cachedCount();
        long bytes = ChunkCacheManager.estimatedBytes();
        return "§6Cached: §f" + count + " chunks §7(~" + (bytes / (1024L * 1024L)) + " MB estimated)";
    }

    private static Component maxChunksText() {
        return Component.literal("Max Cached Chunks: " + ChunkCacheConfig.getInstance().getMaxChunks());
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
