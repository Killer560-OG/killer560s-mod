package com.killer560.hub.profiles;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Real config-file profile system - killer560's "custom mod profiles... clicking between them will
 * change which settings" request. A profile is a real, plain snapshot of every one of this mod's own
 * `killer560smod-*.json` setting files, copied into its own folder under
 * {@code config/killer560smod-profiles/<name>/}. Applying a profile overwrites the live JSON files on disk
 * and then re-runs every config class's static {@code load()} on the client thread (see
 * {@link #reloadAllConfigs()}). Before 2026-09-15 it only wrote the files: every {@code XyzConfig} keeps its
 * settings in a static instance loaded once per session, so the next {@code save()} of ANY setting wrote
 * the old in-memory values straight back over the just-applied profile.
 * <p>
 * Deliberately EXCLUDES real credentials/caches that should never be silently overwritten or shared:
 * the direct-session-login token, per-account proxy assignments (tied to this user's own saved
 * accounts/proxy service, meaningless on another PC), and the RNG-meter/storage-overlay caches (real
 * run data, not a "setting").
 */
public final class ProfileManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-profiles");
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path PROFILES_DIR = CONFIG_DIR.resolve("killer560smod-profiles");
    private static final Path ACTIVE_MARKER = CONFIG_DIR.resolve("killer560smod-active-profile.txt");

    private static final Set<String> EXCLUDED_FILES = Set.of(
            "killer560smod-session-login.json",
            "killer560smod-account-proxies.json",
            "killer560smod-rng-item-log.json",
            "killer560smod-storageoverlay-cache.json",
            // Croesus Profit Logger's claim history + totals - real run data, not a setting.
            "killer560smod-croesus-log.json",
            // Experimentation Table profit tracker's session log + totals - also real run data.
            "killer560smod-experiments-profit.json",
            // Item Browser's downloaded Hypixel item list - a network cache, not a setting (2026-09-15 audit).
            "killer560smod-itembrowser-items-cache.json",
            // Storage Search's per-page "last seen" times - tied to the storage-overlay cache above.
            "killer560smod-storagesearch-timestamps.json"
    );

    /** Whether a file name is one of this mod's own setting files that profiles may copy. Applied on
     *  save AND on apply/import/export (2026-09-15 audit): previously apply/import copied EVERY {@code *.json}
     *  in the profile folder / zip straight into the config dir, so a shared zip that happened to contain
     *  {@code killer560smod-session-login.json} or {@code killer560smod-account-proxies.json} (or any other
     *  mod's config name) silently overwrote this user's own login token / proxy assignments. */
    private static boolean isProfileSettingFile(String fileName) {
        return fileName != null
                && fileName.startsWith("killer560smod-")
                && fileName.endsWith(".json")
                && !EXCLUDED_FILES.contains(fileName);
    }

    /**
     * Setting files that are legitimately part of a profile but carry one per-user secret field alongside
     * the real settings (2026-09-16 audit): the Profile Viewer's own Hypixel API key lives in
     * {@code killer560smod-profileviewer.json} next to its source/keybind/page settings. Excluding the whole
     * file would drop those settings from profiles, so instead the secret fields are blanked when a profile
     * is saved (and therefore exported) and the user's live value is kept when a profile is applied.
     */
    private static final java.util.Map<String, Set<String>> SECRET_FIELDS = java.util.Map.of(
            "killer560smod-profileviewer.json", Set.of("apiKey")
    );

    /** Copies {@code source} to {@code target} with that file's secret fields blanked. Returns false (and
     *  writes nothing) when the file has no secret fields or can't be parsed, so the caller falls back to a
     *  plain copy. */
    private static boolean copyWithSecretsStripped(Path source, Path target) {
        Set<String> secrets = SECRET_FIELDS.get(source.getFileName().toString());
        if (secrets == null) {
            return false;
        }
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser
                    .parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String field : secrets) {
                if (obj.has(field)) {
                    obj.addProperty(field, "");
                }
            }
            Files.writeString(target, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj),
                    StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Copies profile file {@code source} over live file {@code live}, keeping the live file's secret fields
     *  when the profile's copy has them blank/missing (a shared profile never carries them). Returns false
     *  when the file has no secret fields or can't be merged, so the caller falls back to a plain copy. */
    private static boolean copyPreservingSecrets(Path source, Path live) {
        Set<String> secrets = SECRET_FIELDS.get(source.getFileName().toString());
        if (secrets == null) {
            return false;
        }
        try {
            com.google.gson.JsonObject incoming = com.google.gson.JsonParser
                    .parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonObject current = null;
            if (Files.isRegularFile(live)) {
                try {
                    current = com.google.gson.JsonParser
                            .parseString(Files.readString(live, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception ignored) {
                    // Unreadable live file: nothing to preserve.
                }
            }
            for (String field : secrets) {
                boolean incomingBlank = !incoming.has(field) || incoming.get(field).isJsonNull()
                        || (incoming.get(field).isJsonPrimitive() && incoming.get(field).getAsString().isBlank());
                if (incomingBlank && current != null && current.has(field)) {
                    incoming.add(field, current.get(field));
                }
            }
            Files.writeString(live, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(incoming),
                    StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ProfileManager() {
    }

    public record Result(boolean success, String message) {
    }

    /** Filesystem-safe folder name for a profile - anything outside letters/digits/space/underscore/
     *  hyphen becomes an underscore, matching the mod's other simple sanitizers (e.g. Posmsg names). */
    private static String sanitize(String name) {
        return name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
    }

    public static List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(PROFILES_DIR)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PROFILES_DIR)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static String getActiveProfile() {
        try {
            if (Files.exists(ACTIVE_MARKER)) {
                String name = Files.readString(ACTIVE_MARKER, StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static List<Path> liveConfigFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIR, "killer560smod-*.json")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isProfileSettingFile(entry.getFileName().toString())) {
                    files.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        return files;
    }

    /** Snapshots every current setting file into a new (or overwritten) profile folder. */
    public static Result saveCurrentAsProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        try {
            Path dir = PROFILES_DIR.resolve(name);
            Files.createDirectories(dir);
            int count = 0;
            for (Path file : liveConfigFiles()) {
                Path target = dir.resolve(file.getFileName());
                if (!copyWithSecretsStripped(file, target)) {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            return new Result(true, "§a[Profiles] Saved " + count + " setting file(s) as profile \"" + name + "\".");
        } catch (IOException e) {
            return new Result(false, "§cFailed to save profile: " + e.getMessage());
        }
    }

    /** Overwrites every live setting file with the ones stored in the given profile, marks it active, and
     *  reloads every in-memory config from the new files - see class doc. */
    public static Result applyProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        try {
            int count = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file) || !isProfileSettingFile(file.getFileName().toString())) {
                        continue;
                    }
                    Path live = CONFIG_DIR.resolve(file.getFileName());
                    if (!copyPreservingSecrets(file, live)) {
                        Files.copy(file, live, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
            Files.writeString(ACTIVE_MARKER, name, StandardCharsets.UTF_8);
            runOnClientThread(ProfileManager::reloadAllConfigs);
            return new Result(true, "§a[Profiles] Applied " + count + " setting file(s) from \"" + name + "\".");
        } catch (IOException e) {
            return new Result(false, "§cFailed to apply profile: " + e.getMessage());
        }
    }

    private static void runOnClientThread(Runnable task) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.isSameThread()) {
            task.run();
        } else {
            client.execute(task);
        }
    }

    /**
     * Re-reads every setting file a profile can contain into its in-memory config, then refreshes the few
     * features that hold state derived from their config rather than reading it live. Must run on the client
     * thread (GIF/DVD textures, the GLFW window). Each reload is isolated so one failure can't leave later
     * configs holding stale values that their next save would write back over the profile.
     * <p>
     * Deliberately NOT reloaded - the files {@link #EXCLUDED_FILES} keeps out of profiles (session login,
     * account proxies, RNG item log, storage-overlay cache, Croesus/Experiments profit logs, item-browser
     * cache, storage-search timestamps) plus configs that don't use the {@code killer560smod-*.json} name
     * so are never in a profile ({@code proxyclient.json} / ProxyConfig, {@code spotify-lyrics-lastfm.json} /
     * SpotifyLyricsFeature, the Cringe lines .txt).
     * <p>
     * Checked for startup-derived state (2026-09-15): HudElementRegistry resolves positions/scales from
     * HudConfig on every call, the HUD-editor / command / ability / loadout keybinds poll their config each
     * tick, NameChangerFeature rebuilds when {@code NameChangerConfig.version()} changes (load() bumps it),
     * HeldItemConfig.load() refreshes its {@code active} mirror, and no class keeps a config instance in a
     * field - so only GIF Player, DVD and the borderless window need an explicit refresh below. When adding
     * a new {@code killer560smod-*.json} config, add its load() here.
     */
    static void reloadAllConfigs() {
        boolean borderlessBefore = com.killer560.hub.window.WindowModeConfig.getInstance().isBorderlessFullscreenEnabled();
        Runnable[] loaders = {
                com.killer560.hub.abilitykeybinds.AbilityKeybindsConfig::load,
                com.killer560.hub.abilitytimers.AbilityTimersConfig::load,
                com.killer560.hub.autoclosechest.AutoCloseChestConfig::load,
                com.killer560.hub.autocorrect.AutoCorrectConfig::load,
                com.killer560.hub.autojoinskyblock.AutoJoinSkyblockConfig::load,
                com.killer560.hub.automeow.AutoMeowConfig::load,
                com.killer560.hub.autopuzzles.AutoPuzzlesConfig::load,
                com.killer560.hub.motionblur.MotionBlurConfig::load,
                com.killer560.hub.discordrpc.DiscordRpcConfig::load,
                com.killer560.hub.arrowalign.ArrowAlignConfig::load,
                com.killer560.hub.secrettrigger.SecretTriggerbotConfig::load,
                com.killer560.hub.doorhelpers.DoorHelpersConfig::load,
                com.killer560.hub.realtime.RealTimeConfig::load,
                com.killer560.hub.scoreboard.CustomScoreboardConfig::load,
                com.killer560.hub.profileviewer.ProfileViewerConfig::load,
                com.killer560.hub.windowlayout.WindowLayoutConfig::load,
                com.killer560.hub.util.SkyblockGate::reload,
                com.killer560.hub.bloodcamp.BloodCampConfig::load,
                com.killer560.hub.boss.LividSolverConfig::load,
                com.killer560.hub.chatcommands.ChatCommandsConfig::load,
                com.killer560.hub.partycommands.PartyCommandsConfig::load,
                com.killer560.hub.cheatutils.CheatUtilsConfig::load,
                com.killer560.hub.clicktranslate.ClickTranslateConfig::load,
                com.killer560.hub.commandkeybinds.CommandKeybindsConfig::load,
                com.killer560.hub.copychat.CopyChatConfig::load,
                com.killer560.hub.croesus.CroesusConfig::load,
                com.killer560.hub.diorite.DioriteGlassConfig::load,
                com.killer560.hub.doorkeys.DoorKeysConfig::load,
                com.killer560.hub.dungeonalerts.DungeonAlertsConfig::load,
                com.killer560.hub.dungeonbreaker.DungeonBreakerConfig::load,
                com.killer560.hub.dungeonextras.DungeonExtrasConfig::load,
                com.killer560.hub.dungeoninfo.DungeonInfoConfig::load,
                com.killer560.hub.dungeonqueue.DungeonQueueConfig::load,
                com.killer560.hub.dvd.DvdConfig::load,
                com.killer560.hub.emotes.ChatEmoteConfig::load,
                com.killer560.hub.etherwarp.EtherwarpWaypointsConfig::load,
                com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig::load,
                com.killer560.hub.experiments.ExperimentsConfig::load,
                com.killer560.hub.fastleap.FastLeapConfig::load,
                com.killer560.hub.fastleap.I4LeapConfig::load,
                com.killer560.hub.fullbright.FullbrightConfig::load,
                com.killer560.hub.gifplayer.GifPlayerConfig::load,
                com.killer560.hub.helditem.HeldItemConfig::load,
                com.killer560.hub.hud.HudConfig::load,
                com.killer560.hub.i4sensors.I4SensorsConfig::load,
                com.killer560.hub.inventoryhud.InventoryHudConfig::load,
                com.killer560.hub.inventorysearch.InventorySearchConfig::load,
                com.killer560.hub.itembrowser.ItemBrowserConfig::load,
                com.killer560.hub.itemprotect.ItemProtectConfig::load,
                com.killer560.hub.itemrarity.ItemRarityConfig::load,
                com.killer560.hub.leapmenu.LeapMenuConfig::load,
                com.killer560.hub.leapmessage.LeapMessageConfig::load,
                com.killer560.hub.livemap.LiveMapConfig::load,
                com.killer560.hub.loadoutkeybinds.LoadoutKeybindsConfig::load,
                com.killer560.hub.mainmenu.MainMenuThemeConfig::load,
                com.killer560.hub.mapping.MappingConfig::load,
                com.killer560.hub.maskinvincibility.MaskInvincibilityConfig::load,
                com.killer560.hub.mobesp.MobEspConfig::load,
                com.killer560.hub.modchat.ModChatConfig::load,
                com.killer560.hub.namechanger.NameChangerConfig::load,
                com.killer560.hub.nofire.NoFireConfig::load,
                com.killer560.hub.objecthider.ObjectHiderConfig::load,
                com.killer560.hub.lagdisplay.LagDisplayConfig::load,
                com.killer560.hub.abilitycooldown.AbilityCooldownConfig::load,
                com.killer560.hub.terminals.TerminalQolConfig::load,
                com.killer560.hub.goldorfrenzy.GoldorFrenzyConfig::load,
                com.killer560.hub.p4platform.P4PlatformHighlightConfig::load,
                com.killer560.hub.packdisabler.PackDisablerConfig::load,
                com.killer560.hub.partyfinder.PartyFinderOverlayConfig::load,
                com.killer560.hub.playerstats.PlayerStatsConfig::load,
                com.killer560.hub.posmsg.PosmsgConfig::load,
                com.killer560.hub.proximityvoice.ProximityVoiceConfig::load,
                com.killer560.hub.puzzlesolvers.BeamsSolverConfig::load,
                com.killer560.hub.puzzlesolvers.BlazeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.BoulderSolverConfig::load,
                com.killer560.hub.puzzlesolvers.IceFillSolverConfig::load,
                com.killer560.hub.puzzlesolvers.IcePathSolverConfig::load,
                com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.QuizSolverConfig::load,
                com.killer560.hub.puzzlesolvers.WaterSolverConfig::load,
                com.killer560.hub.puzzlesolvers.WeirdosSolverConfig::load,
                com.killer560.hub.quiver.QuiverDisplayConfig::load,
                com.killer560.hub.revertmasterstars.RevertMasterStarsConfig::load,
                com.killer560.hub.rngmeter.RngMeterConfig::load,
                com.killer560.hub.routes.RouteStore::load,
                com.killer560.hub.routes.WaypointRoutesConfig::load,
                com.killer560.hub.screenshotcopy.ScreenshotCopyConfig::load,
                com.killer560.hub.secrets.SecretsConfig::load,
                com.killer560.hub.secretwaypoints.SecretWaypointsConfig::load,
                com.killer560.hub.shorts.ShortsConfig::load,
                com.killer560.hub.simonsays.SimonSaysConfig::load,
                com.killer560.hub.slotbinds.SlotBindsConfig::load,
                com.killer560.hub.spiritleap.SpiritLeapOverlayConfig::load,
                com.killer560.hub.splittimers.SplitTimersConfig::load,
                com.killer560.hub.splittimers.TerminalTimersConfig::load,
                com.killer560.hub.storageoverlay.StorageOverlayConfig::load,
                com.killer560.hub.storagesearch.StorageSearchConfig::load,
                com.killer560.hub.terminals.TerminalSolverConfig::load,
                com.killer560.hub.ticktimers.TickTimersConfig::load,
                com.killer560.hub.trajectories.TrajectoriesConfig::load,
                com.killer560.hub.leveraura.LeverAuraConfig::load,
                com.killer560.hub.terminalaura.TerminalAuraConfig::load,
                com.killer560.hub.terminaltrigger.TerminalTriggerbotConfig::load,
                com.killer560.hub.autoroutes.AutoRoutesConfig::load,
                com.killer560.hub.autoroutes.RouteStore::reload,
                com.killer560.hub.chunkcache.ChunkCacheConfig::load,
                com.killer560.hub.pathfinding.PathfindingConfig::load,
                com.killer560.hub.f7spots.F7SpotsConfig::load,
                com.killer560.hub.p3nav.P3NavConfig::load,
                com.killer560.hub.maxor.MaxorConfig::load,
                com.killer560.hub.blessings.BlessingsConfig::load,
                com.killer560.hub.runsummary.RunSummaryConfig::load,
                com.killer560.hub.runstats.RunStatsConfig::load,
                com.killer560.hub.teammates.TeammatesConfig::load,
                com.killer560.hub.ragaxe.RagAxeConfig::load,
                com.killer560.hub.thorn.ThornConfig::load,
                com.killer560.hub.witherdragons.WitherDragonsConfig::load,
                com.killer560.hub.scorecalc.ScoreCalculatorConfig::load,
                com.killer560.hub.translate.TranslateConfig::load,
                com.killer560.hub.voicetotext.VoiceToTextConfig::load,
                com.killer560.hub.window.WindowModeConfig::load,
        };
        int failed = 0;
        for (Runnable loader : loaders) {
            try {
                loader.run();
            } catch (Throwable t) {
                failed++;
                LOGGER.warn("[Profiles] A config failed to reload after applying a profile", t);
            }
        }
        // Derived runtime state that isn't re-read from config on its own:
        Runnable[] refreshers = {
                // Loaded GIF textures + their HUD elements are synced to GifPlayerConfig's enabled files.
                com.killer560.hub.gifplayer.GifPlayerFeature::reload,
                // DVD runtimes hold the OLD DvdEntry objects; rebuild them from the new ones.
                com.killer560.hub.dvd.DvdFeature::reloadFromConfig,
                // Borderless is applied to the real window once; follow a changed setting.
                () -> com.killer560.hub.window.WindowModeFeature.applyConfigAfterReload(borderlessBefore),
        };
        for (Runnable refresher : refreshers) {
            try {
                refresher.run();
            } catch (Throwable t) {
                failed++;
                LOGGER.warn("[Profiles] A feature failed to refresh after applying a profile", t);
            }
        }
        LOGGER.info("[Profiles] Reloaded {} config(s) after applying a profile ({} failure(s))", loaders.length, failed);
    }

    public static Result deleteProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            // resolve("") is PROFILES_DIR itself - without this guard a blank name deleted every exported .zip.
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
            if (name.equals(getActiveProfile())) {
                Files.deleteIfExists(ACTIVE_MARKER);
            }
            return new Result(true, "§a[Profiles] Deleted \"" + name + "\".");
        } catch (IOException e) {
            return new Result(false, "§cFailed to delete profile: " + e.getMessage());
        }
    }

    /** Zips a profile's folder into {@code config/killer560smod-profiles/<name>.zip} - a single real
     *  file the user can hand to a friend (Discord attachment, etc.) with no path guessing needed. */
    public static Result exportProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        Path zipPath = PROFILES_DIR.resolve(name + ".zip");
        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos);
             DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || !isProfileSettingFile(file.getFileName().toString())) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zos);
                zos.closeEntry();
            }
            return new Result(true, "§a[Profiles] Exported to " + zipPath + " - send that file to share it.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to export profile: " + e.getMessage());
        }
    }

    /** Imports a shared zip into a new profile. {@code source} may be an absolute path, or just a
     *  filename to look for directly inside {@code config/killer560smod-profiles/} (the simplest case -
     *  a friend drops the .zip they were sent into that folder and only types its name). */
    public static Result importProfile(String source, String rawNewName) {
        String name = sanitize(rawNewName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path zipPath = null;
        try {
            zipPath = Path.of(source);
            if (!Files.isRegularFile(zipPath)) {
                zipPath = PROFILES_DIR.resolve(source);
            }
        } catch (RuntimeException ignored) {
            // InvalidPathException for a malformed typed path - falls through to "couldn't find".
        }
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            return new Result(false, "§cCouldn't find a file at \"" + source
                    + "\" (checked that path directly, and inside config/killer560smod-profiles/).");
        }
        Path dir = PROFILES_DIR.resolve(name);
        try {
            Files.createDirectories(dir);
            int count = 0;
            try (InputStream fis = Files.newInputStream(zipPath); ZipInputStream zis = new ZipInputStream(fis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String entryName;
                    try {
                        Path entryFile = Path.of(entry.getName()).getFileName();
                        entryName = entryFile == null ? "" : entryFile.toString();
                    } catch (RuntimeException badName) {
                        continue;
                    }
                    if (!isProfileSettingFile(entryName)) {
                        continue;
                    }
                    Files.copy(zis, dir.resolve(entryName), StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
            return new Result(true, "§a[Profiles] Imported " + count + " setting file(s) into new profile \""
                    + name + "\". Use /killer560 profile load " + name + " to switch to it.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to import profile: " + e.getMessage());
        }
    }
}
