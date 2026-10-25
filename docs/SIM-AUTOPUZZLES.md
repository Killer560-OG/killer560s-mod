# Auto puzzles in the sim

What the puzzle autos needed to play the sim's puzzle rooms, and the auto faults the sim found (marked "Hypixel too" where
the auto runs the same there). The rooms themselves are in [SIM-PUZZLES.md](SIM-PUZZLES.md); later Auto Puzzles lessons
(timing, leaving, camera, reach) are in [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md). Part of the sim docs: [SIM.md](SIM.md).

## Shared

- **Hypixel heights hardcoded where the sim moves the floor** - five places, each failing silently:

  | Where | Was | Symptom |
  |---|---|---|
  | `AutoBeams` | `player.getY() != 75.0` | repositioned onto the platform, re-read 75, repositioned again |
  | `AutoWater` | `player.getY() != 59.0` | every lever click declined, no message |
  | `AutoBlaze` | `player.getY() <= 75` | Higher Blaze skipped its reposition |
  | `AutoIceFill` | `69.5..72.5` band, `stupidStairs`' 71.1 / 72.1 | warped onto the first unfilled tile; stair midpoints dropped |
  | `TeleportMazeSolverFeature` | `pos.y != 69.5` | rejected every sim teleport, never marked a pad |

  `BoulderSolverFeature` called `RoomDatabase.toRealCoord` directly (read the arrangement out of empty air), and
  `AutoBoulder`, `AutoBlaze`, `AutoTicTacToe` did the same for a secret's position. `RouteCoords` now carries the shift in
  both directions, so an Auto Route means the same thing wherever it was recorded (the offset is chosen per floor).
- **Auto puzzles: the sim's Terminator was not a shortbow.** Auto Creeper Beams, Ice Path and Blaze gate their shot on
  `AutoPuzzleUtil.isShortbow`, a lore search for **`Shortbow: Instantly shoots!`** (Hypixel marks every shortbow so).
  `SimItemLore`'s Terminator lacked it, so all three aimed and never fired, and `AutoReposition`'s swap-to-a-bow failed.
- **Fixing the item table fixes no item already in a saved inventory.** The sim's save is reused (`SimWorld`), so his
  Terminator (in `saves/killer560s-dungeon-sim/players/data/<uuid>.dat`) kept the pre-10-01 lore. `loreContains` was right. `SimItemLore.register`
  re-applies the table every 20 server ticks to any stack whose id is in it and whose lore differs (the Dungeon Breaker's
  "Charges" line is ignored and kept), as Hypixel's server does. All three autos call `AutoPuzzleUtil.holdShortbow`
  (HELD / SWAPPING / NONE: any hotbar item whose color-stripped lore has the phrase, one swap a tick, shoot a later tick).
- **A swap and a use in one tick reached the server as a use of the OLD item.** Fabric's client `UseItemCallback` sits on
  `MultiPlayerGameMode.useItem` BEFORE `ensureHasSentCarriedItem` (fabric-events-interaction 5.2.2, javap), and a SUCCESS
  cancels there, so vanilla's "send the selected slot first" never ran: a sneak-held swap-to-AOTV-and-warp arrived as a
  Terminator shot (client "holding ASPECT_OF_THE_VOID", server "use TERMINATOR slot 1"). `SimAbilities.sendHeldSlotFirst`
  calls `ensureHasSentCarriedItem` before answering SUCCESS.
- **The sim teleported along the CAMERA, not the aim.** `player.getViewVector(1f)` goes through `getViewXRot/getViewYRot`,
  which `Ap3ViewYawMixin` answers with `ViewFreeze`'s held view while an auto turns him; `getLookAngle` uses
  `getXRot/getYRot` (26.1.2 bytecode). **Anything in the sim that resolves an aim uses `getLookAngle`** (dash, Mage beam,
  Spirit Sceptre, Superboom/breaker pick); the etherwarp resolver and the Terminator already read the real rotation.
- Every refusal in the autos is an INFO line, once per change of reason (`[AutoIcePath] ...`, `[AutoPuzzles] Blaze: ...`,
  `[AutoPuzzles] Beams: ...`, `[AutoIceFill]`, `Water: waiting - ...`), and each reposition logs its warp and held item.
  `AutoReposition` aims with `AutoPuzzleUtil.etherwarpAim` (TeleportUtils' verified face-center aim, QUOI's as fallback).
