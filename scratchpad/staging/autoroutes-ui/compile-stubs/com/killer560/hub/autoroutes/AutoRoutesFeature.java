package com.killer560.hub.autoroutes;
import net.minecraft.core.BlockPos;
import java.util.List;
/** COMPILE-CHECK STUB ONLY - not for integration. */
public final class AutoRoutesFeature {
    public static void setEditMode(boolean on) {}
    public static boolean isEditMode() { return false; }
    public static boolean onEditRightClick(BlockPos pos, boolean shift) { return false; }
    public static List<RouteNode> currentRouteNodes() { return List.of(); }
    public static void deleteNode(int index) {}
    public static void clearCurrentRoute() {}
}
