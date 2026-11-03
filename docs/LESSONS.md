# Feature-specific lessons

Moved out of CLAUDE.md to keep it under its size limit. Same rules: problem, then fix; verified only.

The rest of the feature lessons are split by topic:
- [LESSONS-MINECRAFT.md](LESSONS-MINECRAFT.md): vanilla, Fabric and Java behavior - packet order in a tick, hotbar swaps
  and `ensureHasSentCarriedItem`, `AttackBlockCallback` per tick, swallowing a click, `ClientLevel.hasChunk`, entity
  subclasses on reload, PEACEFUL on 26.2, `MODIFY_GAME`/`ALLOW_GAME`, chat click styles, color codes in number patterns,
  Brigadier live commands, javac constant-pool entries, Windows file replace, `ServerTickClock`, `Set.of(null)`.
- [LESSONS-PERF.md](LESSONS-PERF.md): measuring FPS and per-feature cost (95-fps-bench, PerfProbe, JFR, mixin
  allocation), FeatureGuard/FeatureBits, chat-line garbage, memoized tab text, a cache that is never clean.
- [LESSONS-DUNGEONS.md](LESSONS-DUNGEONS.md): the live map grid and map corner, Fairy's wither door, blue terracotta
  markers, Predictive Rooms, CryptScanner, item pickups, lever waypoints, per-run done sets, the Murkbat bonus, wiped-run
  PBs, insta-clear observations, Blood Camp, terminals and Tic Tac Toe, and the feature side of routes, the Interactive
  Map and the puzzle autos.

GUI, HUD and rendering lessons are in [LESSONS-GUI.md](LESSONS-GUI.md), [LESSONS-HUD.md](LESSONS-HUD.md) and
[LESSONS-MENU.md](LESSONS-MENU.md). Code comments cite some of them as "docs/LESSONS.md": "Every GUI fill costs more than
its pixels" is in LESSONS-GUI.md, "Two mod-menu rows on one rectangle" in LESSONS-MENU.md, and the HUD element `width()`
lesson and Storage Overlay unclampable in LESSONS-HUD.md. The 26.2 `Gui.extractRenderState` signature crash is in the
project CLAUDE.md. Compiling lessons
(API names across versions, the cloud-session javac filter) are in [COMPILING.md](COMPILING.md).

## Items and Hypixel data

