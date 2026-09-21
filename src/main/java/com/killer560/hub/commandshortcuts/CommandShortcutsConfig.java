package com.killer560.hub.commandshortcuts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;

/**
 * Persisted Command Shortcuts settings (killer560's item 8.3: "/f7, /m7, /infernal, every cata and Kuudra
 * tier"). Unlike most toggle sets in this mod, every shortcut here defaults ON - these are pure client-side
 * chat aliases that just re-send the real Hypixel join command (see {@link CommandShortcutsFeature}), they
 * never act in the world, so there is nothing to hide behind an opt-in. The per-shortcut toggles exist purely
 * so a single alias that turns out to collide with another mod's (or a future vanilla) command of the same
 * name can be switched off without losing every other one - see {@link CommandShortcutsFeature} class doc for
 * why turning one off only takes effect the next time the client (re)builds its command dispatcher (i.e. next
 * world join), not instantly.
 * <p>
 * Same {@code EnumMap} + one JSON key per entry pattern as {@code partycommands.PartyCommandsConfig}, so a
 * hand-edited or renamed key only resets that one shortcut instead of the whole file.
 */
public final class CommandShortcutsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-commandshortcuts.json");

    private static CommandShortcutsConfig instance;

    private boolean enabled = true;
    private final EnumMap<CommandShortcutsFeature.Shortcut, Boolean> shortcuts =
            new EnumMap<>(CommandShortcutsFeature.Shortcut.class);

    private CommandShortcutsConfig() {
        for (CommandShortcutsFeature.Shortcut s : CommandShortcutsFeature.Shortcut.values()) {
            shortcuts.put(s, true);
        }
    }

    public static CommandShortcutsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new CommandShortcutsConfig();
            return;
        }
        CommandShortcutsConfig cfg = new CommandShortcutsConfig();
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            for (CommandShortcutsFeature.Shortcut s : CommandShortcutsFeature.Shortcut.values()) {
                cfg.shortcuts.put(s, ConfigJson.getBool(obj, key(s), true));
            }
        } catch (Exception ignored) {
            // Unreadable file: keep whatever the per-key readers managed, everything else stays at its default.
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            for (CommandShortcutsFeature.Shortcut s : CommandShortcutsFeature.Shortcut.values()) {
                obj.addProperty(key(s), shortcuts.getOrDefault(s, true));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String key(CommandShortcutsFeature.Shortcut s) {
        return "shortcut." + s.name().toLowerCase(Locale.US);
    }

    /** Master switch. Raw (not gated by {@code SkyblockGate}) - registration only cares whether the player
     *  wants the aliases to exist at all; {@link CommandShortcutsFeature} checks the Skyblock/p3sim gate
     *  separately, at run time, so the settings tab always shows what is actually saved. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOn(CommandShortcutsFeature.Shortcut s) {
        return s != null && Boolean.TRUE.equals(shortcuts.get(s));
    }

    public void setOn(CommandShortcutsFeature.Shortcut s, boolean value) {
        if (s != null) {
            shortcuts.put(s, value);
        }
    }
}
