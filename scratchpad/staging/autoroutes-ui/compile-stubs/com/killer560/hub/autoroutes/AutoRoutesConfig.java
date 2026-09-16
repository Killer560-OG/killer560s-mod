package com.killer560.hub.autoroutes;
/** COMPILE-CHECK STUB ONLY - not for integration. Every method here is one the UI code calls; see INTEGRATION.md. */
public final class AutoRoutesConfig {
    public enum RenderStyle { BOX, FILLED_BOX, CYLINDER }
    private static final AutoRoutesConfig I = new AutoRoutesConfig();
    public static AutoRoutesConfig getInstance() { return I; }
    public static void load() {}
    public void save() {}
    public boolean isEnabled() { return false; }
    public boolean isEnabledRaw() { return false; }
    public void setEnabled(boolean v) {}
    public boolean isLegitMode() { return false; }
    public void setLegitMode(boolean v) {}
    public boolean isStartFromStartNodeOnly() { return false; }
    public void setStartFromStartNodeOnly(boolean v) {}
    public boolean isUniformColor() { return false; }
    public void setUniformColor(boolean v) {}
    public int getUniformColorArgb() { return 0; }
    public void setUniformColorArgb(int argb) {}
    public int getActiveColorArgb() { return 0; }
    public void setActiveColorArgb(int argb) {}
    public int getNodeColorArgb(RouteNode.Type type) { return 0; }
    public void setNodeColorArgb(RouteNode.Type type, int argb) {}
    public RenderStyle getRenderStyle() { return RenderStyle.BOX; }
    public void setRenderStyle(RenderStyle s) {}
    public double getThickness() { return 4.0; }
    public void setThickness(double v) {}
    public double getHeight() { return 0.1; }
    public void setHeight(double v) {}
    public int getKeybind(String actionId) { return -1; }
    public void setKeybind(String actionId, int code) {}
}
