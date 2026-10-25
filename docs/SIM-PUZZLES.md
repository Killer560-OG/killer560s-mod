# Sim puzzles

The sim's puzzle rooms (`roomsim/Sim*Puzzle`) and what the puzzle SOLVERS need from them. The autos that play them are in
[SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md); Auto Puzzles behavior on Hypixel is in [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md).
Part of the sim docs: [SIM.md](SIM.md).

## Rules for every sim puzzle

- **Every solver was pointing at Hypixel's height.** `RoomDatabase.toRealCoord` passes y straight through, because on
  Hypixel a room-relative y IS a world y; the sim shifts the floor (`SimAltitude`), so the same call lands tens of blocks
  off. `PuzzleCoords.real/relative` add and subtract `DungeonLayout.simYOffset()` (zero outside the sim). The four private
  `realPos` copies in Quiz, Water, Beams and Ice Fill delegate to it (three of the four lacked the shift), and
  `WeirdosSolverFeature`'s hardcoded `69` too. `PuzzleCoords` has a `RoomEntry.Pos` overload, so nothing reaches past it.
- **Hypixel heights hardcoded where the sim moves the floor**: five autos and solvers compared bare Hypixel heights;
  the table is in [SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md).
- **Puzzle rooms have exactly one door** ("quiz having two doors": the fill pass had carved Redstone Crypt through Quiz).
  Every route that links rooms asks `SimFloorLayout.isOneDoor`: growth sends a puzzle's other doorways to the bricked-up
  list, the loop pass skips puzzles, the fill pass never meets or carves into one and a puzzle it places takes one door, a
  woken pinned puzzle loses its stubs, `doorLinks` joins puzzles last. A cell whose only neighbors are puzzles is filled by
  swapping the puzzle for an ordinary room (after the carve) and the puzzle is owed again. `tools/layoutsim -Dsweep=true`
  judges "puzzle with != 1 door": 22,400 floors went from **15.41%** to **0**; puzzle count right on 99.96%, room minimum
  missed 0.19% (puzzles can no longer be hubs). 95-97% of traps are one-door dead ends, untouched.
- **The one line that broke every solver in the sim.** Solvers ask `LiveMapFeature.currentRoomClayAndRotation()`, and
  `SimBuilder` published the PASTE rotation beside a clay corner
  computed for the DATABASE rotation (`clayByRoom[nameIndex] = new int[]{clay[0], clay[1], rot}`); they differ by the
  capture's own turn (`RoomCaptureRotation`) in 88 of 122 rooms, so every relative coordinate came out spun about the
  corner. Capture turns: Water Board 270, Creeper Beams 0, Tic Tac Toe 180, Boulder 270, Quiz 180, Three Weirdos 270,
  Ice Fill 270, Ice Path 180, Teleport Maze 0, Higher Blaze 270, Lower Blaze 90 - nine of eleven wrong; all match
  `SimRoomPuzzles.bestAnchor`. `SimRoomIndex.add` had always been right, which is why Secret Waypoints landed while the
  solvers did not. Follow-ons: when `bestAnchor` scores better at another rotation `SimRoomIndex.correct` re-records the
  room and the publish (after `armFloor`) follows; a per-room vertical nudge (`SimRoomPuzzles.dyFor`, applied in
  `PuzzleCoords`) exists but no shipped room needs one (the Ice Fill reason for it was wrong, see below).
- **A solver needs two things: geometry at the right height, and CHAT in Hypixel's own shape.** `QuizSolverFeature` wants
  the question line then lines starting `ⓐ`/`ⓑ`/`ⓒ`; `WeirdosSolverFeature` wants `^\[NPC] <name>: <line>` plus an
  ArmorStand of that name. A `[Sim] ...` prefix breaks both, so the sim sends them raw through `sendSystemMessage` (they
  still reach `ChatObserver`). Read the solver's SCAN as well as its coordinates (examples below: Blaze, Tic Tac Toe,
  Creeper Beams, Teleport Maze).
