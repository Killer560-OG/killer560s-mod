# Sim rooms - captures, the room library and capture rotation

What the shipped room captures hold, how the library loads them, and how a capture's turn is recovered.
Part of the sim docs: [SIM.md](SIM.md).

## The room library

- No public dungeon dataset ships room GEOMETRY (checked 2026-09-28). Dungeon Rooms Mod and its kind store secret
  coordinates plus room identification, which a waypoint mod needs and a sim cannot use. DRM is also GPL-3.0
  against this mod's MIT, so its code can never be used here - data only, credited, and only with his say-so.
- **A room stored at the OLD footprint silently HIDES a good copy of the same room.** `RoomLibrary.Room.
  currentFormat()` filters it out of every count and every paste, but it still sits in the `ROOMS` map under the
  right name, so nothing else can occupy that name. Measured 2026-09-29: `Map Logger`'s
  `config/killer560/dungeons/sim/killer560smod-rooms` held 110 files, 60 usable, every usable one 1x1 (all 43
  old-footprint files were the multi-tile rooms), while `26.1.2 (Mod Only Test)` had all 135 rooms and 47 multi-tile.
  Same jar, wildly different sim, nothing on screen saying why. Fixed by shipping the 135 good rooms in the jar
  (`src/main/resources/assets/killer560smod/rooms/` + `index.txt`, 4.6 MB) and merging them in `RoomLibrary.
  mergeBundled`: a usable DISK room wins, an unusable one loses to a usable bundled one, and if neither is usable
  the disk one stays so partial progress survives. A bundled room carries `Room.fromJar` and `saveAll()` skips it,
  so the baseline is never copied into each instance's config folder. When a per-instance library and a filter
  disagree, check which rooms the filter is throwing away before trusting a count.
- Synthetic sim rooms live in `RoomLibrary`'s separate `TEST_ROOMS` map, are never saved, never counted and never
  listed as missing. A synthetic room in the real map would be written to disk by `saveAll()` and would end up in
  the shipped library looking exactly like a captured one.
- `DungeonLayout.name(room)` returns the literal `"Unknown"` for a room it has not identified yet - a placeholder,
  not a name. `RoomLibrary.capture` only rejected null/blank, so the first live scan (2026-09-28) wrote
  `Entrance.json` and `Unknown.json` identical in all 77841 block positions and reported "2 of 2 rooms complete" for
  one room seen twice; the next unidentified room would overwrite it with half of two different rooms. Check for the
  placeholder, not just for blank, and distrust a room count that has not been diffed.
- The room database (`RoomDatabase`) only loads when something calls `ensureLoading()` - the live map does so
  only inside a real dungeon. Anything that names rooms or votes on rotation outside one (Ashfall solo rooms, the
  sim) must start the load itself and must not judge rooms until `isReady()` - before it, the tile audit and the
  rotation vote judge good rooms broken.

- A room's DOORWAYS are read off the captured blocks (`RoomDoors`), not declared anywhere. A Catacombs doorway is 3
  wide and 4 high at a tile-edge midpoint, cut through the perimeter wall at `margin` blocks in. "Doorway" is not
  "air": a shut wither door is coal block and a blood door red terracotta, and checking for air alone reported Spikes,
  Staircase and Arrow Trap as sealed. Blood and Higher Blaze genuinely have none at floor height and are read as
  enterable from any side. The carve and its history: [SIM-DOORS.md](SIM-DOORS.md).
- An empty `catch` on a per-tick handler is a feature that can stop working with nothing anywhere to say so.
  `SimSecretItems` swallowed every `RuntimeException` from the server tick; it now logs once.

## A room's y band is per room, not a constant

`RoomLibrary.MIN_Y`/`MAX_Y` are the band a NEW capture may use (-64..320). They are **not** the band any given room
occupies: `toJson` trims a capture to its content, so a loaded room's band is whatever its file says, and
`Room.index` measures from `room.minY` (y-major: `(y-minY)*sizeX*sizeZ + z*sizeX + x`). Every read must go through
`Room.at(x, y, z)`, which bounds-checks and returns -1 outside the capture, and every loop over a room's volume must
run `room.minY..room.maxY`. Walking the constants asked a room holding y 60..140 for element -135,036 of an
88,209-long array and killed the integrated server on the first floor built (2026-09-29, at `RoomPlacer$PasteJob.step`
and `SimBuilder.collectChests`). `MARKER_Y` is clamped into the band for the same reason - a marker y outside it
places nothing and the uncaptured column is a hole again.

