package com.killer560.hub.profiles;

import com.killer560.hub.util.ModLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * "New Blank Profile" (killer560, 2026-10-08: "make an option to make a profile with 0 settings on"): every config class
 * the mod ships is put at its defaults and then every boolean setting in it is switched OFF.
 * <p>
 * The configs are found by walking the mod's own classes for {@code *Config} classes with a static {@code getInstance()}
 * - not from a hand-kept list - so a config added later is covered without anyone remembering to add it here (the reload
 * list in {@link ProfileManager#reloadAllConfigs()} is still hand-kept, which is how AutoTrap went missing from it). Each
 * one's instance boolean fields go to false, every {@code Map} of booleans in it too, and it is saved. Left alone on
 * purpose: {@link #LEFT_AT_DEFAULTS} (HUD layout and scale preferences, not features), {@code ProxyConfig} (never in a
 * profile), Skyblock Only (a gate that keeps features off outside SkyBlock, not a feature; its file is not a
 * {@code *Config} class), and one-time migration markers ({@link #STATE_FIELD}), which would re-run a migration that can
 * turn things back on; those are set true ("already ran") instead.
 */
public final class BlankProfile {

    private static final Logger LOGGER = ModLog.get("killer560smod-profiles");

    /** Config classes not switched off. HudConfig's file is carried over from the live one (his layout). */
    static final Set<String> LEFT_AT_DEFAULTS = Set.of(
            "com.killer560.hub.hud.HudConfig",
            "com.killer560.hub.proxy.config.ProxyConfig");

    /** Boolean fields that record that a one-time migration or seeding ran, not a setting. They are set TRUE in a blank
     *  profile: left false, the next load runs the migration again, and some turn a feature on (Breaker Aura's Multi
     *  Break default did, testkit 617, 2026-10-08). */
    static final Pattern STATE_FIELD = Pattern.compile("(?i).*(migrat|defaulted|seeded).*");

    private BlankProfile() {
    }

    /** Every {@code com.killer560.hub..*Config} class with a static no-argument {@code getInstance()}, by name. */
    public static List<String> configClassNames() {
        Set<String> names = new TreeSet<>();
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer("killer560smod");
        if (mod.isEmpty()) {
            return new ArrayList<>(names);
        }
        for (Path root : mod.get().getRootPaths()) {
            Path pkg = root.resolve("com/killer560/hub");
            if (!Files.isDirectory(pkg)) {
                continue;
            }
            try (var walk = Files.walk(pkg)) {
                walk.forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    // Never load a class in a mixin package: that is an IllegalClassLoadError at best.
                    if (rel.endsWith("Config.class") && !rel.contains("$") && !rel.contains("/mixin/")) {
                        names.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("[Profiles] could not list the mod's classes under {}", pkg, e);
            }
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (instanceGetter(name) != null) {
                out.add(name);
            }
        }
        return out;
    }

    private static Method instanceGetter(String className) {
        try {
            Class<?> cls = Class.forName(className, true, BlankProfile.class.getClassLoader());
            Method m = cls.getDeclaredMethod("getInstance");
            return Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Switches every boolean setting of every config off and saves each. Run on the client thread, after the live files
     * were removed and the configs reloaded, so everything not boolean is at its default. Returns how many switches
     * it wrote.
     */
    static int switchEverythingOff() {
        int off = 0;
        for (String name : configClassNames()) {
            if (LEFT_AT_DEFAULTS.contains(name)) {
                continue;
            }
            try {
                Method getter = instanceGetter(name);
                Object cfg = getter.invoke(null);
                if (cfg == null) {
                    continue;
                }
                off += zeroBooleans(cfg);
                save(cfg);
            } catch (Throwable t) {
                LOGGER.warn("[Profiles] blank profile: could not switch {} off", name, t);
            }
        }
        // The one feature switch outside a *Config class: AutoTrap keeps its own file (with his captured trap routes,
        // which the blank profile carries over).
        try {
            com.killer560.hub.autotrap.AutoTrap.setEnabled(false);
            off++;
        } catch (Throwable t) {
            LOGGER.warn("[Profiles] blank profile: could not switch Auto Trap off", t);
        }
        return off;
    }

    /** Saves every config, so the files on disk hold exactly what is in memory before the blank profile reloads them
     *  (a setting changed in memory and not yet saved would otherwise be lost: testkit 617 saw nine, 2026-10-08). */
    static void saveEverything() {
        for (String name : configClassNames()) {
            try {
                Object cfg = instanceGetter(name).invoke(null);
                if (cfg != null) {
                    save(cfg);
                }
            } catch (Throwable t) {
                LOGGER.warn("[Profiles] blank profile: could not save {} first", name, t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int zeroBooleans(Object cfg) throws IllegalAccessException {
        int n = 0;
        for (Class<?> c = cfg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
                    continue;
                }
                f.setAccessible(true);
                if (STATE_FIELD.matcher(f.getName()).matches()) {
                    if (f.getType() == boolean.class && !Modifier.isFinal(mod)) {
                        f.setBoolean(cfg, true);
                    }
                    continue;
                }
                if (f.getType() == boolean.class && !Modifier.isFinal(mod)) {
                    f.setBoolean(cfg, false);
                    n++;
                } else if (f.getType() == Boolean.class && !Modifier.isFinal(mod)) {
                    f.set(cfg, Boolean.FALSE);
                    n++;
                } else if (Map.class.isAssignableFrom(f.getType())) {
                    Object v = f.get(cfg);
                    if (v instanceof Map<?, ?> map && !map.isEmpty()
                            && map.values().stream().allMatch(x -> x instanceof Boolean)) {
                        try {
                            ((Map<Object, Object>) map).replaceAll((k, x) -> Boolean.FALSE);
                            n += map.size();
                        } catch (UnsupportedOperationException ignored) {
                            // an immutable map is not a setting
                        }
                    }
                }
            }
        }
        return n;
    }

    private static void save(Object cfg) throws ReflectiveOperationException {
        Method save;
        try {
            save = cfg.getClass().getMethod("save");
        } catch (NoSuchMethodException e) {
            save = cfg.getClass().getDeclaredMethod("save");
            save.setAccessible(true);
        }
        save.invoke(Modifier.isStatic(save.getModifiers()) ? null : cfg);
    }
}
