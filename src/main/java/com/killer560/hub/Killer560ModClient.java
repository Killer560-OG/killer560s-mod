package com.killer560.hub;

import com.killer560.hub.abilitytimers.AbilityTimersFeature;
import com.killer560.hub.accounts.HypixelJoinWatcher;
import com.killer560.hub.autojoinskyblock.AutoJoinSkyblockFeature;
import com.killer560.hub.automeow.AutoMeowFeature;
import com.killer560.hub.compat.ModCompatibility;
import com.killer560.hub.cringe.CringeFeature;
import com.killer560.hub.dungeoninfo.DungeonInfoFeature;
import com.killer560.hub.dvd.DvdFeature;
import com.killer560.hub.etherwarp.EtherwarpFeature;
import com.killer560.hub.etherwarp.EtherwarpHudElement;
import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.experiments.ExperimentsFeature;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.tab.TranslateTab;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.leapmessage.LeapMessageFeature;
import com.killer560.hub.i4sensors.I4SensorsFeature;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.mapping.MappingFeature;
import com.killer560.hub.maskinvincibility.MaskInvincibilityFeature;
import com.killer560.hub.mobesp.MobEspFeature;
import com.killer560.hub.modchat.ModChatFeature;
import com.killer560.hub.secretwaypoints.SecretWaypointsFeature;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.posmsg.PosmsgConfig;
import com.killer560.hub.posmsg.PosmsgEntry;
import com.killer560.hub.posmsg.PosmsgFeature;
import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.rngmeter.MagicFindTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.secrets.SimDiagnosticFeature;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngMeterOverlay;
import com.killer560.hub.simonsays.SimonSaysFeature;
import com.killer560.hub.splittimers.SplitTimersFeature;
import com.killer560.hub.spotify.SpotifyLyricsFeature;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.ticktimers.TickTimersFeature;
import com.killer560.hub.termism.TermismMenuScreen;
import com.killer560.hub.translate.TranslateConfig;
import com.killer560.hub.voicetotext.VoiceToTextFeature;
import com.killer560.hub.translate.TranslateLanguages;
import com.killer560.hub.window.WindowModeFeature;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

