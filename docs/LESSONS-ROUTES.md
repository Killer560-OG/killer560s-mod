# Auto Routes lessons

Auto Routes' runtime: node timing, warp aims, refused warps and awaits. Part of the automation lessons:
[LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md) (GrimAC, right-click pairing, ticks); Interactive Map trips, Auto Secret
and Auto Clear are in [LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md). Problem, then fix; verified only.

## Timing: chain timing, settles, completion

- **Auto Routes chain timing (testkit 62-argrim-chain / 96-ar-chain).** An etherwarp chain ran at 4 ticks per warp on the
  dedicated server (5 in the sim): landing seen, interact-delay settle, a tick for the walk step to re-fire. After a
  server-confirmed landing an etherwarp/path node that fires next goes on that same tick: 1.00 tick per warp (Grim clean),
  2.00 in the sim, whose integrated server answers a tick later. Only warp-into-warp skipped the settle: a breaker stacked
  on the landing tile and fired on the landing tick sent its digs and the dedicated server broke nothing (62-argrim-play,
  not traced). A warp fired on the landing tick stands 0.05 above the floor its look was recorded from, which put a
  16-block shallow warp one block long (96-ar-rotate), so a node fired off its own height re-aims from the eye with
  `TeleportUtils.getEtherwarpDirection`, as path hops do.
- **The Interact Delay was a fixed wait on every node transition but warp-into-warp** (494 times, 988 ticks, in his
  2026-10-19 logs). Obvious mode now has none: the next node goes the same tick when nothing acting on the world left this
  tick (`RouteExecutor.quietThisTick`, fed by `RightClickSendMixin`), else the next. What it guarded is checked against the
  server instead: the breaker digs again whatever still stands a round trip on and never moves on past a standing block,
  and a boom sent within 2 ticks of a landing that changed nothing goes once more. 96-ar-1019-nosettle: landing to breaker
  dig 3 -> 0 ticks, breaker to boom 5 -> 2, boom to warp 5 -> 2. Legit mode keeps the delay.
- **62-argrim-chain's 20/s pace is the server's answer time.** The same jar read 1.00 and 1.18-1.55 ticks per warp an hour
  apart on a busy machine, base fcba4045 included, and every slow warp still went on its landing's tick; there was no
  regressing commit. The case judges 20/s against the round trip it measures (testkit fullclear-1016).
- A route completes the tick its last node finishes (`RouteExecutor.finishAction`), not after the interact delay and a
  walk-step tick (that delay is a wait before a NEXT node). A one-node route therefore starts and completes inside
  `RouteExecutor.start()`/`complete()` before any executor tick runs, so nothing ever sees it running: anything that wants
  a route's progress (the Auto Routes Progress HUD, `RouteRunMemory.noteProgress`) must be told at start and at completion
  as well as each tick (102-sim-autosecret), and Auto Secret watches `RouteExecutor.completions()` /
  `lastCompletedRoom()`, not `isRunning()`. The planners' queue for that HUD is `autoroutes/RunPlan`, published by each
  planner at its own pick, never recomputed by the HUD.
