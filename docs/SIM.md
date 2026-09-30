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
  on alternate runs when it could only see a single floor — a test that flaps teaches you to ignore it. It
  needs no Minecraft classes below it either: `RoomDoors` reads only `Room.blocks`/`palette`/`margin`, so the
  whole generator compiles and runs outside the game against data-loading stubs of `RoomLibrary` and
  `SimFloorGen`. That is the way to measure a distribution over hundreds of floors without booting anything.
- **A ROOM COUNT IS NOT A CELL COUNT, and the generator's target has to be cells.** "It still isnt generating a
  full map" was `SimFloorLayout` stopping at `wantRooms` placements: a 1x2 covers two of the 36 room slots and a
  2x2 covers four, so 21 rooms landed anywhere from 25 to 35 cells, median 30, with whole rows of the 6x6 grid
  empty. His own Map Logger scans of 40 real floors put a fully-walked one at 34-36 with 12 of the 40 at exactly
  36. `Floor.cells` is now the target and `Floor.rooms` only a minimum; measured over 500 planned F7s afterwards,
  36 of 36 cells every time with 21-26 rooms. Three things had to come with it, and each was its own defect:
  the attempt scoring has to rank the room MINIMUM above cells, or an attempt that stalled at 20 rooms over 32
  cells beats one that reached 22 over 31; a room's footprint has to be capped at
  `cellsLeft - (roomsStillOwed - 1)`, or big rooms fill the grid with fewer rooms than the floor is supposed to
  have; and a free cell whose every neighbour is a blank wall can never be filled, so `score` costs a placement
  for each one it would strand.
- Preferring small rooms is not free. `choose` sorted candidates by `area * 0.45 - doors * 0.9`, and a
  multi-tile room also has more doorways that can end up facing a neighbour's blank wall — worth -3 each in
  `score` — so between them the layout refused nearly every big room. Reaching 36 cells took 26 rooms until the
  size term flipped sign while the floor is behind the cells-per-room a real floor has, the "good enough, stop
  looking" threshold moved with the extra credit, and multi-tile footprints got credit for the cells they bring.
  A fixed weighting cannot do this: the same preference that fills the grid leaves the last odd cells unfillable.
- Blood was kept away from the fairy room in one direction only: `touchesFairy` stopped blood landing next to
  fairy, but a fairy placed after blood could still end up against it (about 2% of floors). No door was ever cut
  between them, so the blood-rush rule held by luck rather than by construction. The same rule had a second hole
  on the ENTRANCE side, and for a different reason: choosing where the fairy goes respects it (a fairy is only
  attached to a stub at least one doorway in), but the extra-door pass afterwards chooses nothing - it opens a
  door wherever two placed rooms happen to have doorways facing each other, including between the fairy and the
  entrance it was carefully kept a room away from. Never once in 4,000 generated floors, once in 200 with rooms
  pinned, because pinning lays out five times as many candidates and keeps the best. `SimFloorLayout.
  forbiddenPair` now refuses that link outright in both the growth pass and the pin-waking pass.
- **Pinned rooms: the growth's own extra-door pass is what reaches them, so that is where the bookkeeping goes.**
  `SimFloorLayout.generate(..., Map<Integer,String> pinned, ...)` puts each pinned room on the grid before
  anything grows, registers its doorways as stubs so meeting one scores as a match, and marks it *dormant* - it
  may be grown INTO but not out of, or a wing hangs off a room the entrance cannot reach. Waking it was written
  as a separate fixpoint pass, and the pass almost never fired: the growth loop's "any doorway that meets a
  doorway facing back is a door too" block had already consumed the pin's stub and added the link, without
  clearing the dormant flag. So the pin was pruned at the end with the link still pointing at its now-empty
  cells. 63% of single pins honoured and 67 links to an empty cell per 200 floors, against 100% and none once
  the wake moved into that block. The fixpoint pass is still needed, but only for a pin next to the seed and for
  two pins side by side.
