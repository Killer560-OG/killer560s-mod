# Sim secrets: crypts, princes, mimic, levers, keys, bats, chests

How the sim (`roomsim/`) places, shows and scores secrets, crypts and princes, and what the captures do and do not hold.
Part of the sim docs: [SIM.md](SIM.md).

## Scoring and clears

- The sim scores a crypt and the mimic on the mob's DEATH (`SimMobs.spawnCrypt`, `SimMimic.tickServer`), not when the
  wall breaks or the chest opens, or nothing client-side could see the kill. The mimic chest is a trapped chest, and a
  secret bat carries Hypixel's 100 max health (the client's only way to tell it from a sceptre bat).
- **Room clears, star tags and blast radii.** `SimScore.roomCleared()` once had no caller and `SimRoomState` only knew
  FAILED. `SimMobs` records each starred mob's spawn spot (`STARRED_AT`) and a CONFIRMED death (`STARRED_DEAD`: the entity
  is there and dead, or its section is entity-ticking and it is gone - missing is never killed); a room whose recorded
  starred mobs are all dead is `SimRoomState.markCleared` (the map's white check) and counted. `LiveMapFeature.isRoomCleared`
  reads it in the sim. Generated floors spawn no starred mobs of their own; tests place them (`SimMobs.spawnStarred`), and
  `SimRoomState.clearRoom(name)` un-clears a room. A star tag is discarded on the confirmed death (Hypixel's goes with
  its mob). Wither Impact and the Guided Bat blast radius are both 6 (hypixelskyblock.minecraft.wiki; they were 5 and 3).
  The wiki gives no bat range; the sim's ~29.6 blocks is unmeasured on Hypixel. Testkit 131-sim-auto-clear (only when named).
- **A room's last secret, got as he leaves, never reached the map** (killer560: "the map does not register secrets I get
  if I leave the room too fast"). The server always counted it (`SimScore.secretFound(pos)` keys by position, for chests,
  essences, items, bats and the Redstone Key node), but `SimActionBar` sends "x/y Secrets" only for the room he stands in,
  every ten server ticks, and the sim had no stand-in for Hypixel's map green checkmark (`MapPainter.secretsText` reads it
  as every secret found). `SimRoomState.stateFor` now answers GREEN for a cleared room whose `SimScore.foundInRoom` reaches
  its database total (Hypixel's rule: cleared + all secrets), wherever he is; a room left with secrets missing keeps the
  action bar's last count, as on Hypixel. Testkit 1790-sim-map-leave-secrets (every type, door and etherwarp, same tick
  and one tick later): 20/20 on 26.1.2 and 26.2; main fcba4045 failed 18 of 20.
- **"The secrets are highlighted with my setting off" was not Secret Waypoints.** `SecretWaypointsFeature` gates on
  `SecretWaypointsConfig.isEnabled()` (ships false). The highlighter was `SimMimicRenderer`, a sim-only
  `AFTER_TRANSLUCENT_TERRAIN` callback gated only on `SimState.canAct() && SimMimic.hasMimic()`, outlining every mimic
  candidate within 40 blocks - and `SimSecrets` registers every placed secret chest as one (`SimMimic.addCandidate`).
  Deleted at his request; Secret Waypoints is the only thing drawing secrets in the sim. When a feature seems to ignore
  its setting, look for a SECOND renderer drawing the same thing.
- **Secret Aura gated on `getCurrentServer()` being Hypixel/p3sim**, null in singleplayer, so it never acted in the sim;
  it also accepts `SimState.canAct` now. (Its `PLACED_WITHER` essence branch is gone: the sim's essence is Hypixel's
  profiled player_head, collected on the server.)

## Item secrets, bats and chests

- **Picked-up item secrets are deleted from the inventory** the tick after they are counted. Only stacks carrying the
  `killer560_sim_secret` CUSTOM_DATA mark go, so `/item` copies are never touched; Architect's First Draft is not in the
  secret pool and is excluded by id anyway. `SimArchitect`'s auto-get on a puzzle fail still hands one over (his earlier
  request) - drop that if "only from /item" is meant literally.
