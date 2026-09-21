package com.killer560.hub.partydata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.interop.InteropConfig;
import com.killer560.hub.modchat.ModChatConfig;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted "Share Dungeon Data With Party" setting - see {@link PartyDataFeature}.
 * <p>
 * <b>Default, by explicit request (2026-09-21):</b> unlike every other new feature in this mod (which ships
 * OFF until confirmed live), this one defaults ON <i>only if</i> the two features it is otherwise inert
 * without are themselves already on: Party Interop's "Use Mod Relay" feed ({@link InteropConfig#isEnabled()}
 * + {@link InteropConfig#isRelayData()}, the consuming side) and Mod Chat ({@link ModChatConfig#isEnabled()},
 * the only thing that currently drives {@link com.killer560.hub.relay.RelayClient} to actually connect - see
 * {@link PartyDataFeature}'s class doc). If either is off, sharing would silently do nothing anyway (no
 * connection, or nobody able to accept what we send), so defaulting it on then would just be a toggle that
 * lies about doing something. This default is computed ONCE, the first time this config file is created;
 * flipping either dependency off later does not un-set it - the player already made a choice by then.
 */
public final class PartyDataConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-partydata.json");

    private static PartyDataConfig instance;

    private boolean shareEnabled;

    private PartyDataConfig() {
    }

    public static PartyDataConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        boolean dependenciesOn = InteropConfig.getInstance().isEnabledRaw()
                && InteropConfig.getInstance().isRelayData()
                && ModChatConfig.getInstance().isEnabled();
        if (!Files.exists(CONFIG_PATH)) {
            PartyDataConfig cfg = new PartyDataConfig();
            cfg.shareEnabled = dependenciesOn;
            instance = cfg;
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            PartyDataConfig cfg = new PartyDataConfig();
            // Same computed default for a config that predates this key (an older build's file, or one
            // written before "shareEnabled" existed) as for a brand-new file - see the class doc.
            cfg.shareEnabled = ConfigJson.getBool(obj, "shareEnabled", dependenciesOn);
            instance = cfg;
        } catch (Exception e) {
            PartyDataConfig cfg = new PartyDataConfig();
            cfg.shareEnabled = dependenciesOn;
            instance = cfg;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("shareEnabled", shareEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Publish OUR observed dungeon facts to the party over the relay. Consuming others' facts is controlled
     *  separately by {@link InteropConfig#isRelayData()} - this only gates the send direction. */
    public boolean isShareEnabled() {
        return shareEnabled;
    }

    public void setShareEnabled(boolean shareEnabled) {
        this.shareEnabled = shareEnabled;
    }
}
