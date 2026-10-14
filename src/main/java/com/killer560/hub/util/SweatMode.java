package com.killer560.hub.util;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sweat Mode (killer560, 2026-10-14): "add a new setting called sweat mode and have it open google chrome with this
 * link if you click on it. Make the setting description click to enter sweat mode. Have it as a toggle that turns on
 * then opens the web page then will turn back off."
 *
 * <p>{@link #enter} turns it on, opens {@link #URL} in Google Chrome (the default browser when Chrome is not
 * installed), and {@link #ON_TICKS} client ticks later it is off again. Nothing is saved: it is never on at start.
 * Every launch goes through {@link ExternalOpen}, so a test run logs instead of opening a browser.
 */
public final class SweatMode {

    private static final Logger LOGGER = ModLog.get("killer560smod-sweatmode");

    public static final String URL = "https://www.youtube.com/watch?v=mDUOibsW36w";

    /** How long the toggle shows ON before it turns itself back off: one second. */
    public static final int ON_TICKS = 20;

    private static int ticksLeft;
    private static int entered;
    /** The menu button showing it, given its OFF label again when the time is up (weak: the menu may have closed). */
    private static java.lang.ref.WeakReference<net.minecraft.client.gui.components.AbstractWidget> shownOn;
    private static java.util.function.Supplier<net.minecraft.network.chat.Component> label;

    private SweatMode() {
    }

    public static boolean isOn() {
        return ticksLeft > 0;
    }

    /** How many times it has been entered since launch (for tests). */
    public static int timesEntered() {
        return entered;
    }

    /** {@link #enter} from a menu button whose label {@code text} reads the state - put back to OFF when it ends. */
    public static void enter(net.minecraft.client.gui.components.AbstractWidget button,
                             java.util.function.Supplier<net.minecraft.network.chat.Component> text) {
        enter();
        shownOn = new java.lang.ref.WeakReference<>(button);
        label = text;
        button.setMessage(text.get());
    }

    /** Turns it on and opens the page. */
    public static void enter() {
        ticksLeft = ON_TICKS;
        entered++;
        openInChrome();
    }

    /** Every client tick: counts the ON time down. */
    public static void tick() {
        if (ticksLeft > 0 && --ticksLeft == 0) {
            var button = shownOn == null ? null : shownOn.get();
            if (button != null && label != null) {
                button.setMessage(label.get());
            }
            shownOn = null;
        }
    }

    private static void openInChrome() {
        Path chrome = findChrome();
        if (chrome == null) {
            LOGGER.info("[SweatMode] Chrome not found - opening in the default browser");
            ExternalOpen.uri(URL);
            return;
        }
        List<String> cmd = List.of(chrome.toString(), URL);
        if (ExternalOpen.suppress(cmd)) {
            return;
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            LOGGER.warn("[SweatMode] Could not start Chrome ({}) - opening in the default browser", e.toString());
            ExternalOpen.uri(URL);
        }
    }

    /** chrome.exe in its usual Windows places, or null (another OS, or not installed). */
    private static Path findChrome() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return null;
        }
        List<Path> where = new ArrayList<>();
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String base = System.getenv(env);
            if (base != null) {
                where.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
            }
        }
        for (Path p : where) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
