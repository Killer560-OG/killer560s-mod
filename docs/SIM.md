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
  by weighing the roof marker, the lapis corner and the database's secrets (see "Capture rotation by vote"
  at the end); every capture the sim will place gets a clear answer except Archway, Balcony, Catwalk and
  Purple Flags, which are mis-captured rather than mis-rotated. The paste
  rotation still decides the FOOTPRINT; only the sum decides the corner and the translation, so
  `SimSecrets.clayCorner` takes both separately.
- The room captures only store y 60..140 (`RoomLibrary.MIN_Y`), but 29 of the 167 database chest secrets sit
  below y 60, down to y 28. Those parts of those rooms were never captured, so Catwalk holds 2 chests where
  the database lists 4. Not a translation fault - do not chase it as one.
- Three captures are the wrong SIZE, not the wrong rotation: Deathmite is 2 tiles where the database says 3,
  and Chambers and Raccoon match at no rotation. Re-capturing is the only fix.
- **A room stored at the OLD footprint silently HIDES a good copy of the same room.** `RoomLibrary.Room.
  currentFormat()` filters it out of every count and every paste, but it still sits in the `ROOMS` map under the
  right name, so nothing else can occupy that name. Measured 2026-09-29: `Map Logger`'s `config/killer560/dungeons/sim/killer560smod-rooms`
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
- **A rebuild from INSIDE the sim must hand `SimState` the new map code.** `SimWorld.open` skips the world reload when
  he is already in the sim, and only the world-load path called `SimState.enter(code)`, so after a second floor every
  `SimState.roomNameAt` (the trap-ability rule, the sidebar Room line, the tab list) decoded the FIRST floor: he was
  told "No abilities in a trap room" in Atlas and the sidebar said Atlas while he stood in Museum (2026-10-05, his
  Map Logger log line "already in the sim world"). The shortcut now calls `SimState.replaceMapCode`; scenario
  99-sim-im builds floor A then floor B in-world and checks every tile's name.
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

## A crypt wall is NOT a solid slab of cracked brick, and decoration is speckled

Measured 2026-09-30 by decoding all 135 shipped captures in
`src/main/resources/assets/killer560smod/rooms/`, which is the only honest way to answer "why does Superboom
delete one block".

- The library holds **119,343 cracked stone bricks** and 3,666 chiseled. Walked as orthogonally connected runs
  over `SimItems.isFragile`'s own block set, that is **90,226 separate clusters, 71,260 of them a SINGLE
  block** (79%) and only 661 of six blocks or more. Cracked brick is Catacombs' floor and wall SPECKLE, not a
  crypt marker. So `connectedFragile` breaking one block is the correct output of a correct flood fill aimed at
  decoration, and it is what he will get almost every time: only **411** cracked bricks in the whole library
  seal a small air pocket, against 119,343 that do not.
- A real crypt wall is **mixed**. `Redstone_Crypt` at room-local x=25, z=27..29, y 69..72 is
  `cracked_stone_bricks` with `stone_bricks`, `mossy_stone_bricks` and plain `stone` set through it. A fill that
  may only step on fragile blocks cannot cross those, so it takes 8 blocks of that wall at best and 1 at worst.
  Over all 411 chamber-sealing bricks: median **2** blocks broken, and the chamber ends up joined to the room
  **205 of 411 times (50%)** — so even aiming dead on a genuine crypt, half of them stay sealed.
- What does work is a thin SLAB across the wall rather than a run of fragile blocks: flood the stone-brick
  family (cracked, plain, mossy, their stairs and slabs) bounded to 2 blocks across the face he is looking at
  and 2 deep into it. Median **34** blocks broken, chamber opened **362/411 (88%)**. Adding plain `stone` to the
  set lifts it to 95% at 51 blocks, but a cracked brick set into a stone wall then punches into the room's own
  stonework, which is the "it shouldnt just break blocks in its way" complaint again.
- **`Crypts: 0/5` on the score HUD can never move in the sim.** That readout is
  `ScoreCalculatorFeature.getCrypts()`, which is only ever written from the **TAB LIST**
  (`TAB_CRYPTS` in `pollTabList`). `SimScore.cryptBlown()` feeds `SimScore.summary()` and nothing else, and
  `SimSidebar` writes a scoreboard sidebar with no crypt line at all. So "no crypt spawns" is partly a counter
  that is not wired to the sim, independent of whether a crypt opened.
- A crypt DOES exist behind the wall - it is not a missing feature. The chamber is part of the capture, because
  it was captured out of a real dungeon: 70 of the 135 rooms hold at least one cracked brick sealing a pocket of
  48 air blocks or fewer. What the sim has no record of is WHICH brick, so `sealsAChamber` has to re-derive it at
  detonation time and that test has false positives (gaps under stairs, voids inside stonework, pockets behind
  leaves). Nothing in a capture says "crypt".

## The Terminator's three arrows were hitscan, so there was nothing to see

killer560 (2026-09-30): "The terminator still does not shoot 3 arrows or shoot like a normal shortbow would."
It already fired three and already needed no ammunition; `SimTerminator.shoot` said so in its own javadoc.
Three invisible arrows and no arrows look identical from where he stands. Fixed by spawning real
`net.minecraft.world.entity.projectile.arrow.Arrow` entities on the integrated server thread at `shoot(dx, dy,
dz, 3.0f, 0.0f)` — 3.0 is `BowItem`'s fully-drawn velocity — with the hitscan damage deleted, because leaving
both would double-hit invisibly on 1 HP mobs. Salvation's arming moved onto
`ServerLivingEntityEvents.ALLOW_DAMAGE`, the hook `SimSurvival` already uses, filtered on the damage's DIRECT
entity being a private `TerminatorArrow` subclass; distinct-mobs-across-shots is unchanged. The subclass is the
whole tagging mechanism (no UUID bookkeeping to leak), its entity type is still `EntityType.ARROW` because the
four-argument `Arrow` constructor hard-codes it, and it discards itself after 10 ticks in the ground so four
shots a second do not carpet the floor. `Arrow`, `AbstractArrow`, `AbstractArrow.Pickup` and
`Direction.Axis.choose(int,int,int)` are signature-identical in the 26.1.2 and 26.2 merged jars, so none of this
needs the compat facade.

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

## A doorway can be completely clear and still impossible to cross

`carveDoorway` removes the air ABOVE a floor it searches for. It never lays one. So where two rooms meet at
different heights the connector has no floor, and the player walks into a hole - while every report says
"nothing solid across the seam", which is true and is the point.

Three explanations were possible and two were eliminated by measurement rather than argument:

- **Missing capture data.** Ruled out. Scenario 92 counts unread columns exactly and every column of all 135
  rooms was read. Note `RoomLibrary.complete()` could NOT have ruled it out - it is completeness >= 0.999, and
  on a three-tile room (9409 columns) nine unread columns still passes, nine being exactly the size of the
  hole that was measured.
- **The carve punching through a wall** where a room has no doorway. Ruled out. `SimBuilder` now audits every
  carved door against both rooms' rotated masks at build time, and it reported "all 21 doors are backed by a
  measured doorway in both rooms" on a run that still had an impassable one. The audit stays, because it is
  what would catch this case if it ever does happen.
- **The rooms genuinely meeting at different heights.** What is left, and what the carve now handles: it lays
  stone bricks at `floorY - 1` across the 3x7 wherever that is not already solid. In practice that is 9 to 79
  blocks a floor, so the holes were common. Only the one layer, and only where it is air, so a room's own
  floor is never overwritten.

The room-wide capture heights are a SEPARATE observation and do not explain any of this: Crypt, Mines,
Balcony and Archway all pass scenario 92. A room can sit at y69 over most of its area and still be two blocks
low in the corner a door lands in, which is exactly what a modal measurement cannot see.

## Three different bugs were all reporting as "one doorway in eight is impassable"

Worth knowing before chasing the next one, because each looked identical from the verdict line and only the
numbers told them apart.

- **The report named the wrong block.** `seamBlocks` scanned the seam +/-2 while a doorway is three wide, so
  every "blocked by ..." it ever printed was naming the door FRAME. "blocked by red_carpet" was a carpet in
  the frame, and a carpet cannot stop anyone.
- **The player was placed in mid-air.** The walk started at a fixed four blocks back on a fixed side, which
  outside an Entrance room at the edge of the map is open space: "feet=air head=air under=air", he fell,
  moved 0.2 across, and it was filed as an impassable doorway over a perfect one. The scenario now looks for a
  standable spot on either side and says so when there is none.
- **The crossing was measured along +X/+Z rather than along travel.** Added with the two-sided approach: a
  clean crossing from the positive side reads as "moved -16.5 across" and fails.

And a fourth that is not a bug at all: a doorway whose opening sits one block above the approach. Walking
cannot climb a full block, so the harness stopped; in game he hops over it. The walk now holds jump as well,
which still leaves a two-block step and a real wall failing.

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

## The 2026-09-30 round

**A flag set before `SimWorld.open` does not survive it.** Opening a sim world unloads whatever world is
open, and `SimWorld.onWorldUnloaded` calls `SimState.leave()`, which clears `generatedFloor`. So
`SimFloorGen.generate` setting it and then opening the world left it true only for the first floor of a
session - which is why the Dungeon Breaker's pre-start lock kept not engaging. Anything per-map belongs in
`SimBuilder.build`, which runs after the world is up and which all five floor-building paths go through
(`SimFloorGen.generate`, `SimGenerator`'s two map-code entries, the map editor, `SimRunHistory`'s rebuild).

**The roof marker is evidence, not an answer.** `RoomCaptureRotation` trusted it outright whenever exactly one
corner carried blue terracotta, and on his floor it named the wrong corner for Waterfall, Skull and Purple
Flags. All three are long rooms, and a quarter turn on a long room swaps its long axis, so the database's
secrets were rotated across the short side and fell out of the room - Waterfall lost seven of its eight. The
database's own secret coordinates bound the room's canonical size, so a turn that cannot fit them inside the
capture is provably wrong whatever block sits in that corner; that veto now runs before the marker is
believed. Square rooms admit all four turns and are untouched.

**Shape strings and capture footprints are in different frames.** Every long room in `rooms-modern.json` is
written `1xN`, and its secret coordinates run along **x** - so `1x4` means four tiles along x, not one. Of his
27 non-square captures, 26 also run long along x and one (Gold) does not. Comparing the shape STRING with the
capture's `sizeX`/`sizeZ` will tell you 26 rooms are turned when they are not; compare the secret
coordinates' extent instead, which is convention-free.

**`SimBuildAudit` is the only check in the build that reads the world.** The door audit, the secret audit and
the map all compare the build's inputs against each other, so a paste that wrote the wrong blocks passed all
three in silence. The audit samples each room's footprint after the last block lands and compares it with the
capture. A healthy room does not score 100% - doorways are carved, unused ones bricked up, secrets written in
- and two captures of *different* rooms still agree about 90% of the time because rooms share walls and
floors, so the threshold is 70%: a smoke alarm, not a ruler.

## Captures that hold another room's blocks (2026-09-30)

**The builder was never the problem.** Reading the 16:28 floor straight out of its region file and scoring
all 36 cells against all 134 captures at all four rotations put every cell on the room the sim named, at
99.9-100% against a best rival of 21-91%. Footprints tile the grid exactly, no overruns, no double claims.
`RoomPlacer`, the layout and the live map are all correct, and three separate hypotheses about them were
wrong.

**What was wrong was the (now removed) Room Recorder's footprint clamp**, which anchored a capture on a
mis-grouped run of cells - how Waterfall's capture came to be a tile of Catwalk, then Waterfall, then
nothing, then the whole of Rare Overgrown. The shipped captures still carry those mistakes.

**Measured, so it can be re-measured:** 34 tiles across the 134 captures are block-for-block a tile of a
different room, and 12 are nothing but air. Compare tiles over y 66..99, the dungeon's own floor-to-roof
band - comparing each room over its own captured band finds only 5 of the 34, because the duplicates differ
in how far below the floor they were recorded, not in the room itself. When two rooms share a tile the one
with MORE tiles is the corrupt one: a 1x1 box cannot span a run. `RoomTileAudit` does this at every load and
makes the offenders unusable, so they are never placed.

**A correction to the 16:22 commit.** It removed `Criss-Cross.json` as "97.4% solid on one side, 2.6% on the
other - literally half a room". That measurement used the wrong array index order (`RoomLibrary.index` is
y-major, `(y-minY)*sizeX*sizeZ + z*sizeX + x`) and was really measuring y-halves; done correctly the capture
is 48.3/51.7 and perfectly ordinary. It stays removed for a different and real reason: `Criss-Cross` is not
a name the room database knows, `Criss Cross` is, so it was a stray capture under a name nothing can look
up. Its stale line is gone from the bundled index too, which was warning on every boot.

## Small ones from 2026-09-30

**A filter that matches a room's NAME cannot find a room TYPE.** The Load a Room picker's Puzzles toggle
filtered on `name.contains("puzzle")`, and no Catacombs puzzle room is called that - they are Boulder,
Quiz, Ice Fill, Water Board. It asks `SimFloorGen.typeOf` now. The room database has 140 entries: NORMAL
108, RARE 12, PUZZLE 11, CHAMPION 4, TRAP 2, BLOOD 1, ENTRANCE 1, FAIRY 1, all upper case. It also loads on
a background thread and `typeOf` answers NORMAL until it is ready, so any screen filtering by type has to
rebuild when it arrives or it shows the empty state for the first second and looks exactly like the bug.

**The Dungeon Breaker is a LEFT-click tool.** It had a case on the right-click path as well, so
right-clicking anything while holding it broke the block instead of opening it - and because
`UseBlockCallback` was consuming every click whose item merely had a Skyblock id, the chest would not have
opened even without that. Ask whether the id consumes a right-click before consuming it, and keep that
question in a method: the callback is a COMMON event and the server side has to reach the same answer
without running the ability a second time.

**"The secrets are highlighted with my setting off" was not Secret Waypoints at all.** `SecretWaypointsFeature` gates correctly on `SecretWaypointsConfig.isEnabled()` (which ships false) and nothing in the repo ever writes that field except the GUI tab, so the config was never the culprit. The highlighter was `SimMimicRenderer`, a sim-only `AFTER_TRANSLUCENT_TERRAIN` callback whose entire gate was `SimState.canAct() && SimMimic.hasMimic()`. It outlined every mimic CANDIDATE within 40 blocks in amber, and `SimSecrets` registers each placed secret chest as a candidate (`SimMimic.addCandidate`) on top of every chest baked into a capture - so "the chests that could be the mimic" was, in practice, every secret on the floor. Deleted 2026-09-30 rather than re-gated, at his request: the sim now has no highlighter of its own and Secret Waypoints is the only thing drawing secrets in there. When a feature looks like it is ignoring its setting, check whether a SECOND renderer is drawing the same thing - this one was in a different package, on an earlier render pass, and never consulted the config at all.

**A sim weapon that is hitscan is a sim weapon that fires nothing, as far as he can tell.** The Terminator
read to him as firing none.

**Correction, 2026-10-01: it was not firing them invisibly. It had never fired one.** This section used to say
the Terminator "has fired three arrows since it was written, invisibly". His log from the Map Logger instance
shows otherwise, on every shot:

```
java.lang.IllegalArgumentException: Invalid weapon firing an arrow
  at AbstractArrow.<init>(AbstractArrow.java:126)
  at Arrow.<init>(Arrow.java:38)
  at SimTerminator$TerminatorArrow.<init>(SimTerminator.java:406)
  at SimTerminator.lambda$shoot$0(SimTerminator.java:249)
```

`TerminatorArrow` passed `ItemStack.EMPTY` as the weapon argument, and 26.1.2's `AbstractArrow` constructor
refuses it. The throw happens inside the `server.execute` lambda, so it never surfaced as a crash - it landed
in the log as an `Error executing task on Server` and the weapon simply did nothing. `arrowsFired` could not
increment either, so the counter that was supposed to prove the weapon acted had nothing to count and said so
in a way nobody read. Fixed by passing `new ItemStack(Items.BOW)`, which is what fires these on Hypixel.

Two lessons. A claim that a feature "works, invisibly" needs the log checked before it is written down; this
one was wrong for three days and absorbed two rounds of work on the rendering instead. And **an exception
thrown inside `server.execute` is silent to the player** - every sim feature that queues work there and
reports success from the client side can report success for something that never ran.

## Ice Path: the whole puzzle was already in the capture

`SimIcePathPuzzle` is the ninth sim puzzle and the only one that arms a captured room **without writing a
single block**. Decoding `assets/killer560smod/rooms/Ice_Path.json` and mapping
`IcePathSolverFeature`'s own coordinates into it settles every part of the layout, at database rotation 180:

- the 17x17 board's 289 cells all have `packed_ice` under them at relative y 66, the y the solver draws its
  path at, and the maze is 17 `polished_andesite` pillars at y 67, where the solver reads its walls;
- the border ring around the board is 65 of 65 `polished_andesite`, and **the three cells the ring is missing
  are exactly the solver's exit columns** - row -1, columns 7 to 9. The solver's goal is a real hole in a real
  wall, not a convention;
- the other three rotations put 33, 5 and 4 of those 65, so the ring is what identifies the room, and it
  agrees with the `blue_terracotta` roof marker `RoomCaptureRotation` reads (high-x/high-z corner, i.e. 180).

So arming it is a board read and one entity. **The silverfish is the only thing a block capture cannot hold**,
and nothing in this repo bundles a spawn for it - `IcePathSolverFeature` finds the live one and has no table.
The start cell is therefore chosen by rule: the open cell needing the most shoves to escape, of those that can
escape at all, ties to the lowest row then column. That rule is worth the arithmetic because a fixed guess is
worse than it looks - the obvious "middle of the entry side", cell (16, 8), **slides straight out in a single
push** on this very capture. The rule picks cell (9, 0) and a 16-shove solution, and it can never pick a cell
with no solution.

**One stray block made 26 cells unsolvable, and the board is otherwise exactly right.** killer560 marked the
real room up cell by cell on 2026-10-01 against the decoded capture. Sixteen of the seventeen walls matched;
the capture carried ONE the real room does not have, at board {@code (15,16)}. With it, 26 of the open cells
cannot reach the exit at all - without it, every one of the 273 can, which is what a real Hypixel puzzle looks
like. So "zero unsolvable cells" is now the cheap check on whether a decoded Ice Path board is right, and a
decode that leaves dead cells should be suspected rather than shipped.

He also settled two things that had been guesswork: there is exactly ONE Ice Path layout, and he enters from
row 16 with the chest past row 0 - the orientation `IcePathSolverFeature` already indexes. Because the layout
is known, `SimIcePathPuzzle.bindAt` now CONFORMS a bound room's maze layer to it instead of taking the capture
as found, and logs how many cells it had to correct. The silverfish starts at (15,15), ten shoves out, checked
for solvability at arm time rather than trusted - his first reading of the spot, (14,16), was one of those 26
dead cells, and a silverfish that can never escape looks exactly like one that works.

**A punch must not be a hit.** The shove arrives on `AttackEntityCallback` returning `InteractionResult.FAIL`,
which fires before any damage logic, so the silverfish is shoved and never hurt - otherwise eight HP of
punching kills the puzzle. An arrow is picked up separately, by looking for an `AbstractArrow` against the fish
while it is at rest, and the direction comes from the **arrow's yaw, not its flight**: Auto Ice Path shoots
straight down from on top of the silverfish, so the flight direction says nothing and the yaw says everything.
Tested 2026-10-06 by testkit `94-sim-icepath-shove` on 26.1.2 and 26.2: standing on its cell, an empty-hand punch
and a Terminator shot straight down each slid it one cell the way he faced, and its health stayed 8.0 of 8.0.

**`forget()` has to drop the placed-block list too.** `SimIcePathPuzzle` and `SimIceFillPuzzle` are the only
two puzzles that place their own standalone arena, and both kept `placedBlocks` across `forget()`. Since
`armFloor` calls `forget()` for every puzzle a new floor does NOT hold, that left a later `/simpuzzle reset`
queueing air at a few hundred absolute positions now sitting inside a freshly built floor. Both drop it now;
the callers that genuinely want those blocks removed call `clearPlaced()` first, which queues the writes and
empties the list itself.


