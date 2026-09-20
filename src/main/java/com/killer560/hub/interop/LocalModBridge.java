package com.killer560.hub.interop;

import java.util.Map;

/**
 * Optional extra: read another dungeon mod's own dungeon state out of this Minecraft's memory.
 * <p>
 * <b>This only does anything if the player already has that mod installed here</b>, which is exactly why it is
 * off by default and why nothing else in the mod is allowed to depend on it - killer560 (2026-09-20): "The
 * issue with reading their mods based of me having them assumes someone else using my mod also has them. Which
 * I don't want them to have to do." It helps his own instance and nothing more; {@link SelfDerivation} and
 * {@link InteropChatParser} are what make the feature real for everyone else.
 * <p>
 * <b>Read-only, and local-only.</b> Every access goes through {@link ReadOnlyReflect}, which will only resolve
 * a zero-argument {@code get*}/{@code is*} method and refuses anything else, so there is no code path in this
 * package that can set a field, call a setter, trigger an action, or make one of those mods send anything.
 * Those mods each run their own relay/websocket to share party data between their users; we do not connect to
 * any of them, and nothing here touches their networking classes.
 * <p>
 * Every lookup is resolved once and cached - including failures - so a mod that is missing, or that renamed a
 * field in its next update, costs one reflective miss for the whole session and then degrades silently to "no
 * data". Nothing here logs per poll and nothing here can throw into the caller.
 * <p>
 * What we read, per mod (source checked on disk, 2026-09-20):
 * <ul>
 * <li><b>NoammAddons</b> {@code utils.dungeons.map.handlers.ScoreCalculation} (Kotlin {@code object}):
 * {@code getMimicKilled()}, {@code getPrinceKilled()}, {@code getBatKilled()}, {@code getFoundSecrets()},
 * {@code getCryptsCount()}, {@code getDeathCount()}. And {@code ...handlers.DungeonScanner.getUniqueRooms()},
 * a {@code Map<String, UniqueRoom>}, for each room's {@code getFoundSecrets()} against its
 * {@code getData().getSecrets()} maximum. Nothing else.</li>
 * <li><b>Odin</b> {@code utils.skyblock.dungeon.DungeonUtils} (Kotlin {@code object}, and deliberately its
 * read-only facade rather than the mutable {@code DungeonListener} behind it): {@code getMimicKilled()},
 * {@code getPrinceKilled()}, {@code getBatKilled()}, {@code getBloodDone()}, {@code getSecretCount()},
 * {@code getCryptCount()}, {@code getDeathCount()}. Nothing else.</li>
 * </ul>
 */
public final class LocalModBridge {

    private static final String NOAMM_SCORE = "com.github.noamm9.utils.dungeons.map.handlers.ScoreCalculation";
    private static final String NOAMM_SCANNER = "com.github.noamm9.utils.dungeons.map.handlers.DungeonScanner";
    private static final String ODIN_DUNGEON = "com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils";

    /** Rooms are walked every 4th poll (~0.5 Hz) - they change far more slowly than the run counters. */
    private static final int ROOM_POLL_DIVISOR = 4;

    private static int pollCount = 0;

    private LocalModBridge() {
    }

    static void onRunReset() {
        pollCount = 0;
    }

    /** Called at 2 Hz from {@link InteropFeature#register()}'s tick. Never throws. */
    static void poll() {
        try {
            pollCount++;
            if (DetectedMods.isLoaded(DetectedMods.NOAMM_ADDONS)) {
                pollNoammAddons(pollCount % ROOM_POLL_DIVISOR == 0);
            }
            if (DetectedMods.isLoaded(DetectedMods.ODIN)) {
                pollOdin();
            }
        } catch (Throwable ignored) {
            // A bridge is a nice-to-have. It must never be able to break a dungeon run.
        }
    }

