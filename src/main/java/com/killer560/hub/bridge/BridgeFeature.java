package com.killer560.hub.bridge;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.interop.InteropConfig;
import com.killer560.hub.interop.InteropSource;
import com.killer560.hub.interop.PartyInteropState;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.partydata.PartyDataFeature;
import com.killer560.hub.partydata.PartyRoomIntel;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-Mod Bridge: killer560s-mod speaks Devonian's and Odin's own party sockets, so party data
 * flows between our users and theirs in both directions. killer560 (2026-09-21): "I will never have access to
 * joy's bridge so I need you to build it", "Also make it work for Odin", "I really want everything to tie into
 * my mod."
 * <p>
 * The protocols are documented, source-cited, in the staging research ({@code BRIDGE-PROTOCOLS.md}); every
 * identifier translation lives in {@link BridgeTables}; the wire formats are {@link DevonianCodec} and
 * {@link OdinCodec}; the connection rules shared by both are in
 * {@link SocketAdapter}. This class is the client-thread glue:
 * <ul>
 * <li><b>Out:</b> every 5 ticks it reads a {@link BridgeContext} - including
 * {@link PartyDataFeature#selfFactSnapshot()}, the same SELF facts our own relay publishes, so nothing is
 * re-derived - and hands it to each adapter, which translates and queues what its mod understands.</li>
 * <li><b>In:</b> adapters decode and validate on their own threads, then call back here on the client thread,
 * where facts go into {@link PartyInteropState} as {@link InteropSource#SOCKET} (below our own relay and the
 * local-mod bridge, above party chat) or into {@link PartyRoomIntel}/{@link MelodyIntel}.</li>
 * </ul>
 * <b>Hard rules</b> (all enforced in the adapters' {@code decide}): never connect to a mod's socket when that
 * mod is installed here - it already holds the connection and a duplicate might kick it; Hypixel only; only while
 * the grouping applies; only while the per-mod toggle is on; never any identity but the signed-in account's own.
 * <p>
 * <b>No NoammAddons socket.</b> NoammAddons' socket ({@code ws.noamm.org}) is for NoammAddons' own users only: its
 * owner asked us (2026-11-01) to stop authenticating into it, so this mod never opens it, never calls its auth
 * endpoint, and {@code util/ModNet} refuses both outright. A player who also runs NoammAddons still gets what
 * NoammAddons' own connection learned, read in-process by {@code interop/LocalModBridge}.
 */
public final class BridgeFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bridge");
    private static final int TICK_DIVISOR = 5;
    private static final int SERVER_CODE_DIVISOR = 20;

    private static final DevonianAdapter DEVONIAN = new DevonianAdapter();
    private static final OdinAdapter ODIN = new OdinAdapter();
    private static final List<SocketAdapter> ADAPTERS = List.of(DEVONIAN, ODIN);

    private static int tickCounter;
    private static int serverCodeCounter;
    private static String serverCode;
    private static Object serverCodeLevel;
    private static boolean wasInDungeon;
    private static int runEpoch;
    private static volatile boolean p3Active;
    /** Client thread: a failure in {@link #context} / an inbound apply has already been logged at WARN. */
    private static boolean warnedContext;
    private static boolean warnedInbound;

    private BridgeFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BridgeFeature.tick", BridgeFeature::tick));
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
        LOGGER.info("[Bridge] Registered");
    }

    /** For the settings tab. */
    public static List<SocketAdapter> adapters() {
        return ADAPTERS;
    }

    /** A toggle changed: let a "gave up for this session" adapter try again on demand. */
    public static void onSettingsChanged() {
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.resetSession();
        }
    }

    /** Test hook: when set, every bridge tick hands this context to the adapters instead of reading the game. */
    private static volatile BridgeContext contextOverrideForTest;

    /**
     * Test hook (testkit, by reflection): {@code ctx} replaces the context every bridge tick reads, until set back to
     * null, so a harness can put the bridge in a Hypixel-shaped run (which a local test server never is) and read
     * {@code ModNet.attemptsForTest()} to see which sockets it tried to open. Never set by the mod itself.
     */
    public static void setContextOverrideForTest(BridgeContext ctx) {
        contextOverrideForTest = ctx;
    }

    private static void tick(Minecraft client) {
        boolean inDungeon = DungeonState.isInDungeon() && !DungeonState.isSimOverrideActive();
        if (inDungeon != wasInDungeon) {
            wasInDungeon = inDungeon;
            if (inDungeon) {
                runEpoch++;
            }
            p3Active = false;
            MelodyIntel.clear();
        }
        if (++tickCounter < TICK_DIVISOR) {
            return;
        }
        tickCounter = 0;
        BridgeContext ctx = contextOverrideForTest;
        try {
            if (ctx == null) {
                ctx = context(client, inDungeon);
            }
        } catch (Throwable t) {
            // WARN the first time (DEBUG never reaches the log), then keep trying every tick quietly.
            if (!warnedContext) {
                warnedContext = true;
                LOGGER.warn("[Bridge] context failed (logged once per session): {}", t.toString(), t);
            }
            return;
        }
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.clientTick(ctx);
        }
    }

    private static void onChat(String plain) {
        if (plain == null || plain.isEmpty() || !plain.startsWith("[BOSS] Goldor: ") && !plain.startsWith("The Core ")) {
            return; // P3_START / CORE_OPEN are anchored on these (fps-idle-1015)
        }
        if (OdinCodec.P3_START.matcher(plain).matches()) {
            p3Active = true;
        } else if (OdinCodec.CORE_OPEN.matcher(plain).matches()) {
            // spec 3.3: Odin shuts its socket and clears every player's melody here
            p3Active = false;
            MelodyIntel.clear();
        }
    }

    private static BridgeContext context(Minecraft client, boolean inDungeon) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        boolean anyOn = cfg.isEnabled() && (cfg.isDevonian() || cfg.isOdin());
        if (!anyOn || client.player == null || client.level == null) {
            return new BridgeContext(false, false, false, null, null, null, List.of(), null, null,
                    List.of(), false, runEpoch);
        }
        boolean active = InteropConfig.getInstance().isEnabled();
        boolean onHypixel = onHypixel(client);

        String selfName = null;
        UUID selfUuid = null;
        if (client.getUser() != null && client.getUser().getName() != null
                && client.getUser().getName().equals(client.player.getGameProfile().name())) {
            selfName = client.getUser().getName();
            selfUuid = client.getUser().getProfileId();
        }
        List<String> teammates = List.copyOf(PartyTracker.teammates());

        if (++serverCodeCounter >= SERVER_CODE_DIVISOR / TICK_DIVISOR || client.level != serverCodeLevel) {
            serverCodeCounter = 0;
            if (client.level != serverCodeLevel) {
                serverCodeLevel = client.level;
                serverCode = null;
            }
            String code = readServerCode(client);
            if (code != null) {
                serverCode = code;
            }
        }

        Integer partyHash = inDungeon && onHypixel ? devonianPartyHash(client, selfUuid, teammates) : null;

        List<PartyDataFeature.SelfFact> selfFacts = inDungeon && active && onHypixel && DEVONIAN.isReady()
                ? PartyDataFeature.selfFactSnapshot() : List.of();
        return new BridgeContext(active, onHypixel, inDungeon, DungeonState.getFloor(), selfName, selfUuid,
                teammates, partyHash, serverCode, selfFacts, p3Active, runEpoch);
    }

    /** hypixel.net only. p3sim has none of the identifiers these sockets group by (no Hypixel party for
     *  Devonian's Mod-API hash, no Hypixel server code for Odin), so it is deliberately excluded. */
    private static boolean onHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    /**
     * Devonian's party hash (spec 1.3), or null unless it can be computed EXACTLY: our UUID plus every party
     * mate's, each resolved from the player list Hypixel sends this client. Devonian itself reads the member set
     * from Hypixel's Mod API party packet, which this mod does not depend on; inside a dungeon the whole party is
     * in this instance, so their real UUIDs are known. Anything unresolvable -> null -> do not connect (a wrong
     * set would just be a room nobody else is in). Hypixel's fake tab-list entries use version-2 UUIDs; a real
     * account is always version 4, so only those count.
     */
    static Integer devonianPartyHash(Minecraft client, UUID self, List<String> teammates) {
        ClientPacketListener connection = client.getConnection();
        if (self == null || connection == null || teammates.isEmpty() || teammates.size() > 4) {
            return null;
        }
        List<UUID> members = new ArrayList<>(teammates.size() + 1);
        members.add(self);
        for (String name : teammates) {
            PlayerInfo info = connection.getPlayerInfo(name);
            if (info == null || info.getProfile() == null) {
                return null;
            }
            UUID id = info.getProfile().id();
            if (id == null || id.version() != 4 || members.contains(id)) {
                return null;
            }
            members.add(id);
        }
        return DevonianCodec.partyHash(members);
    }

    /** The sidebar date line's server code, exactly the way Odin reads it: each scoreboard team's prefix + suffix, formatting stripped, through the shared regex. */
    private static String readServerCode(Minecraft client) {
        try {
            for (PlayerTeam team : client.level.getScoreboard().getPlayerTeams()) {
                String text = ChatFormatting.stripFormatting(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
                String code = OdinCodec.serverCode(text);
                if (code != null) {
                    return code;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ inbound (client thread)

    static void applyOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                // WARN the first time (DEBUG never reaches the log); later messages are still applied.
                if (!warnedInbound) {
                    warnedInbound = true;
                    LOGGER.warn("[Bridge] inbound failed (logged once per session): {}", t.toString(), t);
                }
            }
        });
    }

    /** Inbound facts are per-run facts: only while this client is itself in a real run with Interop on. */
    private static boolean acceptInbound() {
        return BridgeConfig.getInstance().isEnabled() && InteropConfig.getInstance().isEnabled()
                && DungeonState.isInDungeon() && !DungeonState.isSimOverrideActive();
    }

    /** The signed-in account's own UUID when it is the player in the world (as {@link #context} reads it), else null. */
    private static UUID selfUuid(Minecraft client) {
        if (client.player == null || client.getUser() == null || client.getUser().getName() == null
                || !client.getUser().getName().equals(client.player.getGameProfile().name())) {
            return null;
        }
        return client.getUser().getProfileId();
    }

    /**
     * Team check for a Devonian frame: Devonian's {@code RoomSecrets} carries no sender, so the frame counts only
     * if it came in on the group whose party hash is exactly this team's right now (self plus every current
     * teammate). A frame from a group left behind by a party change, or with no party, is dropped.
     */
    private static boolean devonianGroupIsTeam(String target) {
        Minecraft client = Minecraft.getInstance();
        List<String> teammates = List.copyOf(PartyTracker.teammates());
        if (target == null || teammates.isEmpty()) {
            return false;
        }
        Integer hash = devonianPartyHash(client, selfUuid(client), teammates);
        return hash != null && target.equals(DevonianAdapter.TARGET_PREFIX + hash);
    }

    static void applyDevonian(DevonianCodec.Message msg, String target) {
        if (!acceptInbound() || !devonianGroupIsTeam(target)) {
            return;
        }
        if (msg instanceof DevonianCodec.RoomSecrets rs) {
            String name = resolveDevonianRoom(rs.devonianRoomId());
            BridgeTables.Room known = BridgeTables.room(name);
            if (known == null || rs.total() != known.secrets()) {
                return; // unmapped id, ambiguous Blaze, or a total that disagrees with the room database
            }
            PartyInteropState.offerRoomSecrets(name, rs.found(), rs.total(), InteropSource.SOCKET, "Devonian");
        } else if (msg instanceof DevonianCodec.SecretTracker st) {
            String teammate = teammate(st.ign());
            if (teammate != null) {
                PartyRoomIntel.offerPlayerSecrets(teammate, st.secrets());
            }
        }
    }

    /** Devonian id -> our room name; for the one id that covers two of ours (Blaze), whichever of the two our
     *  own map scan found this run - or null if neither/both (never a guess). */
    private static String resolveDevonianRoom(int devonianId) {
        List<BridgeTables.Room> rooms = BridgeTables.roomsForDevonianId(devonianId);
        if (rooms.size() == 1) {
            return rooms.get(0).name();
        }
        if (rooms.isEmpty()) {
            return null;
        }
        Set<String> onMap = new HashSet<>();
        DungeonLayout layout = DungeonLayout.current();
        for (int i = 0; i < layout.roomCount(); i++) {
            onMap.add(layout.name(i));
        }
        String match = null;
        for (BridgeTables.Room room : rooms) {
            if (onMap.contains(room.name())) {
                if (match != null) {
                    return null;
                }
                match = room.name();
            }
        }
        return match;
    }

    static void applyOdin(OdinCodec.Update update) {
        if (!acceptInbound() || !DungeonState.isF7OrM7()) {
            return;
        }
        // Odin has no authentication (spec 3.2): only names our own party list vouches for, never ourselves.
        String teammate = teammate(update.ign());
        if (teammate != null) {
            MelodyIntel.offer(teammate, update.type(), update.slot());
        }
    }

    /** @return the party list's spelling of this name if it is a current teammate (not us), else null. */
    private static String teammate(String ign) {
        if (ign == null) {
            return null;
        }
        for (String name : PartyTracker.teammates()) {
            if (name.equalsIgnoreCase(ign)) {
                return name;
            }
        }
        return null;
    }

    /** Case-preserving (unlike {@code PartyDataProtocol}'s reader, which lower-cases). */
    static String str(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    static Integer integer(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= 1_000_000 ? (int) d : null;
        } catch (Exception e) {
            return null;
        }
    }
}
