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
  Blood while the sim has no Watcher), the sim menu's F7 Boss entry (`BossStartScreen`), `/simboss <phase> [floor7|master]`.
  Starting from inside the sim does NOT go through `SimWorld.open` (its in-sim path resets the floor's per-map state).
- Server side: `BossSim.serverTick` (END_SERVER_TICK, only for the integrated server while `SimState.isActive()`),
  interaction callbacks answer only while `BossSim.serverOk` (sim + a fight running).
- `DungeonState.simBossBegins/simBossEnds`: a fight started past P1 has no Maxor line, so the boss phase is set directly;
  leaving the boss box for 2 s or changing world ends it.

## The room

`BossArena`: a capture (`/simbosscapture`, dev builds, on Hypixel in a boss; file `config/killer560/dungeons/sim/
killer560smod-sim-bossarena.bin`, format K5BA v1: gzip, box, palette of block-state strings, run-length cells) when one
exists, else the built-in plan: plain floors at P1 220, P2 168, P3 106 (lava inside the ring), P4 62 (lava with
Necron's platforms), P5 4; every terminal on a command block on a pillar with a slab stair down toward the section's
middle; gates as iron blocks; the core with a gold door. Over a capture NOTHING of the plan is laid (bossfloor-1104):
re-writing the capture resets every block a fight changed, and the plan's gates, holes, pillars, Necron platforms and
device walls had been sitting on the real room (a gray pad on Storm's red floor, iron gate walls, gray concrete for Lights'
redstone lamps, an obsidian slab over the Simon Says frame, the Target plate floating). His capture of 2026-11-04 22:18 is
`killer560s-mod-logs/fixtures/boss-arena-1104.bin` (testkit 1104-boss-capture diffs the built room against it; read it with
`killer560s-mod-logs/bossfloor-1104/cap.py`, `slice.py`, `find.py`). What the capture shows of Hypixel's room: the gates at
P1 are BARRIER walls in nether-brick frames (`BossBlocks.open` clears only barrier/iron/gold in a gate's box); Lights' lamps
are redstone lamps (a pull is answered in `BossDevices.pullLight` with no redstone updates, lamps set by Hypixel's rule);
the Target plate is a light weighted plate at (63, 127, 35) on blue terracotta; the P3 ground is ~y 111-114, not the plan's
106. A capture is read as the client SHOWS the room, so one taken with I Hate Diorite on has stained glass in Storm's
pillars: `readCapture` puts polished diorite back. Item frames are entities, never in a capture: the Arrows frames hang
from the fight's start (`BossDevices.arrows`). All sim lava and fire stand still (`SimStaticWater.holdsStill`, SimStillFireMixin), as Hypixel's, and the capture build skips onPlace (`UPDATE_SKIP_ON_PLACE`), so its hoppers keep `enabled=false` and nothing schedules a tick.
Still unverified against Hypixel: what the gates look like once P3 starts and where the core door really is (the plan's
box at z 58 is solid stone in the capture, so opening it now clears nothing) - a capture taken in P3 would settle both.

## The fight

`BossFight`, one server tick at a time, every boss moving and timed as ADM measured Hypixel after its 2026-10-05/06 boss
update (bossparity-1103 brought it in line; the item-by-item comparison is killer560s-mod-logs\bossparity-1103.md).
Every boss is a no-AI wither moved by the fight (`BossMob.moveTo(pos, look)`, yaw = atan2(-dx, dz)).

- **P1 Maxor**: holds at (73, 226, 53) until t 84, then flies at the closest player +1 at min(0.9, 0.24 + 0.02 d) and
  stops within 3. Crystals at t 5 (stands at 28) are clicked to pick up (a Nether Star "Energy Crystal" in slot 8); the
  pylons open at t 80, the beacon is in at 120. A placement counts 28 ticks after it ("1/2" twice when two go in close
  together); the laser charges 28 after the second. A hit needs Maxor at y >= 225 within 3.5 of the beam on a 10-tick
  check, so he has to be lured in (the strip south of the beam, y 224 z 70-76). The stun ends on damage (5 hits, then
  6-12 ticks) or after 100-199; the second laser hit kills him 8-15 later; his floor goes at kill + 59.
- **P2 Storm**: flies his opening route at 0.4, parks at (102.375, 183, 52.375), chases from t 647. Pads read on
  t % 20 == 19 queue 5 pillar steps 4 ticks apart; a crush is Storm in the square with y + 2.975 at or over the bottom of a
  pillar that stepped in the last 60 ticks. Crush 1 pins him (median 10), crush 2 kills him (death line median 6 later).
  The drop to S1 is under the Red pillar, 24-30 ticks after Goldor's line.
- **P3 Goldor**: walks his loop round the sections (0.048, then 0.06; a 0.6 sprint to the next corner when a door opens
  with him still in its section), flies into the core (0.8) once everyone is in it; 10 hits there kill him (ADM's kill is a
  timer standing in for a party's damage); his "...." queues behind his lines; the core's floor goes 2 ticks before
  Necron's line. Death-tick and Frenzy LINES only (the sim deals no damage).
- **P4 Necron**: a fixed script, no hits: lines 0/42/84/126/188/270/332, a sidestep and two trips out at the player,
  back to mid at 98 and 240, one side platform to lava at 275, gone at 389.
- **P5** (M7): dragons 8 hits each; "[BOSS] The Wither King: ..." lines. The relics are not modeled.

Every hit goes through `SimWither.hurtServer` / `SimDragon.hurt*` -> `BossFight.onHurt`, one per target per tick.

## P3

`BossStations` (29: 17 terminals, 8 levers, 4 devices; 7/8/7/7) - stands, lines ("activated a terminal! (n/m)" plus the
same subtitle), the section flow. A stand click answers 1 tick later (the window, or "This Terminal has already been
completed!" / "...doesn't seem to be responsive at the moment." for another section's); another section's lever only
toggles; a done lever says "Someone has already activated this lever!". `BossTerminals` - real chest menus (`Menu.clicked`
never moves an item), six types, kept per terminal across reopens, at most 5 counted clicks in 10 ticks, Numbers 10 and
Melody 3 lanes in a 6-row window (Hypixel's 2026-10-06 update); the system property `killer560.bosssim.terminal=<TYPE>`
forces a type (testkit). `BossDevices` - Simon Says (4 rounds; first show 4 after the start press, lamps 8 apart, the
start button resets a running device, a wrong press stops it), Lights (20 levers, a lamp lit by its lever or a
neighbour's), Arrows (frames on one of `ArrowAlignFeature.solutions()`), Target (9 targets, lit only while someone is on
the plate (63, 126, 35), an arrow in the lit one turns it blue terracotta, 9 hits; off the plate the attempt resets).

## Tests

testkit `SimBossTests`: 1101-boss-p1p2, -p3, -p4p5, -lava, -ap3, -portal, 1102-boss-maxorcrys, 1103-boss-moves (boss
positions over time), 1103-boss-rules (refusals, levers, terminal rules, devices, death ticks), 1104-boss-capture (the room built from his capture equals it; Lights, SS, frames, still lava, gates on it) - only when named.

## Lessons

- An entity in a chunk that unloads comes back as a NEW object when the chunk reloads, so a fight that keeps entity
  references (stands, frames, bosses) loses them: S2's far terminal "was no terminal's" when clicked (1101-boss-p3,
  2026-11-01). The fight forces every arena chunk loaded while it runs (`BossFight.forceChunks`) and its entities are
  never saved (`shouldBeSaved` false), so a world closed mid-fight cannot bring back a vanilla, AI-driven wither.
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
