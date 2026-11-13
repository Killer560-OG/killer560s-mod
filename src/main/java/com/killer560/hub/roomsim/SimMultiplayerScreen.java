package com.killer560.hub.roomsim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Dungeon Sim menu's Multiplayer entry (co-op phase 2, docs/SIM-COOP.md "Multiplayer"): Create a room, Join one
 * (the relay's public list, a password for a locked one, an address, or a LAN world), and the Session page - the owner's
 * players with Kick, what guests may ask for and Close Room; a guest's requests and Leave. A guest sees only the
 * Session page: the builders are the owner's.
 */
public final class SimMultiplayerScreen extends Screen {

    enum View { CREATE, JOIN, SESSION, INVITE }

    /** One listed room, as the relay gave it; {@code mc} is the host's Minecraft version (every version is listed). */
    record Room(String id, String owner, String name, boolean locked, int players, int max, String modVersion,
                int protocol, String mode, String mc, String path) {
        /** A room on another Minecraft version: vanilla refuses a different network protocol, so Join is blocked. */
        boolean otherVersion() { return !mc.isEmpty() && !mc.equals(SimGuest.mc()); }
    }

    private static final int ROW = 22;
    /** Client ticks between two automatic room list fetches while Join is open (free plan: no faster). */
    private static final int LIST_EVERY = 20 * 30;

    private final Screen parent;
    private View view;
    private SimMenuNav nav;
    private String message = "";
    private int messageColor = MenuStyle.TXT_SOFT;

    // Create
    private CommitEditBox nameBox, passwordBox;
    private int maxPlayers;
    // Join
    private static volatile List<Room> rooms = List.of();
    private static volatile String listState = "";
    private Room passwordFor;
    private CommitEditBox joinPassword, addressBox;
    private int listTicks;
    private final LanServerDetection.LanServerList lanList = new LanServerDetection.LanServerList();
    private LanServerDetection.LanServerDetector lanDetector;
    private final List<LanServer> lanServers = new ArrayList<>();
    // Session
    private String guestKey = "";
    private CommitEditBox roomBox, speedBox;
    private int itemIndex;
    // Invite (owner): who the relay says can take an invite now, from friends, Best Friends and mod users
    private static volatile List<String> reachable = List.of();
    private static volatile String reachState = "";
    private static long lastReachMs;
    private static final java.util.Set<String> INVITED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private CommitEditBox inviteBox;
    private int pendingKey;

    public SimMultiplayerScreen(Screen parent) {
        this(parent, null);
    }

    SimMultiplayerScreen(Screen parent, View view) {
        super(Component.literal("Sim Multiplayer"));
        this.parent = parent;
        this.view = view;
        this.maxPlayers = SimCoopConfig.get().maxPlayers();
    }

    private View shown() {
        Minecraft mc = Minecraft.getInstance();
        if (SimGuest.isGuest(mc)) return View.SESSION;
        if (SimHost.hosting()) return view == View.INVITE ? View.INVITE : View.SESSION;
        return view == null || view == View.SESSION ? View.CREATE : view;
    }

    @Override
    protected void init() {
        nav = new SimMenuNav(this.width, this.height);
        Minecraft mc = this.minecraft;
        boolean guest = SimGuest.isGuest(mc);
        if (guest) {
            nav.addBack(this::addRenderableWidget, parent);
        } else {
            nav.addItems(this::addRenderableWidget, null, parent, null);
        }
        View v = shown();
        int x = nav.contentX, y = nav.contentY + 20, w = nav.contentW;
        if (v == View.CREATE || v == View.JOIN) {
            int half = (w - 6) / 2;
            addRenderableWidget(tabButton("Create", v == View.CREATE, x, y, half, View.CREATE));
            addRenderableWidget(tabButton("Join", v == View.JOIN, x + half + 6, y, w - half - 6, View.JOIN));
            y += ROW + 6;
        }
        switch (v) {
            case CREATE -> initCreate(x, y, w);
            case JOIN -> initJoin(x, y, w);
            case SESSION -> {
                if (guest) initGuest(x, y, w); else initOwner(x, y, w);
            }
            case INVITE -> initInvite(x, y, w);
        }
        if (v == View.JOIN && lanDetector == null) startLan();
    }

    private SettingsButtonWidget tabButton(String label, boolean on, int x, int y, int w, View to) {
        return SettingsButtonWidget.builder(Component.literal(on ? "> " + label + " <" : label), b -> {
            view = to;
            message = "";
            rebuildWidgets();
            if (to == View.JOIN) fetchRooms();
        }).bounds(x, y, w, 20).build();
    }

    // ---- Create ----

    private void initCreate(int x, int y, int w) {
        int lw = 110;
        nameBox = box(x + lw, y, w - lw, 32, "Room Name", nameBox == null ? SimCoopConfig.get().roomName() : nameBox.getValue());
        y += ROW;
        passwordBox = box(x + lw, y, w - lw, 64, "Password (optional)", passwordBox == null ? "" : passwordBox.getValue());
        y += ROW;
        addRenderableWidget(new ThemedSliderButton(x, y, w, 20, Component.literal("Max Players: " + maxPlayers),
                (maxPlayers - 2) / 3.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Max Players: " + (2 + (int) Math.round(this.value * 3.0))));
            }

            @Override
            protected void applyValue() {
                maxPlayers = 2 + (int) Math.round(this.value * 3.0);
            }
        }.steps(3));
        y += ROW;
        addStartAt(x, y, w, false);
        y += ROW + 4;
        SettingsButtonWidget create = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Create Room"),
                b -> create()).bounds(x, y, w, 20).build());
        nameBox.pressOnEnter(create);
        passwordBox.pressOnEnter(create);
    }

    /** The start portion, from the single-player sim's own list; in a running room a click moves everyone there. */
    private void addStartAt(int x, int y, int w, boolean live) {
        var starts = com.killer560.hub.bosssim.BossFight.Start.values();
        Component[] label = new Component[1];
        label[0] = startLabel(live);
        int btnW = live ? w - 54 : w;
        addRenderableWidget(SettingsButtonWidget.cycling(startLabel(live), (b, dir) -> {
            var cur = SimCoopConfig.get().startAt();
            SimCoopConfig.get().setStartAt(starts[SettingsButtonWidget.step(cur.ordinal(), dir, starts.length)]);
            b.setMessage(startLabel(live));
        }).bounds(x, y, btnW, live ? 18 : 20).build());
        if (live) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Move"),
                    b -> SimHost.moveTo(this.minecraft, SimCoopConfig.get().startAt())).bounds(x + w - 50, y, 50, 18).build());
        }
    }

    private static Component startLabel(boolean live) {
        return Component.literal((live ? "Move everyone to: " : "Start at: ")
                + com.killer560.hub.bosssim.BossStartScreen.label(SimCoopConfig.get().startAt()));
    }

    private void create() {
        String why = SimHost.create(this.minecraft, nameBox.getValue(), passwordBox.getValue(), maxPlayers);
        if (why != null) {
            say(why, MenuStyle.RED);
            return;
        }
        if (SimHost.hosting()) {
            rebuildWidgets();
        }
        // Else the sim world is opening; its loading screen takes over and Create publishes once it is up.
    }

    // ---- Join ----

    private void initJoin(int x, int y, int w) {
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), b -> fetchRooms())
                .bounds(x + w - 70, y, 70, 18).build());
        y += ROW;
        int bottom = nav.contentBottom - ROW * 4;
        pendingKey = SimReach.pending().hashCode();
        for (SimReach.Invite inv : SimReach.pending()) {
            if (y + ROW > bottom) break;
            int bw = 56;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Invite: " + inv.from() + " - " + inv.room()),
                    b -> SimReach.act(this.minecraft, "join", inv.id())).bounds(x, y, w - 2 * (bw + 4), 20).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Decline"), b -> {
                SimReach.act(this.minecraft, "decline", inv.id());
                rebuildWidgets();
            }).bounds(x + w - 2 * bw - 4, y, bw, 20).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Block"), b -> {
                SimReach.act(this.minecraft, "block", inv.id());
                rebuildWidgets();
            }).bounds(x + w - bw, y, bw, 20).build());
            y += ROW;
        }
        int listed = 0;
        for (Room r : rooms) {
            if (y + ROW > bottom) break;
            String label = "[" + (r.mc().isEmpty() ? "?" : r.mc()) + (r.otherVersion() ? " - other version] " : "] ")
                    + (r.locked() ? "[Locked] " : "") + r.name() + "  -  " + r.owner() + "  " + r.players() + "/"
                    + r.max() + "  " + r.mode() + "  v" + r.modVersion() + pathTag(r.path());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal(label), b -> pick(r))
                    .bounds(x, y, w, 20).build());
            y += ROW;
            listed++;
        }
        synchronized (lanServers) {
            for (LanServer s : lanServers) {
                if (y + ROW > bottom) break;
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal("[LAN] " + s.getMotd() + "  " + s.getAddress()),
                        b -> connect(s.getAddress(), s.getMotd())).bounds(x, y, w, 20).build());
                y += ROW;
            }
        }
        y = Math.max(y, bottom) + 2;
        if (passwordFor != null) {
            joinPassword = box(x + 110, y, w - 110 - 64, 64, "Password", joinPassword == null ? "" : joinPassword.getValue());
            SettingsButtonWidget go = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Join"),
                    b -> join(passwordFor, joinPassword.getValue())).bounds(x + w - 60, y, 60, 18).build());
            joinPassword.pressOnEnter(go);
            setFocused(joinPassword);
            y += ROW;
        }
        addressBox = box(x + 110, y, w - 110 - 64, 255, "Address (host:port)", addressBox == null ? "" : addressBox.getValue());
        SettingsButtonWidget go = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Join"),
                b -> joinAddress()).bounds(x + w - 60, y, 60, 18).build());
        addressBox.pressOnEnter(go);
        if (listed == 0 && listState.isEmpty()) listState = "No rooms listed.";
    }

    /** Fetches the public list (on opening Join, on Refresh and at most every 30 s while open). */
    private void fetchRooms() {
        listTicks = 0;
        listState = "Loading rooms...";
        SimRooms.list(SimGuest.mc()).whenComplete((rep, err) -> {
            List<Room> out = new ArrayList<>();
            if (rep != null && rep.status() == 200 && rep.body() != null && rep.body().has("rooms")) {
                for (JsonElement e : rep.body().getAsJsonArray("rooms")) {
                    JsonObject o = e.getAsJsonObject();
                    out.add(new Room(str(o, "id"), str(o, "owner"), str(o, "name"), o.has("locked") && o.get("locked").getAsBoolean(),
                            num(o, "players"), num(o, "max"), str(o, "modVersion"), num(o, "protocol"), str(o, "mode"), str(o, "mc"),
                            str(o, "path")));
                }
                listState = out.isEmpty() ? "No rooms listed." : "";
            } else {
                listState = rep == null ? "Could not reach the room list." : SimRooms.refusal(rep);
            }
            rooms = List.copyOf(out);
            Minecraft.getInstance().execute(() -> {
                if (McCompat.screen(Minecraft.getInstance()) == this && shown() == View.JOIN) rebuildWidgets();
            });
        });
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    private static int num(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; }

    private void pick(Room r) {
        if (r.otherVersion()) {
            say("Host is on " + r.mc() + " - open " + r.mc() + " to join.", MenuStyle.RED);
            return;
        }
        if (r.protocol() != SimCoop.PROTOCOL) {
            say("That room runs a different sim version (protocol " + r.protocol() + ").", MenuStyle.RED);
            return;
        }
        if (r.locked()) {
            if (passwordFor == null || !passwordFor.id().equals(r.id())) joinPassword = null; // a list rebuild keeps the typing
            passwordFor = r;
            say("Type the password for " + r.name() + ".", MenuStyle.TXT_SOFT);
            rebuildWidgets();
            return;
        }
        join(r, null);
    }

    /** Asks the relay for a ticket, then connects straight to the host, or by a TCP hole punch; never through the relay. */
    void join(Room r, String password) {
        join(r.id(), r.name(), password, null);
    }

    /** {@code invite}: a received invite's key, which skips the password (phase 3). */
    void join(String roomId, String roomName, String password, String invite) {
        say("Joining " + roomName + "...", MenuStyle.TXT_SOFT);
        SimRooms.join(roomId, password, invite).whenComplete((rep, err) -> Minecraft.getInstance().execute(() -> {
            if (rep == null || rep.status() != 200 || rep.body() == null || !rep.body().has("nonce")) {
                say(rep == null ? "Could not reach the room list." : SimRooms.refusal(rep), MenuStyle.RED);
                return;
            }
            JsonObject b = rep.body();
            String direct = null;
            if (b.has("endpoint") && b.get("endpoint").isJsonObject()) {
                JsonObject e = b.getAsJsonObject("endpoint");
                direct = e.get("host").getAsString() + ":" + e.get("port").getAsInt();
            }
            String nonce = b.get("nonce").getAsString();
            long expires = b.has("expires") ? b.get("expires").getAsLong() : System.currentTimeMillis() + 120_000L;
            boolean punch = b.has("punch") && b.get("punch").getAsBoolean();
            String blocked = blockedText(direct);
            passwordFor = null;
            rebuildWidgets();
            String first = direct;
            // Direct first, then a TCP hole punch. Game traffic never goes through the relay (killer560, 2026-11-11).
            CompletableFuture.supplyAsync(() -> first != null && reachable(first))
                    .thenCompose(ok -> {
                        if (ok) return CompletableFuture.completedFuture(joined(first, "Direct"));
                        if (!punch) return CompletableFuture.completedFuture((String) null);
                        Minecraft.getInstance().execute(() -> say("Joining " + roomName + " by hole punch...", MenuStyle.TXT_SOFT));
                        return SimPunch.guest(roomId, nonce).handle((a, e) -> {
                            if (e != null) SimCoop.LOGGER.info("[SimCoop] hole punch failed ({})", SimSignal.reason(e));
                            return a == null ? null : joined(a, "Punched");
                        });
                    })
                    .whenComplete((address, failed) -> Minecraft.getInstance().execute(() -> {
                        if (address == null) {
                            SimCoop.LOGGER.info("[SimCoop] join failed: {}", failed == null ? "no direct or punched way in" : SimSignal.reason(failed));
                            say("Can't connect to " + roomName + ": " + blocked, MenuStyle.RED);
                            return;
                        }
                        SimGuest.expect(new SimGuest.Pending(roomId, nonce, expires, address, SimHost.key(roomId)));
                        connect(address, roomName);
                    }));
        }));
    }

    /** What a guest is told when neither the direct address nor the hole punch got in. */
    static String blockedText(String direct) {
        int colon = direct == null ? -1 : direct.lastIndexOf(':');
        String port = colon < 0 ? "the co-op port shown on their Host page" : "port " + direct.substring(colon + 1);
        return "the host's router blocks direct connections; the host can enable UPnP or forward " + port + ".";
    }

    /** Logs how a join got through (Direct or Punched) and hands the address on. */
    private static String joined(String address, String path) {
        SimCoop.LOGGER.info("[SimCoop] join path: {} ({})", path, address);
        lastPath = path;
        return address;
    }

    /** How the last join got through: Direct or Punched (null before one). Join page and testkit. */
    static volatile String lastPath;

    private static String pathTag(String path) {
        return switch (path) {
            case "direct" -> "  [Direct]";
            case "punch" -> "  [Punch]";
            case "none" -> "  [Blocked]";
            default -> "";
        };
    }

    /** A TCP connect to the host's public address within 2.5 s (off the render thread). */
    private static boolean reachable(String address) {
        int colon = address.lastIndexOf(':');
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))), 2_500);
            return true;
        } catch (Exception e) {
            SimCoop.LOGGER.info("[SimCoop] direct address not reachable ({})", e.toString());
            return false;
        }
    }

    private void joinAddress() {
        String a = addressBox.getValue().trim();
        if (a.isEmpty() || !ServerAddress.isValidAddress(a)) {
            say("Type an address like 192.168.1.20:25565.", MenuStyle.RED);
            return;
        }
        connect(a, a);
    }

    private void connect(String address, String label) {
        stopLan();
        ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(address),
                new ServerData(label, address, ServerData.Type.OTHER), false, null);
    }

    private void startLan() {
        try {
            lanDetector = new LanServerDetection.LanServerDetector(lanList);
            lanDetector.start();
        } catch (Exception e) {
            SimCoop.LOGGER.warn("[SimCoop] LAN discovery could not start: {}", e.toString());
        }
        fetchRooms();
    }

    private void stopLan() {
        if (lanDetector != null) {
            lanDetector.interrupt();
            lanDetector = null;
        }
    }

    // ---- Session ----

    private void initOwner(int x, int y, int w) {
        y += 4 * 11 + 4; // the room's lines, drawn
        for (SimHost.Guest g : SimHost.guests()) {
            UUID id = g.uuid();
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Kick"),
                    b -> SimHost.kick(this.minecraft, id)).bounds(x + w - 50, y, 50, 18).build());
            y += ROW;
        }
        guestKey = guestsKey();
        y = Math.max(y, nav.contentY + 20 + 4 * 11 + 4 + ROW * 2);
        for (SimRequests.Kind k : SimRequests.Kind.values()) {
            addRenderableWidget(SettingsButtonWidget.builder(onOff(k.label, SimCoopConfig.get().allows(k)), b -> {
                SimCoopConfig.get().setAllowed(k, !SimCoopConfig.get().allows(k));
                b.setMessage(onOff(k.label, SimCoopConfig.get().allows(k)));
            }).bounds(x, y, w, 18).build());
            y += 20;
        }
        y += 4;
        addStartAt(x, y, w, true);
        y += ROW;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Invite Players"), b -> {
            view = View.INVITE;
            message = "";
            rebuildWidgets();
            fetchReach(false);
        }).bounds(x, y, w, 20).build());
        y += ROW;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close Room"), b -> {
            int n = SimHost.guests().size();
            if (n == 0) {
                SimHost.close(this.minecraft);
                rebuildWidgets();
                return;
            }
            McCompat.setScreen(this.minecraft, new ConfirmScreen(ok -> {
                if (ok) SimHost.close(this.minecraft);
                McCompat.setScreen(this.minecraft, this);
            }, Component.literal("Close the room?"), Component.literal(disconnectWarning(n))));
        }).bounds(x, y, w, 20).build());
    }

    // ---- Invite (owner) ----

    private void initInvite(int x, int y, int w) {
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> {
            view = View.SESSION;
            message = "";
            rebuildWidgets();
        }).bounds(x, y, 60, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), b -> fetchReach(true))
                .bounds(x + w - 70, y, 70, 18).build());
        y += ROW + 11;
        int bottom = nav.contentBottom - ROW * 2;
        for (String name : reachable) {
            if (y + ROW > bottom) break;
            boolean sent = INVITED.contains(name.toLowerCase(java.util.Locale.ROOT));
            SettingsButtonWidget b = addRenderableWidget(SettingsButtonWidget.builder(
                    Component.literal(sent ? "Invited" : "Invite"), btn -> sendInvite(name)).bounds(x + w - 70, y, 70, 18).build());
            b.active = !sent;
            y += ROW;
        }
        y = Math.max(y, bottom) + 2;
        inviteBox = box(x + 110, y, w - 110 - 74, 16, "Invite by name", inviteBox == null ? "" : inviteBox.getValue());
        SettingsButtonWidget go = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Invite"),
                b -> sendInvite(inviteBox.getValue().trim())).bounds(x + w - 70, y, 70, 18).build());
        inviteBox.pressOnEnter(go);
    }

    /** Friends (mirrored /fl), Best Friends and mod users, at most 50 names, built off the render thread. */
    private static List<String> candidates() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<String> add = n -> {
            if (n != null && n.matches("[A-Za-z0-9_]{1,16}") && out.size() < 50) out.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), n);
        };
        com.killer560.hub.social.FriendsListConfig.getInstance().friends().forEach(f -> add.accept(f.name));
        com.killer560.hub.social.BestFriendsStore.records().forEach(r -> add.accept(r.lastKnownName));
        com.killer560.hub.modusers.ModUsers.names().forEach(add);
        var user = Minecraft.getInstance().getUser();
        if (user != null) out.remove(user.getName().toLowerCase(java.util.Locale.ROOT));
        return new ArrayList<>(out.values());
    }

    /** Asks the relay which candidates can take an invite (on opening and on Refresh, at most once per 30 s). */
    private void fetchReach(boolean asked) {
        String roomId = SimHost.roomId(), secret = SimHost.secret();
        if (roomId == null || secret == null) {
            reachState = "This room is not on the room list yet: nobody can be invited.";
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReachMs < 30_000) {
            if (asked) say("Refreshed less than 30 s ago.", MenuStyle.TXT_SOFT);
            return;
        }
        lastReachMs = now;
        reachState = "Looking for friends who can join...";
        java.util.concurrent.CompletableFuture.supplyAsync(SimMultiplayerScreen::candidates)
                .thenCompose(names -> names.isEmpty()
                        ? java.util.concurrent.CompletableFuture.completedFuture((com.killer560.hub.relay.RelayCalls.Reply) null)
                        : SimRooms.reach(roomId, secret, names))
                .whenComplete((rep, err) -> {
                    List<String> out = new ArrayList<>();
                    if (rep != null && rep.ok() && rep.body().has("reachable")) {
                        for (JsonElement e : rep.body().getAsJsonArray("reachable")) out.add(str(e.getAsJsonObject(), "name"));
                    }
                    reachable = List.copyOf(out);
                    reachState = err != null ? "Could not reach the room list."
                            : rep == null ? "No friends, Best Friends or mod users known yet: invite by name."
                            : !rep.ok() ? SimRooms.refusal(rep)
                            : out.isEmpty() ? "None of your friends can take an invite right now: invite by name."
                            : "";
                    Minecraft.getInstance().execute(() -> {
                        if (McCompat.screen(Minecraft.getInstance()) == this && shown() == View.INVITE) rebuildWidgets();
                    });
                });
    }

    private void sendInvite(String target) {
        String roomId = SimHost.roomId(), secret = SimHost.secret();
        if (roomId == null || secret == null) {
            say("This room is not on the room list yet.", MenuStyle.RED);
            return;
        }
        if (!target.matches("[A-Za-z0-9_]{1,16}")) {
            say("Type a Minecraft name.", MenuStyle.RED);
            return;
        }
        say("Inviting " + target + "...", MenuStyle.TXT_SOFT);
        SimRooms.invite(roomId, secret, target).whenComplete((rep, err) -> Minecraft.getInstance().execute(() -> {
            if (rep != null && rep.ok()) {
                String name = str(rep.body(), "name");
                INVITED.add((name.isEmpty() ? target : name).toLowerCase(java.util.Locale.ROOT));
                say("Invited " + (name.isEmpty() ? target : name) + ".", MenuStyle.TXT);
                if (inviteBox != null && inviteBox.getValue().trim().equalsIgnoreCase(target)) inviteBox.setValue("");
                if (McCompat.screen(this.minecraft) == this) rebuildWidgets();
            } else {
                say(rep == null ? "Could not reach the room list."
                        : rep.status() == 404 && !"gone".equals(rep.error()) ? target + " cannot take an invite now (offline, invites off, or another version)."
                        : rep.status() == 429 ? "Too many invites, wait a moment." : SimRooms.refusal(rep), MenuStyle.RED);
            }
        }));
    }

    /** Invites sent from this page, for tests. */
    public static java.util.Set<String> invitedForTest() { return java.util.Set.copyOf(INVITED); }

    public static List<String> reachableForTest() { return reachable; }

    static String disconnectWarning(int n) {
        return n + (n == 1 ? " player" : " players") + " will be disconnected.";
    }

    private static Component onOff(String label, boolean on) { return Component.literal(label + ": " + (on ? "ON" : "OFF")); }

    private static String guestsKey() {
        StringBuilder b = new StringBuilder();
        for (SimHost.Guest g : SimHost.guests()) b.append(g.uuid()).append(g.admitted()).append(',');
        return b.toString();
    }

    private void initGuest(int x, int y, int w) {
        y += 3 * 11 + 4 + 11 * Math.min(5, guestNames().size()) + 4;
        int bw = (w - 6) / 2;
        addRenderableWidget(request("Teleport to Owner", SimRequests.Kind.TELEPORT, () -> "owner", x, y, w));
        y += ROW;
        roomBox = box(x + 110, y, w - 110 - 64, 48, "Teleport to Room", roomBox == null ? "" : roomBox.getValue());
        SettingsButtonWidget go = addRenderableWidget(request("Go", SimRequests.Kind.TELEPORT, () -> roomBox.getValue(),
                x + w - 60, y, 60));
        roomBox.pressOnEnter(go);
        y += ROW;
        speedBox = box(x + 110, y, w - 110 - 64, 3, "Speed (100-500)", speedBox == null ? "" : speedBox.getValue());
        SettingsButtonWidget set = addRenderableWidget(request("Set", SimRequests.Kind.SPEED, () -> speedBox.getValue(),
                x + w - 60, y, 60));
        speedBox.pressOnEnter(set);
        y += ROW;
        addRenderableWidget(request("Fly ON", SimRequests.Kind.FLY, () -> "on", x, y, bw));
        addRenderableWidget(request("Fly OFF", SimRequests.Kind.FLY, () -> "off", x + bw + 6, y, w - bw - 6));
        y += ROW;
        addRenderableWidget(request("Apply My Loadout", SimRequests.Kind.LOADOUT, SimRequests::loadoutArg, x, y, w));
        y += ROW;
        List<String> items = SimRequests.items();
        if (!items.isEmpty()) {
            itemIndex = Math.floorMod(itemIndex, items.size());
            addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Item: " + items.get(itemIndex)), (b, dir) -> {
                itemIndex = SettingsButtonWidget.step(itemIndex, dir, items.size());
                b.setMessage(Component.literal("Item: " + items.get(itemIndex)));
            }).bounds(x, y, w - 66, 18).build());
            addRenderableWidget(request("Give", SimRequests.Kind.ITEM, () -> items.get(itemIndex), x + w - 60, y, 60));
            y += ROW;
        }
        y += 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Leave"), b -> SimGuest.leave(this.minecraft))
                .bounds(x, y, w, 20).build());
    }

    private SettingsButtonWidget request(String label, SimRequests.Kind kind, java.util.function.Supplier<String> arg,
                                         int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label), b -> {
            if (!SimRequests.send(kind, arg.get())) say("The owner's game cannot take requests.", MenuStyle.RED);
            else say("Asked the owner: " + label + ".", MenuStyle.TXT_SOFT);
        }).bounds(x, y, w, 18).build();
    }

    private List<String> guestNames() {
        List<String> out = new ArrayList<>();
        var conn = this.minecraft == null ? null : this.minecraft.getConnection();
        if (conn == null) return out;
        for (PlayerInfo p : conn.getOnlinePlayers()) out.add(p.getProfile().name());
        return out;
    }

    // ---- shared ----

    private CommitEditBox box(int x, int y, int w, int maxLen, String label, String value) {
        ThemedEditBox b = new ThemedEditBox(this.font, x, y, w, 18, Component.literal(label));
        b.setMaxLength(maxLen);
        b.setValue(value == null ? "" : value);
        b.hint(label);
        return addRenderableWidget(b);
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    /** The status line under the page, for tests. */
    public String messageForTest() { return message; }

    @Override
    public void tick() {
        super.tick();
        View v = shown();
        if (v == View.JOIN) {
            List<LanServer> dirty = lanList.takeDirtyServers();
            if (dirty != null) {
                synchronized (lanServers) {
                    lanServers.clear();
                    lanServers.addAll(dirty);
                }
                rebuildWidgets();
            }
            if (++listTicks >= LIST_EVERY) fetchRooms();
            if (pendingKey != SimReach.pending().hashCode()) rebuildWidgets();
        } else if (v == View.SESSION && !SimGuest.isGuest(this.minecraft) && !guestKey.equals(guestsKey())) {
            rebuildWidgets();
        }
    }

    @Override
    public void removed() {
        stopLan();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        nav.drawFrame(g, this.width, this.height);
        int x = nav.contentX, y = nav.contentY;
        View v = shown();
        ScreenChrome.text(g, v == View.INVITE ? "Invite Players" : v == View.SESSION ? "Session" : "Multiplayer", x, y + 4, MenuStyle.OR);
        y += 20;
        if (v == View.SESSION && SimGuest.isGuest(this.minecraft)) {
            SimCoop.Info info = SimGuest.info();
            y += 0;
            ScreenChrome.text(g, "Owner: " + (info == null ? "?" : info.owner()), x, y, MenuStyle.TXT);
            ScreenChrome.text(g, "Playing: " + (info == null ? "?" : info.mode() + " " + info.floor()
                    + (info.mode().equals("boss") ? " " + info.phase() : "")), x, y + 11, MenuStyle.TXT);
            ScreenChrome.text(g, "Players:", x, y + 22, MenuStyle.TXT);
            int py = y + 33;
            for (String n : guestNames()) {
                if (py >= y + 33 + 55) break;
                ScreenChrome.text(g, "  " + n, x, py, MenuStyle.TXT_SOFT);
                py += 11;
            }
        } else if (v == View.SESSION) {
            ScreenChrome.text(g, "Room: " + SimHost.roomName() + (SimHost.locked() ? " (locked)" : "") + "  up to "
                    + SimHost.max() + " players", x, y, MenuStyle.TXT);
            ScreenChrome.text(g, SimHost.status(), x, y + 11, MenuStyle.TXT_SOFT);
            String pub = SimHost.publicAddress();
            ScreenChrome.text(g, pub != null ? "Public address: " + pub : SimHost.upnpLine(), x, y + 22,
                    pub != null ? MenuStyle.TXT : MenuStyle.TXT_SOFT);
            ScreenChrome.text(g, SimHost.listLine(), x, y + 33, MenuStyle.TXT_SOFT);
            int py = y + 4 * 11 + 4 + 5;
            List<SimHost.Guest> gs = SimHost.guests();
            if (gs.isEmpty()) ScreenChrome.text(g, "No guests yet.", x, py, MenuStyle.TXT_SOFT);
            for (SimHost.Guest guest : gs) {
                ScreenChrome.text(g, guest.name() + (guest.admitted() ? "" : "  (checking ticket)"), x, py, MenuStyle.TXT);
                py += ROW;
            }
        } else if (v == View.INVITE) {
            ScreenChrome.text(g, reachState.isEmpty() ? "Friends who can join now:" : reachState, x, y + ROW + 2, MenuStyle.TXT_SOFT);
            int py = y + ROW + 11 + 5;
            for (String n : reachable) {
                if (py + ROW > nav.contentBottom - ROW * 2) break;
                ScreenChrome.text(g, n, x, py, MenuStyle.TXT);
                py += ROW;
            }
            ScreenChrome.text(g, "Invite by name", x, nav.contentBottom - ROW * 2 + 2 + 5, MenuStyle.TXT);
        } else if (v == View.CREATE) {
            y += ROW + 6;
            ScreenChrome.text(g, "Room Name", x, y + 5, MenuStyle.TXT);
            ScreenChrome.text(g, "Password", x, y + ROW + 5, MenuStyle.TXT);
            if (SimHost.opening()) ScreenChrome.text(g, SimHost.status(), x, y + ROW * 4 + 8, MenuStyle.TXT_SOFT);
        } else {
            y += ROW + 6;
            ScreenChrome.text(g, "Rooms (" + SimGuest.mc() + ")", x, y + 5, MenuStyle.TXT);
            if (!listState.isEmpty()) ScreenChrome.text(g, listState, x, y + ROW + 5, MenuStyle.TXT_SOFT);
            int by = nav.contentBottom - ROW * 4 + 2 + (passwordFor != null ? ROW : 0);
            if (passwordFor != null) ScreenChrome.text(g, "Password", x, by - ROW + 5, MenuStyle.TXT);
            ScreenChrome.text(g, "Join by address", x, by + 5, MenuStyle.TXT);
        }
        if (!message.isEmpty()) ScreenChrome.text(g, message, x, nav.contentBottom - 10, messageColor);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() { McCompat.setScreen(this.minecraft, parent); }

    /** Session Ended's Back to Sim Menu. */
    static Screen simMenu() { return SimMenuNav.open(new TitleScreen()); }
}