- A pin no attempt can connect has to cause a SECOND layout without it. Its cells counted towards the cell
  target the whole way through and the growth stopped on them, so pruning it at the end leaves a floor short of
  target with a hole where it was - one unreachable pin dragged the median F7 from 36 cells to 34 and the worst
  case to 26. `run` lays the floor out again with that room dropped and still reports it.
- Ranking "every pinned room kept" above the cell count is wrong, however much it sounds like what he asked for.
  A two-room floor that happens to hold his rooms then beats a full one that had to leave one out, and that is
  what came back: 5% of the cell target on 14 of 200 floors. A dropped pin costs five cells' worth in the
  attempt score, and the fact that its cells sit there unfilled does the rest of the work.
- `wantBlood`/`wantFairy` recorded the REQUEST, not the room that actually went in. When blood could not fit at
  that stub `choose` returned null, the fallback put an ordinary room there, and the floor was then recorded as
  having its blood room - at the ordinary room's cells, with that room's stubs deleted as if it were the end of
  the run. Rare while nothing is pinned; certain the moment blood is PINNED, because the name is already used
  and that call can never succeed. Check the placed room's type.
- The entrance's rotation was drawn and then thrown away: `commit(entrance, entranceRotation, ...)` passes
  DEGREES, and `entranceRotation` is 0..3, every one of which integer-divides by 90 to 0. So the footprint and
  the position were worked out at the rotation drawn while the room was always committed unrotated - invisible
  because Entrance is 1x1, but its two doorways never turned. Fixed 2026-09-29; measured over 2,000 F7s before
  and after, 36 of 36 cells either way.
- A pinned room is NOT filtered the way a generated one is - not for L shape, and not for the
  Higher/Lower Blaze pair. He put it there, and the drawn-map path has never filtered what he drew. The
  generator still never ADDS the other blaze half, because the pinned name is in `used` and `excluded` reads the
  group against `used`: 0 floors with both in 800 with one of them pinned.
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
- **A sim miniboss the Mob ESP recognises has to be a real server-side PLAYER, and four separate things each
  silently refuse it.** `MobEspFeature.isMiniboss` wants a client `Player` with a **version-2 UUID** and a name
  from its five, so the only route is an AddEntity packet of type `PLAYER` -
  `ClientPacketListener.createEntityFromPacket` builds a `RemotePlayer` from `getPlayerInfo(uuid).getProfile()`
  and, with no `PlayerInfo` for that UUID, logs "Server attempted to add player prior to sending player info"
  and drops the entity. So `SimMiniboss` sends a `ClientboundPlayerInfoUpdatePacket(ADD_PLAYER, boss)` to each
  real connection **before** `addFreshEntity` (ADD_PLAYER alone, so the client's `listed` stays false and it
  never reaches the tab list), and withdraws it with `ClientboundPlayerInfoRemovePacket` once the entity is gone.
  The other three, all verified in the 26.1.2 bytecode: a bare `ServerPlayer` in a level **crashes**, because
  `ChunkMap.addEntity` calls `updatePlayerStatus(player, true)` which reaches the static
  `ChunkMap.markChunkPendingToSend` reading `player.connection.chunkSender` with no null check - which is why it
  extends Fabric's `FakePlayer`, whose constructor installs a `FakePlayerPacketListener` (a real
  `ServerGamePacketListenerImpl` over a channel-less `Connection` whose `send` is a no-op). `isInvulnerableTo`
  refuses **every** hit twice over: `FakePlayer`'s returns a flat `true`, and `ServerPlayer`'s returns true unless
  `connection.hasClientLoaded()`, which a fake listener never gets told. And `ServerPlayer.canHarmPlayer` returns
  false whenever `isPvpAllowed()` is false. All three are overridden in `SimMiniboss.Miniboss`.
- **`FakePlayer.tick()` is empty, and an empty tick makes a player immortal after ONE hit.** `invulnerableTime`
  is decremented in `ServerPlayer.tick` and nowhere else for a player - `LivingEntity.baseTick` guards its own
  decrement with `!(this instanceof ServerPlayer)` - so with nothing ticking it, it sticks at 20 and
  `LivingEntity.hurtServer`'s `invulnerableTime > 10` branch then refuses every later hit of the same size
  (`amount <= lastHurt`). A test that hits the thing once cannot see this. `SimMiniboss.Miniboss.tick` decrements
  `invulnerableTime`/`hurtTime` and discards the entity once `isDeadOrDying()`, and `die()` is overridden to
  nothing so `ServerPlayer.die` never broadcasts a vanilla death message or leaves a corpse awaiting a respawn.
