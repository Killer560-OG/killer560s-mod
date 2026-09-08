package com.killer560.hub.gifplayer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manually composites GIF frames per the GIF89a disposal-method spec. {@link ImageIO}'s GIF reader
 * hands back each frame as its own (often partial, delta-only) sub-image at its own offset - it does
 * NOT composite them into full frames itself, so a naive {@code reader.read(i)} loop renders garbage
 * for any GIF that relies on incremental frames (i.e. most real-world GIFs).
 */
public final class GifDecoder {

    public record Frame(int[] argb, int delayMs) {
    }

    public record GifImage(int width, int height, List<Frame> frames) {
    }

    public static GifImage decode(Path file) throws IOException {
        ImageReader reader = ImageIO.getImageReadersBySuffix("gif").next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(stream, false);

            int screenWidth;
            int screenHeight;
            try {
                IIOMetadataNode screenDescriptor =
                        findNode((IIOMetadataNode) reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"),
                                "LogicalScreenDescriptor");
                screenWidth = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenWidth"));
                screenHeight = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenHeight"));
            } catch (Exception e) {
                screenWidth = reader.getWidth(0);
                screenHeight = reader.getHeight(0);
            }

            BufferedImage canvas = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();

            List<Frame> frames = new ArrayList<>();
            String pendingDisposal = "none";
            Rectangle pendingRect = null;
            BufferedImage restoreSnapshot = null;

            int frameCount = reader.getNumImages(true);
            for (int i = 0; i < frameCount; i++) {
                if (pendingRect != null) {
                    switch (pendingDisposal) {
                        case "restoreToBackgroundColor" -> {
                            g.setComposite(AlphaComposite.Clear);
                            g.fillRect(pendingRect.x, pendingRect.y, pendingRect.width, pendingRect.height);
                            g.setComposite(AlphaComposite.SrcOver);
                        }
                        case "restoreToPreviousState" -> {
                            if (restoreSnapshot != null) {
                                g.setComposite(AlphaComposite.Src);
                                g.drawImage(restoreSnapshot, 0, 0, null);
                                g.setComposite(AlphaComposite.SrcOver);
                            }
                        }
                        default -> {
                        }
                    }
                }

                BufferedImage frameImage = reader.read(i);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode gce = findNode(root, "GraphicControlExtension");
                IIOMetadataNode imageDescriptor = findNode(root, "ImageDescriptor");

                String disposalMethod = gce != null ? gce.getAttribute("disposalMethod") : "none";
                int delayCenti = gce != null && !gce.getAttribute("delayTime").isEmpty()
                        ? Integer.parseInt(gce.getAttribute("delayTime")) : 10;
                int ix = imageDescriptor != null ? Integer.parseInt(imageDescriptor.getAttribute("imageLeftPosition")) : 0;
                int iy = imageDescriptor != null ? Integer.parseInt(imageDescriptor.getAttribute("imageTopPosition")) : 0;

                if ("restoreToPreviousState".equals(disposalMethod)) {
                    restoreSnapshot = deepCopy(canvas);
                }

                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(frameImage, ix, iy, null);

                int delayMs = delayCenti * 10;
                if (delayMs <= 10) {
                    delayMs = 100;
                }
                frames.add(new Frame(canvas.getRGB(0, 0, screenWidth, screenHeight, null, 0, screenWidth), delayMs));

                pendingDisposal = disposalMethod;
                pendingRect = new Rectangle(ix, iy, frameImage.getWidth(), frameImage.getHeight());
            }

            g.dispose();
            if (frames.isEmpty()) {
                throw new IOException("GIF has no frames: " + file);
            }
            return new GifImage(screenWidth, screenHeight, frames);
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage deepCopy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static IIOMetadataNode findNode(IIOMetadataNode root, String name) {
        if (root == null) {
            return null;
        }
        for (int i = 0; i < root.getLength(); i++) {
            org.w3c.dom.Node child = root.item(i);
            if (child.getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) child;
            }
        }
        return null;
    }

    public static boolean isGif(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.US).endsWith(".gif");
    }

    private GifDecoder() {
    }
}
