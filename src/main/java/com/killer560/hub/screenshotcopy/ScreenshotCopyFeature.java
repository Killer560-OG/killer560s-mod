package com.killer560.hub.screenshotcopy;

import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** When enabled, copies whatever screenshot vanilla's own screenshot key just took to the system
 *  clipboard as an image, in addition to the normal save-to-disk vanilla already does. Triggered via
 *  {@link com.killer560.hub.screenshotcopy.mixin.ScreenshotMixin}, which wraps the real completion
 *  callback {@code Screenshot.grab} hands back once the PNG is actually written - see that class's
 *  own doc for why an earlier version of this feature (guessing a fixed delay after the keypress
 *  instead) didn't reliably work. Ships disabled by default - see {@link ScreenshotCopyConfig}. */
public final class ScreenshotCopyFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-screenshotcopy");

    private ScreenshotCopyFeature() {
    }

    /** Called from {@link com.killer560.hub.screenshotcopy.mixin.ScreenshotMixin} the moment vanilla
     *  itself confirms a screenshot finished writing - may run on whatever thread that callback fires
     *  on, not necessarily the render thread, but everything done here (file IO, AWT clipboard) is
     *  safe off the render thread. */
    public static void onVanillaScreenshotSaved() {
        if (!ScreenshotCopyConfig.getInstance().isEnabled()) {
            return;
        }
        copyNewestScreenshotToClipboard();
    }

    private static void copyNewestScreenshotToClipboard() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
        Optional<Path> newest = findNewestPng(dir);
        if (newest.isEmpty()) {
            LOGGER.warn("Screenshot copy: no .png found in {}", dir);
            return;
        }

        try {
            BufferedImage image = ImageIO.read(newest.get().toFile());
            if (image == null) {
                LOGGER.warn("Screenshot copy: ImageIO couldn't decode {}", newest.get());
                return;
            }
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new ImageTransferable(image), null);
            ModOverlayMessage.show("§b[Killer560's Mod] Screenshot copied to clipboard!", 3000);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Screenshot copy failed for {}", newest.get(), e);
        }
    }

    private static Optional<Path> findNewestPng(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .max(Comparator.comparingLong(ScreenshotCopyFeature::lastModified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static final class ImageTransferable implements Transferable {
        private final BufferedImage image;

        private ImageTransferable(BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
