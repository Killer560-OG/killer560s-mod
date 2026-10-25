# Sim doorways, carves, seams and wither doors

How the sim finds, carves and seals the doors between pasted rooms, and what was wrong with them.
Part of the sim docs: [SIM.md](SIM.md).

## Doorways are at one height, and the openings above it are not doors (2026-10-15, sim-doorheight-1015)

Every perimeter doorway in the shipped library (277 in 135 captures; 276 in his Mod Only Test library) measures its 3x4
window at exactly y69..72, never 68 or 70, with wall on both sides and a lintel over it - Hypixel's fixed door height on
the 32-block grid. `RoomDoors` now takes only the window at `DOOR_FLOOR_Y` (69) with at least 8 of its 11 frame blocks
wall, and `SimDoors` carves and seals at that floor instead of searching the seam column. FloorBench mirrors it. The old
search (y64 to y86) also took openings that are not doors:
- **Entrance W0** (y76 up): the top of the back staircase, open to the sky (it was dropped by an Entrance-only "lowest
  wins" rule, now gone).
- **Mage N1** (y72..87, 17 wide, no wall): the old Mage capture was two rooms stitched - its second tile's center column
  hashes to ALTAR's core, with an air sheet at x=32 between walls at x=31/33. `RoomTileAudit` compares tiles block for
  block, not by core, so it did not flag it. Re-captured with /mage (see [SIM-ROOMS.md](SIM-ROOMS.md)).
- **Quad Lava E0** (y77..80): a 5x4 red terracotta panel in a three-thick east wall; red terracotta is in the doorway set
  as a shut blood door.
- **Twenty open edges of L rooms' empty quarters** (two each in Altar, Chambers, Dino Site, Lava Ravine, Layers, Melon,
  Pirate, Spider, Well, Withermancer). Generated floors leave L rooms out; only a drawn or pinned L could join there.

Before the fix, testkit 1446 (three runs) joined a neighbor through Mage N1 on 21-25 of 30 pinned F7s and through Quad
Lava E0 on 20-23, cutting 25 and 23 solid capture blocks (tunnels into stone or through Hypixel's solid wall); after, 0 of
30 each, every door of both rooms walkable with 0 blocks carved. `tools/layoutsim -Dsweep=true -Dseed=1
-Dnorecency=true` (2,240 floors): 0 failures before and after; fill-pass warnings 267 -> 339, unchecked carves 7 -> 5.
Test lesson: a "can walk on" probe that allows drops must require the shaft above the landing to be clear, or it drops
through solid stone into a cave and calls a dead end walkable.

## Where a doorway's floor is, and three wrong answers about it (2026-09-30, history)

Before the one-height rule, `SimDoors.findFloor` searched for where the 3x4x7 carve starts and was wrong three times,
each giving a doorway nobody could walk through. Kept because any future floor search falls into the same traps:
1. **Upward from the bottom of the world** finds a basement floor, so the carve opened a doorway down there. It only
   worked while captures stopped at y60; scenario 81 then found 2 of 8 doorways impassable.
2. **Downward from y90** stops on a room's internal mezzanine.
3. **Downward from y75** lands on the top of the door's own LINTEL (the seam above a door is usually open to the roof),
   cutting an opening five blocks up. Scenario 81 prints the carve volume layer by layer for every doorway it cannot
   walk: `-1=0 0=13 1=13 2=14 3=14 4=5 5=21 6=21 7=21 8=21` - the 21s were the carve.

The fixed search ran from `DOORWAY_SEARCH_TOP` **72** (the top block of an opening whose floor is y69) down to 58, and
stepped past a perimeter chest at doorway height (Pedestal y70 on its z=0 edge, Archway y70), which had left the chest in
the opening. Impassable doorways went from 2, 1, 1 of 8 to 1, 1, 0 of 8. Superseded by the section above.

## Open (2026-09-30): impassable doorways clustered on rooms whose doorways cannot be MEASURED

Scenario 81's remaining stuck cells (four runs, then three more after the lintel fix: 1, 1 and 0 of 8) were always on
**Blood** or **Supertall** (paired with Atlas, Leaves, Lower Blaze, Mines; once Pedestal/Logs, blocked by stone brick AND
a chest). `RoomDoors.of` cannot measure Blood or Higher Blaze (solid stone perimeter at every height) and INVENTS a
doorway at `edge(NORTH, 0)`, relying on the carve to open it. Supertall's rotation cannot be read from the roof marker and
its band runs y0..254. Likely cause: the layout puts a door on a side of Blood with no opening and the 3x4x7 carve must
tunnel through its solid perimeter ("moved 2.2 across, blocked by stone_bricks"). Worth checking: does carve depth 7
reach through Blood's wall from the connector center, and should the invented doorway go on the side the layout wants
rather than always NORTH. The chest variant may remain for a chest off the connector's center column. (Not re-measured
since the one-height rule.)