- **Never send a chat line that refers to an entity in the same tick as queuing that entity's spawn** - the solver reads
  the line first, logs "No ArmorStand named ...", and gives up.
- **A button has to be allowed to be a button.** `InteractionResult.SUCCESS` from a `UseBlockCallback` cancels the
  interaction: right for a chest (no empty screen), wrong for a button - Quiz's twelve and Tic Tac Toe's nine never
  depressed. Both return PASS on a `ButtonBlock`, the observe-never-consume rule `SimWaterPuzzle`'s levers and
  `SimBoulderPuzzle`'s buttons already follow.
- **A failed sim puzzle can turn its room red.** `DungeonMapScanner.STATE_FAILED` comes from the map ITEM (center byte 18,
  side 66); a sim has none, so `SimRoomState` says so directly, keyed by room NAME (what `MapPainter` resolves a `RoomGroup` to). Cleared when a floor is built and when
  an Architect's First Draft resets the puzzles (his ask). **It is the NAME that goes red, not the square** (killer560,
  2026-10-01): labels are already `stateColor(visibleState(group))` and `STATE_FAILED` is `0xFFFF5555`, so reaching the
  state is the whole job - most "make X show on the map" asks are about a state nothing in a sim can set.
  `SimPuzzles.reportFail` has a room-name overload because `SimQuizPuzzle` runs both Quiz and Three Weirdos.
  `SimRoomState.markFailed` refuses Ice Fill and Ice Path outright.
- **`forget()` has to drop the placed-block list too.** `SimIcePathPuzzle` and `SimIceFillPuzzle` place their own
  standalone arena; `armFloor` calls `forget()` for every puzzle a floor does not hold, so a kept `placedBlocks` made a
  later `/simpuzzle reset` queue air inside a fresh floor. Callers that want the blocks removed call `clearPlaced()` first.
- **Puzzle reward chests are not secrets.** The room database lists 0 secrets for Water Board and Boulder, so both reward
  chests are `SimMimic.markPuzzleReward`ed and skipped by the chest counter.

## Verified against the captures

| Room | Claim | Measured |
|---|---|---|
| Quiz | three answer pillars ringed by the room's buttons | 3/3 smooth_stone with air above at rotation 180, 12 of 12 wall buttons |
| Water Board | 7 levers, 27-piston back wall, 5 wool columns | 7/7 levers at 270; 27 pistons at z=28, 13 extended; wool at `(15,55,z)`, air at 56; terracotta identifier present |
| Creeper Beams | 22 lanterns in 11 pairs | 22 distinct over 13 entries (two duplicates), 9 sea + 13 prismarine, plus 13 sea lanterns in no pair |
| Teleport Maze | 30 pads, sealed chambers | 30 end_portal_frames, 240 iron bars, solid wall at `(12,69,13)` between the start pad and chamber one |
| Tic Tac Toe | 8 of 9 buttons, the ninth already played | 8/9 at rotation 180, the gap is row 2, col 2 (bottom right) |
| Ice Path | 289 ice cells, one stray wall | 289/289 ice; 17 non-air at the wall layer against the corrected 16, extra at `(15,16)` |
| Ice Fill | path is feet positions | at `dy = 0` all 45 identified tiles are air over ice; identifier pairs match all three floors |
| Three Weirdos | chests lined up with the cauldron | chests `(25,69,12)/(26,69,14)/(25,69,17)`, cauldron `(29,69,16)`; new spots have floor under, 3 air over |
| Mines / Pressure Plates | one lever opens a whole wooden door | lever at the recorded spot; Mines' region is 21 cells, 21 dark-oak/iron-bar blocks (y 78..84 is the door, 74..75 a grate below the floor); Pressure Plates' 30 cells, all oak |

## Ice Path

- **The whole puzzle was already in the capture.** `SimIcePathPuzzle` arms `assets/killer560smod/rooms/Ice_Path.json` without writing a block, at
  database rotation 180: the 17x17 board's 289 cells have `packed_ice` at relative y 66 (where the solver draws), the maze
  is 17 `polished_andesite` pillars at y 67 (where `IcePathSolverFeature` reads walls), the border ring is 65 of 65 `polished_andesite` and **the three cells it is
  missing (row -1, columns 7 to 9) are exactly the solver's exit**. The other rotations score 33, 5 and 4 of 65; the
  `blue_terracotta` roof marker agrees.
