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
middle; gates as iron blocks; the core with a gold door. No capture exists yet (2026-11-01): ask him to run
`/simbosscapture` once at the start of an F7/M7 P1. Over a capture the plan still re-lays the blocks a fight changes
(holes, gates, levers, device walls) at the plan's positions - check those against the capture once one exists.

## The fight

`BossFight`, one server tick at a time. Lines and gaps: Maxor 0/42/84, crystals at 40; Storm 0/62/124/186/424/486,
lightning 548, taunts from 847; Goldor's line 62 after Storm's death ("At least my son..." at 42), Goldor's queue 62
apart; gate 100 ticks after its section ends ("The gate will open in 5 seconds!"); Necron 62 after Goldor's death,
intro 42 apart. Simplified for one player: Maxor stuns by the laser after both crystals, 5 hits a stun, 2 stuns; Storm
hovers pillar to pillar and a pad press under him crushes, 2 crushes; Goldor 10 hits in the core; Necron 15 (ARGH at
half); dragons 8 each. Every hit goes through `SimWither.hurtServer` / `SimDragon.hurt*` -> `BossFight.onHurt`, one per
target per tick. The relics are not modeled: "[BOSS] Wither King: You... again?" comes as he lands in P5.

## P3

`BossStations` (29: 17 terminals, 8 levers, 4 devices; 7/8/7/7) - stands, lines ("activated a terminal! (n/m)" plus the
same subtitle), the section flow. `BossTerminals` - real chest menus (`Menu.clicked` never moves an item), six types,
Numbers 10 and Melody 3 lanes (Hypixel's 2026-10-06 update); the system property `killer560.bosssim.terminal=<TYPE>`
forces a type (testkit). `BossDevices` - Simon Says (4 rounds, testkit HxSimonSays' model), Lights (all six levers on),
Arrows (frames on one of `ArrowAlignFeature.solutions()`), Target (lit emerald, an arrow in its box turns it blue
terracotta, 9 hits).

## Tests

testkit `SimBossTests`: 1101-boss-p1p2, -p3, -p4p5, -lava, -ap3 (only when named).

## Lessons

- An entity in a chunk that unloads comes back as a NEW object when the chunk reloads, so a fight that keeps entity
  references (stands, frames, bosses) loses them: S2's far terminal "was no terminal's" when clicked (1101-boss-p3,
  2026-11-01). The fight forces every arena chunk loaded while it runs (`BossFight.forceChunks`) and its entities are
  never saved (`shouldBeSaved` false), so a world closed mid-fight cannot bring back a vanilla, AI-driven wither.
- A floor hole must go through the bedrock bed under P3's and P4's lava too, or he lands on it one block down
  (1101-boss-p4p5 read y 106.0 and 62.0).
- The station table's lever stand is at lever y + 0.688; `y - 0.688` in floating point floors to the block BELOW
  (127.99999...). Round it.