## A doorway can be completely clear and still impossible to cross

`carveDoorway` removes the air above a floor; it never lays one, so where two rooms meet at different heights the
connector had no floor and the player fell in, while every report said "nothing solid across the seam". Measured:
- **Missing capture data**: ruled out; scenario 92 counts unread columns and all 135 rooms were fully read.
  `RoomLibrary.complete()` could not have ruled it out - it is completeness >= 0.999, and on a three-tile room (9409
  columns) nine unread columns, the exact size of the hole, still pass.
- **The carve punching through a wall** where a room has no doorway: ruled out. `SimBuilder` audits every carved door
  against both rooms' rotated masks at build time ("all 21 doors are backed by a measured doorway in both rooms" on a run
  that still had an impassable one). The audit stays.
- **Rooms meeting at different heights**: the cause. The carve now lays stone bricks at `floorY - 1` across the 3x7 only
  where that is air (9 to 79 blocks a floor), so a room's own floor is never overwritten.

Room-wide capture heights do not explain it (Crypt, Mines, Balcony, Archway pass scenario 92): a room can sit at y69 over
most of its area and be two blocks low in the corner a door lands in, which a modal measurement cannot see.

## Three different bugs were all reporting as "one doorway in eight is impassable"

Each looked identical from the verdict line; only the numbers told them apart.
- **The report named the wrong block.** `seamBlocks` scanned the seam +/-2 while a doorway is three wide, so every
  "blocked by ..." named the door FRAME ("blocked by red_carpet" was a carpet in the frame).
- **The player was placed in mid-air.** The walk started a fixed four blocks back on a fixed side, open space outside an
  Entrance at the map edge ("feet=air head=air under=air"). The scenario now finds a standable spot on either side and
  says so when there is none.
- **The crossing was measured along +X/+Z rather than along travel**: a clean crossing from the positive side read
  "moved -16.5 across" and failed.
- Not a bug: an opening one block above the approach. Walking cannot climb a full block (in game he hops), so the walk
  holds jump too; a two-block step and a real wall still fail.

## The sim never builds a wither door; theoretical wither doors (2026-10-04)