public class Killer560ModClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod");
    private static boolean editKeyWasDown = false;
    // ModScreen's tab order: Home, Chat, Dungeon, Spotify, GIF Player - see ModScreen.init().
    // Translate lives inside the Chat folder tab (as its first sub-tab), so opening this index
    // lands on Chat, which defaults to showing Translate.
    private static final int TRANSLATE_TAB_INDEX = 1;

    @Override
    public void onInitializeClient() {
        ModCompatibility.refuseIfFirmamentPresent();

        HypixelJoinWatcher.register();
        AutoJoinSkyblockFeature.register();
        ProxyConfig.load();
        SpotifyLyricsFeature.init();
        MagicFindTracker.register();
        RngMeterOverlay.register();
        RngMeterEngine.PRICES.startAutoRefresh();
        LeapMessageFeature.register();
        GifPlayerFeature.register();
        DvdFeature.register();
        com.killer560.hub.mainmenu.MainMenuTitleLayout.register();
        AutoMeowFeature.register();
        com.killer560.hub.architect.ArchitectDraftFeature.register();
        ExperimentsFeature.register();
        StorageOverlayFeature.register();
        DungeonState.register();
        SimDiagnosticFeature.register();
        PosmsgFeature.register();
        com.killer560.hub.thorn.ThornFeature.register();
        HudElementRegistry.register(com.killer560.hub.thorn.ThornFeature.HUD);
        com.killer560.hub.f7spots.F7SpotsFeature.register();
        com.killer560.hub.p3nav.P3NavFeature.register();
        com.killer560.hub.blessings.BlessingsFeature.register();
        com.killer560.hub.runsummary.RunSummaryFeature.register();
        com.killer560.hub.runsummary.RunLogCommands.register();
        HudElementRegistry.register(com.killer560.hub.blessings.BlessingsFeature.HUD);
        com.killer560.hub.maxor.MaxorCrystalsFeature.register();
        HudElementRegistry.register(com.killer560.hub.maxor.MaxorCrystalsFeature.HUD);
        com.killer560.hub.ragaxe.RagAxeFeature.register();
        com.killer560.hub.ragaxe.RagAxeFeature.hudElements().forEach(HudElementRegistry::register);
        com.killer560.hub.scorecalc.ScoreCalculatorFeature.register();
        com.killer560.hub.witherdragons.WitherDragonsFeature.register();
        com.killer560.hub.chunkcache.ChunkCacheManager.register();
        com.killer560.hub.pathfinding.PathfindingFeature.register();
        HudElementRegistry.register(new com.killer560.hub.witherdragons.WitherDragonsFeature.DragonTimersHudElement());
        HudElementRegistry.register(new com.killer560.hub.witherdragons.KingRelicsFeature.RelicTimerHudElement());
        HudElementRegistry.register(com.killer560.hub.scorecalc.ScoreCalculatorFeature.ScoreHudElement.INSTANCE);
        AbilityTimersFeature.register();
        HudElementRegistry.register(new AbilityTimersFeature.TimersHudElement());
        DungeonInfoFeature.register();
        HudElementRegistry.register(new DungeonInfoFeature.SecretsHudElement());
        HudElementRegistry.register(new DungeonInfoFeature.TimeHudElement());
        MobEspFeature.register();
        com.killer560.hub.teammates.TeammatesFeature.register();
        SimonSaysFeature.register();
        HudElementRegistry.register(new SimonSaysFeature.PartyProgressHudElement());
        TickTimersFeature.register();
        HudElementRegistry.register(new TickTimersFeature.TickTimersHudElement());
        SplitTimersFeature.register();
        HudElementRegistry.register(new SplitTimersFeature.SplitTimersHudElement());
        com.killer560.hub.splittimers.DeviceTimesFeature.register();
        com.killer560.hub.splittimers.TerminalTimersFeature.register();
        com.killer560.hub.itemrarity.ItemRarityFeature.register();
        com.killer560.hub.cheatutils.CheatUtils.register();
        com.killer560.hub.leveraura.LeverAuraFeature.register();
        com.killer560.hub.terminalaura.TerminalAuraFeature.register();
        com.killer560.hub.terminaltrigger.TerminalTriggerbotFeature.register();
        com.killer560.hub.autoroutes.AutoRoutesFeature.register();
        com.killer560.hub.autoroutes.AutoRoutesCommands.register();
        com.killer560.hub.autoroutes.AutoRoutesKeybinds.register();
        com.killer560.hub.autoroutes.AutoRoutesEditInput.register();
        com.killer560.hub.ap3.Ap3Feature.register();
        HudElementRegistry.register(com.killer560.hub.ap3.Ap3Feature.STOPWATCH_HUD);
        com.killer560.hub.ap3.Ap3EditInput.register();
        com.killer560.hub.ap3.Ap3Commands.register();
        com.killer560.hub.ap3.Ap3Keybinds.register();
        com.killer560.hub.leapcounter.LeapCounterFeature.register();
        com.killer560.hub.armourdye.ArmourDyeFeature.register();
        com.killer560.hub.tooltipscroll.TooltipScrollFeature.register();
        com.killer560.hub.enchantcolors.EnchantColorsFeature.register();
        com.killer560.hub.shorts.ShortsFeature.register();
        com.killer560.hub.dungeonalerts.DungeonAlertsFeature.register();
        com.killer560.hub.dungeonalerts.DungeonAlertsFeature.hudElements().forEach(HudElementRegistry::register);
        com.killer560.hub.namechanger.NameChangerFeature.register();
        com.killer560.hub.croesus.ChestProfitFeature.register();
        com.killer560.hub.croesus.AutoCroesusFeature.register();
        com.killer560.hub.croesus.CroesusCommands.register();
        com.killer560.hub.hud.HudInGameRenderer.register();
        MaskInvincibilityFeature.register();
        HudElementRegistry.register(new MaskInvincibilityFeature.MaskInvincibilityHudElement());
        ModChatFeature.register();
        com.killer560.hub.interop.InteropFeature.register();
        com.killer560.hub.partydata.PartyDataFeature.register();
        com.killer560.hub.bridge.BridgeFeature.register();
        com.killer560.hub.melody.MelodyTrackerFeature.register();
        com.killer560.hub.interop.SharingDefaults.applyOnce();
        // Auto Leap Out replaced by the QUOI AutoLeap port (fast leap + auto leaps) and the separate i4 leap.
        com.killer560.hub.fastleap.FastLeapFeature.register();
        com.killer560.hub.bloodcamp.BloodCampFeature.register();
        com.killer560.hub.bloodcamp.BloodCampFeature.hudElements().forEach(HudElementRegistry::register);
        com.killer560.hub.puzzlesolvers.BoulderSolverFeature.register();
        com.killer560.hub.puzzlesolvers.QuizSolverFeature.register();
        com.killer560.hub.puzzlesolvers.IceFillSolverFeature.register();
        com.killer560.hub.puzzlesolvers.WeirdosSolverFeature.register();
        com.killer560.hub.autopuzzles.AutoPuzzlesFeature.register();
        com.killer560.hub.storagesearch.StorageSearchFeature.register();
        com.killer560.hub.routes.WaypointRoutesFeature.register();
        com.killer560.hub.dungeonextras.DungeonExtrasFeature.register();
        com.killer560.hub.dungeonqueue.DungeonQueueFeature.register();
        com.killer560.hub.motionblur.MotionBlurFeature.register();
        com.killer560.hub.discordrpc.DiscordRpcFeature.register();
        com.killer560.hub.windowlayout.WindowLayoutFeature.register();
        com.killer560.hub.puzzlesolvers.WaterSolverFeature.register();
        com.killer560.hub.puzzlesolvers.BeamsSolverFeature.register();
        com.killer560.hub.puzzlesolvers.BlazeSolverFeature.register();
        com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature.register();
        com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature.register();
        com.killer560.hub.puzzlesolvers.IcePathSolverFeature.register();
        com.killer560.hub.boss.LividSolverFeature.register();
        HudElementRegistry.register(new com.killer560.hub.boss.LividSolverFeature.InvulnTimerHudElement());
        com.killer560.hub.quiver.QuiverDisplayFeature.register();
        HudElementRegistry.register(new com.killer560.hub.quiver.QuiverDisplayFeature.QuiverHudElement());
        com.killer560.hub.inventoryhud.InventoryHudFeature.register();
        HudElementRegistry.register(com.killer560.hub.inventoryhud.InventoryHudFeature.InventoryHudElement.INSTANCE);
        HudElementRegistry.register(com.killer560.hub.realtime.RealTimeFeature.RealTimeHudElement.INSTANCE);
        HudElementRegistry.register(com.killer560.hub.position.PositionFeature.PositionHudElement.INSTANCE);
        com.killer560.hub.scoreboard.CustomScoreboardFeature.register();
        com.killer560.hub.profileviewer.ProfileViewerFeature.register();
        com.killer560.hub.arrowalign.ArrowAlignFeature.register();
        com.killer560.hub.secrettrigger.SecretTriggerbotFeature.register();
        com.killer560.hub.doorhelpers.DoorHelpersFeature.register();
        HudElementRegistry.register(com.killer560.hub.scoreboard.CustomScoreboardFeature.Element.INSTANCE);
        com.killer560.hub.playerstats.PlayerStatsFeature.register();
        HudElementRegistry.register(new com.killer560.hub.playerstats.PlayerStatsFeature.StatsHudElement());
        com.killer560.hub.util.SkyblockGate.register();
        com.killer560.hub.spiritleap.SpiritLeapOverlayFeature.register();
        com.killer560.hub.leapmenu.PartyTracker.register();
        com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.register();
        com.killer560.hub.slotbinds.SlotBindsFeature.register();
        com.killer560.hub.chatcommands.ChatCommandsFeature.register();
        com.killer560.hub.partycommands.PartyCommandsFeature.register();
        com.killer560.hub.doorkeys.DoorKeysFeature.register();
        com.killer560.hub.witherdoors.WitherDoorsFeature.register();
        com.killer560.hub.goldor.GoldorTriggerbotFeature.register();
        com.killer560.hub.trajectories.TrajectoriesFeature.register();
        com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature.register();
        com.killer560.hub.abilitykeybinds.AbilityKeybindsFeature.register();
        com.killer560.hub.p4platform.P4PlatformHighlightFeature.register();
        com.killer560.hub.diorite.DioriteGlassFeature.register();
        com.killer560.hub.partyfinder.PartyFinderOverlay.register();
        com.killer560.hub.commandkeybinds.CommandKeybindsFeature.register();
        com.killer560.hub.inventorysearch.InventorySearchFeature.register();
        com.killer560.hub.itemprotect.ItemProtectFeature.register();
        com.killer560.hub.objecthider.ObjectHiderFeature.register();
        com.killer560.hub.abilitycooldown.AbilityCooldownFeature.register();
        HudElementRegistry.register(new com.killer560.hub.abilitycooldown.AbilityCooldownFeature.CooldownHudElement());
        com.killer560.hub.lagdisplay.LagDisplayFeature.register();
        HudElementRegistry.register(new com.killer560.hub.lagdisplay.LagDisplayFeature.LagHudElement());
        com.killer560.hub.terminals.TerminalQolFeature.register();
        com.killer560.hub.itembrowser.ItemBrowserFeature.register();
        I4SensorsFeature.register();
        com.killer560.hub.i4sensors.AutoI4Feature.register();
        LiveMapFeature.register();
        HudElementRegistry.register(new LiveMapFeature.LiveMapHudElement());
        com.killer560.hub.runstats.RunStatsFeature.register();
        VoiceToTextFeature.register();
        SecretWaypointsFeature.register();
        MappingFeature.register();
        EtherwarpFeature.register();
        com.killer560.hub.trail.TrailFeature.register();
        com.killer560.hub.interop.ModConflictWarnings.register();
        com.killer560.hub.social.BestFriendsTracker.register();
        com.killer560.hub.social.BestFriendsCommands.register();
        com.killer560.hub.social.FriendsListCommands.register();
        com.killer560.hub.petwheel.PetWheelFeature.register();
        com.killer560.hub.auction.AuctionHouseFeature.register();
        com.killer560.hub.auction.BazaarFeature.register();
        com.killer560.hub.auction.ListingHelperFeature.register();
        com.killer560.hub.supporters.SupportersFeature.register();
        com.killer560.hub.commandshortcuts.CommandShortcutsFeature.register();
        HudElementRegistry.register(new EtherwarpHudElement());

        ClientTickEvents.END_CLIENT_TICK.register(Killer560ModClient::checkHudEditKeybind);
        ClientTickEvents.END_CLIENT_TICK.register(Killer560ModClient::checkExperimentsCancelKeybind);
        ClientTickEvents.END_CLIENT_TICK.register(WindowModeFeature::tickApplyOnce);

        // "/ew waypoint add|remove|undo" (killer560, 2026-09-21: "change the command to /ew waypoint add. add in
        // remove that removes the closest, undo that undoes the last as well").
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ew")
                        .then(ClientCommands.literal("waypoint")
                                .then(ClientCommands.literal("add")
                                        .executes(context -> {
                                            if (blockedBySkyblockOnly()) {
                                                return 0;
                                            }
                                            ModOverlayMessage.show(EtherwarpFeature.addAtFeet(null), 3000);
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    if (blockedBySkyblockOnly()) {
                                                        return 0;
                                                    }
                                                    ModOverlayMessage.show(EtherwarpFeature.addAtFeet(
                                                            StringArgumentType.getString(context, "name")), 3000);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("remove").executes(context -> {
                                    ModOverlayMessage.show(EtherwarpFeature.removeClosest(), 3000);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("undo").executes(context -> {
                                    ModOverlayMessage.show(EtherwarpFeature.undo(), 3000);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("clear").executes(context -> {
                                    EtherwarpFeature.clear();
                                    ModOverlayMessage.show("[Etherwarp] Cleared all waypoints.", 2500);
                                    return 1;
                                })))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("killer560")
                        .executes(context -> {
                            LOGGER.info("/killer560 executed - command handler running");
                            Minecraft client = Minecraft.getInstance();
                            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                            // Deferred via client.execute (matches the macro mod's own proven-working
                            // /start command pattern) rather than calling setScreen synchronously from
                            // inside command execution, in case that context matters.
                            client.execute(() -> {
                                LOGGER.info("Deferred setScreenAndShow(ModScreen) running now");
                                client.setScreenAndShow(new ModScreen(client.screen));
                            });
                            return 1;
                        })
                        // "/Killer560 leaporder" (2026-09-13 request) - opens the Leap Order menu
                        // directly rather than going through the mod menu's Dungeon folder.
                        .then(ClientCommands.literal("leaporder")
                                .executes(context -> {
                                    Minecraft client = Minecraft.getInstance();
                                    client.execute(() -> client.setScreenAndShow(
                                            new com.killer560.hub.leapmenu.LeapOrderScreen(client.screen)));
                                    return 1;
                                }))
                        // Real, working data-gathering tool for the Mapping tab's placeholders - see
                        // MappingFeature's class doc. Dumps whatever map you're holding right now.
                        .then(ClientCommands.literal("mapdump")
                                .executes(context -> {
                                    ModOverlayMessage.show(MappingFeature.dumpHeldMap(), 4000);
                                    return 1;
                                }))
                        // "/killer560 ew add <name>" - killer560's "secret waypoints" request
                        // (2026-09-13), exact syntax as requested. Deliberately its own local-only
                        // system, not built on Posmsg's add - see EtherwarpFeature's class doc for why
                        // (Posmsg's add immediately broadcasts to real Party Chat; this never does).
                        .then(ClientCommands.literal("ew")
                                .then(ClientCommands.literal("add")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    if (blockedBySkyblockOnly()) {
                                                        return 0;
                                                    }
                                                    String name = StringArgumentType.getString(context, "name");
                                                    ModOverlayMessage.show(EtherwarpFeature.addAtFeet(name), 3000);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("clear")
                                        .executes(context -> {
                                            EtherwarpFeature.clear();
                                            ModOverlayMessage.show("[Etherwarp] Cleared all waypoints.", 2500);
                                            return 1;
                                        })))
                        // "/killer560 chat <message>" - killer560's "custom chat" request. See
                        // ModChatFeature's class doc for why this isn't literally "/chat killer560"
                        // (that root belongs to a real Hypixel command).
                        .then(ClientCommands.literal("chat")
                                .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            if (blockedBySkyblockOnly()) {
                                                return 0;
                                            }
                                            String message = StringArgumentType.getString(context, "message");
                                            ModOverlayMessage.show(com.killer560.hub.modchat.ModChatFeature.send(message), 3000);
                                            return 1;
                                        })))
                        // "/killer560 sim" (2026-09-14) - killer560's own request: p3sim.net's real
                        // sidebar/chat format isn't something this session can observe directly, so
                        // rather than guess at matching it, this is a manual override telling every
                        // F7/M7-gated feature "treat me as if I'm in the real F7 boss fight right now."
                        // See DungeonState#toggleSimOverride's doc comment for exactly when it
                        // auto-clears (a real floor gets detected, or the world unloads).
                        .then(ClientCommands.literal("sim")
                                .executes(context -> {
                                    boolean nowActive = com.killer560.hub.secrets.DungeonState.toggleSimOverride();
                                    ModOverlayMessage.show(nowActive
                                            ? "[Sim] Treating you as if you're in the real F7 boss fight."
                                            : "[Sim] Override off - back to real automatic detection.", 3000);
                                    return 1;
                                }))
                        // "/killer560 profile ..." - killer560's custom settings-profile request. See
                        // ProfileManager's class doc for why "load" needs a restart to fully apply.
                        // Built as its own method (buildProfileCommand) rather than inlined here - this
                        // whole command tree is already nested 4+ levels deep and another sub-tree
                        // inlined by hand risks exactly the kind of mismatched-paren mistake that's easy
                        // to make and hard to spot in a wall of closing parens.
                        .then(buildProfileCommand())
                        // "/killer560 ah" and "/killer560 bz" - item 8.1 (Auction House and Bazaar browsers).
                        .then(ClientCommands.literal("ah")
                                .executes(context -> {
                                    com.killer560.hub.auction.AuctionHouseFeature.openOrExplain();
                                    return 1;
                                }))
                        .then(ClientCommands.literal("bz")
                                .executes(context -> {
                                    com.killer560.hub.auction.BazaarFeature.openOrExplain();
                                    return 1;
                                }))));

        // Posmsg: killer560's request (2026-09-13) for a chat-relayed waypoint system, syntax exactly
        // as he specified it - "/Posmsg add" then the message, then the center coordinate, then the
        // radius. "add" always creates a reusable custom waypoint AND sends it immediately (same as
        // pressing Send on it right after adding); "send <name>" re-sends an existing preset/custom
        // entry by name (case-insensitive), honoring its own once-per-run toggle.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("posmsg")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("rest", StringArgumentType.greedyString())
                                        .executes(Killer560ModClient::posmsgAdd)))
                        .then(ClientCommands.literal("send")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            if (blockedBySkyblockOnly()) {
                                                return 0;
                                            }
                                            String name = StringArgumentType.getString(context, "name");
                                            PosmsgEntry entry = PosmsgConfig.getInstance().byName(name);
                                            if (entry == null) {
                                                ModOverlayMessage.show("§c[Posmsg] No waypoint named \"" + name + "\".", 3000);
                                                return 0;
                                            }
                                            PosmsgFeature.send(entry);
                                            return 1;
                                        })))));

        // Termism: killer560's own terminal-practice request (2026-09-09) - "/termism or... through the
        // settings" - this is the command half, TermismTab (Dungeon folder) is the settings half.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("termism")
                        .executes(context -> {
                            if (blockedBySkyblockOnly()) {
                                return 0;
                            }
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> client.setScreenAndShow(new TermismMenuScreen(client.screen)));
                            return 1;
                        })));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SuggestionProvider<FabricClientCommandSource> languageSuggestions = (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.US);
                for (TranslateLanguages.Lang lang : TranslateLanguages.ALL) {
                    if (lang.name().toLowerCase(Locale.US).startsWith(remaining)) {
                        builder.suggest(lang.name());
                    }
                }
                return builder.buildFuture();
            };

            dispatcher.register(ClientCommands.literal("translate")
                    .then(ClientCommands.argument("language", StringArgumentType.greedyString())
                            .suggests(languageSuggestions)
                            .executes(Killer560ModClient::setLanguageFromCommand)));

            // "/language" alone opens the searchable picker; "/language <name>" sets it directly,
            // same as "/translate <name>" - both accepted since killer560 expects either to work.
            dispatcher.register(ClientCommands.literal("language")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        TranslateTab.openPickerOnNextBuild = true;
                        client.execute(() -> client.setScreenAndShow(new ModScreen(client.screen, TRANSLATE_TAB_INDEX)));
                        return 1;
                    })
                    .then(ClientCommands.argument("language", StringArgumentType.greedyString())
                            .suggests(languageSuggestions)
                            .executes(Killer560ModClient::setLanguageFromCommand)));

            SuggestionProvider<FabricClientCommandSource> cringeChannelSuggestions = (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.US);
                for (String alias : CringeFeature.channelAliases()) {
                    if (alias.startsWith(remaining)) {
                        builder.suggest(alias);
                    }
                }
                return builder.buildFuture();
            };

            dispatcher.register(ClientCommands.literal("cringe")
                    .executes(context -> {
                        if (blockedBySkyblockOnly()) {
                            return 0;
                        }
                        CringeFeature.sendRandom();
                        return 1;
                    })
                    .then(ClientCommands.argument("channel", StringArgumentType.word())
                            .suggests(cringeChannelSuggestions)
                            .executes(context -> {
                                if (blockedBySkyblockOnly()) {
                                    return 0;
                                }
                                String typed = StringArgumentType.getString(context, "channel");
                                String channel = CringeFeature.resolveChannel(typed);
                                if (channel == null) {
                                    ModOverlayMessage.show("§c[Killer560's Mod] Unknown chat channel: " + typed, 3000);
                                    return 0;
                                }
                                CringeFeature.sendRandom(channel);
                                return 1;
                            })));
        });
    }

    /** Builds the whole "/killer560 profile ..." subcommand tree as its own self-contained node -
     *  kept out of the main command registration chain (see the call site's comment) since nesting
     *  this many more levels of {@code .then(...)} inline would make an already deep chain very easy
     *  to mis-close by hand. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildProfileCommand() {
        return ClientCommands.literal("profile")
                .then(ClientCommands.literal("save")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    ModOverlayMessage.show(
                                            com.killer560.hub.profiles.ProfileManager.saveCurrentAsProfile(name).message(), 4000);
                                    return 1;
                                })))
                .then(ClientCommands.literal("load")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    ModOverlayMessage.show(
                                            com.killer560.hub.profiles.ProfileManager.applyProfile(name).message(), 5000);
                                    return 1;
                                })))
                .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    ModOverlayMessage.show(
                                            com.killer560.hub.profiles.ProfileManager.deleteProfile(name).message(), 3000);
                                    return 1;
                                })))
                .then(ClientCommands.literal("export")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    ModOverlayMessage.show(
                                            com.killer560.hub.profiles.ProfileManager.exportProfile(name).message(), 6000);
                                    return 1;
                                })))
                .then(ClientCommands.literal("import")
                        .then(ClientCommands.argument("file", StringArgumentType.string())
                                .then(ClientCommands.argument("newName", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String file = StringArgumentType.getString(context, "file");
                                            String newName = StringArgumentType.getString(context, "newName");
                                            ModOverlayMessage.show(
                                                    com.killer560.hub.profiles.ProfileManager.importProfile(file, newName).message(), 5000);
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("list")
                        .executes(context -> {
                            java.util.List<String> profiles = com.killer560.hub.profiles.ProfileManager.listProfiles();
                            String active = com.killer560.hub.profiles.ProfileManager.getActiveProfile();
                            ModOverlayMessage.show(profiles.isEmpty()
                                    ? "[Profiles] None saved yet."
                                    : "[Profiles] " + String.join(", ", profiles) + " (active: " + (active != null ? active : "none") + ")",
                                    5000);
                            return 1;
                        }));
    }

    /** Parses {@code /posmsg add <message...> <x> <y> <z> <radius>} - the message itself can contain
     *  spaces (e.g. "Simon Says"), so this takes the trailing 4 whitespace-separated tokens as the
     *  numbers and joins everything before them back into the message text, rather than using
     *  separate Brigadier arguments (which can't express "greedy string, but not the last 4 words"). */
    private static int posmsgAdd(CommandContext<FabricClientCommandSource> context) {
        if (blockedBySkyblockOnly()) {
            return 0;
        }
        String rest = StringArgumentType.getString(context, "rest").trim();
        String[] tokens = rest.split("\\s+");
        if (tokens.length < 5) {
            ModOverlayMessage.show("§c[Posmsg] Usage: /posmsg add <message> <x> <y> <z> <radius>", 4000);
            return 0;
        }
        try {
            double radius = Double.parseDouble(tokens[tokens.length - 1]);
            double z = Double.parseDouble(tokens[tokens.length - 2]);
            double y = Double.parseDouble(tokens[tokens.length - 3]);
            double x = Double.parseDouble(tokens[tokens.length - 4]);
            String message = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, tokens.length - 4));
            if (message.isBlank()) {
                ModOverlayMessage.show("§c[Posmsg] Usage: /posmsg add <message> <x> <y> <z> <radius>", 4000);
                return 0;
            }
            PosmsgFeature.addAndSend(message, x, y, z, radius);
            ModOverlayMessage.show("[Posmsg] Added and sent \"" + message + "\"", 3000);
            return 1;
        } catch (NumberFormatException e) {
            ModOverlayMessage.show("§c[Posmsg] The last 4 words must be numbers: x y z radius", 4000);
            return 0;
        }
    }

    /** Skyblock Only: feature commands (cringe, posmsg, mod chat, etherwarp add, termism) do nothing outside
     *  Skyblock / p3sim while the toggle is on. Settings/profile/menu commands are never blocked. */
    private static boolean blockedBySkyblockOnly() {
        if (com.killer560.hub.util.SkyblockGate.allows()) {
            return false;
        }
        ModOverlayMessage.show("§c[Skyblock Only] Mods are paused outside Skyblock / p3sim.", 3000);
        return true;
    }

    private static int setLanguageFromCommand(CommandContext<FabricClientCommandSource> context) {
        String typed = StringArgumentType.getString(context, "language");
        Minecraft client = Minecraft.getInstance();
        Optional<TranslateLanguages.Lang> match = TranslateLanguages.findByName(typed);
        if (match.isEmpty()) {
            ModOverlayMessage.show("§c[Killer560's Mod] Unknown language: " + typed, 3000);
            return 0;
        }
        TranslateConfig cfg = TranslateConfig.getInstance();
        cfg.setTargetLanguageCode(match.get().code());
        cfg.setEnabled(true);
        cfg.save();
        ModOverlayMessage.show("[Killer560's Mod] Chat Translate: " + match.get().name(), 3000);
        return 1;
    }

    private static void checkHudEditKeybind(Minecraft client) {
        int code = HudConfig.getInstance().getEditKeyCode();
        if (code < 0) {
            editKeyWasDown = false;
            return;
        }
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !editKeyWasDown && !(client.screen instanceof HudEditorScreen)) {
            client.setScreen(new HudEditorScreen(client.screen));
        }
        editKeyWasDown = down;
    }

    /** Same raw-keyboard-poll pattern as {@link #checkHudEditKeybind} - per killer560's explicit
     *  requirement that the Experiments emergency-cancel key "work even on the prevent keypress
     *  option," it's checked directly against hardware key state every tick rather than through any
     *  container screen's own key-event handling, which is exactly what that setting blocks. */
    private static boolean experimentsCancelKeyWasDown = false;

    private static void checkExperimentsCancelKeybind(Minecraft client) {
        int code = ExperimentsConfig.getInstance().getEmergencyCancelKeyCode();
        if (code < 0) {
            experimentsCancelKeyWasDown = false;
            return;
        }
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !experimentsCancelKeyWasDown) {
            ExperimentsFeature.emergencyCancel();
        }
        experimentsCancelKeyWasDown = down;
    }
}
