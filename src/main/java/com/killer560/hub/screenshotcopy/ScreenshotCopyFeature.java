package com.killer560.hub.screenshotcopy;

import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** When enabled, copies whatever screenshot vanilla's own screenshot key just took to the system
 *  clipboard as an image, in addition to the normal save-to-disk vanilla already does. Triggered via
 *  {@link com.killer560.hub.screenshotcopy.mixin.ScreenshotMixin}, which wraps the real completion
 *  callback {@code Screenshot.grab} hands back once the PNG is actually written.
 *  <p>
 *  Real bug found and fixed (2026-09-08), after killer560 reported this never worked at all: doesn't
 *  use {@code java.awt.Toolkit}/{@code Clipboard} - confirmed via javap that
 *  {@code net.minecraft.client.main.Main}'s own static initializer unconditionally calls
 *  {@code System.setProperty("java.awt.headless", "true")}, so any AWT clipboard access inside a
 *  Minecraft client process throws {@code HeadlessException} every single time, no exceptions. The
 *  real error only surfaced once proper logging replaced the original silent catch-and-ignore.
 *  Windows-only for now (shells out to PowerShell's {@code System.Windows.Forms.Clipboard}, the
 *  standard workaround for image-clipboard access from a headless-forced JVM) - killer560's own setup
 *  is Windows, and {@link ScreenshotCopyConfig} ships disabled by default regardless. */
public final class ScreenshotCopyFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-screenshotcopy");

    private ScreenshotCopyFeature() {
    }

    /** Called from {@link com.killer560.hub.screenshotcopy.mixin.ScreenshotMixin} the moment vanilla
     *  itself confirms a screenshot finished writing - runs on vanilla's own IO worker thread, not the
     *  render thread, but everything done here (file IO, spawning a process) is safe off-thread. */
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

        if (Util.getPlatform() != Util.OS.WINDOWS) {
            LOGGER.warn("Screenshot copy: only implemented for Windows right now (detected {})", Util.getPlatform());
            return;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                            + "$img = [System.Drawing.Image]::FromFile($env:KILLER560SMOD_SCREENSHOT_PATH); "
                            + "[System.Windows.Forms.Clipboard]::SetImage($img); $img.Dispose()");
            builder.environment().put("KILLER560SMOD_SCREENSHOT_PATH", newest.get().toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Screenshot copy: PowerShell timed out");
                return;
            }
            if (process.exitValue() != 0) {
                LOGGER.warn("Screenshot copy: PowerShell exited {} - {}", process.exitValue(), output);
                return;
            }
            notifySuccess();
        } catch (IOException e) {
            LOGGER.warn("Screenshot copy failed for {}", newest.get(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Screenshot copy interrupted for {}", newest.get(), e);
        }
    }

    private static void notifySuccess() {
        ModOverlayMessage.show("§b[Killer560's Mod] Screenshot copied to clipboard!", 3000);
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal("§6[Killer560's Mod] Screenshot copied to clipboard!"));
            }
        });
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
}
