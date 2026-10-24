# Test hooks

JVM system properties an automated harness can set to test the mod. Every one is inert when absent: with none
set, the mod behaves exactly as it did before the hooks existed.

## Network (`util/ModNet`)

Every HTTP request, URL download and WebSocket the mod opens resolves its address through `ModNet.url(service,
default)` and goes out through `ModNet.send` / `sendAsync` / `open` / `webSocket`.

- `-Dkiller560.net.<service>=http://127.0.0.1:PORT` points one service at a fake. The scheme, host and port of
  the default URL are replaced and its path and query kept; a path on the override is prepended. A `ws`/`wss`
  default given an `http` override becomes `ws` (and the reverse). Read once, when each feature's URL constants
  initialize, so set it on the command line.
- `-Dkiller560.net.offline=true` refuses every call at once with a `java.net.ConnectException`, the same error a
  dead network gives, so each caller's own failure path runs. Hosts named by a `killer560.net.<service>`
  override are still reachable, so a harness can run offline with only its fakes. Read on every call.

Service keys: `hypixel`, `noamm`, `noamm-ws`, `odin-ws`, `devonian-ws`, `pv-backend`, `mojang`,
`minecraftservices`, `microsoft`, `xboxlive`, `github-raw`, `jsdelivr`, `coflnet`, `hysky`, `github-api`, `relay`,
`pf-relay`, `lfg-relay`, `lrclib`, `google-translate`, `mymemory`, `vosk`, `maven-central`, `github-download`. `ModNet`'s javadoc says which host and
feature each one is. `pf-relay` is the relay's `/pf/stats` alone (Party Finder stats) and, unlike the others, is read
on every request, so a case may set it at run time; the testkit points it at its HTTP fake (`/pfrelay`).
Auto Update (`autoupdate/AutoUpdater`) also reads `github-api` and `github-download` on every check, so a case can point
both at an in-process fake at run time. `github-download` is also the only download prefix it accepts
(`<override>/Killer560-OG/killer560s-mod/releases/download/`). `-Dkiller560.test.autoUpdateStallMillis=N` shortens the
download's stall limit (default 60000: it gives up only after that long with no byte arriving); read on every download.

## Custom Party Finder (`lfg/`)

