package com.killer560.hub.dungeonclass;

import java.util.Map;

/** THROWAWAY STUB - declared API (static lookups, instance save()). */
public final class ClassOverrides {
    private static final ClassOverrides I = new ClassOverrides();
    public static ClassOverrides getInstance() { return I; }
    public static void load() {}
    public void save() {}
    public static DungeonClass classOf(String ign, DungeonClass detected) { return detected; }
    public static void set(String ign, DungeonClass cls) {}
    public static void clear(String ign) {}
    public static Map<String, DungeonClass> all() { return Map.of(); }
}