## Water Board: the real mechanic, and what the capture does and does not hold

**The capture settles the geometry; it cannot settle the redstone.** `Water_Board.json` decodes cleanly at
database rotation 270 (`capture (cx,cz) -> db (31-cz, cx-1)`), and every piece of furniture the live
`WaterSolverFeature` indexes is in it at the position that solver already uses: 7 of 7 levers at
`(20|10, 61, 10|15|20)` plus the back lever at `(15, 60, 5)`, each ore lever mounted on its own matching ore
block one block further out; the five colour columns at `x=15`, `z=15..19`, each with its wool at `(15, 55, z)`
standing on an up-facing sticky piston at `(15, 54, z)`, pushing into `(15, 56, z)` - which is exactly the block
the solver's `extendedSlots` scan tests; and the terracotta identifier marker at `(14, 77, 27)`, so this capture
is **board 0**. The room's water is a sealed column: a source at `(15, 62, 3)` feeding `(15, 59..62, 4)`, boxed
in by sea lanterns at `x=14/16` and andesite at `z=5`.

What is NOT in it is the mechanism. A capture is one frozen frame, and in this one **every colour is retracted**
(all five of `(15, 56, z)` are air) and there are no pistons behind the lever walls at all - Hypixel moves those
blocks by `setblock`, not by redstone a capture could record. So the sim cannot replay the real board; it can
only drive the same visible parts.

**Three of five colours have to be pushed out or his solver sits out.** `WaterSolverFeature.scan` refuses to do
anything unless exactly three of the five read as extended AND an identifier marker matches. A captured room
fails the first test, which is the whole reason the Water Board solver never lit up in the sim.
`SimWaterPuzzle.bindAt` therefore picks a board the bundled `water-solutions.json` actually contains (`"012"`
first - every identifier carries it), pushes those three colours out itself, and then drives its own rules from
`WaterSolverFeature.bundledClickOrder` for the same board. It also reads his own **Optimized Path** setting,
because that switches the whole sequence: a sim that ignored it would score him against a different solution
from the one on his screen.

**The bundled data kills the obvious model.** "One timed click opens one colour" holds for only 6 of the 80
bundled boards - the non-zero click counts run from 1 to 10 against always exactly 3 colours. And `water` is not
always a single click: 12 of the 80 boards flip the back lever a SECOND time at a non-zero time, which is the
flow being stopped and restarted mid-solution. So the sim spreads the three colours evenly across however many
timed clicks the board has, nearest the entrance first, and treats a later `water` entry as an ordinary timed
click that also toggles the flow. Both of those are the sim's own choice and are written as such in
`SimWaterPuzzle`'s class doc.

**Starting and stopping the water needs no fluid simulation.** Letting water out into the room would flood the
floor within seconds. Instead the water is removed when the flow is off and put back when it is on, with
`UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS` so nothing schedules a fluid tick. Binding turns it off, so the
first click of every attempt is the back lever - which is what every bundled solution says anyway. **Which water
was corrected on 2026-10-02** - see "The 2026-10-02 puzzle round": it is the board's top feed, not this column.

## A failed sim puzzle can now turn its room red

`DungeonMapScanner.STATE_FAILED` comes from the vanilla dungeon map ITEM: Hypixel sets a failed puzzle's centre
map byte to 18 (red) while its side byte stays 66 (purple), which is the pair `case 18` decodes. A sim has no
map item, the scanner is never calibrated, and every room painted as plain "discovered" - so there was no way
for a failed sim puzzle to show. `SimRoomState` is the sim saying so directly, keyed by room NAME because that
is what a puzzle knows about itself and what `MapPainter` already resolves a `RoomGroup` to. Cleared when a
floor is built and when an Architect's First Draft resets the puzzles, which is exactly the behaviour he asked
for ("If i then use an archetechs draft itll fix it and restart it").

**It is the NAME that goes red, not the square.** killer560, 2026-10-01: "no not the square the text should
become red." That needed nothing drawing-side - every label is already coloured `stateColor(visibleState(group))`
and `STATE_FAILED`'s colour is `0xFFFF5555` - so reaching the state is the whole job and a tint on
`roomColor` was wrong. Worth remembering before "make X show on the map" turns into a painter change: the
state-driven colours are already wired, and most of these asks are about a state nothing in a sim can set.

`SimPuzzles.reportFail` also gained a room-name overload: six of the seven failable puzzles live in a room named
after them, but `SimQuizPuzzle` runs both Quiz and Three Weirdos and was hardcoding "Three Weirdos" into the
chat line of both.

## The sim publishes a Hypixel-shaped tab list

`DungeonInfoFeature`, `ScoreCalculatorFeature`, `RunSummaryFeature`, `PartyTracker` and others take secrets,
crypts, rooms, deaths, puzzles and the team from Hypixel's TAB LIST display names. An integrated server lists one
player, so for a while both HUDs carried a `SimState.isActive()` branch reading `SimScore` instead - a client
feature special-casing the sim. Since 2026-10-04 `SimTabList` sends the tab list from the integrated server
(80 fake player-info entries `!A-a`..`!D-t` plus a header/footer packet) and those branches are gone. The
player-info packet has NO public constructor taking entries (every one takes `ServerPlayer`s, javap 26.1.2 and
26.2), so it is built empty and filled through the `@Mutable` accessor `SimPlayerInfoPacketAccessor`.
`SimScore.roomCleared()` and `died()` are still never called, so "Completed Rooms" and "Team Deaths" read 0.

The first sim branch exposed a real bug underneath: the sim's secret TOTAL was `SimMimic.candidateCount()`, the number of chests
that could have been the mimic. It is a different quantity, and it ignores bats, essences, items and levers.
`SimBuilder` now sums each placed room's `RoomEntry.secrets` - the same number the map prints beside a room's
name.

## The map is a fixed square with the floor's grid inside it

The 2026-10-01 version cut the HUD panel to the bounding box of the rooms REVEALED so far (`MapPainter.panelUnits`
/`autoFit`), so on a real floor the box changed shape and every room shrank each time a far room came into view -
killer560, 2026-10-04: "It shouldn't do this weird fill in and shrink in real dungeons." Both are gone. The outline
is always `MAP_UNITS` square at the Room Size setting, and `MapPainter.floorFit` scales and centres the floor's
whole grid inside it from `DungeonState.getFloor()` alone (`MapPainter.floorRooms`: E 4x4, F1 4x5, F2/F3 5x5, F4
6x5, rest 6x6, M floors as their number - from `DungeonMapScanner.calibrate`'s start corners and the FloorSizeLog
samples). The sim reports M7 and always lays out on the 6x6, so it gets 6x6. A drawn room outside the floor's grid
widens it rather than overflowing the outline; that only fires if the table is wrong. The Interactive Map's
`ppu()`/`originX()`/`originY()` use the same fit, and `cellAt` goes through them, so clicks still map correctly.

## The green room's open faces

The Entrance - green on the map, and the mod's own name for it - has a complete capture (all 1,089 columns
marked seen) and still shows the void through its back wall and its window bays, because what is behind those
faces on Hypixel is dungeon scenery outside the 31x31 tile a capture covers. `SimBuilder.closeGreenRoomShell`
fills air in the room's own outermost layer with stone, using the same "only where the ring is already air" rule
`SimBuildQueue.submitSeal` uses for single-room tests, but with stone rather than diamond because here it is
meant to disappear. It runs from `doorWork` BEFORE the doorways are carved, so the one real door is reopened
afterwards; running it after would brick up the way out of the run.

## Every solver was pointing at Hypixel's height

`RoomDatabase.toRealCoord` rotates x and z and passes y **straight through**, because on Hypixel a room-relative
y IS a world y - every dungeon floor is at the same height. The sim shifts the whole map vertically
(`SimAltitude`), so the identical call there returns a position tens of blocks from the room it was measured in.
That one line is why killer560's solvers "didn't work in sim": not one of them was wrong about the room, they
were all drawing at the right x and z at the wrong altitude - a hundred blocks over his head or buried under the
floor.

The fix is in `PuzzleCoords.real/relative`, which adds and subtracts `DungeonLayout.simYOffset()` (zero outside
the sim, so a real run is unchanged). The four private `realPos` copies in Quiz, Water, Beams and Ice Fill now
delegate to it instead of each repeating the same three lines - three of the four were missing the shift, which
is exactly the drift CLAUDE.md's "a fix applied to one of a set" note warns about. `WeirdosSolverFeature`'s
hardcoded `69` got the same treatment; it was the fifth place to need the helper that already existed for
`doorBlock` and `cellCenter`.

**Making a solver work in the sim is usually two things, not one**: the geometry has to be at the right height
(above) and the CHAT has to be in Hypixel's own shape. `QuizSolverFeature` wants a line containing the question
followed by lines starting `ⓐ`/`ⓑ`/`ⓒ` and ending with the answer; `WeirdosSolverFeature` wants
`^\[NPC] <name>: <line>` plus an ArmorStand of that name. A `[Sim] ...` prefix breaks both anchors, so the sim
now sends those lines raw through `sendSystemMessage` - they still reach `ChatObserver`, which is what every
solver subscribes to.

## Three Weirdos is three weirdos now

*The stands and the speaking changed on 2026-10-02 - see "The 2026-10-02 puzzle round".*

The capture's three chests sit at local (25,69,12), (26,69,14) and (25,69,17), and the chamber's cauldron at
(29,69,16) - so the middle chest was two short of its line. All three move the same two blocks rather than being
re-spaced (killer560: "the middle most should be in line with the cauldron in the room"), because the
arrangement is the room's own and only the alignment was wrong.

Each chest now has a NAMED ArmorStand one database-relative block of -x from it, because that is the exact
offset `WeirdosSolverFeature` walks (`relative.x += 1` from the NPC). Which world direction "-x" is depends on
the room's rotation, so it is derived from the anchor: `anchor.world(1,0,0) - anchor.world(0,0,0)`. The weirdo
at the correct chest speaks a line from the solver's own SOLUTIONS list and the other two from its WRONG list,
which is precisely the rule it implements - "the speaker of a solution line is standing at the right chest".

**An ArmorStand's name floats about 2.3 blocks above the stand.** That is the whole of the "the text is two
blocks too high" report, twice - first on the Quiz, then on Three Weirdos. A stand lifted 1.3 puts its text 3.6
over the spot; -0.7 puts it just above head height. The stand ends up inside the floor, which is fine: it is
invisible and has no collision. There is one lift constant for the whole file now.

## Lower Blaze was looking for its floor at Hypixel's height too

`SimBlazePuzzle.bindAt` started its floor search at a hardcoded captured y of 66 (120 for Higher). Lower Blaze's
capture runs y **15..83** - its floor is at 20 - so the search began 46 blocks above it, found the first
air-over-solid on the way up and hung the chain in the roof. Every room carries its own band
(`RoomLibrary.Room.minY/maxY`) and has done since captures stopped being indexed against a global constant;
starting there is the same fix that file already documents.

## Levers that open a way through, and why they are a table

Mines' barred door and Pressure Plates' boarded wall are blocks Hypixel moves with a command, not with redstone
a capture could record - so nothing in the data connects them to their lever. `SimRoomLevers` is that connection,
written down once per room in capture-local coordinates, which is the system `/simwhere` prints.

Both entries came from killer560 looking at the block and running `/simwhere`, not from decoding and reasoning -
which had already produced two confident wrong answers on these same two rooms. Mines' door is an 18-block dark
oak frame at `x=50, y=78..84, z=58..60` with three iron bars filling its opening, 21 cells exactly; Pressure
Plates' wall is 30 blocks of oak at `x=54..55, y=93..97, z=15..17`, again exactly a box. In both cases the flood
fill matching the box's volume is the check that the box is the thing and nothing else.

`/simwhere` itself had a bug worth knowing about: its "db-relative" line printed the raw world y, because
`toRelativeCoord` passes y through and the y it is handed in the sim is the shifted one. It prints the captured
y now, so the two systems it shows differ only in x and z - which is the entire point of showing both.

## The prince has a second signature

Gold touching smooth stone slab picks out Chambers, Sloth, Red Blue and Market. It cannot pick out Leaves, whose
prince killer560 `/simwhere`'d at capture (10,82,16): a 3x3 of polished andesite with a **sea lantern** in the
middle, a player head on its side and stone brick stairs underneath. Nothing about it is gold.

"Sea lantern with polished andesite on all FOUR horizontal sides" is as sharp as the gold rule - across all 135
captures it picks out exactly two rooms, Leaves and Stairs, both with the full eight-block ring. Relaxing it to
"andesite anywhere beside a lantern" picks 23 rooms and is useless, which is the same trap the gold-alone rule
fell into. Stairs is an inference and is reported in the build log by room, so a wrong second room is visible
rather than silently worth a bonus point.

## One mimic, and only from Floor 5

killer560 (2026-10-01): "always and only make 1 mimic per run. except if you are on floor 4 or below then it
should never have one." It is a property of the FLOOR, not of chance. (Note this is one floor lower than
`SimScore`'s wiki-sourced note, which says VI and above; his rule is the one implemented and the two are flagged
rather than quietly reconciled.)

Two real bugs came out of wiring that up. **`SimState.setFloorLabel` existed and nothing ever called it**, so
the sim believed it was on F7 whatever was generated - anything per-floor was wrong on every floor but one.
`SimFloorGen.generate` sets it now, from a new `Floor.code`. And **the mimic was picked before the secrets were
placed**: `chooseForMap` ran in the placement loop, while the secret chests go in from the build's completion
callback, so the pick only ever saw the chests the captures themselves carry. It runs in that callback now,
which is what "once everything is down" was always supposed to mean.

The mimic's room is painted the map's own blood red (`cfg.getColorBlood()`), not the faint `MIMIC_TINT` blend
the live check uses - the live check looks for a `trapped_chest` at a database secret position, which a sim
never has, so `SimMimic.mimicCell()` answers directly instead.

## Trap rooms take your abilities

killer560: "in trap I cannot etherwarp or teleport or use any ability [...] This does not apply to dungeon
breaker or super boom." Checked at the moment of the click against the room he is STANDING in, so a warp that
starts outside and lands inside is allowed - which is the case he named. The Dungeon Breaker needs no exemption
in the code at all: it is a LEFT-click tool on `AttackBlockCallback` and never reaches the use path. Superboom is
the one name in the allow set. The refusal returns `FAIL` rather than `PASS`, because an ender pearl is vanilla
all the way down and `PASS` would let it throw anyway.

A trap room is identified by `RoomEntry.type`, not by its name containing "trap" - the name is only a fallback
for a room the database does not know.

## A use PACKET is not an ability in here

**Superseded 2026-10-04** - see "The sim answers packets, like Hypixel's server" at the end. Kept for the history.

`ClearExecutor` sends its etherwarp hops as a `ServerboundUseItemPacket` through `startPrediction`. On Hypixel
that IS the ability, because Hypixel's server implements it. The sim's abilities live in `SimAbilities` behind
Fabric's `UseItemCallback`, and nothing on the integrated server turns an inbound use packet into one - so the
hop was sent, accepted and did nothing: "Etherwarp is now saying found path but not actually etherwarping."

The fix is to take the branch that was already there for a missing mixin - `gameMode.useItem`, the client-side
path that callback is injected into. `AutoPuzzleUtil.useItemRotated` has always fired its shots that way, which
is why the puzzle autos worked in the sim when the map's own hops did not. **Anything in this mod that performs
an interaction by building a packet will do nothing in the sim; anything that calls the `gameMode` method
works.** That is the first thing to check when something automated "runs" in here and nothing happens.

The second half of the same report - "if it is far away then it fails" - is a range disagreement. The planner
has always searched with 60 blocks a hop; Hypixel's etherwarp is 57 and `SimAbilities` enforces exactly that, so
a hop planned at 58-60 was accepted by the search and refused by the ability, leaving the queue stuck on a node
that could never fire. In the sim the planner now asks `SimAbilities.etherwarpRange()`.

## Teleporting up had only one fallback, and it was downwards

`dashTarget` walks the look vector and keeps the furthest point the player still fits at. When a step did not
fit it fell back to the same horizontal position **at the height he started at** - right for aiming down (the
floor stops you and you slide along it, which his Hypixel log confirms) and wrong for aiming up, where the
moment the ceiling stopped the climb it dropped him all the way back to his own level. The fallback now walks
from the candidate's own height TOWARDS his, one block at a time, and takes the first that fits: under him that
is the floor, over him it is the last block below the roof. A settle that is not further than the best so far is
refused outright, so looking straight up at a low ceiling does nothing rather than teleporting him to where he
already is.

## Boulder was three writes of scenery

"There are random stone blocks floating everywhere" was `bindAt`: it placed each boulder at `BUTTON_Y` (65)
while the grid the pattern is written to is `FLOOR_Y` (66), so every boulder hung a block under the floor; it
placed one at all, when the pattern already puts a solid block at every `1` cell; and it propped up a missing
button with a **stone pedestal in mid-air**. All three are gone - the boulder is the room's own block at the
grid cell, and a solution step with no button is reported instead of built.

"Pressing the buttons doesnt move the boulders anywhere" was `pressButton` deleting the boulder. It pushes now,
and the direction is read off the solution rather than chosen: every entry pairs a boulder with the click one
square away from it, so the push runs from the button through the boulder and onward until something stops it.
A fail puts the whole 42-cell arrangement back, not just the two boulders the solution names - a push can roll
one several squares onto a cell the pattern wanted empty.

## Where a diagnostic went in instead of a guess

Three reports this round could not be resolved from the captures, so each got one line that names the cause on
the next run rather than a change made on a hunch:

- **Ice Path solver** declines in four different places and all four look the same from in front of it.
  `sayOnce` prints which, once per reason per room. Its silverfish search box also went from 16 to 20 blocks:
  the board is 17 cells across, so its far corners sat exactly on the edge of the old box.
- **Archway "generated halfway and broke [...] rotated 90 degrees"** is the footprint and the paste disagreeing
  about which axis is long - a 65x33 capture needs 2x1 room cells at rotation 0 and 1x2 at 90. The two numbers
  come from different places (the layout's flood fill, and the capture's own size), so the build now compares
  them and says so when they differ.
- **Blood without the fairy on the way** now warns when the escape hatch fires, because that hatch is meant to
  be rare and a line in every log means the bias is not working rather than the floor being unusual.

## Making the fairy a real stop on the way to blood

Requiring blood to be a descendant of the fairy was necessary and not sufficient: the fairy went in on a coin
flip, growth carried on wherever the draw sent it, and by the time blood was due there was usually no stub left
under the fairy at all - so the escape hatch fired on nearly every floor and the rule did nothing. Two changes
make the subtree exist: the fairy goes in at the **first** spot that can take it rather than on a 1-in-4 roll,
and while blood is still owed the stub draw is **biased to the fairy's own subtree** (a bias, not a
restriction - when that side has nothing open the draw falls back to the whole list, so a floor is never lost).

This is ancestry, not every route: the floor deliberately grows loops, and a second way round is what a loop is.

(Superseded 2026-10-04: the bias still never made it reliable - 64% of floors had the fairy off the path through
the doors. The path is now laid first; see "The path to blood is laid first" at the end of this file.)

## A single-room load never armed anything

`SimBuilder.buildSingleRoom` pasted the room, placed its secrets, published its map - and never called
`SimRoomPuzzles.armFloor`. The full-floor path has called it since puzzles were bound at all; this one never
did. So a room loaded on its own was **scenery**: no blazes, no tic-tac-toe buttons, no creeper-beam pairs, no
teleport pads, no silverfish, and therefore no solvers and no autos either, because all of those read the
puzzle's own state.

That is one line and most of a day's reports. killer560, 2026-10-01, all in one message: "There still werent
blazes", "tictactoe still is missing its bottom right button", "the solver isnt working there either", "Nor the
auto puzzles none were working", "The teleport pads in tpmaze still arent teleporting me", "This time i went to
ice path it didnt even have the silver fish **last time it did**". That last clause is the tell - last time was
a generated floor. When a sim report says a puzzle does nothing, the first question is which path built it.

`SimPrince.scan` and `SimRoomLevers.armFloor` were missing from the same place and went in with it.

## Water Board's gates are its back wall, and the capture names every one

