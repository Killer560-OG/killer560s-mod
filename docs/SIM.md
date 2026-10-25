# Dungeon Sim - quirks and lessons

Everything learned the hard way about `roomsim/`. Split out of the project `CLAUDE.md` on 2026-09-29, and split by
topic on 2026-10-25 when this file passed 3,000 lines. Read the file for the area you are touching:

- [SIM-ROOMS.md](SIM-ROOMS.md): the room library and its captures, bad captures, a room's y band, measuring a room's
  floor, capture rotation (roof marker, vote, secret rotation), `/mage` re-captures.
- [SIM-FLOORS.md](SIM-FLOORS.md): floor generation and layout, the path to blood, pinned rooms, builds and the build
  queue, single-room loads, landings and `/goto`, the Map Designer and its filters, the room cycle, run replays, the
  floor bench.
- [SIM-DOORS.md](SIM-DOORS.md): doorways (one height, the carve, seams, impassable doorways), wither doors in the sim,
  the fill pass, Deuces' doorway.
- [SIM-PUZZLES.md](SIM-PUZZLES.md): the sim's puzzles and what each solver reads; [SIM-AUTOPUZZLES.md](SIM-AUTOPUZZLES.md):
  the puzzle autos in the sim.
- [SIM-SECRETS.md](SIM-SECRETS.md): scoring and room clears, item secrets, bats and chests, the mimic, crypts,
  Superboom, princes, levers, keys.
- [SIM-SERVER.md](SIM-SERVER.md): the sim as Hypixel's server - gates and reach, packets and abilities, trap rooms,
  teleports, weapons, mobs and damage, minibosses, live mobs, world rules, tab list and sidebar, per-tick cost.
- [SIM-MAP.md](SIM-MAP.md): the live map in the sim, the Interactive Map's executor and its etherwarp planning (path
  search, the warp graph, the quick floor graph, the exact search).

Sim lessons that sit with a feature rather than the sim are in [LESSONS-AUTOCLEAR.md](LESSONS-AUTOCLEAR.md),
[LESSONS-ROUTES.md](LESSONS-ROUTES.md), [LESSONS-AUTOMATION-PUZZLES.md](LESSONS-AUTOMATION-PUZZLES.md) and
[LESSONS-DUNGEONS.md](LESSONS-DUNGEONS.md).

## Rules for the whole package

- The dungeon sim (`roomsim/`) is the ONE place this mod writes positions, and that is correct there: the
  no-direct-movement rule exists because Hypixel reconstructs your movement and lags you back, and in the sim the
  integrated server is ours. Everything sim-only gates on `SimState.canAct`, which requires a singleplayer world
  AND no connected server. If that gate is ever wrong, those files write positions on Hypixel - treat it as the
  single safety boundary of that package and do not add a second way in.
- **The sim must never act on somebody else's server, and `canAct` alone does not guarantee it.** `canAct`
  needs a singleplayer server to already exist, so it is useless to anything whose job is to CREATE one.
  `SimBuilder.build(code)` had no gate at all and read `getSingleplayerServer() == null` as "no world yet",
  so `/simbuild code <x>` typed on Hypixel called `SimWorld.open` and tried to tear him out into a sim world
  (found 2026-09-29). The check belongs at the one chokepoint every entry into the sim passes through:
  `SimWorld.open` now refuses whenever `getCurrentServer() != null`, via `SimState.canOpen`. Audited at the
  same time: all six sim commands, all eight puzzle resets, and every block write in the package. The other
  writers (`SimSecrets`, `SimBuildQueue`, `RoomPlacer`) take a `ServerLevel` they can only get from a
  singleplayer server, and `SimBreakerState` ticks on `END_SERVER_TICK`, which never fires on a remote
  server - so those are structurally safe rather than gated. Scenario 88 fires every sim command at a real
  dedicated server and fingerprints the arena block for block before and after.
- The sim menu runs from the MAIN MENU, where there is no world yet. Every builder had a `server == null`
  branch that returned silently or opened an empty sim with a "run the command again" message, so nothing the
  menu offered ever built anything (found 2026-09-28: "no room ever loaded"). `SimWorld.open` now takes the
  build as a callback and runs it once the world exists, behind `SimLoadingScreen`. Any new entry point must
  go through that, not call a builder directly.

## Where the old sections went

Code comments cite sections of this file by title. Each old title, and the file that now holds it:

- Unreproduced: "this room is not part of supertall it is its on 1x1": SIM-MAP
- A crypt wall is NOT a solid slab of cracked brick, and decoration is speckled: SIM-SECRETS
- The Terminator's three arrows were hitscan; The Spirit Sceptre fired all along: SIM-SERVER
- A room's y band is per room, not a constant: SIM-ROOMS
- The generated floor's doors are written in plan(), not linkDoors(); isBusy() is not a completion signal: SIM-FLOORS
- Secret rotation: the translation is right, the DATA is what is missing: SIM-ROOMS
- Where a doorway's floor is, and three wrong answers about it: SIM-DOORS
- The single-room load never published its own map, and the build timer lied about it: SIM-FLOORS
- Open: impassable doorways cluster on the rooms whose doorways cannot be MEASURED: SIM-DOORS
- Never iterate the level's entity list while removing from it; Sim mobs are invulnerable to any damage with no
  entity behind it: SIM-SERVER
