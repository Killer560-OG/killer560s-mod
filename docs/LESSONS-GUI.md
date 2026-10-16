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
  never re-clamped, so deleting the entry only lasted until the next stray click: on 2026-10-07 his HUD config
  again held `storage_overlay {x: 0, y: 20}` (saved 20:26) and the overlay sat at the left edge. Its x is now
  not a saved value at all - `layoutPosition()` returns the centre every frame and only the saved y is honoured
  (testkit 453 measures it at six window/GUI sizes with that saved x 0). And `width()`/
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

- **A TAIL inject on `AbstractContainerScreen.extractRenderState` never runs in the player inventory, crafting table or
  furnaces.** `AbstractRecipeBookScreen.extractRenderState` replaces it outright (it calls `extractContents`, the carried
  item and `extractTooltip` itself, never super; javap 26.1.2 and 26.2), so the Item Browser panel drew in chests and not
  in the E inventory (testkit 709/710, 2026-10-11). Inject both classes, or hook `extractContents`/`extractSlot`, which
  both paths call.
- **The last GUI row can hang off the window.** `Window` makes the GUI height the window height over the GUI scale
  ROUNDED UP, so on a window whose height is not a multiple of the scale (a windowed 1440p screen) anything drawn on the
  bottom GUI row is partly or wholly off screen - the themed hotbar lost its whole bottom line (2026-10-12, testkit 897
  at 2560x1369 GUI 3). Clamp bottom-anchored pixel boxes to `getWindow().getHeight()`.
- **A shared slot line must straddle the gap between two items, not sit on one side of it.** The Inventory Theme drew
  each slot's left/top line inside its box and the right/bottom one past it, so a 16-unit item had t px less room on its
  left/top than its right/bottom - t/2 off centre at every GUI scale (killer560, 2026-10-12, measured 2 px at the default
  2 px by testkit 897). `cellBox` now shifts every grid line by `lineShift(t) = t/2`; odd widths stay half a pixel off.
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

- **Fabric's `addLast` HUD layers draw ABOVE an open in-game screen's dim.** With a screen whose `isInGameUi()` is true,
  `Gui.extractSubtitleOverlay` defers the SUBTITLES layer, and Fabric's layers added after it, into
  `Screen.extractBackground`, after the dim gradient (javap 26.1.2). So something meant to sit behind an inventory like the
  hotbar must be attached to a vanilla layer before SUBTITLES - and to one that always runs: an attached element only runs
  where its parent does, and after `EXPERIENCE_LEVEL` the bars vanished in creative. Health and Mana Bars are
  `attachElementAfter(MISC_OVERLAYS)` since 2026-10-07 (testkit 441: no pixel of the bar's raw colour beside the open
  inventory; 443/444 draw in creative).

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
  hotbar and took their clicks; since 2026-10-08 the editor has no buttons at all (its options are in Home's HUD Editor
section) and the bars have their own editor, `HudEditorScreen.forBars`.

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

- **An empty slot with a placeholder icon never reaches `Slot.isFake` in `AbstractContainerScreen.extractSlot`.** The
  method blits the icon (armour outlines, the offhand shield) and returns (javap, 26.1.2 and 26.2), so the Inventory
  Theme's backdrop, injected at `isFake`, left empty armour slots with no square or lines (testkit 475, 2026-10-07: the
  armour column scan found no outline at all with nothing worn). A second injection at `Slot.getNoItemIcon` draws it there
  when the icon is non-null. Theme lines are counted in screen pixels through `inventorytheme/PixelRects` (pose read,
  then `identity().scale(1/guiScale)`), and touching slots share one line by drawing each square's right/bottom line on
  the neighbour's left/top pixels (testkit 473/474).
- **A container reskin is not a mod screen, so Auto Scale skips it** (`AutoScale.scalesScreen` only takes classes under
  `com.killer560.hub`). The unified Auction House draws its API browser (a mod screen, scaled) and Hypixel's reskinned AH
  menus (a vanilla `ContainerScreen`) in one frame; unscaled, the reskin would draw at a different size from the browser
  (the factor is 1.125 at 1920x1080 GUI 2). `AhReskin` lays out at `width / AutoScale.current()`, draws under that pose scale and divides the mouse
  by the same factor (testkit 494 checks the layout at factor 1.125, 2026-10-07).
