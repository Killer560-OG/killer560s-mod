package com.killer560.hub.autokick;

import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/**
 * {@code /autokick populate [player]} - the command half of tonight's request (2026-09-27): "the auto kick
 * time the player wants to let into the party they should be able to choose, it should populate them from
 * someone else via their api." Bare {@code /autokick populate} looks up killer560's own dungeon times;
 * {@code /autokick populate <name>} looks up anyone else's (public API data - no different from opening
 * that player in the Profile Viewer). See {@link AutoKickApi} for exactly what field is read and why, and
 * for why a floor he already set is never overwritten.
 * <p>
 * This command only ever changes the per-floor numbers in {@link AutoKickConfig}. It never flips the
 * master {@link AutoKickConfig#isEnabledRaw() Auto Kick toggle} itself and never changes
 * {@link AutoKickConfig#getMode()} - populating times for a floor he hasn't looked at yet must not be how
 * Auto Kick quietly turns itself on.
 */
public final class AutoKickCommands {

    private AutoKickCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}, alongside {@code AutoKickFeature.register()}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("autokick")
                        .then(ClientCommands.literal("populate")
                                .executes(context -> populate(""))
                                .then(ClientCommands.argument("player", StringArgumentType.greedyString())
                                        .executes(context -> populate(StringArgumentType.getString(context, "player")))))));
    }

    private static int populate(String player) {
        String trimmed = player == null ? "" : player.trim();
        ModChat.send("Auto Kick", ModChat.text("Looking up "),
                ModChat.value(trimmed.isEmpty() ? "your" : trimmed + "'s"),
                ModChat.text(" dungeon clear times..."));
        AutoKickApi.populate(trimmed).whenComplete((result, error) ->
                Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        ModChat.send("Auto Kick", ModChat.bad(ProfileViewerApi.messageFor(error)));
                        return;
                    }
                    ModChat.send("Auto Kick",
                            ModChat.value(result.playerName()), ModChat.text(": set "),
                            ModChat.value(String.valueOf(result.floorsSet())),
                            ModChat.text(" floor target(s) from their fastest recorded clear time. "),
                            ModChat.dim(result.floorsNoData() + " floor(s) had no recorded time (still 0/off), "
                                    + result.floorsAlreadySet() + " already had a target and were left alone."));
                }));
        return 1;
    }
}
