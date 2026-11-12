# Dungeon Sim - the F7 boss (bosssim/)

Built 2026-11-01 (branch doc-adm-boss-1101) from the to-do Doc item "NEW REFRENCE MOD - undonecoffee/adm ... their boss
stuff". Owner decision: all F7 phases together. Code: `com.killer560.hub.bosssim`, its own package; roomsim is touched
only by the sim menu's "F7 Boss" entry (`SimMenuNav`).

## The reference mod and its license

ADM (github.com/undonecoffee/adm, cloned read-only at `C:\Users\Hunter\ref-adm\adm`) declares
`"license": "All-Rights-Reserved"` in its fabric.mod.json and ships no license file. Nothing of its code or assets is
used. What was taken is FACTS about Hypixel's boss it measured: the [BOSS] lines and their tick spacing, the station
coordinates and stand heights, the terminal titles, window sizes and slot layouts, the Storm pillars and pads, the lava
bounce rule (every 2 ticks, 1.8 box, motion 2.25 or 3.0375 above pitch -40.7, 4-tick cooldown). Its `arena.bin` is a
block capture of Hypixel's boss room (box x -8..143, y 0..255, z -8..159) and is NOT ours to ship.

## Where it runs

- The boss sits at Hypixel's own coordinates in the sim world (positive x/z); the clear is on the negative side, so both
  fit in one world and `Floor7Tracker.inF7Boss()` (x > -7, z > -7) is true by position alone - every boss feature
  (Terminal Solver, AP3, P3 Nav, Fast Leap...) works with no special case.