- **Singleplayer packets ARE serialized, so wire limits apply in the sim.** `Connection$3.initChannel` calls
  `configureInMemoryPipeline` -> `configureSerialization`, which installs a real `PacketEncoder`/`PacketDecoder`
  over the local channel. `ADD_PLAYER` encodes the profile name with `ByteBufCodecs.PLAYER_NAME` =
  `stringUtf8(16)`, and `Player.getName()` is the profile name and nothing else (`getfield gameProfile;
  GameProfile.name()`). "Frozen Adventurer" is 17 characters, so that one of the five can never be placed at all
  and `SimMiniboss.place` refuses it by name; the other four fit. Also: `UUID.nameUUIDFromBytes` gives version 3
  and `UUID.randomUUID` version 4, so the version nibble has to be written by hand (byte 6 `& 0x0F | 0x20`).
- **A placed miniboss lands in `ServerLevel.players()`.** `ServerLevel$EntityCallbacks.onTrackingStart` adds
  every `ServerPlayer` to that list, so anything in `roomsim` walking it now sees NPCs: `SimMobs.wakeFels` would
  have had one wake a Fel, and `SimSecretItems` was taking `players().get(0)` as "the player". Both filter on
  `SimMiniboss.isPlaced` now. `Kind.MINIBOSS` is the real thing rather than the 20-HP starred zombie it used to
  be, with that zombie kept only as the automatic fallback when the player entity cannot be built (no player on
  the server yet, an unsendable name, or the `ServerPlayer` constructor throwing) - there is no setting either way.

## Unreproduced: "this room is not part of supertall it is its on 1x1"

His 2026-09-30 screenshot showed a 1x1 drawn as part of the neighbouring 2x2 Supertall. Measured rather than
eyeballed, and it did not happen: scenario 90 plans a floor, publishes it through
`LiveMapFeature.publishSimFloor`, captures it through `DungeonLayout.capture` and compares the two partitions
of the even cells - which placement owns a tile against which map room owns it. Over **240 planned F7s, 160
of them holding Supertall, 8,639 room tiles across 5,263 map rooms: zero merges and zero splits.** Scenario
79 makes the same check on the one floor it really builds.

So the map-code and grouping path is not where it went wrong, and the three mechanisms that could merge two
rooms were each checked and are sound: `SimFloorGen.markRoomCells` fills a rectangle of reserved cells and
never a neighbour's; a connector between two different rooms is left `NO_ROOM` so it is never `Tile.ROOM`;
and `rebuildGroups`' union-by-name is capped at the room's own `shapeTileCount`. Supertall's capture is 65x65
(2 tiles each way) and the database says `2x2`, so it is not a footprint mismatch either.

What is still unexplained is the picture. The likeliest remaining reading is that it was the STALE map from
the single-room bug above - the previous floor still drawn over a world holding one room - which is fixed.
To go further, what is needed is that floor's map code (`SimState.mapCode()`), not another guess.

## The Spirit Sceptre fired all along; it just could not be seen

"the sim spirit scepter doesnt work" (2026-09-30), and his log has six `[Sim] Spirit Sceptre bats fired`
lines from that session. `SimItems.tryUse` was reached and did what it said. What it did was hit whatever was
already inside an `AABB.ofSize(centre, 6, 3, 4)` - no bats, no particles, no sound - and he was standing in a
single-room Supertall sim with no mobs in it, so nothing happened at all.

The box was also axis aligned, so "range" was the X dimension and "width" the Z one whatever the look vector
said: facing east it reached six blocks and spread two each side, facing south it reached two and spread
three. `SimSpiritSceptre` replaces it with five bats that leave the hand two ticks apart, fly the look vector
with a little spread, trail soul flame, and explode in a SPHERE on the first block or mob they meet. The
damage is still server-side and still an approximation, not Hypixel's numbers.

