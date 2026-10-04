package com.killer560.hub.roomsim;

import java.util.HashMap;
import java.util.Map;

/** Offline stand-in: the room database's type and shape per name, filled by LayoutSim. */
public final class SimFloorGen {
    public static final Map<String, String> TYPE = new HashMap<>();
    public static final Map<String, String> SHAPE = new HashMap<>();

    public static String shapeOf(String name) {
        return SHAPE.get(name);
    }

    public static String typeOf(String name) {
        String t = TYPE.get(name);
        return t == null ? "NORMAL" : t;
    }
}
