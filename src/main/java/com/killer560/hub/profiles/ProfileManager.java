package com.killer560.hub.profiles;

import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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
 * {@code config/killer560/system/profiles/killer560smod-profiles/<name>/}. Applying a profile overwrites the live JSON files on disk
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

    private static final Logger LOGGER = ModLog.get("killer560smod-profiles");
    private static final Path PROFILES_DIR = ModPaths.config("killer560smod-profiles");
    private static final Path ACTIVE_MARKER = ModPaths.config("killer560smod-active-profile.txt");
    /** Drop folder for received profiles (killer560, 2026-10-04: "add a button to import a new file that opens the
     *  file location"). Every .zip put here is imported as a new profile named after the file and then moved into
     *  {@code imported/}, so it is never imported twice. A sibling of {@link #PROFILES_DIR}, not inside it, because
     *  every folder in there is listed as a profile. */
    private static final Path IMPORT_DIR = ModPaths.config("killer560smod-profiles-import");

    private static final Set<String> EXCLUDED_FILES = Set.of(
            "killer560smod-session-login.json",
            "killer560smod-account-proxies.json",
            "killer560smod-rng-item-log.json",
            "killer560smod-storageoverlay-cache.json",
            // UUID <-> name lookup cache shared by Class Overrides, Leap Order and the social features - not a setting.
            "killer560smod-playernames.json",
            // Supporter-names list fetched from the relay - a network cache, not a setting.
            "killer560smod-supporters-cache.json",
            // One-time "sharing defaults switched on" marker - not a setting.
            "killer560smod-sharing-defaults-v1.json",
            "killer560smod-sharing-defaults-v2.json",
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
        // Every killer560smod-*.json across the config/killer560 tree, at feature-folder depth only - so the copies
        // inside this class's own profiles folder are never read back as live settings.
        List<Path> files = new ArrayList<>();
        for (Path entry : ModPaths.settingFiles()) {
            if (isProfileSettingFile(entry.getFileName().toString())) {
                files.add(entry);
            }
        }
        return files;
    }

    /** Snapshots every current setting file into a new (or overwritten) profile folder. */
    public static synchronized Result saveCurrentAsProfile(String rawName) {
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
    public static synchronized Result applyProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        // Anything changed since the last auto-save poll belongs to the profile being LEFT - write it there before
        // the live files are replaced, or switching away within two seconds of a change would lose it.
        syncActiveProfile();
        try {
            int count = 0;
            java.util.Set<String> inProfile = new java.util.HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file) || !isProfileSettingFile(file.getFileName().toString())) {
                        continue;
                    }
                    inProfile.add(file.getFileName().toString());
                    // The name decides the folder, exactly as it does for the feature that reads the file.
                    Path live = ModPaths.config(file.getFileName().toString());
                    if (!copyPreservingSecrets(file, live)) {
                        Files.copy(file, live, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
            // A setting file the profile does NOT carry is RESET, not left alone.
            //
            // killer560 (2026-09-27): "I can type in the profiles tab but when I swap over between them it doesn't
            // actually adjust my settings. I tried turning leap counter on and saving that and it looks like it
            // will re-enable the setting but it wwont turn it off if I swap to a variant without leapcounter on."
            //
            // That is exactly this loop's absence. Applying a profile only ever copied the files the profile HAD,
            // so a feature whose config file did not exist when the profile was captured was simply skipped - and
            // whatever the live file said stayed. Turning something on is a file that exists and gets copied;
            // turning it off again is a file that is missing and got ignored. Hence "it re-enables but never
            // disables", and hence a profile that only ever accumulates settings.
            //
            // Deleting the live file is the reset: every config in here loads its own defaults when the file is
            // absent, which is the same path a fresh install takes. profileviewer is the one exception - it holds
            // his API key (SECRET_FIELDS), a shared profile never carries it, and losing it on every profile
            // switch would be its own bug.
            int reset = 0;
            for (Path live : liveConfigFiles()) {
                String fileName = live.getFileName().toString();
                if (inProfile.contains(fileName) || SECRET_FIELDS.containsKey(fileName)) {
                    continue;
                }
                if (Files.deleteIfExists(live)) {
                    reset++;
                }
            }
            Files.writeString(ACTIVE_MARKER, name, StandardCharsets.UTF_8);
            // The live files now ARE this profile; its auto-save starts from here.
            lastLiveNames = null;
            lastSyncedProfile = name;
            runOnClientThread(ProfileManager::reloadAllConfigs);
            return new Result(true, "§a[Profiles] Applied " + count + " setting file(s) from \"" + name + "\""
                    + (reset == 0 ? "." : ", and reset " + reset + " the profile didn't cover."));
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
                com.killer560.hub.autokick.AutoKickConfig::load,
                com.killer560.hub.autoclosechest.AutoCloseChestConfig::load,
                // Missing from this list until the testkit's profile round trip (2026-10-04) caught them: applying a
                // profile wrote their file but left the old values in memory, and the next save put those back.
                com.killer560.hub.autodebuff.AutoDebuffConfig::load,
                com.killer560.hub.autosell.AutoSellConfig::load,
                com.killer560.hub.bazaarflip.BazaarFlipConfig::load,
                com.killer560.hub.bugreport.BugReportConfig::load,
                com.killer560.hub.invsort.InventorySorterConfig::load,
                com.killer560.hub.mining.chmap.CrystalHollowsMapConfig::load,
                com.killer560.hub.mining.nucleus.NucleusRunProfitConfig::load,
                com.killer560.hub.mining.profit.MiningProfitConfig::load,
                com.killer560.hub.updatecheck.UpdateCheckConfig::load,
                com.killer560.hub.autocorrect.AutoCorrectConfig::load,
                com.killer560.hub.trail.TrailConfig::load,
                com.killer560.hub.position.PositionConfig::load,
                com.killer560.hub.social.BestFriendsConfig::load,
                com.killer560.hub.social.FriendsListConfig::load,
                com.killer560.hub.petwheel.PetWheelConfig::load,
                com.killer560.hub.auction.AuctionConfig::load,
                com.killer560.hub.supporters.SupportersConfig::load,
                com.killer560.hub.commandshortcuts.CommandShortcutsConfig::load,
                com.killer560.hub.autojoinskyblock.AutoJoinSkyblockConfig::load,
                com.killer560.hub.automeow.AutoMeowConfig::load,
                com.killer560.hub.architect.ArchitectDraftConfig::load,
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
                com.killer560.hub.witherdoors.WitherDoorsConfig::load,
                com.killer560.hub.goldor.GoldorTriggerbotConfig::load,
                com.killer560.hub.dungeonalerts.DungeonAlertsConfig::load,
                com.killer560.hub.dungeonbreaker.DungeonBreakerConfig::load,
                com.killer560.hub.dungeonextras.DungeonExtrasConfig::load,
                com.killer560.hub.dungeoninfo.DungeonInfoConfig::load,
                com.killer560.hub.dungeonqueue.DungeonQueueConfig::load,
                com.killer560.hub.dvd.DvdConfig::load,
                com.killer560.hub.emotes.ChatEmoteConfig::load,
                com.killer560.hub.etherwarp.EtherwarpWaypointsConfig::load,
                // Real saved waypoint data (2026-09-27, room-relative rewrite), not just a setting - same
                // reason com.killer560.hub.routes.RouteStore::load is in this list for Waypoint Routes.
                com.killer560.hub.etherwarp.EtherwarpWaypointsStore::load,
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
                com.killer560.hub.inventorytheme.InventoryThemeConfig::load,
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
                com.killer560.hub.interop.InteropConfig::load,
                com.killer560.hub.namechanger.NameChangerConfig::load,
                com.killer560.hub.nofire.NoFireConfig::load,
                com.killer560.hub.objecthider.ObjectHiderConfig::load,
                com.killer560.hub.lagdisplay.LagDisplayConfig::load,
                com.killer560.hub.abilitycooldown.AbilityCooldownConfig::load,
                com.killer560.hub.terminals.TerminalQolConfig::load,
                com.killer560.hub.p4platform.P4PlatformHighlightConfig::load,
                com.killer560.hub.partydata.PartyDataConfig::load,
                com.killer560.hub.bridge.BridgeConfig::load,
                com.killer560.hub.melody.MelodyHudConfig::load,
                com.killer560.hub.partyfinder.PartyFinderOverlayConfig::load,
                com.killer560.hub.playerstats.PlayerStatsConfig::load,
                com.killer560.hub.posmsg.PosmsgConfig::load,
                com.killer560.hub.puzzlesolvers.BeamsSolverConfig::load,
                com.killer560.hub.puzzlesolvers.BlazeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.BoulderSolverConfig::load,
                com.killer560.hub.puzzlesolvers.IceFillSolverConfig::load,
                com.killer560.hub.puzzlesolvers.IcePathSolverConfig::load,
                com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.TicTacToeSolverConfig::load,
                com.killer560.hub.puzzlesolvers.QuizSolverConfig::load,
                com.killer560.hub.puzzlesolvers.SolverEspConfig::load,
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
                com.killer560.hub.ap3.Ap3Config::load,
                com.killer560.hub.ap3.Ap3Store::reload,
                com.killer560.hub.dungeonclass.ClassOverrides::load,
                com.killer560.hub.dungeonclass.ClassSelectionOverlayConfig::load,
                com.killer560.hub.leapcounter.LeapCounterConfig::load,
                com.killer560.hub.armourdye.ArmourDyeConfig::load,
                com.killer560.hub.tooltipscroll.TooltipScrollConfig::load,
                com.killer560.hub.enchantcolors.EnchantColorsConfig::load,
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

    public static synchronized Result deleteProfile(String rawName) {
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

    /** Zips a profile's folder into {@code config/killer560/system/profiles/killer560smod-profiles/<name>.zip} - a single real
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
     *  filename to look for directly inside {@code config/killer560/system/profiles/killer560smod-profiles/} (the simplest case -
     *  a friend drops the .zip they were sent into that folder and only types its name). */
    public static synchronized Result importProfile(String source, String rawNewName) {
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
                    + "\" (checked that path directly, and inside config/killer560/system/profiles/killer560smod-profiles/).");
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
            if (count == 0) {
                // Not a profile zip: leave no empty profile behind.
                deleteFolderQuietly(dir);
                return new Result(false, "§c[Profiles] \"" + zipPath.getFileName() + "\" has no setting files in it.");
            }
            return new Result(true, "§a[Profiles] Imported " + count + " setting file(s) into new profile \""
                    + name + "\". Use /killer560 profile load " + name + " to switch to it.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to import profile: " + e.getMessage());
        }
    }

    private static void deleteFolderQuietly(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Import drop folder

    /** The drop folder, created if missing - what the Profiles tab's Import button opens. */
    public static Path importFolder() {
        try {
            Files.createDirectories(IMPORT_DIR);
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not create {}", IMPORT_DIR, e);
        }
        return IMPORT_DIR;
    }

    /**
     * Imports every .zip in the drop folder as a new profile named after the file ("Bob F7.zip" becomes "Bob F7", with
     * " 2", " 3"... added if that name is taken), then moves each zip into {@code imported/} - including one that
     * failed, so a bad file is reported once and not on every poll. A zip that cannot be moved yet (still being
     * written by a download or an Explorer copy) is undone and retried on the next poll. Returns one message per
     * file handled.
     */
    public static synchronized List<String> importDroppedFiles() {
        List<String> messages = new ArrayList<>();
        if (!Files.isDirectory(IMPORT_DIR)) {
            return messages;
        }
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(IMPORT_DIR)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    zips.add(p);
                }
            }
        } catch (IOException e) {
            return messages;
        }
        for (Path zip : zips) {
            String file = zip.getFileName().toString();
            String base = sanitize(file.substring(0, file.length() - 4));
            if (base.isEmpty()) {
                base = "Imported";
            }
            String name = base;
            for (int n = 2; Files.exists(PROFILES_DIR.resolve(name)); n++) {
                name = base + " " + n;
            }
            Result result = importProfile(zip.toString(), name);
            try {
                Path done = IMPORT_DIR.resolve("imported");
                Files.createDirectories(done);
                Files.move(zip, done.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                if (result.success()) {
                    deleteFolderQuietly(PROFILES_DIR.resolve(name));
                }
                continue;
            }
            messages.add(result.success()
                    ? "§a[Profiles] Imported \"" + file + "\" as profile \"" + name + "\"."
                    : result.message());
        }
        return messages;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Auto-save into the active profile

    /**
     * killer560 (2026-10-04): "if I currently have a profile loaded and change a setting then it is saved to that
     * current profile." Every config class writes its own file its own way (about 150 of them), so instead of hooking
     * each save this polls the live setting files every {@link #AUTO_SAVE_PERIOD_MS} on a background thread and
     * copies any that are newer than the active profile's copy into it - the poll interval is the debounce. A setting
     * file that existed on the previous poll and has since been deleted (a reset) is deleted from the profile too;
     * nothing is deleted on a first poll, when there is no previous state to compare with. Secret fields are stripped
     * exactly as Save does. Runs under this class's lock, so it can never interleave with {@link #applyProfile}
     * copying another profile over the live files.
     */
    private static final long AUTO_SAVE_PERIOD_MS = 2000L;
    /** Live setting-file names seen by the previous poll for the active profile; null right after a switch. */
    private static Set<String> lastLiveNames;
    private static String lastSyncedProfile;
    private static java.util.concurrent.ScheduledExecutorService autoSaveExecutor;

    /** Starts the auto-save / import poll. Call once from client init. */
    public static synchronized void startBackgroundTasks() {
        if (autoSaveExecutor != null) {
            return;
        }
        autoSaveExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "killer560smod-profiles-autosave");
            t.setDaemon(true);
            return t;
        });
        autoSaveExecutor.scheduleWithFixedDelay(ProfileManager::pollOnce,
                AUTO_SAVE_PERIOD_MS, AUTO_SAVE_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        // A change made in the last two seconds before quitting must still land.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                .register(client -> syncActiveProfile());
    }

    private static void pollOnce() {
        try {
            syncActiveProfile();
            List<String> imported = importDroppedFiles();
            if (!imported.isEmpty()) {
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    client.execute(() -> {
                        for (String message : imported) {
                            com.killer560.hub.notify.ModOverlayMessage.show(message, 5000);
                        }
                        // The overlay draws behind the settings menu, so refresh the list he is looking at.
                        if (com.killer560.hub.compat.McCompat.screen(client) instanceof com.killer560.hub.gui.ModScreen screen) {
                            screen.refresh();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Profiles] auto-save poll failed", t);
        }
    }

    /** Copies every live setting file newer than the active profile's copy into it. Safe to call from any thread. */
    public static synchronized void syncActiveProfile() {
        String active = getActiveProfile();
        Path dir = active == null ? null : PROFILES_DIR.resolve(sanitize(active));
        if (dir == null || !Files.isDirectory(dir)) {
            lastLiveNames = null;
            lastSyncedProfile = null;
            return;
        }
        if (!active.equals(lastSyncedProfile)) {
            lastLiveNames = null;
            lastSyncedProfile = active;
        }
        Set<String> liveNames = new java.util.HashSet<>();
        int copied = 0;
        for (Path live : liveConfigFiles()) {
            String fileName = live.getFileName().toString();
            liveNames.add(fileName);
            Path stored = dir.resolve(fileName);
            try {
                if (Files.isRegularFile(stored)
                        && Files.getLastModifiedTime(live).compareTo(Files.getLastModifiedTime(stored)) <= 0) {
                    continue;
                }
                if (!copyWithSecretsStripped(live, stored)) {
                    Files.copy(live, stored, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            } catch (IOException e) {
                // Mid-write by its own config class; the next poll picks it up.
            }
        }
        int removed = 0;
        if (lastLiveNames != null) {
            for (String gone : lastLiveNames) {
                if (!liveNames.contains(gone) && !SECRET_FIELDS.containsKey(gone)) {
                    try {
                        if (Files.deleteIfExists(dir.resolve(gone))) {
                            removed++;
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        lastLiveNames = liveNames;
        if (copied > 0 || removed > 0) {
            LOGGER.info("[Profiles] Auto-saved {} changed setting file(s) into \"{}\"{}", copied, active,
                    removed == 0 ? "" : " and removed " + removed + " reset one(s)");
        }
    }
}