- FlatTestRoom is one tile and must declare margin 0; Measuring a captured room's floor is harder than it looks:
  SIM-ROOMS
- A doorway can be completely clear and still impossible to cross; Three different bugs were all reporting as "one
  doorway in eight is impassable": SIM-DOORS
- The 2026-09-30 round: SIM-ROOMS (roof marker, shape strings, `SimBuildAudit`), SIM-FLOORS (a flag set before
  `SimWorld.open`)
- Captures that hold another room's blocks: SIM-ROOMS
- Small ones from 2026-09-30: SIM-FLOORS (Load a Room picker), SIM-SERVER (Dungeon Breaker, hitscan weapons),
  SIM-SECRETS (highlighted secrets)
- Ice Path: the whole puzzle was already in the capture; Water Board: the real mechanic; A failed sim puzzle can now
  turn its room red: SIM-PUZZLES
- The sim publishes a Hypixel-shaped tab list: SIM-SERVER
- The map is a fixed square with the floor's grid inside it; The green room's open faces: SIM-MAP
- Every solver was pointing at Hypixel's height; Three Weirdos is three weirdos now; Lower Blaze was looking for its
  floor at Hypixel's height too: SIM-PUZZLES
- Levers that open a way through, and why they are a table; The prince has a second signature; One mimic, and only
  from Floor 5: SIM-SECRETS
- Trap rooms take your abilities; A use PACKET is not an ability in here; Teleporting up had only one fallback, and it
  was downward: SIM-SERVER
- Boulder was three writes of scenery: SIM-PUZZLES
- Where a diagnostic went in instead of a guess: SIM-PUZZLES (Ice Path), SIM-FLOORS (Archway, blood without the fairy)
- Making the fairy a real stop on the way to blood; A single-room load never armed anything; Rooms that want a
  different spawn than their middle: SIM-FLOORS
- Water Board's gates are its back wall: SIM-PUZZLES
- One held shot is twenty clicks: SIM-SERVER
- The one line that broke every solver in the sim; Hypixel heights hardcoded where the sim moves the floor: SIM-PUZZLES
  (the heights table is in SIM-AUTOPUZZLES)