- Instant Transmission is 8 blocks on Aspect of the End, Aspect of the Void AND the Etherwarp Conduit alike.
  What changes the range is the item's own `tuned_transmission` tag - a Transmission Tuner adds a block, four
  maximum - so a fully tuned one of any of them goes 12, and killer560 plays fully tuned ("nearly no one plays
  with less"). AOTV is NOT 12 by nature; assuming that got AOTE and AOTV wrongly split in the route matcher
  once already. `EtherwarpHopper` reads the same tag for the 57-block etherwarp.
- `ItemIdentity.of()` is shared by Auto Sell, the Inventory Sorter, Armor Dye and the mining profit tracker (shelved
  until after 2.0). Widening it to make two items equal makes "sell my Hyperion" sell an Astraea. Loose matching belongs
  in `matches()`, which only a route's USE_ITEM node reaches.
- A shift-click in Hypixel's Trades menu sells the clicked STACK only, not every item of that kind. Auto Sell marked an
  identity done after one click, so three Dreadlord Swords took three visits and two Training Weights stacks two
  (log, 2026-10-20). `AutoSellVisit` tracks clicks per slot, capped per identity at the most stacks seen in one scan.
- Custom Items' `DataComponentHolder#get` override must never reach `getHoverName`: on a player head that is
  `PlayerHeadItem.getName`, which reads the overridden `minecraft:profile`, so an id-less head (one on an armor stand)
  overflowed the render thread (2026-10-18). `ArmourDye.identityOf` reads the name with `entryFor` switched off per thread.
- **Skyblock price ids differ by source.** hysky.de (Skyblocker's keyless lists, Price Tooltip) keys pets
  `LVL_1_LEGENDARY_GOLDEN_DRAGON` and runes `MUSIC_RUNE_3`; Coflnet and moulberry use NEU ids (`GOLDEN_DRAGON;4`); the
  Bazaar keys books `ENCHANTMENT_SHARPNESS_6`. moulberry.codes answered 525 on 2026-10-15. Item `timestamp` is a long of
  epoch ms on 867 of 867 live auctions checked.
- **Hypixel's public item list has no dyes.** `/v2/resources/skyblock/items` (the item browser cache, 5,680 items on
  2026-10-19) has no `DYE_*` id, and its `color` field is only on leather armor, so a dye list built from it is empty.
  `armourdye/HypixelDyes` holds the 69 dyes with the hex each one's lore prints, copied from the NotEnoughUpdates-REPO
  `items/DYE_*.json` files.
- Vitality is NOT a Rift stat: it is a combat resource (healing abilities, Wither Shield, Creeper Veil - "Not enough vitality!
  Creeper Veil De-activated!" is in his own logs) shown on the action bar as `current/max` + U+E028 (hypixelskyblock wiki),
  which the generic Other readout caught until 2026-10-07. `PlayerStatsFeature.VITALITY_REGEX` reads it and Other skips U+E028.
- Hypixel SkyBlock has no command that summons a named pet. `/pets`, `/pet`, `/petmenu`, `/petsmenu`, `/viewpets`
  and `/viewpetsmenu` are all argument-less aliases that open the Pets menu (hypixelskyblock.minecraft.wiki
  Command page, checked 2026-10-04), and Autopet rules fire only on game events, never on demand. A pet summon
  must go through the menu, which is why Pet Wheel opens it (headless when Hide Pets Menu is on).
- **Hypixel's SkyBlock resource pack is required**: the 0.26 announcement (hypixel.net thread 6117801) says it is
  force-enabled and SkyBlock cannot be joined without loading it, and players who declined report being kicked with
  "failed to load resource pack". Noamm's PackDisabler gets round that by answering ACCEPTED + SUCCESSFULLY_LOADED and
  canceling the push, i.e. reporting a pack it never applied. Our Pack Disabler leaves the push to vanilla (the pack
  really loads) and only swaps the model each SkyBlock item is drawn with, at `ItemModelResolver.appendItemLayers`.
  Hypixel's pack overrides no vanilla item textures, so a vanilla look still comes from his own packs.
- NotEnoughUpdates moved items onto Hypixel's pack models in SEVERAL commits on 2026-07-09 (Auction House, Other,
  Bazaar Items, Recipe, More), so the parent of the Bazaar one (26169fe) already had 299 items on pack models. The
  pre-pack look comes from the newest snapshot where an item was not yet a pack model: 26169fe, then 0046933 (the
  parent of the first conversion), then 60e030e (April). With those, 95 of the 1,366 pack-model items have no old
  look anywhere (`tools/items/gen_item_looks.py`); they are the ones with our own textures.
- Hypixel's public items resource has only ~170 item skins and none for Wither Goggles (checked 2026-11-01), and the old
  Hypixel Skin list matched by the selected item's full display name ("Heroic Wither Goggles ✪✪✪✪✪" matched nothing). The
  NEU repo has 180 skins whose lore names the items each applies to; `tools/item-skins-extract.py` bakes them into
  `armourdye/item-skins.json`, and `ItemSkins.forItem` matches by Skyblock id (STARRED_ dropped) or the cleaned name's end.
- The Witherborn wither of a full F7 armor set is a `WitherBoss` at `getInvulnerableTicks() == 800` (SkyHanni's "Mini
  Wither"; Noamm's boss filter excludes 800, so the four F7 bosses never match). Its smoke and swirls are NOT packets:
  `WitherBoss.aiStep` adds them on the client (three `Level.addParticle` calls), so only a hook there drops them.

## Bazaar and Croesus

- **Hypixel's Bazaar summaries are named the opposite of how they read.** In
  `api.hypixel.net/v2/skyblock/bazaar`, `buy_summary` is the book you INSTANT-BUY OUT OF and `sell_summary` is
  the one you instant-sell into. Verified on `VIBRANT_CORAL` (2026-09-29): `quick_status.buyPrice` 3324220.9
  matches `buy_summary[0].pricePerUnit` and `sellPrice` 221605.8 matches `sell_summary[0]`. Reading them the
  other way round produced a fake 1.6-billion-coin flip. `quick_status.buyPrice` is also a weighted AVERAGE,
  matching the exact top of book on only 628 of 1833 products, so anything sizing a real purchase must walk the
  levels. Scale check for the Bazaar-to-NPC flipper: of 819 products with an `npc_sell_price` only ~46 profit at
  all and the worthwhile margins are 0.4%-1.5% - a result far outside that band means the book is backward.
- `/bz <name>` opens a SEARCH page (`Bazaar ➜ "Lump of Magma"`, product at slot 11), not the product page: the
  flipper's old buy step looked for "instant buy" there and stopped every session (his dry run, 2026-10-08). It now
  reads every page through `BazaarPages` and walks search -> product -> Instant Buy by name (testkit 761-764).
- **The Instant Buy page quotes the book plus 4%.** Wiki (Bazaar): "The Bazaar will quote a price 4% higher than the
  cheapest sell offer", the unused part refunded. His flipper refused Enchanted Pufferfish every time (2026-10-17) because
  the page said 2,433.1 each against NPC 2,400 while the API book, 7 s old, started at 2,339.5 (2,339.6 x 1.04 =
  2,433.1). Not stale data and not the wrong book side: compare quote / 1.04 with the NPC, and the full quote with the purse.
- **Hypixel cuts long container titles.** His flipper stopped on `"Enchanted Pufferfish ➜ Instant"` (2026-10-16),
  not "... ➜ Instant Buy"; his Dungeons logs have `"Enchanted Gunpowder ➜ Instant B"`, `" Fine Jasper Gemstone ➜
  Instan"`, `"EXP Bottles ➜ Titanic Experienc"` - none longer than 31 visible characters. A title pattern with a
  fixed tail misses every long product: match any start of the tail, decide by the page's items, and check the
  product by its full name elsewhere (`BazaarPages.isInstantBuyTitleFor`, testkit 2761, 2775).
- Custom Amount opens a SIGN, not a container: Hypixel closes the menu, sends a fake sign and an open-editor packet
  ("" / "^^^^^^^^^^^^^^^" / "Enter amount" / "to order"). Fill it through the screen itself - `charTyped(new
  CharacterEvent(c))` per digit, then its Done button, whose `removed()` sends `ServerboundSignUpdatePacket` - in one
  tick, so a stray Escape never sends a half-typed number (`AbstractSignEditScreen`, same on 26.1.2 and 26.2; testkit 2761).
- **Hypixel stacks by its own rules, not the vanilla material's.** Kaleidoscopic Mineral (`SALMON_OPAL`) is PAPER, so the
  flipper planned 254 for an inventory that holds one per free slot (his log, 2026-10-30 11:02). Hypixel's items API marks
  539 items `unstackable` and 181 `has_uuid`; the 107 Bazaar reforge stones (category `REFORGE_STONE`) carry neither but
  do not stack either, and nor do enchanted books. The Instant Buy page's "Fill my inventory!" amount is Hypixel's own
  room count: never ask for more (`BazaarStackSizes`, testkit 2790). One buy order: 71,680, or 256 unstackable (wiki).
- **An Instant Buy preset BIGGER than the amount says nothing about its price** - it averages in dearer levels - and the
  top of the book alone under-prices a big buy. Judge by the biggest preset at or under the amount, walk the book for the
  rest, and check the Confirm Instant Buy price against the purse above the reserve before clicking it; a dear confirm is
  resized and retried, not skipped (testkit 2791).
- Hypixel's order-slot refusal reads "[Bazaar] You reached your maximum of 21 Bazaar orders!" (his log, 2026-10-30); 21 is
  his real slot count, not the autopilot's default 14.
- **A big Bazaar Insta Buy reaches the inventory over more than one tick.** His "Bought 1,472x Gravel" (Fill my inventory!,
  2026-10-30 11:01:17) was counted as 832 because the autopilot took the first rise of the carried count as the whole buy.
  It now waits until all the Bought line's units are carried or the count has stood still 750 ms (the rest went to sacks),
  and costs per bought unit (testkit 2801 delivers in two parts; the old jar recorded 1,152 of 2,304).
- **Auto Relist's /ah menu names are from public mods, not a recording** (2026-11-02): NEU's Create BIN Auction slots 13
  (item) / 29 (create) / 31 (price), SkyHanni's "Manage Auctions" and "Status: Sold!/Expired!" lore, Skyblocker's "Collect
  Auction" and "... Auction View". Every match is a case-insensitive "contains" with the fixed slot only as a fallback, and
  each page is logged (`[AhRelist]`). Correct them from a `/ahmenudump` recording (`killer560smod-ahflip-menudump.txt`)
  before trusting it on Hypixel; the price sign's lines and the bids page's claimable lore are the least certain.
- The Auction House endpoints (`/v2/skyblock/auctions`, `auctions_ended`) need no API key, so the AH Flipper polls Hypixel
  directly; nothing goes through the relay (checked 2026-11-01).
- **A wait that ends on "the confirm page is open" must not end on the one it just clicked.** That page stays open until the
  server answers, so the Bazaar Flip autopilot read its own clicked Confirm Instant Buy as a new question, dropped its
  wait, and bought from the reopened Instant Buy page a second time (testkit 2762, 2026-10-22). Key such a wait on the
  clicked page's container id.
- A Croesus chest's Spirit Stone was worth 0: `DungeonChestValuer.priceLine` asked `RngItemNames.BY_NAME` (every RNG meter's
  names) before its own replacement table, and the Wolf slayer's entry said "Spirit Stone" -> `SPIRIT_STONE`, an id that
  does not exist (Hypixel's items API and the Bazaar both say `SPIRIT_DECOY`). killer560's M4 chest read ~35k instead
  of ~1M. The chest table now wins and the RNG entry is fixed (testkit 773-775). An id sweep against the items API also
  flags `SPIRIT_BOW`, `ANCIENT_ROSE`, `VAMPIRE_FANG` and the Tungsten/Umber sets in RngItemData as ids that do not exist.
- Auto Croesus never spent a key because Hypixel closes Croesus after a claim and the reopened head no longer reads
  "No chests opened yet!", the only thing the menu scan opened. It now decides before the first claim whether another
  chest is worth a key and walks back into that run (page:slot, same floor) after the reopen (testkit 766/767).

## Experiments and containers

- **In an open Hypixel menu, judge what the player has by that menu's own inventory half, not by
  `player.getItemBySlot(HEAD)`.** Mask Keybinds skipped a mask that read as "worn" from the helmet slot; killer560
  (2026-11-02): alternating presses worked with both masks in the inventory but stopped once one was on his head. The
  inventory half of /stats is refreshed after each swap (that is what lets the both-in-inventory case alternate), the
  helmet evidently is not while /stats is open, so the mask that had just come off stayed "worn" and every later press
  found nothing. Fix (maskswap-1102): pick only from the masks in the inventory half, read on every press; only a proc
  cooldown refuses. Testkit 1889 holds the client helmet stale and must still alternate six presses.

- **Superpairs powerups come in two kinds, told apart by lore.** "Instant powerup!" (the `+479,095 XP` lapis
  block, "Gained +3 Clicks") applies on the spot; only "Powerup for next click!" (Instant Find) matches the next
  click. Arming on both spent a lone click on an Enchanted Book and reserved it, blocking its pair (2026-10-01).
  Turning a powerup over is all it takes (killer560, 2026-10-05): it never uses a turn's click slot (the open tile
  stays up), Instant Finds stack ("insta find into an insta find then your next two clicks are insta finds"), and
  there is no activation click - the solver's old second click on the face-up tile did nothing but wait out 1s.
  XP reward tiles are recognized by NAME (`... Enchanting Exp`): the item varies, and cocoa beans were missed.
- **A Superpairs board holds at most 2 powerups** (killer560, 2026-11-03: "at max there can only be 2 powerups in a
  table"; the Overview had shown "Undiscovered powerups: up to 9"). `ExperimentSolver.MAX_SUPERPAIRS_POWERUPS`. The unseen
  tiles that are no known partner are 2 x (whole pairs) + (covered powerups), so with the powerups already turned over
  off the cap, an odd count is exactly 1 covered powerup and an even one is 0 or 2 (0 once fewer than 2 are left). The
  Overview uses it (`SuperpairsSummary`); the solver does not need it, since the cap can prove a hidden pair exists but
  never that no money is left. A board that contradicts it (3 seen, or odd with 2 seen) falls back to the uncapped count.
- Clicking a tile that is already uncovered does not use a click (killer560, 2026-10-01). A pair's first click
  can fail to land (tile still covered, others still read "Click any button!"); clicking the partner anyway lost
  Experiment the Fish, so the first tile is now re-clicked before its partner.
- **Superpairs is played in turns of two clicks**: a pair started while a tile is turned over matches against that tile
  and both pairs are lost, and a chance match that is not recognized as claimed gets re-queued and clicked as no-ops
  forever. The solver reads both off the board each decision (two face-up tiles of a kind = claimed, one = the turn's open
  tile; `ExperimentSolver.readBoard`). An offline port of the testkit's Hx table driving the solver class found both: in
  it, main's solver never finished 207 of 300 random boards (how often real Hypixel boards hit it is unmeasured).
- **A "first" rule in Superpairs has to cover the Instant Find too.** With the Nadeshiko Dye as the turn's open tile and
  an Instant Find turned over as its second click, the match went on a known Sharpness V while the dye waited; and
  Explore Before Claiming's held-back kinds, left out of `anyMoneySingle`, let a known XP pair go ahead of a Titanic
  (2026-10-20, a 2,400-board offline check of the solver class; both fixed in `armedTarget` and `firstClickOfTurn`).
- **A finished Superpairs board stays open on Hypixel** until it is closed; Escape takes you to the rewards (killer560,
  2026-10-11). Auto E-Table presses Escape once when Remaining Clicks reads 0; Hx's `holdOnOut` emulates it (testkit 735).
- **Chronomatron's chain grows only when a round's playback ENDS**: the solver latches the new note when slot 49 turns
  to the clock. A stop that waits for chain >= roundsNeeded + 1 therefore sits through the whole next sequence after the
  round that earned the clicks: 10/9 every run, about 10 s at round 10 (killer560, 2026-10-27). A round is done when
  glowstone returns after all its clicks went out (`ExperimentSolver.chronomatronRoundsCompleted`), and the stop uses that
  (testkit 2783: closed 3 ticks after the last accepted click, counter 3/3; the old jar took 33 ticks and read 4/3). Ultrasequencer shows its whole series when a round
  starts, so its size check does not wait for anything (2784).
- **"Is the bot driving?" is the run's state, not its switch.** Auto E-Table's manual-click follow (and the cursor clear
  and tier peek beside it) tested `cfg.isAutonomousMode()`, so after he stopped a run mid-Ultrasequencer his own clicks
  never moved the solver on and the 1-6 highlight sat on 1 (killer560, 2026-11-01). They test `autonomous && armed` now,
  and a stop gives back a click the run had decided (and counted) but not sent (`ExperimentSolver.rewindUnsentClick`).
  Testkit 1101-menu-etable-resume.
- A step that closes a menu and sends a command must time out if the screen it waits for never opens. Auto E-Table's
  Guardian swap closed the table, sent /pets, and waited forever when nothing opened; it now gives up after 10 s.
- **A container scan that commits once must keep reading while the menu is open.** The RNG Meter overlay committed after
  three identical frames (~50 ms, under one server tick) and never read that title again, so rewards Hypixel sent after the
  window were never logged: his item log had 32 Experimentation Table rewards, none from page 1 (2026-10-11). It now re-reads
  every 250 ms and merges anything new (testkit 736 adds a reward to an already-scanned page).
- Container automation must not treat a cursor stack mid-click as the player's. On 26.2 Auto Anvil's first build stopped with
  "something is on your cursor" in the first pair of testkit 292 and 293 (2026-10-07, not on 26.1.2, not on a rerun); the
  item was not captured. It now waits a cursor stack out mid-pair and gates each step on the menu's state id changing
  (`AbstractContainerMenu.getStateId`, which only the server's slot/content packets move), so a client prediction is never
  read as the server's answer. 292-297 then passed twice on 26.2.

## Settings, profiles and migrations

- A migration that reads an old file must decide from the OLD file's keys, never from the new config's fields after the
  carry-over: those already hold the new file's own values wherever an old key is missing. Score Calculator's legacy
  alert migration tested its own `mimicAlertEnabled`/... and so switched `enabled` on when nothing legacy was on
  (testkit 310 caught it once another case had left Score Calculator on, 2026-10-07).
- **A one-time migration marker is a setting's opposite.** The blank profile (2026-10-08) set every boolean false, and
  `breakerAuraMultiBreakDefaulted` false made the next load switch Multi Break back on (testkit 617). `BlankProfile` sets
  `*Migrated`/`*Defaulted`/`*Seeded` fields TRUE. The same walk found `AutoTrap` (own file, own switch, no `*Config`
  class) missing from `ProfileManager.reloadAllConfigs`. And reloading configs from disk drops anything changed in memory
  and not saved (nine network configs after testkit's Quiet profile), so the blank profile saves every config first.
- **A setting file in `other/` was never in a profile.** `ModPaths.settingFiles` took only `<category>/<feature>/file`, and a
  name with no table row lives at `other/file`, one level up, so Zoom, Cape, Legit GFS, Streamer and Mod Users never rode in
  profiles (testkit 320 caught it as an import holding one file more than its source, 2026-10-25). It now takes `other/file` too.
- **Class picks live in the Leap Order's file** (leap-queue-1015): the Class Overrides tab and
  `killer560smod-classoverrides.json` are gone; picks are the `classPicks` array of `killer560smod-leapmenu.json`
  (`LeapMenuConfig.pickOf/setPick/clearPick/allPicks`), and `dungeonclass/ClassOverrides` is only the static front every
  feature already read through (no `getInstance()`, no `load()`, nothing of its own in `reloadAllConfigs`).
  `LeapMenuConfig.load()` moves an old file in (a pick already there wins, saved first, then the old file is deleted);
  a profile saved before that puts the file back and the next `LeapMenuConfig.load()` migrates it again, so applying a
  profile must keep reloading `LeapMenuConfig`. A pick stored with a UUID that `PlayerNames` cannot map back to its
  name is invisible to `classOf(name)` (the lookup is UUID first, then the typed name) - testkit 1072 calls
  `PlayerNames.noteKnown` for its uuid entry.
- `setBreakerAuraCooldownTicks` clamped to a minimum of 1 while the field defaults to 0, so the default could never be
  restored once the setter ran; the SLIDER driving it still mapped onto 1-20 and was fixed separately (2026-09-30). A clamp
  has two halves - the setter and whatever widget feeds it - and a slider whose start position computes negative
  (`(0 - 1) / 19`) is the tell. Every other numeric setter in the repo was swept on 2026-09-30 and has its field default
  inside its clamp.
- **A running total that is checkpointed must be SHOWN live and STORED without truncation.** Best Friends added whole
  seconds at each 30 s checkpoint and restarted the segment at "now", so every checkpoint dropped its fraction, and the
  menu read only the checkpointed total, so the clock sat still for up to 30 s ("it doesn't count up every second",
  killer560 2026-10-08). It now stores ms from `System.nanoTime` with the remainder carried in the segment start, and
  the menu reads `BestFriendsTracker.liveTotalMs`. Testkit 593 (120.5 s of wall clock): old jar stored -2528 ms and
  showed -3028 ms; new jar +19 ms stored, +48 ms shown.
- **The Universal proxy is a file in Prism's SHARED folder** (`%APPDATA%/PrismLauncher/prismaccountswitcher/universal-proxy.json`),
  not in the instance, so a test client that presses Swap Accounts' Universal switch writes the proxy every one of his
  instances connects through (testkit 1851 on main read that real path, 2026-10-15). `-Dprismaccountswitcher.universalProxyFile`
  moves it ([TESTING-HOOKS.md](TESTING-HOOKS.md)); the testkit sets it, and 1851 refuses to press the switch on a jar that
  ignores it.

## Rendering pitfall cited from code

- On 26.2 `McRender.inCameraSpace` runs its callback LATER in the frame (`submitCustomGeometry`, built in
  `CustomFeatureRenderer.buildGroup`), after the tick may have rebuilt whatever list the callback reads. Secret
  Waypoints indexed its live list there and crashed the client ("Index 6 out of bounds for length 6", Render Frame)
  when the list shrank - testkit 98 on 26.2, 2026-10-05. Copy what the callback draws into a local array before
  calling `inCameraSpace`. Audited 2026-10-05: also fixed `WitherDoorsRenderer` (live `CACHED` list),
  `StorageSearchEsp` (live `TARGETS`, mutated by clicks) and both `renderLineStrip`s (`WorldRenderUtils`,
  `SolverEspRender`: callers pass live path lists). Safe because the lambda captures only an immutable `AABB`/`Vec3`
  and a fresh local `float[]`: DoorKeys, MageBeam, EtherwarpWaypoints (fresh per-frame entry list), mobesp
  `EspRenderer`, `P3NavRenderer`, Teammates/Thorn ESP, the box methods of `SolverEspRender`/`WorldRenderUtils`.

## Network services and accounts

- The SkyBlockPV backend's `/authenticate` hands ANY caller a guest token (checked 2026-10-07: a made-up username and
  an unjoined server id got a JWT with `sub` all zeros), and its source (meowdding/skyblock-pv-backend) shows that is
  the "authentication disabled" branch. So `ProfileViewerApi.backendToken` no longer gives up when Mojang's
  `joinServer` fails; the backend decides. Hypixel's `fastest_time_s`/`_s_plus` are MILLISECONDS (263003 = 4:23).
- The SkyBlockPV backend rate-limits `/profiles`: 3-4 requests pass, then 429 "Retry-After: 10" (measured 2026-10-07 on 25
  real players: 4 answered, 19 x 429 inside two seconds); by 2026-10-12..15 (his Mod Only Test logs) it was 2-6 per window
  then "Retry-After: 30" every time, and 53 of 126 answers were HTTP 500s that the relay fetched fine later. A 429 is
  `ProfileViewerApi.RateLimitedException`; the Party Finder worker waits out `backendRetryAtMs()` and requeues the name
  (it had cached each 429 as a 3-minute failure, so a full menu showed about four players). Treat a backend 5xx as
  transient (retry in seconds), never as a 3-minute failure. Since 2026-11-01 the relay holds Hypixel's
  approved PERMANENT application key (300 requests per 5 min) and answers /pf/stats from the Hypixel API (checked: three
  fresh names ok in 1.8-2.9 s, source "Hypixel API"); it falls back to SkyBlockPV for an hour only after a 403.
- Hypixel records no S/S+ on Entrance (every real player's Entrance time is `fastest_time` only), so S/S+-only PBs never
  show there. The Party Finder parser also accepts "Floor: Entrance" (floor 0); that lore wording is assumed, not yet
  seen in a real menu.
- `api.docilelm.top` (Devonian's Party Finder stats) answers HTTP 200 `{"result":{}}` to every User-Agent except Devonian's own
  (`Mozilla/5.0 (Devonian)`, checked 2026-10-07), so an empty result is an access refusal, not "player not found". Do not
  impersonate Devonian; Party Finder uses SkyBlockPV (or his own Hypixel key) instead.
- Custom Party Finder: "the auto accept didnt work" (2026-10-18) was the LEADER's side - his Untested Cheat log shows a request
  sitting 47 s with nothing sent, because only a click on Accept answered one; the joiner's invite accept was already there.
  Read the leader's log before blaming the joiner's chat pattern (Hypixel's framed invite line matched INVITE as written).
- **An in-game account swap must fetch the chat key with the NEW account's token.** `ProfileKeyPairManager.create(service,
  user, dir)` keeps `user` only for the cache file name; the key comes from `service.getKeyPair()`, and Minecraft's
  `userApiService` carries the LAUNCH account's token (javap 26.1.2/26.2: `AccountProfileKeyPairManager.fetchProfileKeyPair`).
  `AccountApplier` reused it, so every swapped-to account signed with the launch account's Mojang certificate and the relay
  refused it ("not issued by Mojang for that UUID": his alts, 2026-10-08; Mod Only Test log, launched as Killer560, playing
  as 9878981). It now builds a fresh `YggdrasilAuthenticationService(...).createUserApiService(token)` (testkit 579 replays
  the swap against a fake Mojang and relay). The relay's embedded Mojang keys were identical to the live set.
- **Another mod's party server is for that mod's users, not ours.** NoammAddons' owner asked on 2026-11-01 ("definitely dont
  manually auth into it"): the Cross-Mod Bridge had signed into `api.noamm.org/hypixel/auth` with the player's Mojang session
  and opened `wss://ws.noamm.org` for anyone WITHOUT NoammAddons. Both are gone (`bridge/NoammAdapter`, `NoammCodec`), and
  `util/ModNet.forbidden` refuses that host and endpoint even if code to reach them comes back (testkit 1101-noamm-never). A
  player who runs NoammAddons still gets its socket-fed state in-process through `interop/LocalModBridge`. Devonian's and
  Odin's sockets are still joined: ask before adding any other mod's server, and stop at once if its owner objects.

## Other features

- **A counter fed only by a server line holds its last value through every gap the server says nothing in.** Maxor's
  Crystals' Active counter was set only by "X/2 Energy Crystals are now active!", so after the laser it read 2/2 until
  the next set's first placement (killer560, 2026-11-02), though the pads were empty and the next set was up. The reset
  now comes from the next set's first End Crystal seen off pylon height (y != 224), armed by Maxor's laser line, with a
  44-tick backstop. The F7 sim had the next set respawn 20 ticks after Maxor was enraged; it now comes 34 ticks after the
  laser line (NoammAddons' respawn timer). Testkit 1102-boss-maxorcrys.

- **Spotify's local sources name only the FIRST artist.** The window title and Windows' media session both said "Rosa
  Walton" for a track Spotify credits to "Rosa Walton, Hallie Coggins" (2026-10-20), and lrclib's search returned another
  recording (3:59 against Spotify's 4:06) whose line times were all wrong. Lyrics are matched by title, artist containment
  and Spotify's length within 2 s (`LyricsEngine.pickVersion`, plus lrclib's `/api/get` with the duration); the length and
  position come from `spotify/SpotifyMediaSession`, a PowerShell reader of the System Media Transport Controls, because
  the window title has no clock.
- **The lyric clock only corrected errors over a second, so smaller ones stayed for the whole track** (killer560,
  2026-10-26: "sometimes the highlighted lyric still gets off by a bit"). The stopwatch starts when the title change is
  SEEN (a 250 ms poll), and a resume restarts it the same way, so a few hundred ms of error was normal and `PlaybackClock`'s
  1000 ms jump threshold never fixed it. Now the first reading of a track always sets the clock and any NEW reading (its
  LastUpdatedTime changed) more than 60 ms off corrects it; over 300 ms is logged. Measured on his PC: Spotify refreshes
  LastUpdatedTime about every 4.5 s even while paused (same position), and at once on a seek (130 ms later). Testkit 2840.
- **Word-timed lyrics without an account (2026-10-27): only Kugou works.** Kugou KRC (`lyrics.kugou.com/search` +
  `/download?fmt=krc`, XOR key + zlib) matched 40 of 50 popular songs within 2 s; NetEase gave no `yrc` anonymously (web
  and eapi endpoints both), Musixmatch's anonymous `token.get` returns an all-zero token and then decoy data ("NOKIA",
  gibberish lines), QQ's QRC needs a custom triple-DES decoder (not built). Kugou line starts run a median +166 ms after
  lrclib's for the same lines (38 songs; Kugou stamps the first sung word, lrclib the line early).
- **Hearing the audio does not place words in a full mix** (2026-10-27, 30 iTunes previews against Kugou word times):
  true word starts meet a detected onset within 50 ms 35 % of the time against 32 % by chance (40 % / 38 % with a
  harmonic-only split), onset snapping did not beat the syllable estimate (354 ms median), and Vosk with a lyric-only
  grammar matched 2-4 % of sung words. The algorithm itself works on synthetic sung tones (offset recovered 380 of
  400 ms), so the limit is the mix; it would need vocal source separation. Code and data:
  `killer560s-mod-logs/words-research/`.
- **"Nowhere near the right word" (2026-10-27) was not a clock or word-path regression.** `LyricTiming` is unchanged
  since before the Spotify branches and the HUD's word call is the same expression moved into `SpotifyHud.sungWord`;
  a 60 s simulation (testkit 2849) holds the clock within 6 ms and lights the right word on every frame with exact
  readings, on the deployed code too. The song (High Forever remix) had shown NO lyrics before the version fix, and all
  ten lrclib listings of it have line times only, so its words are an estimate. lrclib word timing is rare (1 of ~380
  listings searched). The clock now takes a median of three readings and eases drift in (no backward word steps under
  noisy readings), and the log says when lyrics are line-only.
- **"Lyrics found are for another version" was a title spelling, not a version** (2026-10-26): Spotify says "High
  Forever - Marlon Hoffstadt Remix", lrclib lists "High Forever (Marlon Hoffstadt Remix)" eight times at 229-230 s, and
  the exact-title test refused all of them. `LyricsEngine.titleKey` normalizes both sides (dash vs brackets, feat.,
  remaster, explicit/clean dropped; sped up, live, remix names kept) and `chooseFrom` scores every listing. Testkit 2847.
- **Spotify Song Tag never showed in F5 for its owner** (2026-10-26): `SongShare.ownTag()` was set only after the relay took
  the packet, and his Mod Only Test has no `killer560smod-modusers.json` (Show Me As A Mod User defaults off, so every
  packet was blocked) and his account is not in the supporter list; nothing said so. A local line must not wait for a
  network send. Also: vanilla never draws the camera entity's name, so a line over your own name needs the name forced on
  (`OwnNameTagF5Mixin` now also asks `SongTagDraw.hasOwnLine`).
- **The Spotify HUD never showed a cover (2026-11-01): PowerShell could not open the thumbnail stream.** The helper's
  `[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($ras)` failed with "Cannot find an overload" on every
  track, because the awaited `IRandomAccessStreamWithContentType` reaches PowerShell as a bare `__ComObject` that its
  binder will not pass as `IInputStream`; the catch around `SaveCover` hid it. Calling the method through reflection
  (`GetMethod('AsStreamForRead', [type[]]@($inputType)).Invoke($null, @($ras))`) casts it. A file-backed
  `RandomAccessStreamReference` reproduces both (killer560s-mod-logs/spotify-1101/cover_test.py: main fails, the fix writes
  64x64). Test a helper's PowerShell outside the game before trusting a silent catch.
- **Song Tag never reached anyone (2026-11-01): nothing connected the relay for it.** `ModChatFeature` turned the relay on
  only for Mod Chat or party data and used the PARTY room otherwise, so with Mod Chat off and no Hypixel party (his test:
  two accounts and a friend standing together) the socket never opened; the testkit fed `RemoteSongs` directly and never
  saw it. Song Tag now keeps the relay up in the LOBBY room (Mod Chat's room when Mod Chat is on, the party's in a dungeon).
  Testkit 2866 checks the room choice and 2867 runs the whole path through a local `wrangler dev` relay.
- **A dragged volume slider lagged seconds behind (2026-11-01, "making the volume quieter takes a long time").** Every 1 %
  step was a `vol N` line, and the helper ran one per reading cycle, so a long drag queued dozens; down sounded slower than
  up because the last steps of a drag down are the audible ones. The helper now takes every waiting command at once and
  runs only the last `vol` (testkit 2868: 51 at once, 1 ran, 50 skipped), and the game sends only the newest value.
- Windows' media sessions are per app, not per site: Chrome publishes one session (`SourceAppUserModelId` "Chrome") with a
  timeline and a thumbnail for whatever tab plays. A music site fills `AlbumTitle`, a plain video does not, which is what
  Music Source "Music Players" keys on (checked on his PC 2026-11-01 with a video tab: excluded in that mode, followed in
  Anything Playing).
- The media-session helper's stdin is the control channel (`SpotifyMediaSession.send`): the script waits on
  `StreamReader.ReadLineAsync` from `OpenStandardInput()`, not `[Console]::In` (its ReadLineAsync is synchronous on .NET
  Framework), and exits when stdin closes. Per-app volume is Core Audio `ISimpleAudioVolume` compiled by `Add-Type` on
  first use (0.4 s). `TryChangePlaybackPositionAsync` takes 100 ns ticks (a zero seek on his Spotify returned True and
  kept the position, 2026-10-26).
- A fixed RMS threshold is not a voice detector: room noise on an open mic sat above Voice To Text's 500, so no pause was
  ever seen and Open Mic never sent anything. `voicetotext/OpenMicSegmenter` compares against the quietest chunk of the last
  3 s; testkit 564 feeds noise at RMS 700 (2026-10-08).
- The Egglocator's particle trail does not reach the egg (SkyHanni's fitter is built on this; not yet checked in our logs): it
  is the start of a cubic Bezier from the player. Its last particle is not the egg; fit the curve and extrapolate
  (`chocolatefactory/egg/TrailFitter`, SkyHanni's ParticlePathBezierFitter).
- Every game sound, a server packet's (`handleSoundEvent` / `handleSoundEntityEvent` -> `ClientLevel.playSound`) and a
  local one, passes `SoundManager.play` or `playDelayed`, where `SoundManagerClickMixin` hands it to `ClickSounds.replace`;
  that is the ONE sound hook (Click Sounds, Sound Effects, and since 2026-11-02 Sound Disabler, checked first). Returning
  null drops it before the engine and touches no packet. Ticking sounds (`queueTickingSound`: minecarts, bees, elytra) do
  not pass there. A Minecraft sound has no owner, so a per-item mute needs context: Sound Disabler's Bonzo's Staff row
  mutes a ghast sound only where a player holding the staff stands (testkit 1102-sound-bonzo).

## Testing and debugging

- The gametest client runs as **java.exe**, not javaw.exe. `run-scenario.ps1` filtered on javaw only, so every
  safeguard in it was inert — the freeze watcher never saw an unresponsive client and the deadline cleanup
  killed nothing, while the script reported success. That is why "it still doesn't close out on freeze"
  survived two rounds of fixes to the watching logic.
- A test script that pipes its runner into `grep` exits with grep's status, so it passes when the runner cannot even
  compile. `tools/bench/regress.sh` did exactly that (two javac errors, exit 0). Write the run to a file, check its exit
  status, and require a line only a finished run prints.
- **Check which mod sent a command before fixing it.** The 2026-10-15 p3sim screenshot of repeated "Unknown or incomplete
  command ... gfs ender_pearl 16<--[HERE]" was not ours: his Auto GFS was off and the log had no "[Cheat Utils] Auto GFS:"
  echo (every send of ours prints one); QUOI and NoammAddons both had Auto GFS on. Ours had the same hole anyway (fixed in
  `util/SackCommands`, testkit 115-hx-autogfs-unknown-command). Grep his log for our echo line first.
- `util/ChatObserver` drops a line identical to one it saw in the last 250 ms (the dedupe for a mod re-adding Hypixel's
  line). A test that sends the same server line twice back to back loses the second: two "The gate has been destroyed!"
  lines 0.2 s apart left P3SectionTracker one section behind (testkit 1027-ap3-section-s3, 2026-10-27). Space them.

- **Your own dungeon class is `leapmenu/PartyTracker.selfClass()`** (tab list, plus Party Finder lines, with the
  `ClassOverrides` pick on your own IGN already applied by `classOf`). Fast Leap's Class Targets (2026-10-27) read it;
  `Ap3Feature.selfClass()` wraps it in `ClassOverrides` a second time, which is harmless. Do not write another tab parser.
- **Simon Says: a grid press on a light the solver did not hold must still count as a click of the attempt.** Before
  2026-11-01 `onButtonPressed` returned early for it, so `totalClicksThisAttempt` stayed 0, the between-round grid reset took
  the attempt for a fresh one and re-armed `firstPhase`, and the next round got round 1's reverse/drop (his "highlights the
  wrong things after I crouch to override"; traced in the code, not reproduced on the old jar - 1101-ss-override-rescan passes
  with the fix). Count it, and end `firstPhase`.
- A mouse button bound to vanilla's Drop key does nothing in a container screen: `AbstractContainerScreen.keyPressed`
  reads `keyDrop`, `mouseClicked` never does (javap 26.3 and 26.2). The Terminal Drop Key handles a mouse bind itself
  (`TerminalQolFeature.handleMouseDrop`: the same THROW click on the hovered slot, or the Custom GUI's own click).
- Another mod's auto sprint ends in `LivingEntity.setSprinting(true)` whichever way it is built (Odin answers
  `Input.sprint()` in `LocalPlayer.aiStep` with `@ModifyExpressionValue`; others call setSprinting each tick); holding
  the sprint KEY is already undone by our movers replacing the key record. `AutoSprintOverrideMixin` refuses a sprint
  start there while one of the mod's movers drives with sprint up (doc-terms-1101, testkit 1101-menu-autosprint).

- **Owner-evidence log lines (diag-1101, 2026-11-02).** Where a bug's cause needs a Hypixel run, the mod writes one rate-limited
  INFO line his log will carry (DEBUG never reaches it): `[Predict] tile x,z ruled out <room@rot@ax,az> ...: [seen/expected
  blocks]` (once per tile a floor, up to 3 rooms), `[SecretWaypoints] room change A (name) -> B (name) ... N outside` (at most one
  a second) and `still drawing N waypoint(s) outside room ...` (once, within 5 s of a room change), `[CryptScan] gold block, ...`
  (each gold block with its crown rule and the nearest lid's verdict, once per verdict), `[FastLeap] stuck for N ms: [reasons]
  | ...` (once a spell after 1 s, then `stuck state ended`), `[Raffle] Raffle start (...)` plus one line per nearby entity (once a
  Raffle, Raffle highlight on). Grep his log for these before guessing. The sim's Flags proved `CryptScanner.goldBeside`: a gold
  block one out and one down from the raised-row lid makes that lid the prince (1102-diag-wp-gold).
- A feature that shares "whatever the other settings say" needs that exception at EVERY gate on its path, not just where it
  is queued: PartyDataFeature queued Team Melody with Party Dungeon Data off, and drainQueue then cleared the whole queue
  on `!publishActive()`, so nothing was ever sent (found by testkit 1102-boss-melody-relay-roundtrip with a fake relay,
  tkdebt-1101; fixed 9c71162c).
- "The waypoint is still there after I took it" may be another feature's box on the same spot. On 2026-11-02 his log showed
  the Wither Essence waypoint leave on the click tick (`waypoints changed ... wither=0`, before the chat line); the gold
  box left behind was Secret Clicked's 7-second box (on in his config). Read the log's waypoint counts and his config for
  every feature that draws there before touching detection. The gold box is now behind Box On Every Secret (off).