- Ways in: the floor's Blood room portal (`BossPortal`: the Watcher's "You may pass." line, or five seconds standing in
  Blood while the sim has no Watcher, i.e. Watcher Blood Camp off: `roomsim/SimBloodCamp`), the sim menu's F7 Boss entry (`BossStartScreen`), `/simboss <phase> [floor7|master]`.
  Starting from inside the sim does NOT go through `SimWorld.open` (its in-sim path resets the floor's per-map state).
- Server side: `BossSim.serverTick` (END_SERVER_TICK, only for the integrated server while `SimState.isActive()`),
  interaction callbacks answer only while `BossSim.serverOk` (sim + a fight running).
- `DungeonState.simBossBegins/simBossEnds`: a fight started past P1 has no Maxor line, so the boss phase is set directly;
  leaving the boss box for 2 s or changing world ends it. While it runs `getFloor` is the fight's mode (F7 / M7), and every
  "Hypixel or p3sim" address gate counts the sim (simgates-1105, list in killer560s-mod-logs/simgates-1105.md).

## The room

`BossArena`: his capture, BUNDLED in the jar and always used since coopnet-1110 (`assets/killer560smod/sim-boss/sim-bossarena.bin`
for P1, `sim-bossarena-p3.bin` for P3; no file on disk is read, there is no override; the `/simbosscapture` command was removed
2026-11-05; each capture was taken in its boss phase (Floor7Tracker), format K5BA v2 = v1 plus the phase after the version; v1: gzip, box,
palette of block-state strings, run-length cells; a v1 file counts as its file name's phase); only when the bundled file cannot be read
(logged as an ERROR) the built-in plan: plain floors at P1 220, P2 168, P3 106 (lava inside the ring), P4 62 (lava with
Necron's platforms), P5 4; every terminal on a command block on a pillar with a slab stair down toward the section's
middle; gates as iron blocks; the core with a gold door. Over a capture NOTHING of the plan is laid (bossfloor-1104):
re-writing the capture resets every block a fight changed, and the plan's gates, holes, pillars, Necron platforms and
device walls had been sitting on the real room (a gray pad on Storm's red floor, iron gate walls, gray concrete for Lights'
redstone lamps, an obsidian slab over the Simon Says frame, the Target plate floating). His capture of 2026-11-04 22:18 is
`killer560s-mod-logs/fixtures/boss-arena-1104.bin` (testkit 1104-boss-capture diffs the built room against it; read it with
`killer560s-mod-logs/bossfloor-1104/cap.py`, `slice.py`, `find.py`). What the capture shows of Hypixel's room: the gates at
P1 are BARRIER walls in nether-brick frames (`BossBlocks.open` clears only barrier/iron/gold in a gate's box); Lights' lamps
are redstone lamps (a pull is answered in `BossDevices.pullLight` with no redstone updates, lamps set by Hypixel's rule);
the Target plate is a light weighted plate at (63, 127, 35) on blue terracotta; the P3 ground is y 113-116, not the plan's
106: P3 spawns stand on the ground found there (`BossArena.standY`; S1's is 116.0625, a carpet). A capture is read as the client SHOWS the room, so one taken with I Hate Diorite on has stained glass in Storm's
pillars: `readCapture` puts polished diorite back. Item frames are entities, never in a capture: the Arrows frames hang
from the fight's start (`BossDevices.arrows`). All sim lava and fire stand still (`SimStaticWater.holdsStill`, SimStillFireMixin), as Hypixel's, and the capture build skips onPlace (`UPDATE_SKIP_ON_PLACE`), so its hoppers keep `enabled=false` and nothing schedules a tick.
The fight starts from the P1 capture; P3's start (`BossArena.applyP3`, at a P3 start or when P2 ends) writes the 220
blocks of P3's band (y 100-145) in which his P3 capture (2026-11-04 23:21, S1, fixture `boss-arena-p3-1104.bin`) differs
from the P1 one: the wall of nether bricks, cobblestone walls and glass panes at x 77-92, z 35-45 that closes S1's way in is
gone, and the Arrows wall's sea lanterns (x -3) sit where that run put them. The gates are barrier walls in both captures
(no other gate block exists). The core's floor in the capture is a 7x7 plug of gold and bedrock at y 113 (x 51-57,
z 111-117) over a shaft that is already open down to P4's platform (top 63); over a capture `BossFight.openCore` clears
only the plug (the plan's `CORE_HOLE` at 105-106 cut solid stone under a whole floor, and nobody fell), and P4's spawn
stands on the platform found there (y 64; the plan's 63). The core door is the 5x7 of gold blocks at x 52-56, y 115-121,
z 54 (in both captures); opening the core removes only those (killer560, 2026-11-05: "the core door just removes those gold
blocks"; `BossFight.CORE_DOOR`). Maxor's and Storm's holes over a capture are the cells of y 214-224 and 160-170 that are
solid in the P1 capture and air in the P3 one (1214 cells, `BossArena.openCapturedHole`): the plan's holes cut over
bedrock at 218 and 166 there, so nobody fell. Necron's hole (P4 to P5) has no capture yet. In the P1 capture his central
platform is a stone ring (y 54-62, x 50-58, z 72-80) around a shaft open down to the P5 floor, under a disc at y 63-64;
over a capture `BossFight.openP4Floor` clears only the disc cells over the shaft (`P4_SHAFT`, where y 62 is air) and keeps
the ring (simdragons-1105: the plan's P4_HOLE at 61-62 cut the ring's top off and left the disc hanging, his "missing the
underside"). Necron's side-platform break over a capture turns the platform's blocks at y 59-64 to air (iron pillars kept)
so the lava sea at 58 shows; the plan's lava plane at 62 floated over open sea there (his "offset lava"). Still unverified: the Arrows wall's lanterns. Those are 3 sea lanterns among the 25 cells of the blue terracotta wall at x -3, y 120-124, z 75-79
(index dy + dz * 5): {2, 12, 22} in the P1 capture, {0, 17, 20} in the P3 one, whose run logged `[ArrowAlign] layout #5`;
17 and 20 hold no frame of layout 5, so no rule ties them to the layout (the P4 platform's stone pattern also differs
between the two captures: Hypixel seems to shuffle such blocks per run). The sim keeps the P3 capture's lanterns.

## The fight

`BossFight`, one server tick at a time, every boss moving and timed as ADM measured Hypixel after its 2026-10-05/06 boss
update (bossparity-1103 brought it in line; the item-by-item comparison is killer560s-mod-logs\bossparity-1103.md).
Every boss is a no-AI wither moved by the fight (`BossMob.moveTo(pos, look)`, yaw = atan2(-dx, dz)).

- **P1 Maxor**: holds at (73, 226, 53) until t 84, then flies at the closest player +1 at min(0.9, 0.24 + 0.02 d) and
  stops within 3. Crystals at t 5 (stands at 28): walking within 1 block of one picks it up with no click, and a left or right click on it from entity reach does too (a Nether Star "Energy Crystal" in slot 8); a carried crystal is placed with no click within 1 block of an open pad (a click within 3.5 still places it; simcrystal-1105, owner 2026-11-05); the
  pylons open at t 80, the beacon is in at 120. A placement counts 28 ticks after it ("1/2" twice when two go in close
  together); the laser charges 28 after the second. A hit needs Maxor at y >= 225 within 3.5 of the beam on a 10-tick
  check, so he has to be lured in (the strip south of the beam, y 224 z 70-76). The stun ends on damage (one hit drops the bar to its floor,
  then 6-12 ticks) or after 100-199; the second laser hit kills him 8-15 later; his floor goes at kill + 59.
- **P2 Storm**: flies his opening route at 0.4, parks at (102.375, 183, 52.375), chases from t 647. Pads read on
  t % 20 == 19 queue 5 pillar steps 4 ticks apart; a crush is Storm in the square with y + 2.975 at or over the bottom of a
  pillar that stepped in the last 60 ticks. Crush 1 pins him (median 10), crush 2 kills him (death line median 6 later).
  The drop to S1 is under the Red pillar, 24-30 ticks after Goldor's line.
- **P3 Goldor**: walks his loop round the sections (0.048, then 0.06; a 0.6 sprint to the next corner when a door opens
  with him still in its section), flies into the core (0.8) once everyone is in it; one hit there kills him (ADM's kill is a
  timer standing in for a party's damage); his "...." queues behind his lines; the core's floor goes 2 ticks before
  Necron's line. Death-tick and Frenzy LINES only (the sim deals no damage).
- **Necron's pull** (necronpull-1111, killer560 2026-11-11): as the core floor goes, Necron pulls every player (co-op
  guests too, spectators not) down the shaft and holds them hovering 3 over his platform through his welcome lines, then
  lets go at "Goodbye." (P4 t 84). Facts: the floor goes 2 ticks before his first line, 62 ticks after Goldor's death; the
  wiki's "as the players are about to reach the bottom ... Necron hovers them in the air over a platform". All movement is
  server side (`BossFight.necronPull`): a player outside the shaft (x 52-57, z 112-117) is teleported into it at his own
  height, one above the hover height is pushed down at 1.2 a tick and drawn to the shaft, one at it is held (motion 0,
  teleported back past 0.3); the look is kept (relative rotation). Logs "Necron pulls everyone down" and "Necron lets go".
  The older wiki text about Necron breaking the platform under them is not modeled. Testkit 1111-boss-necronpull.
- **P4 Necron**: a fixed script; his bar holds in each part (1 -> 0.8, 0.8 -> 0.25, 0.25 -> 0.05) until one hit takes
  that part's damage: lines 0/42/84/126/188/270/332, a sidestep and two trips out at the player,
  back to mid at 98 and 240, one side platform to lava at 275, gone at 389.
- **P5** (M7): a dragon is one pool of 15 units (boss-tpto-1109, killer560 2026-11-09; integers, no drift): EVERY arrow takes 1 (necronpull-1111: an arrow counts in `SimDragon.hit` itself, never through `onHurt`'s once-a-tick rule, so all 3 arrows of a Terminator volley count), a Mage beam 3 (`SimClass.BEAM_DAMAGE` 10,001 vs a Terminator arrow's 10,000 tells them apart in `BossFight.onHurt`), so 15 arrows, 5 beams or 10 + 2 kill one; "[BOSS] The Wither King: ..." lines. The relics are not modeled. Each dragon spawns
  at its `WitherDragon.spawnPos` 100 ticks after a FLAME burst at y 19 over it (the mod's spawn-timer spec) and places its
  8 parts each tick. Health (necronpull-1111): it sends Hypixel's 1,000,000,000 synched health, scaled by the units left
  (`SimDragon.showHealth`, through `SimLivingHealthAccessor` because `setHealth` clamps to the vanilla max of 200), so
  Wither Dragons' readout drops on every hit. Flight (necronpull-1111, his "mimic the movement from hypixel not this
  random type of flying"): no public trace of the real paths exists, so the pattern keeps what is known: it rises
  straight up out of its spawn point for 40 ticks (0.12 a tick, where stack-shooters aim at spawn + 3.5), then loops its
  own area (`WitherDragon.box`) round the box center at radius min(x, z size)/2 - 5, 0.3 a tick, yaw 6 a tick,
  alternate dragons the other way round, its height swinging slowly between the rise top and 3 under the box top.
  Testkit 1111-boss-dragonhp (rise, loop, 3 arrows in one tick = 3 units, client and Wither Dragons health).

Every hit goes through `SimWither.hurtServer` / `SimDragon.hurt*` -> `BossFight.onHurt`, one per target per tick
(except arrows on a dragon, which count one each: that rule ate 2 of every Terminator volley's 3 arrows, his "the dragon
doesnt feel like it is taking every single arrow", necronpull-1111).
One-hit phases (simitems-1105, killer560 2026-11-05: one mage beam or Terminator volley does all of a phase's damage):
the bars (Maxor's, Storm's, Necron's) are the fight's only health and move ONLY on his hits and on Hypixel's scripted
steps (laser hits, crushes, enrages, deaths); they used to drain on the clock, which read as damage with no hit. Storm
pinned: one hit drops him to 0.45 (0 after crush 2). Damage with no player behind it (lava, fire, mobs) goes through
`BossFight.bossDamage`, which refuses it and logs "[BossSim] refused <msgId> damage" at most once a second.
Lava gives him no hurt tilt: `BossLava`'s bounce is a `SimLaunch.push` and sends no hurt animation. Testkit
1105-boss-onehit (one case per boss), 1105-boss-nostray, 1101-boss-lava (hurtTime stays 0).

## P3

`BossStations` (29: 17 terminals, 8 levers, 4 devices; 7/8/7/7) - stands, lines ("activated a terminal! (n/m)" plus the
same subtitle), the section flow. A stand click answers 1 tick later (the window, or "This Terminal has already been
completed!" / "...doesn't seem to be responsive at the moment." for another section's); another section's lever only
toggles; a done lever says "Someone has already activated this lever!". `BossTerminals` - real chest menus (`Menu.clicked`
never moves an item), six types, kept per terminal across reopens, at most 5 counted clicks in 10 ticks, Numbers 10 and
Melody 3 lanes in a 6-row window (Hypixel's 2026-10-06 update); the system property `killer560.bosssim.terminal=<TYPE>`
forces a type (testkit). `BossDevices` - Simon Says (4 rounds, as Hypixel's since its 2026-10-06 update: his
own log has "SS 4/4" 12 times and no 5th round, and "[SimonSays] Device has 4 rounds (new layout) on proxy.hypixel.net"; first show 4 after the start press, lamps 8 apart, the
start button resets a running device, a wrong press stops it), Lights (20 levers, a lamp lit by its lever or a
neighbour's), Arrows (frames on one of `ArrowAlignFeature.solutions()`; as on Hypixel two more frames hold wool, lime where the path starts and red where it ends: an end is an empty cell an arrow points into, a start the empty cell behind an arrow no other points into, else a free neighbor; rotation 1/5 = -z/+z and 3/7 = down/up, the one mapping that keeps all nine layouts on the wall; the wool frames never turn), Target (9 targets, lit only while someone is on
the plate (63, 126, 35), an arrow in the lit one turns it blue terracotta, 9 hits; off the plate the attempt resets).

Simon Says runs on round TASKS, as ADM measured Hypixel's (bossfix3-1105): a start press while stopped draws a pattern and
queues a round task 4 ticks on; a start press while started resets (round 0, lamps off, buttons gone) but never cancels a
queued task or a running show. A task adds a round and shows it with the pattern as it is then: every button gone, lamp j
at 8j takes its own button and goes out 8 later, the buttons back at 8n+10 except under lit lamps, presses count from then;
the next task 6 ticks after a round's last right press, completion past round 4 (lamps and buttons gone). The skip is a
consequence, not a special case: start, reset 3 ticks later, start 3 later; the first start's task shows round 1, the
second's adds round 2 (three lamps seen, two put in). A wrong press: round 0 and stopped, the buttons gone 3 ticks later, a
round 2 show of a new pattern 26 ticks after that (or a stray round 1 4-8 ticks before one at 34), its stale buttons back
1-4 ticks into each show. The old code reset to IDLE and dropped the pending show, so the skip did nothing.

Boundaries (`BossGates`, over a capture; the plan has its iron walls): each is a GATE (the arch's cracked stone bricks and
stone brick stairs) and a DOOR (barriers, iron blocks, nether brick stairs, then the portcullis of cobblestone, walls and
nether brick fences 5 / 4 / 4 ticks later), read inside the door boxes (95,115,121)-(105,136,124), (16,115,127)-(19,136,137),
(3,115,48)-(13,136,51). The door kinds there match his captures' 371/371/370 cells exactly; the arch kinds inside the box
cover the gate except a few cells outside it (Hypixel's gate box also holds floor at y 113-114, so it is not used to clear).
Section done with its gate up: "The gate will open in 5 seconds!", the door's iron and stairs from y 118 up a tick later,
and the gate blown 100 ticks on. "The gate has been destroyed!": the arch goes a tick later, and the door too when the
section is done. A Superboom within 1.5 of a gate's box (`BossFight.superboomGate`, from `SimItems.superboom`) blows it
only once its section is in progress, and is used up on the gate either way (no blast into the arch); a section that then
ends opens its door at once, no countdown. The P3 HUD's Gate row is chat-driven (`P3SectionTracker`), so it needs no change.

## Tests

testkit `SimBossTests`: 1101-boss-p1p2, -p3, -p4p5, -lava, -ap3, -portal, 1102-boss-maxorcrys, 1103-boss-moves (boss
positions over time), 1103-boss-rules (refusals, levers, terminal rules, devices, death ticks), 1104-boss-capture (the room built from his capture equals it; Lights, SS, frames, still lava, gates on it), 1105-boss-dragons
(over the capture: Necron's break leaves no lava above the sea, P5's ring under the disc, Red's flame timer and spawn
point, flight, Terminator kill on Red, melee kill on Orange, Wither Dragons reads both deaths), 1105-boss-fix3 (items kept across phase starts, the SS skip and its buttons, a boundary gone after its gate line, Superboom on gates 2 and 3, the inventory kept through the chooser's Section 1, the Arrows wool marks) - only when named. A phase start while a fight runs keeps every slot (`BossSim.buildAndBegin` copies the inventory before the rebuild and puts it back before `begin`); the sim loadout applies only on a world load.

## Lessons

- An entity in a chunk that unloads comes back as a NEW object when the chunk reloads, so a fight that keeps entity
  references (stands, frames, bosses) loses them: S2's far terminal "was no terminal's" when clicked (1101-boss-p3,
  2026-11-01). The fight forces every arena chunk loaded while it runs (`BossFight.forceChunks`) and its entities are
  never saved (`shouldBeSaved` false), so a world closed mid-fight cannot bring back a vanilla, AI-driven wither.
- A NoAi `EnderDragon` never moves its parts: `aiStep` returns before `tickPart`, the 8 `EnderDragonPart`s stay at
  0,0,0, and the dragon itself is not pickable, so no arrow or sword could hit a sim dragon (simdragons-1105, his
  log: 36 Terminator shots, no "dragon down"). `SimDragon` runs its own `aiStep` that flies and calls `placeParts`.
  Its `tickDeath` is empty (vanilla's plays the world-wide death roar, 1028, and drops XP).
- Hypixel's King lines read "[BOSS] The Wither King: ..." since its boss update; Wither Dragons' counts regex, Wither
  ESP and Rag Axe matched only "Wither King:" (fixed in simdragons-1105, real Hypixel too).
- Order matters to the client: the sim says the King's counts line 2 ticks after a dragon's health goes to 0, because chat
  goes out at once and entity data at the tick's end, and Wither Dragons marks a dragon dead on whichever comes first (only
  the health death prints "alive for"). The FLAME burst is sent long-range (`sendParticles(.., true, true, ..)`): a plain
  send reaches 32 blocks and Red's point is about 35 from the P5 landing, so the spawn timer never started (simdragons-1105).
- A floor hole must go through the bedrock bed under P3's and P4's lava too, or he lands on it one block down
  (1101-boss-p4p5 read y 106.0 and 62.0).
- The station table's lever stand is at lever y + 0.688; `y - 0.688` in floating point floors to the block BELOW
  (127.99999...). Round it.
- A vanilla stone button stays pressed 20 ticks; Hypixel's Simon Says buttons come back up in 2 (start) and 3 (grid)
  ticks, so a reset press 15 ticks after the start press was invisible to the edge check (1103-boss-rules read INPUT
  where IDLE was due, 2026-11-03). `BossDevices.popUp` raises them on Hypixel's timing.
- Read the reference's own line before changing a behavior from a summary of it: a summary of ADM said the timed gate
  opens without "The gate has been destroyed!", but ADM's auto gate calls the same blow path with no player, which sends
  it (GoldorPhase.kt blowGate), and the mod's P3 trackers depend on that line (bossparity-1103).
- Maxor's stun used to come 60 ticks after both crystals wherever he was, so a test could "stun" him with nobody near the
  beam. Hypixel's needs him in the beam: a P1 test has to lure him (stand on the strip south of it, 73.5 225 76.5).
- Lights device start (simlights-1109, killer560 2026-11-09): the four corner levers and the middle two (60,134 and 60,135, the LIGHTS list) start ON and every other lever off, so every lamp starts lit; the first pull of ANY lever (on or off) completes the device 2 ticks later (`lightsPulled`), the usual "completed a device! (1/1)" path. `/pc`, `/pchat`, `/party chat`, `/p chat` in the sim are swallowed by `roomsim/SimPartyChat` (ALLOW_COMMAND) and answer "You are not in a party right now." (Hypixel, no party), never the vanilla unknown-command error. Testkit 1109-boss-lights.

## Boss spots (boss-tpto-1109)

`/tpto` and `/goto` inside the boss take `BossSpots`: ss, s1, hee2, ee3, outcore, storm, maxor, p5, necron. Coordinates are Posmsg's presets (killer560's own: Simon Says, EE2 High, EE3 Low, Outcore, P5, Necron's Platform) and BossFight's start spots; over a plan room (no capture) the P3 spots fall back to the section start. The menu's Section 2/3/4 start at hee2/ee3/outcore. Attack speed: the sim already fixes every weapon at 100 (`SimAttackSpeed`: melee 5 ticks, shortbow 5, Mage beam 5); testkit 1109-boss-tpto checks the constants.
