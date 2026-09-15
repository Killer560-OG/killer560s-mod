package com.killer560.hub.windowlayout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Window Layout settings plus the last cell this instance was placed in. Lives in the instance's own
 *  config dir, so every Minecraft instance remembers its own spot independently. */
public final class WindowLayoutConfig {

    public static final int MIN_WINDOWS = 1;
    public static final int MAX_WINDOWS = 9;
    public static final int MAX_GAP = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-windowlayout.json");

    private static WindowLayoutConfig instance;

    private int windowsPerMonitor = 4;
    /** -1 = the monitor the window is currently on. */
    private int monitorIndex = -1;
    private String monitorDevice = "";
    private String monitorName = "";
    private int gap = 0;
    private boolean respectTaskbar = true;
    private int pickerKeyCode = -1;
    private boolean restoreOnLaunch = false;

    private boolean hasLastPlacement = false;
    private int lastMonitorIndex = 0;
    private String lastMonitorDevice = "";
    private String lastMonitorName = "";
    private int lastCount = 1;
    private int lastCellIndex = 0;

    private WindowLayoutConfig() {
    }

    public static WindowLayoutConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        WindowLayoutConfig cfg = new WindowLayoutConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.windowsPerMonitor = clamp(getInt(o, "windowsPerMonitor", 4), MIN_WINDOWS, MAX_WINDOWS);
                cfg.monitorIndex = Math.max(-1, getInt(o, "monitorIndex", -1));
                cfg.monitorDevice = getString(o, "monitorDevice");
                cfg.monitorName = getString(o, "monitorName");
                cfg.gap = clamp(getInt(o, "gap", 0), 0, MAX_GAP);
                cfg.respectTaskbar = getBool(o, "respectTaskbar", true);
                cfg.pickerKeyCode = getInt(o, "pickerKeyCode", -1);
                cfg.restoreOnLaunch = getBool(o, "restoreOnLaunch", false);
                cfg.hasLastPlacement = getBool(o, "hasLastPlacement", false);
                cfg.lastMonitorIndex = Math.max(0, getInt(o, "lastMonitorIndex", 0));
                cfg.lastMonitorDevice = getString(o, "lastMonitorDevice");
                cfg.lastMonitorName = getString(o, "lastMonitorName");
                cfg.lastCount = clamp(getInt(o, "lastCount", 1), MIN_WINDOWS, MAX_WINDOWS);
                cfg.lastCellIndex = clamp(getInt(o, "lastCellIndex", 0), 0, cfg.lastCount - 1);
            } catch (Exception e) {
                cfg = new WindowLayoutConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("windowsPerMonitor", windowsPerMonitor);
            o.addProperty("monitorIndex", monitorIndex);
            o.addProperty("monitorDevice", monitorDevice);
            o.addProperty("monitorName", monitorName);
            o.addProperty("gap", gap);
            o.addProperty("respectTaskbar", respectTaskbar);
            o.addProperty("pickerKeyCode", pickerKeyCode);
            o.addProperty("restoreOnLaunch", restoreOnLaunch);
            o.addProperty("hasLastPlacement", hasLastPlacement);
            o.addProperty("lastMonitorIndex", lastMonitorIndex);
            o.addProperty("lastMonitorDevice", lastMonitorDevice);
            o.addProperty("lastMonitorName", lastMonitorName);
            o.addProperty("lastCount", lastCount);
            o.addProperty("lastCellIndex", lastCellIndex);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int getInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    private static String getString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public int getWindowsPerMonitor() {
        return windowsPerMonitor;
    }

    public void setWindowsPerMonitor(int value) {
        this.windowsPerMonitor = clamp(value, MIN_WINDOWS, MAX_WINDOWS);
    }

    public boolean isMonitorAuto() {
        return monitorIndex < 0;
    }

    public int getMonitorIndex() {
        return monitorIndex;
    }

    public String getMonitorDevice() {
        return monitorDevice;
    }

    public String getMonitorName() {
        return monitorName;
    }

    public void setMonitorAuto() {
        this.monitorIndex = -1;
        this.monitorDevice = "";
        this.monitorName = "";
    }

    public void setMonitor(WindowMonitors.MonitorInfo monitor) {
        this.monitorIndex = monitor.index();
        this.monitorDevice = monitor.device();
        this.monitorName = monitor.name();
    }

    public int getGap() {
        return gap;
    }

    public void setGap(int gap) {
        this.gap = clamp(gap, 0, MAX_GAP);
    }

    public boolean isRespectTaskbar() {
        return respectTaskbar;
    }

    public void setRespectTaskbar(boolean respectTaskbar) {
        this.respectTaskbar = respectTaskbar;
    }

    public int getPickerKeyCode() {
        return pickerKeyCode;
    }

    public void setPickerKeyCode(int pickerKeyCode) {
        this.pickerKeyCode = pickerKeyCode;
    }

    public boolean isRestoreOnLaunch() {
        return restoreOnLaunch;
    }

    public void setRestoreOnLaunch(boolean restoreOnLaunch) {
        this.restoreOnLaunch = restoreOnLaunch;
    }

    public boolean hasLastPlacement() {
        return hasLastPlacement;
    }

    public void setLastPlacement(WindowMonitors.MonitorInfo monitor, int count, int cellIndex) {
        this.hasLastPlacement = true;
        this.lastMonitorIndex = monitor.index();
        this.lastMonitorDevice = monitor.device();
        this.lastMonitorName = monitor.name();
        this.lastCount = clamp(count, MIN_WINDOWS, MAX_WINDOWS);
        this.lastCellIndex = clamp(cellIndex, 0, this.lastCount - 1);
    }

    public int getLastMonitorIndex() {
        return lastMonitorIndex;
    }

    public String getLastMonitorDevice() {
        return lastMonitorDevice;
    }

    public String getLastMonitorName() {
        return lastMonitorName;
    }

    public int getLastCount() {
        return lastCount;
    }

    public int getLastCellIndex() {
        return lastCellIndex;
    }
}
