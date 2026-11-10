# Co-op dungeon sim (phases 1 to 3)

Branch `staging-coopsim` (post-2.0 staging: never shipped before 2.0). A second player joins the host's sim world over
LAN (vanilla "Open to LAN"). The host's integrated server stays the only thing that writes the world; a guest's client
only reads and predicts.

## Protocol

Three Fabric play payloads, `killer560smod:` namespace, defined in `roomsim/SimCoop` (protocol `SimCoop.PROTOCOL` = 2 since phase 2;
bump it on any field change, a guest refuses any other number).

- `sim_hello` (S2C) and `sim_state` (S2C), one record `SimCoop.Info`: protocol, mod version, MC version, mode
  (`room` / `floor` / `boss`), floor label, master, boss phase (`BossFight.Phase` name), P3 section, owner name, nonce.
  Strings are capped at 64 characters.
- `sim_hello_ack` (C2S), `SimCoop.Ack`: protocol, mod version, MC version, short commit, variant. The host logs it
  (`[SimCoop] <name> joined with ...`).
- `sim_arrive` (S2C), `SimCoop.Arrive(seq, x, y, z)`: sent by `SimCoop.hold` from every `SimArrival.teleport` of a
  guest. `sim_arrived` (C2S, `seq`) answers it once the guest has the chunks.
- `sim_click` (C2S, empty): a guest's left-click PRESS with the Terminator in hand.

Host side (`SimCoop.serverTick`, every 10 server ticks, only while `SimState.isActive()`, this client's own integrated
server and 2+ players): greets each non-host player whose client registered the channel (`ServerPlayNetworking.canSend`)
once, then sends `sim_state` when mode/floor/master/phase/section changed. The host is `server.isSingleplayerOwner`.
It also keeps the party (host first) that `SimTabList` shows as `Party (n)`; with one player the tab is unchanged.

Nonce: a plain LAN or address join has no ticket (0). A join through the Join page carries the room's key as the hello's
nonce and the relay ticket's nonce in the ack (`SimGuest.Pending`, `ticketMatches`; see Multiplayer below).

## Guest side and gates

`roomsim/SimGuest` registers its two receivers PER CONNECTION in `ClientPlayConnectionEvents.INIT`, and only when the
connection is eligible: no singleplayer server, server data present, address name without `hypixel.net`/`p3sim.net`,
and a remote `InetSocketAddress` that is loopback, site-local, link-local or IPv6 ULA. A global client receiver would put
the channel in the client's `minecraft:register` to every server, Hypixel included. Each payload is checked again on
receipt (protocol, eligibility, a state only after a hello). The testkit's loopback GrimAC server is labeled
`mc.hypixel.net`, so it is refused by name.

On accept: `DungeonState.setRoomSim(true)`, the boss flags follow the phase (`simBossBegins(master)` / `simBossEnds`),
and the ack goes back. On DISCONNECT (netty thread) a flag is set and the render thread's END_CLIENT_TICK drops the state.

`SimState.isGuest(client)` (read-only) is true while that state is held on a remote, non-singleplayer connection. It opens:
`CheatUtils.isOnDungeonServer`, `SecretsFeature.isOnDungeonServer`, `I4SensorsFeature.isOnDungeonServer`, and the client
halves of `SimAbilities.onClientUse` and SimItems' AttackBlockCallback (prediction only: "no local mining", send the held
slot first). It is never read by anything that writes a position, block or entity; `SimState.canAct`/`canOpen` are
unchanged, so every sim ability stays host-only on the client.

## Server fixes for more than one player

- Maxor crystals: carried count and the saved slot 8 are per UUID (`BossFight.carriedForTest(uuid)`); every player's
  slot 8 comes back on stop or Maxor down.
- Devices: the player who pressed the SS button, flipped the Lights lever, used the Arrow Align frame or whose arrow hit
  the Target device is credited (`BossDevices.lastBy`); levers and terminals already passed their player.
