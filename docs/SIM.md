# Dungeon Sim - quirks and lessons

Everything learned the hard way about `roomsim/`: the room library and its captures, floor generation,
secret placement, doors, altitude and the sim's own screens. Split out of the project `CLAUDE.md` on
2026-09-29 when it passed 300 lines.

- The dungeon map does NOT work in the sim by itself. `LiveMapFeature` fills its grid from the vanilla map
  ITEM and Hypixel's clay markers; a singleplayer world has neither, so the scan finds nothing and overwrites
  whatever else tried to fill it. The sim calls `LiveMapFeature.publishSimFloor` instead and the scan is
  skipped while `SimState.isActive()`. Anything else that reads Hypixel coordinates in the sim needs
  `SimAltitude.offset()` added to its y - `DungeonLayout.doorBlock`, Secret Waypoints' database y and its
  68..108 lever band all did.
- An L-shaped room is captured as its 2x2 bounding box, and nine of his eleven have a NEIGHBOURING room's
  geometry in the quarter they do not occupy. They are excluded from generated floors until the capture
  records which quadrant is real. Hand-drawn maps are not filtered.
- A room's DOORWAYS are read off the captured blocks (`RoomDoors`), not declared anywhere. A Catacombs
  doorway is 3 wide and 4 high at a tile-edge midpoint, cut through the perimeter wall at `margin` blocks in.
  "Doorway" is not "air": a shut wither door is coal block and a blood door red terracotta, and checking for
  air alone reported Spikes, Staircase and Arrow Trap as sealed. Blood and Higher Blaze genuinely have none
  at floor height and are read as enterable from any side.
- **A capture carries its own rotation, and it is not the database's.** Secret coordinates in the room
  database are relative to the room's CANONICAL orientation; a capture is taken from whatever instance he
  walked through, so each one holds an arbitrary quarter turn. The sim handed `toRealCoord` the rotation the
  room was PASTED at alone, so every non-canonical room put its secrets in the wrong corner - measured
  2026-09-29, only 34 of 135 captures are canonical and 88 of the 122 identifiable ones were wrong. Invisible
  in square rooms, because a wrong corner is still inside the room. `RoomCaptureRotation` recovers the turn
  from the capture's own blue terracotta roof marker (119 of 135), with the database's chest and lever
  positions breaking ties (122). The paste rotation still decides the FOOTPRINT; only the sum decides the
  corner and the translation, so `SimSecrets.clayCorner` takes both separately.
- The room captures only store y 60..140 (`RoomLibrary.MIN_Y`), but 29 of the 167 database chest secrets sit
  below y 60, down to y 28. Those parts of those rooms were never captured, so Catwalk holds 2 chests where
  the database lists 4. Not a translation fault - do not chase it as one.
- Three captures are the wrong SIZE, not the wrong rotation: Deathmite is 2 tiles where the database says 3,
  and Chambers and Raccoon match at no rotation. Re-capturing is the only fix.
- **A room stored at the OLD footprint silently HIDES a good copy of the same room.** `RoomLibrary.Room.
  currentFormat()` filters it out of every count and every paste, but it still sits in the `ROOMS` map under the
  right name, so nothing else can occupy that name. Measured 2026-09-29: `Map Logger`'s `config/killer560smod-rooms`
  held 110 files, 60 usable, and every usable one 1x1 - all 43 old-footprint files were the multi-tile rooms, so
  generated floors there were entirely 1x1 while `26.1.2 (Mod Only Test)` had all 135 rooms and 47 multi-tile. Same
  jar, wildly different sim, nothing on screen saying why. Fixed by shipping the 135 good rooms in the jar
  (`src/main/resources/assets/killer560smod/rooms/` + `index.txt`, 4.6 MB) and merging them in `RoomLibrary.
  mergeBundled`: a usable DISK room wins, an unusable one loses to a usable bundled one, and if neither is usable
  the disk one stays so partial progress survives. A bundled room carries `Room.fromJar` and `saveAll()` skips it,
  so the baseline is never copied into each instance's config folder. When a per-instance library and a filter
  disagree, check which rooms the filter is throwing away before trusting a count.