The older captures only store y 60..140 (`RoomLibrary.MIN_Y` at the time), but 29 of the 167 database chest secrets
sit below y 60, down to y 28. Those parts of those rooms were never captured, so Catwalk holds 2 chests where the
database lists 4. Not a translation fault - do not chase it as one.

## Bad captures

- An L-shaped room is captured as its 2x2 bounding box, and nine of his eleven have a NEIGHBORING room's geometry in
  the quarter they do not occupy. They are excluded from generated floors until the capture records which quadrant is
  real. Hand-drawn maps are not filtered.
- An L room's missing quarter is decided ONLY by `roomsim/RoomLShape` (room-database core of each tile's center column,
  then a wholly empty tile). "Emptiest in y 66..99" read Lava Ravine's ravine as missing and kept Market's Chains
  quarter; the solo map, `SimRoomIndex`, the paste clear and the spawn all ask it (testkit 97-sim-lshape, 2026-10-14).
- Three captures are the wrong SIZE, not the wrong rotation: Deathmite is 2 tiles where the database says 3, and
  Chambers and Raccoon match at no rotation. Re-capturing is the only fix.
- **FlatTestRoom is one tile and must declare margin 0.** A real capture is `TILE + 2 * WALL_MARGIN` across, because
  it shares a wall column with each neighbor. `FlatTestRoom` is exactly `TILE` and draws its own walls inside it, but
  it predates `Room.margin` and inherited the `WALL_MARGIN` default; `RoomPlacer` anchors at
  `origin - TILE / 2 - margin`, so the whole room was pasted one block negative on both axes. Invisible from inside
  (paste and contents agree); scenario 70 found a chest where the gold orientation marker belongs and called it a
  rotation fault. `createTestRoom` does not set a margin, so any future synthetic room must set its own.

### Captures that hold another room's blocks

**The builder was never the problem.** Reading a built floor straight out of its region file and scoring all 36 cells
against all 134 captures at all four rotations put every cell on the room the sim named, at 99.9-100% against a best
rival of 21-91%. Footprints tile the grid exactly. `RoomPlacer`, the layout and the live map are correct.

**What was wrong was the (now removed) Room Recorder's footprint clamp**, which anchored a capture on a mis-grouped
run of cells - how Waterfall's capture came to be a tile of Catwalk, then Waterfall, then nothing, then the whole of
Rare Overgrown. The shipped captures still carry those mistakes.

**Measured, so it can be re-measured:** 34 tiles across the 134 captures are block-for-block a tile of a different
room, and 12 are nothing but air. Compare tiles over y 66..99, the dungeon's own floor-to-roof band - comparing each
room over its own captured band finds only 5 of the 34, because the duplicates differ in how far below the floor they
were recorded. When two rooms share a tile the one with MORE tiles is the corrupt one: a 1x1 box cannot span a run.
`RoomTileAudit` does this at every load and makes the offenders unusable, so they are never placed.

`Criss-Cross.json` was removed. Its "97.4% solid on one side, half a room" reason was wrong (that measurement used
the wrong index order for `RoomLibrary.index` and measured y-halves; done right it is 48.3/51.7 and ordinary), but it stays removed because
`Criss-Cross` is not a name the room database knows (`Criss Cross` is), so nothing could look it up. Its stale line is
gone from the bundled index too, which was warning on every boot.

**`SimBuildAudit` is the only check in the build that reads the world.** The door audit, the secret audit and the map
all compare the build's inputs against each other, so a paste that wrote the wrong blocks passed all three in
silence. The audit samples each room's footprint after the last block lands and compares it with the capture. A
healthy room does not score 100% (doorways carved, unused ones bricked up, secrets written in), and two captures of
*different* rooms still agree about 90% because rooms share walls and floors, so the threshold is 70%: a smoke
alarm, not a ruler.

