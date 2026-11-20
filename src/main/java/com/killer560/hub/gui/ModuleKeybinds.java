package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.KeyCaptureTab;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A toggle key for every module (killer560, 2026-11-01, "EVERY MODULE KEYBIND": "add a keybind option for every module to
 * set a keybind to turn it on and off. Have none bound by default. It should be at the very bottom of every mod option").
 *
 * <p>Every feature tab with a card switch ({@link BaseTab#masterToggles}) gets a "Toggle Keybind" row as its drawer's
 * last row ({@link ModScreen}). A press of the bound key in game (no screen open) presses that same switch through
 * {@link FeatureCards#toggle}: the very {@code onPress} a click on the card runs, so a key can never do anything the
 * menu could not. Binds are kept by the tab's class name (the id Favorites use) in {@code killer560smod-menu-keybinds.json};
 * none is bound by default.
 */
public final class ModuleKeybinds {

    private static final Logger LOGGER = ModLog.get("killer560smod-menu");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu-keybinds.json");

    /** The row's label (the testkit and the tooltip key read it). */
    public static final String LABEL = "Toggle Keybind";

    /** A feature folded into another tab's drawer that keeps a toggle key of its own (no card, so no
     *  {@link FeatureCards#toggle}): its id is the bind's key in the saved file, so a bind made while it had its own card
     *  carries over. Thorn Triggerbot was its own card until killer560, 2026-11-02 ("move thorn triggerbot under the thorn
     *  stuff already built with that cheat build section"). */
    private record Extra(String name, String label, java.util.function.BooleanSupplier on,
                         java.util.function.Consumer<Boolean> set, Runnable save) {
    }

    private static final Map<String, Extra> EXTRAS = new LinkedHashMap<>();

    /** The id and row label of Thorn Triggerbot's own toggle key, in the Thorn tab's cheat section. */
    public static final String THORN_TRIGGERBOT_ID = "ThornTriggerbotTab";
    public static final String THORN_TRIGGERBOT_LABEL = "Triggerbot Toggle Keybind";
    /** Secret Triggerbot, merged into the Secret Aura card (killer560, 2026-11-09); its old card id keeps old binds. */
    public static final String SECRET_TRIGGERBOT_ID = "SecretTriggerbotTab";
    public static final String SECRET_TRIGGERBOT_LABEL = "Triggerbot Toggle Keybind";
    /** Structure Scanner, folded into the Crystal Hollows Map tab (killer560, 2026-11-05); its old card id keeps old binds. */
    public static final String STRUCTURE_SCANNER_ID = "StructureScannerTab";
    public static final String STRUCTURE_SCANNER_LABEL = "Scanner Toggle Keybind";

    static {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Looked up on each press: a profile apply reloads the config.
            EXTRAS.put(THORN_TRIGGERBOT_ID, new Extra("Thorn Triggerbot", THORN_TRIGGERBOT_LABEL,
                    () -> com.killer560.hub.thorn.ThornConfig.getInstance().isTriggerbotRaw(),
                    v -> com.killer560.hub.thorn.ThornConfig.getInstance().setTriggerbot(v),
                    () -> com.killer560.hub.thorn.ThornConfig.getInstance().save()));
            EXTRAS.put(SECRET_TRIGGERBOT_ID, new Extra("Secret Triggerbot", SECRET_TRIGGERBOT_LABEL,
                    () -> com.killer560.hub.secrettrigger.SecretTriggerbotConfig.getInstance().isEnabled(),
                    v -> com.killer560.hub.secrettrigger.SecretTriggerbotConfig.getInstance().setEnabled(v),
                    () -> com.killer560.hub.secrettrigger.SecretTriggerbotConfig.getInstance().save()));
            EXTRAS.put(STRUCTURE_SCANNER_ID, new Extra("Structure Scanner", STRUCTURE_SCANNER_LABEL,
                    () -> com.killer560.hub.mining.scanner.StructureScannerConfig.getInstance().isEnabled(),
                    v -> com.killer560.hub.mining.scanner.StructureScannerConfig.getInstance().setEnabled(v),
                    () -> com.killer560.hub.mining.scanner.StructureScannerConfig.getInstance().save()));
        }
    }

    private static Map<String, Integer> binds;
    private static final Map<String, Boolean> wasDown = new HashMap<>();
    /** Presses that toggled something, for the testkit (a press proves itself by this AND the switch's state). */
    private static int toggles;
    /** Testkit: a code held down as if pressed (a harness key press never reaches the GLFW poll {@link KeyUtil} reads). */
    public static volatile Integer heldForTest;

    private ModuleKeybinds() {
    }

    public static void register() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ModuleKeybinds.tick", ModuleKeybinds::tick));
    }

    public static void load() {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject b = obj.has("binds") && obj.get("binds").isJsonObject() ? obj.getAsJsonObject("binds") : new JsonObject();
                for (Map.Entry<String, JsonElement> e : b.entrySet()) {
                    try {
                        int code = KeyUtil.sanitizeBind(e.getValue().getAsInt());
                        if (code != KeyUtil.NONE) {
                            m.put(e.getKey(), code);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Menu] Couldn't read {}: {}", CONFIG_PATH.getFileName(), e.toString());
            }
        }
        binds = m;
        wasDown.clear();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject b = new JsonObject();
            for (Map.Entry<String, Integer> e : binds().entrySet()) {
                b.addProperty(e.getKey(), e.getValue());
            }
            JsonObject obj = new JsonObject();
            obj.add("binds", b);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Menu] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    private static Map<String, Integer> binds() {
        if (binds == null) {
            load();
        }
        return binds;
    }

    /** The tab's bound code ({@link KeyUtil#NONE} when unbound). */
    public static int get(String tabId) {
        return binds().getOrDefault(tabId, KeyUtil.NONE);
    }

    /** Binds (or with {@link KeyUtil#NONE} unbinds) the tab's toggle key, and saves. */
    public static void set(String tabId, int code) {
        int c = KeyUtil.sanitizeBind(code);
        if (c == KeyUtil.NONE) {
            binds().remove(tabId);
        } else {
            binds().put(tabId, c);
        }
        wasDown.remove(tabId);
        save();
    }

    public static int toggles() {
        return toggles;
    }

    /** The tab whose row is waiting for a key, or null. */
    private static String capturingId;

    static Component rowText(String tabId) {
        Extra extra = EXTRAS.get(tabId);
        String label = extra != null ? extra.label() : LABEL;
        if (tabId.equals(capturingId)) {
            return Component.literal(label + ": Press any key...");
        }
        int code = get(tabId);
        return Component.literal(label + ": " + (code == KeyUtil.NONE ? "None" : KeyUtil.bindDisplayName(code)));
    }

    /** The row for the tab {@code tabId} at the bottom of its drawer (or, for a folded-in feature's id, wherever its
     *  host tab puts it): a click waits for a key or mouse button (Escape cancels, Backspace clears). */
    public static SettingsButtonWidget row(String tabId, int x, int y, int w) {
        return SettingsButtonWidget.builder(rowText(tabId), btn -> {
            capturingId = tabId;
            btn.setMessage(rowText(tabId));
        }).bounds(x, y, w, 18).build();
    }

    /** The capture for the open drawer {@code tabId}, while its row waits for a key; else null. */
    static KeyCaptureTab listening(String tabId) {
        // A folded-in feature's row sits in its host tab's drawer, so it listens there too.
        return tabId != null && capturingId != null && (tabId.equals(capturingId) || EXTRAS.containsKey(capturingId))
                ? CAPTURE : null;
    }

    /** Stops waiting (the drawer closed or switched). */
    static void cancelCapture() {
        capturingId = null;
    }

    private static final KeyCaptureTab CAPTURE = new KeyCaptureTab() {
        @Override
        public boolean isListeningForKey() {
            return capturingId != null;
        }

        @Override
        public void cancelKeyCapture() {
            capturingId = null;
        }

        @Override
        public void onKeyCaptured(int keyCode) {
            apply(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
        }

        @Override
        public boolean supportsMouseCapture() {
            return true;
        }

        @Override
        public void onMouseCaptured(int button) {
            apply(KeyUtil.codeForMouseButton(button));
        }

        private void apply(int code) {
            String id = capturingId;
            capturingId = null;
            if (id != null) {
                set(id, code);
            }
        }
    };

    private static void tick(Minecraft client) {
        Map<String, Integer> m = binds();
        if (m.isEmpty()) {
            return;
        }
        boolean free = McCompat.screen(client) == null && client.getWindow() != null;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            Integer held = heldForTest;
            boolean down = free && ((held != null && held.intValue() == e.getValue())
                    || KeyUtil.isBindDown(client.getWindow(), e.getValue()));
            Boolean was = wasDown.put(e.getKey(), down);
            if (down && (was == null || !was)) {
                press(e.getKey());
            }
        }
    }

    /** Presses the switch of the tab {@code tabId} as its key does; returns the new state, or null if it has none. */
    public static Boolean press(String tabId) {
        Extra extra = EXTRAS.get(tabId);
        if (extra != null) {
            boolean on = !extra.on().getAsBoolean();
            extra.set().accept(on);
            extra.save().run();
            toggles++;
            com.killer560.hub.notify.ModOverlayMessage.show("§6" + extra.name() + ": " + (on ? "§aON" : "§cOFF"), 1500);
            return on;
        }
        BaseTab tab = find(tabId);
        if (tab == null) {
            return null;
        }
        Boolean on = FeatureCards.toggle(tab, null);
        if (on == null) {
            return null;
        }
        toggles++;
        com.killer560.hub.notify.ModOverlayMessage.show("§6" + tab.name + ": " + (on ? "§aON" : "§cOFF"), 1500);
        return on;
    }

    private static BaseTab find(String tabId) {
        List<BaseTab> leaves = ModScreen.allLeaves();
        for (BaseTab t : leaves) {
            if (ModScreen.id(t).equals(tabId)) {
                return t;
            }
        }
        return null;
    }
}
