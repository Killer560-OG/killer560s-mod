package com.killer560.hub.partydata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.interop.InteropConfig;
import com.killer560.hub.interop.InteropSource;
import com.killer560.hub.interop.PartyInteropState;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayListener;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.witherdragons.WitherDragon;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shares dungeon facts THIS client already worked out with the rest of the party over
 * {@code killer560s-mod-relay}, and folds in what a party mate's copy of this mod sends back - the same kind
 * of data Devonian ({@code RoomSecrets[cur/total, roomId]}, {@code SecretTracker[name, n]}) and NoammAddons
 * (rooms/doors discovered, mimic/prince/bat/M7 dragon events) already share over their own party sockets,
 * now shared over ours. Wire format: {@code killer560s-mod-relay/PROTOCOL.md}'s {@code dg.v1.*} keys, built
 * and parsed by {@link PartyDataProtocol}.
 * <p>
 * <b>Publish.</b> Nothing here re-derives a single fact from a raw packet or chat line. Every outgoing packet
 * is read straight out of somewhere that already worked it out:
 * <ul>
 * <li>Flags, run counters and per-room secrets - {@link PartyInteropState}'s own SELF-sourced facts (written
 * by {@link com.killer560.hub.interop.SelfDerivation} and {@code ScoreCalculatorFeature}). This class only
 * watches for a SELF fact appearing or changing and relays it on; it never calls {@code offerXxx} for these.</li>
 * <li>M7 dragon spawn/alive events - {@link WitherDragon}'s own public {@code state()}/{@code timesSpawned()},
 * which {@code WitherDragonsFeature} already maintains from Hypixel's flame-particle burst and dragon
 * entities. Read-only: nothing here touches {@code WitherDragonsFeature}. A transition is also offered into
 * {@link PartyInteropState} with {@link InteropSource#SELF} so the Interop tab shows it too - that offer was
 * simply never wired up before this feature (nothing called
 * {@link PartyInteropState#offerDragonSpawn} at all), not something this feature invented.</li>
 * <li>Rooms and doors - {@link DungeonLayout#current()}, this client's own map scan. Sent once per
 * grid cell, the moment that cell resolves to a named room or a door, never re-derived from world blocks
 * directly.</li>
 * </ul>
 * <b>Not published:</b> {@code dg.v1.playerSecrets} (per-player secret total). There is no existing SELF
 * signal for "secrets I personally opened" anywhere in this mod - {@link PartyInteropState.Counter#SECRETS_FOUND}
 * is the party's shared total (read off the tab list, which is identical for everyone in the run), not a
 * per-player figure. Inventing a personal-secrets detector would be re-deriving a fact this feature is
 * supposed to only relay, so the key is documented and consumed (for a future peer, or this mod's own next
 * version) but never sent by this build.
 * <p>
 * <b>Consume.</b> Every inbound packet is checked against {@link PartyDataProtocol}'s strict schema (known
 * key, correct types, sane ranges) before it touches anything, and only while connected to a {@code party:}
 * room (never a {@code lobby:} one - dungeon facts are for your actual party, not everyone sharing your
 * Hypixel instance) and while this client is itself in a dungeon. Accepted facts go into
 * {@link PartyInteropState} with {@link InteropSource#RELAY} and the relay-verified sender name; rooms,
 * doors and per-player secrets have no existing home there, so they land in {@link PartyRoomIntel} instead -
 * see that class's doc for exactly what {@code livemap} would need to actually place a party mate's room on
 * this client's own map.
 * <p>
 * <b>Gating.</b> Publishing needs {@link PartyDataConfig#isShareEnabled()} (this feature's own toggle),
 * {@link InteropConfig#isEnabled()} + {@link InteropConfig#isRelayData()} (nobody would accept it otherwise),
 * and a live {@code party:} relay connection - which today only {@code ModChatFeature} ever opens (see its
 * class doc). If Mod Chat is off, the relay never connects and this feature does nothing at all, by design -
 * see {@link PartyDataConfig}'s class doc on why the setting's default depends on exactly this.
 */
public final class PartyDataFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-partydata");

    /** Scans PartyInteropState/the map/the dragons at ~2 Hz - matches LocalModBridge's poll rate; none of
     *  this changes faster than that. */
    private static final int SCAN_TICKS = 10;
    private static final int MAX_QUEUE = 64;

    private static boolean wasInDungeon = false;
    private static int tickCounter = 0;

    // ---- outgoing dedupe (reset per run): SelfFact id -> the payload JSON last queued for it ----
    private static final Map<String, String> SENT = new HashMap<>();
    private static final Map<WitherDragon, WitherDragon.State> LAST_DRAGON_STATE = new EnumMap<>(WitherDragon.class);

    // ---- outgoing rate limit: conservative next to the relay's own 20-burst/4-per-sec bucket ----
    private static final Deque<JsonObject> QUEUE = new ArrayDeque<>();
    private static double tokens = 8.0;
    private static long lastRefillMs = System.currentTimeMillis();

    private PartyDataFeature() {
    }

    public static void register() {
        RelayClient.addListener(new Listener());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LOGGER.info("[PartyData] Registered");
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (inDungeon != wasInDungeon) {
            wasInDungeon = inDungeon;
            SENT.clear();
            LAST_DRAGON_STATE.clear();
            QUEUE.clear();
            PartyRoomIntel.reset();
        }
        drainQueue();
        if (!inDungeon || !publishActive()) {
            return;
        }
        if (++tickCounter < SCAN_TICKS) {
            return;
        }
        tickCounter = 0;
        noteDragonTransitions();
        publishSnapshot();
    }

    /** Everything publishing needs, besides "we found something new to say" - see the class doc. */
    private static boolean publishActive() {
        if (!PartyDataConfig.getInstance().isShareEnabled()) {
            return false;
        }
        InteropConfig cfg = InteropConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isRelayData()) {
            return false;
        }
        return inPartyRoom();
    }

    private static boolean inPartyRoom() {
        return RelayClient.isConnected() && RelayClient.room().startsWith("party:");
    }

    // ------------------------------------------------------------------ publish: what we know ourselves

    /** Wire keys, public so {@code com.killer560.hub.bridge} (the Cross-Mod Bridge) can tell facts apart
     *  without reaching into the package-private {@link PartyDataProtocol}. */
    public static final String KEY_FLAG = PartyDataProtocol.KEY_FLAG;
    public static final String KEY_COUNTER = PartyDataProtocol.KEY_COUNTER;
    public static final String KEY_ROOM_SECRETS = PartyDataProtocol.KEY_ROOM_SECRETS;
    public static final String KEY_ROOM = PartyDataProtocol.KEY_ROOM;
    public static final String KEY_DOOR = PartyDataProtocol.KEY_DOOR;
    public static final String KEY_DRAGON = PartyDataProtocol.KEY_DRAGON;
    public static final String KEY_MELODY = PartyDataProtocol.KEY_MELODY;

    /**
     * One fact this client worked out itself, already in {@code dg.v1.*} wire form. {@code id} names WHICH
     * fact it is (e.g. {@code flag:mimic}, {@code roomSecrets:Water Board}, {@code door:37}) so a consumer can
     * de-duplicate on "same id, same payload" without knowing the schema.
     */
    public record SelfFact(String key, String id, JsonObject payload) {
    }

    /**
     * Every SELF fact this feature publishes, as of right now - the single source of truth for "what do we
     * know ourselves", shared by the relay publisher below and by the Cross-Mod Bridge
     * ({@code com.killer560.hub.bridge}), which translates the same list into Devonian's / NoammAddons' own
     * formats instead of re-deriving any of it. Pure read, no side effects, no dedupe - callers keep their own
     * "already sent" state. Client thread only ({@link DungeonLayout#current()}).
     * <p>
     * Sources, unchanged from before this was factored out: {@link PartyInteropState}'s SELF-sourced flags,
     * counters and room secrets; {@link WitherDragon}'s public state (a SPAWNING or ALIVE dragon, with its spawn
     * count in the id so each new spawn is a new fact); and {@link DungeonLayout#current()}'s named rooms and
     * doors.
     */
    public static List<SelfFact> selfFactSnapshot() {
        List<SelfFact> out = new ArrayList<>();
        for (PartyInteropState.Flag flag : PartyInteropState.Flag.values()) {
            PartyInteropState.Fact<Boolean> fact = PartyInteropState.flagFact(flag);
            if (fact != null && fact.source() == InteropSource.SELF) {
                String wire = PartyDataProtocol.wireName(flag);
                out.add(new SelfFact(KEY_FLAG, "flag:" + wire, PartyDataProtocol.flagPayload(wire)));
            }
        }
        for (PartyInteropState.Counter counter : PartyInteropState.Counter.values()) {
            PartyInteropState.Fact<Integer> fact = PartyInteropState.counterFact(counter);
            if (fact != null && fact.source() == InteropSource.SELF) {
                String wire = PartyDataProtocol.wireName(counter);
                out.add(new SelfFact(KEY_COUNTER, "counter:" + wire,
                        PartyDataProtocol.counterPayload(wire, fact.value())));
            }
        }
        for (Map.Entry<String, PartyInteropState.Fact<PartyInteropState.RoomSecrets>> entry
                : PartyInteropState.roomSnapshot().entrySet()) {
            PartyInteropState.Fact<PartyInteropState.RoomSecrets> fact = entry.getValue();
            if (fact.source() == InteropSource.SELF) {
                out.add(new SelfFact(KEY_ROOM_SECRETS, "roomSecrets:" + entry.getKey(),
                        PartyDataProtocol.roomSecretsPayload(entry.getKey(), fact.value().found(), fact.value().total())));
            }
        }
        for (WitherDragon d : WitherDragon.values()) {
            WitherDragon.State state = d.state();
            if (state != WitherDragon.State.SPAWNING && state != WitherDragon.State.ALIVE) {
                continue;
            }
            String colour = d.colourName.toLowerCase(java.util.Locale.ROOT);
            String event = state == WitherDragon.State.SPAWNING ? "spawning" : "alive";
            // timesSpawned only ticks up on ALIVE (WitherDragonsFeature), so ids run "spawning:n", "alive:n+1",
            // then the next spawn's "spawning:n+1" - every real transition gets its own id, i.e. exactly the
            // edges the old scanDragons() sent.
            out.add(new SelfFact(KEY_DRAGON, "dragon:" + colour + ":" + event + ":" + d.timesSpawned(),
                    PartyDataProtocol.dragonPayload(colour, event)));
        }
        DungeonLayout layout = DungeonLayout.current();
        for (int room = 0; room < layout.roomCount(); room++) {
            String name = layout.name(room);
            if (name == null || name.isBlank() || "Unknown".equals(name)) {
                continue;
            }
            int col = Math.round(layout.labelGX(room));
            int row = Math.round(layout.labelGZ(room));
            if (col < 0 || col >= DungeonLayout.GRID || row < 0 || row >= DungeonLayout.GRID) {
                continue;
            }
            BlockPos centre = DungeonLayout.cellCenter(row * DungeonLayout.GRID + col);
            out.add(new SelfFact(KEY_ROOM, "room:" + room + ":" + name,
                    PartyDataProtocol.roomPayload(name, centre.getX(), centre.getZ(), col, row)));
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!layout.isDoor(idx)) {
                continue;
            }
            String type = PartyDataProtocol.doorTypeWireName(layout.doorType(idx));
            if (type == null) {
                continue;
            }
            BlockPos pos = DungeonLayout.doorBlock(idx);
            out.add(new SelfFact(KEY_DOOR, "door:" + idx,
                    PartyDataProtocol.doorPayload(pos.getX(), pos.getZ(), idx % DungeonLayout.GRID,
                            idx / DungeonLayout.GRID, type)));
        }
        // Melody (2026-09-21, com.killer560.hub.melody): our own read-only tracker's own reading of our own
        // open terminal, gated by its own "Share My Progress" toggle on top of this feature's master one -
        // unlike every other fact above, sharing your live terminal clicks is more exposing than passive
        // room/door/flag data, so it gets its own opt-in rather than riding the master toggle alone.
        if (com.killer560.hub.melody.MelodyHudConfig.getInstance().isShareProgress()) {
            com.killer560.hub.melody.MelodyTrackerFeature.SelfMelody self =
                    com.killer560.hub.melody.MelodyTrackerFeature.selfSnapshot();
            if (self != null) {
                if (self.clayRow() >= com.killer560.hub.bridge.BridgeTables.MELODY_MIN_CLAY_ROW
                        && self.clayRow() <= com.killer560.hub.bridge.BridgeTables.MELODY_MAX_CLAY_ROW) {
                    out.add(new SelfFact(KEY_MELODY, "melody:clay", PartyDataProtocol.melodyPayload(
                            com.killer560.hub.bridge.BridgeTables.MELODY_TYPE_CLAY, self.clayRow())));
                }
                if (self.target() >= com.killer560.hub.bridge.BridgeTables.MELODY_MIN_COLUMN
                        && self.target() <= com.killer560.hub.bridge.BridgeTables.MELODY_MAX_COLUMN) {
                    out.add(new SelfFact(KEY_MELODY, "melody:target", PartyDataProtocol.melodyPayload(
                            com.killer560.hub.bridge.BridgeTables.MELODY_TYPE_PURPLE, self.target())));
                }
                if (self.current() >= com.killer560.hub.bridge.BridgeTables.MELODY_MIN_COLUMN
                        && self.current() <= com.killer560.hub.bridge.BridgeTables.MELODY_MAX_COLUMN) {
                    out.add(new SelfFact(KEY_MELODY, "melody:current", PartyDataProtocol.melodyPayload(
                            com.killer560.hub.bridge.BridgeTables.MELODY_TYPE_PANE, self.current())));
                }
            }
        }
        return out;
    }

    /** Queues every fact in {@link #selfFactSnapshot()} whose payload differs from what was last queued for
     *  its id this run. A fact is only marked sent once {@link #enqueue} accepts it. */
    private static void publishSnapshot() {
        for (SelfFact fact : selfFactSnapshot()) {
            String json = fact.payload().toString();
            if (json.equals(SENT.get(fact.id()))) {
                continue;
            }
            if (enqueue(fact.key(), fact.payload())) {
                SENT.put(fact.id(), json);
            }
        }
    }

    /** The one side effect the old per-type dragon scan had: a transition is also offered into
     *  {@link PartyInteropState} as SELF so the Interop tab shows it. Same edge logic as before, minus the send
     *  (which now comes from {@link #selfFactSnapshot()} like every other fact). */
    private static void noteDragonTransitions() {
        for (WitherDragon d : WitherDragon.values()) {
            WitherDragon.State state = d.state();
            WitherDragon.State last = LAST_DRAGON_STATE.get(d);
            if (state == last) {
                continue;
            }
            LAST_DRAGON_STATE.put(d, state); // track DEAD too, so the next SPAWNING is still an edge
            if (state == WitherDragon.State.SPAWNING || state == WitherDragon.State.ALIVE) {
                // Never called anywhere before this feature - see the class doc's dragon bullet.
                PartyInteropState.offerDragonSpawn(d.colourName.toLowerCase(java.util.Locale.ROOT),
                        InteropSource.SELF, null);
            }
        }
    }

    // ------------------------------------------------------------------ publish: send queue

    /** @return true if the packet was queued (i.e. will be sent) - callers only mark a fact "sent" once this
     *  returns true, so a full queue does not silently drop a dedupe entry for a fact that never went out. */
    private static boolean enqueue(String key, JsonObject payload) {
        if (QUEUE.size() >= MAX_QUEUE) {
            return false;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("key", key);
        packet.add("payload", payload);
        QUEUE.add(packet);
        return true;
    }

    private static void drainQueue() {
        if (QUEUE.isEmpty()) {
            return;
        }
        refillTokens();
        while (!QUEUE.isEmpty() && tokens >= 1.0) {
            if (!publishActive()) {
                // Sharing (or the relay connection) turned off mid-run - drop what's queued rather than
                // send it the moment it turns back on with a stale run's facts.
                QUEUE.clear();
                return;
            }
            JsonObject packet = QUEUE.poll();
            String key = packet.get("key").getAsString();
            JsonElement payload = packet.get("payload");
            if (RelayClient.sendData(key, payload)) {
                tokens -= 1.0;
            } else {
                // Not actually connected after all (e.g. dropped between the gate check and here) - stop
                // for this tick rather than spin through the rest of the queue failing the same way.
                QUEUE.addFirst(packet);
                return;
            }
        }
    }

    private static void refillTokens() {
        long now = System.currentTimeMillis();
        double elapsedSec = Math.max(0, now - lastRefillMs) / 1000.0;
        lastRefillMs = now;
        tokens = Math.min(8.0, tokens + elapsedSec * 2.0); // 2/sec refill, well under the relay's 4/sec
    }

    // ------------------------------------------------------------------ consume

    private static final class Listener implements RelayListener {
        @Override
        public void onData(String from, String key, JsonElement value) {
            if (key == null || from == null || from.isBlank()) {
                return;
            }
            Minecraft.getInstance().execute(() -> handleData(from, key, value));
        }
    }

    private static void handleData(String from, String key, JsonElement value) {
        InteropConfig cfg = InteropConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isRelayData() || !DungeonState.isInDungeon()) {
            return;
        }
        // The relay only ever delivers a packet to sockets already in the room it broadcasts to, so being
        // here already proves the sender shares our room - this is a second, cheap check that the room
        // itself is a party (never a lobby-wide) one, per the class doc.
        if (!inPartyRoom()) {
            return;
        }
        switch (key) {
            case PartyDataProtocol.KEY_FLAG -> {
                PartyInteropState.Flag flag = PartyDataProtocol.readFlag(value);
                if (flag != null) {
                    PartyInteropState.offerFlag(flag, InteropSource.RELAY, from);
                }
            }
            case PartyDataProtocol.KEY_COUNTER -> {
                PartyInteropState.Counter counter = PartyDataProtocol.readCounterName(value);
                int v = PartyDataProtocol.readCounterValue(value);
                if (counter != null && v >= 0) {
                    PartyInteropState.offerCounter(counter, v, InteropSource.RELAY, from);
                }
            }
            case PartyDataProtocol.KEY_ROOM_SECRETS -> {
                String room = PartyDataProtocol.readRoomName(value);
                int found = PartyDataProtocol.readFound(value);
                int total = PartyDataProtocol.readTotal(value);
                if (room != null && found >= 0 && total != -2) {
                    PartyInteropState.offerRoomSecrets(room, found, total, InteropSource.RELAY, from);
                }
            }
            case PartyDataProtocol.KEY_ROOM -> {
                PartyDataProtocol.RoomFields fields = PartyDataProtocol.readRoom(value);
                if (fields != null) {
                    PartyRoomIntel.offerRoom(fields.name(), fields.x(), fields.z(), fields.col(), fields.row(), from);
                }
            }
            case PartyDataProtocol.KEY_DOOR -> {
                PartyDataProtocol.DoorFields fields = PartyDataProtocol.readDoor(value);
                if (fields != null) {
                    PartyRoomIntel.offerDoor(fields.x(), fields.z(), fields.col(), fields.row(), fields.type(), from);
                }
            }
            case PartyDataProtocol.KEY_DRAGON -> {
                String colour = PartyDataProtocol.readDragonColour(value);
                String event = PartyDataProtocol.readDragonEvent(value);
                if (colour != null && event != null) {
                    PartyInteropState.offerDragonSpawn(colour, InteropSource.RELAY, from);
                }
            }
            case PartyDataProtocol.KEY_PLAYER_SECRETS -> {
                int secrets = PartyDataProtocol.readPlayerSecrets(value);
                if (secrets >= 0) {
                    PartyRoomIntel.offerPlayerSecrets(from, secrets);
                }
            }
            case PartyDataProtocol.KEY_MELODY -> {
                // Same store BridgeFeature.applyOdin already writes teammates' Odin-sourced progress into -
                // one place for the Team Melody HUD regardless of which of our own users' Melody state came
                // from. "from" is the relay-verified sender, never a claimed field in the payload.
                Integer type = PartyDataProtocol.readMelodyType(value);
                if (type != null) {
                    int slot = PartyDataProtocol.readMelodySlot(value, type);
                    if (slot >= 0) {
                        com.killer560.hub.bridge.MelodyIntel.offer(from, type, slot);
                    }
                }
            }
            default -> {
                // Unknown key (older/newer protocol version, or a foreign client) - ignored, not logged, so
                // a party with a mismatched build doesn't spam warnings every packet.
            }
        }
    }
}
