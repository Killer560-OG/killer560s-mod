package com.killer560.hub.fonts;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every font the Font setting can pick: Minecraft's own, the bundled ones and whatever is in the import folder.
 * <p>
 * The bundled fonts are all under the SIL Open Font License 1.1, which allows bundling them with software; each one's
 * license sits beside it in {@code assets/killer560smod/fonts/} and they are listed in {@code LICENSES.txt} there.
 * Imported fonts are any {@code .ttf}/{@code .otf} dropped into {@code config/killer560/interface/fonts/killer560smod-fonts/}
 * (the Open Fonts Folder button), read again by Reload Fonts.
 */
public final class FontChoices {

    private static final Logger LOGGER = ModLog.get("killer560smod-fonts");

    /** Minecraft's own font: nothing is replaced. */
    public static final String MINECRAFT = "minecraft";
    public static final String BUNDLED_PREFIX = "bundled:";
    public static final String FILE_PREFIX = "file:";

    /** The import folder (a sibling of the settings file; ModPaths' {@code fonts} row). */
    private static final Path IMPORT_DIR = ModPaths.config("killer560smod-fonts");

    /**
     * A font that can be picked. {@code sizePx} is its glyph size in GUI pixels at Font Size 100% (chosen so its capital
     * letters stand about as tall as Minecraft's 7-pixel ones), {@code shiftY} moves it down onto Minecraft's baseline.
     */
    public record Choice(String id, String label, String resource, Path file, float sizePx, float shiftY) {
        public boolean isMinecraft() {
            return MINECRAFT.equals(id);
        }
    }

    private static final List<Choice> BUNDLED = List.of(
            // Clean modern sans serif.
            new Choice(BUNDLED_PREFIX + "lato", "Lato", "/assets/killer560smod/fonts/Lato-Regular.ttf", null, 10f, 0f),
            // Monospace: every character the same width, good for numbers that change.
            new Choice(BUNDLED_PREFIX + "space_mono", "Space Mono", "/assets/killer560smod/fonts/SpaceMono-Regular.ttf",
                    null, 9.5f, 0f),
            // An old terminal look, tall and narrow.
            new Choice(BUNDLED_PREFIX + "vt323", "VT323", "/assets/killer560smod/fonts/VT323-Regular.ttf", null, 11f, 0f),
            // Arcade pixel font, wide and blocky.
            new Choice(BUNDLED_PREFIX + "press_start", "Press Start 2P",
                    "/assets/killer560smod/fonts/PressStart2P-Regular.ttf", null, 7f, 0.5f));

    private static volatile List<Choice> imported = List.of();

    private FontChoices() {
    }

    public static Choice minecraft() {
        return new Choice(MINECRAFT, "Minecraft", null, null, 0f, 0f);
    }

    public static List<Choice> bundled() {
        return BUNDLED;
    }

    public static List<Choice> imported() {
        return imported;
    }

    /** Minecraft first, then the bundled fonts, then the imported ones (by name). */
    public static List<Choice> all() {
        List<Choice> out = new ArrayList<>();
        out.add(minecraft());
        out.addAll(BUNDLED);
        out.addAll(imported);
        return out;
    }

    /** The choice with {@code id}, or null when it is gone (an imported file deleted since it was picked). */
    public static Choice find(String id) {
        if (id == null) {
            return null;
        }
        for (Choice c : all()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** The label a saved id shows: its font's name, or "Minecraft" for anything unknown. */
    public static String label(String id) {
        Choice c = find(id);
        return c == null ? "Minecraft" : c.label();
    }

    /** The import folder, created if missing - what Open Fonts Folder opens. */
    public static Path importFolder() {
        try {
            Files.createDirectories(IMPORT_DIR);
        } catch (IOException e) {
            LOGGER.warn("[Fonts] could not create {}", IMPORT_DIR, e);
        }
        return IMPORT_DIR;
    }

    /** Re-reads the import folder. Returns how many fonts it holds. */
    public static synchronized int rescan() {
        List<Choice> out = new ArrayList<>();
        if (Files.isDirectory(IMPORT_DIR)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(IMPORT_DIR)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!Files.isRegularFile(p) || !(lower.endsWith(".ttf") || lower.endsWith(".otf"))) {
                        continue;
                    }
                    String label = name.substring(0, name.length() - 4);
                    out.add(new Choice(FILE_PREFIX + name, label, null, p, 10f, 0f));
                }
            } catch (IOException e) {
                LOGGER.warn("[Fonts] could not list {}", IMPORT_DIR, e);
            }
        }
        out.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        imported = Collections.unmodifiableList(out);
        return out.size();
    }
}
