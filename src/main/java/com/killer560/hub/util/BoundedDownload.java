package com.killer560.hub.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies a stream to disk with a hard byte cap, for the handful of features that download a zip from a
 * third-party host (the room database, the speech model). Added 2026-09-16 in the security pass, to give
 * these the same treatment {@code GraphRepository} already had: the caps are far above any legitimate
 * response, so a normal download never notices them, but a broken mirror, a hostile redirect or a zip
 * bomb can't quietly fill the user's disk while the game keeps running.
 * <p>
 * The extraction cap matters as much as the download cap: a 1 MB zip can expand to gigabytes, so
 * {@link #copyCapped} is used on each entry as well as on the download itself.
 */
public final class BoundedDownload {

    private BoundedDownload() {
    }

    /** Streams {@code in} to {@code target}, failing (and deleting the partial file) past {@code maxBytes}. */
    public static void toFile(InputStream in, Path target, long maxBytes, String what) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            copyCapped(in, out, maxBytes, what);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    /**
     * Copies at most {@code maxBytes} from {@code in} to {@code out}.
     *
     * @return the number of bytes copied
     * @throws IOException if the source has more than {@code maxBytes} left to give
     */
    public static long copyCapped(InputStream in, OutputStream out, long maxBytes, String what) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > maxBytes) {
                throw new IOException(what + " exceeded " + maxBytes + " bytes - refusing to keep writing");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }
}