- **Pickup range is the Magnetic Talisman's** ("the gravity talisman" in his words; on the wiki the Magnetic Talisman
  "increases the player's item pickup range by 3x", the Gravity one gives Strength/Defense). Vanilla's reach is the player
  box inflated (1.0, 0.5, 1.0) (`Player.aiStep`, javap); `SimSecretItems.magneticPickup` touches every item in the box
  inflated (3.0, 1.5, 3.0) with vanilla's own `ItemEntity.playerTouch`, so delay, full inventory and the take packet stay
  vanilla's. Testkit 641: taken up to 3.4 blocks, left from 3.5 (3.425 expected). Item secrets measure his body to the
  item's block (3 blocks, gap to gap): feet to block center missed one 3 blocks ahead (testkit 1405).
- **A secret bat appears within 10 blocks of his feet, and its death counts for the room.** 8 was a guess: his Doors
  route's BAT node, recorded on Hypixel where the bat appeared, stands 8.6 from it. 10 is Auto Routes' own bat-spawn window
  (`AwaitEvents.BAT_SPAWN_RANGE`), the widest a recorded node can rely on; still not measured on Hypixel. Reference mods
  (shallow clones in killer560s-mod-logs/*-ref) state no spawn distance: Skyblocker matches a dead bat to its waypoint
  within 16 blocks, Odin reads the death from the BAT_HURT/BAT_DEATH sound at volume 0.1 (vanilla's, so sim bats send it),
  SkyHanni names it "Dungeon Secret Bat". Bats used to spawn with the floor unregistered, so their death counted nothing,
  `SimScore.batKilled` never reached the per-room count, and Auto Routes' await (which counts a secret bat's APPEARANCE
  within 10 blocks, QUOI's rule) never saw one appear. The kill is read off the entity (dead or removed KILLED), not
  `getEntity(uuid) == null`, which also fired for a bat in a non-ticking section. Testkit 643.
- **Secret chests appear when he comes near, like bats** (his request). `SimSecrets.chests` registers each database chest
  with `SimSecretItems.registerChest` (a chest the capture already had on that spot is taken out) and sets it once he has
  been within `BAT_TRIGGER_RANGE` (10) for `BAT_TRIGGER_TICKS` (5) server ticks in a row. Not in puzzle rooms (their chests
  are the puzzles'). The mimic turns its pending chest trapped (`trapPendingChest`); the build's "secret chests in place"
  audit counts a pending chest as placed. Anything looking for a far secret chest as a block (an etherwarp onto a chest top
  from over 10 blocks, the cheat map's trapped-chest tint) finds air until he is near. Testkit 1018-sim-chest-appear.

## The mimic

- **One mimic, and only from Floor 5** (killer560: "always and only make 1 mimic per run. except if you are on floor 4 or
  below then it should never have one"). A property of the floor, not chance. `SimScore`'s wiki-sourced note says VI and
  above; his rule is the one implemented, and the two are flagged rather than reconciled.
- `SimState.setFloorLabel` once had no caller, so the sim believed it was on F7 whatever was generated; `SimFloorGen.generate`
  sets it from `Floor.code`. The mimic is picked in the build's completion callback, after the secret chests go in (it used
  to run in the placement loop and saw only the captures' own chests), and only among the chests `SimSecrets` placed.
- The mimic's room gets the map's ordinary `MIMIC_TINT` from the ordinary check (a `trapped_chest` at a database secret
  position, cheat build); the old sim-only `mimicCell()` branch in `MapPainter` (it took the NEAREST cell center, a doorway
  cell for any chest over 8 blocks off its tile center) is gone. The map latches the room for the run
  (`MapPainter.MIMIC_TILES`), because the chest turns to air when the mimic springs - which lost his room its red on Hypixel.
- **`SimMimic` answers the use-on-block packet on the server.** The mimic used to spawn from the CLIENT's chest-click
  callback via `server.execute`, so Secret Aura's click (which bypasses the client's gameMode) followed by Kill Mimic's
  Hyperion could reach the server first and the Wither Impact hit nothing (one run in five, 96-ar-644). Hypixel's rule
  follows: a chest counts as a secret only when the server accepts the click, i.e. in reach (96-ar-charges had to move
  closer than 7 blocks). His "kill the mimic then teleport in the direction of the mimic" was not reproduced in 14 tries.
  Found on the way: a straight-down Hyperion moved an off-center player to his block's center (0.42 blocks, 644-sim-hype-down; `dashTarget` settled on his
  own block; fixed, his own block is not a teleport); and in legit mode with Secret Aura on, Kill Mimic's Hyperion fired at
  pitch 50.9 after its 20-tick aim timeout, along his look at the mimic (Auto Routes, `MimicKiller.tick` AIM).

## Crypts

- **A crypt wall is NOT a solid slab of cracked brick, and decoration is speckled.** Decoded from all 135 shipped captures
  (`src/main/resources/assets/killer560smod/rooms/`): 119,343 cracked stone bricks and 3,666 chiseled, in 90,226 separate
  clusters over `SimItems.isFragile`'s block set, 71,260 of them a single block (79%), only 661 of six or more. Cracked
  brick is floor and wall SPECKLE, not a crypt marker; only 411 cracked bricks in the library seal a small air pocket, so `connectedFragile` breaking one block
  aimed at decoration is the correct output of a correct flood fill.
- A real crypt wall is mixed: `Redstone_Crypt` at room-local x=25, z=27..29, y 69..72 is `cracked_stone_bricks` with
  `stone_bricks`, `mossy_stone_bricks` and plain `stone` through it. A fill stepping only on fragile blocks broke a median
  of 2 blocks and joined the chamber 205 of 411 times (50%). A thin SLAB across the wall (the stone-brick family: cracked,
  plain, mossy, their stairs and slabs; 2 across the face he looks at, 2 deep) broke a median 34 and opened 362/411 (88%).
  Adding plain `stone` reaches 95% at 51 blocks but punches into the room's own stonework ("it shouldnt just break blocks
  in its way").
- The crypt chamber is in the capture (70 of 135 rooms hold a cracked brick sealing a pocket of 48 air blocks or fewer),
  but nothing says WHICH brick, so `sealsAChamber` re-derives it at detonation and has false positives (gaps under stairs,
  voids in stonework, pockets behind leaves).
- **`Crypts: 0/5` on the score HUD could not move in the sim**: `ScoreCalculatorFeature.getCrypts()` is written only from
  the TAB LIST (`TAB_CRYPTS` in `pollTabList`), while `SimScore.cryptBlown()` fed only `SimScore.summary()` and
  `SimSidebar` has no crypt line. (The sim's tab list now carries it: [SIM-SERVER.md](SIM-SERVER.md).)
- **Crypt zombies are not starred**, and neither is the prince's (a golden crypt is still a crypt). The mimic and the
  sim's `/summon` still spawn starred. Sim mobs are `setNoAi(true)`, so no gravity: `SimMobs.spawnCrypt` drops crypt and
  prince undead to the top of the first collision box below (Big Red Flag's prince once spawned in mid-air above its
  blasted tomb; testkit 1401/1402 check the feet).

## Superboom

- **One Superboom takes everything in reach, by whole groups** (killer560, Big Red Flag: "that whole structure should be
  gone"; "if it is a chain of blocks but any one is within the 3 blocks then it should break them all"). Reach is radius 3
  from the center of the hit FACE to each block's box (`SimItems.inReach`; it was an x/z 3, y 2 cube,
  `SUPERBOOM_CRYPT_REACH`). Every prince with any tomb block in reach goes whole (`SimPrince.tombOf`, below); a slab crypt
  whose lid or rim is in reach opens (`withStairsAndFloor`, his 2026-10-01 rule - 421 slab runs in the library, many of
  them furniture, so no tomb rule there); every cracked brick sealing a chamber opens it (a chamber counted once); a fragile
  block in reach takes its whole connected fragile chain (256 blocks, 15 across). Each crypt and prince spawns its own
  undead. A lid must be mostly BOTTOM slabs (`CryptScanner`'s rule), or Big Red Flag's two top-slab eaves opened as crypts.
  Captures carry no "breakable" mark, so groups are by block family and structure. Testkit 642, 645, 1401-1403.
- The floor's prince scan reads only loaded chunks, so a prince can go unregistered (Leaves through route practice:
  prince=false). Every Superboom rescans an 8-block cube round itself (`SimPrince.scanNear`); testkit 1404.
- **King Midas: the altar is the crypt.** `rooms/King_Midas.json` has one crypt in the database and one smooth stone slab
  run: the altar, a 5 x 7 stone-brick tomb at capture x 14..18, y 67..69, z 9..15 with a 35-slab bottom-slab lid at y 69,
  gold at its four foot corners and under the lid's front row, the skull at (16, 68, 15). killer560: its mob spawns only
  once the altar is superboomed. Opening it spawns a Crypt Undead and a starred King Midas miniboss (`SimItems.superboom`,
  by `SimWhere.roomNameAt` of the opened crypt), so the room clears when he dies. Auto Clear finds the lid by its blocks
  (`autoclear/MidasAltar`: a bottom smooth stone slab run over 20 with gold under it). That a miniboss could not die
  (`SimSurvival`'s ALLOW_DEATH) is in [SIM-SERVER.md](SIM-SERVER.md). Testkit 131-sim-midas-1020, 62-argrim-1020-midas.

## Princes: five signatures

Every rule lives in `CryptScanner` and is asked by both `SimPrince` and Secret Waypoints, so they agree. A rule loose
enough to match one more feature matches many (gold alone, andesite beside any lantern: 23 rooms; obsidian alone: 12).

1. **Gold touching smooth stone slab**: Chambers, Sloth, Red Blue, Market. Pirate's three gold-on-slab spots are surely
   decoration now that its obsidian tomb is a prince (not changed).
2. **The prince has a second signature: a sea lantern with polished andesite on all FOUR horizontal sides** - Leaves'
   prince (capture 10,82,16: 3x3 polished andesite, sea lantern in the middle, a player head on its side, stone brick
   stairs under it) and Stairs, both with the full eight-block ring, nothing else in 135 captures. Stairs is an inference,
   reported in the build log by room.
3. **Big Red Flag's tomb**: a 2x3 lid of full smooth stone ringed by bottom smooth stone slabs (`SimPrince.LID`), exactly
   that one structure in the library. The wiki's Prince page does not describe the tomb.
4. **Obsidian with air above and a bottom smooth stone slab beside it** (`CryptScanner.obsidianPrince`, from killer560 in
   Supertall: "this is a prince" - the raised tomb at capture x 48..53 y 142 z 44..48, a 4x4 obsidian ring round a 2x2 of
   double smooth stone slabs, rimmed by bottom slabs, on stair legs). The wiki's Supertall row ("6 Crypts (4 Crypt Undeads,
   2 Princes)", one on Level 2 behind cracked bricks) found the second: the obsidian tomb at x 4..6 y 99 z 11..14, which
   the crypt rule had counted as a crypt. Six tombs match: Supertall 2, Withermancer, Grass Ruin, Pirate, Waterfall. The
   wiki's Princes column is filled only for Doors, Skull, Supertall and Withermancer (the last two match exactly; the
   other three are inferred); counted as princes, lids plus obsidian princes equal the database's crypt count in
   Withermancer, Grass Ruin, Pirate and Waterfall. **Skull** is the one no rule finds (its prince is a plain 3x4 slab
   lid), and Supertall's three chest platforms at y 141 pass the crypt rule (7 lids for a room with 4 crypts plus its
   princes); neither changed. Testkit 1781 (both tombs, one Superboom each, /reset restores them), 1782, 1783.
5. **Sarcophagus** (`CryptScanner.sarcophagusPrince`): a run of bottom smooth stone slabs every one lying on polished
   andesite, with a head touching the run on its layer. Its tomb: a 5x3 polished andesite body (capture x 14..18, y 70,
   z 15..17) on stone brick stair legs round a polished andesite core (y 69), five slabs on top (y 71, z 16), two heads.
   Slab runs wholly on polished andesite also exist in Catwalk, Deathmite, Doors, Flags, Hallway, Pressure Plates and
   Prison Cell; only Sarcophagus' has a head. Testkit 1018-sim-prince-sarcophagus.

**What a blown prince takes.** `SimPrince.tombOf`: the smooth stone joined to the hit gives footprint and top, layers are
taken down to a fully solid one, at most 2 under the skin (Big Red Flag 86 blocks, the room wall at z 27 / x 18 outside;
Sarcophagus' floor at y 68 stays). `SimPrince.extrasOf` adds the player head touching it, the layer under its footprint
(legs, core). Its last step, `SimPrince.andesiteRing`, takes every polished andesite block touching the tomb (26-way), ONE
ring, never a flood ("yes fix all of them"): Stairs 11 (y 89), Withermancer 12 (y 71, the cover of its ladder shaft; its
three chiseled corners and one nether brick stay), Sloth 27 (x 11 / x 15 / z 31, y 71..73; z 31 is the outer wall, so the
blast opens a 3x3 window into the gap behind), Red Blue 4 (x 61, y 83, z 6..9), Grass Ruin 2 ((3, 96, 14), (3, 96, 19)),
nothing to any other prince. Left one further out: Stairs' ledge 6, Sloth's arch 11, Grass Ruin's upper posts 2, Red Blue 1.
Testkit 1018-sim-prince-stairs / -withermancer / -sloth / -red-blue / -grass-ruin, 645, 646.

## Levers and gates (`SimRoomLevers`)

- **Levers that open a way through, and why they are a table.** Gates like Mines' barred door and Pressure Plates' boarded
  wall are moved by Hypixel with a command, not redstone a capture could record, so nothing in the data ties them to their
  lever. `SimRoomLevers` is that tie, in capture-local coordinates (what `/simwhere` prints); per-room facts and how each
  was matched are in its javadoc (39 rules). Mines' door: an 18-block dark oak frame at `x=50, y=78..84, z=58..60` with
  three iron bars, 21 cells; Pressure Plates' wall: 30 blocks of oak at `x=54..55, y=93..97, z=15..17`. A flood fill
  matching the box's volume checks the box is the thing and nothing else. Entries come from killer560 looking at the
  block and running `/simwhere`; decoding and reasoning gave two confident wrong answers on these same rooms.
- Bridges and Raccoon have their lever in the capture; Logs and Arrow Trap had none and the sim places a floor lever on
  the wood block he pointed at (only into air). Doors' four levers each stand on a log of one wood and open the door of
  that wood (testkit 1407). Deathmite and Doors added 2026-10-13. Testkit 647-650 flick each and require the whole door gone.
- **Pair a lever with its gate through the secret ROUTE, not by distance.** Quantizr's DungeonRooms waypoints
  (`secretlocations.json` in Skyblocker's test resources, killer560s-mod-logs/skyblocker-ref; GPL, read only) number each
  secret's steps: "5 - Lever" goes with "5 - Chest", and a "5 - Stonk"/"Superboom"/"Entrance" point usually sits IN the
  gate. `tools/lever_gates.py` fits the route to each capture by its chests (four turns and a shift) and ranks the
  capture's gate bodies (wood, bars, barriers; cobblestone, walls and fences each their own class) by that secret.
  Nearest-to-the-lever is often wrong (Bridges 22 blocks, Long Hall 18). It only proposes: three picks lost to his shots
  (Overgrown's log wall, not the stonk bars; Mines' second lever, the oak wall, not the window; Long Hall's panel, not the
  bars nearest the chest). Name differences: Mithril Cave = Mines, Draw Bridge = Pirate, Sewer = Pipes, Rail Track = Rails,
  Redstone Skull = Redstone Crypt.
- **RSM's waypoints find a lever or node a capture is missing.** `rsm-ref/.../assets/rsm/secrets.json` (killer560s-mod-logs)
  lists every secret per room, LEVER and REDSTONE_BLOCK included, center-relative (+16 = capture, +15 = database) in the
  canonical turn; turn it by the k that lands its chests, keys and levers on the capture's blocks (Cobble Wall Pillar k=1,
  Golden Oasis and Redstone Key k=0, Redstone Crypt k=2).
- **A capture can be the table's room turned round.** Rules are capture-local and RoomLibrary prefers a local capture over
  the bundled one (Mod Only Test's Overgrown is the bundled one turned 180). `SimRoomLevers.fit` tries every turn the shape
  allows and keeps the one landing the lever on a lever and the most gate blocks in the box, turn 0 on a tie.
- Layers and Purple Flags have no lever where route and RSM agree; the sim places one (Layers on the smooth stone the
  data's y stands one above, Purple Flags hanging from the cobblestone over air). Layers' other lever was captured
  powered: armed levers are set off first, or the first click reads as nothing.
- **Gates can be BARRIERS or lattices of WALL blocks.** Cobble Wall Pillar's gate has Hypixel's barriers three by four at
  z=28 behind the bars at z=27, so its box is x 18..20, y 60..63, z 27..28 (24 blocks); look for barriers behind any gate.
  Rails' and Spider's gates are `cobblestone_wall` lattices in a cobblestone facade ("the cobblestone fences"); grown with
  the facade they filled no box. Floor hatches (Supertall's 3 by 3, Museum's panel) are walls too: a gate may be thin in
  any axis.
- **Secret Waypoints' lever band was 68..108**, which missed 21 captured levers in 11 rooms (y 53 to 142), on Hypixel too;
  it scans the whole height now (the section palette check makes that free).
- **`/simwhere`**: its "db-relative" line once printed the shifted world y (`toRelativeCoord` passes y through); it prints
  the captured y, so the two systems differ only in x and z. It said "not inside any placed room" for a room's walls by
  picking the nearest cell of the 11 x 11 map grid, whose odd cells are connectors; `SimWhere.roomAt` asks each placed
  room's pasted footprint. Testkit 1980 (his Rails point reads back as capture (56, 51, 42), Spider (21, 84, 33), Atlas
  (8, 81, 25)).
- Testkit 1961-1979 (`SimLeverGateTests`): library-wide sweep, each listed room solo with each lever, Grass Ruin with
  Layers on one floor, `/reset` restoring Grass Ruin's window (1978 is a screenshot survey, only when named). Reach is to
  the lever's box: Purple Flags' lever is 4.4 to its box from the floor and 4.9 to its center. 1412 (Cobble Wall Pillar),
  1414 (Golden Oasis lever).

## Keys

- **No Wither Key drop.** The last starred death no longer calls `SimDoors.dropKeyAt` (the only key the sim made), so the
  **blood door opens on a plain right-click** (main hand, nothing consumed). Wither doors still need a key, but generated
  floors build none and drop no keys. Bring the drop back with per-room keys if they ever are.
- **Key rooms** (sim side; the Auto Clear side is in [LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md), "Key rooms"):
  `SimKeyRoom.armForTest(room)` (test aid only; nothing happens unarmed), judged on the server from the server player's
  positions: the room's first entry by a jump over 2.5 blocks in a tick insta clears it (cleared, mobs standing, key
  lost); a first entry on foot clears it at its last starred death, when its Wither Key drops there (`SimKeys.drop`).
- **Redstone Key, what the client sees on Hypixel** (RSA, RSM, QUOI and Odin, read not copied): the key is a `player_head`
  with profile id `fed95410-aba1-39df-9b95-1d4f361eb66e`, and picking it up turns that head to air; the node is a
  `redstone_block`, and when the key goes in the block changes, an armor stand wearing the key appears with its block y two
  below the node, and a piston sound plays (RSM counts the node's secret off `block.piston.contract`). The wiki: placing it
  "rewards 1 secret"; since 0.18.4 it is not an item and anyone can place it once found. `SimRedstoneKey` sends all of that
  from the server: pickup is not a secret, placing is one (`SimScore.secretFound` at the node).
- **One Redstone Key per room.** Redstone Key's database lists two spots (19,66,7 and 10,70,26); Hypixel spawns one, and
  `SimSecrets.place` keeps one at random (`setKeySpotForTest` forces it). Auto Routes' side is `autoroutes/KeySpots`
  ([LESSONS-ROUTES.md](LESSONS-ROUTES.md)).
- **Golden Oasis' node is missing from its capture** (air at capture 11,71,4, a stone-brick ledge); Redstone Crypt's and
  Redstone Key's hold theirs. The sim places a redstone block there, only into air.
- **Placing the key removes the room's batch of redstone blocks.** `SimRedstoneKey.batchOf` reads it off the capture: every
  redstone block in a group of two or more (26-way), not the node's group, not a group reaching the highest layer holding
  redstone (every roof carries redstone markers). Each key room has exactly one: Golden Oasis 3 at (27..29, 70, 26),
  Redstone Crypt 7 at (16..19, 69..70, 16), Redstone Key 4 at (19..22, 68, 24). Only blocks still redstone are taken.
- Auto Routes' await (`AwaitEvents.onLocalBlockClick`): the pickup counts nothing; the node click counts at once when a key
  is known found (his pickup seen to go, or Secret Aura's `keyFoundNotPlaced`) on a face open to air, else when the server
  shows the key in within 40 ticks (block changed, a key head on top, or a stand wearing the key). Auto Secret's last-node
  wait holds for a node the aura has due. `SimRedstoneKey.armForTest`/`foundForTest` put a key room's answers in any arena
  (testkit 96-ar-1016-key*).
- **Open: what the node looks like after placing on Hypixel.** The sim (from RSA) turns the node to air with a stand
  wearing the key; killer560 said on 2026-10-12 the key sits ON TOP of the block (the testkit server's model, and
  Skyblocker files the secret at the block's `above()`). Every reader accepts both; a real capture would settle it.
- Testkit 1415 (the key, a no-key node click first as control), 1018-sim-keyspot-one, 1018-sim-skull-golden-oasis /
  -redstone-crypt / -redstone-key, 1018-ar-keyspot-empty.

## Change Room

**Change Room opens the page used last**: the pause screen's button opens `SimMenuNav.open` (the designer after a
generated map), Load a Room when the menu was not opened yet this session. Testkit 1018-sim-changeroom-lastpage.
