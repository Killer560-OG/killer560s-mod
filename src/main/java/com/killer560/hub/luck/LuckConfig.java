package com.killer560.hub.luck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How Lucky Are You settings ({@code config/killer560/skyblock/luck/killer560smod-luck.json}). The records themselves
 * (attempts, expected, drops) are in the shared store ({@link LuckStore}), not here.
 */
public final class LuckConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-luck.json");

    /** Which Dungeon Reward Chest chance the screen uses: the wiki's "No Bonuses" or "S+ Max Bonuses" average. */
    public enum ChestOdds {
        BASE("No Bonuses"), MAX("S+ Max Bonuses");

        public final String label;

        ChestOdds(String label) {
            this.label = label;
        }
    }

    /** Magic Find for a slayer kill: the last value Hypixel printed on a drop line, or the one typed here. */
    public enum MagicFindMode {
        AUTO("Last Seen"), MANUAL("Manual");

        public final String label;

        MagicFindMode(String label) {
            this.label = label;
        }
    }

    /** Which mob deaths count as his kills: mobs he hit, any named mob within 8 blocks, or none. */
    public enum MobKills {
        HIT("Hit By Me"), NEARBY("Within 8 Blocks"), OFF("Off");

        public final String label;

        MobKills(String label) {
            this.label = label;
        }
    }

    public static final int MAX_MAGIC_FIND = 1500;

    private static LuckConfig instance;

    private boolean enabled = true;
    private boolean announce = true;
    private boolean inventoryDrops = true;
    private ChestOdds chestOdds = ChestOdds.BASE;
    private MagicFindMode magicFindMode = MagicFindMode.AUTO;
    private int manualMagicFind = 0;
    private MobKills mobKills = MobKills.HIT;
    /** Sends a summary of your luck to the mod users in your relay room. Off until he turns it on (opt-in). */
    private boolean shareMyLuck = false;

    private LuckConfig() {
    }

    public static LuckConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LuckConfig cfg = new LuckConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", true);
                cfg.announce = ConfigJson.getBool(o, "announce", true);
                cfg.inventoryDrops = ConfigJson.getBool(o, "inventoryDrops", true);
                cfg.chestOdds = ConfigJson.getEnum(o, "chestOdds", ChestOdds.class, ChestOdds.BASE);
                cfg.magicFindMode = ConfigJson.getEnum(o, "magicFindMode", MagicFindMode.class, MagicFindMode.AUTO);
                cfg.manualMagicFind = clampMf(ConfigJson.getInt(o, "manualMagicFind", 0));
                cfg.shareMyLuck = ConfigJson.getBool(o, "shareMyLuck", false);
                cfg.mobKills = ConfigJson.getEnum(o, "mobKills", MobKills.class, MobKills.HIT);
            } catch (Exception e) {
                ConfigJson.backupBroken(CONFIG_PATH);
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("announce", announce);
        o.addProperty("inventoryDrops", inventoryDrops);
        o.addProperty("chestOdds", chestOdds.name());
        o.addProperty("magicFindMode", magicFindMode.name());
        o.addProperty("manualMagicFind", manualMagicFind);
        o.addProperty("shareMyLuck", shareMyLuck);
        o.addProperty("mobKills", mobKills.name());
        ConfigJson.write(CONFIG_PATH, o);
    }

    private static int clampMf(int v) {
        return Math.max(0, Math.min(MAX_MAGIC_FIND, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isAnnounce() {
        return announce;
    }

    public void setAnnounce(boolean v) {
        announce = v;
    }

    public boolean isInventoryDrops() {
        return inventoryDrops;
    }

    public void setInventoryDrops(boolean v) {
        inventoryDrops = v;
    }

    public ChestOdds getChestOdds() {
        return chestOdds;
    }

    public void setChestOdds(ChestOdds v) {
        chestOdds = v == null ? ChestOdds.BASE : v;
    }

    public MagicFindMode getMagicFindMode() {
        return magicFindMode;
    }

    public void setMagicFindMode(MagicFindMode v) {
        magicFindMode = v == null ? MagicFindMode.AUTO : v;
    }

    public int getManualMagicFind() {
        return manualMagicFind;
    }

    public void setManualMagicFind(int v) {
        manualMagicFind = clampMf(v);
    }

    public boolean isShareMyLuck() {
        return shareMyLuck;
    }

    public void setShareMyLuck(boolean v) {
        shareMyLuck = v;
    }

    public MobKills getMobKills() {
        return mobKills;
    }

    public void setMobKills(MobKills v) {
        mobKills = v == null ? MobKills.HIT : v;
    }
}
