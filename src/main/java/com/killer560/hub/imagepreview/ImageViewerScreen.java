package com.killer560.hub.imagepreview;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gifplayer.GifTextureUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Image Preview's bigger viewer, opened by a middle click on a previewed chat link: the picture fit to most of the
 * screen, the album's "N/M" with the arrow keys and mouse wheel stepping through it, and Escape (or any click) back to
 * the chat it came from.
 */
public final class ImageViewerScreen extends Screen {

    private final Screen parent;
    private final ImagePreviewFeature.Entry entry;

    ImageViewerScreen(Screen parent, ImagePreviewFeature.Entry entry) {
        super(Component.literal("Image Preview"));
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xD0000000);
        ImagePreviewFeature.Pic p = ImagePreviewFeature.currentPic(entry);
        int n = entry.pics.size();
        String counter = n > 1 ? (Math.floorMod(entry.index, n) + 1) + "/" + n + "   (arrow keys or mouse wheel)" : "";
        String status = null;
        if (p == null) {
            status = "Loading...";
        } else {
            ImagePreviewFeature.requestLoad(p);
            if (p.error != null) {
                status = "Couldn't load: " + p.error;
            } else if (p.ignored) {
                status = "Not a picture";
            } else if (!ImagePreviewFeature.ensureTexture(p)) {
                status = "Loading...";
            }
        }
        if (status != null) {
            g.text(this.font, status, (this.width - this.font.width(status)) / 2, this.height / 2 - 4, 0xFFFFFFFF, true);
        } else {
            ImageDecoding.Decoded d = p.decoded;
            ImagePreviewFeature.tickAnimation(p, System.currentTimeMillis());
            int maxW = this.width - 40;
            int maxH = this.height - 50;
            double s = Math.min(maxW / (double) d.sourceWidth(), maxH / (double) d.sourceHeight());
            // Never blown up past 4 screen pixels per picture pixel (a tiny emoji would be a blur).
            s = Math.min(s, 4.0 / Math.max(1.0, this.minecraft.getWindow().getGuiScale()));
            int w = Math.max(1, (int) Math.round(d.sourceWidth() * s));
            int h = Math.max(1, (int) Math.round(d.sourceHeight() * s));
            int x = (this.width - w) / 2;
            int y = (this.height - 20 - h) / 2 + 4;
            GifTextureUtil.blit(g, p.texture, x, y, w, h);
            String size = d.sourceWidth() + " x " + d.sourceHeight();
            g.text(this.font, size, (this.width - this.font.width(size)) / 2, y + h + 4, 0xFFAAAAAA, true);
        }
        if (!counter.isEmpty()) {
            g.text(this.font, counter, (this.width - this.font.width(counter)) / 2, this.height - 14, 0xFFFF8C1A, true);
        }
        String link = entry.link.length() > 90 ? entry.link.substring(0, 87) + "..." : entry.link;
        g.text(this.font, link, (this.width - this.font.width(link)) / 2, 6, 0xFFAAAAAA, true);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int n = entry.pics.size();
        if (n > 1 && (event.key() == InputConstants.KEY_RIGHT || event.key() == InputConstants.KEY_LEFT)) {
            ImagePreviewFeature.step(entry, event.key() == InputConstants.KEY_RIGHT ? 1 : -1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entry.pics.size() > 1 && scrollY != 0) {
            ImagePreviewFeature.step(entry, scrollY > 0 ? -1 : 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
