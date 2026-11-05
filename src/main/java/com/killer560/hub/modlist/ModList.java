package com.killer560.hub.modlist;

import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.util.ModLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every loaded Fabric mod, read once from {@link FabricLoader} (the set never changes while the game runs), for
 * {@link ModsScreen}. A mod's parent is the jar that carries it (Jar-in-Jar, {@code getContainingMod}) or the
 * {@code custom.modmenu.parent} Mod Menu reads; a mod is a library when its {@code custom.modmenu.badges} say so or it is
 * a built-in (Java, Minecraft, Fabric Loader). Children are listed under their parent, as Mod Menu does.
 * <p>
 * Configure screens come from the {@code modmenu} entrypoint every Mod Menu-ready mod declares, called by reflection so
 * nothing here needs Mod Menu: without Mod Menu installed those entrypoint classes cannot load (their interface is
 * missing), so only this mod's own screen is offered then; with it, every mod's {@code getModConfigScreenFactory} and
 * {@code getProvidedConfigScreenFactories} is used. Read the first time a mod is selected, then kept.
 */
public final class ModList {

    private static final Logger LOGGER = ModLog.get("killer560smod-modlist");
    static final String OWN_ID = "killer560smod";
    private static final String API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private static final String FACTORY = "com.terraformersmc.modmenu.api.ConfigScreenFactory";

    /** One mod. Everything the screen draws or filters on is worked out here once. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String version;
        public final String description;
        public final String authors;
        public final String homepage;
        public final String sources;
        public final String issues;
        public final String parentId;
        public final boolean library;
        final ModContainer container;
        final String search;
        public final List<Entry> children = new ArrayList<>();

        Entry(ModContainer c) {
            ModMetadata m = c.getMetadata();
            container = c;
            id = m.getId();
            name = m.getName() == null || m.getName().isBlank() ? id : m.getName();
            version = m.getVersion().getFriendlyString();
            description = m.getDescription() == null ? "" : m.getDescription().trim();
            authors = m.getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
            homepage = m.getContact().get("homepage").orElse(null);
            sources = m.getContact().get("sources").orElse(null);
            issues = m.getContact().get("issues").orElse(null);
            CustomValue mm = m.getCustomValue("modmenu");
            String parent = c.getContainingMod().map(p -> p.getMetadata().getId()).orElse(null);
            boolean lib = "builtin".equals(m.getType());
            if (mm != null && mm.getType() == CustomValue.CvType.OBJECT) {
                CustomValue.CvObject o = mm.getAsObject();
                CustomValue p = o.get("parent");
                if (parent == null && p != null && p.getType() == CustomValue.CvType.OBJECT) {
                    p = p.getAsObject().get("id");
                }
                if (parent == null && p != null && p.getType() == CustomValue.CvType.STRING) {
                    parent = p.getAsString(); // nested only when that parent is loaded (top() below)
                }
                CustomValue badges = o.get("badges");
                if (badges != null && badges.getType() == CustomValue.CvType.ARRAY) {
                    for (CustomValue b : badges.getAsArray()) {
                        lib |= b.getType() == CustomValue.CvType.STRING && "library".equals(b.getAsString());
                    }
                }
            }
            if (parent == null && m.containsCustomValue("fabric-api:module-lifecycle") && !"fabric-api".equals(id)) {
                parent = "fabric-api"; // a module loaded on its own (dev, or a pack that unzipped it), as Mod Menu folds it
                lib = true;
            }
            parentId = parent;
            library = lib;
            search = (name + " " + id + " " + authors + " " + description).toLowerCase(Locale.ROOT);
        }

        public boolean matches(String lowerQuery) {
            return lowerQuery.isEmpty() || search.contains(lowerQuery);
        }

        /** The icon's path inside the mod's jar, or null. */
        String iconPath() {
            return container.getMetadata().getIconPath(64).orElse(null);
        }
    }

    private static List<Entry> top;
    private static Map<String, Entry> byId;
    private static Map<String, Function<Screen, Screen>> factories;

    private ModList() {
    }

    /** The top-level mods, by name; each holds its children, also by name. */
    public static synchronized List<Entry> top() {
        if (top == null) {
            Map<String, Entry> all = new LinkedHashMap<>();
            for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
                Entry e = new Entry(c);
                all.put(e.id, e);
            }
            Comparator<Entry> order = Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT));
            List<Entry> roots = new ArrayList<>();
            for (Entry e : all.values()) {
                Entry p = e.parentId == null || e.parentId.equals(e.id) ? null : all.get(e.parentId);
                (p == null ? roots : p.children).add(e);
            }
            for (Entry e : all.values()) {
                e.children.sort(order);
            }
            roots.sort(order);
            byId = all;
            top = roots;
        }
        return top;
    }

    public static synchronized int count() {
        top();
        return byId.size();
    }

    /** Whether {@code e} has a Configure screen. */
    public static boolean configurable(Entry e) {
        return OWN_ID.equals(e.id) || factories().containsKey(e.id);
    }

    /** {@code e}'s config screen over {@code parent}, or null when it has none or making it failed. */
    public static Screen configScreen(Entry e, Screen parent) {
        if (OWN_ID.equals(e.id)) {
            return new ModScreen(parent);
        }
        Function<Screen, Screen> f = factories().get(e.id);
        try {
            return f == null ? null : f.apply(parent);
        } catch (Throwable t) {
            LOGGER.warn("[ModList] {}'s config screen failed: {}", e.id, t.toString());
            return null;
        }
    }

    private static synchronized Map<String, Function<Screen, Screen>> factories() {
        if (factories != null) {
            return factories;
        }
        Map<String, Function<Screen, Screen>> out = new HashMap<>();
        factories = out;
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
            return out;
        }
        try {
            ClassLoader cl = ModList.class.getClassLoader();
            Method getFactory = Class.forName(API, false, cl).getMethod("getModConfigScreenFactory");
            Method provided = Class.forName(API, false, cl).getMethod("getProvidedConfigScreenFactories");
            Method create = Class.forName(FACTORY, false, cl).getMethod("create", Screen.class);
            Map<String, Function<Screen, Screen>> providedByOthers = new HashMap<>();
            for (EntrypointContainer<Object> ep : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
                String id = ep.getProvider().getMetadata().getId();
                if (OWN_ID.equals(id)) {
                    continue;
                }
                try {
                    Object api = ep.getEntrypoint();
                    Object factory = getFactory.invoke(api);
                    if (factory != null) {
                        out.put(id, p -> (Screen) invoke(create, factory, p));
                    }
                    if (provided.invoke(api) instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> me : map.entrySet()) {
                            Object f = me.getValue();
                            if (me.getKey() instanceof String other && f != null) {
                                providedByOthers.putIfAbsent(other, p -> (Screen) invoke(create, f, p));
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[ModList] {}'s modmenu entrypoint failed: {}", id, t.toString());
                }
            }
            providedByOthers.forEach(out::putIfAbsent);
        } catch (Throwable t) {
            LOGGER.warn("[ModList] Mod Menu's API is not readable: {}", t.toString());
        }
        return out;
    }

    private static Object invoke(Method m, Object target, Object arg) {
        try {
            return m.invoke(target, arg);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex.getCause() != null ? ex.getCause() : ex);
        }
    }
}