- **Test aids.** `SimPuzzles.isComplete(name)`: one solved signal per puzzle, by key (`icepath`) or live-map name
  (`Ice Path`; a blaze room name is true only when the armed arena is that room). `SimPuzzles.isRoomComplete(roomName)`:
  per ROOM (`isWeirdosComplete`/`isQuizComplete` tell the shared `SimQuizPuzzle` apart; Boulder's is the path opening).
  `SimCreeperPuzzle.joinedCount()`, `SimBlazePuzzle.killedInOrder()`. Testkit `93-solve-*` plays each auto alone on both
  capture sets (Mod Only Test and Map Logger); round B's record was 3 of 3 launches all five PASS on each.

## Auto Ice Path

- **The shot yaw was an etherwarp aim.** `etherwarpDirection(nextSpot)`'s top-center point never matches from above, so it
  used a point 0.001 from an edge - up to 45 degrees off the board axis (Hypixel too). The yaw is cell center to cell
  center. Logs `[AutoIcePath] shot N from ... toward ...`; three shots from one cell without a slide WARN. The sim side of
  the shove (arrow aim, not arrow yaw) is in [SIM-PUZZLES.md](SIM-PUZZLES.md).
- **The only spot it could shoot from was one it could rarely reach** (Hypixel too). From the board the eye is 1.27 over
  the ice, so a ray to a cell a few blocks off runs along the pillars. Now: a direct warp, else a warp to a visible open
  cell from which the fish's cell can be seen (a visible neighbor of it always qualifies), else an Interactive Map walk.
  Solver: a board change seen while the fish was missing or sliding is kept (`boardDirty`) until a solve uses it.

## Auto Ice Fill

- `AutoIceFill` splits its path into sections by height (stair midpoints excluded). Half or more of a section's tiles gone
  to air, or feet 1.5 under the last tile, starts a recovery: reposition / map walk canceled, sneak released, no hops,
  until every tile of the section reads `ICE` (15 s cap, then a chat line and stop until he leaves the room); then
  `AutoReposition` onto that section's first tile (map walk if no warp line; 10 s cap) and hops resume. Leaving the room
  resets it; five breaks in one room stop it. The "off the band" reposition onto the first ICE tile runs after the break
  check, so it no longer skips a broken section. Hopping before the last landing reaches the client is normal on Hypixel.
- Testkit `-PicefillControl=true` (`-PicefillBreaks`) forces a mistake half way across sections 1 and 2 by putting him
  back on the tile he left, judged as a landing (`onTeleport`) - a plain server teleport was not, since the next hop in the
  same tick moved him first. Verdict reads the sim: broke, no landing while broken after a 4-tick grace, regenerated, first
  landing after on its entry tile, complete. 26.1.2 10/10 forced and 10/10 plain, 26.2 3/3; a jar with the check off fails
  (13 landings while broken). The fall test ("feet under the section") never fired - unexercised.

## Auto Blaze

- **Spots** (Hypixel too). A QUOI spot was judged from `spot.getY() + 1.62`, inside the block he stands ON; the real eye
  is the sneaking 1.27 over its top. His own spot is skipped. With no listed spot clear it searches the room's standable
  blocks (24 around, 14 below to 24 above the blaze, nearest first, 12 a tick, never below Higher Blaze's top level) - the
  fifth blaze of Lower Blaze floats a block over the shaft floor, under QUOI's lowest spot. A spot no single warp reaches
  is reached by two warps through a searched block that sees it, and only then, once per spot, handed to the Interactive
  Map planner (asked every tick it failed 1,100 times a minute). Four shots from one spot that kill nothing give it up for
  that blaze (196 shots from one spot on Mod Only Test; requiring a clean shot from the vanilla arrow origin, eye - 0.1,
  refused every spot in Lower Blaze).
- **Out-of-order kills** (Hypixel too). The arrow wait was `distance / 2.5` ticks, far shorter than a steep climb (28.8
  blocks at pitch -84), so a second shot flew through the first kill into the next blaze; the wait became the arrow's
  simulated flight to closest approach plus four ticks (later replaced by `AutoBlaze.VolleyWatch`, see
  [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md)). Side arrows are followed until they land (QUOI stopped them past the
  target). One arrow kills and arrows pass through a dying blaze, so a target touch is not safe: 31 of 160 and 52 of 206
  kills were by a side arrow; every arrow (center and both sides) is traced to where it lands with the target treated as
  absent and must miss every other blaze, the center one reaching the target first.
- "Blaze: done." waits for the solver's list to stay empty for 30 ticks (a rebuilt chain empties it for a moment).

## Auto Creeper Beams

- Shots aimed with `etherwarpDirection` (a SNEAKING eye at QUOI's face-edge points) missed once he stood up. Shots aim from
  the real eye at the lantern's center or a face center inset 0.05, each checked with a collider ray. "On the platform" is
  within half a block, not `==` (a landing is 0.05 high and settles a tick later). A platform spot it cannot warp onto is
  skipped next time; the lantern under the creeper's feet is not checked for the creeper; with no clear spot it shoots and
  says so.

## Auto Tic Tac Toe

- It stopped after one move because of its own chest trip (`ticTacToeAuraChestEnabled: true`): the walk stopped short, re-
  asked for a path every tick, and the 15 s timeout sat behind `if (ClearExecutor.isBusy()) return`. The timeout is checked
  first and cancels the walk, a leg that asked for 3 paths gives up, and every trip transition is an INFO line. The trip
  ends once the chest is in aura reach, not on its lid (Hypixel too).
- **The last move out of reach**: the map's spot (relative 11,68,16) is where a walk-in ends, ~5.5 blocks from the last
  move. `MazeWalk.planToSpot` runs one Dijkstra over his floor and takes the nearest spot BY WALKING whose standing eye is
  within 4.0 of the button's box; the walk is the camera plus the forward key (Hypixel too).

## Auto Boulder

- **Finds the chest block**: the database lists no Boulder chest and the capture holds none, so it scans the room
  (relative -1..31, y 60..75, through `PuzzleCoords`, section palettes first) every 20 ticks for a CHEST/TRAPPED_CHEST and
  takes the back-most; within 4.5 blocks it auras without walking. Identical on Hypixel.
- **The chest is behind the boxes, so it pushes.** "Stand 3 up and 3 back" was air over the barrier roof and etherwarp is
  refused there. Now: find the chest, get off the roof (a hole whose fall lands on the floor and walks to the grid's front,
  relative (15,64,7)), press each Boulder Solver button from a walked-to spot in reach (once that button exists), walk into
  reach of the chest and aura it, then ask the map for the doorway once. Records `SimBoulderPuzzle.isRewardChestOpened`.
- **From the map's doorway spot** (relative (15,68,-2), filed under the next tile) Boulder Solver does not scan, so it walks
  inside the tile first and starts its 4 s solver wait there. The chest click takes a point it can see, else the middle (a
  stair edge hid a random outline point once in four). Testkit `93-solve-boulder-mapdoor`.

## Auto Water

- **QUOI's spots are out of reach**: standing on (15,58,z), every side lever (x 10 or 20) is 4.52-4.54 eye-to-box. The auto
  warps only when the lever is not in reach, onto the standable lever-floor block nearest QUOI's spot whose standing eye is
  within 4.3 of the lever and which an etherwarp can aim at (read off the world, so Hypixel too).

## Auto Teleport Maze

- Holds the free camera (`ViewFreeze`) every tick from the first maze teleport until it stops, from the view of the tick
  before (`rotateCamera`'s per-hop lease of 400 ms lapsed during walks of up to 3 s).
- The center is walled from the start pad at z 13, so its Hypixel finish (walk to the start pad) cannot work in the sim;
  there it steps off the end pad and back on. The start-pad walk also runs when he is in the maze before any teleport, on
  the ground, no movement keys for 10 ticks, start pad within 24 blocks on foot - once per visit. 60 teleports without the
  end stop it.
- **The pick is `autopuzzles/MazeRoute`**: the exit pad when named and here; a way to its chamber over links seen this visit
  (breadth first); QUOI's exploring order; else a known way to the nearest chamber with an untried pad ("the diagonal
  again" is gone). `tools/mazecheck/route.sh` plays it offline: with a correct solver both picks reach the center every
  time (mean 6.7 teleports, max 13). Testkit `93-solve-teleportmaze-budget` (30 s, `ExitWatch`: the exit pad must stay a
  candidate after every teleport).

## Auto Three Weirdos and Auto Quiz

- Auto Three Weirdos logs each NPC it talks to and every reason it waits; it moves into reach itself since
  weirdos-reach-1015 ([LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md)).
- Auto Quiz only answers (the room starts on walk-in); **Quiz First** ends the trip into the room the moment QuizState sees
  the intro ("Oruo began N tick(s) into the trip, as he walked in - leaving at once") and the next decision is Ice Fill.