The first version moved three blocks at the foot of each lever's plinth. That was a guess, and killer560 said
so: "it should move those blocks at the very back in and out on that wall, not right belowt he levers."

The real mechanism is entirely in the capture. Twenty-seven sticky pistons stand at room-relative `z=28`, and
each one pushes an **ore block** - and that ore is what says whose slot it is: `coal_block` is COAL's,
`terracotta` is CLAY's (the solution file spells it `hardened_clay`), and so on through the six. Retracted, the
ore sits at `z=27` and is what you see on the wall; extended, the piston head is at `z=27` and the ore has been
pushed back to `z=26`. So **the wall's pattern of ore blocks is the board's state**, and reading either cell
gives both the owner and the position.

Counted off the file: coal 4, gold 3, quartz 5, diamond 6, emerald 4, clay 4, plus one `lapis_block` slot that
belongs to no lever and is left alone. Thirteen of the twenty-seven start extended - which is "some amount of
blocks need to start out for the path to get the secret", and it is the room's own starting pattern rather than
one invented here. A reset restores *that*, not a flattened board.

The piston head and the two piston states are **copied off the room** at arm time rather than built from
literals, so nothing here guesses a block property name - which is the mistake that cost a build on the same
day.

## Rooms that want a different spawn than their middle

`snapPlayerTo` drops him at the tile centre and scans UP from the bottom of the world for somewhere to stand.
Two rooms need better: a blaze shaft's middle is the chain and its "level" is the one you walk in on, and
Creeper Beams' middle is the puzzle's own structure. `SimBuilder.spawnFor` is the table - an x/z offset plus a
flag that flips the landing scan to come DOWN from the top, which is what Higher Blaze needs and no other room
does. The blaze doorway side comes from the room's own measured `RoomDoors` mask rather than a guess; 14 is the
tile centre stepped out to the wall and back in one. `/goto` passes the room through to the same table, so the
command and a single-room build cannot drift apart.

## One held shot is twenty clicks

"it wouldnt let my shots render the thing as hit most of the time" and "make the delay between selecting a
block and unslecting that same block 3 ticks" are the same fault. CLAUDE.md already records that cancelling a
block break re-enters `AttackBlockCallback` every TICK rather than once per click; Creeper Beams consumes that
callback, so one held shot picked a lantern, cancelled it and picked it again twenty times a second. From in
front of it that is a lantern that mostly does not respond. Three ticks of per-position cooldown, and the
repeat is swallowed rather than passed on to a block break.

## The one line that broke every solver in the sim

`SimBuilder` publishes each room's clay corner and rotation to the live map, and every puzzle solver asks
`LiveMapFeature.currentRoomClayAndRotation()` for them. It was publishing the **paste rotation** next to a corner
computed for the **database rotation**:

```java
int[] clay = SimSecrets.clayCorner(room, gx, gz, rot);   // db rotation worked out internally - correct
clayByRoom[nameIndex] = new int[]{clay[0], clay[1], rot}; // and then the PASTE rotation published beside it
```

`clayCorner`'s own doc spells the distinction out - the footprint follows the paste rotation, which corner the
marker stands in follows the database rotation - and the two differ by the capture's own turn
(`RoomCaptureRotation`) in 88 of the 122 identifiable rooms. So the solvers got a correct corner with a rotation
a quarter or a half turn off it, and **every relative coordinate came out spun about that corner**.

Decoded off the captures, with the paste rotation 0 that a single-room load always uses:

| Room | Capture turn | Rotation published (was) |
|---|---|---|
| Water Board | 270 | 0 |
| Creeper Beams | 0 | 0 |
| Tic Tac Toe | 180 | 0 |
| Boulder | 270 | 0 |
| Quiz | 180 | 0 |
| Three Weirdos | 270 | 0 |
| Ice Fill | 270 | 0 |
| Ice Path | 180 | 0 |
| Teleport Maze | 0 | 0 |
| Higher Blaze | 270 | 0 |
| Lower Blaze | 90 | 0 |

Nine of the eleven puzzle rooms were wrong. Only Creeper Beams and Teleport Maze, whose captures happen to be
canonical, could ever have worked - which is exactly the pattern of "my solvers don't work in the sim" reports.
Every one of those turns matches what `SimRoomPuzzles.bestAnchor` independently measured against the pasted
blocks, so the two methods agree.

`SimRoomIndex.add` has always computed it correctly, for Secret Waypoints - which is why the **waypoints landed
while the solvers did not**, and the single best clue in hindsight. Both now use the same expression.

Two follow-ons went in with it:

- **`bestAnchor` can overrule the recovered turn, and now the map follows.** When a puzzle's own furniture scores
  better at a different rotation, `SimRoomIndex.correct` re-records that room, and the publish (which runs after
  `armFloor`) picks it up. Before, the puzzle moved and every solver's highlight stayed where it was.
- **A per-room vertical nudge is published too** (`SimRoomPuzzles.dyFor`, applied in `PuzzleCoords`). Ice Fill
  was the reason, and **that reason was wrong - corrected below.** No shipped room needs a nudge now.

## Hypixel heights hardcoded where the sim moves the floor

`PuzzleCoords` carries the floor shift for everything that goes through it. Five places did not go through it,
and each one failed silently:

| Where | Was | Symptom |
|---|---|---|
| `AutoBeams` | `player.getY() != 75.0` | "auto creeper beams would look down and teleport and that was it" - it repositioned onto the platform, re-read 75, and repositioned again |
| `AutoWater` | `player.getY() != 59.0` | every lever click declined, no message |
| `AutoBlaze` | `player.getY() <= 75` | Higher Blaze skipped its reposition instead of repeating it |
| `AutoIceFill` | `69.5..72.5` band, and `stupidStairs`' 71.1 / 72.1 | warped onto the first unfilled tile instead of walking; both stair midpoints dropped |
| `TeleportMazeSolverFeature` | `pos.y != 69.5` | the solver rejected every teleport the sim made, so it never marked a pad visited or found a candidate |

`BoulderSolverFeature` was reaching past `PuzzleCoords` to `RoomDatabase.toRealCoord` directly for all three of
its coordinates, so it read the arrangement out of empty air (all-air matches no bundled pattern, hence nothing
drawn) and would have put its boxes a hundred blocks under the room. `AutoBoulder`, `AutoBlaze` and
`AutoTicTacToe` did the same for a secret's position, which is why their chest side-trips walked to nowhere -
`PuzzleCoords` now has a `RoomEntry.Pos` overload so there is no reason to reach past it.

`RouteCoords` did not carry it in either direction, so an Auto Route recorded on Hypixel aimed at Hypixel's
height in the sim, and one recorded in the sim stored a shifted height as though it were relative - wrong on
Hypixel *and* wrong in the sim's own next build, because the offset is chosen per floor. Both directions carry
it now, which makes a stored route mean the same thing wherever it was recorded.

## Auto puzzles: the sim's Terminator was not a shortbow

"the auto puzzles none were working except auto blaze wanted to look towards the middle."

Auto Creeper Beams, Auto Ice Path and Auto Blaze all gate their shot on `AutoPuzzleUtil.isShortbow`, which is a
lore search for the single line **`Shortbow: Instantly shoots!`** - how Hypixel marks every shortbow, Terminator
included. `SimItemLore`'s Terminator did not have it. So all three ran their aim and then declined to fire, and
Auto Blaze stopping with the crosshair on the middle blaze is precisely what that looks like.
`AutoReposition`'s own swap-to-a-bow was failing for the same reason.

## Teleport Maze: the start pad was the one pad nothing indexed

"The teleport pads in tpmaze still arent teleporting me."

The seven chambers are sealed - the capture holds **240 iron bars** and a solid stone brick wall at relative
`(12,69,13)`, right between the start pad and chamber one - so the start pad at `(15,69,12)` is the only way in,
exactly as on Hypixel. `bindAt` indexed `REAL_PADS[0..27]`, the twenty-eight choice pads, and stopped. He stood
outside a sealed maze with nothing to step on, and every pad that would have worked was behind a wall.

Maze teleports also now land at `y + 1.5` rather than `y + 1`. That half block is not cosmetic: the solver only
treats a position packet as a maze teleport when it lands on room-relative `y 69.5`, which is what every real
Hypixel maze teleport does, and works out the exit from the yaw of exactly those packets.

## Tic Tac Toe: the marks were painted where the solver never looks

The real board's nine marks are maps in item frames. An item frame is an entity, so a block capture cannot hold
one and the sim has nothing to paste - it writes concrete instead. It was writing it on the **wall at relative
`x=7`**, to keep the buttons standing at `x=8`. `TicTacToeSolverFeature` reads the board at `x=8` and nowhere
else, so every mark was invisible to it and to Auto Tic Tac Toe with it.

The mark now goes **on the cell**, which is also where its button is, so painting a mark and removing that cell's
button are the same write instead of two that had to agree - and an unplayed cell is simply its button again,
which is what the real board looks like and what makes it clickable. The solver reads those nine cells when it
finds no item frames at all, so a real dungeon never takes that path.

## Creeper Beams: a correct first shot was painted as a failure

"For creeper beams lanters still are messed up i am not even sure what all is wrong but it is just wrong."

Holding the first lantern of a pair turned it to `PRISMARINE`, and that is the one thing it must never do.
`BeamsSolverFeature.rescan` reads exactly that difference, and reads it as the **failure** state:

```java
litA != litB && (usedUp(a) || usedUp(b))   ->  misaligned, paint both ends RED
```

its "one of this pair was burned on the wrong partner and can never be finished". So the first correct shot of
every pair made his own solver light that pair up red and draw a red line across the room. The hold is the sim's
bookkeeping, not a change to the room, so it is drawn by the puzzle's own renderer and the block is left alone
until the pair is actually joined.

The room also has **35** lantern blocks and only **22** of them are in the bundled eleven pairs. The other
thirteen are the room's own sea lanterns and are there on Hypixel too, so shooting one does nothing there either
- but the sim can say which it was instead of a shot that silently achieves nothing, and does, once per lantern.

Auto Creeper Beams needed one more thing: it does not watch the blocks at all, it advances a pair's stage only
when an `entity.elder_guardian.hurt` sound packet arrives at the exact lantern it just shot, with pitch
`1.3968254` (first hit) or `2.0` (pair done). The sim plays both now, at **integer** coordinates - a sound at a
block's centre arrives as `x + 0.5` and would never match.

## Quiz: the question was announced from the corridor, and the solver wiped it

"my quiz solver and auto quiz are still broken."

`QuizSolverFeature` and `WeirdosSolverFeature` both do this on a room change:

```java
if (current != lastRoomEntry) { lastRoomEntry = current; reset(); }
```

and that `reset()` clears `triviaAnswers` and every `options[].correct`. The announce range is 22 blocks, which
reaches well outside a one-tile room, so the question and its three ⓐ/ⓑ/ⓒ lines went out **while he was still in
the corridor**. The solver read them and armed correctly - and then he stepped through the door, the room
changed, and it wiped everything it had just learned. Nothing sends those lines again.

The gate is now the live map's own answer, held steady: the room it names must be the bound room, with a resolved
rotation, for three ticks running. Two is already enough to guarantee the solvers have done their reset before
the first line arrives, because they run on `END_CLIENT_TICK` and this runs on `START`. A bound room the map
cannot name at all falls back to distance after three seconds, with a line in the log saying so.

Four more things came out of reading that solver against the sim's:

- **A second question in the same room lit up two answers.** Hypixel always announces the previous question as
  answered before asking the next, so the solver has never had to cope with two arriving back to back - and the
  sim hands out a new question after a wrong answer. `clearForNewQuestion` / `clearForNewRound` are that line's
  job, done by hand.
- **The pillar buttons stopped working after a wrong answer.** `newQuestion`'s label-replacement branch clears
  the whole click index, and in a bound Quiz room those twelve buttons are the only way to answer.
- **The highlight was one block inside the pillar.** Decoding `Quiz.json` settles what the solver's three
  coordinates name: at the capture's own rotation they land on `smooth_stone` with air above, each ringed by four
  of the room's twelve wall buttons - all twelve. They are the little pillars Oruo's buttons hang off, not floor
  to stand on, so the box goes on the block itself rather than the one under it.
- **Auto Quiz was clicking the pillar.** A right-click on smooth stone does nothing. `getCorrectAnswerButton`
  returns a button off the correct pillar's four sides, falling back to the pillar if a room has none.
- **A standalone `/simpuzzle quiz` arena never spoke the server's format at all** - it announced with the three
  options squashed onto one `[Sim] ...` row, which is exactly the shape the solver cannot read. Both paths share
  one question now.

Two smaller hardenings, both read off the solver rather than guessed: the sim only picks a question that
`QuizSolverFeature`'s own first-contained-key scan resolves to itself (a question whose text contains an earlier
entry's hands the solver the wrong answer list), and a distractor that merely **ends with** any of the question's
correct answers is rejected, because the option test is `anyMatch(trimmed::endsWith)` over the whole list.

## Boulder: a boulder is three blocks tall

*Superseded 2026-10-02: a box is 3x3x3, not a one-wide column - see "The 2026-10-02 puzzle round".*

"boulder still has a bunch of random floating stone blocks on the top layer of the wood boulders and the buttons
still dont push them."

Decoding `Boulder.json`: 90 jungle and 72 birch planks on **each** of y 64, 65, 66, and nothing on 67. A boulder
is a three-block plank column, the arrangement is sampled at its top (66), and all 31 stone buttons are at 65.
So writing `Blocks.STONE` at 66 dropped a stone cap onto every real boulder and writing AIR there beheaded the
ones the pattern did not want. It has to be written at all - this room's own arrangement,
`011110001011000101100000010000101000001100`, is not one of the eight in `boulder-solutions.json` - but as whole
boulders, in the room's own plank.

The push had the same off-by-a-column fault: it moved one block, from y 65, so a press carved the middle out of
a boulder and left its top and bottom standing. The clearance test only looked at y 65 too, so a neighbouring
boulder's gap-free column read as "air" at the only height being checked and the roll walked through it.

## Etherwarp onto the roof, and what the interactive map actually needs

"somehow my etherwarp pathfound onto the roof of the dungeon while using interactive map."

On Hypixel that cannot happen and nothing ever had to stop it: a real dungeon is a solid block of rock with rooms
carved out of it, so there is no outside surface to stand on and no line of sight to one. The sim is the opposite
shape - each room is pasted as its own captured column, rock above the ceiling included, and the **cells between
the rooms are empty air** because nothing was captured there. So the sim's map is a cluster of rock towers with
open sky over them and gaps to see out through, and a pathfinder whose moves are "etherwarp at anything you can
see" will climb one.

`TeleportUtils.underCover` tests the thing that is actually different: a landing inside the dungeon has the
dungeon's own rock above it, a landing on top of one has nothing. Skipped within six blocks of floor height,
which is where nearly every landing on a clear is, so the normal path costs nothing.

"it doesn't need to go the exact spot that I click instead it just needs to go to that room, more specifically if
that room is a 1x2 then it should go into that rooms quadrant that I clicked. It can choose anywhere in that room
whatever is fastest." The fallback was `nearestEtherwarpable` on the tile centre - a 25-block sphere sorted by
distance from that centre, which can leave the tile, pick a block 20 up, or land on a wall, and has no idea where
the player is. `etherwarpableInTile` stays inside the clicked tile's own footprint, at that tile's floor height,
and picks the candidate closest to him. A room override that turns out not to be standable now falls through to
it rather than failing the whole press.

## Blaze: the solver only looks at armour stands

"auto blaze wanted to look towards the middle" - and then nothing. `BlazeSolverFeature.rescan` begins:

```java
for (Entity entity : client.level.entitiesForRendering()) {
    if (!(entity instanceof ArmorStand)) continue;
```

so a name set on the Blaze itself is invisible to it however well it matches the pattern. The sim named the blaze.
The solver therefore found **zero** blazes in there, and `AutoBlaze` reads nothing but `getOrderedBlazes()` - its
very first line. What he saw was `seedDefaultView`, which runs before the empty-list check, turning him towards
the middle; everything after that was skipped. The label hangs on its own stand now, the same way `SimMobs`
already tags its starred mobs, and it is dropped the tick its blaze is found dead so the solver stops counting it.

**One dial left, and it is written down rather than guessed.** Both the solver's highlight box and Auto Blaze's
aim are reconstructed *from the stand*, with offsets Odin tuned against Hypixel's own stand geometry
(`inflate(0.5, 1.0, 0.5).move(0, -1, 0)` for the box, `boundingBox.getCenter().y - 1.0` for the aim). That
geometry cannot be measured from a capture, because a stand is an entity and a capture holds blocks. The stand is
placed where `SimMobs` puts its star tags - just above the mob, the only placement in this codebase shown not to
swallow a kill. If the highlight reads too tall or the auto shoots over the blazes, that height is the only thing
to change; `attachLabel`'s doc says so at the call site.

## A button has to be allowed to be a button

`InteractionResult.SUCCESS` from a `UseBlockCallback` cancels the interaction. That is exactly right for a chest
- it stops an empty chest screen opening - and exactly wrong for a button: the Quiz room's twelve pillar buttons
and Tic Tac Toe's nine cell buttons never depressed and never clicked, so the only sign a press had registered
was a chat line. Both now return PASS when the block they are on is a `ButtonBlock`, which is the same
observe-never-consume rule `SimWaterPuzzle`'s levers and `SimBoulderPuzzle`'s buttons were already written to.

## Verified against the captures this round

Every claim these puzzles make about their room was re-decoded rather than taken from the comment above it:

| Room | Claim | Measured |
|---|---|---|
| Quiz | three answer pillars ringed by the room's buttons | 3/3 smooth_stone with air above at rotation 180, 12 of 12 wall buttons accounted for |
| Water Board | 7 levers, 27-piston back wall, 5 wool columns | 7/7 levers at 270; 27 pistons at z=28, 13 extended; wool at `(15,55,z)` with air at 56; the terracotta identifier marker present |
| Creeper Beams | 22 lanterns in 11 pairs | 22 distinct over 13 entries (two are duplicates), 9 sea + 13 prismarine, plus 13 further sea lanterns in no pair |
| Teleport Maze | 30 pads, sealed chambers | 30 end_portal_frames, 240 iron bars, solid wall at `(12,69,13)` between the start pad and chamber one |
| Tic Tac Toe | 8 of 9 buttons, the ninth already played | 8/9 at rotation 180 - the gap is **row 2, col 2**, the bottom right, exactly as reported |
| Ice Path | 289 ice cells, one stray wall in the capture | 289/289 ice; 17 non-air at the wall layer against the corrected 16, the extra at `(15,16)` |
| Ice Fill | ~~the capture is a block low~~ - wrong, see "Ice Fill's path is feet positions" | the bundled path is feet positions: at `dy = 0` all 45 identified tiles are air with ice under them, and the identifier pairs match all three floors |
| Three Weirdos | three chests moved to line the middle one up with the cauldron | chests at local `(25,69,12)/(26,69,14)/(25,69,17)`, cauldron at `(29,69,16)`; all three new spots have solid floor under them and three blocks of air over them |
| Mines / Pressure Plates | one lever opens a whole wooden door | lever exactly at the recorded spot in both; Mines' region is 21 cells and holds 21 dark-oak/iron-bar blocks (y 78..84 is the whole door - 74..75 is a separate grate below the floor), Pressure Plates' is 30 cells and all 30 are oak |

## Ice Fill's path is feet positions, not ice (2026-10-01, local session)

"the ice fill solver still isnt working in ice fill." The 2026-10-01 cloud round decided the Ice Fill capture
was a block low because the bundled path "lands on ice at dy = -1". It does - because the path is where you
STAND, and the ice is the block under your feet. Decoded at database rotation 270: at `dy = 0` all 45 tiles of
the identified layout are air with ice directly beneath, and `IceFillSolverFeature`'s identifier pairs match
exactly one pattern on each floor (3, 4, 3); at `dy = -1` none of them match. Publishing -1 moved the solver's
identifier lookups into the ice, every floor failed to identify, and the solver drew nothing. `SimIceFillPuzzle.
bindAt` now tests for ice one under each waypoint, so it binds at no nudge and publishes none. Before reading a
bundled coordinate as "the block", check whether it is the block or the space above it.