- **One gate hides every HUD layer: wrap them as they are registered.** Fabric's `HudElementRegistryImpl.addFirst/addLast/
  attachElementBefore/attachElementAfter` each take exactly one `HudElement` (javap, fabric-rendering-v1 23.3.1 and 25.3.3,
  identical), so `bazaar/mixin/HudLayerGateMixin` (`@ModifyVariable` at HEAD, `remap = false`) wraps every layer, this mod's
  and any other mod's, in a check of `BazaarHud.hidesHud()` - no draw site had to change. Testkit 485 adds two probe layers
  at run time (one last, one after MISC_OVERLAYS): with no screen both draw ~120 times in 20 ticks, with the Bazaar open and
  the gate on 0, with it off ~120 again - over the menu-less screen AND over a reskinned chest (2026-10-07).
- **A menu reskin flashes on every page change unless it draws the OLD page until the new one is read.** Each Hypixel page
  is a new container screen whose items arrive later; drawing "Reading..." (or Hypixel's chest) until a settle window passed
  is what killer560 saw as "this reloading thing". The Bazaar reskin now classifies on every item change and, until the new
  menu classifies, draws the previous `State` (its menu object still holds its items) frozen and inert to clicks. Testkit 486
  logs every frame: a page whose items trickle in over 5 ticks drew 81 frozen frames of the old page and 0 chest frames.
- **A reskin over a vanilla container is not Auto Scaled; the mod's own screens are** (`AutoScale.scalesScreen` is by
  package). For the menu-less Bazaar screen and the reskin to draw the same frame, the reskin scales its own drawing by
  `AutoScale.current()` and divides the mouse by it - and places tooltips in unscaled coordinates, since they are drawn
  after its pose is popped. A test pressing at layout coordinates must multiply by the factor too: 462/464/465/467 pressed
  beside every button until it did.

- **A screen opened straight from a chat command is closed again by the chat screen.** `/invsort load` opened the
  inventory inside the command, and `ChatScreen` sets the screen to null after the command returns, so the sorter's wait
  ended in "could not open your inventory" (traced in the code for killer560's "it says cannot open inventory", not
  reproduced on the old jar; fixed 2026-10-08). Open from
  `client.execute(...)` (as every other command here does) or from the next tick once no screen is up.
  `client.execute` only defers because the real chat Enter is itself a `Minecraft.execute` task (KeyboardHandler wraps
  GLFW's key callback in one; a nested execute is queued while a task runs). The testkit's `TestInput.pressKey` is not a
  task, so there the execute ran at once and /killer560 never opened the menu in the 1900 video; it opens in the game
  and through testkit 1920's real-key path (2026-10-15).

- **A setting that resizes the menu it sits in must not relayout mid-drag.** HUD Scale sizes the mod's screens since
  2026-10-08 (`AutoScale.menuFactor`); `AutoScaleScreenMixin` relays a screen out when its factor changes, which rebuilds
  every widget, the slider being dragged included. The relayout waits while the screen `isDragging()` or the left button
  is down. Also: ModScreen's sidebar does not scroll, so the menu factor is capped where the layout canvas stays 430 tall
  (at 280 the sidebar ran out of the panel at HUD Scale 200%, testkit 615's picture).
- **The first-person hands are drawn INSIDE `GameRenderer.renderItemInHand`, under its model-view push, on both versions.**
  26.1.2's `GameRenderer.renderItemInHand` only submits, but `ItemInHandRenderer.renderHandsWithItems` ends with its own
  `renderAllFeatures()` + `endBatch()` (26.2 flushes in `renderItemInHand`), while the `Matrix4fc` argument is multiplied
  onto the model-view stack. Anything that draws a hand item again later must push that same matrix: Held Item Outline's
  mask copy drawn without it never reached the screen (testkit 671's test views, 2026-10-11). `McRender.drawHandMask` does it.
- **26.2 is reverse-Z.** `GameRenderer.renderLevel` clears depth to 0.0 before the hands (26.1.2: 1.0) and LevelRenderer's
  always-on-top pass clears its own target to 0.0 (javap). An off-screen target cleared to 1.0 on 26.2 rejects every
  fragment, silently: Held Item Outline's mask stayed empty there until it cleared to `McRender.FAR_DEPTH` (2026-10-11).
- **The player's own tab-list latency is not a ping on Hypixel.** The Performance HUD read `PlayerInfo.getLatency()` and
  always said 1 ms (killer560, 2026-10-11). `util/PingTracker` measures a round trip with vanilla's ping request / pong
  pair (the F3 ping chart's packets; `ClientPacketListener.handlePongResponse` runs on the Netty thread, javap both
  versions) and only sends while something shows it; testkit 724 follows an injected 200 ms. Blood Camp, Auto Routes,
  AP3, Terminal Protection and Auto Debuff had compensated with that ~1 ms all along; since 2026-10-11 they read
  `PingTracker.automationMs()` (median of the last 5 pongs, tab value until the first) and keep it requesting while on
  (testkit 791-796).
- **Vanilla does not read Bungee's `§x§R§R§G§G§B§B` hex escape.** `StringDecomposer.iterateFormatted` skips `§x` and applies
  each `§<hex digit>` as a LEGACY colour (javap, 26.1.2 and 26.2), so the Name Changer fade drew every letter in the
  16-colour code of its blue channel's low nibble - "random blacks, whites and pinks" (killer560, 2026-10-11). Decode such
  strings with `NameColor.iterateFormatted` (NameReplacer, NameSplice, `toComponent`, and a redirect in
  `Font.prepareText(String)`); testkit 811-814 read the screenshot for a clean green-to-blue run in chat, tab, F5 tag, preview.
- A nametag's height comes from `EntityRenderState.nameTagAttachment` (the entity's dimensions), not from the render-state
  `scale`, so a render-only model scale leaves the tag at 1x height; `SupportersScaleMixin` scales it too (testkit 816).
- A Fabric `ScreenEvents.afterExtract` overlay draws ON TOP of what the screen drew, the carried item included: the custom
  leap menu's 80%-opaque boxes covered the head a leap click puts on the cursor, which is the "almost fully faded
  out" head killer560 saw (2026-10-11). Hiding the real menu needs the carried/snapback/floating item renders
  cancelled too (`SpiritLeapHideMixin`), not just the slots.

- **A raw-polled key must also ask "was he typing a tick ago?".** Key presses reach the open screen before
  END_CLIENT_TICK, so the press that ends a "Press any key..." capture finds the capture already over at the poll and the
  Edit HUD key opened the editor while being bound (testkit 862, 2026-10-12). `util/TypingDetector` decides typing (a
  focused EditBox/text area anywhere in the screen's widgets, chat/sign/book screens, the recipe-book and creative searches
  through accessors, the mod's own hand-drawn search boxes); the key poll skips on typing now OR at its previous run.
- Opening a screen over a container is safe to come back from: `AbstractContainerScreen.removed()` only runs
  `menu.removed(player)`, which drops a cursor item client-side and sends nothing (javap 26.1.2), so the server's menu stays
  open and `setScreen(container)` restores it. Edit HUD does this and refuses with an item on the cursor.

- **Anything drawn per slot from `ScreenEvents.afterExtract` lands on top of the tooltip.** Croesus's Highlight Runs filled
  each run slot from there and outlined it 1 px outside, so the boxes joined into a green grid painted over the item
  tooltip (killer560's screenshot, 2026-10-12). Per-slot drawing goes at `extractSlot`'s `Slot.isFake()` call in slot-local
  coordinates (`croesus/mixin/CroesusSlotMixin`, like Inventory Search), and borders stay inside the 16x16 square;
  testkit 875 diffs the shots and counts changed pixels inside the tooltip box (0) and in the gaps (0).
- **State read in a tick and drawn in frames is stale for every frame between a packet and the next tick.** Croesus read
  its run states at END_CLIENT_TICK, so after the menu's content landed the frames before the next tick drew the
  opened runs unhidden (killer560: "a quick flash showing the old run stuff"). It now works them out in the frame from
  the slots on screen, cached on the screen and an identity fingerprint of the run slots' stacks. Testkit 883 puts the
  content in after the mod's tick (frames outrun ticks in the real game) and checks every frame; the old jar drew 10/12
  unhidden on the content frame.
- **A Croesus run is not identified by its party.** He runs the same floor with the same party all day, so a key of name +
  floor + players was shared by many runs: looking at one run's chests re-coloured, hid or showed every run like it (his
  "sometimes they come back sometimes they disappear"; testkit 884 on the old jar: all four runs flipped 2<->3 six
  times). Runs are now known by that signature plus the instant their chests expire (croesus/CroesusRunMemory, saved
  in killer560smod-croesus-runs.json), matched per page by the list's shift, so a run keeps its state when one ahead
  expires (888-890). And a claim is remembered from the chest screen itself,
  because the list head can lag it by a refresh (886: the old jar read the run as unclaimed, orange, after its claim).
- **A clicked slot is empty until the server answers.** A left click on a container slot moves its item onto the cursor
  client-side, so a frame later the Croesus page looked one run short and the next same-party run took the clicked run's
  place and memory (889's first run). Croesus keeps the previous read while the cursor carries something.
- **A mod-menu tab's buttons act on the config object they were built with.** Health and Mana Bars' lambdas capture
  `PlayerStatsConfig.getInstance()`, and `PlayerStatsConfig.load()` (a profile apply) replaces the instance, so until the tab
  rebuilds, a press changes the old object (testkit 893's first runs: Bar Style "did nothing" after a reload, 2026-10-12).
- **A Unifont glyph's pixels are half a text unit.** The ♨ Vitality icon outlined like the bar number (one whole unit) was a
  black blob with red slivers; it is outlined by one glyph pixel (half a unit) and scaled so a glyph pixel is whole screen
  pixels (`BarPainter.drawGlyph`, v3 shots, 2026-10-12).
- **Judge "can you tell these colours apart" with CIEDE2000, over the real background, with a CVD simulation.** CIE76 rated
  Croesus's old gold needs-a-key border 39 from the unclaimed orange, which he could not tell apart; CIEDE2000 puts the
  gold-vs-gold "not looked at" pair at 19.8 and orange-vs-gold for a deuteranope at 11.6. Magenta and blue collapse for a
  protanope whatever the shades, so the key mark and its plate carry that pair, and testkit 898 samples those pixels too.
- **Distinct is not enough when most of a page is one state.** Five well-separated Croesus colours still hid the runs
  needing action under 17 bright "not looked at" outlines (2026-10-13). 898 now also scores salience (frame contrast and
  chroma against the page with highlights off): the action states must beat the quiet ones 2x; the old palette scored 0.77.
- **A testkit "restart" that reloads one store is not a restart.** 890 reloaded the run memory from disk and passed, while the
  Croesus totals lived in a second static map the reload never touched - on a real restart they came back "18+ / 60, Pages
  seen 1 of 3". The page counts are now saved with the run memory, and 890 also clears any leftover static when it restarts.
- **The Option A menu (2026-10-12) draws the tabs' own widgets as rows, centrally.** `SettingsButtonWidget`,
  `ThemedSliderButton` and `RangeSliderWidget` take the row look (label left; switch, value pill or track right) only
  while `ModScreen` is the screen (`SettingsButtonWidget.inMenu`); every other screen using them keeps the plain box. The
  look is read from the message: `"Label: ON/OFF"` is a switch, a `cycling(...)` button a pill, `"Label: value"` a chip,
  `▶/▼` a section header, anything else a raised button. A tab never needs to know.
- **A restyled slider must map the mouse onto the track it draws.** `AbstractSliderButton.setValueFromMouse` is private
  and maps the WHOLE widget width (`getX() + 4`, `width - 8`; javap 26.1.2 and 26.2), so a row whose track is only its
  middle third put the knob a third of a row away from the mouse. `ThemedSliderButton` overrides `onClick` (super for
  the drag flag, then `setValue` at the track) and `onDrag`, from the same `trackRange()` its drawing uses.
- **Regrouping categories must not move a tooltip.** `SettingTooltipsData` has category-scoped keys (`dungeon/mode`,
  `hud elements/scale`, `helpers/mode`); the regroup renamed every category, so `MenuCategories` remembers each tab's OLD
  category and the drawer looks tooltips up with it (`legacyCategory`). Rename a key only if the text should change.
- **The menu builds every tab off-screen** (card switches, the on/total counts, the search), so a one-shot flag a tab
  consumes in `buildWidgets` is eaten by whichever build runs first: `TranslateTab.openPickerOnNextBuild` (`/language`)
  would open the picker in a throwaway build. The real drawer is built first on open, and the Basic/Advanced strip's room
  is learned from that real build (one extra layout) rather than from an off-screen one.
- **A narrow drawer stacks side-by-side rows** (`ModScreen.stackRows`): only a line made purely of setting rows sharing one
  y, with nothing else overlapping it, is stacked full width; everything below moves down by the same amount. A line
  with a text box, a preview or a list keeps its layout. Tabs still lay themselves out for any width.
- **The menu's colours are one palette derived from five.** `MenuStyle`'s colours are mutable statics that `MenuStyle.use()`
  re-derives from the active `MenuTheme` (accent, panel, card, text, on-switch) whenever `MenuConfig.version()` moves, so a
  Custom set gets the same bevels, wells and hover shades as a preset; a panel above mid luminance switches on the light
  treatment (grey outlines, shadows at a third, no text shadow). A new piece of the menu takes its colours from those
  statics - a literal there is a colour the theme cannot change. Danger red and Discord/GitHub's own colours are the
  only fixed ones.
- **A tab's `StringWidget` colours its own text** (§ codes, drawn through `visitLines`, left-aligned at `getX()`), so a
  light theme cannot reach it: the drawer pane draws section headings itself in the accent / cheat red, and on a light
  theme every other line too, with the washed-out codes (§7 §f §a §b §e) swapped for darker partners
  (`MenuStyle.lightReadable`). `ColorSwatch`'s square is a styled sibling, not a § code, so `getString()` loses it: the
  row reads the sibling's `TextColor` and paints the swatch.
- **A container reskin is not a MenuThemedScreen, so it sets its palette itself** (sweep-b, 2026-10-13). The Bazaar and AH
  reskins draw over a vanilla `ContainerScreen`, where `MenuStyle.use()` falls back to the menu's colours; they call
  `MenuStyle.use(MenuTheme.forScreen(id))` before reading the palette, and the next `use()` re-derives the menu's. Their
  frame is `ScreenChrome.frameTight`: the reskin keeps a 4-8 unit margin whose outer 6 units testkit 496 requires to be
  pure backdrop at Auto Scale 1.125, and a 3-unit shadow already reached 2 pixel rows into it.
- **A hand-drawn card under real buttons in a menu page is a `gui/PaintedPanel`** (Profiles page, 2026-10-13): the content
  pane presses the FIRST child under the cursor, so the card is `active = false` (its `mouseClicked` declines and the press
  falls through to the button on it) and `hoveredChild` skips it when a real widget sits on it, or the buttons lose their
  tooltips. A vanilla `EditBox` draws its text and hint with a shadow, which smears dark text on the Light theme: call
  `setTextShadow(MenuStyle.TEXT_SHADOW)` (both versions, javap) and paint the hint yourself (testkit 948's shots).
- **Anything that must change a name's look over time belongs at `Font.prepareText`, not where the name is built.** Chat
  lines are styled once when added and the tab list caches its rows, so a name built with per-frame colours would only
  animate in some places. `NameAnimation` (Wave/Pulse, 2026-10-13) recolours at draw time, recognising a name by its
  exact letters in their exact base colours; testkit 960-965 freeze its clock to compare two frames pixel for pixel.
- **Never find a menu tab by its top-level index.** A testing build's `TestingMenu.arrange` moves every untested tab into
  Untested, Profiles included, so Home's Profiles button (`indexOf(ProfilesTab.class)`) silently did nothing there until
  2026-10-13. Open the leaf from `allLeaves()` with `openFeature(leaf)`: a page normally, a drawer in Untested (testkit 948).
- **Per-glyph text colour belongs in the font, not in the component.** Rebuilding a styled `Component` to animate a colour
  (Enchant Colours' old 100 ms chroma bucket) can only give a whole segment one colour. `Font$PreparedTextBuilder.accept`
  reads `style.getColor()` once per glyph while `x`/`y` are still that glyph's origin, and GUI text is prepared there at its
  GUI coordinates every frame (`GuiTextRenderState.ensurePrepared`; javap 26.1.2 and 26.2), so `chroma/mixin/
  ChromaGlyphColorMixin` redirects that call for a `util/ChromaWave` spec's TextColor (matched by identity; `fromRgb` never
  interns) and a whole tooltip is one diagonal waterfall (testkit 971: line 2 continues line 1 by +23.8 deg, 2026-10-13).
- **26.2's title screen has a row of icons the themed layout must name.** `TitleScreen.init` adds `FriendsButton`
  (key `gui.friends.open`), language and accessibility between the column and Options/Quit, and Mod Menu 20's icon style adds
  `SmallModMenuButtonWidget` there (18 on 26.1.2 used `UpdateCheckerTexturedButtonWidget`; javap). Unrecognised, both stayed
  on top of Swap Accounts (killer560, 2026-10-13). `MainMenuTitleLayout` removes them and every other Mod Menu button,
  classic row included (Friends via `McCompat.isFriendsButton`);
  testkit 341 checks every visible widget and every pair's overlap, Mod Menu loaded, both styles, both versions.
- **A fill drawn after an item in the same slot is always above it, player heads included** (javap 26.1.2 and 26.2): every
  non-oversized item is blitted from the item atlas, and an oversized one through its picture-in-picture, both into the
  item's OWN render node (`forEachItem` sets `current` before `addBlitToCurrentLayer`), while a later fill over it is
  placed in a node above (`findAppropriateNode` checks `itemStates`). So "the item paints over my overlay" is not a
  render-pass fact; testkit 961 shows Item Protect's star on heads (real skin, pack-era, helmet) on both versions.
- **A themed text box is an unbordered EditBox inside the box you draw, so widen its hit area.** Unbordered, vanilla draws the
  text at the box's top-left and only that line takes clicks; `AbstractWidget.mouseClicked` asks `isMouseOver` (javap 26.1.2),
  so `gui/ThemedEditBox` overrides it with the drawn well. Per-screen Theme (2026-10-13): `MenuStyle.use()` reads the open
  `MenuThemedScreen`'s `themeId()` through `MenuTheme.forScreen`, so every helper that calls `use()` gets that screen's palette.
- **The menu "remembered" its drawer, but Escape closed the drawer first,** so the remembered one was always none: closing
  the menu with a drawer open was impossible from the keyboard. Escape now closes the menu and the X closes the drawer
  (doc-menu, 2026-10-13; testkit 942/986).
- **A widget that resizes the screen it sits in can stay live if the drag is handed over.** HUD Scale's relayout waited for
  the release; now the slider keeps its press-time track in WINDOW units (`ThemedSliderButton.LiveDrag`, layout x times the
  applied factor) and `ModScreen.resumeLiveDrag` re-focuses the rebuilt slider, so the menu follows the drag without the
  value feeding back through the moving track (testkit 985). In the small test window every HUD Scale under 100% snaps to
  the same crisp factor, so 985 measures at 1920x1080.
- `ModScreenDriver`'s merged layouts (a group like Secrets or Puzzle Solvers) only take real screen clicks on the drawer
  opened last, so 806 reports their cyclers as "single value"; 987 checks the pill halves on one drawer directly.
- **A ChatScreen subclass must not call `ChatScreen.removed`** if it has no text of its own: vanilla's `removed` discards the saved
  draft when the box is blank and overwrites it otherwise (javap 26.1.2/26.2), so a read-only chat (Peek Chat, 2026-10-13) would
  eat his half-typed message. `PeekChatScreen.removed` only resets the scroll; testkit 1314 checks the draft survives.
- **A key that CLOSES a screen also opens it again** if the opener polls raw keys at END_CLIENT_TICK: the press reaches the
  screen first, so at the poll there is no screen and the key reads as a fresh edge. The closer marks the key held
  (`PeekChatFeature.closedByKey`) so the poll waits for a release (testkit 1314: open, key, closed).
- A vanilla `StringWidget` always shadows its text (smeared on the Light theme); a themed screen's labels override its
  draw (`ScoreboardEditorScreen.label`, screen sweep C, 2026-10-13).
- **An outline-only box shows nothing on a face you are pressed against.** Its four edges leave a 70-degree view once the
  camera is closer than about 0.4 of a block to the face, so the Etherwarp Overlay's default Outline drew no pixel at all
  walking into a wall ("doesnt draw on the face close to the player", 2026-10-13); every other face drew at every angle,
  distance, version and with Sodium (the box faces never z-fight the block, LEQUAL/GEQUAL with no bias is enough). That
  face first got a tint, then a smaller outline shrunk onto it; killer560 wanted neither (2026-10-13: "I want the full
  outline and if you can't see an Edge then don't draw that part of it"), so the Outline style is the box's own edges at
  every distance and pressed against it you see nothing. Testkit 1321 and 1322 check that. Sodium 0.9.2 breaks the gametest API's `takeScreenshot` ("Global terrain uniforms have not been
  updated"); read the main target back with `Screenshot.takeScreenshot` instead.
- **Hypixel's sidebar owner is one INVALID format code** ("§i", "§p", "§s"; prefix = the first 16 characters, then owner, then
  suffix), and `stripFormatting` only removes valid codes, so a "drop the owner when it strips blank" test kept it inside every
  number crossing character 16 ("Copper: §c114,25§i0" read 11,425, 2026-10-13). Judge visibility as the renderer does: '§' plus
  ANY next character is invisible (`ScoreboardData.hasVisibleText`; testkit 1311's Hypixel-shape block).
- **Text drawn from a Component the game rebuilds every frame never hits an identity cache.** Vanilla copies every tab
  row's and nametag's Component each frame, so its visual-order sequence is new each frame: Name Changer's weak-keyed
  cache paid a WeakReference and a locked put for each, Name Animation buffered every glyph of every text, and supporter
  names were rebuilt with `NameColor.toComponent` per frame (Bidi included). testkit 975's per-call timer put the name
  hooks at ~280 us a frame (8.5-9.5%) in a busy hub on both versions, 75 us with every name feature off; after a probe
  fast reject, a two-generation identity map, the unstyled `FormattedText.visit` for widths and one reused Component per
  supporter name (`PlayerNameDisplay.cached`) it is ~150 / ~30 us (2026-10-13), glyph-for-glyph identical (975's dumps).
- **On 26.2, geometry submitted from `LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES` is dropped on some frames and drawn a
  frame late on others** - that event runs after the frame's submitted nodes have been drawn. The Etherwarp Overlay box
  vanished or sat behind the block while he crouch-walked into it (2026-10-13); testkit 1322 walks into a gold block and
  lost the outline on 4 of 27 frames until the overlay moved to `COLLECT_SUBMITS` (0 of 27, both versions). The camera was
  never stale: the probe in 1322 showed McRender's camera equal to the main camera every frame. 36 other registrations
  still use AFTER_TRANSLUCENT_FEATURES.
- **On 26.1.2 a block entity paints over anything drawn into the immediate buffer source before the solid features.**
  `COLLECT_SUBMITS` fires before `renderSolidFeatures`, and McRender's 26.1.2 path writes into `context.bufferSource()`,
  whose shared buffer is ended (drawn) the moment the feature renderers ask for their first buffer; neither
  `LINES_TRANSLUCENT` nor `debugFilledBox` writes depth, so a chest, ender chest or trapped chest model drawn afterwards
  covered the Etherwarp Overlay box except the half of each line outside its silhouette ("etherwarp on chest corners does
  not really show up", killer560 2026-10-15; testkit 1324 on main: Filled 0/50, Outline 0/63 on a chest, full blocks and
  slabs fine; 26.2 never had it, because its McRender already submits). The overlay now submits through
  `context.submitNodeCollector().order(0).submitCustomGeometry` (same call on both versions), which `CustomFeatureRenderer`
  draws after the model parts of the same pass (javap 26.1.2). Any other `COLLECT_SUBMITS` drawer that must show on a
  block entity needs the same.

- **A font scoped to one screen must be decided per text render state, not per frame.** GUI text is measured while a
  screen lays out and draws, but PREPARED later, after every screen and the HUD have submitted (`GuiTextRenderState.
  ensurePrepared`, javap 26.1.2 and 26.2), so a "menu font on" flag held only while the mod screen draws would measure in
  one font and draw in the other. The Font setting's Mod Menu Only (`fonts/FontScope`) has each state remember, as it is
  made, whether it was made inside a mod screen, and replays that around its own preparation; the glyph swap itself is a
  ModifyReturnValue on `Font.getGlyphSource(FontDescription)`, where every width and glyph lookup goes. Testkit 1561/1562
  read each state's prepared bounds: Mod Menu Only changed 49 of 53 menu texts and 0 of 13 Options texts (2026-10-15).
- **A custom FontSet that borrows the default font's providers must be dropped at `FontManager.apply`.** That reload closes
  the old providers (unifont's buffers included), so a set still holding them reads freed glyph data; `fonts/CustomFonts`
  drops at apply's HEAD and rebuilds at the next tick (testkit 1562 reloads resources with a font on).
- **Every vanilla button label is drawn with a drop shadow, whatever the screen wants.** `GuiGraphicsExtractor$
  RenderingTextCollector.accept` builds its GuiTextRenderState with `dropShadow = true` (javap 26.1.2 and 26.2), so dark
  text on the Light theme's title screen smeared; `mainmenu/mixin/MainMenuLabelShadowMixin` turns it off there (1565).

- **Vanilla menus kept the old dark-and-orange look whatever the theme** because `mainmenu/MenuChrome` and `MenuWidgets`
  held it in `static final` colours. They are now plain statics re-derived by `MenuWidgets.sync()` from the main menu's
  theme (`MainMenuLook`), checked at the top of every draw helper, so a theme change reaches an open pause menu on the next
  frame (testkit 1570: Back to Game measured 41,35,29 in Amber, 33,42,52 in Ocean, 231,229,226 in Light without reopening).
  Inside one of the mod's own screens they read that screen's palette instead: re-deriving `MenuStyle` there would repaint
  the rest of the mod screen's frame in the main menu's colours.

## Moved from CLAUDE.md (2026-10-15, to keep it under 300 lines)

- **Moving a setting to a different sub-tab silently orphans its scoped tooltip.** `SettingTooltips.describe`
  looks up `"<sub-tab name>/<label>"` first and falls back to the bare label, so a `d.put("experiments/set", ...)`
  entry stops being found the moment that button is built by a different tab - no error, the hover text just
  changes or disappears. Re-key the entry in `SettingTooltipsData` in the same session as the move.

- **A per-screen or per-overlay Theme choice must name a saved theme by a stable id, and be listed live** (2026-10-15). `ScreenThemes`
  offered Theme Default, the presets and ONE "Custom" (the menu's current Custom colours), so a theme he saved never appeared;
  a list captured when the tab was built would also miss one saved after. A choice is `SAVED:<id>` (`MenuConfig.SavedTheme.id`,
  "T1"..., persisted; a file from before ids gets them in list order) and `ScreenThemes.choices()` is read when the button is
  pressed. Overlays over the game (`OverlayThemes`, setting "Menu Theme") use the same list but "Default" means the overlay's
  own look and `OverlayThemes.of(id)` is null there; `MenuStyle.snapshot` derives a palette without leaving the menu's statics
  changed. Testkit 1421 walks `OverlayThemes.ids()`.
- **Name Changer: a command line is never restyled, and the suggestions list is not an edit box (2026-10-15).** `NameReplacer`'s
  Scanner now ignores any line whose first visible glyph is `/` (it also covers a command echoed in chat), and
  `NameChangerCommandSuggestionsMixin` suppresses replacement while `CommandSuggestions` draws its list and usage hint - those
  are drawn outside `EditBox`, so the edit-box suppression never reached them. Matched by method name with a `CallbackInfo`-only
  handler so it serves both Minecraft versions (testkit 1550 fails on the old jar: `/<ign>` came back as `/Zed`).
- **A rank tag is replaced with the name, not on its own (2026-10-15).** The scanner tracks `[LETTERS+] ` glyph by glyph and
  extends a match of your own name back over a tag that sits directly in front of it, only when the tag is 2..12 letters/`+`
  (so a Skyblock level `[285]` is never taken); `Entry.rankReplacement` carries the new tag. Hypixel sends the tag in several
  Component parts, so it cannot be matched as one string - 1551 feeds it as `[MVP` + `++` + `] ` + name.
- **Custom names live in `killer560smod-namechanger-names.json`, outside every profile (2026-10-15).** It is in
  `ProfileManager.EXCLUDED_FILES` and also holds the Per-Profile Names switch, so the switch is the same in every profile. With
  the switch on, the names go in the profile's own `killer560smod-namechanger.json` as before. An install from before the split
  finds no names file on its first load and moves the names its profile file holds into it (1552).
- **Slot Text and Compactor Preview hook points (2026-10-15, javap 26.1.2 and 26.2).** Text over an item goes right after the
  one `GuiGraphicsExtractor.itemDecorations(Font, ItemStack, int, int, String)` call in `AbstractContainerScreen.extractSlot`
  (the rarity and search fills use the `Slot.isFake` call before the item instead). A grid inside an item tooltip is a
  `ClientTooltipComponent` added by a `@ModifyVariable` on the list argument of `GuiGraphicsExtractor.tooltip(Font, List, int, int,
  ClientTooltipPositioner, Identifier)`; hand it a COPY, vanilla's list may be immutable. A Personal Compactor/Deletor keeps its
  slots in custom data as `personal_compact_<n>` / `personal_deletor_<n>` strings holding a SkyBlock id (read from Skyblocker's
  open source and cross-checked with the wiki's slot counts, not yet seen on a live item).
- A harness key press never reaches `InputConstants.isKeyDown` (it polls GLFW), so a hold-key mode needs a test hook
  (`keyHeldForTest`, docs/TESTING-HOOKS.md). And an inventory screenshot diff must not look at the whole frame: the
  player model turns with the cursor and changed 602 px in testkit 961's first run; diff only the slot grid.
- **A card's description has to be written for the narrowest card, not measured on the one in front of you.** The grid lays
  cards out between `FeatureCards.CARD_MIN_W` (104) and about 131 wide, so a card has 90 px of text: three lines (about 38
  characters) when its name takes one line, two (about 26) when it takes two. The old descriptions were the first sentence of the
  master toggle's tooltip, 60-150 characters, so `MenuStyle.wrap` cut the last line with "..." on all 169 cards (testkit
  1060 failed on every one before `CardBlurbsData`). The long tooltip is now the hover text (`FeatureCards.longDescription`);
  `FeatureCards.cardText` is the one place a card's text is laid out, and `autoCut()` lists any tab whose text the menu had to
  trim itself, which 1060 requires to be empty. A new tab needs a blurb in `CardBlurbsData` (or `cardDescription()`).
- **Every vanilla button click is `minecraft:ui.button.click` through `SoundManager.play`**, so the Click Sound option is one
  `@ModifyVariable` on that method (`clicksound/mixin`), not a change per widget. An imported .ogg is not in any resource pack:
  `SoundBufferLibrary.getCompleteBuffer` is answered from the folder by a second mixin, and the decoded `SoundBuffer` is kept
  per file because each one owns an OpenAL buffer that is only freed when the engine clears its library. An `Identifier` cannot
  hold capitals or spaces, so an imported file is named to the engine by a hashed key (`imported/s<hash>`).
- **A setting that only touches a state the painter never draws does nothing.** Darken Unexplored multiplied only rooms in
  `STATE_UNDISCOVERED`, and those are exactly the rooms `LiveMapHudElement.renderMap` skips; on the cheat jar every
  world-scanned room was promoted to DISCOVERED first, so the slider could not reach a drawn pixel on either build
  (killer560, 2026-10-15: "Darken Unexplored does not work"). `MapPainter.unexplored` now decides from the map item's own
  tile states, and testkit 1015-sim-map-item-only reads the drawn fill at Darken 0 and 0.6.
- **A half-width row with no partner keeps its switch halfway across the drawer.** `ModScreen.stackRows` widened only rows of
  two or more, so a row whose partner was hidden (Door Keys' Show Tracer with the tracer off) or never planned (Names
  Holding Leap) stopped in the middle. Lone plain rows now take the drawer's full width, stacked or not
  (`widenLoneRows`); testkit 1015-ui-switch-align sweeps every drawer with each switch flipped.
- **A UI animation must start when the input happened, not at the next frame that notices it** (menu-anim, 2026-10-15). A
  tween retargeted from the draw starts at that frame's time, so at 30 FPS a switch's knob ran 33 ms behind the same click
  at 300 FPS (testkit 1590: 0.83 against 0.96 at 100 ms). `MenuAnim.input()` stamps clicks, keys and the wheel and a
  change seen at the next frame starts from that stamp. Two draws at one clock value are one frame, or the stamp is lost.
- **`now - Long.MIN_VALUE` overflows to a negative elapsed time.** The menu's "never started" sentinel made `progress()`
  return 0, so a fresh menu's cards were drawn at opacity 0 and every drawer row at 25% - found because 1594's per-frame
  allocation was 100 KB LOWER with Smooth than with Off (invisible text is never built). Guard the sentinel first.
- **A closing animation cannot keep the screen open.** Escape must hand the game its input at once (testkit 942/986 check the
  menu is gone right after the key), so the menu closes immediately and `MenuCloseFade` draws the closed ModScreen's
  picture fading over whatever is next (a HUD layer with no screen, `ScreenEvents.afterExtract` over one).
- **A slider's knob position must come from what was SAVED, not from the mouse** (menu-anim-2, 2026-10-15). 160 menu sliders
  each round in their own `applyValue` (whole numbers, steps of 25 or 50k, tenths, a list) and none told the widget, so
  the knob rested wherever the mouse left it: testkit 1598 found 186 knobs resting between two values on main. Rather
  than give 160 sites a step count, `ThemedSliderButton.setValue` (vanilla's mouse and key paths both call it) asks
  `ModScreen.twinSliderValue`: the tab is built again off-screen and the same row's constructor turns the saved
  setting into the knob position, used only when its text matches. The same sweep found a label showing the unsnapped
  drag ("Width: 0.51x" saved as 0.50) and two sliders storing more precision than they show (Turn Speed, Timer Text
  Scale): a slider must save what its label says.

- **`FormattedCharSequence.forward(String, Style)` does NOT apply formatting codes**: it iterates characters, so "§6X"
  draws a literal "§6". Vanilla's `getVisualOrder` reads them with `StringDecomposer.iterateFormatted`. `HudTextCache`'s
  left-to-right fast path (no ICU Bidi for text below U+0590 under a left-to-right language) first used `forward` and
  testkit 95-perf-fixes, which compares every glyph and style with vanilla's, caught it; it now wraps
  `iterateFormatted(text, Style.EMPTY, sink)` (2026-10-15).
- **`HudElementRegistry.drawAt` resolves the position, scale and pose BEFORE the element's render finds its feature
  off**, about 2 us per element per frame; Health and Mana Bars' Predefined layout was built every frame with the
  feature off (20 us, 2 KB). `HudInGameRenderer` now skips an element whose `isEnabledInSettings()` is false (its
  sixteen renders gate on that same setting - check a new element's before adding it to that list) and draws no stat
  readout while the feature is off (95-perf-features, 2026-10-15). Other `drawAt` callers still pay it. (The Auto Clear
  and Autopilot status lines this used to name are one `AutoStatusHud` since 2026-10-15; it builds its text on the client
  tick, only when what it shows changed, and its layer returns before `drawAt` when there is nothing to show.)
- **Never write a SCREEN position into HudConfig.** `HudConfig` positions are Auto Scale baseline units and
  `resolvePosition` multiplies them by the factor. The Custom Scoreboard's SkyHanni snap stored its screen x there, read
  back `x * factor` on the next frame, took the mismatch for an Edit HUD drag and switched the snap off for good, so the
  board sat at `x * 0.8` (228 px in from the right edge on a 1366x768 window) instead of against it (testkit 1661/1667 on
  main, 2026-10-15). An element that must hug an edge keeps its own anchor and serves it from `layoutPosition()`
  (`HudElement.keepOnScreen` / `storeEditorPosition` also make Edit HUD clamp and hand the drop back to it).
- **A right-anchored HUD box drawn from a rounded x drifts.** The Custom Scoreboard's right wall was `round(x) + width * scale`,
  so at a non-integer drawn scale (Auto Scale makes nearly every real one so) it moved up to a pixel each time the board's
  width changed, and a Min Width box kept its text on the left so the text's right end moved by the slack (testkit 1668,
  2026-10-15). Draw it shifted by `round(w * scale) - w * scale` (`anchorShift`) and put the text block against the wall
  (`blockOffset`); the hover layout must use the same shift and block.
- **Hiding a container's slots does not stop vanilla hovering them.** The terminal Custom GUI cancelled `extractSlot`,
  the tooltip and the background, but `extractContents` still set `hoveredSlot` from the private `getHoveredSlot(DD)`, so
  the white hover highlight (`extractSlotHighlightBack/Front`, not part of `extractSlot`) lit up the invisible slots and the
  hotbar-number and drop keys sent SWAP/THROW on them (killer560, 2026-10-15; testkit 1015-menu-term-hidden-slots on main:
  1024 px changed, `0/0/SWAP`, `0/0/THROW`). Answer null from `getHoveredSlot` while slots are hidden; every hover reader
  (highlight, tooltip, keys, scroll, other features) goes quiet at once (javap 26.1.2 and 26.2).
- **A table column sized by a fixed number overlaps the moment its heading grows.** Load a Room's columns were 30-64
  units wide, and "Crypts ▼" (the sort arrow adds a space and a glyph) is 44 against Crypts' 34, so with Crypts sorted it
  ran 4 units into "Secrets" (killer560's screenshot, 2026-10-15; Size and Secrets did the same sorted). `SimMenuScreen
  .columns()` now measures each column's heading with either arrow and every listed cell, keeps 8 units between columns,
  gives Name the rest with "...", and drops whole columns before anything can touch. testkit 97-sim-menu-tidy measures it.
- A field made lazy behind a getter must not be read raw anywhere else. `GreenTheme.gradient` became null-until-`gradient()`
  (d7bdc71e) while `background()` still switched on the field, so the first Green menu draw threw NPE and the themed main
  menu turned itself off for the session (the vanilla title showed; 2026-10-16). Grep the class for the bare field.
- **A one-unit grid on a mod screen is not even.** A mod screen's GUI unit is guiScale x Auto Scale screen pixels, 2.25 at
  1920x1080 GUI 3, so the map designer's 1-unit lines came out 2 and 3 px wide and its cells 112 and 113 px apart (testkit
  75-sim-designer-grid on main, 2026-10-16). Anything that must line up to the pixel is laid out in its own whole-pixel unit
  (`SimMapEditorScreen.layoutGrid`: pose scaled by `gk / pixelsPerUnit`, `gk = round(pixelsPerUnit)`), with mouse hits
  mapped through the same numbers.
- **On 26.1.2 every switch of render type in a world render event was a draw call.** None of the types the mod draws
  with is a fixed buffer of the level `BufferSource`, so they share one buffer and `getBuffer` for a different type ends
  the previous batch (javap `MultiBufferSource$BufferSource.getBuffer`, `lastSharedType`). Dungeon ESP's fill + outline
  per mob made 40 draw calls a frame for 20 mobs and allocated 89-112 KB (testkit 95-perf-world on main 69505e45).
  `McRender.inCameraSpace` now writes into the mod's own source inside AFTER_TRANSLUCENT_FEATURES / _TERRAIN, every
  type a fixed buffer, drawn once after the event (ESP: 2 draw calls, 6.6 KB); 26.2's submit collector already groups by
  type. The probe's CPU microseconds did NOT drop measurably (building and uploading the same vertices dominates, and the
  driver's per-draw work is not in the probe), so judge this kind of change on draw calls and bytes. The probe now books
  that draw under `compat.McRender`, so add it back when comparing a feature with main.
  A per-item loop should still feed a `util/BoxBatch` rather than one `inCameraSpace` per box: each call allocates its
  lambda and colour array, and the ESP's per-box AABB/Vec3 were most of its 112 KB.
- **A screen drawn every frame must not lay out, split or Bidi its text every frame.** The mod menu allocated 340 KB a
  frame on its card grid and 700 KB with a drawer open (testkit 95-perf-screens, 2026-10-15): about 45% was ICU Bidi from
  `text(Font, String)` (vanilla reorders every String, every call; a `Component` keeps its order, a fresh
  `Component.literal(..).withStyle(BOLD)` per frame does not), 30% was `GuiRects` building a render state, a pose copy
  and four bounds objects per fill, and 20% was each card's word-wrap (`String.split` regex, a candidate string per
  word). The menu now draws inside `HudTextCache`'s scope, `GuiRects` keeps parts as ints (vanilla's states only for a
  lone part) and card text, tooltip lines and message splits are kept until their inputs change: 86 / 163 KB. Read a
  widget's message by object identity (`setMessage` replaces it; nothing edits one in place), not `getString()` per call.
- **Most of a text HUD's per-frame allocation is vanilla's glyphs, not the mod's strings.** Measured per draw with the
  PerfProbe (testkit 95-perf-hud, 2026-10-15): Score Calculator 13,375 B on main with its lines rebuilt every frame,
  11,712 B with them cached; Advanced Position 7,607 vs 7,476. Caching text saves the String.format/concatenation time,
  not the ~70 B per drawn character `GuiGraphicsExtractor.text` costs. And `Font.width` of a string with `§` codes builds
  a `Style` per code: Advanced Position's width() was 1,600 B a call until it was memoised on the line array.
- **A HUD element's render-state elements can be replayed on a later frame** (`livemap/MapDrawCache`, 2026-10-15): hand
  the same `ColoredRectangleRenderState`/`GuiTextRenderState` objects to `GuiRenderState` again, through the same `add*`
  calls in the same order, and they layer exactly as a fresh draw (a text state even keeps its glyph layout, so drop the
  cache on `FontSet.reload`). Record only the OUTERMOST call: recording every hooked call put each element in twice
  (95-perf-livemap verify: "count 30 vs fresh 15", 2406 stale frames), counting depth fixed it (0 of 2817). The key must
  be every input compared exactly; the verify mode (paint afresh unsubmitted, compare) is what finds a missed one, and
  the paint count is what found Predictive Rooms bumping its version every tick (the map repainted every tick under ALL ON).
- **Writing world vertices through VertexConsumer's Pose/Matrix/float-colour helpers allocates per vertex** (javap, 26.1.2
  and 26.2 alike; JFR 2026-10-16, ar-render-1016): `addVertex(Matrix4fc|Pose, x, y, z)` and `setNormal(Pose, ...)` each
  make a `new Vector3f`, and `setColor(r, g, b, a)` becomes four `MemoryUtil.memPutByte`, each an FFM segment object on
  Java 25. BufferBuilder overrides none of them, so for a plain BufferBuilder the same JOML calls into scratch vectors plus
  `setColor(int argb)` (one `memPutInt`, same bytes) write identical vertices: Auto Routes' bare node shapes went from
  25.8 KB to 0.2 KB a frame with pixel-identical shots. `WorldRenderUtils` and every other world draw still use the helpers.
- A painted main menu that must move smoothly at full resolution cannot use `g.blit` tiles (GUI_TEXTURED samples NEAREST,
  so a sub-pixel offset snaps a whole pixel). Amber's Aurora (2026-10-16) submits one `BlitRenderState` with its own
  pipeline built on `RenderPipelines.GUI_TEXTURED_SNIPPET` (which already declares Sampler0 in 26.1.2 and 26.2, so no
  `withSampler0`) and a `TextureSetup` with the LINEAR clamp sampler; the clock rides in the vertex colour (24-bit loop
  fraction) because a GUI element has no uniform of its own. Booted and checked on both versions by testkit 1695.