## A room's y band is per room, not a constant

`RoomLibrary.MIN_Y`/`MAX_Y` are the band a NEW capture may use (-64..320). They are **not** the band any
given room occupies: `toJson` trims a capture to its content, so a loaded room's band is whatever its file
says, and `Room.index` measures from `room.minY`. Every read must therefore go through `Room.at(x, y, z)`,
which bounds-checks and returns -1 outside the capture, and every loop over a room's volume must run
`room.minY..room.maxY`. Walking the constants instead asked a room holding y 60..140 for element -135,036
of an 88,209-long array and killed the integrated server on the first floor built (2026-09-29, at
`RoomPlacer$PasteJob.step` and `SimBuilder.collectChests`). `MARKER_Y` is clamped into the band for the
same reason - a marker y outside it places nothing and the uncaptured column is a hole again.

## The generated floor's doors are written in plan(), not linkDoors()

There are two door passes and they are not the same code. `linkDoors` serves the map designer's
**explicitly drawn** floor; the generated floor doors `laid.links()` inline in `plan(floor, puzzles,
blood, pinned)`. A no-loops union-find added to `linkDoors` alone changed nothing measurable, because
scenario 73 plans through `plan()` - all 120 floors still carried 3 to 8 doors more than a tree. Fix the
pass that writes the doors you are testing, and check the count: a connected floor is a tree exactly when
its inter-room doors equal rooms minus one.

Blood and entrance links are placed first and so are never the link refused; an ordinary link that closes
a cycle is dropped. Connectivity survives because a link is only refused when both sides are already
joined by links already kept.

## isBusy() is not a completion signal

`SimBuildQueue.isBusy()` is false both before a build starts and after it finishes, and `progress()` is 0
in both cases too (`finishedWork` is zeroed the moment the queue empties). `generate()` returns as soon as
the world is opening, and the rooms are queued from a later server task, so anything that asks "is it busy"
straight afterwards gets "no" and then measures an empty world. Use `buildsFinished()`, which only counts
completions: snapshot it, ask for the floor, wait for it to change. Eleven testkit scenarios had this bug
and two of them - "0 secret chests" and "sim mobs never spawn" - read for days as defects in the mod.

## A capture's footprint comes from the database, not from the layout

`DungeonLayout` grouping is not trustworthy as a footprint source. On an Ashfall practice preset the rooms
sit in a LINE spanning 47 cells, and when the grouping merges a run of neighbours into one room the bounding
box of the whole run becomes the footprint: a 2026-09-30 scan of every room produced 43 of 135 with a
footprint the room database contradicts, up to 11x1, and each one overwrote a good capture because
`captureAt` replaces a room whose footprint changed with an empty one.

So `resolveFootprint` takes the database's shape whenever it knows one (the six shapes are 1x1, 1x2, 1x3,
1x4, 2x2 and L, so 4 tiles is the hard ceiling), keeping the orientation the world suggested and only taking
the size. It CLAMPS rather than refuses, in both directions: too big captures the one room, too small
captures into the correct larger box and leaves the unseen columns unread so `complete()` stays false.
Refusing would mean a room he has only half-walked could never be captured at all.

Two things made this invisible for a day. `currentFormat()` only asks whether the size is a whole number of
tiles, never whether the tile count could fit; and the generator's `footprintCells` silently finds no room
for an oversized room on a 6-tile grid. A room can be captured, report as captured, and never once appear on
a floor, with nothing logged. If rooms are "missing" from generated floors, check the footprints against the
database before anything else.

## Secret rotation: the translation is right, the DATA is what is missing

Scenario 82 measures how many of the database's chest secrets land on a chest the capture already had, and it
sits at 55-61% against a 70% bar. Before concluding the translation is wrong, this was measured directly
(2026-09-30): every room with database chest coordinates and chests in its capture was tried at all four
rotations across dx/dz/dy offsets of -2..+2, and **the winning transform was dx=0 dz=0 dy=0 in 78 of 79
rooms**. So the coordinate translation has no constant error. The only free variable is the rotation.

