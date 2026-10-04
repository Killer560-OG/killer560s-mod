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

**What is wrong is `resolveFootprint`'s clamp.** `captureBox` anchors the box at the lowest grid cell the
live map has grouped into the room. When that grouping is not the room - an Ashfall practice floor lays
rooms out in a LINE and the map merges a whole run into one, or he has only walked part of a room - the box
starts in the wrong place, and the clamp then read the DATABASE's number of tiles from that wrong start. It
fixed the size and left the position alone, which is how Waterfall's capture came to be a tile of Catwalk,
then Waterfall, then nothing, then the whole of Rare Overgrown. A mismatched footprint now REFUSES the
capture instead of clamping it.

**Measured, so it can be re-measured:** 34 tiles across the 134 captures are block-for-block a tile of a
different room, and 12 are nothing but air. Compare tiles over y 66..99, the dungeon's own floor-to-roof
band - comparing each room over its own captured band finds only 5 of the 34, because the duplicates differ
in how far below the floor they were recorded, not in the room itself. When two rooms share a tile the one
with MORE tiles is the corrupt one: a 1x1 box cannot span a run. `RoomTileAudit` does this at every load and
makes the offenders unusable, so they are asked for again rather than placed.

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
Untested in game - no shortbow has been fired at a sim silverfish yet.

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
floor within seconds. Instead the sealed column itself is removed when the flow is off and put back when it is
on, with `UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS` so nothing schedules a fluid tick. Binding turns it off,
so the first click of every attempt is the back lever - which is what every bundled solution says anyway.

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

## The sim has no tab list, and two HUDs were reading one

`DungeonInfoFeature` and `ScoreCalculatorFeature` both take their secrets, crypts, rooms and deaths from
Hypixel's TAB LIST display names. An integrated server lists one player and none of those lines exist, so both
found nothing for a whole sim run - his log says so once per run, `No tab-list 'Secrets Found' line matched`.
Both now read `SimScore` while `SimState.isActive()`, which is the same counter the sim's own sidebar uses, so
the HUD and the score screen cannot disagree.

That exposed a real bug underneath: the sim's secret TOTAL was `SimMimic.candidateCount()`, the number of chests
that could have been the mimic. It is a different quantity, and it ignores bats, essences, items and levers.
`SimBuilder` now sums each placed room's `RoomEntry.secrets` - the same number the map prints beside a room's
name.

## The map is the shape of the floor now, not a fixed square

`MAP_UNITS` (116 = 6 rooms of 16 units + 5 gaps of 4) is F7's size, and `autoFit` already blew smaller floors up
to fill it - but it CENTRED them inside a square panel, which left a dead band down one pair of edges on every
floor that is not square. `MapPainter.panelUnits` now gives the floor's own shape at that same zoom and the HUD
element measures AND draws itself from it, so a fully-walked F7 is the 116x116 it always was and a shorter floor
gets a shorter map. The long axis is still always 116, so the panel never grows past what it used to be and no
saved HUD position can be stranded off screen (which is the failure mode CLAUDE.md records for the Storage
Overlay). Teammate-reported cells are counted in the box as well as locally revealed ones, because they are
drawn and the panel is now cut to that box.

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
- **Rescanning a room**: `/killer560 roomrecorder rescan <room>` empties the capture so the recorder reads it
  again; without it a complete room is never re-read, because capture skips seen columns. Balcony and Archway are
  the only two rooms whose captures have no roof marker, which is the code's own sign of a missing roof corner.
  The handoff said `SimBuilder` warns when a 1x2's reserved cells disagree with its long axis; no such warning
  exists in the code.


## The 2026-10-04 list

- **A sim listener that returns SUCCESS hides the click from every listener registered after it.** Fabric's
  `UseBlockCallback` stops at the first non-PASS result. `SimSecrets`' essence handler is registered before
  `SecretWaypointsFeature`, consumed the skull click, and so the essence's waypoint never cleared. It now calls
  `SecretWaypointsFeature.markSimEssenceCollected` itself (nearest WITHER waypoint within 3 blocks, because a
  buried essence is placed up to two blocks above its database spot).
- **Secret Aura gated on `getCurrentServer()` being Hypixel/p3sim**, which is null in singleplayer, so it never
  acted in the sim. It also accepts `SimState.canAct` now, and treats `SimSecrets.PLACED_WITHER` as essences there
  (the sim's essence is a plain wither skeleton skull with no Hypixel skin, so `isWitherEssence` cannot match it).
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
  `config/killer560smod-sim-recent.json` by `SimRecencyStore` (kept out of `SimFloorLayout` so the generator stays
  runnable outside the game), decay 0.6, key weight 3.0, score weight 2.0, die 0..4. Not measured over many floors.
- The sim's sidebar, the Custom Scoreboard (which shows the sim sidebar under a plain title instead of its Skyblock
  entries) and the vanilla tab list header/footer carry "killer560's personal testing sim" and
  `discord.gg/hkQMF5fE84`. The sidebar objective's title stays `SKYBLOCK`: `SkyblockGate` reads it, and every
  "Skyblock Only" feature in the sim depends on that.
- The pause screen's Change Room button is a vanilla `Button` placed 4 px under the lowest button in the centre
  column, read from the screen's widgets, instead of pinned to `height - 46`.
