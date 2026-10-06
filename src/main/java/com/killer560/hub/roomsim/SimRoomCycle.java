package com.killer560.hub.roomsim;

import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModPaths;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Route practice: one room at a time out of a chosen set, stepped with {@code /next} and {@code /back}.
 *
 * <p>killer560 (2026-10-06): "change the all rooms thing, make it only have one room at a time excluding any with 0
 * secrets or puzzles, then make the command /next take me to the next room and /back take me back a room. also make
 * it so when I select it then it goes to a new menu that says rooms without routes or all rooms or rooms with auto
 * routes." This replaces the old All Rooms preset, which pasted every captured room in one line across the grid.
 *
 * <p><b>The set.</b> Every room {@link SimRoomRoutes#isEligible} accepts - the same rule as the picker's Routes
 * filter, so the two cannot disagree about which rooms are worth routing: not PUZZLE, BLOOD, ENTRANCE or FAIRY, and
 * at least one secret (which also drops every CHAMPION room, all 0 secrets). Then, per choice, the picker's own
 * {@link SimRoomRoutes#matches} test: {@code NONE} for "without routes", {@code HAS} for "with Auto Routes". Order
 * is the picker's ({@link RoomLibrary#names}, alphabetical).
 *
 * <p><b>Frozen when chosen.</b> The list is taken once, when he picks the set, and does not change under him: in
 * "Rooms Without Routes" the room he has just routed would otherwise fall out of the list and shift every index
 * after it, so "Room 3/41" would stop meaning anything. Pick the set again to refresh it.
 *
 * <p><b>Stops at the ends</b> rather than wrapping: a progress count that jumps from 41/41 back to 1/41 hides that
 * he has finished the set. {@code /next} on the last room (and {@code /back} on the first) says so and loads
 * nothing.
 *
 * <p>Each step loads through {@link SimBuilder#buildSingleRoom}, the picker's path, which wipes the grid first - so
 * exactly one room exists at a time, and secrets, waypoints, Auto Routes and room detection behave as they do for
 * a picked room. If he loads a different room from the picker meanwhile and it is in the set, the next step
 * continues from that room.
 */
public final class SimRoomCycle {

    /** The three sets the menu offers. */
    public enum Choice {
        WITHOUT_ROUTES("Rooms Without Routes"),
        ALL("All Rooms"),
        WITH_ROUTES("Rooms With Auto Routes");

        public final String label;

        Choice(String label) {
            this.label = label;
        }
    }

    private static Choice choice;
    private static List<String> rooms;
    private static int index = -1;

    private SimRoomCycle() {
    }

    // ------------------------------------------------------------------------------------------- the set

    /** The rooms a choice covers right now, in the picker's order. Empty while the room database is loading. */
    public static List<String> roomsFor(Choice c) {
        List<String> out = new ArrayList<>();
        if (!RoomDatabase.isReady()) {
            return out;
        }
        for (String name : RoomLibrary.names()) {
            if (!SimRoomRoutes.isEligible(name)) {
                continue;
            }
            boolean keep = switch (c) {
                case ALL -> true;
                case WITHOUT_ROUTES -> SimRoomRoutes.matches(name, SimRoomRoutes.Filter.NONE);
                case WITH_ROUTES -> SimRoomRoutes.matches(name, SimRoomRoutes.Filter.HAS);
            };
            if (keep) {
                out.add(name);
            }
        }
        return out;
    }

    /** The chosen set, or null before one was picked. For the testkit. */
    public static synchronized Choice choice() {
        return choice;
    }

    /** The frozen list being stepped through, empty before a set was picked. For the testkit. */
    public static synchronized List<String> currentRooms() {
        return rooms == null ? List.of() : List.copyOf(rooms);
    }

    /** Index of the room loaded last within {@link #currentRooms}, -1 before one. For the testkit. */
    public static synchronized int currentIndex() {
        return index;
    }

    // ------------------------------------------------------------------------------------------- actions

    /**
     * Picks a set and loads its first room. Works from the main menu (the world is opened) and from inside the sim.
     *
     * @return true when a room started loading
     */
    public static boolean start(Minecraft client, Choice c) {
        if (!SimState.canOpen(client)) {
            ModChat.send("Sim", ModChat.text("The dungeon sim only runs in its own world."));
            return false;
        }
        if (!RoomDatabase.isReady()) {
            RoomDatabase.ensureLoading();
            ModChat.send("Sim", ModChat.text("The room database is still loading - try again in a moment."));
            return false;
        }
        List<String> list = roomsFor(c);
        if (list.isEmpty()) {
            ModChat.send("Sim", ModChat.text("No rooms in "), ModChat.value(c.label),
                    ModChat.dim(" (puzzles, Blood, Entrance, Fairy and 0-secret rooms are left out)"));
            return false;
        }
        synchronized (SimRoomCycle.class) {
            choice = c;
            rooms = list;
            index = 0;
        }
        ModChat.send("Sim", ModChat.text(c.label + ": "), ModChat.value(String.valueOf(list.size())),
                ModChat.text(" room(s), one at a time. "),
                ModChat.dim("/next and /back step through them (puzzles, Blood, Entrance, Fairy and 0-secret "
                        + "rooms left out)"));
        load(client);
        return true;
    }

    /**
     * {@code /next} ({@code delta} 1) and {@code /back} ({@code delta} -1).
     *
     * @return true when a room started loading
     */
    public static boolean step(Minecraft client, int delta) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Only works inside the dungeon sim."));
            return false;
        }
        List<String> list;
        int at;
        synchronized (SimRoomCycle.class) {
            list = rooms;
            at = index;
        }
        if (list == null || list.isEmpty()) {
            ModChat.send("Sim", ModChat.text("Pick a set first: "),
                    ModChat.value("Dungeon Sim > All Rooms (route practice)"), ModChat.dim(" (or /map in the sim)"));
            return false;
        }
        // He may have loaded another room from the picker since; continue from it when it is in the set.
        String standing = SimRoomRoutes.currentSoloRoom();
        if (standing != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equalsIgnoreCase(standing)) {
                    at = i;
                    break;
                }
            }
        }
        int target = at + delta;
        if (target < 0 || target >= list.size()) {
            ModChat.send("Sim", ModChat.text(delta > 0 ? "That is the last room " : "That is the first room "),
                    ModChat.value("(" + (at + 1) + "/" + list.size() + ")"),
                    ModChat.dim(" of " + choice.label + (delta > 0 ? " - /back goes back" : " - /next goes on")));
            return false;
        }
        if (SimBuildQueue.isBusy()) {
            ModChat.send("Sim", ModChat.text("Still building the last room - try again in a moment."));
            return false;
        }
        synchronized (SimRoomCycle.class) {
            index = target;
        }
        load(client);
        return true;
    }

    /** Says which room, then loads it through the picker's own single-room path. */
    private static void load(Minecraft client) {
        String name;
        int at;
        int total;
        synchronized (SimRoomCycle.class) {
            name = rooms.get(index);
            at = index;
            total = rooms.size();
        }
        RoomEntry entry = RoomDatabase.lookupByName(name);
        int secrets = entry == null ? 0 : entry.secrets;
        boolean routed = SimRoomRoutes.routeNodes(name) > 0;
        ModChat.send("Sim", ModChat.text("Room " + (at + 1) + "/" + total + ": "), ModChat.value(name),
                ModChat.dim(" (" + secrets + (secrets == 1 ? " secret" : " secrets") + ", routes: "
                        + (routed ? "yes" : "no") + ")"));
        SimBuilder.buildSingleRoom(client, name);
    }

    // ------------------------------------------------------------------------------------------- keybinds

    /** Raw-polled keys like {@code AutoRoutesKeybinds}: never vanilla KeyMappings. Unbound by default. */
    private static final java.nio.file.Path KEYS_FILE = ModPaths.config("killer560smod-sim-roomcycle-keys.txt");
    private static int nextKey = KeyUtil.NONE;
    private static int backKey = KeyUtil.NONE;
    private static boolean keysLoaded;
    private static boolean nextWasDown;
    private static boolean backWasDown;

    public static synchronized int getNextKey() {
        loadKeys();
        return nextKey;
    }

    public static synchronized int getBackKey() {
        loadKeys();
        return backKey;
    }

    public static synchronized void setNextKey(int code) {
        loadKeys();
        nextKey = KeyUtil.sanitizeBind(code);
        saveKeys();
    }

    public static synchronized void setBackKey(int code) {
        loadKeys();
        backKey = KeyUtil.sanitizeBind(code);
        saveKeys();
    }

    private static void loadKeys() {
        if (keysLoaded) {
            return;
        }
        keysLoaded = true;
        try {
            if (java.nio.file.Files.exists(KEYS_FILE)) {
                for (String line : java.nio.file.Files.readAllLines(KEYS_FILE,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    String[] kv = line.trim().split("=", 2);
                    if (kv.length != 2) {
                        continue;
                    }
                    int code;
                    try {
                        code = KeyUtil.sanitizeBind(Integer.parseInt(kv[1].trim()));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (kv[0].trim().equals("next")) {
                        nextKey = code;
                    } else if (kv[0].trim().equals("back")) {
                        backKey = code;
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable means unbound, which is the default anyway.
        }
    }

    private static void saveKeys() {
        try {
            java.nio.file.Files.createDirectories(KEYS_FILE.getParent());
            java.nio.file.Files.writeString(KEYS_FILE, "next=" + nextKey + "\nback=" + backKey + "\n",
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Losing the bind is not worth failing the click.
        }
    }

    private static void tickKeys(Minecraft client) {
        if (McCompat.screen(client) != null || client.getWindow() == null || !SimState.canAct(client)) {
            // A key held while a screen was open was pressed somewhere else; do not fire when it closes.
            nextWasDown = false;
            backWasDown = false;
            return;
        }
        boolean next = KeyUtil.isBindDown(client.getWindow(), getNextKey());
        boolean back = KeyUtil.isBindDown(client.getWindow(), getBackKey());
        if (next && !nextWasDown) {
            step(client, 1);
        } else if (back && !backWasDown) {
            step(client, -1);
        }
        nextWasDown = next;
        backWasDown = back;
    }

    // ------------------------------------------------------------------------------------------- registration

    /** Call once from {@code Killer560ModClient#onInitializeClient}, beside the other sim registrations. */
    public static void register() {
        // Gated with requires(), like /goto and /c: outside the sim the names do not exist, do not tab-complete,
        // and whatever a server does with /next or /back still happens.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("next")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.execute(() -> step(mc, 1));
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("back")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.execute(() -> step(mc, -1));
                        return 1;
                    }));
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SimRoomCycle.keys", SimRoomCycle::tickKeys));
    }
}