## Smaller ones from the same round

- **Teleport Maze is paired like the real room.** Each of the 28 pads is linked both ways to a pad in another
  chamber, the start pad to one of them, and one pad leads to the end; a landing puts you ON the destination pad,
  which does nothing until you step off it. Every teleport turns you to face the exit pad, because
  `TeleportMazeSolverFeature` finds the exit by crossing those look rays. Abilities and the Dungeon Breaker are
  refused in the maze by room name.
- **The interactive map stalled in the sim because it chains hops on a predicted position.** `ClearNode.
  doTeleport` moves the executor's cached position to the landing and the next hop fires next tick; on Hypixel the
  aim travels in the packet, but the sim's etherwarp resolves from where the client player actually is, which has
  not moved yet - "no etherwarp target there" in bursts. In the sim the cache is dropped after each hop so the next
  one waits for the landing.
- **Generated floors repeated the same big rooms** because `choose` ranked by size and doorways with a 0..1 die.
  `SimFloorLayout.RECENT` now costs a room for having been on recent floors (halved each floor) and the die is
  wider.
- **Tic Tac Toe** paints a played cell's mark on the wall one block back (database x=7) and removes the button;
  `TicTacToeSolverFeature.readSimBoard` reads the wall as well as the cell.
- **`/goto Higher Blaze`** scans down from the sky for its landing and found the roof. A downward scan now only
  accepts a spot with something over it. Spawn offsets for the blaze rooms and Ice Fill are turned by the room's
  paste rotation, which they never were.
- Balcony and Archway are the only two rooms whose captures have no roof marker, which is the code's own sign
  of a missing roof corner.
  The handoff said `SimBuilder` warns when a 1x2's reserved cells disagree with its long axis; no such warning
  exists in the code.


## The 2026-10-04 list

- **A sim listener that returns SUCCESS hides the click from every listener registered after it.** Fabric's
  `UseBlockCallback` stops at the first non-PASS result. `SimSecrets`' essence handler is registered before
  `SecretWaypointsFeature`, consumed the skull click, and so the essence's waypoint never cleared. It now calls
  `SecretWaypointsFeature.markSimEssenceCollected` itself (nearest WITHER waypoint within 3 blocks, because a
  buried essence is placed up to two blocks above its database spot). **2026-10-05:** the same trap from the other
  side - `SimAbilities`' client hook, registered BEFORE it, answered the skull click whenever an ability item was
  held, so Secret Aura (and he) never collected an essence with AOTV/Hyperion in hand. The essence is now a
  player_head with Hypixel's essence profile (black via a bundled skin patch), collected by a SERVER listener,
  and `SimAbilities.blockWins` lets it beat the held item. Scenario 99-sim-essence-aura.
- **Secret Aura gated on `getCurrentServer()` being Hypixel/p3sim**, which is null in singleplayer, so it never
  acted in the sim. It also accepts `SimState.canAct` now. (Its `PLACED_WITHER` essence branch is gone since
  2026-10-05: the sim's essence is now Hypixel's profiled player_head, collected on the server - see below.)
- **A client command's `requires()` is evaluated when the command tree arrives on join**, not when typed - Fabric
  copies only the nodes that pass at that moment into the suggestion tree. `/start` required
  `isGeneratedFloor()`, which `SimBuilder.build` sets after the join, so it never tab-completed. The floor check
  lives in `SimRun.begin` only now. Any sim command gated on per-map state has the same trap.
- **`freezeWorld` ran for every singleplayer world**, not only the sim: it sat above the pending-code check in
  `SimWorld.onWorldLoaded`. Moved after `SimState.enter`. It also turns off `MOB_DROPS` (loot and XP orbs - read
  in `LivingEntity.dropExperience`), `ENTITY_DROPS` and `BLOCK_DROPS`; the sim's own drops are spawned directly
  and unaffected.
- **Picked-up item secrets are deleted from the inventory** the tick after they are counted. Only stacks
  carrying the `killer560_sim_secret` CUSTOM_DATA mark go, so `/item` copies are never touched; Architect's First
  Draft is not in the secret pool and is excluded from the purge by id anyway. `SimArchitect`'s auto-get on a
  puzzle fail still hands one over (his earlier request) - drop that if "only from /item" is meant literally.
- **Room variety**: the RNG was never the problem (`SimFloorGen.RNG` is an unseeded `new Random()`). `RECENT` was
  in memory only, so every launch started with no recency, and its weight lost to `choose`'s deterministic
  doorway/size ordering, whose head wins because `choose` stops at the first "good enough" placement. Now saved to
  `config/killer560/dungeons/sim/killer560smod-sim-recent.json` by `SimRecencyStore` (kept out of `SimFloorLayout` so the generator stays
  runnable outside the game), decay 0.6, key weight 3.0, score weight 2.0, die 0..4. Not measured over many floors -
  and that was the mistake: see "Blank cells" below, the same day.
- The sim's sidebar, the Custom Scoreboard (which shows the sim sidebar under a plain title instead of its Skyblock
  entries) and the vanilla tab list header/footer carry "killer560's personal testing sim" and
  `discord.gg/hkQMF5fE84`. The sidebar objective's title stays `SKYBLOCK`: `SkyblockGate` reads it, and every
  "Skyblock Only" feature in the sim depends on that.
- The pause screen's Change Room button is a vanilla `Button` placed 4 px under the lowest button in the centre
  column, read from the screen's widgets, instead of pinned to `height - 46`.
- **Routes filter and "Next room with no routes" (2026-10-05)** live in `SimRoomRoutes`. Eligible = the room
  database entry exists, type is not PUZZLE/BLOOD/ENTRANCE/FAIRY, `secrets > 0` (113 of his 135; every CHAMPION room
  has 0 secrets so they drop out too). "Has routes" = `RouteStore.forRoom(name)` has at least one node; library names
  and database names match for all 135, and routes are stored room-relative, so there is no rotation key to worry
  about. The pause-menu button only appears when exactly one room is placed and the floor is not generated
  (`currentSoloRoom`), sits under Change Room, and shares its row at half width when the window is too short.
  `belowVanillaColumn` must skip it, or a resize stacks Change Room under it. Testkit scenario 97 proves both.

## The 2026-10-04 Map Logger round: damage, the key, crypts, landings and the sidebar

- **No lava, fire or fall damage.** `SimWorld.freezeWorld` (sim world only) turns off `FIRE_DAMAGE` and
  `FALL_DAMAGE`; `Player.isInvulnerableTo` reads them for the `IS_FIRE` tag (which holds `minecraft:lava`) and
  `IS_FALL`. `SimSurvival`'s `ALLOW_DAMAGE` refuses both tags too (it only refused `DamageTypes.FALL` before),
  and the flames are put out each tick so lava does not paint the screen.
- **No Wither Key drop.** The last starred death no longer calls `SimDoors.dropKeyAt` - that was the only key
  the sim ever made, so the **blood door now opens on a plain right-click** (main hand, nothing consumed).
  Wither doors still need a key, but none are built. Bring the drop back with per-room keys if they ever are.
- **Crypt zombies are not starred**, and neither is the prince's (a golden crypt is still a crypt). The
  mimic and the sim's `/summon` still spawn starred.