- Auto puzzles: the sim's Terminator was not a shortbow: SIM-AUTOPUZZLES
- Teleport Maze: the start pad; Tic Tac Toe: the marks were painted where the solver never looks; Creeper Beams: a
  correct first shot was painted as a failure; Quiz (corridor, Oruo's cooldowns, walking in starts it); Boulder: a
  boulder is three blocks tall; Blaze: the solver only looks at armor stands; A button has to be allowed to be a
  button: SIM-PUZZLES
- Etherwarp onto the roof, and what the interactive map actually needs: SIM-MAP
- Verified against the captures this round: SIM-ROOMS (and SIM-PUZZLES)
- Ice Fill's path is feet positions, not ice: SIM-PUZZLES
- Smaller ones from the same round: SIM-PUZZLES (Teleport Maze pairs, Tic Tac Toe), SIM-MAP (predicted position),
  SIM-FLOORS (big rooms repeating, `/goto Higher Blaze`), SIM-ROOMS (Balcony and Archway roof markers)
- The 2026-10-04 list: SIM-SERVER (listener SUCCESS, `requires()`, `freezeWorld`, sidebar), SIM-SECRETS (Secret Aura,
  picked-up items), SIM-FLOORS (room variety, Change Room button, routes filter, All Rooms, single-room map code,
  landings), SIM-MAP (L room on the map)
- The 2026-10-04 Map Logger round: damage, the key, crypts, landings and the sidebar: SIM-SERVER (damage, sidebar),
  SIM-SECRETS ("No Wither Key drop", crypt zombies), SIM-FLOORS (`/goto` landings)
- The 2026-10-04 round: traps, the designer's doors, and the map's etherwarp: SIM-FLOORS (every floor has a trap),
  SIM-DOORS (the sim never builds a wither door), SIM-MAP (the map's etherwarp, off by one, path search speed)
- Blank cells, theoretical wither doors, and the path search, round two: SIM-FLOORS (blank cells), SIM-DOORS
  (theoretical wither doors), SIM-MAP (path search, bench)
- The 2026-10-02 puzzle round; the 2026-10-04 auto puzzle round; the 2026-10-04 puzzle rounds (14:12, 18:01): split by
  puzzle across SIM-PUZZLES and SIM-AUTOPUZZLES
- Capture rotation by vote, and how Hypixel does it: SIM-ROOMS
- The sim answers packets, like Hypixel's server: SIM-SERVER
- The path to blood is laid first: SIM-FLOORS
- Auto Ice Path never shoved, and Auto Boulder had no chest: SIM-AUTOPUZZLES
- The map's etherwarp plans the whole floor at once, by warps: SIM-MAP
- The map's executor checks its landings; Tic Tac Toe, and the Teleport Maze's one loop: SIM-MAP
- The floor bench was not building the game's floors: SIM-FLOORS
- Ice Path, Blaze and Creeper Beams played to the end by their autos; 93-solve round B; Auto Teleport Maze and Auto
  Boulder under the autopilot: SIM-AUTOPUZZLES and SIM-PUZZLES
- The map's etherwarp: block changes, cheaper aims, a denser graph: SIM-MAP
- Ice Fill is judged on the server, every landing; Ice Fill: a broken section is waited out and re-entered: SIM-PUZZLES
  and SIM-AUTOPUZZLES
- A click during the first warm-up: the quick floor graph: SIM-MAP
- Exact floors, and Generate keeps his room: SIM-FLOORS
- Water Board: whole color layers, and the reward chest: SIM-PUZZLES
- The Map Designer's room filters; One Filters panel, and /next only in All Rooms: SIM-FLOORS
- Room clears, star tags and blast radii: SIM-SECRETS
- A single-room rebuild kept the last run's Dungeon Breaker regrows; A new floor carried the last session's starred
  mobs: SIM-FLOORS
- Fewest warps, proved: the exact search: SIM-MAP
- The 2026-10-09 list: pickup range, one boom for every crypt, bats, the prince and four lever doors: SIM-SECRETS
- The seam between rooms was a way out of the map: SIM-DOORS
- Superboom: a real radius, and whole groups: SIM-SECRETS
- Water stands still outside the Water Board: SIM-SERVER
- Two placed levers and Hypixel's Redstone Key: SIM-SECRETS
- Every floor fills its real rectangle; the designer's Load list: SIM-FLOORS
- Deuces' doorway (cold braziers; again; third look; recreated exactly): SIM-DOORS
- Doorways are at one height, and the openings above it are not doors: SIM-DOORS
- /mage: re-capturing one room from a real dungeon: SIM-ROOMS
- 1446 after the re-capture; One-door rooms got a second door through the fill's carve: SIM-DOORS
- Boulder is done when its chest is opened: SIM-PUZZLES
- /reset: a solo room loaded again, and what had to be pinned: SIM-FLOORS
- A room's last secret, got as he leaves, never reached the map: SIM-SECRETS
- Supertall's prince, and the obsidian tomb; Room levers, room by room: SIM-SECRETS
- Moved from CLAUDE.md (2026-10-15): split by topic across every SIM-* file
- Sarcophagus' prince, one Redstone Key, chests that appear like bats; The key takes the room's redstone batch; King
  Midas: the altar is the crypt: SIM-SECRETS
- Live mobs and the trap room counted cleared: SIM-SERVER
- Key rooms: SIM-SECRETS (the sim side; the Auto Clear side is in LESSONS-AUTOMATION.md)

## The Room Recorder was removed (2026-10-04)

killer560: "Remove [the Room Recorder] entirely but remember the code in case we ever need it again." The
recorder (the dev-only F7 loop and `/killer560 roomrecorder` that captured rooms off Hypixel and Ashfall into
`config/.../killer560smod-rooms`) is gone from the source tree. The sim still loads the shipped captures
(`assets/killer560smod/rooms`) and any local ones already on disk; `RoomLibrary` is read-only now. The code
lives at git tag **`room-recorder-last`** (commit 6d4e09f); restore with
`git checkout room-recorder-last -- <paths>`.

Removed files: `roomsim/RoomRecorderFeature`, `RoomRecorderConfig`, `RoomEntryWalk`,
`DungeonInstanceCooldown`, `MissingRoomsHud`, `MissingRoomsConfig`, `SimMeasure` (wither door measuring, only
called from the recorder's scan), `RoomLibraryScreen`, and `gui/tab/RoomRecorderTab`. Also removed: the
capture / rescan / save half of `RoomLibrary` (`capture`, `captureBox`, `captureAt`, `resolveFootprint`,
mob capture, `recordMobSpawn`, `resetForRescan`, `resetAllBroken`, the pending-rescan file, `saveAll`,
`saveDirty`, `toJson`, `floorProgress`, `completeCount`, `expectedCount`, `incomplete`, `Room.cutOff`),
`RoomDatabase.allEntries`, `RunSummaryFeature.puzzleCount()`, `ActionGate.Actor.ROOM_RECORDER_MENU`, the
New-tab entry, both HUD/feature registrations, and its FEATURES.md entry. Restoring it means putting those
`RoomLibrary` methods back as well.

Lessons that only concerned the recorder, kept here in brief:
- `captureAt` replaced a room whose footprint changed with an empty one, and `DungeonLayout`'s grouping on an
  Ashfall line preset merged runs of rooms (up to 11x1), so one scan wrecked 43 good captures. Footprint must
  come from the room database's shape, and a mismatch must REFUSE, not clamp - clamping kept the wrong anchor.
- `Level.isLoaded(pos)` is false for any y outside the build height. Hypixel's dungeon world starts at y 0, so a
  load check asked at -64 captured nothing on Hypixel from 2026-09-29 to 2026-10-04. Clamp the y first.
- Capture skips seen columns, so a complete room is never re-read without explicitly emptying it (the old
  `rescan`), and the shipped copy must not win over a room being rescanned.
