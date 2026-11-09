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
  it, its own fire rate). Salvation stays the host's. Host behavior is unchanged (guest UUID null).
- `PlayerBlockBreakEvents.BEFORE` never refused anything: Fabric fires it on the server with the ServerPlayer, and the
  old `player != client.player` test was always true. It now refuses every survival player on the sim's server (logged
  `[Sim] vanilla break refused`); creative still breaks.
- Terminals: a second player opening a terminal someone else has open gets `This Terminal is being used by someone
  else!` and nothing opens. Hypixel's real rule was not found in any capture or doc; this line is ours, not a captured one.

## Not done (phase 2+)

A guest is not held by `SimArrival` on its own client after a far teleport (it can fall through unloaded chunks); the
sceptre's explosion particles draw only on the host; a guest's Salvation; the lobby ticket (nonce).