- A phase change (`BossSim.buildAndBegin`) keeps every player's inventory, by UUID.
- `SimBuilder.holdPlayer` holds and `SimArrival.teleport`s every player.
- The fight ends only after every player has been out of the boss box for 40 server ticks (`BossSim.leaveCheck`).
- The Blood portal is server-side (`BossPortal.serverTick`): any player in Blood opens it, any player stepping in starts P1.
- A guest's Terminator and Spirit Sceptre fire from that guest (`SimAim.of(sp)`, arrows owned by it, the blast hurts as
  it, its own fire rate). Host behavior is unchanged (guest UUID null).
- Salvation is per shooter (`SimTerminator.ARMED`, hits counted per arrow owner, 3 distinct mobs each). The host's left
  click is still SimClass reading his attack key; a guest's press goes up as `sim_click`, and the server fires
  `SimTerminator.use(.., true, guest)` from the guest's aim: its beam, its damage, its own fire rate, never hitting itself.
- Far teleports of a guest (issue: its own client falls through chunks it does not have yet; nothing on the guest may
  write its position): `SimArrival.teleport` calls `SimCoop.hold`, which sends `sim_arrive`. The guest's
  `SimArrival.clientTick` holds its loading screen until the 3x3 chunks are in (it has no server to put itself back),
  then `sim_arrived`. Until then the HOST's server puts the guest back on the spot (`SimArrival.move`, server-side
  teleport, rotation relative) whenever it is below it and within 2 blocks sideways, at most every 5 ticks; the hold
  ends on the answer, after 340 ticks, or when the guest leaves. A vanilla client (no channel) and sim minibosses are
  skipped.
- Spirit Sceptre: the launch sound, the explosion particles and sound are sent by the server (`sendParticles`,
  `playSound(null, ..)`), so every player sees and hears them; the bats were already server entities.
- `PlayerBlockBreakEvents.BEFORE` never refused anything: Fabric fires it on the server with the ServerPlayer, and the
  old `player != client.player` test was always true. It now refuses every survival player on the sim's server (logged
  `[Sim] vanilla break refused`); creative still breaks.
- Terminals: a second player opening a terminal someone else has open gets `This Terminal is being used by someone
  else!` and nothing opens. Hypixel's real rule was not found in any capture or doc; this line is ours, not a captured one.

## Two-client test assertions (testkit staging-coopsim: CoopSimTests 1109-coop-two / -rules / -p3 / -portal / -fakeguest, hx 1109-coop-hypixel-refused)

Test counters: `SimGuest.acceptedForTest/refusedForTest/guestForTest()` ({arrives, clicks}), `SimCoop.acksForTest`,
`SimCoop.holdsForTest()` ({started, answered, put-backs}), `SimTerminator.salvationArmedForTest(uuid)` and
`salvationsForTest()`, `SimSpiritSceptre.lastBlastViewersForTest()`, `BossFight.carriedForTest(uuid)`, `BossDevices.lastBy`.

1. Handshake: guest accepted 1 hello, host acks +1, `[SimCoop] <guest> joined with` logged; `SimState.isGuest` true on
   the guest only; guest `canAct` stays false.
2. Privacy: on the testkit's GrimAC server (labeled mc.hypixel.net) the guest registers no `killer560smod:sim_*` channel.
3. Tab: both clients' tab shows `Party (2)` and both names (the guest's tab is the host server's sim tab rows;
   `SimCoop.partyNames` is the host's own list, the guest's stays just itself).
4. Boss flags: guest's `DungeonState.isSimBoss` follows the host's phase; drops on leave.
5. Maxor crystals per UUID: a guest walking into a tower crystal carries it (carried count, slot 8, the pickup line
   names it), the host carries none.
6. Device credit: guest presses SS / flips Lights lever / Arrow Align / Target: `lastBy` is the guest.
7. Terminal in use: second opener gets the line and no screen.
8. Leave rule: the fight continues while one player is inside the boss box; ends 40 ticks after both left.
9. Portal: the guest stepping into the Blood portal starts P1.
10. Vanilla break refused for the guest, survival AND creative, like the host: `SimItems.onServerAttack` answers every
   START on the sim's server (the BEFORE guard is the second gate and is never reached by a START).
