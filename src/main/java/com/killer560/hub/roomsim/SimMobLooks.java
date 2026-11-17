package com.killer560.hub.roomsim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.util.ModLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What each starred mob looked like on Hypixel, per room and room-relative block (simblood-1110, killer560 2026-11-10: "for
 * regular mobs i only want starred to spawn"). WHERE a room's starred mobs stand is Blood Rush Helper's spawn boxes
 * ({@code bloodrush.BloodRushData}); this adds WHAT stood in one: entity type, the star tag's name, the six equipment slots
 * (item, skull skin, dye, glint), a player mob's skin and its facing, all turned into the room's own frame.
 *
 * <p>Read-only since brboxes-only-1117 (the passive recorder went with Learn Spawn Spots): looks recorded earlier stay in the shared
 * record store ({@link RecordCatalog#SIM_MOB_LOOKS}), one entry per room and block. The sim's Real Mob Looks ({@link SimMenuPrefs#realMobs}) spawns a recorded look on its spot ({@link SimMobs#spawnLook});
 * a spot with none keeps the plain dummy. Bounded: {@link #MAX_PER_ROOM} a room, {@link #MAX} in all.
 */
public final class SimMobLooks {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    /** A spot this close (each axis) is the same spot (Blood Rush's own merge distance). */
    static final int MERGE = 2;
    static final int MAX_PER_ROOM = 48;
    static final int MAX = 6000;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private static final Object LOCK = new Object();
    /** room -> entries, each {room, x, y, z, type, name, skin, yaw, gear{slot: {item, tex, color, glint}}, seen}. */
    private static final Map<String, List<JsonObject>> ROOMS = new HashMap<>();
    private static boolean loaded;
    private static boolean loading;
    private static int total;

    private SimMobLooks() {
    }

    /** A recorded look: the entity type id, the tag's name, a player's skin (or null), the room-relative yaw, the gear. */
    public record Look(String type, String name, String skin, float yaw, Map<EquipmentSlot, ItemStack> gear) {
    }

    /** Adds or updates (the newest look wins, seen counts up); false when it only updated or the store is full. */
    private static boolean put(JsonObject e) {
        String room = e.get("room").getAsString();
        List<JsonObject> list = ROOMS.computeIfAbsent(room, k -> new ArrayList<>());
        int x = e.get("x").getAsInt(), y = e.get("y").getAsInt(), z = e.get("z").getAsInt();
        for (JsonObject o : list) {
            if (Math.abs(o.get("x").getAsInt() - x) <= MERGE && Math.abs(o.get("y").getAsInt() - y) <= MERGE
                    && Math.abs(o.get("z").getAsInt() - z) <= MERGE) {
                int seen = o.has("seen") ? o.get("seen").getAsInt() : 1;
                for (String k : List.of("type", "name", "skin", "yaw", "gear")) {
                    o.remove(k);
                    if (e.has(k)) o.add(k, e.get(k));
                }
                o.addProperty("seen", seen + 1);
                return false;
            }
        }
        if (list.size() >= MAX_PER_ROOM || total >= MAX) {
            return false;
        }
        list.add(e);
        total++;
        return true;
    }

    // ---- reading (the sim, any thread but the render thread) ---------------------------------------------------------

    /** Reads the store once (the server thread at GO, or the shared writer). */
    static void loadNow() {
        JsonObject root = SharedRecords.load(SharedRecords.file(RecordCatalog.SIM_MOB_LOOKS));
        synchronized (LOCK) {
            install(root);
            loaded = true;
            loading = false;
        }
    }

    /** Adds every disk entry this memory lacks (memory wins on a clash: it is newer or equal). */
    private static void install(JsonObject root) {
        JsonElement mobs = root == null ? null : root.get("mobs");
        if (mobs == null || !mobs.isJsonArray()) return;
        for (JsonElement el : mobs.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("room") || !o.has("x") || !o.has("y") || !o.has("z") || !o.has("type")) continue;
            String room = o.get("room").getAsString();
            List<JsonObject> list = ROOMS.computeIfAbsent(room, k -> new ArrayList<>());
            boolean known = false;
            for (JsonObject m : list) {
                known |= m.get("x").getAsInt() == o.get("x").getAsInt() && m.get("y").getAsInt() == o.get("y").getAsInt()
                        && m.get("z").getAsInt() == o.get("z").getAsInt();
            }
            if (!known && list.size() < MAX_PER_ROOM && total < MAX) {
                list.add(o);
                total++;
            }
        }
    }

    /** The look recorded nearest the middle of {@code box} (room-relative x1,y1,z1,x2,y2,z2; a look inside it or within {@link #MERGE}
     *  of it on each axis counts) in {@code room}, or null. */
    static Look lookIn(String room, int[] box) {
        int cx = (box[0] + box[3]) / 2;
        int cy = (box[1] + box[4]) / 2;
        int cz = (box[2] + box[5]) / 2;
        JsonObject best = null;
        synchronized (LOCK) {
            if (!loaded) {
                loading = true;
            }
        }
        if (!isLoaded()) {
            loadNow();
        }
        synchronized (LOCK) {
            List<JsonObject> list = ROOMS.get(room);
            int bestD = Integer.MAX_VALUE;
            for (JsonObject o : list == null ? List.<JsonObject>of() : list) {
                int x = o.get("x").getAsInt(), y = o.get("y").getAsInt(), z = o.get("z").getAsInt();
                int dx = Math.abs(x - cx), dy = Math.abs(y - cy), dz = Math.abs(z - cz);
                if (x >= box[0] - MERGE && x <= box[3] + MERGE && y >= box[1] - MERGE && y <= box[4] + MERGE
                        && z >= box[2] - MERGE && z <= box[5] + MERGE && dx + dy + dz < bestD) {
                    bestD = dx + dy + dz;
                    best = o;
                }
            }
            return best == null ? null : parse(best);
        }
    }

    private static boolean isLoaded() {
        synchronized (LOCK) {
            return loaded;
        }
    }

    private static Look parse(JsonObject o) {
        Map<EquipmentSlot, ItemStack> gear = new HashMap<>();
        JsonObject g = o.has("gear") && o.get("gear").isJsonObject() ? o.getAsJsonObject("gear") : new JsonObject();
        for (EquipmentSlot slot : SLOTS) {
            if (g.has(slot.getName()) && g.get(slot.getName()).isJsonObject()) {
                ItemStack s = stack(g.getAsJsonObject(slot.getName()));
                if (!s.isEmpty()) gear.put(slot, s);
            }
        }
        return new Look(o.get("type").getAsString(), o.has("name") ? o.get("name").getAsString() : "Mob",
                o.has("skin") ? o.get("skin").getAsString() : null, o.has("yaw") ? o.get("yaw").getAsFloat() : 0f, gear);
    }

    private static ItemStack stack(JsonObject o) {
        Identifier id = o.has("item") ? Identifier.tryParse(o.get("item").getAsString()) : null;
        var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(item);
        if (o.has("tex")) s.set(DataComponents.PROFILE, com.killer560.hub.profileviewer.item.LegacyItems.skullProfile(o.get("tex").getAsString()));
        if (o.has("color")) s.set(DataComponents.DYED_COLOR, new DyedItemColor(o.get("color").getAsInt()));
        if (o.has("glint")) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    /** A player head wearing {@code skin} (a player-model mob's look on a sim zombie). */
    static ItemStack skinHead(String skin) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, com.killer560.hub.profileviewer.item.LegacyItems.skullProfile(skin));
        return s;
    }

    /** Rooms with at least one recorded look, and how many, for the log, the tab and tests. */
    public static Map<String, Integer> counts() {
        if (!isLoaded()) loadNow();
        Map<String, Integer> out = new HashMap<>();
        synchronized (LOCK) {
            ROOMS.forEach((k, v) -> {
                if (!v.isEmpty()) out.put(k, v.size());
            });
        }
        return out;
    }

    /** Test hook: one look for {@code room} at a room-relative block (memory only, never saved). */
    public static void putForTest(String room, int x, int y, int z, String type, String name, String headTexture, float yaw) {
        JsonObject e = new JsonObject();
        e.addProperty("room", room);
        e.addProperty("x", x);
        e.addProperty("y", y);
        e.addProperty("z", z);
        e.addProperty("type", type);
        e.addProperty("name", name);
        e.addProperty("yaw", yaw);
        JsonObject gear = new JsonObject();
        if (headTexture != null) {
            JsonObject h = new JsonObject();
            h.addProperty("item", "minecraft:player_head");
            h.addProperty("tex", headTexture);
            gear.add(EquipmentSlot.HEAD.getName(), h);
        }
        JsonObject chest = new JsonObject();
        chest.addProperty("item", "minecraft:leather_chestplate");
        chest.addProperty("color", 0x3366FF);
        gear.add(EquipmentSlot.CHEST.getName(), chest);
        e.add("gear", gear);
        e.addProperty("seen", 1);
        synchronized (LOCK) {
            loaded = true;
            put(e);
        }
    }

    /** Test hook: forgets memory ({@code reload}: and reads the store again on next use). Never touches the disk. */
    public static void resetForTest(boolean reload) {
        synchronized (LOCK) {
            ROOMS.clear();
            total = 0;
            loaded = !reload;
            loading = false;
        }
    }

    static String describe(Look l) {
        return String.format(Locale.ROOT, "%s %s gear %d%s", l.type(), l.name(), l.gear().size(), l.skin() != null ? " +skin" : "");
    }
}
