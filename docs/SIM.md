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