Generated doors come out of `plan()` as NORMAL, BLOOD or ENTRANCE only. Real wither doors are not a drawing change:
`SimMobs` fires the key drop once, when the LAST starred mob on the floor dies, so one key exists per floor and the blood
door needs it; wither doors on the blood path would be unfinishable until keys drop per room.
`SimWitherDoors` marks every ordinary door on the room path from Entrance to Blood (the wiki: "The path to the Blood Room
is guarded by a series of Wither Doors") when the floor is published. Drawn on the sim's live map in the wither color with
a SINGLE amber outline (a real locked one has two) and in the Map Designer like a wither door. `isTheoretical` is false
unless `SimState.isActive()` and the table is only filled by `publishSimFloor`, so a Hypixel map never shows one. They
stay ordinary doors to every pathfinder.

## The seam between rooms was a way out of the map (2026-10-12, sim-seal)

killer560 (Bridges, F7): "doors will generate with ground beneath you to walk on but not blocking you from going outside
of the map." A capture's outermost column is the seam it shares with the next cell, held as AIR at walking height
(Bridges: every margin column open at y 69-71). Pasted side by side, two rooms leave a one-wide slot open to the sky with
nothing under it; a carved door's floor lays three blocks across it and the slot runs to the floor's edge or out of the
bottom of the world. `SimBuilder.sealOuterEdges` fills every open seam block on tile edges that face no room and between
two different rooms (never inside one room), between the lowest and highest block of the room column(s) beside it, in the
wall's own material (else stone bricks): about 100,000 blocks a floor, ~1.5 s at the end of the build.
- **The door columns were the "small gap" left** (Redstone Warrior: "the doors are better but still a small gap"): the
  fill skipped a door's three seam columns at every height, leaving 2,400-3,500 open seam blocks a floor above and below
  the opening. `SimDoors.carveDoorway` now returns the floor it carved from and the fill walls those columns too, all but
  the opening (floor .. floor + 3).
- Testkit 855 walks every standable, roofed spot of three generated floors (step, one-block jump, any drop) and counts
  ways out of the rooms: 3 / 2 / 7 on the old jar, 0 after; it also scans every seam block by kind and requires none open.

## The fill pass's carve: least-cost walls, never a one-door room, never blind (2026-10-15/16)

A cell no strict room fits gets a 1x1 whose doorway faces a neighbor, carved through the neighbor's wall where
`RoomDoors.carvable` says the cut lands on floor. That is by design; three rules now bound it:
- **Least cost.** The carve took the FIRST walkable wall in cell order and about four in five cut more than a one-thick
  wall (`tools/layoutsim -Dsweep=true -Dbounded=true -Dnorecency=true -Dseed=1 -Doffdoor=true`, 2,240 floors: 248 thin,
  1,278 tunnels, 83 blind, 14,302 solid blocks cut). `RoomDoors.carveCost` counts, per walkable edge, the solid capture
  blocks in the two columns behind the wall plane that the 3-deep cut reaches (lower three rows, 0..18; 341 of the
  library's 557 walkable cuts read 0, 121 read 18, Quad Lava E0 14), and `carveOnce` takes the least over the whole floor
  (the Entrance only when nothing else fits). After: 339 thin, 1,179 tunnels, 77 blind, 12,126 blocks. Refusing thick
  walls outright (at most 2 blocks behind) sent 882 carves to BLIND instead of 153 - worse; leaving the cell empty breaks
  "every floor fills its real rectangle". Testkit 1446's TUNNEL phase sums the cut over 150 seeded F7s.
- **Never a one-door room** (killer560: "Old Trap does not have a door off of the back of it, only the one entrance").
  The carve skipped only Blood and puzzles, so Old Trap's back wall, New Trap's, the Entrance's and the rare rooms' were
  targets: 180 of 2,240 floors had one with two doors. `SimFloorLayout.isDeadEnd` (trap, PUZZLE, BLOOD, ENTRANCE,
  CHAMPION, RARE) is never a carve target now: 0 floors; dropped pins with a random NORMAL room pinned 153 -> 185 of 2,240
  (partly noise).
- **Never blind** (fullclear-1016 run 017: the last resort "NOT a walkable cut - nothing else fitted" left Cell behind a
  wall). `carveOnce(false)` refuses (`SimFloorLayout.blindCarvesRefused()` counts it) and the whole-floor retries find
  another layout; `tools/layoutsim -Dsweep=true` fails any floor with a BLIND door (4,480 floors: 9 before, 0 after).

**What Hypixel has, from his own runs.** 50 real F7 `RunRecord` maps from his Dungeons run history (no rotations stored),
compared rotation-free with the measured masks (`tools/layoutsim/hyp_doors.py` over `run.sh -Ddoorstats=true`): every
Entrance, Blood, Old/New Trap, puzzle and champion room had exactly ONE door on every map (Entrance 50/50, Blood 50/50,
Old Trap 17, New Trap 32, puzzles 9-20 each, champions 8-20). 52 ordinary rooms had more real doors than their capture
measures (Cathedral 2 measured / up to 7 real, Pipes 2/7, Pit 3/7, Pedestal 1/5, Balcony 1/4, Catwalk 1/4, Red Blue 1/4,
Knight 2/4, Fairy 2/4, ...): a capture records only the doorways open on its run and Hypixel walls up the rest. So for an
ordinary room the measured mask is a lower bound and a carve is a door Hypixel can have; for the one-door types it is
exact and a carve is always wrong. Rare rooms never appeared in the 50 runs; they are one-door because every capture of
them measures one.

**1446 after the /mage re-capture** (main 4a2b306d, "Mage N1 ... 4 of 30", "Quad Lava E0 ... 1 of 30"): neither was
detection. The new Mage is 33x65 (two tiles on Z), so it has no N1, and the scenario's edge, turned by
`RoomDoors.doorCells`, pointed at the cell EAST of Mage and counted doors between two other rooms; the scenario now
refuses an edge off the room's tile grid. Quad Lava E0 was the fill carve (pinned, his library, 840 F7s: joined on 5
floors before least-cost, 2 after).

## Doorway trim, braziers and Hypixel's door connector piece

**Cold braziers** (sim-deuces-1015; testkit 1433 found Deuces block for block equal to `Dueces.json`, solo and on a
full floor: x 1..8, 0 of 1,976 differ). A brazier is a
hopper with fire on it; 1,099 of the library's 1,471 hoppers have air on top (372 fire), same placement signature either
way. Fire is the block a player punches out, and RoomDatabase leaves it out of its core hash, so a capture keeps whatever
was still lit. `RoomBraziers` relights every hopper with air above it at library load (never in the wall plane or seam),
so paste and build audit agree. If he ever sees an unlit hopper on Hypixel, this rule is the thing to narrow.

**The carve cut each room's top trim.** `carveDoorway` clears 3 wide x 4 high x 3 deep into each room; inside a room
whose doorway is already open it only took the top layer one in from the wall (stairs pairs, iron-bar rows, slabs - 157
blocks over 65 doorways in 31 rooms). A top-layer block over air outside the seam is now kept (`SimDoors.KEPT_TRIM`,
38-47 a floor); 80/81/855 still pass.

**Hypixel's door piece follows the world, not the room** (sim-deuces-door-1016). The 5 wide x 3 high box (y 74..76) over
the opening and trim row, in the wall plane and one in, holds one of exactly two fillings in 207 doorways of the shipped
captures (`pieces.py` in `killer560s-mod-logs/sim-deuces-door-1016/`): every EAST/SOUTH one (62 + 44) cyan terracotta
then air, a recess; every WEST/NORTH one (55 + 46) stone then the same fifteen blocks (stone bricks, one stone, one mossy,
four upside-down stairs facing the wall; north is west turned), flush. Zero exceptions, in rooms captured at every turn.
The paste carried the capture's half round with the room (1433 on main fcba4045: 24, 19 and 10 wrong doorways on three
F7s). `RoomDoorPieces` swaps the box over each standard-filled doorway to the one for the side it faces once turned,
inside `PasteJob` (capture-frame states). Rotation 0 is unchanged. Of the library's 278 measured doorways (`framed.py`), 70
hold neither filling (Altar, Spider, Mossy, Supertall, Withermancer...) and paste as captured. Open doubt: his one real
north-facing Deuces shot (below) had no fill, against 101 captured west/north doorways that all do - so either the piece
is not decided by world side alone or Hypixel changed it. An F3 shot of another room's north or west doorway from inside
would settle it.

