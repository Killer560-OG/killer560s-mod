# GUI, HUD and rendering lessons

Split out of [LESSONS.md](LESSONS.md) on 2026-10-07 to keep it under its size limit. Problem, then fix; verified only.

- `RenderSystem.setShaderColor` does not exist in 26.1.2, so there is no global colour multiplier and items
  cannot be tinted per-item. The inventory HUD's Opacity now dims items with a translucent quad drawn over the
  panel after the item loop instead: 0 hides the panel outright, and the darkening is capped at 80% so no
  setting turns it into an unreadable black box.

- **A `HudElement`'s `render()` is not always where it draws.** For `croesus_start_button`,
  `experiments_start_button`, `rng_meter_ranking`, `storage_overlay`, `inventory_hud` and `custom_scoreboard`,
  `render()` is ONLY the HUD editor's preview and the real pixels come from a container-screen or Fabric HUD
  layer elsewhere in the feature; `etherwarp_waypoints` never draws at all (`isVisible()` is hardcoded false).
  So anything that needs to know "was this on screen" must be placed at each feature's own draw site, not on
  the interface method. That is what `hud/HudSeen` does, and why `isRelevantNow` was split into
  `isEnabledInSettings()` (the toggle) plus the draw stamp on 2026-09-30 - the old single predicate had
  already drifted from the render path it mirrored in four places (Split Timers' tested `isInDungeon()` and
  its `render` did not).

- `Map.getOrDefault` EVALUATES its default eagerly. `HudConfig.getPosition` allocated a throwaway `int[2]` on
  every call even when a saved value existed - about 110 allocations a frame across the HUD.

- **A HUD element's `width()` must be in the registry's unit, and the HUD editor saves on a zero-pixel
  click.** `HudElementRegistry` defines an element's on-screen size as `width() * HudConfig` scale, but the
  Storage Overlay's `defaultX()` centred against `width() * its own slider scale` and its render pose used a
  third combination, so the clamp, the editor box and the drawn panel measured three different panels
  (2026-09-30). A clamp also cannot rescue a panel *wider* than the screen - it only picks which columns to
  hide - so `gridWidthLocal()` now drops columns until the grid fits. Separately, `HudEditorScreen.
  mouseReleased` persists a position for any press-release on a box, drag or not: one click in the editor
  while the window was briefly 854x480 froze `storage_overlay` at the clamped `x:0` and it stayed there at
  2560x1441, which is what "the storage overlay is no longer centered" turned out to be. A saved position is
  never re-clamped, so the cure is deleting the element's entry from `killer560smod-hud.json`. And `width()`/
  `height()` must measure the SAME lines `render()` draws (one `lines()`/`layout()` method both read, sizes via
  `hud/HudText`): fixed guesses left 20 of 50 boxes more than 3 units off (Split Timers +70, Autopilot -76) until
  2026-10-07; testkit 390-ui-hud-boxes checks every element. Centred elements (Room Alerts, blood camp popup) keep
  a fixed box, since their centre is the anchor and a hugging box would move the text.

