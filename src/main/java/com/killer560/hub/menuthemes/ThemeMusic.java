package com.killer560.hub.menuthemes;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.mainmenu.MainMenuLook;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Theme Music (killer560, 2026-10-21: "Can you add an option for menu themes to have their own background music as well
 * for all themes"). While the main menu is open, the main menu's theme ({@link MainMenuLook#current()}, so Match, Separate
 * and Custom styles all hear the theme they show) plays its track on a loop; leaving the main menu for a world or a server
 * stops it at once. The menus opened from the title screen (Options, Singleplayer, Multiplayer, this mod's menu) keep it
 * going, so stepping into Options does not restart the song; a connecting or loading screen ends it.
 * <p>While a theme track plays, vanilla's own menu music is held off ({@code MusicManagerThemeMixin}); with Theme Music
 * off, or the theme's track set to Off, the game's music plays exactly as before. Default OFF.
 * <p>Bundled tracks: one original loop per preset theme, made for this mod by its owner (see
 * {@code assets/killer560smod/sounds/music/readme.txt}; lower case, as a resource path must be). Custom has no track of its own; it can pick any of them.
 * <p>Ticked at END_CLIENT_TICK; nothing here waits: a change of theme or track is heard on the next tick.
 */
public final class ThemeMusic {

    private static final Logger LOGGER = ModLog.get("killer560smod-thememusic");

    public static final String BUNDLED_PREFIX = "bundled:";

    /** A bundled loop: its id, label and sound event. */
    public record Bundled(String id, String label, Identifier event) {
    }

    private static final List<Bundled> BUNDLED = List.of(
            bundled("amber", "Amber Loop"),
            bundled("dark", "Dark Loop"),
            bundled("light", "Light Loop"),
            bundled("ocean", "Ocean Loop"),
            bundled("green", "Viridian Loop"));

    /** What is playing: the resolved choice and its instance. */
    private static String currentKey;
    private static ThemeMusicInstance current;
    /** The choice that failed to start, so a broken track is not retried every tick; cleared when the choice changes. */
    private static String failedKey;
    /** A choice that failed twice in a row: left alone until he picks another (or it stops being wanted). */
    private static String gaveUp;
    /** The title screen has been shown since the last world: the out-of-world menus after it are "the main menu". */
    private static boolean titleSeen;
    private static boolean registered;

    // testkit evidence (the test client is muted, so the engine's answer is what proves the track was handed over)
    private static volatile int starts;
    private static volatile int stops;
    private static volatile String lastStarted = "";
    private static volatile String lastResult = "";
    private static volatile String lastError;

    private ThemeMusic() {
    }

    private static Bundled bundled(String id, String label) {
        return new Bundled(id, label, Identifier.fromNamespaceAndPath("killer560smod", "theme_music_" + id));
    }

    public static List<Bundled> bundled() {
        return BUNDLED;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                lastError = String.valueOf(e);
                LOGGER.warn("[ThemeMusic] tick failed", e);
            }
        });
    }

    /** The theme's own loop, or null (Custom, or a theme added later). */
    public static Bundled themeTrack(MenuTheme theme) {
        if (theme == null) {
            return null;
        }
        String id = switch (theme) {
            case AMBER -> "amber";
            case DARK -> "dark";
            case LIGHT -> "light";
            case OCEAN -> "ocean";
            case GREEN -> "green";
            default -> null;
        };
        return id == null ? null : find(id);
    }

    static Bundled find(String id) {
        for (Bundled b : BUNDLED) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /** Every choice for {@code theme}: its own track, Off, each bundled loop, then the imported files. */
    public static List<String> choices() {
        List<String> out = new ArrayList<>();
        out.add(ThemeExtrasConfig.MUSIC_THEME);
        out.add(ThemeExtrasConfig.MUSIC_NONE);
        for (Bundled b : BUNDLED) {
            out.add(BUNDLED_PREFIX + b.id());
        }
        return out;
    }

    /** The choice after (+1) or before (-1) {@code id}, wrapping; an unknown one steps from the top. */
    public static String step(String id, int dir) {
        List<String> all = choices();
        int i = Math.max(0, all.indexOf(id));
        return all.get(Math.floorMod(i + dir, all.size()));
    }

    /** What a choice shows as for {@code theme}. */
    public static String label(MenuTheme theme, String id) {
        if (id == null || ThemeExtrasConfig.MUSIC_THEME.equals(id)) {
            Bundled own = themeTrack(theme);
            return "Theme Track (" + (own == null ? "none" : own.label()) + ")";
        }
        if (ThemeExtrasConfig.MUSIC_NONE.equals(id)) {
            return "Off";
        }
        if (id.startsWith(BUNDLED_PREFIX)) {
            Bundled b = find(id.substring(BUNDLED_PREFIX.length()));
            return b == null ? "Off" : b.label();
        }
        return id;
    }

    /** The event a choice plays for {@code theme}, or null for nothing. */
    static Identifier resolve(MenuTheme theme, String id) {
        if (id == null || ThemeExtrasConfig.MUSIC_THEME.equals(id)) {
            Bundled own = themeTrack(theme);
            return own == null ? null : own.event();
        }
        if (id.startsWith(BUNDLED_PREFIX)) {
            Bundled b = find(id.substring(BUNDLED_PREFIX.length()));
            return b == null ? null : b.event();
        }
        return null;
    }

    /** The track's gain now: Theme Music Volume, 0..1. */
    static float gain() {
        return ThemeExtrasConfig.getInstance().getMusicVolume() / 100f;
    }

    /** The main menu is what is on screen: no world, the title screen seen since the last one, and not a connecting or
     *  loading screen. */
    static boolean onMainMenu(Minecraft mc) {
        if (mc.level != null) {
            titleSeen = false;
            return false;
        }
        Screen s = McCompat.screen(mc);
        if (s instanceof TitleScreen) {
            titleSeen = true;
            return true;
        }
        if (s == null || s instanceof ConnectScreen || s instanceof LevelLoadingScreen || s instanceof ProgressScreen
                || s instanceof GenericMessageScreen || s instanceof GenericWaitingScreen) {
            titleSeen = false;
            return false;
        }
        return titleSeen;
    }

    static void tick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        ThemeExtrasConfig cfg = ThemeExtrasConfig.getInstance();
        Identifier want = null;
        String key = null;
        if (cfg.isMusicOn() && onMainMenu(mc)) {
            MenuTheme theme = MainMenuLook.current().theme();
            String choice = cfg.getMusic(theme);
            want = resolve(theme, choice);
            key = want == null ? null : want.toString();
        } else {
            onMainMenu(mc); // keeps titleSeen right while the switch is off
        }
        SoundManager sounds = mc.getSoundManager();
        if (key == null || !key.equals(gaveUp)) {
            gaveUp = null;
        }
        if (key != null && key.equals(currentKey) && current != null) {
            if (sounds.isActive(current)) {
                return;
            }
            // The engine dropped it (a sound reload, a stream error): start it again once; a second failure in a row
            // gives up on that track until the choice changes, so a broken file is not retried every tick.
            stopCurrent(sounds);
            if (key.equals(failedKey)) {
                gaveUp = key;
                lastError = "the sound engine would not keep " + key + " playing";
                LOGGER.warn("[ThemeMusic] {} stopped by itself twice; giving up on it", key);
                return;
            }
            failedKey = key;
            LOGGER.warn("[ThemeMusic] {} stopped by itself; starting it again", key);
        }
        if (key != null && key.equals(gaveUp)) {
            return;
        }
        if (key == null ? current == null : key.equals(currentKey)) {
            return;
        }
        stopCurrent(sounds);
        if (want == null) {
            failedKey = null;
            return;
        }
        if (!key.equals(failedKey)) {
            failedKey = null;
        }
        ThemeMusicInstance next = new ThemeMusicInstance(want);
        // The vanilla menu music stops the moment a theme track takes over; the mixin keeps it from starting again.
        mc.getMusicManager().stopPlaying();
        Object result = sounds.play(next);
        lastResult = String.valueOf(result);
        lastStarted = key;
        current = next;
        currentKey = key;
        starts++;
        LOGGER.info("[ThemeMusic] playing {} ({})", key, result);
    }

    private static void stopCurrent(SoundManager sounds) {
        ThemeMusicInstance old = current;
        current = null;
        currentKey = null;
        if (old != null) {
            old.finish();
            sounds.stop(old);
            stops++;
        }
    }

    /** True while a theme track is playing: vanilla's MusicManager stays quiet. */
    public static boolean playing() {
        return current != null;
    }

    // ---------------------------------------------------------------- testkit evidence

    /** The event (or imported key) playing now, or null. */
    public static String currentKey() {
        return currentKey;
    }

    /** Whether the sound engine holds the current track as a live channel. */
    public static boolean engineActive() {
        ThemeMusicInstance c = current;
        return c != null && Minecraft.getInstance().getSoundManager().isActive(c);
    }

    public static int starts() {
        return starts;
    }

    public static int stops() {
        return stops;
    }

    public static String lastStarted() {
        return lastStarted;
    }

    /** The engine's answer to the last play (STARTED, STARTED_SILENTLY or NOT_STARTED). */
    public static String lastResult() {
        return lastResult;
    }

    public static String lastError() {
        return lastError;
    }

    /** The volume the current track was last given (0..1), or -1. */
    public static float currentVolume() {
        ThemeMusicInstance c = current;
        return c == null ? -1f : c.rawVolume();
    }
}