- **`/goto` for trap rooms and Teleport Maze** lands one block in from the entrance doorway (offset 14, the
  blaze rooms' number), at the doorway's floor, facing in. "The entrance" is the doorway a BFS from the
  Entrance over the decoded floor (`SimBuilder.floorPlan`, the room's own cells excluded) reaches first; a
  single-room load falls back to the first measured doorway.
- **`/goto museum` put him on the roof** because Museum is a 2x2 whose four tile centres are all inside solid
  pillars (decoded `Museum.json`: those columns are solid y58 to the roof at y119), so the first spot with two
  air on it was the roof. Every landing now needs cover overhead in both scan directions, and a column without
  one gives way to the nearest covered column in the same tile (rings out to 15). A tile with no covered spot
  at all falls back to the old uncovered scan.
- **The sidebar showed "k560l4"** because a score holder's name is drawn after its team prefix - the holders
  were plain `k560l0..15`. They are colour-code-only names now (`§X§r`, Hypixel's trick), the old holders and
  teams are cleared from the saved world once, and the score numbers are blanked (`BlankFormat`). The lines are
  only the title line, secrets, room and the Discord link: time, keys/doors, the floor line and the "not
  Hypixel" line went. Nothing in the sim needed them - `DungeonState` gets the floor from `setRoomSim`. The
  objective title stays `SKYBLOCK` for `SkyblockGate`. The Custom Scoreboard's sim branch is gated on
  `SimState`, so on Hypixel it still builds from the real sidebar.

## The 2026-10-04 round: traps, the designer's doors, and the map's etherwarp

**Every generated floor has a trap.** Trap rooms (database type `TRAP`, or the names Old Trap / New Trap
before the database loads - Arrow Trap is an ordinary room) are held out of the normal pool and asked for on
their own: a 30% roll once a stub is a doorway in, and every stub once the floor is within eight cells or five
rooms of done. The attempt scoring ranks "has a trap" just under "has blood", and if no attempt managed one
`SimFloorLayout.ensureTrap` swaps a trap in for an unpinned 1x1 NORMAL room whose links its doorways cover,
bricking any extra doorway it brings. Not measured over a batch of floors yet - scenario 73 is where that
belongs.

**The sim never builds a wither door,** so the designer can never show one on a generated floor. Doors come
out of `plan()` as NORMAL, BLOOD or ENTRANCE only. Adding them is not a drawing change: `SimMobs` fires the key
drop once, when the LAST starred mob on the whole floor dies, so one key exists per floor and it is needed for
the blood door. A floor with wither doors on the blood path would be unfinishable until keys drop per room.

**The map's etherwarp in the sim was slow because every hop waited to land.** The 2026-10-01 fix dropped the
predicted position after each hop, because the hop resolved from the CLIENT player's position and the previous
teleport had not arrived. Etherwarp hops now go through `SimAbilities.etherwarpAlong`, which resolves on the
integrated server from the server's own copy of him with the planner's ray (`TeleportUtils.getLook` +
`traverseVoxels`, sneaking eye 1.27) - which is how Hypixel does it - so the queue chains from the prediction
one hop a tick again. A hop that lands off its planned block reports back and only then is the position
re-read; a hop with no target stops the path; a queue that cannot find him on any hop for two seconds cancels.

(Superseded 2026-10-04: the sim now lands at + 1.05 too and the planner uses one value - see the end of this file.)
**"Off by one" had two causes.** The planner stood him at block top + 1.05 (QUOI's Hypixel number) while the
sim stands him at + 1.0, so every sim hop was aimed from an eye 0.05 too high; the sim now plans at + 1.0. And
QUOI's aim points are mostly 0.001 from a block's edge, with its top-centre point exactly ON the top face, which
the voxel walk files under the air block above - so from above the edge points were what got used, and a ray
grazing an edge flips to the neighbour on any rounding. `getEtherwarpDirection` now aims at face centres first
and only returns an aim after casting the real float yaw/pitch for exactly the hop range and seeing it land on
the block.

**Path search speed, measured outside the game.** The search was rewritten (`EtherSearch`) over a byte-per-
block grid (`LevelEtherGrid`): flags read once per chunk section straight from the `LevelChunkSection`, kept
across searches and dropped by packet hooks (`LiveMapPacketListenerMixin`, at RETURN of the block, section and
chunk handlers) or after 15 s; a primitive heap and open-addressed node map instead of `PriorityQueue`/`HashMap`
and four threads on one lock; a leg ends the moment any landing satisfies it; each leg is bounded to the two
rooms it joins (and re-run unbounded if that fails, so the bound can only make a search faster). The search has
no Minecraft imports, so `tools/bench/run.sh` times it on a 6x6 floor of the shipped 1x1 captures with a 3x4
doorway carved through every seam, keeping only the 31 of 60 seams that a walk proves join both rooms (a
capture's own doorways are wherever they were in the instance he walked). 2,000 clicks of 1 to 7 rooms, warm
JIT, his default 6/7 fan (995 rays), through the game's section-table grid:

| | mean | median | p90 | p99 | worst |
|-|------|--------|-----|-----|-------|
| sections cached | 0.47 ms | 0.32 | 0.91 | 3.5 | 10 |
| every section filled fresh | 0.60 ms | 0.45 | 1.15 | 3.8 | 10 |

(Superseded - these numbers were on a bench that read most of every room as air; see "The path search, round
two" below.) 99.8% of clicks found a path. Before, from his own Map Logger log (2026-10-01, 19 successful clicks timed
by the old chat line): median 6 ms, worst 153 ms, and eight "Failed after ~675ms" timeouts. The tail that is
still over 2 ms is legs where weighted A* needs 60-300 expansions (about 30 us each) to find a way round a
wall; a click reads a median of 38 sections (p90 92). Not covered: the bench's flags come from palette NAMES,
not `TeleportUtils`' instanceof rules, and the "fresh" row fills by copying an array, which is cheaper than
reading a real section - so a first click in a new area costs somewhat more in game than that row. The
`[Path] ... total N ms` log line is the in-game number.

## Blank cells, theoretical wither doors, and the path search, round two (2026-10-04, later)

**Blank cells were the recency change.** "the more maps I generate the more it seems to not put rooms in."
`tools/layoutsim/run.sh` runs the real `SimFloorLayout` + `RoomDoors` outside the game over his own library (his
Map Logger rooms folder plus the shipped captures, minus the 28 the tile audit refused - 106 usable, exactly what
his log says). Starting from his saved `killer560smod-sim-recent.json`, 1000 F7s: 63% had an empty cell and 16%
failed `LayoutSim`'s structure check (one blood, one trap, no overlap, every room reachable); with recency off, 18% and 1.4%. The cause: `choose` subtracted
`recency * 2.0` from the PLACEMENT score, as much as two stranded cells cost, so a fresh room that walled a cell
in beat a recent one that fitted. Variety also went past the point of usefulness: consecutive floors shared 0.6
rooms, against about 3.5 by pure chance. Now recency only orders the shortlist (weight 1.5; table in the javadoc
of `RECENCY_ORDER_WEIGHT`), the sixty-room shortlist cap only ends a search that has found something, and
`fillGaps` fills whatever the growth leaves: an unused room meeting an open doorway; else a 1x1 neighbour turned
or swapped so a measured doorway faces the hole (all its doors kept); else, last, a 1x1 whose door is carved
through the neighbour's wall. One WARN per floor that needed it, another if a cell is still empty. `ensureBlood`
swaps the blood room in for the deepest 1x1 dead end when no attempt placed one. Result, 2000 floors from his
file: 0 with an empty cell, 1 without blood, consecutive floors share 5.0 rooms. Most holes the growth leaves are
corner cells walled in by 2x2 rooms, which only a carve fills - about a quarter of floors get one carved door.

**Theoretical wither doors.** `SimWitherDoors`: every ordinary door on the room path from the Entrance to Blood
(the wiki: "The path to the Blood Room is guarded by a series of Wither Doors"), worked out when the floor is
published. Drawn on the sim's live map in the wither colour with a SINGLE amber outline (a real locked one has
two) and in the Map Designer like a wither door. `isTheoretical` is false unless `SimState.isActive()`, and the
table is only filled by `publishSimFloor`, so a Hypixel map never shows one. They stay ordinary doors to every
pathfinder.

**The path search, round two.** Three causes, from his log:
- *"Room hop N failed" right after a build, fine after teleporting there*: the sim writes a floor without telling
  clients, and with Keep Chunks Loaded / the Chunk Cache his client keeps every chunk it ever had - so every chunk
  not re-sent since the rebuild held the PREVIOUS floor (at its own altitude). `LevelEtherGrid.mirror` snapshots
  the built floor from the server level at the end of the build; a column reads from it until his client
  receives that chunk or a block change in it. Sim only; dropped on leaving and on a single-room load. Not
  reproduced offline - the reasoning is the log pattern plus `ChunkCacheManager`'s own "Known limitation".
- *"No single-room path ... within 57.0 blocks a hop", 670 ms, ten times*: the target was Tic Tac Toe's chest
  (Auto Tic Tac Toe walking to it), which in his capture sits in a walled alcove - solid with two air above, so
  "etherwarpable", and unreachable. Rebuilt in `tools/bench` (`-Dttt=`): the room alone is exhausted after 295
  nodes. A same-room target is now searched inside the room, exact first and then any landing within 5 blocks,
  and the last leg of a multi-room path gets the same near fallback; the old unbounded search still runs if both
  fail. On Hypixel this only changes a click that used to fail.
- The fan (6 x 7 degrees) can miss a single goal block from every node; the A* now also tries a verified `aim`
  at the goal from every node it expands.

**The bench was reading most of every room as air.** `EtherSearchBench` cut each palette at its first `]` -
the one inside `chest[facing=north]` - so every later state was air. Fixed; the 2026-10-04 table above is too
optimistic. On the fixed bench (2,000 clicks, sections cached): before mean 2.21 ms, median 0.84, p99 16, max 33,
99.5% found; after mean 1.96 ms, median 0.76, p99 13, max 20, 100% found.

## The 2026-10-02 puzzle round

Eight reports, one fix each. Where a rule came from somewhere other than the code, it says where.

- **Water Board: the back lever drives the TOP water.** It used to cut the sealed column right behind it at
  `(15, 59..62, 4)`, which on Hypixel just runs. Decoding `Water_Board.json` at rotation 270 puts the board's
  own feed far above: sources at `(15, 91|95, 20)`, a channel along y 89 to z 26 and the fall down the board's
  face at `x 14..16, y 82..88, z 26` - 31 blocks, all at y >= 82. Everything from relative y 75 up is read at
  arm time with its exact level and is what the lever removes and restores; the column behind the lever is
  never touched. A restore never writes over a piston slot that has been pushed into the fall.
- **Blaze: the label stand is a MARKER.** A plain invisible stand keeps its 0.5 x 1.975 hitbox, so it sat on
  every blaze catching his arrows and punches ("a hidden one above it... I cannot hit it"), and the blaze's own
  visible name made a second label. `setMarker` is private; the flag goes through `ArmorStand.DATA_CLIENT_FLAGS`
  / `CLIENT_FLAG_MARKER`, public and identical in 26.1.2 and 26.2, and `onSyncedDataUpdated` refreshes the box
  on that key. A marker also fits Odin's "1 under the stand" offsets better: from `bbHeight + 0.1` it lands on
  the blaze's centre, where the full stand put it at the head. `SimMobs`' star tags are still full stands.
- **Teleport Maze: the route to the centre is checked pad by pad.** On paper the pairing was already right
  (20,000 simulated draws: every successful one reached the exit), but nothing verified it at bind and a draw
  that failed 500 times left `link` empty, which makes every pad dead. `routeToCentre` now walks exactly what he
  does - walk to any pad of the chamber, step on it, land where it links - and a draw is only kept if it reaches
  the end pad; the shortest route is logged at bind. A fixed chamber chain is the fallback, never an empty table.
  The decoded floor confirms the start pad is alone in the entrance chamber and the end pad alone in the centre.
  **Not reproduced**: no defect was found in the pairing itself, so if a run still never reaches the middle,
  the bind log line now names the route to compare against.
- **Ice Path: a silverfish out of range is not a dead one.** `level.getEntity(uuid)` only sees entities in
  sections the server is tracking, so while Ice Path was beyond his range the tick read null and the old
  branch dropped `fishId` and `cell` for good - "sometimes", depending on where Ice Path landed relative to him
  when the floor finished. A refused spawn also left the room unarmed. Now the state is kept, and a fish that is
  missing while he is within 24 blocks of its cell is put back there (strays on the board are cleared first).
- **Creeper Beams: a second lantern always draws a beam.** A held button re-fired the pick every third tick
  (the stamp was only written on a pick), so the second shot of a pair flickered between "not that pair", a new
  hold and a drop. The stamp is now refreshed on every re-entry, so one hold is one click. Any second lantern
  draws the beam and burns both to prismarine; a wrong pair is drawn red and costs those two lanterns. The wiki
  calls the room non-failable and wants "four different beams" through the creeper, so a wrong pair does not
  fail it and four right pairs solve it.
- **Ice Fill: the auto overshot, and a slip painted the room red.** Auto Ice Fill hops one tile by aiming from
  the eye at the next tile's feet point. `SimAbilities.dashTarget`, once the look drove the box into the floor,
  slid at that height for the full range - six blocks at that angle - so the hop left the path (the auto then
  finds no path point under him and stops) and the puzzle read it as "teleported off the ice". A settled walk
  now ends where the EYE ray meets a block, which is also what the cited measurement says (35 degrees down
  moved 1.6 blocks; the eye ray meets the floor 2.3 out, the old slide went 9.8), and it may step up one block
  the eye can see over, which is how the auto climbs between sections. Separately, `breakSection` called
  `SimPuzzles.reportFail`, which is what turned the room red; a broken section regenerates, so it reports
  nothing now, and `SimRoomState.markFailed` refuses Ice Fill and Ice Path outright.
- **Boulder: random known pattern, 3x3x3 boxes, buttons that push.** The wiki: boxes are "3x3x3 blocks of wood
  planks with buttons", a press moves the box away from the button, and "if the box is at the very back... the
  box will disappear". The bundled data settles the rest: a press moves the box ONE cell; if that cell is off
  the grid or taken, the box disappears. Played over all eight patterns, all 18 solution steps find their box
  and an empty button cell, and every pattern goes from no row-9-to-row-24 path to an open one - which is the
  solved test. Plain push fails 6 of the 18 steps, pull fails 4. Buttons are wall buttons at y 65, one block
  out from the middle of each face whose neighbour cell is on the grid and empty (the capture's 31 follow that
  rule), and are laid again after every move. The puzzle cannot be failed.
- **Three Weirdos: visible NPCs, and they speak when talked to.** The three `[NPC]` lines were sent on the
  client in the same call that queued the stands' spawn on the server, so the solver read each line before its
  stand existed client-side, logged "No ArmorStand named ...", and gave up - no highlight, so the auto had
  nothing. Now three visible stands (arms, name shown) are placed at bind, one database block of -x from each
  chest through the room's anchor, each with an invisible "CLICK" stand under its name - the word Auto Three
  Weirdos looks for. Talking to either (right or left click) sends that weirdo's line, and the walk-in check
  moves them if the live map's transform disagrees with the anchor. **Lesson: never send a chat line that
  refers to an entity in the same tick as queuing that entity's spawn** - the solver reads the line first.


## The 2026-10-04 auto puzzle round (Map Logger log, 14:03-14:12)

- **Tic Tac Toe: an ability item ate the button press.** `SimAbilities`' `UseBlockCallback` consumed every
  right-click on a block while an AOTV/AOTE or wither blade was in hand, so Auto Tic Tac Toe's press (it never
  swaps item) became an Instant Transmission or Wither Impact and the board never changed. The log has no
  game result at all in that room, and an "no etherwarp target there" earlier shows the AOTV in hand. Vanilla
  and Hypixel let a button, lever or chest win unless you sneak; the sim now does the same. Not reproduced in
  game - the new `TicTacToe: clicked ... holding ...` INFO line will say what was in hand. Separately, the
  chest side trip only "arrived" standing on the chest's lid, so it always waited out its 15 s timeout; it now
  ends once the chest is in aura reach (Hypixel too).
- **Blaze: the sim's Terminator cast Salvation on a right click.** Salvation is its left-click ability; the
  sim fired it on whichever click came after three hits, so Auto Blaze (right-click only) sent a five-pierce
  beam up the chain every fourth shot - "Salvation - 2 hit" in the log is two blazes in one beam, an
  out-of-order kill. Right click now always shoots. The side arrows went from 8 to 5 degrees, the angle Auto
  Blaze's safety check assumes (QUOI's number, not measured on Hypixel here). And a fail never removed the
  label stands - only an in-order kill did - so after one fail the solver held ghost "10/10" stands and the auto
  shot at empty air; a fail now drops every label, and `despawnCurrent` snapshots the labels it removes so a
  bind on the server thread cannot lose the new chain's. The aim offsets were left alone: Odin's
  `centre - 1` from a marker stand at `bbHeight + 0.1` is the blaze's middle. `[AutoPuzzles] Blaze:` INFO lines
  now log each target, each shot and a missing clean shot.
- **Teleport Maze: the centre has a chest and a way out.** The capture holds no chest and the database lists
  no secret, so the sim places one at relative `(15,69,17)` in the centre chamber (interior x 12..18, z 14..20,
  walls at 11/19 and 13/21). A click on it from outside that box is refused with FAIL (no packet, not counted -
  registered before `SimMimic`). The end pad `(15,69,14)` used to do nothing; stepping off it and back on now
  sends him to the Interactive Map's entry spot for the room (`AutoClearUtils` `(15,68,-2)`, standing on it),
  falling back a block or three into the doorway if that is not standable in the build. The decoded capture
  confirms the centre is walled from the start pad at z 13, so Auto Teleport Maze's Hypixel finish (walk to the
  start pad) cannot work in here; in the sim only, it steps off the end pad and back on instead.
- **Auto Teleport Maze holds the free camera** (`ViewFreeze`) every tick from the first maze teleport until it
  stops or finishes, starting from the view of the tick before that teleport. `rotateCamera`'s per-hop lease was
  400 ms against walks of up to 3 s.

## The 2026-10-04 puzzle round (Map Logger log 14:12)

- **Auto Ice Fill froze because the sim teleported along the CAMERA, not the aim.** `SimAbilities.dashTarget`
  read `player.getViewVector(1f)`, which goes through `getViewXRot/getViewYRot` - and `Ap3ViewYawMixin` answers
  those with `ViewFreeze`'s held view while an auto is turning him. So every auto hop went where his camera
  pointed (his own look, or after a lapsed lease the previous hop's aim): fine on a straight run, off the path
  at the first turn, where the auto finds no path point and stops. Verified in the 26.1.2 bytecode:
  `getViewVector` calls `getViewXRot/getViewYRot`, `getLookAngle` calls `getXRot/getYRot`. **Anything in the sim
  that resolves an aim uses `getLookAngle`** (dash, Mage beam, Spirit Sceptre, Superboom/breaker pick); the
  etherwarp resolver and the Terminator already read the real rotation. The 2026-10-02 "stop where the eye ray
  meets the floor" fix was geometry and was right, but never the cause. Auto Ice Fill now logs every hop and
  every stop reason as `[AutoIceFill]` INFO lines, so the next stall names itself.
- **Water Board: the back lever is the lapis slot.** Decoded `Water_Board.json` (capture x,z = relative z+1,
  x+1): the top water always runs and rests on a `lapis_block` at relative (15, 82, 26), pushed by an extended
  sticky piston at (15, 82, 28) with a redstone block behind it - the one board slot no ore lever owns. Pulling
  it back drops the water down the one-wide shaft at x 15, y 78..81 into the maze plane (z 26), front glass at
  z 25. The lever now moves that slot; the old version deleted and restored the top water with no fluid
  updates, so nothing ever flowed. Slot moves write the maze-plane cell with `UPDATE_ALL` (water re-ticks and
  flows or drains) and everything else with no updates; each slot's power block (relative z 29: redstone when
  out, polished andesite when in, as every slot in the capture is) moves with it, so a piston head next to
  flowing water that forwards an update to its base finds it already agreeing. No piston sits directly under
  another, so nothing is quasi-powered. **The puzzle cannot fail any more** and the back lever always works;
  an off-script click is named in chat and nothing else. Water leaving the maze's five bottom gaps spreads on
  the room floor at y 59 - not measured in game.
- **Ice Path's silverfish pushed him from the CLIENT.** The `SimSilverfish` overrides exist only server-side;
  the client builds a plain `Silverfish`, and on the client a non-player entity's `pushEntities` selects
  exactly the local player (`EntitySelector.pushableBy`, 26.1.2 bytecode). The fish now joins a scoreboard team
  (`k560_icepath_fish`) with collision rule NEVER, which is synced and makes `pushableBy` return push-nothing.
  **A behaviour override on a server-side entity subclass does nothing on the client.**
- **Creeper Beams: the far lantern was out of reach, not refused.** The only ways in were a left or right
  click ON the block, which vanilla only produces within 4.5 blocks; the Terminator's arrows hit nothing the
  puzzle listened to and the Mage beam only looked for mobs. `SimCreeperPuzzle.shotAlong` now resolves a shot
  (Terminator centre arrow, Salvation, Mage beam with no mob in the way) as a block ray at fire time; the
  three-tick same-lantern guard absorbs a shot and a click on the same lantern in one tick.
- **Boulder: reward chest and no etherwarp.** The chest goes on the top step of the far staircase, capture
  (30, 66, 16) - three steps at x 28..30 across z 14..18, back wall at x 31, opposite the raised doorway side
  at low x - facing back into the room, the facing taken from where the paste put (29, 66, 16). Written only
  into air and logged. The box grid and its barrier ceiling are what keep it until solved. Etherwarp (sneak +
  etherwarp item, and the Interactive Map's `etherwarpAlong`) is refused while standing in Boulder, with
  "No etherwarp in Boulder"; Instant Transmission is not. Auto Boulder still says "no chest position known":
  it reads the room database's chest secrets, which do not list this chest on Hypixel either - untouched.

## Capture rotation by vote, and how Hypixel does it (2026-10-04)

killer560: "make there be a way to tell for rotation struggling rooms. There is some way it is done with secret
waypoints so figure it out." **There is not, on Hypixel.** The live map's only mechanism is the roof marker:
`LiveMapFeature.findRoomRotation` -> `RoomDatabase.findRotationAndCorner` (blue terracotta at one of the four
roof corners). No secret matching, no doors, no per-room table. A room whose marker is not read yet logs
"No blue-terracotta corner marker ..." ONCE and is then retried every second (`rotationRetryAtMs`) until the roof
chunk and `getHighestY` read right; until then it is left out of `identifiedRoomsWithRotation`, so its waypoints
simply do not draw yet. The log line is the first failed attempt, not the outcome. The one exception is Fairy,
fixed at rotation 0 by its room TYPE. A real Hypixel room always has its marker; a capture does not, which is why
the capture side needs more than one source.

`RoomCaptureRotation` now votes, and the answer feeds the same cache (`of`) that `SimRoomIndex`, `SimSecrets`
and `SimBuilder` read, so the paste, the published rotation, `PuzzleCoords` and the waypoints cannot disagree.
Over the rotations the database's secrets can FIT (the long-room shape veto, unchanged):

- roof marker: 2 votes for a single marked corner, 1 each for two;
- lapis corner: 1 vote. Roof corners are redstone blocks, and a lapis block sits DIAGONALLY OPPOSITE the blue
  terracotta - in all 85 captures that have both (measured by `tools/layoutsim/rotation.sh`). So it names the
  marker's corner when the marker itself was not captured. An observation about the captures, not a Hypixel rule;
- one vote per database secret that lands: chest secrets on a chest or trapped chest, wither essence AND
  redstone key secrets on a player head. **Redstone key positions are player heads, not levers** - the old
  tie-break looked for a lever there and never matched (Golden Oasis, Redstone Crypt, Redstone Key all show the
  head). Bats and items are skipped; neither is a block.

Outright winner or 0. INFO once per room per library load (lazily, when the room is first placed) with every
score; WARN when the marker and the secrets disagree, and when the answer is uncertain.
`RoomCaptureRotation.isUncertain(name)` / `verdict(name).reason()` are public for Auto Routes to warn before
recording: uncertain means no outright winner, fewer than two votes, a source preferring another rotation, or the
marker/secrets pointing only at a rotation the shape forbids. Sim only - on Hypixel the rotation is read live.

**Hypixel behaviour is unchanged**: nothing in `livemap`, `secretwaypoints` or `roomdatabase` was touched, and
this class is consulted only for captured rooms in the sim.

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

**The four uncertain rooms are not a rotation problem, they are bad captures**, the same fault `RoomTileAudit`
already refuses in 28 others (Skull, Bridges, Pedestal, Slime, Gravel, Doors, Wizard, Waterfall show the identical
signature and ARE refused). Catwalk and Purple Flags: the marker and the tile-0 secrets agree on a quarter turn
that a long-along-x box cannot have, and every secret beyond tile 0 is out of the box - the room ran along z and
the box was laid along x, so tiles 1+ are a neighbour. Archway and Balcony: nothing lands until the room is moved
one tile, so the box is anchored a tile off. No rotation is right for any of them; a route recorded there cannot
match Hypixel until each is walked again. The audit only misses them because their neighbours' tiles are not
captured anywhere else to compare against.

## The sim answers packets, like Hypixel's server (2026-10-04)

killer560: "If I make an auto route on sim it will still work the exact same on main, right? That is my entire
reason for creating the sim." It did not: `SimAbilities` and `SimItems` reacted to the CLIENT's `gameMode` calls,
so a raw packet did nothing, and `RouteExecutor`/`ClearExecutor` had grown sim-only branches calling
`etherwarpAlong` / `superboomAt` / `dungeonBreakAt` directly, plus a sim landing height (+ 1.0) with matching sim
branches in `ClearNode`, `ClearExecutor`, `EtherwarpPathfinder` and `EtherSearch`.

- **Where the abilities live now.** Fabric's `UseItemCallback`, `UseBlockCallback` and `AttackBlockCallback` are
  common events; their server copies fire inside `ServerPlayerGameMode.useItem` / `useItemOn` /
  `handleBlockBreakAction(START_DESTROY_BLOCK)` with the `ServerPlayer` (checked with javap in
  fabric-events-interaction 5.2.2 and 5.2.8; `handleUseItem` snaps the server player to the packet's rotation
  before `useItem`, 26.1.2 and 26.2). The sim's abilities run there, from the server player's position, rotation,
  `isShiftKeyDown` (set by `ServerboundPlayerInputPacket`) and held item, and teleport with
  `teleportTo(..., {Y_ROT, X_ROT} relative, 0, 0)` so the client gets a position packet and keeps its camera.
  No new mixins.
- **The client halves only stop vanilla prediction.** For an item the server handles, the client callback returns
  SUCCESS, which makes Fabric send the same packet vanilla would and skip the client-side use (no predicted TNT
  placement, no bow draw); left clicks in the sim return SUCCESS (START goes to the server, no local mining),
  except a Creeper Beams lantern, which returns FAIL so no START follows the connect.
- **Rules kept, now checked against the server player:** trap rooms and the Teleport Maze (room at the server
  position), no etherwarp in Boulder, breaker locked before a generated floor starts, no breaker in puzzle rooms
  or on secrets, one charge per block. The held-button "one charge per press" guard moved to the server: a START
  for the block just broken within 3 server ticks of the last START for it is the same press.
- **Charges are in the lore.** `SimBreakerState` keeps the Dungeon Breaker's "Charges: N/20" lore line current on
  the server, so Auto Routes reads charges from the lore as on Hypixel (the 0-ping breaker reads the same line).
- **Landing height is one value.** Etherwarp lands at the collision top + 1.05 (`SimAbilities.
  ETHERWARP_LANDING_OFFSET`), then falls; the planner always plans 1.05 (`EtherwarpPathfinder.STAND_OFFSET`).
  Instant Transmission still lands on a whole y - that is the measured Hypixel behaviour and what
  `ClearNode.AotvNode` (+ 1.0) already predicts. The ray is the planner's (`TeleportUtils.traverseVoxels` with a
  `Level` argument, eye 1.27, `getLook`), run on the server level.
- **Weapons aimed by the packet.** Spirit Sceptre and Terminator right clicks are resolved on the server too, which
  hands the client-side flight code the server player's eye and rotation through `SimAim`. Their LEFT clicks (Mage
  beam, Salvation, Terminator left shot) are still read off the client's attack key in `SimClass`: a left click in
  the air sends no packet but a swing, and nothing in the mod automates those.
- **Removed client branches:** `RouteExecutor` (sim etherwarp, sim BOOM, sim BREAKER, sim charges),
  `ClearExecutor` (sim range 57, sim landing 1.0, `etherwarpAlong` hop + result callback, sim "lost the path"
  40-tick cancel, sim wait-to-land after non-etherwarp hops), `ClearNode` (sim 1.0 / 57),
  `EtherwarpPathfinder` (the `offset` parameter), `DungeonBreakerFeature` (sim gate; dead anyway, since the sim's
  client callback cancels `startDestroyBlock` at HEAD before its TAIL hook).
- **Still different, and why:** `TeleportUtils.underCover` / `EtherwarpPathfinder.coverTest` (the sim's rooms are
  separate towers with open sky between them; Hypixel's dungeon is solid rock), `AutoTeleportMaze.afterChest` (the
  capture walls the centre chamber off from the start pad) and the y offset everywhere (`SimAltitude`). (Secret
  Aura's essence test was on this list until 2026-10-05; the sim now places Hypixel's essence skull, so it is not.) Each is the sim's WORLD differing, not its
  server; fixing them means changing what the sim builds.
- **Auto Routes recording warning.** Starting a recording or `/ar add` in the sim in a room whose capture rotation
  is uncertain (`RoomCaptureRotation.uncertainForRecording`: no marker, ambiguous, or overruled) says once that the
  route may come out rotated on Hypixel until the room's capture is fixed.

## The path to blood is laid first (2026-10-04)

killer560: "I just generated a map where fairy was not on the path to blood and it had far more than the 5 the
slider had picked for it" (his log: `blood went in at depth 11 WITHOUT the fairy on the way to it`).

**The definition, unchanged:** "Rooms to blood" counts the rooms on the Entrance-to-Blood path NOT counting the
Entrance, the Fairy or Blood ("Do not count blood, green room, or fairy those are given", 2026-09-28). So the path
is exactly Entrance, the slider's N rooms with the Fairy among them, Blood - N + 3 rooms, N + 2 doors. The path is
measured through the doors the build writes, which is also what `SimWitherDoors` walks.

**Root cause, three parts.**
- The growth dropped blood in at the first stub whose depth was AT LEAST the target (`stub.depth + 1 >=
  bloodDepth`), so the slider was a lower bound; and only "while blood is still owed" did anything steer it.
- `SimFloorGen.plan` passed slider + 1 as the blood DEPTH, which has no room on the path for the fairy - a
  floor that did put the fairy on the path came out one short.
- The door pass (which turns layout links into a loop-free tree) took every link touching the entrance or blood
  first, then the rest in layout order. A loop link from the entrance to a deep room became that room's door,
  so the path through the doors was not the one the layout built and counted - usually shorter, sometimes
  longer, and the fairy fell off it.

Measured with `tools/layoutsim -Dsweep=true` (every floor size x slider 2..8 x puzzles 2..5, his Map Logger
library of 134 rooms and his saved recency, judged through the real door graph), 4,480 floors BEFORE: path
length wrong on 84.8% (from 6 short to 9 long; right on 15%), fairy off the path on 64.1%, puzzle count not the
slider's on 40.2%, room minimum missed 0.18%, no blood 0.02%.

**The fix.** `SimFloorLayout.growOnce` plans the path before anything else grows (`planSpine`): a bounded
depth-first search with backtracking from the entrance, ordinary rooms with at least two doorways, the fairy at a
drawn position 2..N (never next to the entrance or blood), blood last and not touching the fairy's cells, each
joined to the one before through a measured doorway in both rooms. The rest of the floor then grows around it.
The `Floor` record carries the path's links (`spine`), and `SimFloorLayout.doorLinks` - now the ONE place the
door tree is decided, called by `SimFloorGen.plan` and by `tools/layoutsim` - takes them first, so any other link
between two path rooms is a loop and is refused, and no other link to blood is ever a door. Path rooms are never
the room `ensureTrap` swaps (unless nothing else can take the trap; a trap still counts as one of the slider's
rooms) or `ensurePuzzles` swaps. A pinned Fairy or Blood is the path's goal at its own cell (reached through one
of its own doorways, at an allowed step), and the entrance seat is drawn within reach of it. If no attempt can
lay the path the old growth runs as a fallback and says so in the log; it never fired in the runs below.

**Puzzles had to be fixed with it.** The path spends the doorways the growth used to hang puzzles from, and the
miss rate went from 40% to 64%. Now: the growth asks for a puzzle on every stub once the cells left are few
(`cellsLeft <= puzzlesLeft * 2 + 2`), the fill pass uses an owed puzzle first and no puzzle once none is owed,
then `ensurePuzzles` swaps owed puzzles in for ordinary 1x1 dead ends off the path, and last puts one in an empty
cell, past the cell target if need be. The fill pass also keeps going past the cell target while the floor is
short of its room minimum, and a trap that `ensureTrap` cannot swap in goes into an empty cell.