- **A jump takes him out of every node's ring** (0.1 high by default), and standing in no node clears the latch,
  `justFinished` and `stoppedByUser`, so a finished JUMP node armed again the moment he landed back on it ("falls right back
  onto the node", killer560 2026-10-14). A finished jump node holds its tile for 3 s of real time
  (`RouteExecutor.jumpCooldownLeft`, gate line "jump cooldown N.Ns left"), then the latch rules apply; testkit
  96-ar-1014-jumpcd / 62-argrim-1014-jumpcd measure 3.04-3.10 s between fires, and a jump -> etherwarp route goes on at once.

- **A path arrival on a stack tile must fire before the nodes that move him** (pre1026-ar, 2026-10-26). It was ranked as an
  etherwarp, so on his Bridges route the stack "#3 ETHERWARP, #16 PATH" warped him away first and the arrival then ran 7 blocks
  off its tile; since 60920921 that stops the route ("not at path #16"): 96-ar-1017-bridges failed on the batch jar
  (batch1026-c-ar-1, unreported) and on bad234a8. `Route.fireRank` puts an arrival (second PATH of a pair) after every
  standing-still node and before etherwarp, path, jump and walk; every other node keeps its place.

## Aiming a node

- **An etherwarp node's recorded look is right from the node's own spot and nowhere else** (his Museum #10 armed 0.6 off
  center and 0.27 up mid-jump, fired the recorded look, landed a block wide of #11). The ring is up to 0.8 wide either
  side, and only a height difference used to trigger the re-aim. `RouteExecutor.reaimIfMoved` re-aims at the recorded
  landing block from wherever he is, by any axis, at set-up AND on the tick the use goes out (sent at the start of a tick,
  so the server has exactly the client's position). Where no ray from his spot reaches the block (a pillar hides it from
  one corner of #10's ring), `stepOntoWarpSpot` walks him back toward the node with sneak-held discrete keys until one
  does, or onto the spot, then fires. Testkit 96-ar-398-offnode (old jar lands at his exact (-79.5, -105.5)). The aim must
  also HOLD, not just land: see "A run-time etherwarp aim must HOLD" in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md).
- **In obvious mode `Minecraft.pick` uses the HELD camera, not the body.** `Ap3ViewYawMixin` makes `getViewYRot/XRot`
  return `ViewFreeze`'s view, and the crosshair pick reads those, so a vanilla key press while the body is turned acts on the
  block the CAMERA looks at. The crypt node holds the real use key (vanilla `handleKeybinds`: held + `rightClickDelay == 0` ->
  `startUseItem`, every 4 ticks, javap 26.1.2 and 26.2) and `autoroutes/mixin/HeldUsePickMixin` re-picks along the body at
  the end of `pick` while it holds. Vanilla's held use with a sword on a floor sends `use_item_on`, `use_item`,
  `use_item_on` (both hands), which 62-argrim-crypt compares against a harness-held key.
- **A held key does nothing under a screen.** `Minecraft.tick` runs `handleKeybinds` only while `overlay == null && screen
  == null` (javap 26.1.2 and 26.2), so the crypt node's held use key sent no use with the Interactive Map open (Run While
  Map Open) and timed out (96-ar-405-crypt-mapopen: 0 uses / 101 ticks against 3 uses / 9 ticks). `HeldUsePickMixin` runs
  vanilla's held-use step itself after the tick's `pick` while `RouteExecutor.heldUseUnderScreen` (key down,
  `rightClickDelay == 0`, not using; the delay counts down whatever the screen). Every other node sends its packets
  directly or through the input mixin, which run under screens.
- **A use-on-block packet carries no rotation** - the server uses whatever the last movement packet reported, and vanilla
  sends a tick's held use BEFORE that tick's movement packet. A crypt node turning straight down and holding use the same
  tick sent its first use at the look he armed with (30.0, or 89.85-89.96 in legit; testkit 96-ar-653). It now holds use
  only after two ticks reading pitch 90.0. RouteRotation's ease also never reached its target exactly (a decaying arc
  tail kept it thousandths off), so its last 0.01 degree now lands exactly and the arc stops there (hype-down).
- **The sim's ability hook ended vanilla's right click after its block half** (96-ar-chain after 96-ar-awaitskip: every
  corner warp waited a tick, `[0, 0, 1, ...]`). `SimAbilities.onClientUse` answers a block click with an ability item
  SUCCESS (no prediction for a reskinned block item), and `Minecraft.startUseItem` stops at a Success; on Hypixel the same
  click PASSes (stone with a sword or shovel) and the use half follows in the tick, pairing it. So `util/RightClick` held a
  crypt node's Hyperion floor click unpaired for good. `roomsim/mixin/SimUseHalfMixin` turns that Success into PASS inside
  `startUseItem` only, so vanilla sends the use half; the sim's server pairs it. 96-ar-awaitskip asserts nothing is left
  unpaired.
- **A sceptre crypt node's reach check must not stop at its own bat.** `WeaponReach.batImpact` ends at the first living
  entity on the line, and the node's sceptre bat in flight is one, so the mob it had just shot read "out of reach" for a
  tick and the node turned to the next mob between a mob's two uses (96-ar-1015-cryptaim "2 mobs": 14, 13, 14).
  `cryptMobOwed` traces blocks only (crypt-aim-2-1016): A, A, B, B, C, C. Crypt mobs are told by Hypixel's whole name tag
  (`autoroutes/CryptMobs.TAG`, "Crypt Undead" or "Prince" with its health), never by "appeared near me", since entering a
  room brings every mob into tracking range at once.
