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

Service keys: `hypixel`, `noamm`, `noamm-ws`, `odin-ws`, `devonian-ws`, `docilelm`, `pv-backend`, `mojang`,
`minecraftservices`, `microsoft`, `xboxlive`, `github-raw`, `jsdelivr`, `coflnet`, `github-api`, `relay`,
`lrclib`, `google-translate`, `mymemory`, `vosk`, `maven-central`. `ModNet`'s javadoc says which host and feature each one is.

Not routed: the Shorts player's DevTools client (127.0.0.1, a browser the mod launched), Discord RPC (a local
pipe), the Proxy Client (the game connection itself), and anything Minecraft or authlib does on its own
(skins, the Devonian bridge's `joinServer` session check).

## OS opens (`util/ExternalOpen`)

- `-Dkiller560.test.noExternalOpen=true` makes every "open folder", "open link" and browser launch log
  `[TestHook] would open <target>` (WARN) instead of opening anything. Covers the bug report folder, the
  folder buttons in the AP3, Auto Routes, Breaker Aura, Cringe, Custom Scoreboard, GIF Player and Inventory
  Sorter tabs, the update and Discord buttons on the home tab, and the Shorts player's browser and sign-in
  windows (both of which then do nothing further).

## Chat listener failures (`util/ChatObserver`)

No property. `ChatObserver.failures()` counts every listener or rewriter throw it has caught since start-up,
`lastFailure()` names the most recent (`<listener class>: <exception>`), and `failuresByListener()` gives the
count per listener. A harness asserts `failures()` did not move across a scenario.

## The sim's tab list (`roomsim/SimTabList`)

No property. The dungeon sim's integrated server publishes a Hypixel-shaped tab list (80 fake player-info
entries plus a header/footer packet), so tab-list readers run the same code in the sim as on Hypixel. The
lines and the parser each one feeds are listed in `SimTabList`'s javadoc.