- An empty `catch` on a per-tick handler is a feature that can stop working with nothing anywhere to say so.
  `SimSecretItems` swallowed every `RuntimeException` from the server tick; it now logs once.
- `SimSecrets`' clay corner is ROTATION-DEPENDENT: NW at 0, NE at 90, SE at 180, SW at 270. It used NW
  always, which was invisible while the generator refused to rotate rooms and threw every secret out of the
  room the moment it did. It is the corner of the TILE area, not of the captured window - the wall margin is
  deliberately not subtracted.
- `DungeonState.toggleSimOverride()` (the `/killer560 sim` command) forces floor, F7 **and boss phase** on
  together, so it shuts the gate on any feature that requires *not* being in the boss. To get a dungeon that
  is not a boss, let floor detection run for real off a scoreboard sidebar line reading
  "The Catacombs (F7)".
- Several features gate on `getCurrentServer().ip` containing `hypixel.net` or `p3sim.net`, with no
  override anywhere in the codebase.
- Server interaction limits, measured on the sim: **4.5 blocks** to a block's box (past it the server refuses
  outright), **3.0 blocks** to an entity (past it the anticheat names the distance). `MEASURED_MAX_REACH` and
  `MEASURED_MAX_ENTITY_REACH` in `CheatUtilsConfig` are the single places those live. The entity figure was
  re-measured at 0.1 resolution on 2026-09-29 (scenario 85, player placed rather than walked): **3.00 draws no
  flag, 3.10 does** - so 3.0 is right and is exactly on the edge. Confirmed in the same run that 4.50 to the box
  is 5.08 to the CENTRE, which is why a centre-measured 4.5 limit silently refuses legitimate blocks.
  Audited 2026-09-29: 22 sites carried their own number, all inherited from QUOI and none tied to the
  measurement - 6.0 in the puzzle chest auras and Auto Croesus, 5.48 in Weirdos/Water/Tic Tac Toe/Auto Routes,
  4.0 for a terminal (an ENTITY click, so the limit is 3.0), 4.7 for essence skulls, and clamps at 5.5 and 6.0
  that let a saved config keep an unsafe value even after the default moved. All now derive from the constants.
- The dungeon sim (`roomsim/`) is the ONE place this mod writes positions, and that is correct there: the
  no-direct-movement rule exists because Hypixel reconstructs your movement and lags you back, and in the sim the
  integrated server is ours. Everything sim-only gates on `SimState.canAct`, which requires a singleplayer world
  AND no connected server. If that gate is ever wrong, those files write positions on Hypixel - treat it as the
  single safety boundary of that package and do not add a second way in.
- **A negative y is a valid y in the sim.** A bottom-aligned floor occupies y -63..17, so `landing = -1` as a
  "not found" sentinel made `SimBuilder.snapPlayerTo` throw away every spot it found and drop him in at
  `maxWorldY` to land on the roof (2026-09-29). Use a flag. `SimDoors.findFloor` was already right.
- A teleport that walks its bounding box along the look vector must SLIDE when only the vertical part is
  blocked. Standing on a floor, the box's bottom face is on the floor's top face, so any downward look made
  `SimAbilities.dash`'s first 0.25 step collide and refused the AOTV teleport outright - 14 refusals in 10
  seconds of play. Keep the horizontal part and carry on at the starting height.
- Synthetic sim rooms live in `RoomLibrary`'s separate `TEST_ROOMS` map, are never saved, never counted and never
  listed as missing. A synthetic room in the real map would be written to disk by `saveAll()` and would end up in
  the shipped library looking exactly like a captured one.
- No public dungeon dataset ships room GEOMETRY (checked 2026-09-28). Dungeon Rooms Mod and its kind store secret
  coordinates plus room identification, which a waypoint mod needs and a sim cannot use. DRM is also GPL-3.0
  against this mod's MIT, so its code can never be used here - data only, credited, and only with his say-so.