Where it does fail is a DATA mismatch, not arithmetic. Seven rooms - Purple Flags, Quartz Knight, Redstone
Warrior, Spider, Supertall, Waterfall, Withermancer - land zero secrets at every rotation AND every offset,
and Red Blue's capture has no chest in it at all. Those Ashfall-preset rooms simply do not hold the chests
the database describes, so nothing the code does can make a secret land on one. Scenario 82's figure is
therefore a mixture of "is the rotation right" (the thing under test) and "does this capture contain the
chest at all" (a property of the source world), and it cannot reach 70% on this library however correct the
code is. Fix the instrument before touching the bar: assert the chosen rotation against the best-scoring
rotation per room, and skip the rooms whose captures hold no matching chest.

What the marker can and cannot do: 116 of 135 captures resolve from the roof marker, because in these rooms
blue terracotta is often the ROOF MATERIAL rather than a single corner marker - Mossy has 76 of them, Pit 105,
Cathedral 544. Two changes on 2026-09-30 took it to 120: `roofLine` now looks for the highest y holding the
MARKER rather than the highest non-air block in a corner column (a taller capture put terrain above the room,
so the old rule looked a hundred blocks above the marker - Redstone Warrior), and `narrowBySecrets` now falls
back to SCORING the rotations and taking a clear winner instead of requiring every secret to land, which
settles Catwalk, Pedestal and Slime.

## Where a doorway's floor is, and three wrong answers about it

`SimDoors.findFloor` decides where the 3x4x7 carve starts, and it was wrong three times on 2026-09-30, each
time in a way that produced a doorway nobody could walk through. Kept as one entry because they are one
story - the band is narrow for reasons, and widening either end reintroduces a specific failure.

1. **Upward from the bottom of the world.** That finds the LOWEST surface in the column, which for any room
   with a basement is the basement floor - so the carve opened a doorway down there and left the real one
   solid. It only ever worked because captures used to stop at y60, which put the lowest surface at the
   walking floor by luck; once rooms were captured at full height with content down to y15, scenario 81
   found two of eight doorways impassable on one floor.
2. **Downward from y90.** Better, but a room's internal upper floor is also a surface with air above it, so
   the carve opened a doorway on the mezzanine. Two impassable doorways became two DIFFERENT ones.
3. **Downward from y75.** y75 is above a doorway's four-block opening rather than inside it, and the seam
   above a door is usually open to the roof - so the first surface met coming down was the top of the door's
   own LINTEL, and the carve cut a perfect opening five blocks over the walking floor. Measured by printing
   the carve volume layer by layer, which scenario 81 now does for every doorway it cannot walk:
   `-1=0 0=13 1=13 2=14 3=14 4=5 5=21 6=21 7=21 8=21` - the 21s are the carve, five layers up.

`DOORWAY_SEARCH_TOP` is **72**, the top block OF an opening whose floor is y69, so the scan starts inside the
opening and walks down to its floor; the bottom is 58, below the floor and above any basement. That covers
every Catacombs doorway, which all sit on the dungeon floor around y68-70. A chest in a room's perimeter at
doorway height is stepped past rather than treated as the floor - Pedestal has one at y70 on its z=0 edge -
because returning y71 left the chest sitting in the opening.

Over three runs of scenario 81 after the third fix the five-blocks-up pattern is gone and impassable doorways
went from 2, 1, 1 of 8 to 1, 1, 0 of 8. What is left is the cluster below.

## The single-room load never published its own map, and the build timer lied about it

Two separate things behind "if i load only a single room make sure it wipes everything else on the map first
and the map should only show the room that i loaded not the previous map" (2026-09-30):

`SimBuilder.buildSingleRoom` wiped the world and never called `LiveMapFeature.publishSimFloor`, which only
`build()` did - so the HUD map, the interactive map and every pathfinder that reads the layout kept the last
FLOOR's twenty-two rooms while the world held one. It now publishes a map of just that room's cells.
Scenario 91 builds an F7, loads one room into the same world, and requires the map to go from 22 rooms to
exactly 1 named room, with a block of the old floor found at the edge of the grid first and gone afterwards.

