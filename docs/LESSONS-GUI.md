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
