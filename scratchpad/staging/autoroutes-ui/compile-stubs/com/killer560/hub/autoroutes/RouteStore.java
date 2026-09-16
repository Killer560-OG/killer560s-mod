package com.killer560.hub.autoroutes;
import java.nio.file.Path;
import java.util.List;
/** COMPILE-CHECK STUB ONLY - not for integration. */
public final class RouteStore {
    private static final RouteStore I = new RouteStore();
    public static RouteStore getInstance() { return I; }
    public java.util.Collection<Object> routes() { return List.of(); }
    public Object forRoom(String room) { return null; }
    public void save() {}
    public static void reload() {}
    public static Path routesDirectory() { return Path.of("config"); }
}