## Measuring a captured room's floor is harder than it looks

Three methods each produced a confident list of rooms to rescan that was really the probe's own mistakes (all three
are in scenario 92's method comment). Scanning DOWN from the top finds the roof: a room is captured up to y140, so its
ceiling has open air above it - that version reported forty rooms at exactly y100, roof height. Scanning UP from the
bottom finds the deepest shaft (Mines at y38, Lava Ravine at y20). The mode of every standable surface still lets the
roof win, because a roof is a clean 31 by 31 slab while a floor is broken up by furniture. What works is requiring a
ceiling somewhere above the surface before counting it.

## /mage: re-capturing one room from a real dungeon

`roomsim/RoomCapture` (dev builds only) restores the Room Recorder's read-and-save half for ONE room, from tag
`room-recorder-last`: the footprint from the live map's tiles (refused unless they fit the room database's shape - the
recorder's worst damage came from a mis-grouped box), every chunk of it loaded (asked of the chunk source -
`ClientLevel.hasChunk` is always true), every block of every y of the level in WORLD orientation (as every shipped
capture was; `RoomCaptureRotation` finds the turn), y taken back by `SimAltitude` in the sim. Then the check the
recorder never had: each tile's center column must hash to the room in the room database (`RoomDatabase.coreOfColumn`,
as `RoomLShape` uses) and there must be a doorway at y 69..72. The shipped Mage fails it on tile 1,0 ("Altar"). A good
capture goes to the user library through `RoomLibrary.writeCapture` (the file it replaces kept as `.bak-<stamp>`), is
read back block for block, and the library reloads; a usable disk room wins over the jar's. Only `/mage` is exposed;
`captureCurrent(client, name)` takes any room. Testkit 1490 builds Mage in the sim, checks every refusal, swaps the
stitched tile's center column for the good one's so the room validates, and compares the decoded file with the
server's world: 602,745 blocks, 0 differ.

## A capture carries its own rotation, and it is not the database's

Secret coordinates in the room database are relative to the room's CANONICAL orientation; a capture is taken from
whatever instance he walked through, so each one holds an arbitrary quarter turn. The sim handed `toRealCoord` the
rotation the room was PASTED at alone, so every non-canonical room put its secrets in the wrong corner - measured
2026-09-29, only 34 of 135 captures are canonical and 88 of the 122 identifiable ones were wrong. Invisible in square
rooms, because a wrong corner is still inside the room. `RoomCaptureRotation` recovers the turn (below). The paste
rotation still decides the FOOTPRINT; only the sum decides the corner and the translation, so `SimSecrets.clayCorner`
takes both separately.

`SimSecrets`' clay corner is ROTATION-DEPENDENT: NW at 0, NE at 90, SE at 180, SW at 270. It used NW always, which was
invisible while the generator refused to rotate rooms and threw every secret out of the room the moment it did. It is
the corner of the TILE area, not of the captured window - the wall margin is deliberately not subtracted.

**Shape strings and capture footprints are in different frames.** Every long room in `rooms-modern.json` is written
`1xN`, and its secret coordinates run along **x** - so `1x4` means four tiles along x, not one. Of his 27 non-square
captures, 26 also run long along x and one (Gold) does not. Comparing the shape STRING with the capture's
`sizeX`/`sizeZ` will tell you 26 rooms are turned when they are not; compare the secret coordinates' extent instead,
which is convention-free.

### Secret rotation: the translation is right, the DATA is what is missing

Scenario 82 measures how many of the database's chest secrets land on a chest the capture already had (55-61%
against a 70% bar). Every room with database chest coordinates and chests in its capture was tried at all four
rotations across dx/dz/dy offsets of -2..+2, and **the winning transform was dx=0 dz=0 dy=0 in 78 of 79 rooms**. The
coordinate translation has no constant error; the only free variable is the rotation.

