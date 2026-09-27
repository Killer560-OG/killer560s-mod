package com.killer560.hub.tooltipscroll;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Scrollable Tooltips" settings - see {@link TooltipScrollFeature}. Ships disabled by default.
 *  <p>
 *  Deliberately NOT gated on {@link com.killer560.hub.util.SkyblockGate}: a tooltip taller than the screen is
 *  a vanilla rendering limitation, not a Skyblock one (a shulker box full of named items, another mod's
 *  tooltip, a heavily-enchanted vanilla item all hit it too), and "Skyblock Only" exists to stop the mod
 *  ACTING outside Skyblock, not to un-fix a crop. Enchant Colours, which parses Hypixel lore text, IS gated.
 *  Raised as a judgement call for killer560 to confirm.
 *  <p>
 *  <b>2026-09-27 rework, killer560:</b> "remove hold a key to scroll." The modifier-key gate
 *  ({@code requireModifier}/{@code modifierKey}) is gone entirely - the wheel now always acts on a hovered
 *  tooltip, whether that means line-scrolling an overflowing one or nudging the position of one that already
 *  fits (see {@link TooltipScrollFeature}). An old config file's now-unused {@code requireModifier}/
 *  {@code modifierKey} keys are simply ignored by {@link ConfigJson}'s per-key reads - same precedent as
 *  {@code EnchantColorsConfig}'s 2026-09-20 tier rewrite - so nobody's file breaks and nothing needs a
 *  migration for a key that's being removed, not renamed. */
public final class TooltipScrollConfig {

    public static final int MIN_LINES_PER_SCROLL = 1;
    public static final int MAX_LINES_PER_SCROLL = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-tooltipscroll.json");

    private static TooltipScrollConfig instance;

    private boolean enabled = false;
    private int linesPerScroll = 3;
    private boolean invert = false;

    private TooltipScrollConfig() {
    }

    public static TooltipScrollConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TooltipScrollConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TooltipScrollConfig cfg = new TooltipScrollConfig();
            // Per-key reads (util/ConfigJson): one bad value must not reset the whole file to defaults.
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.linesPerScroll = clampLines(ConfigJson.getInt(obj, "linesPerScroll", 3));
            cfg.invert = ConfigJson.getBool(obj, "invert", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TooltipScrollConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("linesPerScroll", linesPerScroll);
            obj.addProperty("invert", invert);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampLines(int v) {
        return Math.max(MIN_LINES_PER_SCROLL, Math.min(MAX_LINES_PER_SCROLL, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLinesPerScroll() {
        return linesPerScroll;
    }

    public void setLinesPerScroll(int linesPerScroll) {
        this.linesPerScroll = clampLines(linesPerScroll);
    }

    public boolean isInvert() {
        return invert;
    }

    public void setInvert(boolean invert) {
        this.invert = invert;
    }
}