- A room's captured size is `tiles * 32 + 1` (`RoomLibrary.footprint`), and the ONLY inverse is
  `tiles = (size - 1) / 32`. Two places had their own: capture read a room's grid-cell span as a tile count
  (a 3-tile room captured 157 blocks long instead of 97, running 60 columns into the next room), and
  `SimFloorGen.cellFootprint` read the captured size as a tile span and halved it. Those two errors cancelled
  exactly, so fixing one alone produced a worse bug than either — a 2-tile room planned into one cell and
  pasted over its neighbour. `RoomLibrary.cellFootprint(name)` is now the single inverse; scenario 73 asserts
  every room covers exactly its captured footprint.
- Map-code room ids are per PLACEMENT, never per name. They were deduplicated by name, and `SimBuilder` pastes
  by flood-filling cells with the same id — so two placements of one room standing next to each other merged
  into a single smeared paste. Non-adjacent duplicates survived by luck, which is why it usually looked fine.
- `SimFloorGen.plan(...)` lays out a floor without touching the world, so a scenario can assert over a hundred
  floors instead of one. Built because the "does it ever place a multi-tile room" assertion passed and failed
  on alternate runs when it could only see a single floor — a test that flaps teaches you to ignore it.
- Verify Skyblock item ids against Hypixel's own list (`api.hypixel.net/v2/resources/skyblock/items`), not
  against the name or memory. Three were wrong at once (2026-09-28): the Spirit Sceptre is `BAT_WAND`, not
  `SPIRIT_SCEPTRE`, which broke both the sim item and the RNG meter's auction price lookup; `ClearNode` had
  `ASTREA` for `ASTRAEA`; and Auto Debuff's `equals` missed `STARRED_MIDAS_SWORD`. Exactly 30 items have a
  `STARRED_` form and the wither blades are not among them, so "strip STARRED_" and "treat the blades as one
  item" are separate fixes. `ItemIdentity.family()` is the one place that knows both.
- `DungeonLayout.name(room)` returns the literal `"Unknown"` for a room it has not identified yet - a
  placeholder, not a name. `RoomLibrary.capture` only rejected null/blank, so the first live scan (2026-09-28)
  wrote `Entrance.json` and `Unknown.json` identical in all 77841 block positions and reported "2 of 2 rooms
  complete" for one room seen twice. Every unidentified room shares that one placeholder, so the real damage
  was the next one overwriting it and producing a file holding half of two different rooms. Check for the
  placeholder, not just for blank, and distrust a room count that has not been diffed.
- The sim menu runs from the MAIN MENU, where there is no world yet. Every builder had a `server == null`
  branch that returned silently or opened an empty sim with a "run the command again" message, so nothing the
  menu offered ever built anything (found 2026-09-28: "no room ever loaded"). `SimWorld.open` now takes the
  build as a callback and runs it once the world exists, behind `SimLoadingScreen`. Any new entry point must
  go through that, not call a builder directly.
- A second Interactive Map goal cannot simply be issued over a running one. `ClearExecutor.etherPath` returns
  immediately while `pathPending`, and even when it does plan, it plans from the position you were at when you
  pressed - `ClearNode.inside` needs you within 0.32 blocks of the first hop, so once you have warped off that
  spot the new queue is inert and `isBusy()` never clears. A new goal must `cancel()` and then be issued from a
  later tick. `cancel()` also had to stop clearing everything *except* `syncDelay`, which kept `isBusy()` true
  for up to 49 more ticks with no completion callback left to run. Anything that holds a goal across those ticks
  must publish its own "still steering" flag: `isBusy()` is false for the whole wait by design, and Auto Routes'
  interlock 5 reads exactly that, so a node underfoot would arm in the gap and steer against the warp about to
  start. `InteractiveMapFeature.isSteering()` is that flag.