    private static void pollNoammAddons(boolean includeRooms) {
        String who = "NoammAddons";
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(NOAMM_SCORE, "getMimicKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.MIMIC_KILLED, InteropSource.BRIDGE, who);
        }
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(NOAMM_SCORE, "getPrinceKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.PRINCE_KILLED, InteropSource.BRIDGE, who);
        }
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(NOAMM_SCORE, "getBatKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BAT_KILLED, InteropSource.BRIDGE, who);
        }
        offerCounter(PartyInteropState.Counter.SECRETS_FOUND, NOAMM_SCORE, "getFoundSecrets", who);
        offerCounter(PartyInteropState.Counter.CRYPTS, NOAMM_SCORE, "getCryptsCount", who);
        offerCounter(PartyInteropState.Counter.DEATHS, NOAMM_SCORE, "getDeathCount", who);
        if (includeRooms) {
            pollNoammRooms(who);
        }
    }

    /** {@code DungeonScanner.uniqueRooms} is a {@code LinkedHashMap<String, UniqueRoom>} keyed by room name;
     *  each value exposes {@code getFoundSecrets()} and {@code getData().getSecrets()} (its maximum). */
    private static void pollNoammRooms(String who) {
        Object rooms = ReadOnlyReflect.objectGetter(NOAMM_SCANNER, "getUniqueRooms").read();
        if (!(rooms instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object room = entry.getValue();
            if (room == null) {
                continue;
            }
            Object found = ReadOnlyReflect.readFrom(room, "getFoundSecrets");
            if (!(found instanceof Number foundNum) || foundNum.intValue() <= 0) {
                continue;
            }
            String name = entry.getKey() instanceof String key ? key : null;
            if (name == null) {
                Object roomName = ReadOnlyReflect.readFrom(room, "getName");
                name = roomName instanceof String s ? s : null;
            }
            if (name == null || name.isBlank()) {
                continue;
            }
            Object data = ReadOnlyReflect.readFrom(room, "getData");
            Object max = data == null ? null : ReadOnlyReflect.readFrom(data, "getSecrets");
            int total = max instanceof Number maxNum ? maxNum.intValue() : -1;
            PartyInteropState.offerRoomSecrets(name, foundNum.intValue(), total, InteropSource.BRIDGE, who);
        }
    }

    /** Odin exposes everything we want through one read-only facade object, so this is six getter calls and
     *  no walking of its internals. Odin has no per-room secret map worth reading here - its room objects are
     *  map tiles, and the party-chat route already covers what it announces (nothing, for secrets). */
    private static void pollOdin() {
        String who = "Odin";
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(ODIN_DUNGEON, "getMimicKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.MIMIC_KILLED, InteropSource.BRIDGE, who);
        }
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(ODIN_DUNGEON, "getPrinceKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.PRINCE_KILLED, InteropSource.BRIDGE, who);
        }
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(ODIN_DUNGEON, "getBatKilled").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BAT_KILLED, InteropSource.BRIDGE, who);
        }
        if (Boolean.TRUE.equals(ReadOnlyReflect.objectGetter(ODIN_DUNGEON, "getBloodDone").readBoolean())) {
            PartyInteropState.offerFlag(PartyInteropState.Flag.BLOOD_DONE, InteropSource.BRIDGE, who);
        }
        offerCounter(PartyInteropState.Counter.SECRETS_FOUND, ODIN_DUNGEON, "getSecretCount", who);
        offerCounter(PartyInteropState.Counter.CRYPTS, ODIN_DUNGEON, "getCryptCount", who);
        offerCounter(PartyInteropState.Counter.DEATHS, ODIN_DUNGEON, "getDeathCount", who);
    }

    private static void offerCounter(PartyInteropState.Counter counter, String className, String getter, String who) {
        Integer value = ReadOnlyReflect.objectGetter(className, getter).readInt();
        if (value != null && value > 0) {
            PartyInteropState.offerCounter(counter, value, InteropSource.BRIDGE, who);
        }
    }

    /** Whether any bridge target is actually installed here - for the Interop tab's status line. */
    public static boolean anyTargetInstalled() {
        return DetectedMods.isLoaded(DetectedMods.NOAMM_ADDONS) || DetectedMods.isLoaded(DetectedMods.ODIN);
    }
}
