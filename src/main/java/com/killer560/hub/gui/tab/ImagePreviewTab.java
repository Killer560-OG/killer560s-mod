package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.imagepreview.ImagePreviewConfig;
import com.killer560.hub.imagepreview.ImagePreviewFeature;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Image Preview settings (see {@link ImagePreviewFeature}): the switch, the preview size, which hosts are fetched, GIF
 *  playback and the disk cache's size. */
public class ImagePreviewTab extends BaseTab {

    public ImagePreviewTab() {
        super("Image Preview");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ImagePreviewConfig cfg = ImagePreviewConfig.getInstance();
        int y = contentY;

        widgets.add(onOffButton("Image Preview", cfg::isEnabled, v -> {
            cfg.setEnabled(v);
            ImagePreviewFeature.onSettingsChanged();
        }, cfg::save, requestRebuild, contentX, y, contentWidth, 20));
        y += 26;
        if (!cfg.isEnabled()) {
            return widgets;
        }

        int sizeRange = ImagePreviewConfig.MAX_SIZE - ImagePreviewConfig.MIN_SIZE;
        widgets.add(normSlider(contentX, y, contentWidth, 18, () -> sizeText(ImagePreviewConfig.getInstance()),
                (cfg.getMaxPreviewSize() - ImagePreviewConfig.MIN_SIZE) / (double) sizeRange,
                v -> cfg.setMaxPreviewSize((int) Math.round((ImagePreviewConfig.MIN_SIZE + v * sizeRange) / 8.0) * 8),
                cfg::save));
        y += 22;

        widgets.add(SettingsButtonWidget.cycling(hostsText(cfg), (btn, dir) -> {
            cfg.setAnyHost(!cfg.isAnyHost());
            cfg.save();
            ImagePreviewFeature.onSettingsChanged();
            btn.setMessage(hostsText(cfg));
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(onOffButton("Play GIFs", cfg::isPlayGifs, cfg::setPlayGifs, cfg::save, null, contentX, y,
                contentWidth, 18));
        y += 22;

        int cacheRange = ImagePreviewConfig.MAX_CACHE_MB - ImagePreviewConfig.MIN_CACHE_MB;
        widgets.add(normSlider(contentX, y, contentWidth, 18, () -> cacheText(ImagePreviewConfig.getInstance()),
                (cfg.getCacheSizeMb() - ImagePreviewConfig.MIN_CACHE_MB) / (double) cacheRange,
                v -> cfg.setCacheSizeMb((int) Math.round((ImagePreviewConfig.MIN_CACHE_MB + v * cacheRange) / 10.0) * 10),
                cfg::save));
        return widgets;
    }

    private static String sizeText(ImagePreviewConfig cfg) {
        return "Max Preview Size: " + cfg.getMaxPreviewSize() + " px";
    }

    private static String cacheText(ImagePreviewConfig cfg) {
        return "Cache Size: " + cfg.getCacheSizeMb() + " MB";
    }

    private static Component hostsText(ImagePreviewConfig cfg) {
        return Component.literal("Hosts: §6" + (cfg.isAnyHost() ? "Any Host" : "Trusted Hosts Only"));
    }
}