Where it fails is a DATA mismatch. Seven rooms - Purple Flags, Quartz Knight, Redstone Warrior, Spider, Supertall,
Waterfall, Withermancer - land zero secrets at every rotation AND every offset, and Red Blue's capture has no chest at
all. Those Ashfall-preset rooms do not hold the chests the database describes. Scenario 82's figure mixes "is the
rotation right" with "does this capture contain the chest at all", and cannot reach 70% on this library however
correct the code is. Fix the instrument before touching the bar: assert the chosen rotation against the
best-scoring rotation per room, and skip the rooms whose captures hold no matching chest.

### The roof marker is evidence, not an answer

116 of 135 captures resolve from the roof marker, because in these rooms blue terracotta is often the ROOF MATERIAL
rather than a single corner marker - Mossy has 76 of them, Pit 105, Cathedral 544. `roofLine` looks for the highest y
holding the MARKER rather than the highest non-air block in a corner column (a taller capture put terrain above the
room, so the old rule looked a hundred blocks above the marker - Redstone Warrior). `narrowBySecrets` falls back to SCORING
the rotations and taking a clear winner instead of requiring every secret to land, which settles Catwalk, Pedestal and
Slime (with the marker fix, 120 of 135 resolved, 2026-09-30). Balcony and Archway are the only
two rooms whose captures have no roof marker, which is the code's own sign of a missing roof corner. (An old handoff
said `SimBuilder` warns when a 1x2's reserved cells disagree with its long axis; no such warning exists.)

`RoomCaptureRotation` once trusted the marker outright whenever exactly one corner carried blue terracotta, and named
the wrong corner for Waterfall, Skull and Purple Flags. All three are long rooms, and a quarter turn on a long room
swaps its long axis, so the secrets were rotated across the short side and fell out of the room - Waterfall lost seven
of its eight. The database's secret coordinates bound the room's canonical size, so a turn that cannot fit them inside
the capture is provably wrong whatever block sits in that corner; that shape veto runs before the marker is believed.
Square rooms admit all four turns and are untouched.

### Capture rotation by vote, and how Hypixel does it

killer560: "make there be a way to tell for rotation struggling rooms. There is some way it is done with secret
waypoints so figure it out." **There is not, on Hypixel.** The live map's only mechanism is the roof marker:
`LiveMapFeature.findRoomRotation` -> `RoomDatabase.findRotationAndCorner` (blue terracotta at one of the four roof
corners). No secret matching, no doors, no per-room table. A room whose marker is not read yet logs "No
blue-terracotta corner marker ..." ONCE and is then retried every second (`rotationRetryAtMs`) until the roof chunk and
`getHighestY` read right; until then it is left out of `identifiedRoomsWithRotation`, so its waypoints simply do not
draw yet. The log line is the first failed attempt, not the outcome. The one exception is Fairy, fixed at rotation 0 by
its room TYPE. A real Hypixel room always has its marker; a capture does not, which is why the capture side needs more
than one source.

`RoomCaptureRotation` votes, and the answer feeds the same cache (`of`) that `SimRoomIndex`, `SimSecrets` and
`SimBuilder` read, so the paste, the published rotation, `PuzzleCoords` and the waypoints cannot disagree. Over the
rotations the database's secrets can FIT (the shape veto above):

- roof marker: 2 votes for a single marked corner, 1 each for two;
- lapis corner: 1 vote. Roof corners are redstone blocks, and a lapis block sits DIAGONALLY OPPOSITE the blue
  terracotta - in all 85 captures that have both (measured by `tools/layoutsim/rotation.sh`). So it names the marker's
  corner when the marker itself was not captured. An observation about the captures, not a Hypixel rule;
- one vote per database secret that lands: chest secrets on a chest or trapped chest, wither essence AND redstone key
  secrets on a player head. **Redstone key positions are player heads, not levers** - the old tie-break looked for a
  lever there and never matched (Golden Oasis, Redstone Crypt, Redstone Key all show the head). Bats and items are
  skipped; neither is a block.

Outright winner or 0. INFO once per room per library load (lazily, when the room is first placed) with every score;
WARN when the marker and the secrets disagree, and when the answer is uncertain. `RoomCaptureRotation.isUncertain(name)`
/ `verdict(name).reason()` are public for Auto Routes to warn before recording: uncertain means no outright winner,
fewer than two votes, a source preferring another rotation, or the marker/secrets pointing only at a rotation the shape
forbids. Sim only - on Hypixel the rotation is read live; nothing in `livemap`, `secretwaypoints` or `roomdatabase` uses
this class.

