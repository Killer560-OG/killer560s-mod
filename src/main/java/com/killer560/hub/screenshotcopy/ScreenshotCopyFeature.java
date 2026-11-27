package com.killer560.hub.screenshotcopy;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.util.HiddenProcess;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final Logger LOGGER = ModLog.get("killer560smod-screenshotcopy");

    private static final String COMMAND = "k560screenshot";

    private ScreenshotCopyFeature() {
    }

    /** Called from {@link com.killer560.hub.screenshotcopy.mixin.ScreenshotMixin} the moment vanilla
     *  itself confirms a screenshot finished writing - runs on vanilla's own IO worker thread, not the
     *  render thread, but everything done here (file IO, spawning a process) is safe off-thread. */
    public static void onVanillaScreenshotSaved() {
        if (!ScreenshotCopyConfig.getInstance().isEnabled()) return;
        // own thread: PowerShell takes a second or more, and this is one of vanilla's shared IO pool threads
        Thread worker = new Thread(ScreenshotCopyFeature::copyNewestScreenshotToClipboard, "killer560smod-screenshotcopy");
        worker.setDaemon(true);
        worker.start();
    }

    private static void copyNewestScreenshotToClipboard() {
        Path dir = ScreenshotEdit.folder();
        Optional<Path> newest = findNewestPng(dir);
        if (newest.isEmpty()) {
            LOGGER.warn("Screenshot copy: no .png found in {}", dir);
            return;
        }
        if (copyToClipboard(newest.get())) {
            notifySuccess(newest.get().getFileName().toString());
        }
    }

    /** Puts a PNG on the system clipboard as an image (blocking, a second or more: never on the render thread). The
     *  Screenshot Editor's Copy goes through here too. With {@code killer560smod.test.clipboardfile} set (docs/TESTING-HOOKS.md)
     *  the file is copied there instead and no clipboard is touched. */
    public static boolean copyToClipboard(Path png) {
        String fake = System.getProperty("killer560smod.test.clipboardfile");
        if (fake != null) {
            try {
                Files.copy(png, Path.of(fake), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        if (Util.getPlatform() != Util.OS.WINDOWS) {
            LOGGER.warn("Screenshot copy: only implemented for Windows right now (detected {})", Util.getPlatform());
            return false;
        }

        Path log = null;
        try {
            log = Files.createTempFile("killer560smod-screenshot-", ".log");
            int exit = HiddenProcess.run(List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                            + "$img = [System.Drawing.Image]::FromFile($env:KILLER560SMOD_SCREENSHOT_PATH); "
                            + "[System.Windows.Forms.Clipboard]::SetImage($img); $img.Dispose()"),
                    Map.of("KILLER560SMOD_SCREENSHOT_PATH", png.toAbsolutePath().toString()), log, 10_000L);
            if (exit == -2) {
                LOGGER.warn("Screenshot copy: PowerShell timed out");
                return false;
            }
            if (exit != 0) {
                LOGGER.warn("Screenshot copy: PowerShell exited {} - {}", exit, Files.readString(log, StandardCharsets.UTF_8));
                return false;
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Screenshot copy failed for {}", png, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Screenshot copy interrupted for {}", png, e);
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // a temp file the OS will clear anyway
                }
            }
        }
        return false;
    }

    private static void notifySuccess(String fileName) {
        // Per killer560's request (2026-09-09), the center-screen overlay message was removed - the
        // chat message below is the only confirmation now. Clicking it opens the Screenshot Editor.
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(editable(ModChat.line("Killer560's Mod", ModChat.text("Screenshot copied to clipboard!")), fileName));
            }
        });
    }

    /** The command a click on a screenshot line runs: the file name only, so nothing outside the screenshots folder opens. */
    static String editCommand(String fileName) {
        return "/" + COMMAND + " edit " + fileName;
    }

    /** {@code line} with a click that opens the editor on {@code fileName} and a hover that says so. */
    static MutableComponent editable(MutableComponent line, String fileName) {
        return line.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(editCommand(fileName)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to edit this screenshot"))));
    }

    /** Vanilla's "Saved screenshot as ..." line made clickable: the file name keeps vanilla's open-file click, the rest of
     *  the line and an added [Edit] open the editor. Unchanged when the feature is off or no file is found in it. */
    public static Component withEdit(Component saved) {
        if (!ScreenshotCopyConfig.getInstance().isEnabled()) {
            return saved;
        }
        String name = savedName(saved);
        if (name == null) {
            return saved;
        }
        MutableComponent edit = Component.literal(" [Edit]").withStyle(s -> s.withColor(ThemeAccent.onDark(ThemeAccent.light(0xFFAA00))));
        return editable(saved.copy().append(edit), name);
    }

    /** The file name in vanilla's saved-screenshot line: the part carrying the open-file click. */
    private static String savedName(Component c) {
        if (c.getStyle().getClickEvent() instanceof ClickEvent.OpenFile open) {
            return open.file().getName();
        }
        if (c.getContents() instanceof TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component inner && savedName(inner) != null) {
                    return savedName(inner);
                }
            }
        }
        return c.getSiblings().stream().map(ScreenshotCopyFeature::savedName).filter(n -> n != null).findFirst().orElse(null);
    }

    /** Client entrypoint: the {@code /k560screenshot edit <file name>} command the chat clicks run. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal(COMMAND)
                        .requires(src -> ScreenshotCopyConfig.getInstance().isEnabled())
                        .then(ClientCommands.literal("edit")
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            openEditor(StringArgumentType.getString(ctx, "file"));
                                            return 1;
                                        })))));
    }

    private static void openEditor(String name) {
        Path file = ScreenshotEdit.resolve(name);
        Minecraft mc = Minecraft.getInstance();
        if (file == null) {
            ModChat.send("Screenshot", ModChat.bad("No screenshot called " + name + " in the screenshots folder."));
            return;
        }
        // after the click that ran this has finished with the chat screen
        mc.execute(() -> McCompat.setScreen(mc, new ScreenshotEditorScreen(file)));
    }

    private static Optional<Path> findNewestPng(Path dir) {
        if (!Files.isDirectory(dir)) return Optional.empty();
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
