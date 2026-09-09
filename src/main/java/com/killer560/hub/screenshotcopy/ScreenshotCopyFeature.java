package com.killer560.hub.screenshotcopy;

import com.killer560.hub.notify.ModOverlayMessage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** When enabled, watches the real vanilla screenshot keybind ({@code Options.keyScreenshot}) and
 *  copies whatever screenshot it just took to the system clipboard as an image, in addition to the
 *  normal save-to-disk vanilla already does. Deliberately does NOT hook vanilla's own
 *  {@code Screenshot.grab} internals (its actual file-write happens off a private lambda with no
 *  stable injection point) - instead peeks the keybind's own {@code isDown()} state every tick (a
 *  safe, non-consuming read confirmed via javap - it doesn't steal or interfere with the real
 *  keypress vanilla also sees), waits for the write to finish, then picks up whatever new file
 *  landed in the screenshots directory. Ships disabled by default - see {@link ScreenshotCopyConfig}. */
public final class ScreenshotCopyFeature {

    // Vanilla's own screenshot write is fire-and-forget from the render thread; this is enough
    // margin for the PNG encode+write to land before we go looking for it.
    private static final long COPY_DELAY_MS = 300;

    private static boolean keyWasDown = false;
    private static long pendingCopyAtMs = -1;

    private ScreenshotCopyFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ScreenshotCopyFeature::tick);
    }

    private static void tick(Minecraft client) {
        if (!ScreenshotCopyConfig.getInstance().isEnabled()) {
            keyWasDown = false;
            pendingCopyAtMs = -1;
            return;
        }

        boolean down = client.options.keyScreenshot.isDown();
        if (down && !keyWasDown) {
            pendingCopyAtMs = System.currentTimeMillis() + COPY_DELAY_MS;
        }
        keyWasDown = down;

        if (pendingCopyAtMs > 0 && System.currentTimeMillis() >= pendingCopyAtMs) {
            pendingCopyAtMs = -1;
            copyNewestScreenshotToClipboard();
        }
    }

    private static void copyNewestScreenshotToClipboard() {
        Path dir = client().gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
        Optional<Path> newest = findNewestPng(dir);
        if (newest.isEmpty()) {
            return;
        }

        try {
            BufferedImage image = ImageIO.read(newest.get().toFile());
            if (image == null) {
                return;
            }
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new ImageTransferable(image), null);
            ModOverlayMessage.show("§b[Killer560's Mod] Screenshot copied to clipboard!", 3000);
        } catch (IOException | RuntimeException ignored) {
            // Clipboard ownership can be lost to another app mid-write on some platforms - not
            // worth surfacing to killer560 as an error, the file is still safely saved to disk.
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

    private static Minecraft client() {
        return Minecraft.getInstance();
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
