# Co-op dungeon sim (phase 1)

Branch `staging-coopsim` (post-2.0 staging: never shipped before 2.0). A second player joins the host's sim world over
LAN (vanilla "Open to LAN"). The host's integrated server stays the only thing that writes the world; a guest's client
only reads and predicts.

## Protocol

Three Fabric play payloads, `killer560smod:` namespace, defined in `roomsim/SimCoop` (protocol `SimCoop.PROTOCOL` = 1;
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

Nonce: phase 1 has no ticket (0); the LAN join is the ticket. `SimGuest.ticketMatches` is the hook for phase 2's lobby.

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

## Two-client test assertions (to write once the testkit harness is ready)

Test counters: `SimGuest.acceptedForTest/refusedForTest/guestForTest()` ({arrives, clicks}), `SimCoop.acksForTest`,
`SimCoop.holdsForTest()` ({started, answered, put-backs}), `SimTerminator.salvationArmedForTest(uuid)` and
`salvationsForTest()`, `SimSpiritSceptre.lastBlastViewersForTest()`, `BossFight.carriedForTest(uuid)`, `BossDevices.lastBy`.

1. Handshake: guest accepted 1 hello, host acks +1, `[SimCoop] <guest> joined with` logged; `SimState.isGuest` true on
   the guest only; guest `canAct` stays false.
2. Privacy: on the testkit's GrimAC server (labeled mc.hypixel.net) the guest registers no `killer560smod:sim_*` channel.
3. Tab: both clients' tab shows `Party (2)` with host first.
4. Boss flags: guest's `DungeonState.isSimBoss` follows the host's phase; drops on leave.
5. Maxor crystals per UUID: each player's carried count and slot 8 restored on Maxor down.
6. Device credit: guest presses SS / flips Lights lever / Arrow Align / Target: `lastBy` is the guest.
7. Terminal in use: second opener gets the line and no screen.
8. Leave rule: the fight continues while one player is inside the boss box; ends 40 ticks after both left.
9. Portal: the guest stepping into the Blood portal starts P1.
10. Vanilla break refused for the guest in survival (log line), allowed in creative.
11. Guest Terminator: arrows owned by the guest, from its eye; guest Spirit Sceptre: blast damage source is the guest.
12. Guest far-teleport hold: after a boss phase teleport the guest's `guestForTest()[0]` rises, `holdsForTest()` started
    and answered rise by one each, the guest never ends below the landing (y within 0.2 when released) and its client
    never wrote its position (no setPos: assert by the put-backs count, server-side only).
13. Sceptre picture for all: a host blast reports `lastBlastViewersForTest() == 2`, and the guest's client sees an
    EXPLOSION particle at the blast (particle packet received).
14. Guest Salvation: 3 distinct mobs hit by the guest's arrows arm only the guest (`salvationArmedForTest(guest)` true,
    host false); a guest left click fires it (`salvationsForTest` +1, `guestForTest()[1]` +1) and the beam's damage
    source is the guest; the host's own Salvation is unchanged.

## Not done (phase 2+)

The lobby ticket (nonce). After a floor rebuild only the host is moved from the holding area to the landing
(`SimBuilder` teleports `uuid` alone); guests stay on the holding barrier until moved.
