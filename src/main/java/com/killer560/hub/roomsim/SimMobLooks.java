package com.killer560.hub.roomsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.autoroutes.RouteCoords;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.ModLog;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What each starred mob looked like on Hypixel, per room and room-relative block (simblood-1110, killer560 2026-11-10: "for
 * regular mobs i only want starred to spawn"). WHERE a room's starred mobs stand is Blood Rush Helper's spawn spot table
 * ({@code bloodrush.BloodRushSpots}); this adds WHAT stood there: entity type, the star tag's name, the six equipment slots
 * (item, skull skin, dye, glint), a player mob's skin and its facing, all turned into the room's own frame.
 *
 * <p>Recorded passively, on Hypixel only, at the moment Blood Rush Helper's Learn Spawn Spots confirms a starred mob standing
 * on its spot ({@link #record}); kept in the shared record store ({@link RecordCatalog#SIM_MOB_LOOKS}), one entry per room and
 * block. The sim's Real Mob Looks ({@link SimMenuPrefs#realMobs}) spawns a recorded look on its spot ({@link SimMobs#spawnLook});
 * a spot with none keeps the plain dummy. Bounded: {@link #MAX_PER_ROOM} a room, {@link #MAX} in all.
 */
public final class SimMobLooks {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    /** A spot this close (each axis) is the same spot (Blood Rush's own merge distance). */
    static final int MERGE = 2;
    static final int MAX_PER_ROOM = 48;
    static final int MAX = 6000;
    private static final long SAVE_EVERY_MS = 10_000L;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private static final Object LOCK = new Object();
    /** room -> entries, each {room, x, y, z, type, name, skin, yaw, gear{slot: {item, tex, color, glint}}, seen}. */
    private static final Map<String, List<JsonObject>> ROOMS = new HashMap<>();
    private static boolean loaded;
    private static boolean loading;
    private static boolean dirty;
    private static long lastSave;
    private static int total;

    /** Test hook: record in the Dungeon Sim too (never on in play). */
    public static volatile boolean recordInSimForTest;

    private SimMobLooks() {
    }

    /** A recorded look: the entity type id, the tag's name, a player's skin (or null), the room-relative yaw, the gear. */
    public record Look(String type, String name, String skin, float yaw, Map<EquipmentSlot, ItemStack> gear) {
    }

    // ---- recording (client thread, Hypixel) -------------------------------------------------------------------------

    /**
     * A starred mob confirmed standing on its spawn spot ({@code BloodRushHelperFeature.confirmLearning}): its look saved for
     * that room-relative block. Never in the sim (whose mobs it placed itself), unless a test asks.
     */
    public static void record(String room, RouteCoords.Frame frame, Entity mob, Entity tag) {
        if (room == null || frame == null || !(mob instanceof LivingEntity living) || tag == null
                || (SimState.isActive() && !recordInSimForTest)) {
            return;
        }
        String name = starName(tag.getName().getString());
        if (name == null) {
            return;
        }
        BlockPos rel = RouteCoords.toRelativeBlock(frame, BlockPos.containing(mob.position()));
        JsonObject e = new JsonObject();
        e.addProperty("room", room);
        e.addProperty("x", rel.getX());
        e.addProperty("y", rel.getY());
        e.addProperty("z", rel.getZ());
        e.addProperty("type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType())));
        e.addProperty("name", name);
        if (mob instanceof Player p) {
            String skin = texture(p.getGameProfile());
            if (skin != null) {
                e.addProperty("skin", skin);
            }
        }
        e.addProperty("yaw", Math.round(RouteCoords.toRelativeYaw(frame, mob.getYRot())));
        JsonObject gear = new JsonObject();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = living.getItemBySlot(slot);
            if (!s.isEmpty()) {
                gear.add(slot.getName(), item(s));
            }
        }
        e.add("gear", gear);
        e.addProperty("seen", 1);
        boolean added;
        synchronized (LOCK) {
            if (!loaded && !loading) {
                loading = true;
                SharedWriter.submit(SimMobLooks::loadNow);
            }
            added = put(e);
            dirty = true;
        }
        if (added) {
            LOGGER.info("[SimMobLooks] recorded {} ({}) in {} at room {},{},{}", name, e.get("type").getAsString(), room,
                    rel.getX(), rel.getY(), rel.getZ());
        }
        saveIfDirty(false);
    }

    /** The star tag's mob name: " ✯ Zombie Commander 1.2M❤" -> "Zombie Commander"; null when it is no star tag. */
    static String starName(String raw) {
        if (raw == null) return null;
        String s = ChatFormatting.stripFormatting(raw);
        int star = s == null ? -1 : s.indexOf('✯');
        int heart = star < 0 ? -1 : s.lastIndexOf('❤');
        if (heart <= star) return null;
        String mid = s.substring(star + 1, heart).trim();
        int sp = mid.lastIndexOf(' ');
        // The health figure ("1.2M", "400k/1.2M") is the last word: drop it when it starts with a digit.
        if (sp > 0 && Character.isDigit(mid.charAt(sp + 1))) {
            mid = mid.substring(0, sp).trim();
        }
        return mid.isEmpty() || mid.length() > 48 ? null : mid;
    }

    private static JsonObject item(ItemStack s) {
        JsonObject o = new JsonObject();
        o.addProperty("item", String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem())));
        ResolvableProfile prof = s.get(DataComponents.PROFILE);
        String tex = prof == null ? null : texture(prof.partialProfile());
        if (tex != null) {
            o.addProperty("tex", tex);
        }
        DyedItemColor dye = s.get(DataComponents.DYED_COLOR);
        if (dye != null) {
            o.addProperty("color", dye.rgb());
        }
        if (s.hasFoil()) {
            o.addProperty("glint", true);
        }
        return o;
    }

    private static String texture(GameProfile profile) {
        if (profile == null) return null;
        Iterator<Property> it = profile.properties().get("textures").iterator();
        return it.hasNext() ? it.next().value() : null;
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

    /** Saves at most every 10 s ({@code now}: whatever the time, as the run ends). The write runs on the shared writer. */
    public static void saveIfDirty(boolean now) {
        JsonObject snap;
        synchronized (LOCK) {
            long t = System.currentTimeMillis();
            if (!dirty || (!now && t - lastSave < SAVE_EVERY_MS)) {
                return;
            }
            dirty = false;
            lastSave = t;
            JsonArray all = new JsonArray();
            for (List<JsonObject> l : ROOMS.values()) {
                for (JsonObject o : l) {
                    all.add(o.deepCopy());
                }
            }
            snap = new JsonObject();
            snap.addProperty("version", 1);
            snap.add("mobs", all);
        }
        SharedWriter.submit(() -> {
            JsonObject merged = SharedRecords.save(SharedRecords.file(RecordCatalog.SIM_MOB_LOOKS), snap);
            synchronized (LOCK) {
                install(merged);
            }
        });
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

    /** The look recorded nearest {@code rel} (within {@link #MERGE} each axis) in {@code room}, or null. */
    static Look lookAt(String room, BlockPos rel) {
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
                int dx = Math.abs(o.get("x").getAsInt() - rel.getX()), dy = Math.abs(o.get("y").getAsInt() - rel.getY());
                int dz = Math.abs(o.get("z").getAsInt() - rel.getZ());
                if (dx <= MERGE && dy <= MERGE && dz <= MERGE && dx + dy + dz < bestD) {
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
            dirty = false;
            loaded = !reload;
            loading = false;
        }
    }

    static String describe(Look l) {
        return String.format(Locale.ROOT, "%s %s gear %d%s", l.type(), l.name(), l.gear().size(), l.skin() != null ? " +skin" : "");
    }
}