- **One stray block made 26 cells unsolvable.** killer560 marked the real room up (2026-10-01): the capture had one extra
  wall at board `(15,16)`; without it all 273 open cells can exit. "Zero unsolvable cells" is the cheap check on a decoded
  board. There is exactly ONE Ice Path layout, entered from row 16 with the chest past row 0, so `bindAt` CONFORMS a bound
  room's maze layer to it (`SimIcePathPuzzle.bindAt`) and logs how many cells it corrected.
- **The silverfish is the only thing a capture cannot hold.** It starts at (15,15), ten shoves out, checked for
  solvability at arm time (his first spot, (14,16), was a dead cell). A fixed guess is worse than it looks: cell (16, 8)
  slides straight out in one push.
- **A punch must not be a hit.** The shove arrives on `AttackEntityCallback` returning `InteractionResult.FAIL`, before any
  damage, so 8 HP is never punched away. An arrow is picked up by looking for an `AbstractArrow` at the resting fish, and
  the direction comes from the shot's AIM: `TerminatorArrow` carries it (`SimTerminator.shotYaw`), any other arrow uses
  its shooter's yaw (`pollForArrow`), and all three arrows of the shot are discarded. **An arrow's `getYRot()` is not where it was aimed**:
  `Projectile.shoot` sets yaw to `atan2(x, z)` of the velocity (a look is `atan2(-x, z)`), a pitch-90 shot has almost no
  horizontal velocity (`SimTerminator.fromAngles` even points it backward, `cos` of float pi/2 is -4.4e-8), and a hit on
  the invulnerable fish is deflected with `ProjectileDeflection.REVERSE` - so every Auto Ice Path shot used to shove into
  a wall, silently. Every shove or wall-shove is an INFO line. Testkit `94-sim-icepath-shove` (26.1.2 and 26.2): a punch
  and a straight-down Terminator shot each slide it one cell, health stays 8.0.
- **A silverfish out of range is not a dead one.** `level.getEntity(uuid)` only sees tracked sections; the state is kept,
  and a fish missing while he is within 24 blocks of its cell is put back (strays on the board cleared first). It is put
  back only where `isPositionEntityTicking`, at most once per 100 ticks, and the next tick that finds it removes other sim
  silverfish (`respawnIfNear` had piled 361 onto one cell while chunks came up).
- **The silverfish pushed him from the CLIENT.** `SimSilverfish`'s overrides exist only server-side; the client's plain
  `Silverfish`'s `pushEntities` selects the local player (`EntitySelector.pushableBy`). It joins team `k560_icepath_fish` with collision
  NEVER. **A behavior override on a server-side entity subclass does nothing on the client.**
- Solver: declines in four places that look alike, so `sayOnce` prints which; its silverfish search box is 20 blocks
  (the board is 17 across, the old 16 put its corners on the edge).

## Ice Fill

