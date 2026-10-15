package com.killer560.hub.clicksound;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The menu click sound (killer560, 2026-10-15: "custom click sounds in the gui with an option for them to work in normal
 * minecraft settings as well ... preset options and ways to import your own").
 *
 * <p>Every button in the game, this mod's and Minecraft's, plays {@code minecraft:ui.button.click} through
 * {@code SoundManager.play}. The {@code SoundManagerClickMixin} hands each such instance to {@link #replace}, which swaps
 * it for the chosen sound: a bundled preset (a {@code killer560smod:click_*} event from {@code sounds.json}) or an
 * imported .ogg from {@link #importDir()}. The swap happens only for this mod's own screens unless "Vanilla Screens" is on.
 * Choosing Default changes nothing at all.
 *
 * <p>An imported file is played through {@link ImportedClickInstance}; {@code SoundBufferLibraryClickMixin} lets the
 * sound engine read it from the folder (it is not in any resource pack), via {@link ClickSoundBuffers}.
 *
 * <p>The presets are original synthesized sounds (tools/gen-click-sounds.py), CC0; see docs/SOUND-LICENSES.md.
 */
public final class ClickSounds {

    private static final Logger LOGGER = ModLog.get("killer560smod-clicksound");

    public static final String DEFAULT_ID = "default";
    public static final String IMPORT_PREFIX = "import:";
    /** The sound event every button click plays. */
    public static final String VANILLA_CLICK = "minecraft:ui.button.click";
    static final String NAMESPACE = "killer560smod";
    /** An imported file may be at most this big (a click is a fraction of a second; this stops a song being dropped in). */
    public static final long MAX_IMPORT_BYTES = 1024L * 1024L;
    private static final String SCREEN_PACKAGE = "com.killer560.hub.";

    /** A bundled click sound: its id, label and event. */
    public record Preset(String id, String label, SoundEvent event) {
    }

    private static final List<Preset> PRESETS = List.of(
            preset("soft", "Soft Tap"),
            preset("tick", "Tick"),
            preset("pop", "Pop"),
            preset("wood", "Wood Knock"),
            preset("chime", "Chime"),
            preset("bleep", "Bleep"));

    /** Imported files by the key their Sound location carries (an Identifier cannot hold spaces or capitals). */
    private static final Map<String, Path> IMPORT_KEYS = new ConcurrentHashMap<>();

    private static volatile String lastPlayedId = "";
    private static volatile int played;
    private static volatile int replaced;

    private ClickSounds() {
    }

    private static Preset preset(String id, String label) {
        return new Preset(id, label, SoundEvent.createVariableRangeEvent(
                Identifier.fromNamespaceAndPath(NAMESPACE, "click_" + id)));
    }

    public static List<Preset> presets() {
        return PRESETS;
    }

    /** The folder imported .ogg files live in (created on demand). */
    public static Path importDir() {
        Path dir = ModPaths.config("killer560smod-menu-clicksounds");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[ClickSound] could not create {}", dir, e);
        }
        return dir;
    }

    /** The .ogg file names in {@link #importDir()}, sorted. */
    public static List<String> importedFiles() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(importDir())) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            // an unreadable folder just lists nothing
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Every choosable id: Default, the presets, then the imported files. */
    public static List<String> choices() {
        List<String> ids = new ArrayList<>();
        ids.add(DEFAULT_ID);
        for (Preset p : PRESETS) {
            ids.add(p.id());
        }
        for (String f : importedFiles()) {
            ids.add(IMPORT_PREFIX + f);
        }
        return ids;
    }

    /** The label a choice shows as. */
    public static String label(String id) {
        if (id == null || DEFAULT_ID.equals(id)) {
            return "Default";
        }
        if (id.startsWith(IMPORT_PREFIX)) {
            String f = id.substring(IMPORT_PREFIX.length());
            return f.length() > 4 ? f.substring(0, f.length() - 4) : f;
        }
        for (Preset p : PRESETS) {
            if (p.id().equals(id)) {
                return p.label();
            }
        }
        return "Default";
    }

    /** The choice after (dir +1) or before (-1) {@code id} in {@link #choices()}, wrapping; a vanished import steps from the top. */
    public static String step(String id, int dir) {
        List<String> all = choices();
        int i = all.indexOf(id);
        if (i < 0) {
            i = 0;
        }
        return all.get(Math.floorMod(i + dir, all.size()));
    }

    /** The sound a click should make for {@code id}, or null (Default, or an imported file that is gone). */
    public static SoundInstance make(String id, float volume) {
        if (id == null || DEFAULT_ID.equals(id)) {
            return null;
        }
        if (id.startsWith(IMPORT_PREFIX)) {
            String name = id.substring(IMPORT_PREFIX.length());
            Path dir = importDir().normalize();
            Path file = dir.resolve(name).normalize();
            if (!dir.equals(file.getParent()) || !Files.isRegularFile(file)) {
                return null;
            }
            String key = "imported/s" + Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode());
            IMPORT_KEYS.put(key, file);
            return new ImportedClickInstance(key, volume);
        }
        for (Preset p : PRESETS) {
            if (p.id().equals(id)) {
                return SimpleSoundInstance.forUI(p.event(), 1.0f, volume);
            }
        }
        return null;
    }

    /** The file an imported sound's key names (read by the buffer mixin), or null. */
    static Path fileForKey(String key) {
        return IMPORT_KEYS.get(key);
    }

    /** Whether a click on the screen that is open now takes the chosen sound. */
    public static boolean appliesHere(ClickSoundConfig cfg) {
        Screen screen = com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance());
        boolean ours = screen == null || screen.getClass().getName().startsWith(SCREEN_PACKAGE);
        return ours || cfg.isVanillaScreens();
    }

    /**
     * Called for every instance handed to {@code SoundManager.play}: a vanilla button click comes back as the chosen
     * sound (when one applies), anything else comes back untouched.
     */
    public static SoundInstance replace(SoundInstance in) {
        if (in == null || !VANILLA_CLICK.equals(String.valueOf(in.getIdentifier()))) {
            return in;
        }
        SoundInstance out = in;
        try {
            ClickSoundConfig cfg = ClickSoundConfig.getInstance();
            if (!DEFAULT_ID.equals(cfg.getChoice()) && appliesHere(cfg)) {
                SoundInstance made = make(cfg.getChoice(), cfg.getVolume());
                if (made != null) {
                    out = made;
                    replaced++;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[ClickSound] could not pick the click sound, using the vanilla one", e);
        }
        lastPlayedId = String.valueOf(out.getIdentifier());
        played++;
        return out;
    }

    /** Plays {@code id} now (the tab's Preview button); Default plays vanilla's click. */
    public static void preview(String id, float volume) {
        SoundInstance s = make(id, volume);
        if (s == null) {
            s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, volume);
        }
        Minecraft.getInstance().getSoundManager().play(s);
    }

    /**
     * Copies {@code source} into the import folder after checking it is a short, readable Ogg Vorbis file.
     * @return the file name it was saved as
     * @throws IOException with a message fit to show him when it is refused
     */
    public static String importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No such file.");
        }
        String name = source.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            throw new IOException("Only .ogg files can be imported.");
        }
        if (Files.size(source) > MAX_IMPORT_BYTES) {
            throw new IOException("That file is over 1 MB; a click should be a fraction of a second.");
        }
        try (InputStream in = Files.newInputStream(source)) {
            ClickSoundBuffers.decode(in);
        } catch (IOException | RuntimeException e) {
            throw new IOException("That is not a readable Ogg Vorbis file.");
        }
        Path target = importDir().resolve(name);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        ClickSoundBuffers.forget(target);
        return name;
    }

    /** The id of the last button-click sound handed to the engine (testkit evidence), "" before any. */
    public static String lastPlayedId() {
        return lastPlayedId;
    }

    /** Button clicks seen by {@link #replace}. */
    public static int clicksSeen() {
        return played;
    }

    /** Button clicks {@link #replace} swapped for the chosen sound. */
    public static int clicksReplaced() {
        return replaced;
    }
}