And `SimBuildQueue`'s "Sim build took N ms" was measuring from the PREVIOUS build. `startedAtMs` was set only
in `submit`, and a single-room load queues its clear first, so the paste then found a non-empty queue and
nobody started the clock. That is where "Sim build took 112851 ms for 1715451 block(s)" came from: 112.8
seconds is exactly the gap back to the floor he had generated two minutes earlier, and his own log's
timestamps put the actual build at about three seconds. Every queue entry point marks the start now. Do not
read a build time from a log older than 2026-09-30.

The clear was still the wrong shape, though, and is fixed too: `ClearJob` read and wrote block by block
through `level.getBlockState`/`level.setBlock` while the paste had been moved onto `RoomPlacer.SectionWriter`
in September. It now walks chunk sections, skips an all-air section in one `hasOnlyAir()` call - about three
quarters of a 385-block-tall band - and writes into the section the way the paste does. Measured after:
1.79M blocks cleared and pasted in 702 ms.

## Open: impassable doorways cluster on the rooms whose doorways cannot be MEASURED

Three fixes to `SimDoors.findFloor` each cut this down and none of them ended it. Measured over four
consecutive runs of scenario 81 on 2026-09-30, with the scenario now naming both rooms:

| run | cells stuck | rooms |
|-----|-------------|-------|
| 1 | 1 of 8 | **Blood** and Atlas |
| 2 | 1 of 8 | **Pedestal** and Logs (blocked by stone brick AND a chest) |
| 3 | 2 of 8 | **Supertall** and Lower Blaze; **Supertall** and Mines |
| 4 | 1 of 8 | **Blood** and Leaves |

Two of those four runs were the lintel bug above, not this. Re-measured after that fix, over three more runs
(2026-09-30): 1, 1 and 0 of 8, still on **Blood** and **Supertall**, so the clustering stands and so does
everything below - there is just less noise on top of it now.

The clustering is the finding. **Blood** and **Supertall** are exactly the rooms the mod already knows it
cannot measure a doorway for: `RoomDoors.of` says "Blood and Higher Blaze are the only two rooms this cannot
measure: their captured perimeters are solid stone at every height", and for those it INVENTS a doorway at
`edge(NORTH, 0)` and relies on the builder carving an opening. Supertall is also one of the captures whose
rotation cannot be determined from the roof marker, and its band runs y0..254, which is not a normal room.

So the likely cause is not the floor search at all, it is the invented doorway: the layout puts a door on a
side of Blood that has no opening, and the 3x4x7 carve is then expected to tunnel through the room's own solid
perimeter. "moved 2.2 across, blocked by stone_bricks" is consistent with a tunnel that starts but does not
finish. Worth checking first whether the carve depth of 7 actually reaches through Blood's wall from the
connector centre, and second whether the invented doorway should be placed on the side the layout wants rather
than always NORTH.

The chest case is separate and partly fixed: `findFloor` used to treat a chest in the room's perimeter as the
floor, returning one block too high and leaving the chest in the opening - Pedestal has one at capture y70 on
its z=0 edge and Archway one at y70, and y70 is exactly where an opening starts. It now steps past a chest to
find the real floor. Run 2 above still shows a chest in the blockage, so at least one variant of that remains,
probably a chest off the connector's centre column.


## Never iterate the level's entity list while removing from it

`clearFloorDrops` did `for (var e : level.getAllEntities()) { e.discard(); }`. That mutates the level's own
`Int2ObjectLinkedOpenHashMap` underneath the iterator walking it, and fastutil does not raise
`ConcurrentModificationException` for that - it throws `ArrayIndexOutOfBoundsException` from inside
`MapIterator.nextEntry`, which looks like anything but the bug it is. killer560 hit it on 2026-09-30:
"it got to the point where it said 1 room remaining then nothing loaded". No crash and no freeze - the
exception killed the build task before it could hand over, so the loading screen simply stayed up.

