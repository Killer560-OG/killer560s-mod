package com.killer560.hub.dungeonqol;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/** Settings of the small dungeon extras: Item Quality tooltip, Iced Mob Boxes, Room Name HUD (featE-1108), Block Ender Pearls In Boss.
 *  All default OFF except the pearl block; the feature getters also need Skyblock, the tabs read the {@code *Raw} ones. */
public final class DungeonQolConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-dungeonqol.json");
    private static DungeonQolConfig instance;

    private boolean itemQuality = false;
    private boolean icedBoxes = false;
    private boolean roomHud = false;
    private boolean blockBossPearls = true;
    private int icedColor = 0xFF55FFFF;

    private static final BoolFields<DungeonQolConfig> BOOLS = new BoolFields<DungeonQolConfig>()
            .add("itemQuality", false, c -> c.itemQuality, (c, v) -> c.itemQuality = v)
            .add("icedBoxes", false, c -> c.icedBoxes, (c, v) -> c.icedBoxes = v)
            .add("roomHud", false, c -> c.roomHud, (c, v) -> c.roomHud = v)
            .add("blockBossPearls", true, c -> c.blockBossPearls, (c, v) -> c.blockBossPearls = v);

    private DungeonQolConfig() {
    }

    public static DungeonQolConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DungeonQolConfig cfg = new DungeonQolConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
            cfg.icedColor = ConfigJson.getInt(obj, "icedColor", cfg.icedColor);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("icedColor", icedColor);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isItemQuality() { return itemQuality && SkyblockGate.allows(); }
    public boolean isItemQualityRaw() { return itemQuality; }
    public void setItemQuality(boolean v) { itemQuality = v; }

    public boolean isIcedBoxes() { return icedBoxes && SkyblockGate.allows(); }
    public boolean isIcedBoxesRaw() { return icedBoxes; }
    public void setIcedBoxes(boolean v) { icedBoxes = v; }

    public boolean isRoomHud() { return roomHud; }
    public void setRoomHud(boolean v) { roomHud = v; }

    public boolean isBlockBossPearls() { return blockBossPearls; }
    public void setBlockBossPearls(boolean v) { blockBossPearls = v; }

    public int getIcedColor() { return icedColor; }
    public void setIcedColor(int argb) { icedColor = argb; }
}
