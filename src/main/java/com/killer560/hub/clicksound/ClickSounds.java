package com.killer560.hub.clicksound;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.inventory.Slot;
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
 * imported .ogg from {@link #importDir()}. The swap happens on every screen. Choosing Default changes nothing at all
 * (except that a volume above 100% still layers the vanilla click).
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
    /** {@link #VANILLA_CLICK} as an id: every sound the game plays is checked against it, so the check compares ids
     *  rather than building each sound's id string (mixin FPS pass, 2026-10-15). */
    private static final Identifier VANILLA_CLICK_ID = Identifier.withDefaultNamespace("ui.button.click");
    static final String NAMESPACE = "killer560smod";
    /** An imported file may be at most this big (a click is a fraction of a second; this stops a song being dropped in). */
    public static final long MAX_IMPORT_BYTES = 1024L * 1024L;

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
            files.filter(p -> Files.isRegularFile(p) && (extension(p.getFileName().toString()).equals("ogg")
                            || NATIVE_EXTENSIONS.contains(extension(p.getFileName().toString()))))
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
            int dot = f.lastIndexOf('.');
            return dot > 0 ? f.substring(0, dot) : f;
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

    /** True while the extra layers of a loud click are being played, so they are not layered again. */
    private static boolean layering;
    private static volatile int layersPlayed;
    private static volatile int slotClicks;

    /**
     * Called for every instance handed to {@code SoundManager.play}: a vanilla button click comes back as the chosen
     * sound, anything else comes back untouched. It applies on every screen (killer560, 2026-10-18: "the click sounds if
     * on should work for all menus regardless"). Above 100% the click is played in layers - the sound engine clamps one
     * instance's gain to 1.0, so 300% is three copies playing together (each source adds to the mix).
     */
    public static SoundInstance replace(SoundInstance in) {
        if (in == null || layering || !VANILLA_CLICK_ID.equals(in.getIdentifier())) {
            return in;
        }
        SoundInstance out = in;
        try {
            ClickSoundConfig cfg = ClickSoundConfig.getInstance();
            float gain = cfg.getVolume();
            boolean isDefault = DEFAULT_ID.equals(cfg.getChoice());
            if (!isDefault) {
                SoundInstance made = make(cfg.getChoice(), Math.min(gain, 1.0f));
                if (made != null) {
                    out = made;
                    replaced++;
                }
            }
            if (gain > 1.0f && (isDefault || out != in)) {
                playLayers(cfg.getChoice(), gain);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[ClickSound] could not pick the click sound, using the vanilla one", e);
        }
        lastPlayedId = String.valueOf(out.getIdentifier());
        played++;
        return out;
    }

    /** The copies of a click that make up {@code gain} beyond the first full-volume one: {1.0, ..., remainder}. */
    public static float[] extraLayers(float gain) {
        if (!(gain > 1.0f)) {
            return new float[0];
        }
        int total = (int) Math.ceil(gain - 1.0e-4f);
        float[] out = new float[Math.max(0, total - 1)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.min(1.0f, gain - (i + 1));
        }
        return out;
    }

    private static void playLayers(String choice, float gain) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        layering = true;
        try {
            for (float v : extraLayers(gain)) {
                SoundInstance layer = make(choice, v);
                if (layer == null) {
                    layer = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, v);
                }
                layersPlayed++;
                sounds.play(layer);
            }
        } finally {
            layering = false;
        }
    }

    /** Extra layers started for loud clicks (testkit evidence). */
    public static int layersPlayed() {
        return layersPlayed;
    }

    /** Plays the chosen click for a click on a menu slot in a chest-style screen (Hypixel's menus are chests; vanilla makes
     *  no sound there). Does nothing on Default. */
    public static void playSlotClick() {
        ClickSoundConfig cfg = ClickSoundConfig.getInstance();
        if (DEFAULT_ID.equals(cfg.getChoice())) {
            return;
        }
        SoundInstance made = make(cfg.getChoice(), Math.min(cfg.getVolume(), 1.0f));
        if (made == null) {
            return;
        }
        slotClicks++;
        Minecraft.getInstance().getSoundManager().play(made);
        if (cfg.getVolume() > 1.0f) {
            playLayers(cfg.getChoice(), cfg.getVolume());
        }
    }

    /** Slot clicks in chest-style screens that played the click (testkit evidence). */
    public static int slotClicks() {
        return slotClicks;
    }

    /** Hooks every chest-style container screen (a Skyblock menu): a click on a slot holding an item plays the click. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                try {
                    Slot slot = ((AbstractContainerScreenAccessor) container).killer560smod$getHoveredSlot();
                    if (slot != null && slot.hasItem()) {
                        playSlotClick();
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("[ClickSound] slot click hook failed", e);
                }
                return true;
            });
        });
    }

    /** Plays {@code id} now (the tab's Preview button); Default plays vanilla's click. */
    public static void preview(String id, float volume) {
        float first = Math.min(volume, 1.0f);
        SoundInstance s = make(id, first);
        if (s == null) {
            s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, first);
        }
        layering = true;   // the preview is not a button click: only its own layers, not the replace hook's
        try {
            Minecraft.getInstance().getSoundManager().play(s);
        } finally {
            layering = false;
        }
        if (volume > 1.0f) {
            playLayers(id, volume);
        }
    }

    /**
     * Copies {@code source} into the import folder after checking it is a short, readable sound. An .ogg is Ogg Vorbis; a
     * .wav, .mp3, .aiff or .au is read by the JDK and the bundled MP3 decoder. Anything else, or a file those cannot read -
     * a video such as mp4 or mkv, where only the audio is used - is turned into a short .wav by ffmpeg when ffmpeg is
     * installed (killer560, 2026-10-18: "any sound file type can be used even a video but it just uses the audio").
     * @return the file name it was saved as
     * @throws IOException with a message fit to show him when it is refused
     */
    public static String importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No such file.");
        }
        String name = source.getFileName().toString();
        boolean ogg = name.toLowerCase(Locale.ROOT).endsWith(".ogg");
        boolean small = Files.size(source) <= MAX_IMPORT_BYTES;
        if (ogg) {
            if (!small) {
                throw new IOException("That file is over 1 MB; a click should be a fraction of a second.");
            }
            checkReadable(source);
            return keep(source, name);
        }
        if (small && NATIVE_EXTENSIONS.contains(extension(name)) && readable(source)) {
            return keep(source, name);
        }
        return convertWithFfmpeg(source, name);
    }

    /** Sound types the JDK and the bundled MP3 decoder read without ffmpeg (besides .ogg, which JOrbis reads). */
    static final java.util.Set<String> NATIVE_EXTENSIONS = java.util.Set.of("wav", "mp3", "aiff", "aif", "au");
    /** The longest sound an import may hold, in seconds of audio. */
    static final int MAX_SECONDS = 10;

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String keep(Path source, String name) throws IOException {
        Path target = importDir().resolve(name);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        ClickSoundBuffers.forget(target);
        return name;
    }

    private static boolean readable(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            ClickSoundBuffers.decodeAudio(in, true);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void checkReadable(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            ClickSoundBuffers.decode(in);
        } catch (IOException | RuntimeException e) {
            throw new IOException("That is not a readable Ogg Vorbis file.");
        }
    }

    /** The longest part of a long file the converter keeps, in seconds. */
    static final int CONVERT_SECONDS = 5;

    private static String convertWithFfmpeg(Path source, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9 _-]", "_");
        if (base.isBlank()) {
            base = "sound";
        }
        Path target = importDir().resolve(base + ".wav");
        Process p;
        try {
            p = new ProcessBuilder("ffmpeg", "-y", "-v", "quiet", "-i", source.toAbsolutePath().toString(), "-vn",
                    "-t", String.valueOf(CONVERT_SECONDS), "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le",
                    target.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new IOException("Could not read that file here. .ogg, .wav and .mp3 import as they are; other types need ffmpeg installed.");
        }
        try {
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took too long on that file.");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        if (p.exitValue() != 0 || !Files.isRegularFile(target) || Files.size(target) > MAX_IMPORT_BYTES) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not read any audio in that file.");
        }
        ClickSoundBuffers.forget(target);
        return target.getFileName().toString();
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
