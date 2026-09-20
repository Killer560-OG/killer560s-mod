package com.killer560.hub.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only way {@link LocalModBridge} is allowed to touch another mod. Read-only by construction:
 * <ul>
 * <li>The only thing it can resolve is a <b>zero-argument method whose name starts with {@code get} or
 * {@code is}</b> - {@link #assertReadOnly} rejects anything else outright, so there is no code path here that
 * can call another mod's setter, mutator, or anything that sends a packet.</li>
 * <li>Fields are read, never written. There is no {@code Field.set} in this class.</li>
 * <li>Nothing here opens a socket, resolves a host, or touches another mod's network layer. Those mods share
 * their party data over their own servers; we do not connect to them and never will.</li>
 * </ul>
 * Failure handling: every lookup and every call is wrapped, and a lookup that fails is <b>cached as failed</b>
 * so a missing class or a renamed method costs one reflective miss for the whole session, not one per poll.
 * Nothing here ever logs per frame - a dead handle logs once, at debug level, and then stays quiet.
 */
final class ReadOnlyReflect {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-interop");

    /** A resolved (or permanently failed) read. A failed one returns {@code null} forever. */
    static final class Reader {

        private final String description;
        private final Object receiver;
        private final Method method;
        private boolean warned;

        private Reader(String description, Object receiver, Method method) {
            this.description = description;
            this.receiver = receiver;
            this.method = method;
        }

        /** @return the value, or {@code null} for a dead handle or any thrown exception. Never throws. */
        Object read() {
            if (method == null) {
                return null;
            }
            try {
                return method.invoke(receiver);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true; // once per handle per session - never a stack trace per poll
                    LOGGER.debug("[Interop] {} threw, ignoring it for the rest of this session: {}",
                            description, t.toString());
                }
                return null;
            }
        }

        Boolean readBoolean() {
            Object value = read();
            return value instanceof Boolean b ? b : null;
        }

        Integer readInt() {
            Object value = read();
            return value instanceof Number n ? n.intValue() : null;
        }
    }

    private static final Reader DEAD = new Reader("unresolved", null, null);
    private static final Map<String, Reader> CACHE = new ConcurrentHashMap<>();

    private ReadOnlyReflect() {
    }

    /**
     * A getter on a Kotlin {@code object} singleton (Odin, NoammAddons, SkyHanni and Devonian are all Kotlin,
     * so their state lives on a {@code public static final INSTANCE}).
     */
    static Reader objectGetter(String className, String getterName) {
        return CACHE.computeIfAbsent(className + "#" + getterName, key -> resolve(className, getterName));
    }

    /** A getter invoked on an object we already hold (e.g. one room out of another mod's room map). */
    static Object readFrom(Object target, String getterName) {
        if (target == null) {
            return null;
        }
        Reader reader = CACHE.computeIfAbsent(target.getClass().getName() + "#!" + getterName, key -> {
            try {
                assertReadOnly(getterName);
                Method method = target.getClass().getMethod(getterName);
                if (method.getParameterCount() != 0) {
                    return DEAD;
                }
                method.setAccessible(true);
                return new Reader(target.getClass().getName() + "." + getterName + "()", null, method);
            } catch (Throwable t) {
                return DEAD;
            }
        });
        if (reader == DEAD || reader.method == null) {
            return null;
        }
        try {
            return reader.method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Reader resolve(String className, String getterName) {
        try {
            assertReadOnly(getterName);
            Class<?> type = Class.forName(className, false, ReadOnlyReflect.class.getClassLoader());
            Object instance = type.getField("INSTANCE").get(null);
            Method method = type.getMethod(getterName);
            if (method.getParameterCount() != 0) {
                return DEAD;
            }
            method.setAccessible(true);
            LOGGER.debug("[Interop] Bridge resolved {}.{}()", className, getterName);
            return new Reader(className + "." + getterName + "()", instance, method);
        } catch (Throwable t) {
            // A mod that isn't installed, or one that renamed something: perfectly normal, cached as dead.
            return DEAD;
        }
    }

    /** Makes "we only ever read" a rule the code enforces, not a promise in a comment. */
    private static void assertReadOnly(String getterName) {
        if (getterName == null || !(getterName.startsWith("get") || getterName.startsWith("is"))) {
            throw new IllegalArgumentException("Interop may only call getters, refused: " + getterName);
        }
    }
}
