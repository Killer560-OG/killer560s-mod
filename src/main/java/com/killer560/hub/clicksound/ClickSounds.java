package com.killer560.hub.clicksound;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The menu click sound (killer560, 2026-10-15: "custom click sounds in the gui with an option for them to work in normal
 * minecraft settings as well ... preset options and ways to import your own").
 *
 * <p>Every button in the game, this mod's and Minecraft's, plays {@code minecraft:ui.button.click} through
 * {@code SoundManager.play}. The {@code SoundManagerClickMixin} hands each such instance to {@link #replace}, which swaps
 * it for the chosen sound: a bundled preset (a {@code killer560smod:click_*} event from {@code sounds.json}) or an
 * imported .ogg from {@link #importDir()}. The swap happens on every screen. Choosing Default changes nothing at all
 * (except that a volume above 100% still layers the vanilla click). Every other game sound goes to
 * {@link SoundEffects#replace}, which does the same for the Sound Effects the player has turned on.
 *
 * <p>An imported file is played through {@link ImportedSoundInstance}; {@code SoundBufferLibraryClickMixin} lets the
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
    /** The longest sound an import may keep as it is, in seconds. Longer ones (and video) are cut to {@link #CONVERT_SECONDS}. */
    static final int MAX_SECONDS = 10;
    /** The longest part of a file ffmpeg keeps, in seconds: a click or a hit, not a song. */
    static final int CONVERT_SECONDS = 3;

    /** Sound types the JDK and the bundled MP3 decoder read without ffmpeg (besides .ogg, which JOrbis reads). */
    static final Set<String> NATIVE_EXTENSIONS = Set.of("wav", "mp3", "aiff", "aif", "au");

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

    /** The folder imported sound files live in (created on demand). */
    public static Path importDir() {
        Path dir = ModPaths.config("killer560smod-menu-clicksounds");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[ClickSound] could not create {}", dir, e);
        }
        return dir;
    }

    /** The sound file names in {@link #importDir()} (.ogg and the native list), sorted. */
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

    /** The UI sound a click should make for {@code id} at {@code volume}, or null (Default, or an import that is gone). */
    public static SoundInstance make(String id, float volume) {
        return make(id, volume, null);
    }

    /**
     * The sound {@code id} makes at {@code volume}, or null (Default, or an import that is gone). {@code at} is the game
     * sound it replaces when it is a sound effect: the new one is then heard from the same place, at the same pitch and
     * source, and fades with distance. Null {@code at} is a UI click (master volume, no position).
     */
    public static SoundInstance make(String id, float volume, SoundInstance at) {
        if (id == null || DEFAULT_ID.equals(id)) {
            return null;
        }
        boolean positional = at != null && at.getAttenuation() != SoundInstance.Attenuation.NONE && !at.isRelative();
        SoundSource source = at == null ? SoundSource.MASTER : at.getSource();
        float pitch = at == null ? 1.0f : at.getPitch();
        if (id.startsWith(IMPORT_PREFIX)) {
            String name = id.substring(IMPORT_PREFIX.length());
            Path dir = importDir().normalize();
            Path file = dir.resolve(name).normalize();
            if (!dir.equals(file.getParent()) || !Files.isRegularFile(file)) {
                return null;
            }
            String key = "imported/s" + Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode());
            IMPORT_KEYS.put(key, file);
            return positional
                    ? new ImportedSoundInstance(key, source, volume, pitch, at.getX(), at.getY(), at.getZ(), true)
                    : new ImportedSoundInstance(key, source, volume, 1.0f, 0, 0, 0, false);
        }
        for (Preset p : PRESETS) {
            if (p.id().equals(id)) {
                return positional
                        ? new SimpleSoundInstance(p.event(), source, volume, pitch, SoundInstance.createUnseededRandom(),
                                at.getX(), at.getY(), at.getZ())
                        : SimpleSoundInstance.forUI(p.event(), 1.0f, volume);
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
     * sound, a sound effect is handed on to {@link SoundEffects}, anything else comes back untouched. It applies on every
     * screen (killer560, 2026-10-18: "the click sounds if on should work for all menus regardless"). Above 100% the click
     * is played in layers - the sound engine clamps one instance's gain to 1.0, so 300% is three copies playing together
     * (each source adds to the mix).
     */
    public static SoundInstance replace(SoundInstance in) {
        if (in == null || layering) {
            return in;
        }
        if (!VANILLA_CLICK_ID.equals(in.getIdentifier())) {
            return SoundEffects.replace(in);
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

    /** Plays the chosen click for a left click on an item slot in any container screen (Hypixel's menus are chests; vanilla
     *  makes no sound there). Does nothing on Default. */
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

    /** Slot clicks in container screens that played the click (testkit evidence). */
    public static int slotClicks() {
        return slotClicks;
    }

    /** Hooks every container screen - a Skyblock chest, the mod's own inventory-style screens and the vanilla ones (killer560,
     *  2026-10-18: the chest hook used to match only the vanilla ContainerScreen class): a left click on a slot holding an
     *  item plays the click. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (event.button() != 0) {
                    return true;
                }
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
     * Imports the file whose path is on the clipboard (the quotes Explorer's "Copy as path" adds are stripped). Returns the
     * name it was saved as.
     * @throws IOException with the message to show him when it is refused
     */
    public static String importClipboard(String clip) throws IOException {
        String text = clip == null ? "" : clip.strip();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isEmpty()) {
            throw new IOException("Copy a sound or video file's path first (Shift + right click it, Copy as path).");
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (java.nio.file.InvalidPathException e) {
            throw new IOException("That is not a file path.");
        }
        return importFile(path);
    }

    /**
     * Copies {@code source} into the import folder after checking it is a short, readable sound. An .ogg, .wav, .mp3, .aiff
     * or .au that is at most {@link #MAX_SECONDS} long and {@link #MAX_IMPORT_BYTES} big is kept as it is, with no ffmpeg
     * needed. Anything else - a video (only its audio is used), another audio type, or a longer or bigger sound - is cut to
     * its first {@link #CONVERT_SECONDS} and saved as .ogg by ffmpeg, which must be installed (killer560, 2026-10-18: "any
     * sound file type can be used even a video but it just uses the audio").
     * @return the file name it was saved as
     * @throws IOException with a message fit to show him when it is refused; a file with no sound says so
     */
    public static String importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No such file.");
        }
        String name = source.getFileName().toString();
        String ext = extension(name);
        boolean small = Files.size(source) <= MAX_IMPORT_BYTES;
        if (small && (ext.equals("ogg") || NATIVE_EXTENSIONS.contains(ext)) && shortEnough(source, ext)) {
            return keep(source, name);
        }
        return convertWithFfmpeg(source, name);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** True when the file is a readable sound of at most {@link #MAX_SECONDS}, decoded the way the engine decodes it. */
    private static boolean shortEnough(Path file, String ext) {
        try (InputStream in = Files.newInputStream(file)) {
            if (ext.equals("ogg")) {
                return ClickSoundBuffers.oggSeconds(in) <= MAX_SECONDS;
            }
            ClickSoundBuffers.decodeAudio(in, true);   // refuses one longer than MAX_SECONDS
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String keep(Path source, String name) throws IOException {
        Path target = importDir().resolve(name);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        ClickSoundBuffers.forget(target);
        return name;
    }

    /** Converts any sound or video file to a short .ogg in the import folder, through ffmpeg's libvorbis. */
    private static String convertWithFfmpeg(Path source, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9 _-]", "_");
        if (base.isBlank()) {
            base = "sound";
        }
        Path work = Files.createTempFile("killer560smod-clicksound-", ".ogg");
        Path log = Files.createTempFile("killer560smod-clicksound-", ".log");
        try {
            Process p;
            try {
                p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-v", "error",
                        "-i", source.toAbsolutePath().toString(), "-vn", "-t", String.valueOf(CONVERT_SECONDS),
                        "-ac", "2", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "4",
                        work.toAbsolutePath().toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            } catch (IOException e) {
                throw new IOException(name + " needs ffmpeg to import (a video, another type, or a sound over "
                        + MAX_SECONDS + " seconds or 1 MB). ffmpeg was not found: install it, then restart Minecraft. "
                        + ".ogg, .wav, .mp3, .aiff and .au under " + MAX_SECONDS + " seconds import without it.");
            }
            try {
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("ffmpeg took too long on " + name + ".");
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted.");
            }
            String errors = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
            if (p.exitValue() != 0 || !Files.isRegularFile(work) || Files.size(work) == 0) {
                if (errors.contains("matches no streams") || errors.contains("does not contain any stream")) {
                    throw new IOException(name + " has no audio track, so there is no sound to import.");
                }
                throw new IOException(name + " is not a sound or video file that ffmpeg can read.");
            }
            if (Files.size(work) > MAX_IMPORT_BYTES || !shortEnough(work, "ogg")) {
                throw new IOException("ffmpeg could not make a short enough sound from " + name + ".");
            }
            Path target = importDir().resolve(base + ".ogg");
            Files.move(work, target, StandardCopyOption.REPLACE_EXISTING);
            ClickSoundBuffers.forget(target);
            return target.getFileName().toString();
        } finally {
            Files.deleteIfExists(work);
            Files.deleteIfExists(log);
        }
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
