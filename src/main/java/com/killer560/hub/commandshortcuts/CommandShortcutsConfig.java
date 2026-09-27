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
 * never act in the world, so there is nothing to hide behind an opt-in.
 * <p>
 * <b>2026-09-27 regroup</b> (killer560: "clump them into groups like catacombs/mastermode as one toggle
 * for each floor. Same for kuudra.") - the settings tab used to show one toggle per
 * {@link CommandShortcutsFeature.Shortcut} (20 rows total). Now there is one toggle per
 * {@link CommandShortcutsFeature.Group} instead - each Catacombs floor's toggle (F1-F7) covers BOTH that
 * floor's normal Catacombs shortcut AND its Master Mode shortcut together (F0 has no Master Mode, so its
 * group is just itself); Kuudra already had exactly one shortcut per tier, so its grouping is unchanged
 * in substance, just moved onto the same {@code Group}-keyed storage as everything else. The individual
 * {@code /f1}, {@code /m1}, etc. commands themselves are untouched - only how they're enabled/displayed
 * changed; see {@link CommandShortcutsFeature.Group#members()}.
 * <p>
 * <b>Migrating old configs.</b> Earlier versions of this file stored one boolean per {@code Shortcut}
 * under {@code "shortcut.<name>"}. On load, if the new {@code "group.<name>"} key isn't present yet (first
 * load after this update), that group instead reads its OLD member keys rather than just resetting to the
 * default-true: a group comes back on only if EVERY one of its old member shortcuts was on - so if
 * killer560 had deliberately switched off just the Master Mode half of a floor (e.g. a real collision with
 * another mod), that "off" is honoured for the whole floor instead of silently re-enabling it. A group
 * with no old keys present at all (a config from before this feature even existed) just gets the default,
 * same as every other setting in this mod.
 */
public final class CommandShortcutsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-commandshortcuts.json");

    private static CommandShortcutsConfig instance;

    private boolean enabled = true;
    private final EnumMap<CommandShortcutsFeature.Group, Boolean> groups =
            new EnumMap<>(CommandShortcutsFeature.Group.class);

    private CommandShortcutsConfig() {
        for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
            groups.put(g, true);
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
            for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
                String groupKey = groupKey(g);
                if (obj.has(groupKey)) {
                    cfg.groups.put(g, ConfigJson.getBool(obj, groupKey, true));
                } else {
                    // No group key yet - fall back to this group's old per-shortcut keys (see class doc)
                    // instead of resetting straight to the default.
                    boolean anyOldKey = false;
                    boolean allOn = true;
                    for (CommandShortcutsFeature.Shortcut s : g.members()) {
                        String oldKey = legacyShortcutKey(s);
                        if (obj.has(oldKey)) {
                            anyOldKey = true;
                            allOn &= ConfigJson.getBool(obj, oldKey, true);
                        }
                    }
                    cfg.groups.put(g, anyOldKey ? allOn : true);
                }
            }
        } catch (Exception ignored) {
            // Unreadable file: keep whatever the per-group readers managed, everything else stays at its default.
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
                obj.addProperty(groupKey(g), groups.getOrDefault(g, true));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String groupKey(CommandShortcutsFeature.Group g) {
        return "group." + g.name().toLowerCase(Locale.US);
    }

    /** Pre-2026-09-27 config key for an individual shortcut - read once during migration, never written
     *  again after the first {@link #save()} on the new {@code group.*} keys. */
    private static String legacyShortcutKey(CommandShortcutsFeature.Shortcut s) {
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

    public boolean isGroupOn(CommandShortcutsFeature.Group g) {
        return g != null && Boolean.TRUE.equals(groups.get(g));
    }

    public void setGroupOn(CommandShortcutsFeature.Group g, boolean value) {
        if (g != null) {
            groups.put(g, value);
        }
    }

    /** Whether the given shortcut's owning {@link CommandShortcutsFeature.Group} toggle is on - every
     *  real command still checks this exact method, {@link CommandShortcutsFeature#register()} and
     *  {@code exec()} are otherwise unchanged by the regroup. */
    public boolean isOn(CommandShortcutsFeature.Shortcut s) {
        return isGroupOn(CommandShortcutsFeature.Group.forShortcut(s));
    }
}