- The mod's own widget labels are not drawn through `GuiGraphicsExtractor.text`; they go through
  `GuiGraphicsExtractor$RenderingTextCollector.accept`. A text hook that only targets `text` misses every
  button and label in the settings GUI (found when keeping Name Changer out of the mod's own menus, 2026-10-04).

- **A settings preview that shares only the formatter still drifts if the real path gates on data the preview
  always has.** Party Finder's preview and tooltip both called `memberLine`, but the tooltip skipped it until the stats
  service answered, and on 2026-10-07 api.docilelm.top/v2/dungeons returned `{"result":{}}` for every name (real
  dungeon players, checked with curl), so Hypixel's menu was never styled while the preview always was. Both now go
  through `PartyFinderOverlay.styleLines` (missing stats render as `?`); testkit 236-menu-partyfinder-style asserts it.
  The stats now come from `ProfileViewerApi` (SkyBlockPV backend, Hypixel's raw profiles) and docilelm is no longer
  called; testkit 400-menu-partyfinder-stats serves real trimmed backend answers through a loopback fake.

- **Anything drawn from inside `AbstractContainerScreen.extractContents` is already translated by leftPos/topPos**
  (labels, `extractSlotHighlightBack/Front`, `extractSlots`/`extractSlot`; javap, 26.1.2 and 26.2), so it draws at
  plain `slot.x, slot.y`. `extractBackground` and the `extractRenderState` TAIL / `ScreenEvents.afterExtract` are
  outside that pose and DO add leftPos. The Inventory Theme added it in both places and drew every slot square at
  twice the panel's offset - "an empty orange inventory grid at the bottom right"; the 2026-09-27 recipe
  book "fix" for that symptom was a different bug. A `SkinManager.createLookup` on a bare `new GameProfile(uuid,
  name)` is always the default skin: SkinManager only unpacks the profile's own textures property and never fetches
  one, so fetch the textured profile first (`ProfileViewerApi.fetchSkinProfile`) or use the tab list's `PlayerInfo`.

- HUD scale has one source: `HudElementRegistry.resolveScale` = the element's own `HudConfig` scale times the global
  HUD Scale (Home tab, `globalScale` in `killer560smod-hud.json`). The HUD editor scrolls and saves the OWN scale and
  draws at the product. Until 2026-10-04 three Gui mixins (Ability Timers, Dungeon Info, Etherwarp Waypoints) drew at
  `resolvePosition` with no scale at all, so resizing them in the editor never showed in game - a draw site that skips
  `resolveScale` silently opts out of both scales. Since 2026-10-05 the product also includes Auto Scale
  (`hud/AutoScale`, via `HudConfig.getEffectiveGlobalScale`), and SAVED positions are baseline units drawn at
  `saved * factor` - so a draw site that reads `HudConfig.getPosition` directly instead of `resolvePosition`, or saves a
  dragged position without `HudElementRegistry.toSaved`, lands in the wrong place on any monitor but 2560x1440 / GUI 3.

- The mod's own screens are laid out at `guiSize / factor` and drawn under a pose scale (`hud/mixin/AutoScaleScreenMixin`);
  every GUI mouse coordinate, drag deltas and the render mouseX/Y included, passes through the static
  `MouseHandler.getScaledXPos/YPos(Window, double)` (javap 26.1.2 and 26.2), which `AutoScaleMouseMixin` divides. So a mod
  screen must take its size from `this.width/height`, never from `getWindow().getGuiScaledWidth()`, and must read the mouse
  from its event arguments, never from `mouseHandler.xpos()`.

- In the mod menu a drag is delivered only to the widget that took the PRESS (`ContainerEventHandler` routes `mouseDragged`
  and `mouseReleased` to the focused child, javap 26.1.2), and every change rebuilds every widget. So a reorderable list is
  ONE widget with its scroll kept outside it (`gui/DragListWidget`, 2026-10-07), not a widget per row. `ModScreen` also
  scrolls the page on a wheel turn before any child sees it; a widget that scrolls itself implements `gui/WheelTarget`.

- A `FolderTab` section that is pinned (always open, no header) is never in `expanded`, so
  `findListeningKeyCaptureTab` did not ask it: Home's "Edit HUD Keybind" sat on "Press any key..." forever. Pinned
  sections are now checked first.
  (`CustomScoreboardGuiMixin` now strips the stat segments via `PlayerStatsFeature.actionBarReplacement` and re-sends the
  rest under a re-entry guard), never by blanking it there.

- **Every GUI `fill` costs more than its pixels.** Each one becomes a render-state element with its own matrix copy and
  screen rectangle, and vanilla's `GuiRenderState.hasIntersection` scans the node's existing elements for each
  one added, so a HUD that draws a shape a pixel row at a time is quadratic in rows. The Custom Scoreboard border
  (two fills per pixel row) was 6% of the render thread and most of its allocation; merged into runs of equal rows
  it is a handful of fills with identical pixels (2026-10-05). Draw runs, not rows - and when merging, keep the
  rects non-overlapping or a translucent colour blends twice.
  The placement walk (`navigateToAboveHighestElementWithIntersectingBounds`, javap 26.1.2 and 26.2) starts at the TOP
  node and scans every element of each node until one intersects, so fills drawn before each item of a menu are
  scanned against every earlier item: the Inventory Theme's per-slot fill + outline (450 elements in a chest) was 8% of
  the render thread with a chest open (403-perf-hub, 2026-10-07). When the fills cannot be merged into fewer rects,
  submit them as ONE element with `hud/GuiRects` (same `ColoredRectangleRenderState`s `fill`/`outline` build, emitted
  in order, bounds = their union - pixels and overlap order unchanged).

- **`GuiGraphicsExtractor.text(Font, String, ...)` runs ICU Bidi on every call.** It is
  `text(font, Language.getInstance().getVisualOrder(FormattedText.of(s)), ...)` (javap, both versions), and
  `FormattedBidiReorder.reorder` builds a `Bidi` and styled substrings each time; a `Component` caches its visual order,
  a `String` never does. Inside the mod's HUD layers `hud/HudTextCache` keeps the sequence per string (same call, same
  `Language`), via `hud/mixin/HudTextVisualOrderMixin`; outside them nothing changes (2026-10-07).

- **26.2 sorts QUADS only.** `StagedVertexBuffer.appendDraw` throws "Cannot sort draw with LINES" for any non-QUADS
  topology given a sorting (javap 26.2), so a `RenderType` built with `.sortOnUpload()` on `LINES_SNIPPET` crashes the
  first frame it draws. 26.1.2 accepted it. Solver ESP's through-walls lines did this; sort lines through
  `McRender.sortLinesOnUpload` (2026-10-05). `DEBUG_FILLED_SNIPPET` is QUADS on 26.2, so the filled types are fine.

- **Two mod-menu rows on one rectangle look like the top one and act as the bottom one.** ModScreen's content pane draws
  children in order (last on top) but gives a press to the FIRST under the cursor. Breaker Aura's `y += 20 ... y -= 20`
  side-step left Cooldown on Side Reach's rect and Auto Swap on Multi Break's, so dragging "Cooldown" moved Side Reach
  (2026-10-05). Testkit `386-ui-sliders` checks every tab, toggles flipped, for overlapping rows.

- To replace a vanilla HUD piece, wrap its Fabric layer instead of mixing into `Gui`: `HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> ...)` (identical in Fabric API 0.155.2+26.1.2 and 0.160.0+26.2, javap) calls the
  vanilla layer only when you do, so there is no 26.2 descriptor to break. The Custom Crosshair does this (2026-10-06); F1 is
  `McCompat.hudHidden`, because `options.hideGui` is gone on 26.2. To draw in SCREEN pixels from any pose, `pose().identity()`
  then `scale(1f / guiScale)` - an int `fill` is then one framebuffer pixel.

- A marker outline drawn as one-unit side strips per row is not an outline: the back edge had none and the tip rows were all
  edge, so only 69% of the old map arrow's edge was dark and his green arrow vanished on the green Entrance (testkit 395,
  2026-10-07). Draw a solid outline shape one unit bigger UNDER the fill (`MapPainter.outlinedTriangle`): 100%, 15.3:1.

