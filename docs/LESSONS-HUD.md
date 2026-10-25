# HUD lessons

HUD elements, their size, position and scale, the HUD editor, the dungeon map painter, bars and readouts, and HUD cost.
Problem, then fix; verified only. Part of the GUI lessons: [LESSONS-GUI.md](LESSONS-GUI.md) (drawing cost, text, world
rendering); the mod menu is [LESSONS-MENU.md](LESSONS-MENU.md).

## Where a HUD element draws

- **A `HudElement`'s `render()` is not always where it draws.** For `croesus_start_button`, `experiments_start_button`,
  `rng_meter_ranking`, `storage_overlay`, `inventory_hud` and `custom_scoreboard`, `render()` is ONLY the HUD editor's
  preview; the real pixels come from a container-screen or Fabric HUD layer elsewhere in the feature, and
  `etherwarp_waypoints` never draws (`isVisible()` is hardcoded false). Anything that needs "was this on screen" goes at each
  feature's own draw site: that is `hud/HudSeen`, and why `isRelevantNow` was split into `isEnabledInSettings()` (the
  toggle) plus the draw stamp - the single predicate had drifted from the render path in four places (Split Timers' tested
  `isInDungeon()` and its `render` did not).
- **One gate hides every HUD layer: wrap them as they are registered.** Fabric's `HudElementRegistryImpl.addFirst/addLast/
  attachElementBefore/attachElementAfter` each take exactly one `HudElement` (javap, fabric-rendering-v1 23.3.1 and 25.3.3,
  identical), so `bazaar/mixin/HudLayerGateMixin` (`@ModifyVariable` at HEAD, `remap = false`) wraps every layer, this mod's
  and any other mod's, in a check of `BazaarHud.hidesHud()`; no draw site changed. Testkit 485 adds two probe layers (one
  last, one after MISC_OVERLAYS): ~120 draws in 20 ticks with no screen, 0 with the Bazaar open and the gate on, ~120 with
  it off, over the menu-less screen and over a reskinned chest.
- Layer order and the in-game screen dim are in [LESSONS-GUI.md](LESSONS-GUI.md) ("Fabric's `addLast` HUD layers draw ABOVE
  an open in-game screen's dim").

## Size, position and scale

- **HUD scale has one source:** `HudElementRegistry.resolveScale` = the element's own `HudConfig` scale times the global HUD
  Scale (Home tab, `globalScale` in `killer560smod-hud.json`) times Auto Scale (`hud/AutoScale`, via
  `HudConfig.getEffectiveGlobalScale`). The HUD editor scrolls and saves the OWN scale and draws at the product. A draw site
  that skips `resolveScale` silently opts out of every scale (three Gui mixins - Ability Timers, Dungeon Info, Etherwarp
  Waypoints - did until 2026-10-04). SAVED positions are baseline units drawn at `saved * factor`, so a draw site that reads
  `HudConfig.getPosition` directly instead of `resolvePosition`, or saves a drag without `HudElementRegistry.toSaved`, lands
  in the wrong place on any monitor but 2560x1440 / GUI 3.
- **Never write a SCREEN position into HudConfig.** The Custom Scoreboard's SkyHanni snap stored its screen x there, read
  back `x * factor` on the next frame, took the mismatch for an Edit HUD drag and switched the snap off for good, so the
  board sat at `x * 0.8` (228 px in from the right edge on 1366x768; testkit 1661/1667). An element that must hug an edge
  keeps its own anchor and serves it from `layoutPosition()` (`HudElement.keepOnScreen` / `storeEditorPosition` also make
  Edit HUD clamp and hand the drop back to it).
- **A HUD element's `width()` must be in the registry's unit, and the HUD editor saves on a zero-pixel click.**
  `HudElementRegistry` defines on-screen size as `width() * HudConfig` scale, but the Storage Overlay's `defaultX()` centered
  against `width() * its own slider scale` and its render pose used a third combination, so the clamp, the editor box and the
  panel measured three different panels. A clamp cannot rescue a panel *wider* than the screen (it only picks which columns
  to hide), so `gridWidthLocal()` drops columns until the grid fits. `HudEditorScreen.mouseReleased` persists a position for
  any press-release on a box, drag or not, and a saved position is never re-clamped: one click while the window was briefly
  854x480 froze `storage_overlay` at `x:0` at 2560x1441, and deleting the entry lasted only until the next stray click. The
  Storage Overlay is therefore unclampable by a saved x: its x is not a saved value at all - `layoutPosition()` returns the
  center every frame and only the saved y is honored (testkit 453, six window/GUI sizes with a saved x 0).
- **`width()`/`height()` must measure the SAME lines `render()` draws** (one `lines()`/`layout()` method both read, sizes
  via `hud/HudText`): fixed guesses left 20 of 50 boxes more than 3 units off (Split Timers +70, Autopilot -76); testkit
  390-ui-hud-boxes checks every element. Centered elements (Room Alerts, blood camp popup) keep a fixed box, since their
  center is the anchor and a hugging box would move the text.
- **A right-anchored HUD box drawn from a rounded x drifts.** The Custom Scoreboard's right wall was `round(x) + width *
  scale`, so at a non-integer drawn scale (Auto Scale makes nearly every real one so) it moved up to a pixel whenever the
  width changed, and a Min Width box's text right end moved by the slack (testkit 1668). Draw it shifted by
  `round(w * scale) - w * scale` (`anchorShift`) with the text block against the wall (`blockOffset`); the hover layout uses
  the same shift and block.
- **A layout that places HUD elements must not write their saved positions.** Health and Mana Bars' Predefined layout
  (`playerstats/StatLayout`) answers `HudElement.layoutPosition()`, which `resolvePosition` returns before any saved
  position, and the HUD editor skips `setPosition` for those elements; switching back to Custom draws every bar on its old
  pixels (testkit 407). The editor has no buttons (they took clicks beside the hotbar; its options are in Home's HUD Editor
  section) and the bars have their own editor, `HudEditorScreen.forBars`.
- `Map.getOrDefault` EVALUATES its default eagerly: `HudConfig.getPosition` allocated a throwaway `int[2]` on every call
  even with a saved value, about 110 allocations a frame across the HUD.

## HUD editor

- Mouse cursors: `GuiGraphicsExtractor.requestCursor(CursorType)` during `extractRenderState` sets the window cursor for that
  frame (javap both versions). `CursorTypes` has only the straight resize arrows; the diagonals come from
  `CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, name, fallback)` (LWJGL 3.4.1 / GLFW 3.4), created lazily
  on the render thread. Testkit 399 reads `pendingCursor` and the window's `currentCursor`.
- Opening a screen over a container is safe to come back from: `AbstractContainerScreen.removed()` only runs
  `menu.removed(player)`, which drops a cursor item client-side and sends nothing (javap 26.1.2), so the server's menu stays
  open and `setScreen(container)` restores it. Edit HUD does this and refuses with an item on the cursor.
- A pinned `FolderTab` section (always open, no header) is never in `expanded`, so `findListeningKeyCaptureTab` did not ask
  it and Home's "Edit HUD Keybind" sat on "Press any key..." forever. Pinned sections are checked first. (The Edit HUD key
  opening the editor while being bound is the typing lesson in [LESSONS-GUI.md](LESSONS-GUI.md).)

## Dungeon map painter

- A marker outline drawn as one-unit side strips per row is not an outline: the back edge had none and the tip rows were
  all edge, so only 69% of the old map arrow's edge was dark and the green arrow vanished on the green Entrance (testkit 395).
  Draw a solid outline shape one unit bigger UNDER the fill (`MapPainter.outlinedTriangle`): 100%, 15.3:1.
- **A setting that only touches a state the painter never draws does nothing.** Darken Unexplored multiplied only rooms in
  `STATE_UNDISCOVERED`, exactly the rooms `LiveMapHudElement.renderMap` skips, and on the cheat jar every world-scanned room
  was promoted to DISCOVERED first ("Darken Unexplored does not work"). `MapPainter.unexplored` decides from the map item's
  own tile states; testkit 1015-sim-map-item-only reads the drawn fill at Darken 0 and 0.6.
- **A blood door has a room on both sides.** `MapPainter.unopenedBloodRoom` took any unopened room touching it, so the room
  it is reached from was grayed as the blood room too. The Blood Room is the dead end: the blood door must be its only door.
  Funny Map painted a not-yet-scanned unopened room in the regular map's gray; it is its own color darkened now (testkit
  1018-map-unopened-funny/-blood).

## Bars and readouts

- `RenderSystem.setShaderColor` does not exist in 26.1.2, so there is no global color multiplier and item textures cannot
  be tinted. A translucent black quad over the Inventory HUD can only darken (every Opacity step below 100% put a black box
  over it, 2026-10-18), and a darker one is no replacement: Opacity fades the panel/outline/slots only, 0 still hides the
  panel, and the items are never dimmed.
- **A Unifont glyph's pixels are half a text unit.** The ♨ Vitality icon outlined like the bar number (one whole unit) was a
  black blob; it is outlined by one glyph pixel (half a unit) and scaled so a glyph pixel is whole screen pixels
  (`BarPainter.drawGlyph`).
- The action bar's stat segments are stripped by `CustomScoreboardGuiMixin` via `PlayerStatsFeature.actionBarReplacement`,
  which re-sends the rest under a re-entry guard, never by blanking the action bar there.
- **Hypixel's sidebar owner is one INVALID format code** ("§i", "§p", "§s"; prefix = the first 16 characters, then owner,
  then suffix), and `stripFormatting` only removes valid codes, so a "drop the owner when it strips blank" test kept it inside
  every number crossing character 16 ("Copper: §c114,25§i0" read 11,425). Judge visibility as the renderer does: '§' plus ANY
  next character is invisible (`ScoreboardData.hasVisibleText`; testkit 1311's Hypixel-shape block).
- **The player's own tab-list latency is not a ping on Hypixel.** `PlayerInfo.getLatency()` always said 1 ms.
  `util/PingTracker` measures a round trip with vanilla's ping request / pong pair (the F3 ping chart's packets;
  `ClientPacketListener.handlePongResponse` runs on the Netty thread, javap both versions), only while something shows it
  (testkit 724 follows an injected 200 ms). Blood Camp, Auto Routes, AP3, Terminal Protection and Auto Debuff had compensated
  with that ~1 ms; they read `PingTracker.automationMs()` (median of the last 5 pongs, tab value until the first) and keep it
  requesting while on (testkit 791-796).

## HUD cost

- **Most of a text HUD's per-frame allocation is vanilla's glyphs, not the mod's strings** (PerfProbe, testkit 95-perf-hud):
  Score Calculator 13,375 B with its lines rebuilt every frame, 11,712 B cached; Advanced Position 7,607 vs 7,476. Caching
  saves the String.format/concatenation time, not the ~70 B per drawn character `GuiGraphicsExtractor.text` costs.
  `Font.width` of a string with `§` codes builds a `Style` per code: Advanced Position's width() was 1,600 B a call until
  memoized on the line array.
- **`HudElementRegistry.drawAt` resolves the position, scale and pose BEFORE the element's render finds its feature off**,
  about 2 us per element per frame; Health and Mana Bars' Predefined layout was built every frame with the feature off (20 us,
  2 KB). `HudInGameRenderer` skips an element whose `isEnabledInSettings()` is false (its sixteen renders gate on that same
  setting - check a new element's before adding it to that list) and draws no stat readout while the feature is off
  (95-perf-features). Other `drawAt` callers still pay it. The Auto Clear and Autopilot status lines are one `AutoStatusHud`,
  which builds its text on the client tick only when it changed and returns before `drawAt` when there is nothing to show.
- **A HUD element's render-state elements can be replayed on a later frame** (`livemap/MapDrawCache`): hand the same
  `ColoredRectangleRenderState`/`GuiTextRenderState` objects to `GuiRenderState` again, through the same `add*` calls in the
  same order, and they layer exactly as a fresh draw (a text state keeps its glyph layout, so drop the cache on
  `FontSet.reload`). Record only the OUTERMOST call: recording every hooked call put each element in twice (95-perf-livemap
  verify: "count 30 vs fresh 15", 2406 stale frames); counting depth fixed it (0 of 2817). The key must be every input
  compared exactly; the verify mode (paint afresh unsubmitted, compare) finds a missed one, and the paint count found
  Predictive Rooms bumping its version every tick (the map repainted every tick under ALL ON).
- **A HUD fed by a FeatureBits-gated tick keeps its last lines after the switch goes off.** The tick stops with the switch, so
  nothing clears the cached lines; Boss Health showed "Bonzo 250k" with the feature off (testkit 1025-bosshp-f1-gate). Gate the
  READ on the switch (`currentLines()` returns nothing while it is off), not only the refresh.
