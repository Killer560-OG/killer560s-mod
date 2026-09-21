package com.killer560.hub.interop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig;
import com.killer560.hub.terminals.TerminalSolverConfig;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One chat warning per game session when another installed mod is doing the same job as one of ours and
 * will override it. Checked once, a few seconds after the first join to Hypixel or p3sim, so it lands after
 * the join spam instead of inside it. Reads the other mods' own config files; never writes them.
 * <ul>
 * <li>Skyblocker's terminal solver drawing over ours (settled 2026-09-21: warn once on join).</li>
 * <li>Devonian's Party Finder Overview rewriting the party items' lore every tick, which replaces our style
 *     and leaves our parser nothing it recognises - found 2026-09-21 as the cause of "the current style does
 *     not work".</li>
 * </ul>
 */
public final class ModConflictWarnings {

    private static final int DELAY_TICKS = 100;
    private static boolean checked = false;
    private static int countdown = -1;

    private ModConflictWarnings() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!checked) {
                countdown = DELAY_TICKS;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(ModConflictWarnings::tick);
    }

    private static void tick(Minecraft client) {
        if (checked || countdown < 0 || --countdown > 0) {
            return;
        }
        countdown = -1;
        if (client.player == null || !CheatUtils.isOnDungeonServer(client)) {
            return;
        }
        checked = true;
        try {
            if (DetectedMods.isLoaded(DetectedMods.SKYBLOCKER) && TerminalSolverConfig.getInstance().isEnabled()
                    && skyblockerTerminalSolverOn()) {
                ModChat.send("Conflicts", Component.literal(
                        "§eSkyblocker's terminal solver is also on and will draw over ours. Turn one of them off."));
            }
            PartyFinderOverlayConfig pf = PartyFinderOverlayConfig.getInstance();
            if (DetectedMods.isLoaded(DetectedMods.DEVONIAN) && pf.isEnabled() && pf.isTooltip()
                    && devonianPartyFinderOverviewOn()) {
                ModChat.send("Conflicts", Component.literal(
                        "§eDevonian's Party Finder Overview is on and replaces our Dungeon Queue style. Turn one of them off."));
            }
        } catch (RuntimeException ignored) {
            // A warning must never break joining.
        }
    }

    private static boolean skyblockerTerminalSolverOn() {
        JsonObject root = readJson(FabricLoader.getInstance().getConfigDir().resolve("skyblocker.json"));
        JsonObject terminals = child(child(root, "dungeons"), "terminals");
        if (terminals == null) {
            return false;
        }
        for (String key : new String[]{"solveColor", "solveSameColor", "solveOrder", "solveStartsWith"}) {
            if (bool(terminals, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean devonianPartyFinderOverviewOn() {
        JsonObject root = readJson(FabricLoader.getInstance().getConfigDir().resolve("devonianConfig.json"));
        return bool(child(root, "config"), "partyFinderOverview");
    }

    private static JsonObject readJson(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            JsonElement e = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject child(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return null;
        }
        return obj.getAsJsonObject(key);
    }

    private static boolean bool(JsonObject obj, String key) {
        try {
            return obj != null && obj.has(key) && obj.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