11. Guest Terminator: arrows owned by the guest, from its eye; guest Spirit Sceptre: blast damage source is the guest.
12. Guest far-teleport hold: after a boss phase teleport the guest's `guestForTest()[0]` rises, `holdsForTest()` started
    and answered rise by one each, the guest never ends below the landing (y within 0.2 when released) and its client
    never wrote its position (no setPos: assert by the put-backs count, server-side only).
13. Sceptre picture for all: a blast with both players within 32 blocks (vanilla's particle range) reports
    `lastBlastViewersForTest() == 2`.
14. Guest Salvation: 3 distinct mobs hit by the guest's arrows arm only the guest (`salvationArmedForTest(guest)` true,
    host false); a guest left click fires it (`salvationsForTest` +1, `guestForTest()[1]` +1) and the beam's damage
    source is the guest; the host's own Salvation is unchanged.

## Multiplayer (phase 2: Create, Join, Session, requests)

The sim menu's **Multiplayer** entry (`SimMenuNav`, its own separator above Back, set apart from the builders) opens
`SimMultiplayerScreen`: tabs Create and Join, and a Session page while hosting or joined (a guest reaches it from the pause
menu's "Sim Session", `SimPauseEntry`).

- **Create** (`SimHost.create`): Room Name (relay cap 32), Password (optional), Max Players 2-5. Starts the F7 boss sim,
  publishes the integrated server on a free port (LAN, authentication off as vanilla's Open to LAN), asks the router
  (`SimUpnp`), and lists the room (`SimRooms.create`, relay `/simrooms/*`, metadata only: name, lock, players, max, mode,
  versions and the public endpoint when UPnP opened one). The room is refreshed every 600 server ticks and at once when a
  guest comes or goes (the relay's capacity is players + live tickets); the refresh answer carries new tickets. Close or
  leaving sends a relay delete and removes the port mapping. With guests in, Close Room and the pause menu's quit ask
  first ("N player(s) will be disconnected.").
- **UPnP** (`SimUpnp`): SSDP M-SEARCH, the WANIPConnection/WANPPPConnection control URL, AddPortMapping (TCP, lease 0 = until removed; Close and leaving delete it),
  GetExternalIPAddress. Every failure says why and what is left: "Your router did not open the port (<reason>): friends on
  your network can still join; others need port forwarding of TCP port N." In a client gametest UPnP is OFF unless
  `-Dkiller560.simcoop.ssdp=host:port` names a gateway (the testkit's `FakeGateway`), so no test ever asks the real router.
- **Join**: the relay list for this MC version (fetched on opening Join, on Refresh, and at most every 30 s while open),
  rows "[Locked] name  -  owner  p/max  mode  vX". A locked room asks for its password. `SimRooms.join` gets a ticket
  (nonce, 120 s) and the owner's endpoint; 403 "Wrong password.", 409 "That room is full.", 404 "That room is gone.", no
  endpoint "That room is LAN only: pick it in the LAN list", another `protocol` refused before asking. Also join by address
  (host:port) and the LAN list (vanilla `LanServerDetection`). A ticket's address is trusted like a private one by
  `SimGuest.eligible` (the receivers register for that connection only).
- **Admission** (`SimHost.serverTick` / `acked`): in a locked or listed room a guest must name a live ticket for its uuid in
  its `sim_hello_ack` within `ADMIT_TICKS` (240 server ticks), else it is kicked "Join through the Multiplayer menu.". A
  ticket unknown to the host forces a refresh first. A guest over Max is refused "This room is full." at once. The owner's
  Session page lists guests with Kick ("The owner kicked you.").
- **Ended**: a guest dropped by the owner sees `SimSessionEndedScreen`, "Session ended: the owner left" / "...kicked you" /
  "...closed the room", with Back to Sim Menu.
- **Requests** (`SimRequests`, C2S `sim_request(kind, arg)`): Teleport (to the owner or a room), Speed (100-500), Fly,
  Apply My Loadout, Item. The host's server runs each for the SENDER only and answers "[Sim] ..." in its chat; each kind
  sits behind a saved owner switch (`SimCoopConfig`, `killer560smod-sim-coop.json`, on by default, in `ProfileManager`),
  refused "The owner does not let guests <verb>.". The owner's own requests are refused. Nothing here writes a position on
  the guest's client: a teleport is the server's (`SimArrival.teleport`, held as in phase 1).

Testkit (staging-coopsim, `CoopMultiplayerTests`, relay stub `coop/SimRoomsStub`, `coop/FakeGateway`): 1109-mp-menu (entry
placement, Join list, password, full, protocol, Refresh), 1109-mp-upnp (off, silent, refused, mapped, unmapped), 1109-mp-host
(publish, mapping, listing with the public endpoint, ticketless kick, ticket admit, Max refusal, every request for the sender
only, a switched-off kind refused and saved, Kick, the two warnings, Close deletes listing and mapping), 1109-mp-two (two
clients through `run-coop.sh -s 1109-mp-two`: Join page, wrong then right password, admitted and kept, Session page via the
pause menu, speed honored then refused, Kick -> ended screen -> Back, rejoin, owner leaves -> "Session ended: the owner left").

## Invites (phase 3)

The owner invites friends by name; the receiver joins without the password. Relay side (relay `src/simreach.ts`, routes in
`src/simrooms.ts`, contract in the relay CLAUDE.md "Sim invites"): ONE Durable Object `simreach` holds every receiver's
HIBERNATING WebSocket (`GET /simreach?token=&mc=&protocol=`), tagged with the token's uuid and lower-case name; idle sockets
cost nothing (the mod sends only WebSocket ping frames, which the runtime answers without waking the object). Reach
(`POST /simrooms/reach`, up to 50 names, 6 a minute) and invite (`POST /simrooms/invite`, 10 a minute, 60 an hour, the same
target once per 30 s) go through SimRooms, so only the owner of a live room can ask; an invite key lives 10 minutes with the
room, is bound to the target's uuid, single use, and `POST /simrooms/join {invite}` skips the password with it (capacity
still applies). Offline, OFF, blocked, another MC/protocol and yourself all answer the same 404 "not reachable", so a block
is not revealed. The Presence object is untouched (no player data there, owner's rule).

Mod side:
- `roomsim/SimReach`: the socket while Sim Settings' "Receive Sim Invites" is ON (default ON, `SimCoopConfig.receiveInvites`,
  BoolFields, in ProfileManager through SimCoopConfig). Connects from a client tick every second (never at boot), backs off
  5 s .. 10 min with jitter, reconnects on an account switch, closes when OFF and on client stop; the socket is stored in
  `onOpen`, before any message can arrive. It sends the block list (`SimCoopConfig.blocked`, at most 100, saved as
  `blockedInviters`) after connecting and on every change. An invite shows as a chat line with [Join] [Decline] [Block]
  (`/siminvite join|decline|block <n>`) in a world, or a Join / Decline prompt on the title and sim menus; live invites
  (10 kept, dropped at expiry) are also listed at the top of the Join page with Decline and Block. Join leaves the current
  world, opens the Join page and asks the relay with the invite key (`SimMultiplayerScreen.join(roomId, name, null, key)`),
  then the phase-2 ticket path. Joining while hosting is refused.
- Owner: Session page -> "Invite Players": the relay is asked which of his mirrored /fl friends, Best Friends and mod users
  (at most 50) can take an invite now (on opening and on Refresh, at most once per 30 s); each has an Invite button, and
  "Invite by name" (Enter = Invite) invites anyone with the mod. A LAN-only room (no listing) cannot invite.
- Cost on the Cloudflare free plan (100k Worker and 100k DO requests a day): a receiver is 1 Worker + 1 DO request per
  connect and nothing idle; a reach and an invite are 1 Worker + 2 DO each; the accepted join is 1 + 1.

Testkit 1109-mp-invite (two clients, `run-coop.sh -s 1109-mp-invite -w`, which starts a local wrangler dev relay with the
test secret): guest OFF -> socket closed, OFF survives a reload, the reach list is empty and an invite by name is refused;
ON -> the reach list shows the guest, the row's Invite sends, the guest's title-screen prompt is declined (nothing pending,
no world); a second invite by name with Enter, Join -> in the owner's world as a guest, admitted with its ticket, no password.

## Not done (phase 2+)

After a floor rebuild only the host is moved from the holding area to the landing
(`SimBuilder` teleports `uuid` alone); guests stay on the holding barrier until moved.