- Mouse cursors: `GuiGraphicsExtractor.requestCursor(CursorType)` during `extractRenderState` sets the window cursor for that
  frame (javap, 26.1.2 and 26.2). `CursorTypes` has only the straight resize arrows; the diagonals come from
  `CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, name, fallback)` (LWJGL 3.4.1 / GLFW 3.4), created lazily on
  the render thread. The HUD editor does this; testkit 399 reads `pendingCursor` and the window's `currentCursor`.

- **A layout that places HUD elements must not write their saved positions.** Health and Mana Bars' Predefined layout
  (`playerstats/StatLayout`, 2026-10-07) answers `HudElement.layoutPosition()`, which `resolvePosition` returns before any
  saved position, and the HUD editor skips `setPosition` for those elements; so switching back to Custom draws every bar
  on the exact pixels it had (testkit 407 compares them). The editor's bottom button row sat on the areas beside the
  hotbar and took their clicks; it moves under the instructions while Predefined readouts are listed.

- **Hypixel's item resource is not an icon source for the Bazaar.** 1,174 of 2,201 bazaar ids are not in
  `/v2/resources/skyblock/items` (every `ENCHANTMENT_*`, `SHARD_*`, `ESSENCE_*`, `FACTION_*`), ~480 more are only a
  `hypixel_skyblock:` resource-pack model on a paper base, and `durability` was ignored, so 1,744 drew as paper without the
  pack (2026-10-07). The browser now reads the bundled `assets/killer560smod/bazaar/products.json` (tools/bazaar, from NEU
  incl. its pre-resource-pack commit 26169fe for old head textures) and uses a pack model only when
  `getResource("items/<path>.json")` finds it. Since Pack Disabler (2026-10-07) the icons come from the shared
  `skyblock/item_looks.json` (older NEU snapshots plus our own textures) and none fall back. Testkit 425/432 count them.

- **Fabric recreates every per-screen event at each `init`, `remove` included** (`fabric-screen-api` 5.1.0
  `ScreenMixin.beforeInit`, javap): a resize runs init again with fresh, empty events. Anything that keeps state for a
  screen across a resize must re-register ALL its per-screen listeners on every AFTER_INIT, its `remove` listener too, or
  the state outlives the screen. The Bazaar reskin (`auction/screen/BazaarReskin`, 2026-10-07) keys its state on the
  screen object and re-registers each init. To be first for input and last for drawing over a container, register two
  AFTER_INIT callbacks in phases ordered before and after `Event.DEFAULT_PHASE`: the per-screen listeners are then added
  before/after every other feature's (the reskin's presses never reach another feature's hidden-slot handler, and it
  paints over their overlays).