Collect into a list, then discard from the list. `clearFloorMobs`, immediately below it, already did exactly
that and also wrapped itself in a try/catch so a tidy-up failure can never stop a build; the drop clear now
matches it on both counts.

Every sim scenario missed this because each one builds into a BRAND NEW world, where the clear matches
nothing and so never removes anything while iterating. Reproducing it needs a world that has been played in.
Two attempts at a regression test both gave wrong verdicts - asserting "the build finished" passed on the
broken jar, and asserting "no drops survive" failed on the FIXED one, because summoned drops that land in
chunks the level is not ticking never enter `getAllEntities()` at all, so the count is noise on both. No test
is kept for it: the fix is justified by his stack trace and by matching the correct pattern beside it, and a
test that cannot tell fixed from broken is worse than none.

## Sim mobs are invulnerable to any damage with no entity behind it

`SimMobs.environmental(source)` is `getEntity() == null && getDirectEntity() == null`, and every sim mob's
`isInvulnerableTo` starts with it. That is deliberate and worth keeping: a sim mob has 1 HP, so without it a
practice target suffocates on a low ceiling before he reaches it.

The trap is that `damageSources().magic()` has neither an entity nor a direct entity. The mage beam
(`SimClass.fire`) and the terminator (`SimTerminator.applyDamage`) both used it, so both landed and did
nothing whatsoever — no error, no log line, the mob simply stood there. The sceptre and superboom already
used `playerAttack(sp)` and were fine, which is the usual shape: a fix applied to some of a set.

Anything in `roomsim/` that damages a mob must name the player. Resolve the `ServerPlayer` by UUID inside the
`server.execute` and use `level.damageSources().playerAttack(sp)`.

## FlatTestRoom is one tile and must declare margin 0

A real capture is `TILE + 2 * WALL_MARGIN` across, because it shares a wall column with each neighbour.
`FlatTestRoom` is exactly `TILE` and draws its own walls inside that tile, so it owns nothing outside it — but
it was built before `Room.margin` existed and inherited the `WALL_MARGIN` default. `RoomPlacer` anchors at
`origin - TILE / 2 - margin`, so the entire room was pasted one block negative on both axes.

That is invisible from inside the room, because the paste and its own contents agree with each other. It shows
only when something reads a fixed local coordinate: scenario 70 found a chest where the gold orientation
marker belongs and called it a rotation fault. `createTestRoom` does not set a margin, so any future synthetic
room must set its own.

## One offset for the whole map means every capture's floor must be at the same height

`SimAltitude` shifts the whole floor by a single offset and deliberately never moves a room on its own, so
two rooms whose captures disagree about where the floor is meet at a step. The doorway carve does not fix
that: `carveDoorway` only removes the air space above a floor it searches for, and never lays one. The result
is a doorway with nothing solid in it that the player still cannot cross, because he walks into a trench.

Measured 2026-09-30 by scenario 81: between "Crypt" and "Mines", every column of the doorway at foot and head
height was air, and the floor was missing for the three blocks on the approach side. He walked 3.2 blocks and
fell in. Scenario 92 audits all 135 captures for this in about four seconds and 99 of them agree at y69.

Note "Criss Cross" and "Criss-Cross" are both in the library at the same height - almost certainly one room
captured twice under two spellings.

## Measuring a captured room's floor is harder than it looks

Three different methods each produced a confident list of rooms to rescan that was really a list of the
probe's own mistakes, and all three are recorded in scenario 92's method comment so the fourth reader does
not repeat them.

Scanning DOWN from the top finds the roof: a room is captured up to y140, so its ceiling has open air above
it and passes any "standable" test perfectly - that version reported forty rooms at exactly y100, which is
roof height. Scanning UP from the bottom finds the bottom of the deepest shaft, which put Mines at y38 and
Lava Ravine at y20. Taking the mode of every standable surface still lets the roof win, because a roof is a
clean 31 by 31 slab while a floor is broken up by furniture.

What works is requiring a ceiling somewhere above the surface before counting it. A floor has one; a roof, by
definition, does not.
