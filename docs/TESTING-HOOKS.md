# Test hooks

JVM system properties an automated harness can set to test the mod. Every one is inert when absent: with none
set, the mod behaves exactly as it did before the hooks existed.

## Network (`util/ModNet`)

Every HTTP request, URL download and WebSocket the mod opens resolves its address through `ModNet.url(service,
default)` and goes out through `ModNet.send` / `sendAsync` / `open` / `webSocket`.

- `-Dkiller560.net.<service>=http://127.0.0.1:PORT` points one service at a fake. The scheme, host and port of
  the default URL are replaced and its path and query kept; a path on the override is prepended. A `ws`/`wss`
  default given an `http` override becomes `ws` (and the reverse). Read once, when each feature's URL constants
  initialise, so set it on the command line.
- `-Dkiller560.net.offline=true` refuses every call at once with a `java.net.ConnectException`, the same error a
  dead network gives, so each caller's own failure path runs. Hosts named by a `killer560.net.<service>`
  override are still reachable, so a harness can run offline with only its fakes. Read on every call.

Service keys: `hypixel`, `noamm`, `noamm-ws`, `odin-ws`, `devonian-ws`, `pv-backend`, `mojang`,
`minecraftservices`, `microsoft`, `xboxlive`, `github-raw`, `jsdelivr`, `coflnet`, `github-api`, `relay`,
`pf-relay`, `lrclib`, `google-translate`, `mymemory`, `vosk`, `maven-central`, `github-download`. `ModNet`'s javadoc says which host and
feature each one is. `pf-relay` is the relay's `/pf/stats` alone (Party Finder stats) and, unlike the others, is read
on every request, so a case may set it at run time; the testkit points it at its HTTP fake (`/pfrelay`).
Auto Update (`autoupdate/AutoUpdater`) also reads `github-api` and `github-download` on every check, so a case can point
both at an in-process fake at run time. `github-download` is also the only download prefix it accepts
(`<override>/Killer560-OG/killer560s-mod/releases/download/`).

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

## Interactive Map warp count (`livemap/autoclear/EtherwarpPathfinder`)

- `-Dkiller560.test.checkFewest=true` makes every tile click plan itself a second time without the centre
  preference and append `[check] without the centre preference: N warp(s), landing D block(s) from the tile centre`
  to its `[Path] ... warp(s) (tile)` line, so a harness can check that landing nearer the centre never costs a warp
  (testkit 95-sim-map-warp). Costs one more search per click; never set outside the testkit.

## Name Animation clock (`namechanger/NameAnimation`)

No property. `NameAnimation.freezeClockForTest(epochMs)` fixes the clock Wave/Pulse names are coloured from (-1 goes
back to the real one), so two screenshots are exact (testkit 960-965); `coloursNow(sequence)` returns the colours the
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
