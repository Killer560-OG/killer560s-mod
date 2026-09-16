package com.killer560.hub.ap3;

/** THROWAWAY STUB - assumed getter/setter names; see INTEGRATION.md "Assumed by this half". */
public final class Ap3Config {
    private static final Ap3Config I = new Ap3Config();
    public static Ap3Config getInstance() { return I; }
    public static void load() {}
    public void save() {}
    public boolean isEnabled() { return false; }
    public boolean isEnabledRaw() { return false; }
    public void setEnabled(boolean v) {}
    public boolean isUniformColor() { return false; }
    public void setUniformColor(boolean v) {}
    public int getUniformColorArgb() { return 0; }
    public void setUniformColorArgb(int v) {}
    public int getActiveColorArgb() { return 0; }
    public void setActiveColorArgb(int v) {}
    public int getNodeColorArgb(Ap3Node.Type t) { return 0; }
    public void setNodeColorArgb(Ap3Node.Type t, int v) {}
    public int getKeybind(String id) { return -1; }
    public void setKeybind(String id, int code) {}
}