- **A boom node stores only a look, and the look was cast from the live eye.** With the previous warp's sneak still held
  (0.35 lower) a shallow look met the floor a block short of the block it was recorded on, and Hypixel blows only what is
  within 3 of the dug block: his Museum #11 (27 degrees down) dug -167, 68, -163 and changed nothing; fired standing it dug
  -166 and blew 30. `RouteExecutor.planBoom` casts the look from the node's standing eye once (`/ar add` is typed
  standing) and every send aims at that block from wherever the eye is. Testkit 96-ar-1020-boomsneak and
  62-argrim-1020-boomsneak (main blows nothing, fixed blows both, GrimAC clean). A boom that blew this run is remembered
  (`RouteRunMemory.boomed`, keyed with its look: a stack's booms share a tile) and a re-run of its stack completes it at
  once (main re-sent it and waited 13 ticks per re-run of a two-boom stack).
- A Superboom does not break the block it lands on: it blows the crypt or wall NEAR it. The boom node watched only the hit
  block and its six neighbors, so a boom that opened a crypt a block over read as "superboom didn't break anything" and
  stopped the route (his Museum stack, 2026-10-06). It watches a radius-3 cube, and a boom that changes nothing within a
  round trip completes (nothing left to blow) instead of stopping; 96-ar-museum plays his exact route.
- **A breaker node fired with the sneak still at the server measures from the 1.27 eye.** His Wizard #1 (start node, right
  after the map's sneaking warp) left its third block at 4.71; the stacked #2 etherwarp put the sneak back, the block never
  came in reach and #2's landing stayed shut. A non-sneaking breaker node waits (one tick in the sim) while a block is in
  reach only from the standing eye (`RouteExecutor.breakerStandTicks`; testkit 62-argrim-1020-breakerstand).
- **`/ar edit db` measured reach from the NODE, not from him** (killer560, 2026-10-09: "it says they are too far for my
  breaker"). Picks are measured eye-to-box at 4.5 (a plain dig - the wiki gives the Dungeon Breaker no range), and a pick
  far from the node is only noted in chat, since playback digs from wherever the route has him (96-ar-658-dbreach).
- **A rule that fires on "blocks standing" cannot tell a regrow from an edit.** The breaker re-fire (2026-10-12) breaks a
  latched breaker node's standing blocks while he stands on it; `/ar edit db` picks add standing blocks to exactly that
  node, so the tick edit mode closed it broke both picks (96-ar-add: 2 digs). The picks themselves never sent a packet.
  Closing edit mode now puts the room's breaker nodes in the re-fire's `refired` set.

## Refused and dropped warps

- **Hypixel acks block clicks but not a bare item use** (Mod Only Test log 2026-10-19): the refused-warp retry gated on
  `ChestWindows.acked(warpUseSeq)` fired only when one of HIS later block clicks brought an ack in, and never in six Slime
  #9 refusals, each of which sat the 60-tick timeout and re-fired #5's whole stack from the same spot. The retry now also
  counts a use as handled after `NO_ACK_ROUND_TRIPS`; an unlanded warp from an unmoved spot holds its node
  (`refireHoldAt`) until he left-clicks or moves; and a resend re-selects the etherwarp item (two resends went out with
  BAT_WAND / ITEM_SPIRIT_BOW after he changed slots while it waited).
- **A warp the server will take as an unpaired click's second half is sent again, not waited out** (fullclear-1016; his
  Cathedral #15 and Crypt #5, every time a chest opened with the AOTV by the hop's spot). The 1.8 server's ray (1.54 eye)
  met oak leaves the client's 1.27 look never reaches, `RightClick.reaimIfUnpairable` found no look with agreeing rays,
  and the use was dropped: Auto Routes sat out its 61-tick landing timeout until Route Stall stopped the route; the
  Interactive Map chained four more hops from the landing that never came. The dropped use clears the server's stored
  click whatever happens, so when `barePairedUses()` rose on a warp both executors send it again after
  `RightClick.eatenRetryTicks()` (1.5x the measured round trip + 2 ticks, at least 3; 300 ms before the first pong) with no
  landing, and ClearExecutor queues nothing behind it meanwhile. If the judgment was wrong the landing arrives inside that
  and nothing is resent. Testkit 96-ar-1016-eaten (main: timed out; fixed: resent 4 ticks after, landed).
- **An unpaired click outlives the warp after it and eats a later one** (his Bridges, 2026-10-17: "after the second dungeon
  breaker I cannot get it to make that etherwarp ... if it stalls out then it works"). Secret Aura flipped #1's lever with
  the AOTV in hand; #1's warp was a right click in the air, so the lever's verdict stayed stored through #2's breaker. The
  warp down the hole the breaker opened passes under the rim from the 1.27 eye while the 1.54 ray meets Stone Bricks at
  -83, -51, -118 2.8 out, and no other look onto the landing had agreeing rays: the use paired with the 10-tick-old lever
  click and was dropped, every run. The stall "worked" because the dropped use had paired the click.
  `RouteExecutor.spendStaleClickFirst` pairs the click with a bare Dungeon Breaker use at the heard look the tick before
  (`RightClick.spendStoredClick`: only when `unpairable` holds, i.e. the client's look reaches no block, so one bare use is
  what a vanilla right click sends) and the warp goes the next tick. Testkit 96-ar-1017-bridges (his route, exact),
  62-argrim-1017-breakerwarp.
- **A warp the server refused is sent once more, not waited out** (fullclear-1016 run 019, his Doors #13 hop 3: the
  server's ray stopped at a jungle log - the shut way described under "His routes count a secret bat's APPEARANCE"; the
  retry is a guard, not Doors' fix). With no landing a round trip on and him not moved, `RouteExecutor.retryAim` aims at
  the same block with a look that holds within `EtherSearch.AIM_MARGIN`; a saved hop fired from its own spot is re-aimed
  first when its look only lands for its exact floats (`TeleportUtils.holdsOn`). Testkit 96-ar-1016-refused. Since
  2026-10-19: on Hypixel the first warp a tick after a Secret Aura lever or chest click went unanswered in 6 of 8 cases
  (cause NOT found: server ray met nothing, the same look landed on the resend), and the retry, gated on 3 round trips
  because Hypixel never acks a bare use, came 13-22 ticks later while every landing took 2-4. It now goes past
  `RouteExecutor.refusedRetryTicks()` (2x the slowest of the last 5 landings + 2, or `eatenRetryTicks`), up to twice per
  warp, and `retryAim` resends the same look when no other holds (it used to send nothing: Pedestal #3 waited for his left
  click). Each secret await logs "secret gap: ..." when the next node fires. Risk on Hypixel: a landing slower than the
  retry (plus measured server lag) would be followed by a second warp; ServerLag covers stalls, not a single late packet.
- **On Hypixel a use one client tick after a block click made with the SAME item gets no teleport about two times in
  three** (ar-1019, every AOTV warp with an unpaired AOTV click before it in his 2026-10-19 logs): 1 tick after, 45 of 69
  refused; 2 ticks, 1 of 7; 3+, 0 of ~250; after a Dungeon Breaker or Superboom click, 3 of 3 landed one tick on. Every
  K=1 case had a fresh sneak, but 19 of 19 fresh-sneak warps with no click landed, and the server ray met nothing in every
  refusal, so it is neither the sneak nor the 1.8 pairing; the cause is NOT known. QUOI (`interactDelay = 2`) and RSA
  (`lastBlockC08 = 2`) both resume at N+2. `RouteExecutor.clickGapHolds` sends the warp two client tick ends after such a
  click (counted from `client_tick_end` packets, the server's own clock); the testkit's `HypixelClickThenUse` plays the
  rule (96-ar-1019-clickgap: main ignored the warp and retried, 7 ticks; fixed 3). `ar1019-refusals.py` in
  killer560s-mod-logs rebuilds the table from any log.

## Awaits

- Awaits never read the "x/y Secrets" bar (since 2026-10-05): it is the ROOM's count, so a teammate's secret raised it,
  and levers never touch it. `autoroutes/AwaitEvents` counts only his own clicks/pickups/nearby bat spawns/mimic kill,
  from the previous node's finish. The sim still sends the bar (`roomsim/SimActionBar`) for the map.
- An await met by the chest CLICK lets the route warp before the chest's window arrives, which is right - Hypixel credits
  the chest on the click - but the late window then stopped the route ("a screen opened"). A container screen within 2 s
  of our own chest click is waited under (98b457be; 96-ar-play emulates the late window). In the sim the window arrives
  1-2 ticks after the click, so whether it lands before the use is the client/server tick phase, which drifts through a
  session; when it does, the action starts over under it ("window arrived after the await was met", logged) and acts a
  tick or two later. 96-ar-1015-reaim's control read 2 for 1 in session runs; it reruns a side the window landed in.
- **The start node's lookback.** Secret Aura and Auto Routes are released on the same tick after an Interactive Map warp,
  and the aura ticks first (CheatUtils registers before Auto Routes), so its click on the start node's secret came one
  tick before `AwaitEvents.begin` and #1's await sat at 0/1 (Museum, three runs of four, 2026-10-07). `begin` counts his
  own secret clicks and pickups from the last `START_LOOKBACK` (10) ticks (96-ar-398-startawait). But Secret Aura also
  takes the LAST room's secret as the map's warp leaves it, inside those 10 ticks, and that met Museum #1's await from 50
  blocks away (fullclear-1016 run 010), so a pre-start secret counts only within `RouteRunMemory.NEAR_NODE` of where he
  stands when the route starts (96-ar-1016-farstart).
- **`tickAwait` uses the same `step` field as the action**: an etherwarp node waiting on its await is in `Step.CONFIRM`
  before its PREP has set `warpLanding`. `checkCorrections` read that as the warp's confirm and measured a 1-block server
  move from a stale landing ("47.4 from its landing"), let the start node go, and the route stood still (his 1 in 5 "map
  warp put me off the node"). It now requires `awaitPhaseDone`, and `beginAction` clears the warp fields. What moved him
  that block in his sim is NOT found: 20 map warps with his mimic, slot, speed 600 and the map open never moved him
  (96-ar-405-startwarp), so the case injects the move.
- **On Hypixel the item he warps onto is taken in the same batch as the landing** (killer560, 2026-10-14: "it picks them
  up faster than it knows it grabs them"). The take packet is handled before the route's tick sees the landing, so it was
  counted in the previous node's window and wiped by `window()`; an integrated server picks up a server tick later, so the
  sim never showed it. A secret got while the previous node was acting, in its last `AwaitEvents.CARRY_TICKS` (10), within
  6 blocks and unclaimed, counts for the next node; the sim picks up on the teleport step
  (`SimSecretItems.pickupOnLanding`), and the testkit's `HypixelOrder` forces that order on any jar (96-ar-1015-landpickup).
- **A secret got under a node with no await belongs to the next await, however many await-less nodes come between**
  (fullclear-1016 run 004, his Double Diamond #4 PATH / #5 breaker / #6 etherwarp, #7 awaiting the item the breaker opened:
  the Magnetic Talisman range picked it up while #4/#5 acted and #7 waited 20 s). `finishAction` keeps the carry a node
  began with when it has no await (`nodeCarry`). Testkit 96-ar-1016-carry.
- **A left click only skips while the node is ALREADY waiting** - one made while the warp onto it was in flight went to
  vanilla as a swing a tick before the await opened, and one while Kill Mimic held the hands was never read (that branch
  of `tick` returned before `tickAction`). Both are taken now: held `EARLY_SKIP_TICKS` (10) for the next await, and Kill
  Mimic is stopped by the skip (96-ar-1015-earlyskip, -mimicskip).
- **A lever is not one of a room's secrets.** Awaits count his lever flips (a route records one to wait on), but Auto
  Secret's room count reused that memory ("Museum: every secret counted (5/5: map 4/5)" with the lever as the fifth,
  fullclear-1016 runs 002-004). `RouteRunMemory.secretsSince` / `secretsWhatSince` leave levers out; awaits still count them.
- **The Redstone Key.** Hypixel credits the PLACING ("rewards 1 secret", wiki): the node click counts (at once with a key
  known found on an open face, else on the server's answer); Secret Aura has always placed it (no hotbar swap: not an item
  since 0.18.4) (aura-skull-place-1016, testkit 1415, 96-ar-1016-key*, 848). His routes also count the key's PICKUP as an
  await event (his Redstone Crypt #1, await 2: its chest and the key, stood 20 s on every floor once only the placing
  counted, ce2cc26b), so the pickup counts again once the skull goes (`AwaitEvents.countPickup`) and the run memory files
  it like a lever, so Auto Secret's room count still has only Hypixel's secrets (96-ar-1016-keyawait/-keyaura).
- **An await beside a Redstone Key spot the key never spawned at starts one secret on** (killer560, 2026-10-18: a room
  with two key spots needs an await at each, and the empty one waited until he clicked). `autoroutes/KeySpots` reads the
  room's key spots from the room database; a node with a SECRET await whose eye (feet + 1.62) is within 4.5 of a spot's
  block box gets one remembered secret per spot that is loaded, holds no player head, and never held a key this run (each
  spot is looked at every tick in the room, and his click on a key marks it), only in a room with two or more spots
  (Redstone Key). Any player head counts as not empty, so a head whose profile has not reached the client yet is never
  read as "no key". Testkit 1018-ar-keyspot-empty.
- **Secret Aura clicks nothing while a route runs unless the node waits for secrets** (killer560, 2026-10-16: a WALK route
  sprinting past a lever had the aura flip it mid-walk and miss its chest; 96-ar-1016-auranoawait). The one gate is
  `RouteExecutor.secretAuraMayClick()`, asked in `SecretAuraFeature.tick` before the body turn, the ActionGate and the
  attempt booking, and in `scanTaking`, so a held-back secret is just picked again later. It is true with no route
  running, and in a run only while the active node waits on a SECRET await it has not met. A warp in flight, the settle
  between nodes, a DELAY await, a crypt/bat node and an await already met all say no. So a secret passed mid-route is
  taken only if an await node has it in reach (Kill Mimic's trapped chest and the Redstone Key's pickup and placing too);
  item pickups are untouched (96-ar-1016-auraawait, 62-argrim-1016-auraawait).
- **His routes count a secret bat's APPEARANCE toward the node waiting when it appears, BAT node or not** (QUOI's rule).
  Deathmite #11 breaker awaits 2, a chest and the bat that #12 BAT then kills, on one tile; taking the appearance away from
  #11 while a BAT node was ahead stalled it 20 s (fullclear-1016 run 034), so that change was reverted. The same rule is
  why his Doors fails under aura-await-only: #7 awaits 2, the bat appears 8.6 blocks off and meets one, the second lever
  is left to Secret Aura mid-route, and the way it opens stays shut, so #13's hop 3 is refused at that jungle log. Route
  data ("needs await node"), not code.
- **A sceptre BAT node counts its uses from the OUTGOING use packets** (`BlockClickGapSendMixin`:
  `ServerboundUseItemOnPacket` and `ServerboundUseItemPacket`), one per tick. The game-mode calls (`useItem` HEAD,
  `useItemOn` RETURN) are not seen in the sim: Fabric's UseBlockCallback answers an ability item at the head, so the first
  version never left its two downward uses (96-ar-1015-batseek). Past two it lets go of the held key and sends plain item
  uses at the nearest secret bat. Run memory carries between cases: call `forgetRunMemory` first or the node says "its bat
  was already killed". No real Hypixel BAT-node run exists in his logs yet (every `Node #N BAT` line in Mod Only Test is a
  sim run), so "a bat already alive on arrival is never killed" is NOT reproduced (96-ar-1015-batpresent passes on main).
- **A Spirit Sceptre fired straight down never reaches a mimic six blocks up** (his Chains: the trapped chest at y -43 over
  his feet at -49, 51 uses at pitch 90.0, then "did not die in 5 s"). The blast is 6 from where the bat hits the floor.
  Kill Mimic sends two uses down and aims the rest at the mimic (a new baby zombie by the chest, `AwaitEvents.liveMimic`)
  while `RouteExecutor.sceptreReaches` says its bat gets there, in every room (killer560, 2026-10-17); obvious mode uses
  only once the server has heard the look. Testkit 96-ar-1017-mimicaim (main: 51 down, no kill; branch: 2 down, 3 aimed,
  killed), 62-argrim-1017-mimicaim.
