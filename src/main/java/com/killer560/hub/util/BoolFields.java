package com.killer560.hub.util;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** A config's plain on/off settings as one table, so its load and save walk the same list of keys. */
public final class BoolFields<T> {

    private record Field<T>(String key, boolean def, Predicate<T> get, BiConsumer<T, Boolean> set) {
    }

    private final List<Field<T>> fields = new ArrayList<>();

    public BoolFields<T> add(String key, boolean def, Predicate<T> get, BiConsumer<T, Boolean> set) {
        fields.add(new Field<>(key, def, get, set));
        return this;
    }

    /** Reads every key from {@code obj} into {@code cfg}, a missing one taking its default. */
    public void load(T cfg, JsonObject obj) {
        for (Field<T> f : fields) {
            f.set.accept(cfg, ConfigJson.getBool(obj, f.key, f.def));
        }
    }

    public void save(T cfg, JsonObject obj) {
        for (Field<T> f : fields) {
            obj.addProperty(f.key, f.get.test(cfg));
        }
    }
}