Result over the 134 shipped captures (`tools/layoutsim/rotation.sh -Droomdata=...rooms-modern.json`, which runs the
real class and `RoomTileAudit`): every usable capture certain except four, and only ONE usable room changed answer.

| Room | Marker | Lapis | Secrets 0/90/180/270 | Chosen | Verdict |
|---|---|---|---|---|---|
| Mage | none | 180 | 0/0/1/0 (wither on the capture's only player head) | **180** (was 0) | certain |
| Fairy | none | all four | no secrets | 0 | certain, same rule as the live map |
| Archway | none | 270 (shape forbids) | 0/0/0/0; 1 lands at 90 moved a tile | 0 | uncertain |
| Balcony | none | none | 0/0/0/0; both land at 180 moved a tile west | 0 | uncertain |
| Catwalk | 90 + 270 (shape forbids both) | none | 0/0/0/2 (shape forbids) | 0 | uncertain |
| Purple Flags | 270 (shape forbids) | none | 0/0/0/1 (shape forbids) | 0 | uncertain |

**The four uncertain rooms are not a rotation problem, they are bad captures**, the same fault `RoomTileAudit` already
refuses in 28 others (Skull, Bridges, Pedestal, Slime, Gravel, Doors, Wizard, Waterfall show the identical signature
and ARE refused). Catwalk and Purple Flags: the marker and the tile-0 secrets agree on a quarter turn that a
long-along-x box cannot have, and every secret beyond tile 0 is out of the box - the room ran along z and the box was
laid along x, so tiles 1+ are a neighbor. Archway and Balcony: nothing lands until the room is moved one tile, so the
box is anchored a tile off. No rotation is right for any of them; a route recorded there cannot match Hypixel until
each is walked again. The audit only misses them because their neighbors' tiles are not captured anywhere else.

## Verified against the captures (puzzle rooms)

Every claim these puzzles make about their room was re-decoded rather than taken from the comment above it:

| Room | Claim | Measured |
|---|---|---|
| Quiz | three answer pillars ringed by the room's buttons | 3/3 smooth_stone with air above at rotation 180, 12 of 12 wall buttons accounted for |
| Water Board | 7 levers, 27-piston back wall, 5 wool columns | 7/7 levers at 270; 27 pistons at z=28, 13 extended; wool at `(15,55,z)` with air at 56; the terracotta identifier marker present |
| Creeper Beams | 22 lanterns in 11 pairs | 22 distinct over 13 entries (two are duplicates), 9 sea + 13 prismarine, plus 13 further sea lanterns in no pair |
| Teleport Maze | 30 pads, sealed chambers | 30 end_portal_frames, 240 iron bars, solid wall at `(12,69,13)` between the start pad and chamber one |
| Tic Tac Toe | 8 of 9 buttons, the ninth already played | 8/9 at rotation 180 - the gap is **row 2, col 2**, the bottom right, exactly as reported |
| Ice Path | 289 ice cells, one stray wall in the capture | 289/289 ice; 17 non-air at the wall layer against the corrected 16, the extra at `(15,16)` |
| Ice Fill | the bundled path is feet positions (see "Ice Fill's path is feet positions" in [SIM-PUZZLES.md](SIM-PUZZLES.md)) | at `dy = 0` all 45 identified tiles are air with ice under them, and the identifier pairs match all three floors |
| Three Weirdos | three chests moved to line the middle one up with the cauldron | chests at local `(25,69,12)/(26,69,14)/(25,69,17)`, cauldron at `(29,69,16)`; all three new spots have solid floor under them and three blocks of air over them |
| Mines / Pressure Plates | one lever opens a whole wooden door | lever exactly at the recorded spot in both; Mines' region is 21 cells and holds 21 dark-oak/iron-bar blocks (y 78..84 is the whole door - 74..75 is a separate grate below the floor), Pressure Plates' is 30 cells and all 30 are oak |
