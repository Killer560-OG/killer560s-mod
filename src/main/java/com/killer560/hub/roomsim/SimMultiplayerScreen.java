package com.killer560.hub.roomsim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
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

/**
 * The Dungeon Sim menu's Multiplayer entry (co-op phase 2, docs/SIM-COOP.md "Multiplayer"): Create a room, Join one
 * (the relay's public list, a password for a locked one, an address, or a LAN world), and the Session page - the owner's
 * players with Kick, what guests may ask for and Close Room; a guest's requests and Leave. A guest sees only the
 * Session page: the builders are the owner's.
 */
public final class SimMultiplayerScreen extends Screen {

    enum View { CREATE, JOIN, SESSION }

    /** One listed room, as the relay gave it. */
    record Room(String id, String owner, String name, boolean locked, int players, int max, String modVersion,
                int protocol, String mode) {
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
        if (SimGuest.isGuest(mc) || SimHost.hosting()) return View.SESSION;
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
        if (v != View.SESSION) {
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
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Max Players: " + maxPlayers), (b, dir) -> {
            maxPlayers = 2 + SettingsButtonWidget.step(maxPlayers - 2, dir, 4);
            b.setMessage(Component.literal("Max Players: " + maxPlayers));
        }).bounds(x, y, w, 20).build());
        y += ROW + 4;
        SettingsButtonWidget create = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Create Room"),
                b -> create()).bounds(x, y, w, 20).build());
        nameBox.pressOnEnter(create);
        passwordBox.pressOnEnter(create);
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
        int listed = 0;
        int bottom = nav.contentBottom - ROW * 4;
        for (Room r : rooms) {
            if (y + ROW > bottom) break;
            String label = (r.locked() ? "[Locked] " : "") + r.name() + "  -  " + r.owner() + "  " + r.players() + "/"
                    + r.max() + "  " + r.mode() + "  v" + r.modVersion();
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
                            num(o, "players"), num(o, "max"), str(o, "modVersion"), num(o, "protocol"), str(o, "mode")));
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

    /** Asks the relay for a ticket, then connects straight to the host. */
    void join(Room r, String password) {
        say("Joining " + r.name() + "...", MenuStyle.TXT_SOFT);
        SimRooms.join(r.id(), password).whenComplete((rep, err) -> Minecraft.getInstance().execute(() -> {
            if (rep == null || rep.status() != 200 || rep.body() == null || !rep.body().has("nonce")) {
                say(rep == null ? "Could not reach the room list." : SimRooms.refusal(rep), MenuStyle.RED);
                return;
            }
            JsonObject b = rep.body();
            String address = null;
            if (b.has("endpoint") && b.get("endpoint").isJsonObject()) {
                JsonObject e = b.getAsJsonObject("endpoint");
                address = e.get("host").getAsString() + ":" + e.get("port").getAsInt();
            }
            SimGuest.expect(new SimGuest.Pending(r.id(), b.get("nonce").getAsString(),
                    b.has("expires") ? b.get("expires").getAsLong() : System.currentTimeMillis() + 120_000L, address,
                    SimHost.key(r.id())));
            passwordFor = null;
            if (address == null) {
                say("That room is LAN only: pick it in the LAN list (same network as the owner).", MenuStyle.TXT_SOFT);
                rebuildWidgets();
                return;
            }
            connect(address, r.name());
        }));
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
        ScreenChrome.text(g, v == View.SESSION ? "Session" : "Multiplayer", x, y + 4, MenuStyle.OR);
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