AFTER, 22,400 floors (100 per combination, his recency): path length right on 100%, fairy on the path 100%,
`SimWitherDoors` exactly the path's ordinary doors 100%, every cell target met, one blood, one trap, one fairy,
every room reachable through doors, puzzles right on 99.99% (3 floors one short), room minimum missed 0.12% (27,
all full 36-cell F5/F7 grids where big rooms covered every cell with 20 rooms - the same failure as before).
Recency off, 11,200 floors: the same, 0.07% room minimum. One ordinary room pinned (2,240), a pinned Fairy (672)
and a pinned Blood (672): path and fairy 100%. Variety unchanged: consecutive F7s share 3.6 rooms (3.9 before),
123 of 134 rooms used. Cost: 3.7 ms a floor (1.2 before), 99 ms with a pinned fairy, 260 ms with a pinned blood.

**Slider values that cannot be met:** none in these runs - every floor size takes every slider value 2..8 with
2..5 puzzles. Pins can make it impossible - a Blood pinned next door to a pinned Entrance is reached at
one room, and no attempt can lay a longer path to it - though a Fairy or Blood pinned at a random cell never did
in 1,344 floors. Then the floor falls back to the old growth (approximate length, fairy maybe off the path) with
a WARN, and `SimFloorGen.plan` WARNs the length it got against the one asked for. The designer's status line shows
the measured count, not the request.

A trap found on the way: a pinned room's `Candidate` is a separate object from the pool's (`resolvePins` builds its
own through `candidateOf`), so `candidate == fairyRoom` is false for a pinned Fairy. The first pinned-fairy runs
fell back on every attempt because of it. Compare given rooms by name.

## Auto Ice Path never shoved, and Auto Boulder had no chest (2026-10-04, Map Logger log 17:23)