## Deuces' chest doorway, recreated exactly (2026-10-18, deuces-exact-1018)

History: the first "reference" picture was the sim's own (`killer560s-mod-logs/deuces-doorway.png` was 1433's screenshot,
named like a reference; 1433 now writes to `killer560s-mod-logs/sim-deuces-door-1016/`). His real Hypixel shot
(`killer560s-mod-logs/deuces-door-2/hypixel-2026-10-16_22.21.06.png`, entering from Lava Ravine, log "Dueces - WEST",
chest doorway into Fairy) showed capture W0 facing NORTH (Deuces at 90) with cobblestone jambs, a stone-brick lintel and
no fill - a shape no captured doorway of the 298 has (`k1query.py`), so no rule could be derived (testkit 1434
renders from his camera in `deuces-door-2/renders/`; variant `box2air` clears the fill).
killer560: "that door under the chest always looks like that regardless of direction for Deuces but it is for it
specifically. Just recreate it exactly". So `DeucesChestDoorway` holds it as a table of capture-frame edits (`SPEC`, the
same `x y z block` lines testkit 1434's `spec=` variants read), pasted at every turn, solo and on floors, through the same
per-block override map as `RoomDoorPieces` (which skips Deuces W0). Matched to his shot (`killer560s-mod-logs/deuces-exact/`,
`final-side-by-side.png`). In capture terms (wall plane x 1, one in x 2, opening z 15..17):
- **Three high, not four** - the only such doorway in the library. Cobblestone WALL posts at x 2, z 14 and 18, y 69..71;
  a bottom stone-brick slab across x 2, y 72, z 14..18; the wall plane's opening closed at x 1, y 72.
- **One stone-brick step** on the slab (x 2, y 73, z 14..18); x 2, y 74..76 air in front of three rows of the wall plane's
  own stone - the tall "flat stone face".
- **Cobblestone along y 76** at z 13, 14, 18, 19 from x 2 to x 5 (the box's top corners, the stone-brick stairs at x 3 and
  the magenta wool beside them; magenta wool is in only Museum, Deuces and Purple Flags).
- `SimDoors.carveDoorway` skips every block of the table (its 4-high cut would take the slab and the x 1 row; kept-trim
  never applies). Positions come from the paste (`DeucesChestDoorway.pastedAt`), cleared when a floor build starts. The
  solo diamond seal is unchanged (it fills the opening's air, x 0..1).
- Matching his camera: the testkit's camera flies and flying multiplies FOV by 1.1, so his FOV 110 is
  `-PdeucesFovs=100` in 1434. Feet (9.25, 69, 16.5) pitch -25 and (8.75, 69, 16.5) pitch -28 bracket his view; compare
  structure (band order, relative heights), not pixel positions. What settled the height: the flat stone was about twice
  its height over a 4-high doorway while slab, step and flat stone kept their size together.
- Tests: 1435-sim-deuces-exact (solo and floor at 0/90/180/270: all 57 table blocks equal the world, 44 of them not the
  capture's; the door-piece sweep with Deuces W0 exempt finds no wrong doorway; the floor-0 passage into Altar is open).
  1433's chest-doorway check is now the table check, and its capture comparison skips the table's blocks.

## A room is judged by its database TYPE, not its name (fullclear-1016 run 047)

Mimic eligibility matched names against "puzzle"/"trap", which Tic Tac Toe's does not contain, so its reward chest bit.
`SimMimic.roomEligible` reads `RoomDatabase` type too, and `SimSecrets` offers a placed chest to the picker only for an
eligible room (86b6b2ce). The same rule is why `SimFloorLayout.isDeadEnd` goes by type.
