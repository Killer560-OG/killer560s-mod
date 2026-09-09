package com.killer560.hub;

import com.killer560.hub.accounts.HypixelJoinWatcher;
import com.killer560.hub.autojoinskyblock.AutoJoinSkyblockFeature;
import com.killer560.hub.automeow.AutoMeowFeature;
import com.killer560.hub.compat.ModCompatibility;
import com.killer560.hub.cringe.CringeFeature;
import com.killer560.hub.dvd.DvdFeature;
import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.experiments.ExperimentsFeature;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.tab.TranslateTab;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.jumpscare.JumpscareFeature;
import com.killer560.hub.leapmessage.LeapMessageFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.rngmeter.MagicFindTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngMeterOverlay;
import com.killer560.hub.spotify.SpotifyLyricsFeature;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.termism.TermismMenuScreen;
import com.killer560.hub.translate.TranslateConfig;
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
        AutoMeowFeature.register();
        ExperimentsFeature.register();
        JumpscareFeature.register();
        StorageOverlayFeature.register();
        DungeonState.register();

        ClientTickEvents.END_CLIENT_TICK.register(Killer560ModClient::checkHudEditKeybind);
        ClientTickEvents.END_CLIENT_TICK.register(Killer560ModClient::checkExperimentsCancelKeybind);
        ClientTickEvents.END_CLIENT_TICK.register(WindowModeFeature::tickApplyOnce);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("killer560")
                        .executes(context -> {
                            LOGGER.info("/killer560 executed - command handler running");
                            Minecraft client = Minecraft.getInstance();
                            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                            ModOverlayMessage.show("§b[Killer560's Mod] Opening menu...", 3000);
                            // Deferred via client.execute (matches the macro mod's own proven-working
                            // /start command pattern) rather than calling setScreen synchronously from
                            // inside command execution, in case that context matters.
                            client.execute(() -> {
                                LOGGER.info("Deferred setScreenAndShow(ModScreen) running now");
                                client.setScreenAndShow(new ModScreen(client.screen));
                            });
                            return 1;
                        })));

        // Termism: killer560's own terminal-practice request (2026-09-09) - "/termism or... through the
        // settings" - this is the command half, TermismTab (Dungeon folder) is the settings half.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("termism")
                        .executes(context -> {
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
                        CringeFeature.sendRandom();
                        return 1;
                    })
                    .then(ClientCommands.argument("channel", StringArgumentType.word())
                            .suggests(cringeChannelSuggestions)
                            .executes(context -> {
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
        ModOverlayMessage.show("§b[Killer560's Mod] Chat Translate: §e" + match.get().name(), 3000);
        return 1;
    }

    private static void checkHudEditKeybind(Minecraft client) {
        int code = HudConfig.getInstance().getEditKeyCode();
        if (code < 0) {
            editKeyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), code);
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
        boolean down = InputConstants.isKeyDown(client.getWindow(), code);
        if (down && !experimentsCancelKeyWasDown) {
            ExperimentsFeature.emergencyCancel();
        }
        experimentsCancelKeyWasDown = down;
    }
}