- **The sim read the shove direction off the arrow entity, which is never the aim.** `Projectile.shoot` sets an
  arrow's yaw to `atan2(x, z)` of its velocity (26.1.2 bytecode) - the mirror of a look yaw's `atan2(-x, z)`; a
  shot at pitch 90 has almost no horizontal velocity, and `SimTerminator.fromAngles` even points that sliver
  backwards (`cos` of the float pi/2 is -4.4e-8); and an arrow hitting the invulnerable silverfish is deflected
  with `ProjectileDeflection.REVERSE` (about 180 degrees). So every Auto Ice Path shot shoved along a flipped
  board axis - into a wall, where nothing moves and nothing was logged. `TerminatorArrow` now carries the aim
  (`SimTerminator.shotYaw`), `pollForArrow` shoves with that (any other arrow: its shooter's yaw), discards all
  three arrows of the shot, and every shove or wall-shove is an INFO line. **An arrow's `getYRot()` is not where
  it was aimed.**
- **Auto Ice Path's shot yaw was an etherwarp aim.** It took the yaw of `etherwarpDirection(nextSpot)`, whose
  top-centre point never matches from above (filed under the air block), so the point used was 0.001 from an edge
  of the next block - up to 45 degrees off the board axis for a one-cell stop, on Hypixel too, and no shot at all
  when nothing was visible. The yaw is now cell centre to cell centre. `AutoReposition` aims with
  `AutoPuzzleUtil.etherwarpAim` (TeleportUtils' verified face-centre aim, QUOI's as fallback) and logs start,
  arrival and refusal. Every Auto Ice Path gate logs once per change as `[AutoIcePath] ...`, each shot as
  `[AutoIcePath] shot N from ... toward ...`, and three shots from one cell without a slide WARN.
- **Not proven from the log which gate stopped it** - the old code had no log line in any of them. The sim fault
  above is certain from the bytecode and would have stopped it on its own; the next log names the rest.
- **Auto Boulder finds the chest block.** The room database lists no Boulder chest (Hypixel or sim) and the
  capture holds none either (taken before a solve), so the old "no chest position known" stop is gone: with no
  database chest it scans the room (relative -1..31, y 60..75, through `PuzzleCoords`; section palettes first)
  every 20 ticks for a CHEST/TRAPPED_CHEST and takes the back-most. Identical on Hypixel and in the sim. The
  sim's chest, capture (30,66,16), is relative (15, 66, 29): the alcove at the back middle, up the three steps
  at relative z 27..28, under the oak-log mantle at y 69 - the only alcove in the decoded room. Hypixel's real
  chest position is still unverified; the scan does not depend on it. If the chest is already within 4.5 blocks
  it auras without walking.

## The 2026-10-04 puzzle round (Map Logger log 18:01, fix-puz3)

- **No auto ever held a bow, because his Terminator was older than its tooltip.** The log repeats "[AutoIcePath]
  waiting: no shortbow ... in the hotbar" and Auto Blaze "holding 'Aspect of the Void', which is not a shortbow".
  `SimItemLore`'s table has had the `§6Shortbow: Instantly shoots!` line since 2026-10-01 and `loreContains`
  strips colour codes correctly - but the sim's save is reused (`SimWorld`), so the player's inventory is too, and
  the Terminator in his hotbar (read out of `saves/killer560s-dungeon-sim/players/data/<uuid>.dat`) still carries
  the pre-10-01 lore: "Ability: Salvation / Shoots 3 arrows at once. / (blank) / LEGENDARY BOW". **Fixing the
  item table fixes no item already in a saved inventory.** `SimItemLore.register` now re-applies the table, every
  20 server ticks while the sim is active, to any stack whose id is in it and whose lore reads differently (the
  Dungeon Breaker's "Charges" line is ignored in the comparison and kept) - what Hypixel's server does whenever an
  item's lore changes. Separately, only Auto Ice Path swapped; Auto Blaze and Auto Creeper Beams only checked the
  hand. All three now call `AutoPuzzleUtil.holdShortbow` (HELD / SWAPPING / NONE: any hotbar item whose
  colour-stripped lore contains the phrase, one swap a tick, shoot on a later tick). The real Hypixel Terminator
  carries the same line, so it is one rule in both places.
- **Higher Blaze's blazes sat above the top landing.** `SimBlazePuzzle.bindAt` searched for the floor DOWN from
  Higher Blaze's top, and the first air-over-solid on the way down is the cobblestone landing at capture y 118, so
  all ten went into the 11 blocks between it and the ceiling, one apart (log: "heights [1..10]", first stand at
  y 310.9 while Auto Blaze stood on relative 88). Decoded, Higher Blaze (y 65..133) and Lower Blaze (15..83) are the
  same shaft 50 blocks apart: floor block at 69 / 19, air from 70 / 20 to a polished-andesite ceiling at 130 / 80,
  iron bars up the middle (Higher's bar runs 71..129). On Hypixel the blazes float through that shaft around the
  bar ("a tall chamber with blazes" - wiki; exact heights are not published and cannot be captured, a blaze is an
  entity). Both rooms now start on the shaft floor and spread up to 5 apart through the clear air above it,
  relative 71..116 in Higher Blaze - the band QUOI's `HIGHER_SPOTS` (85..118) shoot into. Kill order unchanged.
- **Creeper Beams cannot be failed.** A wrong second lantern used to burn both to prismarine and leave a red line,
  using up a pair the room needed. Now it flashes a red line for 2 s, drops the held end and changes nothing (no
  block, no sound), so the solver and Auto Creeper Beams see nothing happen. On Hypixel the room is non-failable
  (wiki: "Failable: No"); four beams through the creeper solve it. In the log the pair-0 join at 18:09:45 had worked;
  that round's "nothing fires" was the bow above.
- **Auto Tic Tac Toe stopped after one move because of its own chest trip, not the board.** His config has
  `ticTacToeAuraChestEnabled: true`, so the first click starts the walk to the room's chest; the click logic waits
  for that trip. The walk stopped short ("the block itself cannot be reached"), then re-asked for a path every tick
  (about seventy "Already there" in four seconds), and the trip's 15 s timeout sat behind `if (ClearExecutor.isBusy())
  return`, so it could also outlive a stuck walk; he finished the game by hand (Draw at 18:09:19). The timeout is now
  checked first and cancels a running walk, a leg that has asked for 3 paths and is still not there gives up, every
  trip transition is an INFO line, and a move out of reach or a cell that used its three clicks says so once. The
  sim's board and the solver's reading of it were not at fault. Hypixel identical (the AI opens; the sim does too).
- **Puzzle rooms have exactly one door.** "quiz having two doors": the log has "the fill pass put ... Redstone
  Crypt at 5,0 through Quiz" - the carve cut a door through Quiz's wall, so a room hung off Quiz and the door tree
  (which only refuses loops) had to keep both. `tools/layoutsim -Dsweep=true` now judges "puzzle with != 1 door"
  (and reports trap door counts as info). BEFORE, 22,400 floors (100 per combination, his Map Logger library and
  recency, seed 1): **15.41%** of floors had a puzzle with 2 or 3 doors, every puzzle affected. Every route that
  links rooms now asks `SimFloorLayout.isOneDoor`: the growth sends a puzzle's other doorways straight to the
  bricked-up list, the loop pass skips puzzles, the fill pass never meets or carves into a puzzle and a puzzle it
  places takes one door, a woken pinned puzzle loses its stubs, and `doorLinks` joins puzzles last, one door each.
  A cell whose only neighbours are puzzles is filled by swapping the puzzle for an ordinary room (last step, after
  the carve) and the puzzle is owed again. AFTER, same sweep: **0** puzzles with != 1 door in 22,400 floors with
  recency and 22,400 without; path, fairy, wither doors, cells, blood/trap/fairy counts and reachability all 100%;
  puzzle count right on 99.96% (8 short, was 1) and room minimum missed 0.19% (42, was 15) - the same full F5/F7
  grids as before, slightly more often now that a puzzle can no longer be a hub. Traps untouched: 95-97% of traps
  are one-door dead ends here, as before.

## The map's etherwarp plans the whole floor at once, by warps (2026-10-04, fix-path)

killer560: "it is taking a lot of warps and taking like 40ms [...] Get it to the point where it is only a few ms
every time and prioritize using as few warps as physically possible." His Map Logger log: "11 leg(s) ... total
94.02 ms, 40 warp(s)", and other clicks at 13-40 ms with 32-40 warps.

**Two reasons it took so many warps.** The planner went room by room - one weighted-A* leg per doorway on the
room route, each bounded to the two rooms it joins - so it could never skip a room. And every move was a ray of
the 6 x 7 degree fan: from an eye 2.32 above the floor, pitch -6 lands on a flat floor about 22 blocks out and
pitch -13 about 10, and nothing between 22 and 57 blocks along a floor is ever hit, so long rooms were crossed in
9-to-20-block hops. A bench run with a 2-degree fan cut the warps by 30% on its own.

**Now** (`WarpGraph`, wired in `EtherwarpPathfinder.findDungeonPath` / `findDungeonPathToTile`):
- Nodes are the floor's landings thinned to one per 3x3 columns and height (the one nearest the square's centre),
  except along every tile's centre lines and in every doorway box, where each landing is its own node: every
  door sits on a seam at a centre line, and whether a long sight through it passes is decided by a block either
  way. Without those the graph took 12% more warps than every-landing; with them under 5%.
- An edge is a verified aim at a node within reach: the real float yaw/pitch is cast for exactly the hop range and
  must land on the block, the same rule as before. A line to the block's top centre that is stopped in its first
  60% is refused without trying the other 17 aim points (they are inside the same block); stopped later, the full
  aim runs. That cheap refusal misses 1.4-4% of the pairs the full aim accepts (bench `-Daimcheck`).
- A click is A* on (warps, distance). Once the floor is warm the heuristic is exact: a room click reads a per-tile
  distance field (breadth first backwards from the tile's floor band, built when warming completes), an exact
  block gets a backward breadth-first search from the nodes that can aim at it, stopped at his own landings.
- A node's edges are kept. Every section its rays read is recorded; `LevelEtherGrid` sets a touched section's old
  flags aside and the planner thread reports it to the graph only if the FLAGS changed (`processChanges`), so a
  lever or a chunk re-sent unchanged costs nothing and a door opening drops exactly the nodes that looked through
  it. Air sections are cached too now, so a chunk arriving can be compared.
- `EtherwarpPathfinder.tickWarm` (from `ClearExecutor`'s tick, Interactive Map on, in a dungeon) keeps warm-up
  slices running on the planner thread, the rays on up to six low-priority worker threads (a quarter of the
  cores), yielding to any click. A click before the floor is warm gets 40 ms on the graph and then the old
  room-by-room planner, which is kept as the fallback whenever the graph finds nothing.
- A room click now goes to ANY landing in the clicked tile's floor band (`etherwarpableInTile`'s first band), not
  the one block nearest him; his own words were "it can choose anywhere in that room whatever is fastest". An
  exact block that nothing can aim at falls back to the nearest landing within 5 blocks in its room.
- Nodes in a trap, a maze or Boulder may be landed on but never warped from (`AutoClearUtils.canPath`'s rooms).
- Hop range is the held item's 57 + tuners, minus one block, at most 60 (`ClearExecutor.hopRange`); it was 60 for
  everyone, which an item with fewer than three tuners cannot do.

**Measured** with `tools/bench/floor.sh` (`FloorBench`): whole floors from `SimFloorLayout.generate` over the
shipped captures, pasted with `RoomPlacer`'s transform, links carved and unlinked doorways sealed as `SimDoors`
does, the sim's roof rule on; random clicks from walkable spots, half tile clicks and half exact blocks; every
returned path replayed ray by ray. 5 floors x 300 clicks, seed 560, warm graph, 6 warm-up threads:

| | found | warps mean / median / max | mean | median | p90 | p99 | max |
|-|-------|---------------------------|------|--------|-----|-----|-----|
| old, room by room | 93.9% | 13.56 / 14 / 41 | 91 ms | 5.4 | 374 | 670 | 670 |
| new, warm | 99.9% | 7.62 / 7 / 21 | 1.33 ms | 0.76 | 3.3 | 5.3 | 9.8 |

Room (tile) clicks alone: median 0.38 ms, p99 1.4; exact blocks: median 1.9, p99 5.3 (the backward search is most
of it). The one click the new planner failed, the old one failed too (a start on a sealed ledge). No path was
invalid. Warm-up: about 15,000 nodes and 3 s a floor on the bench's flat array with 6 threads; the game's
section grid is slower, so expect several seconds in game, during which clicks fall back as above. Without
warm-up (a fresh graph each click) the graph search is useless - 21% found in 670 ms - which is why warm-up exists.

**Minimality.** Within its graph the search is exact: re-planning 200 clicks on the same graph with only the
geometric bound gave the same warp count every time (`SELF-CHECK`). Against a reference graph of EVERY landing
with the full 18-point aim, on 3x2-tile windows of generated floors (900 clicks): reference 3.48 warps, new 3.60,
728 identical, 95 one more, 3 two more; the old planner was 5.24, and 11 of its paths did not replay (a hop that
did not land where the next one started). The reference is not "physically" minimal either: it is still a fixed
set of 18 aim points per block.

**Regression test:** `tools/bench/regress.sh` (thresholds in `tools/bench/floorbench-expect.txt`) fails on more
warps, a lower found rate, any invalid hop, any click the old planner finds and the new one does not, or exact
heuristics changing a warp count. Not covered: the bench's block flags come from palette names, its grid is a flat
array, and nothing here has run in the game yet - the `[Path]` lines (one per click, one when a floor goes warm)
are the in-game numbers.

## The map's executor checks its landings; Tic Tac Toe, and the Teleport Maze's one loop (2026-10-04, fix-map3)

killer560 (Map Logger log `maplogger-latest2.log`, 18:02-18:11): "The interactive map is working really well, just
sometimes it is getting stuck and breaking. Also it does something really funny in tictactoe." Then three Teleport
Maze items: the map does not hand over to the auto at the maze, the auto walks into fences, and "make sure tp maze
only has one closed loop, not multiple."

**The executor trusted its prediction completely.** `ClearExecutor` fires one hop a tick from where the previous hop
was PREDICTED to land (QUOI's design, and the reason a 20-warp path takes one second). Nothing compared that with
the server: a hop it refused was "done" anyway, every later hop was cast from the wrong spot, and the path ran to its
end with him somewhere else (18:08:17: nine `[Sim] no etherwarp target there` in one second, then nothing). And a
path whose first spot he was no longer on when it arrived (it starts at his position when the click was planned)
sat in the queue for ever: `isBusy()` true, Auto Routes inert, every later click retargeting onto the same dead
queue. 18:07:53-18:08:16 is eight clicks, each "Found path", with no room change in between. The executor logged
nothing per hop, so WHICH wait held that one cannot be read off the log; every wait now names itself.
- Every hop's expected landing is recorded; each server position packet is matched against them (0.6 horizontally,
  1.3 vertically, so the first ticks of the fall still match). A packet leaving him where he already was is ignored
  (a rotation-only correction); one that puts him anywhere else aborts the rest - the automation rule "abort on any
  server correction" - and the same goal is planned again from where he really is, at most twice a click, with a
  chat line saying why ("Warp 4 put you 12.3 blocks from where it was aimed - planning again from here (1/2)").
- At most 6 hops in flight unanswered; 20 ticks with no progress (no hop issued or answered) gives up with the
  reason (`warp N never landed`, `the sneak never reached the server`, `not on the path's first spot (x blocks
  off)` - that one after 5 ticks on the ground). The arrival sync waits for every hop to be answered, and the
  "arrived" callback only runs if he ends within 1.5 blocks of the last landing. A replan waits up to 3 s for the
  ground and a room a path may start from (`canPath`), then stops with a message.
- The planner thread's result is handed back even on an `Error` (it caught `RuntimeException` only, and anything
  else left `pathPending` set for good); a search that does not answer in 10 s is dropped with a message.
- `[Path] running N warp(s) from ... to ...` and `[Path] off the plan: ...` are the new log lines.
- **On Hypixel:** the same. An etherwarp there is answered by one position packet to the landing (what QUOI's
  executor syncs on), so a correct path confirms hop by hop exactly as in the sim; a lagback or a refused warp now
  stops the chain within a tick or a second instead of firing the rest blind.

**Tic Tac Toe's "funny" thing was the chest trip.** With "aura the chest" on, the first placed move sends him off to
the room's secret chest (his own 2026-09-27 design: click, chest, back, carry on). 18:08:55: first click, a 2-warp
path that could only land NEAR the chest (`near: the block itself cannot be reached`), then about a hundred
`already there` searches and chat lines in four seconds: the leg re-asked the map for the chest every tick, the
planner answered "you are on the nearest landing already", and the leg only ended on "standing on it" or "in reach",
neither of which became true from there. The auto never clicked again (one `clicked` line in the log); he finished
the board by hand ("Draw"). Now each leg of the trip asks for ONE path and is over when that path is; the aura stage
works out whether the chest is reachable and moves on after three tries; every stage change is an INFO line; and the
executor says "Already there" once per goal per 2 s. Nothing in the log points at the map planning into or through
the board itself: the click into the room went to the room's recorded spot (`15 warp(s) (exact)`) and the auto
clicked from there. **On Hypixel:** identical code path.

**Teleport Maze: the map now hands over.** The auto only ever reacted to a maze teleport (QUOI: you step on the start
pad yourself), and the map's spot for this room is the doorway, relative (15,68,-2). `ClearExecutor.arrivalSeq`
moves when a path ends where it planned; within 3 s of one, standing within 3 blocks of the arrival and either at
the maze's doorway spot or inside the maze, before any maze teleport, Auto Teleport Maze walks onto the start pad
(15,69,12) and runs from its teleport as before. Walking in yourself does not trigger it. **On Hypixel:** the same -
the map lands on the doorway, and the walk is the forward key and the camera.

**Teleport Maze: round the walls.** The capture has a cobblestone wall (collision 1.5) in the middle of two sides of
every chamber, right between the two pads on that side, so a straight walk to the pad beside you hit it and timed
out ("no teleport after 3000ms of walking"). `autopuzzles/MazeWalk` is a grid Dijkstra at the pad's feet level (8
neighbours, no corner cutting, 0.6 step-up, nothing solid in the body's 1.8), thinned to straight legs, steered with
the camera and the forward key only; it re-plans if pushed 1.6 blocks off its line, falls back to the old straight
walk if it finds no way, and the timeout grows with the planned length. The mod's walking pathfinder
(`pathfinding.GraphPathfinder`) runs on recorded island graphs, of which a dungeon room has none, which is why it is
not used here. `tools/mazecheck/walkcheck.py` on the capture: 84 pad-to-pad walks inside chambers, 28 blocked going
straight (exactly the same-side pairs), 0 without a way round; doorway to start pad 13 blocks, straight is clear.
**On Hypixel:** the room is the same capture, so the same walls.

**Teleport Maze: one closed loop.** Reading of the structure: seven chambers of four corner pads, every pad a two-way
link to a pad in another chamber, the start linked to one, one exit to the centre (wiki, Dungeon Puzzle Rooms:
"seven rooms with four teleport pads each, all leading to different rooms"). The wiki's way through (Catacombs
Puzzle Rooms) is to take the pad diagonal to the one you arrived on until you land facing a pad in the same room,
and Auto Teleport Maze's fallback does the same. Pair each pad with its diagonal: the fourteen diagonals and the
links between them make paths and closed loops. The old draw paired pads at random, which split them into several
loops; a walk that entered one off the start-to-exit line (any non-diagonal pad, the auto's "best"/"farthest" picks,
or a human) could go round it for ever. `TeleportMazeLinks.draw` now lays all fourteen diagonals in one chain
(start, d1..d14, exit), no two neighbours in the same chamber, each entered by a random one of its two pads. The
centre pad returns him beside the start, so start-chain-centre-start is the one loop. `tools/mazecheck/run.sh`,
100,000 draws each:

| | closed loops (1 / 2 / 3 / 4+) | landings from which following diagonals never reaches the centre |
|-|-------------------------------|---------------------------------------------------------------|
| old random pairing | 39.4% / 42.1% / 15.7% / 2.7% | 26.6% |
| one chain | 100% / 0 / 0 / 0 | 0% |

Both: no structural faults (every link two-way and across chambers, one exit, start linked). From the start pad,
diagonals reach the centre in 15 pads now (11.3 on average before: the random pairing's start-to-exit line was
shorter, and the rest of the pads were in the separate loops). Only the sim is changed; on Hypixel the pairing is
the server's own, and nothing in the client assumes either.

**`tools/bench/regress.sh` was not running.** The path-to-blood merge changed `SimFloorLayout.Floor` (a `spine`
field) and made `LayoutSim` need `SimWitherDoors`; `floor.sh` stopped compiling, and `regress.sh` piped the bench
straight into `grep`, so it printed two javac errors and exited 0. Fixed both, and `regress.sh` now checks the
bench's own exit status and requires its `SELF-CHECK` line before reporting anything.

**And once it runs, it fails - on main, not on this change.** None of the files the bench compiles was touched here
(SimFloorLayout, RoomDoors, SimWitherDoors, EtherSearch, WarpGraph, the stubs). The same seed now draws different
floors, because the path-to-blood generator lays them differently ("0 puzzle(s) of the 3 asked for" on every one),
and on those floors many clicks are impossible for every planner even though the door graph is connected (the floor
line now prints how many rooms the carved doors leave unreachable: 0 on all three whole floors). Whole floors: old
67.3% found, new 83.6%, 8.25 warps against old 13.21, nothing the old planner found that the new one did not,
INVALID 0, SELF-CHECK 0 of 97 different; the thresholds (100% found, 7.55 warps, p99 12 ms) fail. Small floors: new
finds exactly what the every-landing reference finds (87.25% both), 3.14 warps against 3.03 (limit x1.045), 40 of
347 worse (limit 16%); only the absolute 89.7% found fails. The tree at d17520c (WarpGraph before the merge) passes
with the same script: 100% / 7.53 warps, small 89.75%. So the planner is unchanged and the floors are what moved -
most likely doorways the new generator joins that the bench's standard seam carve does not open the way SimDoors
does. Re-baselining the thresholds, or modelling those doorways, is his call and is not done here. (That guess was
wrong - the carve was faithful; see the next section.)

## The floor bench was not building the game's floors (2026-10-04, fix-bench)

**Root cause of the `regress.sh` failure: the bench, not the planner and not (mostly) the generator.** Two ways
`FloorBench` differed from `SimFloorGen.plan` + `SimBuilder`:
- It never loaded the room database, so `SimFloorGen.typeOf` said NORMAL and `shapeOf` null for every room. The
  layout therefore placed no puzzles ("0 puzzle(s) of the 3 asked for" on every floor), treated traps and puzzles as
  ordinary rooms, and placed the eleven L rooms a generated floor never gets (`SimFloorLayout.candidates` leaves them
  out: their 2x2 capture box has a void or a neighbour's quarter). Every failing click traced on seed 560 crossed an L room
  (Dino Site, Layers, Spider, Withermancer, Chambers); a top-down walk map of Dino Site showed the door to Quad Lava
  opening onto a quarter with no floor between y30 and y80.
- It carved EVERY `links()` adjacency instead of the `doorLinks` tree, and sealed only unlinked doorways. At d17520c
  that was 26/24/28 doors for 22/22/21 rooms - 3 to 8 loops a floor the game seals - and the loops routed round the
  broken L-room doors. The path-to-blood generator emits far fewer spare links (0 to 3), so the same broken doors
  became the only way through, which is when it started failing.

Now the bench loads `tools/bench/roomtypes.json` (name/type/shape cut from the game's `rooms-modern.json`; override with
`-Droomdata=`), caps champions as `plan` does (`LayoutSim.capChampions`), carves exactly `doorLinks` (including a
door inside one multi-tile room) and seals every other measured doorway, and builds its small-floor windows from the
door tree. `-Ddiag=true` checks every carved door for a measured doorway in both rooms and a walk both ways, and
counts the clicks a walk alone reaches; failed clicks are attributed per floor (start>goal room).

**Numbers, seed 560 (`regress.sh`).** Before: whole floors new 83.6% found / 8.25 warps, old 67.3%; small floors
87.25%. After: whole floors **new 100% / 8.26 warps**, old 92.4% / 15.84; small floors 100%, 3.43 warps against the
every-landing reference's 3.29 (x1.043, limit 1.045), 54 of 400 worse (limit 16%). The whole-floor warp limit was
7.55, set on the loopy, L-room, puzzle-less floors; on the game's floors the every-landing full-aim reference itself
needs 7.81 on 180 of the clicks (graph 8.19, 58 of 180 one or more worse), so it was re-baselined to 8.28 in
`floorbench-expect.txt` with the reason written there, and `small.minFoundPercent` raised from 89.7 to 100. Whole
floors are where the graph's thinning costs most: 32% of clicks one warp or more over the reference, against 13.5% on
3x2 windows.

**A real generator fault found on the way: the fill pass's carve through a wall.** Over 40 faithful floors the planner
failed 88 of 6,000 clicks; 54 of them were into or out of a room `fillGaps` had put in "through a carved wall" (Criss
Cross through the Entrance's two-block platform, Rail Track through Three Floors, Rare Pillars and Painting through Old
Trap, Spikes through the Entrance...): clicks into or out of 8 of the 27 rooms placed that way failed, and the door
audit over the same 40 floors found 5 of 27 such doors that cannot be walked. `RoomDoors.carvable(name)` now measures, per perimeter tile edge in the
capture's frame, whether a cut at the doorway floor (y69) lands on floor he can walk from 3 to 6 blocks in (stepping
at most one block, so a one-block ledge before Jumping Skulls' pit does not count). `carveOnce` uses only those
edges; the old unchecked carve is kept as the very last resort so no guarantee is lost, and says "(NOT a walkable cut
- nothing else fitted)" in the fill WARN. Also: traps no longer get second and third doors that way.

`tools/layoutsim -Dsweep=true` (his Map Logger library and recency, seed 1, 22,400 floors), before / after: any check
failed 45 / 33; path length, fairy, wither doors, cells, blood/trap/fairy, reachability and one-door puzzles 100% both;
room minimum missed 42 / 27; puzzles short 8 / 7; traps with 3-4 doors 98 / 0; consecutive F7s share 3.6 / 3.5 rooms.
Carves 7,785 all unchecked / 7,347 of which 124 unchecked (123 floors, 0.55%). FloorBench, 40 floors x 150 clicks:
98.53% found (88 failed) / 99.65% (21), and no failure is into a carved room now. A purely checked carve left 0.57% of
floors with an empty cell, which is why the fallback stays.

**What still fails (21 of 6,000, planner or capture, not doors):** 11 start or end in Lower/Higher Blaze (the shaft
and its far-off floor), 6 in Balcony (one of the four uncertain captures - its box is a tile off), 3 from Three Floors,
1 Waterfall>Fairy. Not investigated further here.

`tools/layoutsim/rotation.sh` had the same missing `SimWitherDoors` compile error as `floor.sh`; fixed.

## Ice Path, Blaze and Creeper Beams played to the end by their autos (2026-10-04, fix-puzA)

Played by the testkit's `93-solve-*` scenarios against both capture sets (Map Logger and Mod Only Test). Five faults
were the SIM's and three were the autos'; each is named at its fix.

**The sim (Hypixel never saw these):**
- **A swap and a use in one tick reached the server as a use of the OLD item.** Fabric's client `UseItemCallback` hook
  sits on `MultiPlayerGameMode.useItem` at the call to `ensureHasSentCarriedItem`, BEFORE it (fabric-events-interaction
  5.2.2, javap), and a SUCCESS cancels there - so for every item `SimAbilities` answers, vanilla's "send the selected
  slot first" never ran. Every Auto Puzzles reposition made with sneak already held (swap to the AOTV and warp in the
  same tick) arrived as a Terminator shot along the etherwarp's aim: client "holding ASPECT_OF_THE_VOID", server "use
  TERMINATOR slot 1". In Ice Path those arrows landed on the silverfish's next stop and shoved it; in Creeper Beams the
  warp timed out and the auto sat still. `SimAbilities.sendHeldSlotFirst` now calls `ensureHasSentCarriedItem` (the
  existing invoker) before answering SUCCESS, which is exactly what vanilla would have done next.
- **A blaze whose chunk unloads comes back as a plain blaze, and peaceful discards it.** `SimBlazeEntity` only exists
  while loaded; saved, it is "minecraft:blaze", and the sim's PEACEFUL world discards a reloaded one on its first tick.
  The label stands (peaceful leaves them alone) stayed up, so Auto Blaze shot at ten labels with nothing under them
  (server: ten alive at 20:02:41, none three seconds later; client: no `Blaze` entity at all). A single-room load
  puts him ~170 blocks off until the build hands over, and a floor puts the Blaze room anywhere, so this is not a test
  artefact. `SimBlazePuzzle.putBackMissing` puts a chain blaze back at its spot with its HP, and moves its label over,
  once it has been missing for 20 ticks from a section that `isPositionEntityTicking`.
- **"Dead" was inferred from absence.** `isDead` was `getEntity == null || !isAlive`, and null only means the section
  is not being shown - every blaze for the ~27 s a fresh sim world takes to bring its chunks up. So the whole chain
  read as ten in-order kills and `isComplete()` was true before the auto was switched on (base run, Lower Blaze). Now a
  blaze is dead only once SEEN dying (`isDeadOrDying`, kept in `SEEN_DEAD`), which also keeps a vanished blaze from
  counting as an out-of-order kill.
- **361 silverfish on one cell.** While the chunks came up, `respawnIfNear` found no fish and put one back every
  tick; each went into a section the server was not showing yet, and all of them appeared together once it did. It
  now puts one back only where `isPositionEntityTicking`, waits 100 ticks after a put-back, and the next tick that
  finds the fish removes any other sim silverfish on the board.
- **Test aids.** `SimPuzzles.isComplete(name)` is the one solved signal per puzzle: a key (`icepath`) or the room's
  live-map name (`Ice Path`; a blaze room name is only true when the armed arena is that room).
  `SimCreeperPuzzle.joinedCount()` is the joined-pairs count; `SimBlazePuzzle.killedInOrder()` the chain's progress.

**The autos (these run on Hypixel too):**
- **Ice Path: the only spot it could shoot from was one it could rarely reach.** It etherwarped onto the silverfish's
  cell or did nothing, and from the board the eye is 1.27 over the ice, so the ray to a cell a few blocks off runs
  along the maze's own pillars ("no etherwarp aim onto -114,-59,-115 from where you stand", from the room's spawn six
  cells away). Now: a direct warp, else a warp to an open board cell he can see from which the fish's cell can be
  seen (a neighbour of the fish's cell always qualifies when visible), else a walk through the Interactive Map's
  pathing. **Solver:** a board change seen on a tick the silverfish was not found or was sliding was thrown away,
  so a board that read all air before the chunks arrived was never solved again; the change is now kept
  (`boardDirty`) until a solve uses it.
- **Blaze, "loop onto the same spot ~4x/s" and "left holding the AOTV":** a QUOI spot was judged from
  `spot.getY() + 1.62` - the spot is the block he stands ON, so that eye was inside the block, not the sneaking eye
  1.27 over its top he actually shoots from; his own spot was a candidate; and with nothing passing it warped to the
  first visible spot with the AOTV swapped in. Now the real eye, his own spot skipped, and when no listed spot has a
  shot it searches the room's standable blocks (24 around and 14 below to 24 above the blaze, nearest first, 12 a
  tick, never below Higher Blaze's top level) - the fifth blaze of Lower Blaze floats a block over the shaft floor,
  under QUOI's lowest spot. A searched spot with a shot that no single warp reaches (the shaft floor seen from the
  top landing, where the first target sat a block over the floor in two Mod Only Test runs and he never moved) is
  reached by two warps through any searched block that sees it, and only then, once per spot, handed to the
  Interactive Map's planner - which could not path to the shaft floor by the bar and failed 1,100 times in a minute
  when it was asked every tick. "Blaze: done." now waits for the solver's list to stay empty for 30 ticks (a rebuilt chain
  empties it for a moment). Side arrows are followed until they land in the safety check (QUOI stopped them a few
  blocks past the target, so one could fly on across the shaft and kill a far blaze out of order). One out-of-order kill
  was seen once the blazes existed, and it was the auto's wait, not the spread: after a shot it waited
  `distance / 2.5` ticks for the arrow, which for a steep shot up the shaft (28.8 blocks at pitch -84) is far shorter
  than the climb, so a second shot left before the first landed; the first killed the blaze and the second flew on
  through the empty space into the next blaze up. The wait is now the arrow's own simulated flight to its closest
  approach plus four ticks. The spread stays 5 degrees, QUOI's figure. Four shots from
  one spot that kill nothing give that spot up for that blaze: on the Mod Only Test captures it fired 196 shots at one
  blaze from one spot, the simulated arrow clearing the ledge he stood on and the real ones dying in it. (Requiring
  the shot to be clean from the vanilla arrow origin, eye - 0.1, as well as QUOI's was tried and refused every spot
  in Lower Blaze, so the give-up is the fix.)
- **Blaze, still an out-of-order kill about one run in twenty (2026-10-05, night-blaze):** the safety check traced each
  arrow until it touched a box and counted a touch of the TARGET as safe. One arrow kills a blaze and an arrow passes
  through a dying one, so whichever of the three killed the target first, the others flew on. Measured with the sim's
  new per-kill attribution ("blaze N of 10 died (due: k) ... killed by shot #S centre / yaw-5 side / yaw+5 side
  arrow"): 31 of 160 and 52 of 206 kills were made by a side arrow, and the one fail in 36 baseline runs was shot #9's
  centre arrow killing blaze 5 at tick 4 and its +5 arrow - "safe" because it reached blaze 5's box - killing blaze 10
  at tick 6. Now every arrow (centre and both sides) is traced to where it lands with the target treated as absent, and
  must miss every other blaze; the centre one must still reach the target first. This is the auto, so it holds on
  Hypixel too.
- **Sim: Terminator arrows were saved with the world.** One still in the air when the world closed came back on the
  next load as a plain arrow with its 10,000 damage and kept flying; in back-to-back Lower Blaze runs two saved arrows
  rising out of the shaft fell back down it and killed a chain blaze within two seconds of the build, before Auto Blaze
  was on (2 of 15 runs). `TerminatorArrow.shouldBeSaved()` is false now, and a plain DISALLOWED-pickup arrow (one an
  older jar saved) is refused and discarded by the damage hook.
- **Creeper Beams, "times out, then never shoots again":** the timeout was the slot fault above. After it, the shots
  were aimed with `etherwarpDirection` - a SNEAKING eye whatever his stance, at QUOI's face-edge sample points - so
  standing up after the cancelled reposition every shot missed its lantern. Shots now aim from the real eye at the
  lantern's centre or a face centre inset 0.05, each checked with a collider ray to land on the lantern. "On the
  platform" is within half a block, not `==` (an etherwarp lands 0.05 high and settles a tick later, and the exact
  test started a second warp). A platform spot it cannot warp onto is skipped next time; the lantern under the
  creeper's own feet is not checked for the creeper being in the way; with no clear spot it shoots anyway and says so.
- Every refusal in the three autos is an INFO line, once per change of reason (`[AutoIcePath] ...`,
  `[AutoPuzzles] Blaze: ...`, `[AutoPuzzles] Beams: ...`), and each reposition logs the warp it sent and the item held.

## 93-solve round B: Tic Tac Toe, Boulder, Three Weirdos, Water Board, Teleport Maze (2026-10-04, fix-puzB)

Played by the testkit's `93-solve-*` scenarios on both capture sets (Mod Only Test and Map Logger), each auto alone,
nobody at the keys except where the scenario says so.

- **Tic Tac Toe: the last move was out of reach and nothing walked.** The Interactive Map's spot for the room
  (relative 11,68,16) is where a walk into the room ends, not where the whole board is in reach; the last move sat
  ~5.5 blocks off and the auto only logged "out of reach". It now walks into reach: `MazeWalk.planToSpot` runs one
  Dijkstra over his floor and takes the nearest spot BY WALKING whose standing eye is within 4.0 of the button's box,
  and the walk is the camera plus the forward key. Same on Hypixel.
- **Boulder would not arm on Mod Only Test's capture** ("best 1 of 7"). The bind fingerprinted the seven buttons of
  one row of the capture's OWN arrangement - but every capture holds whatever arrangement Hypixel dealt that run, and
  Mod Only Test's is also turned a quarter. Decoded, the two captures agree only on the fixed room: the diorite
  checkerboard under every other grid cell (21 cell centres at relative y 63) and the far staircase (stairs at
  (13..17, 64, 27), barrier over them at y 68). That is the fingerprint now (31 of 31 land on both). The reward chest
  moved from capture-local (30,66,16) to relative (15,66,29) through the bind's anchor, which is the same block in
  every capture. **Bind a puzzle to what the room always has, never to the puzzle's own state.**
- **Boulder: the chest is behind the boxes, so the auto has to push.** The old "stand 3 up and 3 back from the chest
  and aura it" spot is the air over the barrier roof, and etherwarp is refused in Boulder, so the map walk failed.
  Auto Boulder now: finds the chest (scan), gets off the roof if he is on it (a hole in the roof found in the world
  whose fall lands on the floor and whose landing walks to the grid's front, relative (15,64,7)), presses each of
  Boulder Solver's buttons from a walked-to spot in reach (only once that button exists - it is laid after the box
  before it moves), walks into reach of the chest across the opened floor and auras it, then asks the map for the
  doorway once. Every stage change and refusal is an INFO line. The sim presses the box on the SERVER copy of
  `UseBlockCallback` now (raw packets push too) and records the reward chest being opened
  (`SimBoulderPuzzle.isRewardChestOpened`). A single-room Boulder spawns one block in from its doorway, like the maze:
  the tile-centre scan put him on the floor inside whatever ring of boxes the arrangement had, walled in.
- **Three Weirdos never spoke to the auto.** The weirdo spoke from the CLIENT copy of `UseEntityCallback`, which Fabric
  fires only from `Minecraft.startUseItem` - a real click - so `gameMode.interact` (the same packet) did nothing. The
  SERVER copy (inside `ServerGamePacketListenerImpl.handleInteract`, javap of fabric-events-interaction 5.2.8) now
  speaks, with `ServerPlayer.sendSystemMessage`; left clicks pass on the client and are answered (and cancelled) on
  the server. Auto Three Weirdos logs each NPC it talks to and every reason it waits. It still never walks to the
  NPCs; the scenario places him.
- **Water Board: QUOI's spots are out of reach.** Standing on (15,58,z), every side lever (x 10 or 20) is 4.52-4.54
  eye-to-box, past 4.5. The auto now warps only when the lever is not already in reach, onto the standable lever-floor
  block nearest QUOI's spot whose standing eye is within 4.3 of the lever and which an etherwarp can aim at (read off
  the world, so the same on Hypixel). Every refusal is logged (`Water: waiting - ...`).
- **Teleport Maze starts from the entrance.** Besides a map arrival, the start-pad walk now runs when he is in the maze,
  before any teleport, on the ground, with none of his movement keys down for 10 ticks, and the start pad is at most 24
  blocks away on foot - once per visit. And when every pad in his chamber is visited (the solver's "best" pad had sent
  him sideways twice), it takes the diagonal again instead of stopping; 60 teleports without the end stop it.
- **Test aid:** `SimPuzzles.isRoomComplete(roomName)` - one solved signal per ROOM (Three Weirdos and Quiz share
  `SimQuizPuzzle`, so `isWeirdosComplete`/`isQuizComplete` tell them apart). Boulder's is the path opening.

**Record** (testkit at master, merged main, five scenarios per launch): 3 of 3 launches all five PASS on Mod Only Test
and 3 of 3 on Map Logger, on the final jar, after the same on the jar before the merge. The scenario still teleports
him to the weirdos (Approach NEAR_WEIRDOS); Teleport Maze and Boulder approached by themselves.

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

## The map's etherwarp: block changes, cheaper aims, a denser graph (2026-10-05, night-path)

killer560 again: "only a few ms every time and prioritize using as few warps as physically possible."

**A block change dropped a third of the floor graph.** His 2026-10-04 Map Logger log has three clicks (18:07:53,
18:07:57, 18:08:14) that fell back to room by room with 39-40 warps where the warm graph gave 17-22: a block change
had made warm-up run again, and a click in that window got 40 ms without the exact heuristic. One door change
dropped 2,000-5,900 of the floor's ~15,000 nodes, because a node was thrown away if any ray of it read any of the
changed SECTION's 4,096 blocks, or if a landing appeared or went in any column it took candidates from. Now
`LevelEtherGrid` reports the box of the blocks whose flags changed and `WarpGraph.blocksChanged` re-checks only the
aims whose cone from the eye into the target passes near it, aims at landings that appeared and drops the ones that
went, on the warm-up workers. `FloorBench -Dchangecheck` expands every node from scratch after each change and
compares: 0 mismatches. (Found on the way: a bucket's landings are listed under the column its FIRST block is in,
which the old column check missed at chunk edges.) A click on a floor that was warm finishes the re-warm first
(fields are built in parallel, and there is no re-seed after a change that only moved edges), never gets the 40 ms
cold budget, and skips room by room when the warm graph proves there is no way (a closed door) - `[Path] not a
proof of no way because: ...` when it cannot prove it. `FloorBench -Dchanges=8` (clicks right after a door seals or
opens): fell back 98 of 192 -> 0, warps 14.17 -> 8.44 (= a fresh graph), median 43 -> 1.3 ms.

**Aims.** Failing 18-point aims were ~70% of warm-up's rays. `EtherSearch.aimPast` skips an aim point whose line
passes through the inside of a block that already stopped a line to the same target: same answers (edge counts
identical), 44-71% fewer rays.

**Density.** Bucket 3 -> 2 and partial aims from 0.3 of the way instead of 0.6: whole floors 8.26 -> 8.04 warps; on
the clicks the every-landing full-aim reference runs on, 8.19 -> 8.01 against its 7.81. Bucket 1 reaches the
reference (7.89 vs 7.88) but its clicks take 11-14 ms at p95 and it holds 12M edges, so it is not used.

**Clicks.** Exact goals stop their backward search after 3,000 nodes and bound the rest through per-cell fields
(fewest warps into each 32 x 32 cell's column, built with the tile fields); ties among equal bounds go deep first.
Whole floors, warm: median 0.65 ms, p95 3.6, p99 7.2.

**In game** (testkit scenario `95-sim-map-warp`, three runs on generated F7s): warm-up 4.6-4.9 s of planner time for
21-22k nodes; 17 presses across the floor planned in 0.2-5.3 ms, 1-17 warps, every one moved him 23-160 blocks into
the clicked room with the arrival confirmed; a press right after stone was placed beside him planned in 0.7-0.8 ms on
the graph. **On Hypixel:** the same code; a door opening there is a block change like any other.

Not fixed: before the floor's FIRST warm-up completes (about 5 s after the floor loads) a click still gets 40 ms on
the partial graph and then room by room.

## Ice Fill is judged on the server, every landing (2026-10-05, night-icefill)

One Auto Ice Fill run in 93-solve (rp-2612.log, 07:27:56) broke section 2 for "teleported off the ice" in the
middle of a run of one-block hops, the auto then repositioned onto section 3 (the first tile still ICE while 2
was air) and finished a fill the sim never counted. 23 plain repeats did not reproduce it. The cause:
`SimIceFillPuzzle` judged on the CLIENT tick from `client.player`. Auto Ice Fill hops on a two-tick timer from
the point it predicts, so when two position packets reach the client between two of its ticks (a loaded
machine), the client goes from tile 9 to tile 11 without ever standing on 10, and the judge compared 11 with 9.
The client gametest runs client and server in lockstep, so a server-thread sleep changes nothing; holding the
client connection's netty loop does (testkit `-PnetStallMs=120`): 0 of 6 passed with the old judge, the trace
showing exactly 9 -> 11.

The judge now runs where Hypixel's does: on the server, once per server tick for walking and jumping, and from
`SimAbilities.teleport` for every landing (`onTeleport`), so two hops handled in one server tick are still two
tiles. A jump now also needs the feet above standing height (`JUMP_HEIGHT` 0.2), because a teleport's landing
reads not-on-ground for a tick or two. The auto is unchanged: hopping before the last landing reaches the client
is normal on Hypixel, where every hop is sent inside the ping. Testkit `-PicefillControl=true` proves the judge
still breaks a section on a two-tile warp and on a repeated tile before letting the auto play.

~~Not changed: after a break, Auto Ice Fill's etherwarp reposition picks the first still-ICE tile, which while a
section is broken is the next section's, so it skips the broken one.~~ Fixed the same day - see "Ice Fill: a broken
section is waited out and re-entered".
Not fixed here: before the floor's FIRST warm-up completes (about 5 s after the floor loads) a click still got 40 ms
on the partial graph and then room by room - see the next section.

## Ice Fill: a broken section is waited out and re-entered (2026-10-05, icefill-recover)

killer560 on Hypixel: a broken section comes back "after two-ish seconds", ONLY that section, and the auto should
sense it is on the floor below, "pause everything that it is doing until it regenerates then ... teleport back onto
the ice fill starting position and continue." The sim already did the room's half: `breakSection` airs only the live
section, `regenerate` lays only that one back as fresh ice after `REGEN_TICKS` (40), finished sections stay packed,
`activeSection` does not move, and the server-side per-landing judge is unchanged. Two additions, both read-only for
play: each section's ENTRY tile (`entryTile(s)`, the first waypoint of that bundled floor, one under the feet) and
counters a scenario reads instead of trusting the auto's log - `breaks()`, `regenerations()`, `lastBrokenSection()`,
`isBroken()`, `activeSection()`, `landings()` (every `onTeleport`, broken or not) and `lastLandingTile()`. Breaks and
regenerations also log one INFO line each.

`AutoIceFill` splits its path into sections by height (stair midpoints excluded). A section with half or more of its
path tiles gone to air, or feet 1.5 under the tile last stood on, starts a recovery: reposition / map walk cancelled,
sneak released, no hops, until every tile of that section reads `ICE` (15 s cap, then a chat line and it stops until
he leaves the room); then `AutoReposition` onto that section's first tile (Interactive Map walk if there is no warp
line; 10 s cap), and the hops resume from it. Leaving the room resets it; five breaks in one room stops it. The old
"off the band" reposition onto the first ICE tile still exists for getting ONTO the fill, but runs after the break
check, so it can no longer pick the next section's tile while one is air.

Testkit `-PicefillControl=true` now also forces a mistake once the auto is half way across sections 1 and 2
(`-PicefillBreaks`): he is put back on the tile he just left, judged as a landing (`onTeleport`) - a plain server
teleport was not, because the auto's next hop in the same server tick moved him on before the once-a-tick judge
looked, and section 1 never broke on the first try. The verdict reads the sim: that section broke, no landing while
broken after a 4-tick grace, it regenerated, the first landing after was its entry tile, and isComplete. 26.1.2:
10/10 forced-break and 10/10 plain; 26.2: 3/3 forced-break. A jar with the break check switched off fails it (13
landings while section 1 was broken, first landing on section 2's tile, never solved). The fall test ("feet under the
section") never fired in these runs - the air test always saw the break first - so it is unexercised.

## A click during the first warm-up: the quick floor graph (2026-10-05, night-warm)

The gap night-path left: a click before the floor graph's first warm-up completes (about 5 s of planner time on six
threads) got 40 ms on the half-built graph and then room by room. `FloorBench -Dearly=0.5,1,2,3` (clicks from the
entrance at t seconds into a fresh floor's warm-up; the bench's flat grid warms a floor in the game's 4.6-4.9 s on
six threads, so t means the same thing): 13-14 warps where the warm graph takes 9, 20/18/14/8 of 30 clicks falling
back, up to 711 ms.

**Searching the half-built graph harder cannot fix it.** Until a node's edges are worked out it costs about 1.5 ms of
rays a thread. Counted on the warm graph, a cold click's A* closes on average 6,170 nodes (a tile click's bound is
just "one more warp", so it is a breadth-first search of the floor); weighting the bound changes little; a heuristic
that follows the doors' centres still needs 2,600 nodes for +0.04 warps or 940 for +0.42 - 0.2-0.6 s on every core.

**`FloorGraphs`** pairs the full graph with a QUICK graph of the same floor: bucket 5, every landing kept only on
each doorway's own centre line (7 columns per door, not the 7x3 box and the tile centre lines), no partial-occlusion
aims. A tenth of the rays: warm in ~0.3-0.5 s, 6.4-7.8k nodes, 0.29-0.51M edges. It is warmed first, follows the
full graph for block changes (`WarpGraph.follower`), and until the full graph is warm a click is planned on it -
complete and exact on its own landings, every hop still a verified aim, so the bench's hop-by-hop replay holds (0
invalid). A click arriving before it is warm finishes it (at most 600 ms). Chosen by sweep: bucket 4 is ~0.2 warps
better but makes a click at t=0 wait ~150 ms longer; door boxes instead of lines buy nothing; a 25 ms look on the
half-warm full graph for a shorter path bought 0.0-0.3 warps for 25 ms on every click and was dropped.

"Has been warm" is not "knows the floor": the sim's floor warms 288 nodes in the sealed entrance before GO, and his
Map Logger log has a 279-node warm before the real 13,454. A full graph that is re-warming counts as knowing the
floor only if its last finished warm had at least 1.3x the quick graph's nodes (the whole floor is ~2.6x); then a
click finishes the re-warm as before. Found on the way: a click read `warmDone()` before the queued changes were
applied, so the click right after a door opened (the sealed entrance in `-Dearlygate`) searched stale fields and
went room by room; the click now applies them first.

**Measured** (3 floors x 10 clicks per t, the same clicks before and after; warm graph in brackets):

| t | before: warps, fell back, median / max ms | after: warps, fell back, median / max ms |
|-|-|-|
| 0.5 s | 14.07, 20 of 30, 47 / 711 | 9.47 (8.63), 0, 0.6 / 232 |
| 1 s | 13.90, 18, 44 / 711 | 9.73 (8.93), 0, 0.6 / 2.1 |
| 2 s | 14.13, 14, 21 / 60 | 10.33 (9.13), 0, 0.5 / 1.2 |
| 3 s | 13.17, 8, 10 / 65 | 11.13 (9.90), 0, 0.5 / 1.7 |

Entrance gate opening (`-Dearlygate`, t = 0.2/1/3 s): before 12.8/12.5/12.0 warps, 15/11/7 of 30 fell back; after
8.9/9.2/9.7 (warm 8.6/8.9/9.1), none. Warm clicks unchanged (8.04 warps, median 0.6 ms). The quick graph holds 10-13.5
MB beside the full graph's 51-70 MB (`-Dmemcheck`), kept for the floor. `regress.sh` runs both early modes against
`early.*` in `floorbench-expect.txt`. **In game** (`95-sim-map-warp`, a press three ticks after GO to the farthest
room, then the same trip once warm): 11 warps in 475 ms vs 8 warm (26.1.2), 15 in 246 ms vs 13 (26.2); the old jar
on the same scenario fell back to room by room, 19 warps vs 10 warm. The few hundred ms is the quick graph being
finished inside that click. **On Hypixel:** the same code; nothing here reads the sim.

## Exact floors, and Generate keeps his room (2026-10-05, night-flakes)

**73 "only 20 room(s), wanted 21"** (2-3 runs in 8, 120 floors a run). `tools/layoutsim -Dsweep=true`, seed 1, 11,200
floors: 28 under the room minimum, every one an F5/F7 whose 36 cells were all covered by 20 rooms; also 10 a puzzle
short and 1 with no trap. **Cause:** `fillGaps`' strict step put multi-tile rooms into the last free cells while the
floor still owed rooms - the growth caps a room at `cellsLeft - (owed - 1)`, the fill did not - and nothing adds a room
to a full grid. And the finishing passes (trap, fill, puzzles) ran once on the attempt loop's pick, with no way back.
**Fix:** `Filler.maxArea` is the same cap (alone it took the room shortfall to 0 of 22,400); `run` lays out up to 12
whole floors (4 with pins) until `shortfalls` is empty - room minimum, cells, puzzles, trap, blood, and the
Entrance-to-Blood path through the build's doors exactly the slider with the fairy on it - and only the kept floor
goes into recency. A retry fires about once in 1,000-4,000 floors, always resolved by the second floor. After: 0
failing of 190,400 sweep floors with the cap and retry alone, and 0 of 67,200 (8,400 per floor size, recency on and
off) with the pin changes below as well; 2.4-3.5 ms a floor.

**83 "a hand-placed room was replaced by Generate" was a real bug.** With Quiz pinned in the middle of an F7 (the
designer's defaults), 19 Generates in 200 dropped it - yet on those calls a quarter of the attempts HAD kept it. The
attempt score charged a dropped pin five cells, so a full floor without his room beat one that kept it a few cells
short, and `run` then laid the floor out again WITHOUT the pin, reporting "nothing could open a doorway into it". A
dropped pin now ranks above the cell count (still below blood, trap and the room minimum, which is what stops the
old "two-room floor that holds his rooms" problem); the fill pass tops up the cells. Two things came with it: a
pinned PUZZLE now counts as one of the slider's puzzles in the growth too (it did in `ensurePuzzles` and the fill;
the growth added the slider's count on top, 778 of 1,120 floors one over), and a pinned Fairy or Blood that no path
of his length can reach (the loop fell back to the approximate growth) is laid out again without, as it always was
in effect - otherwise the heavier pin weight kept it with the path wrong. Quiz@14: 0 dropped in 1,000, 61 -> 19 ms a
floor. Random pins, 1,120 floors each, before -> after: normal 98 -> 53 dropped (all 53 his footprint running off
the grid, refused before layout), puzzle 100 -> 1, fairy failing checks 16 -> 0 (but 235 -> 780 ms a floor: an
unreachable fairy is retried four times), blood 0 -> 0. `tools/layoutsim -Dpinroom=Quiz -Dpincell=14 -Donly=F7
-Dslider=5 -Dpuzzles=3` reproduces the scenario.

**88 "the world CHANGED"** was the test server's own random tick: the one failure's block counts differ by exactly one
grass_block turned dirt. The testkit compares the arena position by position and ignores a grass/dirt swap.

## Water Board: whole colour layers, and the reward chest (2026-10-06, sim-waterboard)

killer560: "make it so the bottom path isn't just the one bottom middle block up for the ones it needs to fix it should
be that entire layer is out", and "Waterboard needs a chest that spawns in once I complete the puzzle, it should be in
between those carpets down low but closer to the exit between them not touching the wall."

- **The bottom path is a walkway under the glass.** Decoded (shipped/Ashfall, Map Logger and Mod Only Test captures
  agree): stairs at relative z 9..11 lead from the lever floor down to y 56, a walkway x 14..16 runs to an end wall at
  z 24, and each colour owns one layer of it at its z (red 15 .. purple 19). A layer has FIVE sticky pistons - up under
  the middle at (15, 54, z), and inward at (12, 56|57, z) and (18, 56|57, z) with the colour's wool at (13|17, 56|57, z).
  Every capture is a retracted frame. The sim used to move only the middle wool; `SimWaterPuzzle.LayerPiston` now reads
  all five per colour at arm time (facing off the block, so no rotation guessed) and moves them together, with a power
  cell behind each (redstone while out, the board's own rule). The solver still reads only (15, 56, z).
- **The reward chest is relative (15, 56, 22), facing back up the walkway.** Gray carpet sits at (13|14|16|17, 56, 22)
  and (14|16, 56, 23): between them is x 15, and z 23 touches the end wall while z 22 does not and is nearer the stairs.
  It is the block straight under QUOI's/`AutoWater`'s chest spot (15, 58, 22), so after the auto's last warp Secret
  Aura opens it through the glass (server reach has no line-of-sight check). Placed on solve (a server task queued
  after the last layer's), removed by a puzzle reset, dropped by `forget`. Opened is recorded on the server
  (`isRewardChestOpened`).
- **Puzzle reward chests are not secrets.** `SimMimic`'s hook counted every opened chest; the room database lists 0
  secrets for Water Board and Boulder, so both reward chests are now `SimMimic.markPuzzleReward`ed and skipped.
- Testkit 93-solve-waterboard asserts all of it through `PuzzleCoords` (the solver's path, not the sim's anchor): 3
  whole layers out at the start, chest absent; after the solve the chest at (15,56,22) and equal to the sim's, the path
  clear, Secret Aura opening it, secrets unchanged; a reset removing it and refilling the layers. Passed on Mod Only
  Test and Map Logger captures; the old jar fails it at the start ("0,0,1,1,1").

## The Map Designer's room filters (2026-10-06, designer-filters)

killer560: "in the generate a map thing have a filter section where i can filter based on things like puzzles, room size,
secrets in a room, etc." `SimRoomFilters` holds the rules (saved in `killer560smod-sim-designer-filters.json`),
`SimRoomFilterScreen` edits them, and the designer's list, Fill (which draws from the list as shown) and Generate all read
them. Generate passes `SimRoomFilters::generatorAllows` to `SimFloorGen.plan(..., allow)`, which never filters out Entrance,
Blood, Fairy, a trap or a pinned room, and lays the floor out again from every room when the filtered one has any
`SimFloorLayout` shortfall (now carried out as `PinnedFloor.missed` / `Planned.missed`), restoring the recency memory first.

- **With only 1x1 rooms every F7 came out with no trap.** The growth stalls early on a 1x1-only pool, `ensureTrap` found no
  1x1 it could swap (every one on it has more doorways than a trap room), and `fillGaps` then covered all 36 cells, so the
  "trap into an empty cell" last resort after it had nowhere to go. `runOnce` now tries the empty cell BEFORE the fill too.
  `tools/layoutsim -Dsweep=true -Dkeepshapes=1x1` (new: the size filter as Generate applies it), seed 1, 20 a combination:
  1,119 of 4,480 floors with no trap before (all 560 F7s, 559 F5s), 0 failing after; unfiltered 0 of 4,480 after.
- **`ModChat.send` says nothing from the main menu**: it drops the line when `client.player` is null, and the designer opens
  from the main menu. So none of `plan()`'s chat explanations ever reached him there; the designer's status line is the
  only thing he sees, which is why the filter note leads it ("filters too strict - used every room · ...").