- **Ice Fill's path is feet positions, not ice.** The bundled path is where you STAND; the ice is under it. Decoded at
  rotation 270, at `dy = 0` all 45 tiles are air over ice and `IceFillSolverFeature`'s identifier pairs match one pattern
  per floor (3, 4, 3); at `dy = -1` none match. Publishing -1 (an earlier cloud round's reading) broke every floor's
  identification. `SimIceFillPuzzle.bindAt` tests for ice one under each waypoint and publishes no nudge. Before reading
  a bundled coordinate as "the block", check whether it is the block or the space above it.
- **Ice Fill is judged on the server, every landing.** The judge read `client.player` once a client tick, so two position
  packets between ticks (a loaded machine) went 9 -> 11 and broke the section (reproduced with testkit `-PnetStallMs=120`,
  0 of 6). It now judges once per server tick for walking and jumping and from `SimAbilities.teleport` for every landing
  (`onTeleport`). A jump needs the feet above standing height (`JUMP_HEIGHT` 0.2), since a landing reads not-on-ground for
  a tick or two. `-PicefillControl=true` proves it still breaks a section on a two-tile warp and a repeated tile.
- **A broken section regenerates, alone.** Hypixel brings it back "after two-ish seconds" (killer560). `breakSection`
  airs only the live section and `regenerate` lays it back after `REGEN_TICKS` (40); finished sections stay, `activeSection`
  does not move, and it reports no fail. Read-only aids: `entryTile(s)`, `breaks()`, `regenerations()`,
  `lastBrokenSection()`, `isBroken()`, `activeSection()`, `landings()`, `lastLandingTile()`; breaks and regenerations log
  an INFO line. Auto Ice Fill's recovery is in [SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md).
- `SimAbilities.dashTarget`, once the look drove the box into the floor, slid at that height for the full range (six blocks), off the path. A settled
  walk ends where the EYE ray meets a block (35 degrees down: the eye ray meets the floor 2.3 out, the old slide went 9.8)
  and may step up one block the eye can see over.

## Water Board

- **The capture settles the geometry, not the redstone.** `Water_Board.json` decodes at rotation 270
  (`capture (cx,cz) -> db (31-cz, cx-1)`; capture x,z = relative z+1, x+1). Board 0 (terracotta identifier at
  `(14, 77, 27)`). 7 of 7 levers at `(20|10, 61, 10|15|20)` plus the back lever at `(15, 60, 5)`, each on its matching ore.
  The capture is one frozen frame with every color retracted; Hypixel moves blocks by `setblock`, so the sim drives the
  visible parts with its own rules. A sealed column at `(15, 59..62, 4)` (source `(15, 62, 3)`) just runs and is never
  touched.
- **Three of five colors must be out or the solver sits out.** `WaterSolverFeature.scan` wants exactly three extended plus
  a matching identifier. `SimWaterPuzzle.bindAt` picks a board `water-solutions.json` contains (`"012"` first), pushes
  those three out, and drives `WaterSolverFeature.bundledClickOrder` for that board - reading his **Optimized Path**
  setting, which switches the whole sequence.
- **The bundled data kills "one click opens one color"**: true for 6 of 80 boards; click counts run 1 to 10 against always
  3 colors, and 12 of 80 flip the back lever a second time. The sim spreads the three colors over the timed clicks,
  nearest the entrance first, and treats a later `water` entry as a timed click that also toggles the flow (the sim's own
  choice, written in `SimWaterPuzzle`'s class doc).
- **The gates are the back wall.** Twenty-seven sticky pistons at relative `z=28` each push an ore block naming the slot's
  owner (`coal_block` COAL, `terracotta` CLAY - the solution file says `hardened_clay` - and so on); retracted the ore is at
  `z=27`, extended it is pushed to `z=26`. Counts: coal 4, gold 3, quartz 5, diamond 6, emerald 4, clay 4, plus one
  `lapis_block`. Thirteen start extended, the room's own starting pattern, which a reset restores. Piston head and states
  are copied off the room at arm time, never built from property-name literals.
- **The back lever is the lapis slot.** The top water (sources `(15, 91|95, 20)`, channel along y 89 to z 26, fall at
  `x 14..16, y 82..88, z 26`) always runs and rests on the `lapis_block` at `(15, 82, 26)`, pushed by a sticky piston at
  `(15, 82, 28)` with a redstone block behind it. Pulling it drops the water down the one-wide shaft x 15, y 78..81 into
  the maze plane (z 26, front glass z 25). Slot moves write the maze-plane cell with `UPDATE_ALL` (water flows or drains)
  and everything else with no updates; each slot's power block (relative z 29: redstone out, polished andesite in) moves
  with it. No piston is under another, so nothing is quasi-powered. **The puzzle cannot fail**; an off-script click is
  named in chat. Water leaving the maze's five bottom gaps spreads on the floor at y 59 - not measured in game.
- **Whole color layers.** The bottom path is a walkway under the glass: stairs at z 9..11 down to y 56, walkway x 14..16 to
  an end wall at z 24, each color owning one layer at its z (red 15 .. purple 19). A layer has FIVE sticky pistons: up
  under the middle at (15, 54, z) and inward at (12, 56|57, z) and (18, 56|57, z) with wool at (13|17, 56|57, z). The middle push lands in
  `(15, 56, z)`, the block the solver's `extendedSlots` scan tests.
  `SimWaterPuzzle.LayerPiston` reads all five per color at arm time (facing off the block) and moves them together, each
  with a power cell behind. The solver still reads only (15, 56, z).
- **The reward chest is relative (15, 56, 22)**, facing up the walkway, between the gray carpets ((13|14|16|17, 56, 22),
  (14|16, 56, 23)) and not touching the end wall (his spec). It is straight under `AutoWater`'s chest spot (15, 58, 22), so
  Secret Aura opens it through the glass (server reach has no line-of-sight check). Placed on solve, removed by a reset,
  dropped by `forget`; `isRewardChestOpened` on the server. Testkit 93-solve-waterboard checks all of it through
  `PuzzleCoords` (old jar fails at the start, "0,0,1,1,1").
- Water stands still outside the Water Board: see [SIM-SERVER.md](SIM-SERVER.md).

## Boulder

- **Boxes are 3x3x3 planks, a press moves the box ONE cell**, and if that cell is off the grid or taken the box disappears
  (wiki plus the bundled data: over all eight patterns all 18 solution steps find their box and an empty button cell, and
  every pattern opens a row-9-to-row-24 path; plain push fails 6 of 18, pull 4). Buttons are wall buttons at y 65, one
  block out from each face whose neighbor cell is on the grid and empty (the capture's 31 follow it), laid again after
  every move. The puzzle cannot be failed. Decoded `Boulder.json`: 90 jungle and 72 birch planks on each of y 64..66,
  none on 67; the room's own arrangement `011110001011000101100000010000101000001100` is not one of the eight in `boulder-solutions.json`, so a random
  known pattern is written, as whole boulders in the room's own plank. (Earlier versions hung boulders at `BUTTON_Y` 65
  under `FLOOR_Y` 66, propped missing buttons on stone pedestals, deleted instead of pushing, and moved one block of a
  column; all gone.)
- **Bind a puzzle to what the room always has, never to the puzzle's own state.** Fingerprinting one row of the capture's
  own arrangement failed on Mod Only Test's capture ("best 1 of 7", also turned a quarter). The fingerprint is the fixed
  room: the diorite checkerboard (21 cell centers at relative y 63) and the far staircase (stairs at (13..17, 64, 27),
  barrier over them at y 68) - 31 of 31 on both captures.
- **Reward chest** at relative (15, 66, 29) through the bind's anchor (capture (30,66,16)): the back-middle alcove up three
  steps at z 27..28, under the oak-log mantle at y 69. Written only into air. The box grid and its barrier ceiling keep it
  until solved. Hypixel's real chest position is unverified.
- **Boulder is done when its chest is opened** (killer560, 2026-10-15). `SimBoulderPuzzle.isComplete` is "the chest was opened, or the
  board is solved" (read by `SimTabList` and `SimRoomState`); `isPathOpen` is the board alone; a reset clears the chest.
  Testkit 93-solve-boulder-chest-unsolved: complete the tick the chest opens, tab ✔ 1-4 ticks later.
- **No etherwarp in Boulder**: sneak + etherwarp item and the Interactive Map's `etherwarpAlong` are refused while standing
  in it; Instant Transmission is not. The sim presses boxes on the SERVER copy of `UseBlockCallback` (raw packets push).
  A single-room Boulder spawns one block in from its doorway (the tile-center scan walled him in).

## Blaze

- **The solver only looks at armor stands.** `BlazeSolverFeature.rescan` skips anything not an `ArmorStand`, so a name on
  the blaze was invisible and `AutoBlaze` (reads only `getOrderedBlazes()`) did nothing after `seedDefaultView` turned him
  to the middle. The label hangs on its own stand, dropped the tick its blaze dies.
- **The label stand is a MARKER.** A plain invisible stand keeps its 0.5 x 1.975 hitbox and caught his arrows. `setMarker`
  is private; the flag goes through `ArmorStand.DATA_CLIENT_FLAGS` / `CLIENT_FLAG_MARKER` (public, same in 26.1.2 and 26.2),
  and `onSyncedDataUpdated` refreshes the box on that key.
  Odin's offsets (`inflate(0.5, 1.0, 0.5).move(0, -1, 0)` for the box, `boundingBox.getCenter().y - 1.0` for the aim) from
  a marker at `bbHeight + 0.1` land on the blaze's middle. That stand height is the one dial (stand geometry cannot be
  captured); `attachLabel`'s doc says so. `SimMobs`' star tags are still full stands.
- **Heights.** Higher Blaze (y 65..133) and Lower Blaze (15..83) are the same shaft 50 blocks apart: floor at 69 / 19, air
  from 70 / 20 to a polished-andesite ceiling at 130 / 80, iron bars up the middle (Higher's 71..129). Both start on the
  shaft floor from the room's own band (`SimBlazePuzzle.bindAt` uses `RoomLibrary.Room.minY/maxY`, never a hardcoded 66 or 120; searching down from the
  top found the landing at capture y 118) and spread up to 5 apart, relative 71..116 in Higher - the band QUOI's
  `HIGHER_SPOTS` (85..118) shoot into.
- **Blaze life.** A `SimBlazeEntity` whose chunk unloads saves as "minecraft:blaze" and PEACEFUL discards it, leaving labels;
  `SimBlazePuzzle.putBackMissing` restores it with its HP once missing 20 ticks from a section that
  `isPositionEntityTicking`. **"Dead" is never inferred from absence** (`getEntity == null || !isAlive` made `isComplete()` true before the auto ran; null means not shown, ~27 s on a fresh
  world): dead only once SEEN dying (`isDeadOrDying`, kept in `SEEN_DEAD`). A fail drops every label, and `despawnCurrent`
  snapshots the labels it removes. Per-kill attribution is logged ("blaze N of 10 died (due: k) ... killed by shot #S").
- **The sim's Terminator**: right click always shoots; Salvation is the left-click ability. Side arrows at 5 degrees
  (QUOI's number, not measured on Hypixel). `TerminatorArrow.shouldBeSaved()` is false, and a plain DISALLOWED-pickup
  arrow an older jar saved is refused and discarded by the damage hook (two saved arrows had killed chain blazes, 2 of 15
  runs).

## Creeper Beams

- **A correct first shot was painted as a failure.** Turning a held lantern to `PRISMARINE` is what
  `BeamsSolverFeature.rescan` reads as "burned on the wrong partner" (`litA != litB && (usedUp(a) || usedUp(b))`), so it
  drew the pair red. The hold is drawn by the puzzle's own renderer; the block changes only when the pair joins.
- 35 lantern blocks, 22 of them in the bundled eleven pairs; the other 13 are the room's own sea lanterns (on Hypixel too),
  and the sim says once per lantern that one is not in a pair.
- **Sound packets drive Auto Creeper Beams**: `entity.elder_guardian.hurt` at the exact lantern, pitch `1.3968254` (first
  hit) or `2.0` (pair done), at INTEGER coordinates (a center sound arrives as `x + 0.5` and never matches).
- **It cannot be failed** (wiki "Failable: No"; four beams through the creeper solve it). A wrong second lantern flashes a
  red line for 2 s, drops the held end and changes nothing. A held button re-fired the pick every third tick; the stamp is
  refreshed on every re-entry, so one hold is one click.
- **The far lantern was out of reach, not refused.** Clicks only happen within 4.5 blocks; `SimCreeperPuzzle.shotAlong`
  resolves a shot (Terminator center arrow, Salvation, Mage beam with no mob in the way) as a block ray at fire time; a
  three-tick same-lantern guard absorbs a shot and a click in one tick.

## Teleport Maze

- **The start pad was the one pad nothing indexed.** The chambers are sealed (240 iron bars, a wall at `(12,69,13)`), so the
  start pad `(15,69,12)` is the only way in. `bindAt` indexed only `REAL_PADS[0..27]`.
- **Landings at `y + 1.5`**: the solver only treats a position packet as a maze teleport when it lands on relative
  `y 69.5`, and finds the exit from the yaw of exactly those packets. Every teleport turns you to face the exit pad.
- **Paired like the real room**: each of the 28 pads links both ways to a pad in another chamber, the start pad to one,
  one pad to the end; a landing puts you ON the destination pad, inert until you step off. `routeToCentre` walks each draw
  pad by pad and keeps it only if it reaches the end pad (shortest route logged at bind); a fixed chamber chain is the
  fallback, never an empty table. Abilities and the Dungeon Breaker are refused in the maze by room name.
- **The center has a chest and a way out.** The capture has no chest and the database no secret, so the sim places one at
  relative `(15,69,17)` (center interior x 12..18, z 14..20, walls at 11/19 and 13/21); a click from outside that box is
  refused with FAIL (registered before `SimMimic`). Stepping off the end pad `(15,69,14)` and back on sends him to the
  Interactive Map's entry spot (`AutoClearUtils` `(15,68,-2)`), falling back into the doorway if not standable.
- **The solver read the wrong landing pad** (same pad layout on Hypixel, so the same fault there). QUOI's landing box
  x-0.5 .. x+2.5 took in the pad two blocks over in +x/+z, and the room has eleven such pairs across a wall
  ((4,12)/(4,14), (10,20)/(10,22), ...). The box is the landing's own block, a quarter block either way. **And it lost the
  exit pad**: the 32-block XZ candidate ray followed his LOOK pitch (66-75 degrees down on landing), stopping 8-13 blocks
  out; it is leveled now. Each teleport logs `[TeleportMaze] teleport N: from -> to, yaw, pitch, candidates`.

## Tic Tac Toe

- The real marks are maps in item frames (entities, never captured). The sim paints a played cell's mark on the wall one
  block back (database x=7) and removes that cell's button; `TicTacToeSolverFeature.readSimBoard` reads the wall as well as
  the cell (x=8), and only when it finds no item frames, so a real dungeon never takes that path. Painting only the wall
  once made every mark invisible to the solver.
- An ability item in hand ate the button press (`SimAbilities`' `UseBlockCallback` consumed every block right-click with
  an AOTV/AOTE or wither blade): a button, lever or chest now wins unless you sneak, as on Hypixel. The click logs
  `TicTacToe: clicked ... holding ...`.

## Quiz

- **The solver wiped a question announced from the corridor.** `QuizSolverFeature` and `WeirdosSolverFeature` `reset()` (clearing `triviaAnswers` and
  every `options[].correct`) on every room change, and the 22-block announce reached the corridor. An announce waits until the live map names the bound
  room with a resolved rotation for three ticks running (solvers reset on `END_CLIENT_TICK`, this runs on `START`); a room
  the map cannot name falls back to distance after three seconds, logged. This gate now covers Three Weirdos and the
  standalone `/simpuzzle quiz` drill only.
- Read off the solver: a second question in the same room lit two answers (Hypixel always announces the previous as
  answered first), so `clearForNewQuestion` / `clearForNewRound` do it; `newQuestion`'s label-replacement clears the click
  index, which killed the pillar buttons; the solver's three coordinates are `smooth_stone` pillars, so the box goes on the
  block itself (decoded `Quiz.json`); `getCorrectAnswerButton` returns a button off the correct pillar's four sides. The sim only asks a question
  `QuizSolverFeature`'s first-contained-key scan resolves to itself, and rejects a distractor that merely ends with a
  correct answer (the test is `anyMatch(trimmed::endsWith)`). The standalone arena and the room share one question format.
- **Oruo's cooldowns** (his ask, 2026-10-17, played on the SERVER in `SimQuizPuzzle`'s Oruo section). "I am Oruo the
  Omniscient...", then "Though I sit...", "Prove your knowledge..." and "Answer incorrectly..." 40 ticks apart, Question
  #1 at +160, open at +220. After a correct answer: "NAME answered Question #N correctly!", the lock line ("2 questions
  left..." / "One more question!") 40 ticks later, its question 40 after that, open 100 after the lock line. Last:
  "NAME answered the final question correctly!", then "I bestow upon you all the power of a hundred years!" 20 ticks
  later. Locks from NoammAddons `QuizSolver.kt` (`+ 220` / `+ 100`) and Odin/QUOI (`START_DURATION = 220`,
  `QUESTION_DURATION = 100`); gaps from his `26.1.2 (Dungeons)` logs, 276 runs (2 s between Oruo's lines in 256 of 278,
  Question #1 8 s after the intro in 210 of 274, answers 11 s after the intro and never under 10, 5 s after a lock line and
  never under 4). An early press is refused silently. After the last answer: talking gets "You have already shown your
  vast knowledge...", a press "You've already proven enough to me!...". A wrong answer sends "NAME chose the wrong answer!
  I shall never forget this moment of misrememberance." (his report, in `ArchitectDraftFeature`), fails the room and resets
  Oruo. Lines go out at START_SERVER_TICK (or in the interact packet) and ticks count at END, so a lock of N opens exactly
  when a client counting N pings (QuizState) may press: 1017-sim-quiz-timing measures every Auto Quiz answer on the opening
  tick + 1. The question block: blank, centered "Question #N", the centered question, blank, `    §6 ⓐ §a<answer>` x3,
  blank; answer holograms are replaced each question.
- **Quiz: walking in starts it** (killer560, 2026-10-18). His logs: of 293 intros, the 47 where he walked in came 0 s (29)
  or 1 s (18) after SecretRoutes' "Entered Dungeon Room: Trivia-Room"; NoammAddons and Odin only read Oruo's lines. The
  sim's trigger is a player's feet stepping into the one-tile footprint (relative 0..30 through the bind's anchor) at
  START_SERVER_TICK while Oruo is idle; after a wrong answer it takes leaving and coming back. Talking to Oruo does nothing
  until the last answer, and there is no CLICK stand. **[QuizLog]** (`puzzlesolvers/QuizLog`, always on, INFO, so dev/cheat
  builds only) logs room entry/exit, every Oruo line, each question with QuizState's open tick, every press in the room
  (his through an early `UseBlockCallback` phase, Auto Quiz's through `notePress`) as EARLY/on time, and Oruo's answer
  within 40 server ticks, on `ServerTickClock`. Auto Quiz's side is in [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md).

## Three Weirdos

- The capture's chests are at local (25,69,12), (26,69,14), (25,69,17), cauldron (29,69,16); all three moved the same two
  blocks so the middle lines up with the cauldron (his rule: the arrangement is the room's own).
- Three visible stands (arms, name shown) are placed at bind, one database block of -x from each chest - the offset
  `WeirdosSolverFeature` walks (`relative.x += 1`) - with the world direction derived from the anchor
  (`anchor.world(1,0,0) - anchor.world(0,0,0)`), each with an invisible "CLICK" stand under its name (what Auto Three
  Weirdos looks for). The right weirdo speaks a line from the solver's SOLUTIONS list, the others from WRONG. The walk-in
  check moves them if the live map's transform disagrees with the anchor.
- **They speak from the SERVER copy of `UseEntityCallback`** (inside `ServerGamePacketListenerImpl.handleInteract`), with
  `ServerPlayer.sendSystemMessage`: the client copy fires only from `Minecraft.startUseItem`, so `gameMode.interact` did
  nothing. Left clicks pass on the client and are answered (and canceled) on the server.
- **An ArmorStand's name floats about 2.3 blocks above the stand** (the "text two blocks too high" report, on Quiz and
  Weirdos): one lift constant for the file; a stand inside the floor is fine (invisible, no collision).