No property beyond the `lfg-relay` service key (the relay's `/lfg/*` alone, read on every call). `LfgRelay.calls()` lists the
last 50 relay calls attempted ("METHOD /path {body}", recorded before the network, so offline reads the same). Relay answers
are fed with `LfgFeature.onMeForTest(json)`, `onListingsForTest(category, json)`, `onJoinReplyForTest(listingId, json)` and
`onUpsertForTest(json)`; `events()` says what the feature did, `wantedForTest()` is the listing it keeps up, `resetForTest()`
clears it all. `LfgCommandGate.sent()` / `refused()` are every party command let through or refused, `resetForTest()` clears
the limits (testkit 1018-pf-*).

## Price Tooltip (`pricetooltip/`)

No property beyond the `hypixel` and `hysky` service overrides above, which it reads at every fetch. `PriceData.
refreshNow(includeItems)` fetches on the calling thread (never the render thread); `clearForTest()` empties every map;
`markStartedForTest()` keeps the background loop and disk cache out of a case. `MuseumDonations.setDonated(ids)` stands
in for your museum; `PriceTooltipFeature.setShiftForTest(Boolean)` fakes Shift; `obtainedDate(tag)` is the date reader.

## Auto Update (`autoupdate/`)

No property is needed to keep it away from a harness: it never checks unless the mod is a jar directly inside the game
folder's `mods/` (the testkit loads it through `fabric.addMods`). For cases: `AutoUpdater.checkForTest(channel, testing,
gameDir, mc, variant, commit, stagingDir)` runs one check with explicit inputs (gates included) and returns the outcome
as text; `AutoUpdateFeature.spawnHelper(parentPid, oldJar, newJar, stagingDir)` starts the real installer process
against any parent PID; `AutoUpdateFeature.launchGate()` says why this game would not update.

Not routed: the Shorts player's DevTools client (127.0.0.1, a browser the mod launched), Discord RPC (a local
pipe), the Proxy Client (the game connection itself), and anything Minecraft or authlib does on its own
(skins, the Devonian bridge's `joinServer` session check).

## OS opens (`util/ExternalOpen`)

- `-Dkiller560.test.noExternalOpen=true` makes every "open folder", "open link" and browser launch log
  `[TestHook] would open <target>` (WARN) instead of opening anything. Covers the bug report folder, the
  folder buttons in the AP3, Auto Routes, Breaker Aura, Cringe, Custom Scoreboard, GIF Player and Inventory
  Sorter tabs, the update and Discord buttons on the home tab, and the Shorts player's browser and sign-in
  windows (both of which then do nothing further).

## Accounts and the Universal proxy (`accounts/core/PrismAccountStore`, `proxy/config/ProxyConfig`)

- `-Dprismaccountswitcher.accountsFile=<path>` reads that file instead of Prism's real `accounts.json` (refresh tokens).
- `-Dprismaccountswitcher.universalProxyFile=<path>` reads and writes the Universal proxy there instead of Prism's shared
  `prismaccountswitcher/universal-proxy.json`, which every instance reads; a case that presses Swap Accounts' Universal
  switch needs it (testkit 1851 refuses to press it without). Read once, when `ProxyConfig` loads. Without it a test
  client also CONNECTS through whatever universal proxy is on for real play: on 2026-10-15 that failed every testkit join
  to localhost with a ProxyConnectException, until the testkit set it for every run.

## Interactive Map warp count (`livemap/autoclear/EtherwarpPathfinder`)

- `-Dkiller560.test.checkFewest=true` makes every tile click plan itself a second time without the center
  preference and append `[check] without the center preference: N warp(s), landing D block(s) from the tile center`
  to its `[Path] ... warp(s) (tile)` line, so a harness can check that landing nearer the center never costs a warp
  (testkit 95-sim-map-warp). Costs one more search per click; never set outside the testkit.

## Name Animation clock (`namechanger/NameAnimation`)

No property. `NameAnimation.freezeClockForTest(epochMs)` fixes the clock Wave/Pulse names are colored from (-1 goes
back to the real one), so two screenshots are exact (testkit 960-965); `coloursNow(sequence)` returns the colors the
Font hook paints for a text at that time. Wave at 1x moves one letter per 250 ms; Pulse at 1x is darkest at 1000 ms.

## Chat listener failures (`util/ChatObserver`)

No property. `ChatObserver.failures()` counts every listener or rewriter throw it has caught since start-up,
`lastFailure()` names the most recent (`<listener class>: <exception>`), and `failuresByListener()` gives the
count per listener. A harness asserts `failures()` did not move across a scenario.

## The sim's tab list (`roomsim/SimTabList`)

No property. The dungeon sim's integrated server publishes a Hypixel-shaped tab list (80 fake player-info
entries plus a header/footer packet), so tab-list readers run the same code in the sim as on Hypixel. The
lines and the parser each one feeds are listed in `SimTabList`'s javadoc.

## Shared trails (`trail/RemoteTrails`, `supporters/SupportersAutoShare`)

No property. `SupportersSelfService.lastAttempt()` is "POST <body>" or "DELETE" for the last share push attempted (set
before the network call, so it reads the same offline) and `attempts()` counts them; `SupportersAutoShare.setLinked`
stands in for the relay's linked-supporter answer. The startup check (`tickStartupCheck`, first try 10 s in, retries
30 s doubling to 15 min) reports `startupCheckState()`, and `onStartupMe(MeResult)` feeds it a made-up answer. `RemoteTrails.tracked()`, `lastDrawnTrails()`, `framesDrawn()`,
`ribbonState(uuid)` and `squareCount(uuid)` say what other players' trails were kept and drawn (testkit 1036-1039).

## Slot Text and Compactor Preview (`slottext/SlotTextFeature`, `compactorpreview/CompactorPreviewFeature`)

No property. Both show only while their key is held in the key modes, and a harness key press never reaches the GLFW poll, so `keyHeldForTest` (static `Boolean`, null in play) on each feature stands in for the key. `SlotTextFeature.textFor(stack, menuTitle, slotIndex, playerSlot)` returns the text a slot would get under the live per-type switches; `CompactorPreviewFeature.contentsOf(stack)` and `augment(components, stack)` read a compactor's slots and add the grid (testkit 960-967).

## Menu animation clock (`gui/MenuAnim`)

- `-Dkiller560.test.menuAnimClock=instant` makes every mod menu animation instant (as Animations: Off) while no case
  drives the clock. The gametest lockstep renders one frame per tick as fast as it can, and many cases read the very
  frame after a click; on the wall clock they would read a fade half done. The testkit sets it for every run.
- `MenuAnim.setClockForTest(ms)` fixes the clock (a negative value goes back to the real one), so a case can step it
  1/30 s or 1/300 s per frame and compare (testkit 1590-1596). `ModScreen.animState()` / `cardAnimState(tab)` read the
  animations; `ModScreen.allocProbe` adds up the bytes the render thread allocates inside each menu frame
  (`allocBytes` / `allocFrames`).
## Callback profiler (`perfprobe/PerfProbe`)
`-Dkiller560.perf.probe=true` on a dev jar (not `-Prelease=true`) wraps every listener a `com.killer560.hub` class
registers on a Fabric event, and every Fabric HUD layer it adds, in a timing proxy, and times each mod HUD element in
`HudElementRegistry.drawAt`, and the open screen's whole draw (`Screen.extractRenderStateWithTooltipAndSubtitles`, key
`screen.<SimpleName> extract`, vanilla's work and every mod mixin inside it): self time and render-thread allocation per
key (`<owner class> <event>` or `<owner> HUD[<id>]`). Read by reflection: `PerfProbe.snapshot()` (TSV: key, kind tick/frame/event/server, calls,
self ns, bytes, max ns), `reset()`, `calibrate(n)` (the probe's own per-call cost), `keyCount()`. Without the property
the probe mixins are not applied at all (`PerfProbeMixinPlugin`), so the release jar and normal runs pay nothing.
The testkit sets it for any `-Pscenario` naming `95-perf` (testkit docs/fps-bench.md).

## Feature bits (`util/FeatureBits`)

- `FeatureBits.snapshot()` returns `name=true|false` per gated listener after a refresh; `count()` how many are
  registered. Testkit 95-perf-bits reads them (and the `ON`/`names` fields directly, to see a bit without refreshing).

## Title Screen Switcher (`mainmenu/TitleScreenLooks`)

The switcher exists only in the Prism instance "Title Screen GUI" (killer560, 2026-10-16: it "will never be loaded into a
server - only for testing the main menus"). It is on when the game folder holds the empty marker file
`killer560smod-title-screen-gui` (`TitleScreenLooks.MARKER_FILE`, like Auto Update's `killer560smod-no-auto-update`), or when
`-Dkiller560smod.titleScreenSwitcher=true` is set; both are read once, at the first title screen. With neither, nothing
changes. `TitleScreenLooks.setEnabledForTest(boolean)` turns it on or off for the session (off also drops the picked look);
`looks()`, `currentIndex()`, `apply(look)`, `step(dir)` and `cornerButton()` are what testkit 1574 drives. A pick is a session
override read by `MainMenuLook.current()`, `MainMenuTheme.active()` and `MenuStyle.use()`; it writes no setting.
`MainMenuScenes.setAmberScene` / `setGreenScene` and `GreenTheme.setGradient` are the runtime choices behind it (the
`AMBER_SCENE` / `GREEN_SCENE` / `DEFAULT_GRADIENT` constants stay the defaults).

## World render batch (`compat/McRender`, 26.1.2)
Inside `AFTER_TRANSLUCENT_FEATURES` and `AFTER_TRANSLUCENT_TERRAIN`, `McRender.inCameraSpace` writes into the mod's own
buffer source and draws each render type once after the event's last listener (see `McRender.registerBatching`).
`McRender.batchStats()` returns `{geometry callbacks batched, batches drawn}` since launch (both 0 on 26.2, whose submit
collector groups by type itself); the probe attributes the batch's draw to `compat.McRender <event>`, not to the
features that filled it. Testkit 95-perf-world reads both, plus its own draw-call counter.

## Dungeon Map draw cache (`livemap/MapDrawCache`)
The Dungeon Map HUD keeps the render-state elements it painted and hands them over again while nothing it is painted
from changed. `-Dkiller560.livemap.verifyCache=true` (or `MapDrawCache.setVerify(true)`) also paints every replayed
frame afresh, unsubmitted, and compares the two element by element; `MapDrawCache.stats()` is {replays, paints,
compared, differed within the paint's own tick, differed after it}, `lastMismatch()` names the last stale element.
Testkit 95-perf-livemap runs it.
