# GUI, HUD and rendering lessons

Problem, then fix; verified only. Drawing cost, text and fonts, container screens, screen input and world rendering are
here. HUD elements, positions, scale, the HUD editor and the map painter are in [LESSONS-HUD.md](LESSONS-HUD.md); the
mod menu (rows, sliders, tooltips, themes, animation) and the painted main menu are in [LESSONS-MENU.md](LESSONS-MENU.md).

## Drawing cost

- **Every GUI `fill` costs more than its pixels.** Each one becomes a render-state element with its own matrix copy and
  screen rectangle, and vanilla's `GuiRenderState.hasIntersection` scans the node's existing elements for each one added,
  so a HUD that draws a shape a pixel row at a time is quadratic in rows. The Custom Scoreboard border (two fills per pixel
  row) was 6% of the render thread and most of its allocation; merged into runs of equal rows it is a handful of fills with
  identical pixels. Draw runs, not rows, and keep merged rects non-overlapping or a translucent color blends twice.
  The placement walk (`navigateToAboveHighestElementWithIntersectingBounds`, javap 26.1.2 and 26.2) starts at the TOP node
  and scans every element of each node until one intersects, so fills drawn before each item of a menu are scanned against
  every earlier item: the Inventory Theme's per-slot fill + outline (450 elements in a chest) was 8% of the render thread
  (403-perf-hub). When fills cannot be merged, submit them as ONE element with `hud/GuiRects` (the same
  `ColoredRectangleRenderState`s `fill`/`outline` build, in order, bounds = their union; pixels and overlap unchanged).
- **`GuiGraphicsExtractor.text(Font, String, ...)` runs ICU Bidi on every call.** It is
  `text(font, Language.getInstance().getVisualOrder(FormattedText.of(s)), ...)` (javap, both versions), and
  `FormattedBidiReorder.reorder` builds a `Bidi` and styled substrings each time; a `Component` caches its visual order, a
  `String` never does. Inside the mod's HUD layers `hud/HudTextCache` keeps the sequence per string (same call, same
  `Language`) via `hud/mixin/HudTextVisualOrderMixin`.
- **`FormattedCharSequence.forward(String, Style)` does NOT apply formatting codes**: it iterates characters, so "§6X" draws
  a literal "§6". `HudTextCache`'s left-to-right fast path (no ICU Bidi for text below U+0590 under a left-to-right
  language) wraps `iterateFormatted(text, Style.EMPTY, sink)`, as vanilla's `getVisualOrder` does (testkit 95-perf-fixes
  compares every glyph and style with vanilla's).
- **Text drawn from a Component the game rebuilds every frame never hits an identity cache.** Vanilla copies every tab
  row's and nametag's Component each frame. Name Changer's weak-keyed cache paid a WeakReference and a locked put for each,
  Name Animation buffered every glyph, and supporter names were rebuilt with `NameColor.toComponent` per frame (Bidi
  included): ~280 us a frame (8.5-9.5%) in a busy hub, 75 us with every name feature off (testkit 975's per-call timer).
  A probe fast reject, a two-generation identity map, the unstyled `FormattedText.visit` for widths and one reused
  Component per supporter name (`PlayerNameDisplay.cached`) made it ~150 / ~30 us, glyph-for-glyph identical.
- **A screen drawn every frame must not lay out, split or Bidi its text every frame.** The mod menu allocated 340 KB a
  frame on its card grid and 700 KB with a drawer open (testkit 95-perf-screens): ~45% ICU Bidi from `text(Font, String)`
  (a fresh `Component.literal(..).withStyle(BOLD)` per frame does not keep its order either), 30% `GuiRects` building a
  render state, a pose copy and four bounds objects per fill, 20% each card's word-wrap (`String.split` regex, a string per
  word). The menu now draws inside `HudTextCache`'s scope, `GuiRects` keeps parts as ints (vanilla's states only for a lone
  part), and card text, tooltip lines and message splits are kept until their inputs change: 86 / 163 KB. Read a widget's
  message by object identity (`setMessage` replaces it; nothing edits one in place), not `getString()` per call.
- **In a `GuiRects` batch every pose CHANGE costs a pose copy** (shared only while the pose stays the same), so a shape
  drawn as several push/translate/pop pieces allocates per piece. Ocean's Cavern Falls (each streak under three poses, each
  mist column under its own) was 8085 quads at 35.4 B/quad against the old waterfall's 28.9 (testkit 1690); each streak,
  puff and ring under ONE scaled pose (integer units of w/12, len/256, a column's eighth) made it 5136 quads at 30.8 B/quad,
  same picture.

## Text, fonts and names

- The mod's own widget labels are not drawn through `GuiGraphicsExtractor.text`; they go through
  `GuiGraphicsExtractor$RenderingTextCollector.accept`. A text hook that only targets `text` misses every button and label
  in the settings GUI.
- **Vanilla text shadows on the Light theme.** `RenderingTextCollector.accept` builds every vanilla button label's
  GuiTextRenderState with `dropShadow = true` (javap 26.1.2 and 26.2), so dark text smeared on the Light title screen;
  `mainmenu/mixin/MainMenuLabelShadowMixin` turns it off there (testkit 1565). A vanilla `StringWidget` always shadows its
  text: a themed screen's labels override its draw (`ScoreboardEditorScreen.label`). A vanilla `EditBox` draws its text and
  hint with a shadow: call `setTextShadow(MenuStyle.TEXT_SHADOW)` (both versions, javap) and paint the hint yourself (the
  Profiles name box, testkit 948). This is why the mod menu's Light theme draws without shadows.
- **Vanilla does not read Bungee's `§x§R§R§G§G§B§B` hex escape.** `StringDecomposer.iterateFormatted` skips `§x` and applies
  each `§<hex digit>` as a LEGACY color (javap, both versions), so the Name Changer fade drew each letter in the 16-color
  code of its blue channel's low nibble ("random blacks, whites and pinks"). Decode such strings with
  `NameColor.iterateFormatted` (NameReplacer, NameSplice, `toComponent`, and a redirect in `Font.prepareText(String)`);
  testkit 811-814 check a clean green-to-blue run in chat, tab, F5 tag and preview.
- **Anything that must change a name's look over time belongs at `Font.prepareText`, not where the name is built.** Chat
  lines are styled once when added and the tab list caches its rows. `NameAnimation` (Wave/Pulse) recolors at draw time,
  recognizing a name by its exact letters in their exact base colors; testkit 960-965 freeze its clock to compare frames.
- **Per-glyph text color belongs in the font, not in the component.** Rebuilding a styled `Component` (Enchant Colors' old
  100 ms chroma bucket) can only give a whole segment one color. `Font$PreparedTextBuilder.accept` reads `style.getColor()`
  once per glyph while `x`/`y` are that glyph's origin, and GUI text is prepared there at its GUI coordinates every frame
  (`GuiTextRenderState.ensurePrepared`; javap both versions), so `chroma/mixin/ChromaGlyphColorMixin` redirects that call for
  a `util/ChromaWave` spec's TextColor (matched by identity; `fromRgb` never interns): one diagonal waterfall over a whole
  tooltip (testkit 971: line 2 continues line 1 by +23.8 deg).
- **A font scoped to one screen must be decided per text render state, not per frame.** GUI text is measured while a screen
  lays out and draws but PREPARED later, after every screen and the HUD have submitted (`GuiTextRenderState.ensurePrepared`),
  so a flag held only while the mod screen draws measures in one font and draws in the other. The Font setting's Mod Menu
  Only (`fonts/FontScope`) has each state remember whether it was made inside a mod screen and replays that around its
  preparation; the glyph swap is a ModifyReturnValue on `Font.getGlyphSource(FontDescription)`, where every width and glyph
  lookup goes. Testkit 1561/1562: Mod Menu Only changed 49 of 53 menu texts and 0 of 13 Options texts.
- **A custom FontSet that borrows the default font's providers must be dropped at `FontManager.apply`.** That reload closes
  the old providers (unifont's buffers included), so a set still holding them reads freed glyph data; `fonts/CustomFonts`
  drops at apply's HEAD and rebuilds at the next tick (testkit 1562).
- **Name Changer: a command line is never restyled, and the suggestions list is not an edit box.** `NameReplacer`'s Scanner
  ignores any line whose first visible glyph is `/` (a command echoed in chat too), and
  `NameChangerCommandSuggestionsMixin` suppresses replacement while `CommandSuggestions` draws its list and usage hint, which
  are drawn outside `EditBox`. Matched by method name with a `CallbackInfo`-only handler for both versions (testkit 1550 fails
  on the old jar: `/<ign>` came back as `/Zed`).
- **A rank tag is replaced with the name, not on its own.** The scanner tracks `[LETTERS+] ` glyph by glyph and extends a
  match of your own name back over a tag directly in front of it, only for 2..12 letters/`+` (a Skyblock level `[285]` is
  never taken); `Entry.rankReplacement` carries the new tag. Hypixel sends the tag in several Component parts, so it cannot
  be matched as one string (1551 feeds `[MVP` + `++` + `] ` + name).
- **Custom names live in `killer560smod-namechanger-names.json`, outside every profile.** It is in
  `ProfileManager.EXCLUDED_FILES` and also holds the Per-Profile Names switch, so the switch is the same in every profile;
  with it on, the names go in the profile's own `killer560smod-namechanger.json`. An install from before the split moves the
  names its profile file holds into it on first load (1552).
- A nametag's height comes from `EntityRenderState.nameTagAttachment` (the entity's dimensions), not from the render-state
  `scale`, so a render-only model scale leaves the tag at 1x height; `SupportersScaleMixin` scales it too (testkit 816).

## Container screens and slots

- **A TAIL inject on `AbstractContainerScreen.extractRenderState` never runs in the player inventory, crafting table or
  furnaces.** `AbstractRecipeBookScreen.extractRenderState` replaces it (it calls `extractContents`, the carried item and
  `extractTooltip` itself, never super; javap both versions), so the Item Browser panel drew in chests and not in the E
  inventory (testkit 709/710). Inject both classes, or hook `extractContents`/`extractSlot`, which both paths call.
- **Anything drawn from inside `AbstractContainerScreen.extractContents` is already translated by leftPos/topPos** (labels,
  `extractSlotHighlightBack/Front`, `extractSlots`/`extractSlot`; javap both versions), so it draws at plain `slot.x, slot.y`
  (slot-local). `extractBackground` and the `extractRenderState` TAIL / `ScreenEvents.afterExtract` are outside that pose and
  DO add leftPos. The Inventory Theme added it in both places and drew every slot square at twice the panel's offset ("an
  empty orange inventory grid at the bottom right").
- **Anything drawn per slot from `ScreenEvents.afterExtract` lands on top of the tooltip**, and so does any afterExtract
  overlay over the carried item. Croesus's Highlight Runs filled each run slot from there and outlined it 1 px outside, a
  green grid over the item tooltip. Per-slot drawing goes at `extractSlot`'s `Slot.isFake()` call in slot-local coordinates
  (`croesus/mixin/CroesusSlotMixin`, like Inventory Search), one fill a slot, borders inside the 16x16 square (testkit 875
  counts changed pixels in the tooltip box and the gaps: 0). The custom leap menu's 80%-opaque boxes covered the head a leap
  click puts on the cursor; hiding the real menu needs the carried/snapback/floating item renders canceled too
  (`SpiritLeapHideMixin`), not just the slots.
- **An empty slot with a placeholder icon never reaches `Slot.isFake` in `AbstractContainerScreen.extractSlot`.** It blits
  the icon (armor outlines, the offhand shield) and returns (javap both versions), so the Inventory Theme backdrop left empty
  armor slots bare (testkit 475). A second injection at `Slot.getNoItemIcon` draws there when the icon is non-null. Theme
  lines are counted in screen pixels through `inventorytheme/PixelRects` (pose read, then `identity().scale(1/guiScale)`).
- **A shared slot line must straddle the gap between two items.** The Inventory Theme drew each slot's left/top line inside
  its box and the right/bottom one past it, so an item sat t/2 off center at every GUI scale (2 px at the default 2 px,
  testkit 897). `cellBox` shifts every grid line by `lineShift(t) = t/2`; touching slots share one line (473/474); odd widths
  stay half a pixel off.
- **A fill drawn after an item in the same slot is always above it, player heads included** (javap both versions): every
  non-oversized item is blitted from the item atlas, and an oversized one through its picture-in-picture, both into the
  item's OWN render node (`forEachItem` sets `current` before `addBlitToCurrentLayer`), while a later fill is placed in a
  node above (`findAppropriateNode` checks `itemStates`). Testkit 961 shows Item Protect's star on heads on both versions.
- **Hiding a container's slots does not stop vanilla hovering them.** The terminal Custom GUI canceled `extractSlot`, the
  tooltip and the background, but `extractContents` still set `hoveredSlot` from the private `getHoveredSlot(DD)`, so the
  hover highlight (`extractSlotHighlightBack/Front`) lit the invisible slots and the hotbar-number and drop keys sent
  SWAP/THROW on them (testkit 1015-menu-term-hidden-slots: 1024 px changed, `0/0/SWAP`, `0/0/THROW`). Answer null from
  `getHoveredSlot` while slots are hidden; every hover reader goes quiet at once.
- **Slot Text and Compactor Preview hook points** (javap both versions). Text over an item goes right after the one
  `GuiGraphicsExtractor.itemDecorations(Font, ItemStack, int, int, String)` call in `AbstractContainerScreen.extractSlot`
  (rarity and search fills use the `Slot.isFake` call before the item). A grid inside an item tooltip is a
  `ClientTooltipComponent` added by a `@ModifyVariable` on the list argument of `GuiGraphicsExtractor.tooltip(Font, List,
  int, int, ClientTooltipPositioner, Identifier)`; hand it a COPY, vanilla's list may be immutable. A Personal
  Compactor/Deletor keeps its slots in custom data as `personal_compact_<n>` / `personal_deletor_<n>` strings holding a
  SkyBlock id (from Skyblocker's source and the wiki's slot counts, not yet seen on a live item).
- **A container reskin is not a mod screen, so Auto Scale skips it** (`AutoScale.scalesScreen` only takes classes under
  `com.killer560.hub`). The unified Auction House draws its API browser (scaled) and Hypixel's reskinned AH menus (a vanilla
  `ContainerScreen`) in one frame, and the Bazaar the same with its menu-less screen. `AhReskin` and the Bazaar reskin lay
  out at `width / AutoScale.current()`, draw under that pose scale, divide the mouse by the same factor (1.125 at 1920x1080
  GUI 2) and place tooltips in unscaled coordinates, since those draw after the pose is popped. A test pressing at layout
  coordinates must multiply by the factor too (494; 462/464/465/467 pressed beside every button until they did).
- **A menu reskin flashes on every page change unless it draws the OLD page until the new one is read.** Each Hypixel page
  is a new container screen whose items arrive later. The Bazaar reskin classifies on every item change and, until the new
  menu classifies, draws the previous `State` (its menu object still holds its items) frozen and inert to clicks (testkit
  486: a page trickling in over 5 ticks drew 81 frozen frames and 0 chest frames).
- **Fabric recreates every per-screen event at each `init`, `remove` included** (`fabric-screen-api` 5.1.0
  `ScreenMixin.beforeInit`, javap): a resize runs init again with fresh events, so state kept across a resize must re-register
  ALL its per-screen listeners, `remove` too, on every AFTER_INIT (`auction/screen/BazaarReskin` keys its state on the screen
  object). To be first for input and last for drawing over a container, register two AFTER_INIT callbacks in phases ordered
  before and after `Event.DEFAULT_PHASE`.
- **Hypixel's item resource is not an icon source for the Bazaar.** 1,174 of 2,201 bazaar ids are not in
  `/v2/resources/skyblock/items` (every `ENCHANTMENT_*`, `SHARD_*`, `ESSENCE_*`, `FACTION_*`), ~480 more are only a
  `hypixel_skyblock:` resource-pack model on a paper base, and `durability` was ignored: 1,744 drew as paper without the
  pack. The browser read the bundled `assets/killer560smod/bazaar/products.json` (tools/bazaar, from NEU incl. its
  pre-resource-pack commit 26169fe for old head textures), with a pack model only when `getResource("items/<path>.json")`
  finds it; since Pack Disabler the icons come from the shared `skyblock/item_looks.json` and none fall back (425/432).
- **A picture-in-picture GUI element ignores the pose.** `GuiGraphicsExtractor.skin(...)` (and the other PIP states) is
  built from bare coordinates and the scissor, never `pose()` (javap 26.3 and 26.2), so under Auto Scale's pose scale the
  tour guide drew at 1/1.125 of his place (x off by ~190 px at the right edge of 1920x1080 GUI 2). Put the box through
  `pose().transformPosition` and scale the model by the transformed height (`intro/Guide`). The Profile Viewer's
  `PlayerSkinWidget` inside an Auto Scaled screen has the same offset (not fixed, 2026-11-01).
- A `SkinManager.createLookup` on a bare `new GameProfile(uuid, name)` is always the default skin: SkinManager only unpacks
  the profile's own textures property and never fetches one. Fetch the textured profile (`ProfileViewerApi.fetchSkinProfile`)
  or use the tab list's `PlayerInfo`.
- **The last GUI row can hang off the window.** `Window` makes the GUI height the window height over the GUI scale ROUNDED
  UP, so on a window whose height is not a multiple of the scale (windowed 1440p) the bottom GUI row is partly off screen:
  the themed hotbar lost its bottom line (testkit 897 at 2560x1369 GUI 3). Clamp bottom-anchored pixel boxes to
  `getWindow().getHeight()`.

### Croesus overlay

- **State read in a tick and drawn in frames is stale for every frame between a packet and the next tick.** Croesus read
  its run states at END_CLIENT_TICK, so the frames after the menu content landed drew opened runs unhidden ("a quick flash
  showing the old run stuff"). It works them out in the frame from the slots on screen, cached on the screen and an identity
  fingerprint of the run slots' stacks. Testkit 883 puts the content in after the mod's tick and checks every frame (old jar:
  10/12 unhidden on the content frame).
- **A Croesus run is not identified by its party.** Same floor, same party all day, so name + floor + players was shared by
  many runs and one run's chests re-colored or hid every run like it (884 on the old jar: all four runs flipped 2<->3 six
  times). Runs are known by that signature plus the instant their chests expire (`croesus/CroesusRunMemory`, saved in
  `killer560smod-croesus-runs.json`), matched per page by the list's shift (888-890). A claim is remembered from the chest
  screen itself, because the list head can lag it by a refresh (886).
- **A clicked slot is empty until the server answers.** A left click moves the item onto the cursor client-side, so a frame
  later the page looked one run short and the next same-party run took the clicked run's place (889). Croesus keeps the
  previous read while the cursor carries something.
- **Judge "can you tell these colors apart" with CIEDE2000, over the real background, with a CVD simulation.** CIE76 rated
  the old gold needs-a-key border 39 from the unclaimed orange, which he could not tell apart; CIEDE2000 puts the gold-vs-gold
  "not looked at" pair at 19.8 and orange-vs-gold for a deuteranope at 11.6. Magenta and blue collapse for a protanope
  whatever the shades, so the key mark and its plate carry that pair (testkit 898 samples those pixels).
- **Distinct is not enough when most of a page is one state.** Five well-separated colors still hid the runs needing action
  under 17 bright "not looked at" outlines. 898 also scores salience (frame contrast and chroma against the page with
  highlights off): action states must beat the quiet ones 2x; the old palette scored 0.77.
- **A testkit "restart" that reloads one store is not a restart.** 890 reloaded the run memory and passed while the totals
  lived in a second static map; on a real restart they came back "18+ / 60, Pages seen 1 of 3". Page counts are saved with
  the run memory, and 890 clears any leftover static.

## Screens and input

- **A screen opened straight from a chat command is closed again by the chat screen.** `/invsort load` opened the inventory
  inside the command, and `ChatScreen` sets the screen to null after the command returns ("could not open your inventory").
  Open from `client.execute(...)` (as every other command here does) or from the next tick once no screen is up.
  `client.execute` only defers because the real chat Enter is itself a `Minecraft.execute` task (KeyboardHandler wraps GLFW's
  key callback in one); the testkit's `TestInput.pressKey` is not a task, so there the execute runs at once and /killer560
  never opened the menu in the 1900 video; it does in game and through testkit 1920's real-key path.
- **A raw-polled key must also ask "was he typing a tick ago?".** Key presses reach the open screen before END_CLIENT_TICK,
  so the press that ends a "Press any key..." capture finds the capture already over at the poll, and the Edit HUD key
  opened the editor while being bound (testkit 862). `util/TypingDetector` decides typing (a focused EditBox/text area in
  the screen's widgets, chat/sign/book screens, the recipe-book and creative searches through accessors, the mod's own
  search boxes); the key poll skips on typing now OR at its previous run.
- **A key that CLOSES a screen also opens it again** if the opener polls raw keys at END_CLIENT_TICK: at the poll there is no
  screen and the key reads as a fresh edge. The closer marks the key held (`PeekChatFeature.closedByKey`) so the poll waits
  for a release (testkit 1314).
- **A ChatScreen subclass must not call `ChatScreen.removed`** if it has no text of its own: vanilla's `removed` discards the
  saved draft when the box is blank and overwrites it otherwise (javap both versions), so Peek Chat would eat a half-typed
  message. `PeekChatScreen.removed` only resets the scroll; testkit 1314 checks the draft survives.
- A harness key press never reaches `InputConstants.isKeyDown` (it polls GLFW), so a hold-key mode needs a test hook
  (`keyHeldForTest`, [TESTING-HOOKS.md](TESTING-HOOKS.md)). An inventory screenshot diff must look only at the slot grid: the
  player model turns with the cursor (602 px changed in testkit 961's first run).
- **A settings preview that shares only the formatter still drifts if the real path gates on data the preview always has.**
  Party Finder's preview and tooltip both called `memberLine`, but the tooltip skipped it until the stats service answered,
  and api.docilelm.top/v2/dungeons returned `{"result":{}}` for every name, so Hypixel's menu was never styled while the
  preview always was. Both go through `PartyFinderOverlay.styleLines` (missing stats render as `?`; testkit
  236-menu-partyfinder-style). Stats now come from `ProfileViewerApi` (SkyBlockPV backend); docilelm is no longer called
  (400-menu-partyfinder-stats serves real trimmed answers through a loopback fake).
- **Vanilla's chat hover only finds links the server made clickable.** `ActiveTextCollector.ClickableStyleFinder` (what
  `ChatScreen.mouseClicked` uses, javap 26.1.2 and 26.2) returns a style only when it carries a click event, and players'
  links on Hypixel arrive as plain text. Image Preview (2026-10-27) tries the finder first, then records each placed row
  (pose, x, text) through the same `captureClickableText`, sums per-code-point widths (`FormattedCharSequence.codepoint`) to
  get the character under the cursor, and searches the WHOLE message (`GuiMessage.Line.parent()`, rows located in
  `parent.content()` in order), so a long URL wrapped over two rows is found from either row (testkit 1072). A URL regex
  whose host must end in letters misses `127.0.0.1` (the first 1072 run): allow an IPv4 host alternative.

- The themed vanilla button box (`MainMenuButtonMixin`) is near black, and the pause menu's icon row (bug, feedback,
  Friends, Player Reporting; the same `SpriteIconButton$CenteredIcon`s on 26.2 and 26.3) draws sprites whose outlines
  are near black too (bug legs 11,25,30; friends outline 30,30,31; Amber face 31,25,19), so the icons lost their edges and
  the row looked "a hair weird" (killer560, 2026-11-01). The geometry was identical to vanilla (testkit 1101-pause-layout
  dumps every widget box with and without the mod). Those sprites (`pause_menu/*`, `friends/friends`) now get a face lifted
  toward the theme text color on dark themes (`MainMenuTheme.iconWell`); compare a sprite's own palette against the face
  before theming another icon button. The owner then chose to drop the row: Hide Pause Menu Icons (default ON,
  `mainmenu/mixin/PauseMenuIconRowMixin`) skips the grid `addChild` of the `LinearLayout`, which leaves no hole because
  `GridLayout.RowHelper` only advances when something is added (rows stay 24 apart on 26.3 and 26.2, testkit 1101).

## World rendering

- **A render-only block swap goes in the chunk mesher, not the world.** `SectionCompiler.compile` calls
  `ModelBlockRenderer.tesselateBlock(BlockQuadOutput, float, float, float, BlockAndTintGetter, BlockPos, BlockState,
  BlockStateModel, long)` once per block, with the state and its position (same descriptor on 26.3 and 26.2, javap
  2026-11-01); a `@WrapOperation` there that passes another state and `blockModelSet.get(thatState)` draws the block as
  something else while the client world, collision and the server keep the real block (Mithril As Stone,
  `miningqol/mixin/MithrilStoneSectionMixin`). It runs on the section-compile threads, so its decision is a volatile snapshot.
  To re-mesh what is already built, `ClientLevel.setSectionRangeDirty(minSecX, minSecY, minSecZ, maxSecX, maxSecY, maxSecZ)`
  (both versions). `level.setBlock` (I Hate Diorite's way) changes the client world instead and cannot be undone cleanly.
- **To replace a vanilla HUD piece, wrap its Fabric layer instead of mixing into `Gui`:**
  `HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> ...)` (identical in Fabric API 0.155.2+26.1.2
  and 0.160.0+26.2, javap) calls the vanilla layer only when you do, so there is no 26.2 descriptor to break (Custom
  Crosshair). F1 is `McCompat.hudHidden`, because `options.hideGui` is gone on 26.2. To draw in screen pixels from any pose,
  `pose().identity()` then `scale(1f / guiScale)`: an int `fill` is then one framebuffer pixel.
- **Fabric's `addLast` HUD layers draw ABOVE an open in-game screen's dim.** With a screen whose `isInGameUi()` is true,
  `Gui.extractSubtitleOverlay` defers the SUBTITLES layer, and Fabric's layers added after it, into the open screen's
  background (`Screen.extractBackground`, after the dim gradient; javap 26.1.2). Something meant to sit behind an inventory,
  like the hotbar, is attached to a vanilla layer before SUBTITLES that always runs (an attached element runs only where its
  parent does; after `EXPERIENCE_LEVEL` the bars vanished in creative). Health and Mana Bars are
  `attachElementAfter(MISC_OVERLAYS)` (testkit 441: no bar pixel beside the open inventory; 443/444 draw in creative).
- **The first-person hands are drawn INSIDE `GameRenderer.renderItemInHand`, under its model-view push, on both versions.**
  26.1.2's `renderItemInHand` only submits, but `ItemInHandRenderer.renderHandsWithItems` ends with its own
  `renderAllFeatures()` + `endBatch()` (26.2 flushes in `renderItemInHand`), with the `Matrix4fc` argument on the model-view
  stack. Anything drawing a hand item again later must push that matrix: Held Item Outline's mask copy without it never
  reached the screen (testkit 671). `McRender.drawHandMask` does it.
- **26.2 is reverse-Z.** `GameRenderer.renderLevel` clears depth to 0.0 before the hands (26.1.2: 1.0) and LevelRenderer's
  always-on-top pass clears its own target to 0.0 (javap). An off-screen target cleared to 1.0 on 26.2 silently rejects every
  fragment; Held Item Outline's mask clears to `McRender.FAR_DEPTH`.
- **26.2 sorts QUADS only.** `StagedVertexBuffer.appendDraw` throws "Cannot sort draw with LINES" for any non-QUADS topology
  given a sorting (javap 26.2), so a `RenderType` with `.sortOnUpload()` on `LINES_SNIPPET` crashes the first frame it draws
  (26.1.2 accepted it). Sort lines through `McRender.sortLinesOnUpload`. `DEBUG_FILLED_SNIPPET` is QUADS on 26.2.
- **On 26.2, geometry submitted from `LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES` is dropped on some frames and drawn a
  frame late on others**: that event runs after the frame's submitted nodes are drawn. The Etherwarp Overlay box lost its
  outline on 4 of 27 frames walking into a gold block (testkit 1322) until it moved to `COLLECT_SUBMITS` (0 of 27, both
  versions). The camera was never stale. 36 other registrations still use AFTER_TRANSLUCENT_FEATURES.
- **On 26.1.2 a block entity paints over anything drawn into the immediate buffer source before the solid features.**
  `COLLECT_SUBMITS` fires before `renderSolidFeatures`, and McRender's 26.1.2 path writes into `context.bufferSource()`,
  whose shared buffer is drawn the moment the feature renderers ask for their first buffer; neither `LINES_TRANSLUCENT` nor
  `debugFilledBox` writes depth, so a chest, ender chest or trapped chest model drawn afterward covered the Etherwarp Overlay
  box (testkit 1324: Filled 0/50, Outline 0/63 on a chest; 26.2 never had it). The overlay submits through
  `context.submitNodeCollector().order(0).submitCustomGeometry` (both versions), which `CustomFeatureRenderer` draws after
  the model parts of the same pass. Any other `COLLECT_SUBMITS` drawer that must show on a block entity needs the same.
- **An outline-only box shows nothing on a face you are pressed against.** Its four edges leave a 70-degree view once the
  camera is closer than ~0.4 of a block to the face, so the Etherwarp Overlay's Outline drew no pixel walking into a wall;
  every other face drew at every angle, distance and version, with Sodium too (LEQUAL/GEQUAL with no bias is enough). He
  rejected a tint and a shrunk outline (killer560, 2026-10-13: "I want the full outline and if you can't see an Edge then
  don't draw that part of it"), so Outline is the box's own edges and pressed against it you see nothing (testkit 1321,
  1322). Sodium 0.9.2 breaks the gametest API's `takeScreenshot` ("Global terrain uniforms have not been updated"); read the
  main target back with `Screenshot.takeScreenshot`.
- **On 26.1.2 every switch of render type in a world render event was a draw call.** None of the mod's types is a fixed
  buffer of the level `BufferSource`, so they share one buffer and `getBuffer` for another type ends the previous batch
  (javap `MultiBufferSource$BufferSource.getBuffer`, `lastSharedType`): Dungeon ESP's fill + outline per mob made 40 draw
  calls a frame for 20 mobs and 89-112 KB (testkit 95-perf-world). `McRender.inCameraSpace` writes into the mod's own source
  inside AFTER_TRANSLUCENT_FEATURES / _TERRAIN, every type a fixed buffer, drawn once after the event (ESP: 2 draw calls,
  6.6 KB); 26.2's submit collector already groups by type. The probe's CPU microseconds did NOT drop measurably, so judge
  such a change on draw calls and bytes; the probe books that draw under `compat.McRender`, so add it back when comparing
  with main. A per-item loop feeds a `util/BoxBatch` rather than one `inCameraSpace` per box (each call allocates its lambda
  and color array; the ESP's per-box AABB/Vec3 were most of its 112 KB).
- **Writing world vertices through VertexConsumer's Pose/Matrix/float-color helpers allocates per vertex** (javap both
  versions; JFR ar-render-1016): `addVertex(Matrix4fc|Pose, x, y, z)` and `setNormal(Pose, ...)` each make a `new Vector3f`,
  and `setColor(r, g, b, a)` is four `MemoryUtil.memPutByte`, each an FFM segment object on Java 25. BufferBuilder overrides
  none, so the same JOML calls into scratch vectors plus `setColor(int argb)` (one `memPutInt`) write identical vertices:
  Auto Routes' bare node shapes went from 25.8 KB to 0.2 KB a frame. `WorldRenderUtils` and other world draws still use the
  helpers.
- An entity made with `EntityType.create` and never added to a level needs `setId` before any renderer sees it: on 26.2
  `getId()` throws "Tried to access entity ID before ID assignment" (26.1.2 does not). Play as Mob's stand-ins drew nothing on
  26.2 until they got negative ids (testkit 1018-mob-all: 78 of 78 broken, then 0).
- `ThemedEditBox` (the menu-theme text box) is an unbordered EditBox placed 5 px inside the box it was given, so its text line
  is the given width minus 10 and `getWidth()` reports that line. A box sized for a vanilla bordered box loses 2 px: the
  Profile Viewer's name box went 96 -> 94 px and no longer held a 16-character name until its sidebar widened to 120
  (testkit 693, 2026-10-26).

## First-person hands and the GPU API on 26.3

All javap on the 26.3 merged jar (2026-11-01, port-263-render-1101). Runtime unverified until a 26.3 boot.

- **26.3 removed `ItemInHandRenderer`.** Drawing is `FirstPersonHandsAndItemsRenderer` (`GameRenderer.
  firstPersonHandsAndItemsRenderer`), working from `PlayerRenderState` + `FirstPersonHandsAndItemsRenderState`; the
  equip state (hand heights, `shouldInstantlyReplaceVisibleItem`, `tick(LocalPlayer)`) moved to
  `net.minecraft.client.player.FirstPersonHandsAndItems`. `submitArmWithItem` keeps `attack` as its third float and
  submits the item through `ItemStackRenderState.submit(PoseStack, SubmitNodeCollector, int, int, int)` (two call sites,
  same as 26.2's two `renderItem`); there is no `renderItem` to hook. The view sway is `PoseStack.rotateDegrees(Axis,
  float)` (angle at index 1), called exactly twice in `submitHandsWithItems`. `applyEatTransform` is now
  `(PoseStack, float, HumanoidArm, float, int)`. Every mixin on these (Held Item Transform, Held Item Outline, Object
  Hider) has one copy per version directory; Held Item Transform's equip hooks are their own mixin
  (`HeldItemEquipMixin`) because they target a different class on 26.3.
- **An empty hand never reaches `swingArm`** (26.2 and 26.3): the bare arm is drawn by `renderPlayerArm`, which has its
  own swing translate; only an item goes `submitArmWithItem` -> `swingArm(float, PoseStack, int, HumanoidArm)`, whose one
  `PoseStack.translate` Swing Range scales. A test of a hand-item hook must put an item in the hand, and turn Skyblock
  Only off: `HeldItemConfig.isActive()` includes `SkyblockGate`, so in singleplayer every hook returns early (testkit
  1101-ds-held-item read 0 hook calls until both were done, 2026-11-01).
- **The hands moved from `renderLevel` to `GameRenderer.render3dHud`**, which calls `renderItemInHand(CameraRenderState,
  PlayerRenderState, GpuTextureView depth)` and then `ScreenEffectRenderer.submit(float, SubmitNodeCollector,
  PlayerRenderState, CameraRenderState, boolean)`. The hand view matrix is `camera.viewRotationMatrix` (pushed on the
  model-view stack; the pose starts from its inverse). Depth is still reverse-Z (cleared to 0.0).
- **`RenderSystem.outputColorTextureOverride`/`outputDepthTextureOverride` are gone**, and `FeatureRenderDispatcher.
  renderAllFeatures` is static `(RenderPass, PreparedFrame)`. To draw a SubmitNodeStorage into your own target, copy
  `renderItemInHand`: `prepareFrame(storage)` (drains it), `createRenderPass(label, colorView, Optional.empty(),
  depthView, OptionalDouble.empty())`, `RenderSystem.bindDefaultUniforms(pass)`, `renderAllFeatures`, close the pass,
  then the frame (`McRender.drawHandMask`). The dispatcher reuses ONE PreparedFrame: never prepare while vanilla holds
  one open.
- **`GpuDevice.precompilePipeline` and `CompiledRenderPipeline.isValid` are gone.** `RenderSystem.
  getCompiledPipelineNullable(pipeline)` compiles a cache miss synchronously through `PipelineCache.get` and returns
  null on failure (`McRender.pipelineCompiles`). `TextureTarget` takes `(String, int, int, GpuFormat color, GpuFormat
  depth)`; null depth means none (MainTarget: RGBA8_UNORM, D32_FLOAT). `SubmitNodeStorage.submitItem` takes `ItemQuads`
  (record of all/solid/translucent; build one with `ItemQuads.split(list)`) where 26.2 took `List<BakedQuad>`.
- **Swing animation:** `LivingEntity.getAttackAnim(float)` is `getSwingAnimation(float)` (`McRender.swingProgress`);
  `ArmedEntityRenderState.attackTime`/`attackArm` became `swingAnimation`/`currentSwing` (`McRender.copySwing`);
  `getCurrentSwingDuration()` became `getModifiedSwingDuration(SwingAnimation)`.
- A mixin handler that takes only `CallbackInfo`/`CallbackInfoReturnable` matches both versions' descriptors, which is
  how `HeldItemSwingDurationMixin` stays one file. A mixin listed in a shared `*.mixins.json` must exist in BOTH version
  directories (or in src/main), so a 26.3-only hook that needs a new class gets a main-dir mixin that is a no-op on 26.2
  (`HeldOutlineItemStateMixin`).
- **Mixin strings are NOT remapped.** `remap.txt` rewrites dotted class names in Java code only, so a slash descriptor in a
  mixin string written for 26.2 (`Lcom/mojang/blaze3d/pipeline/RenderPipeline;`) matched nothing on 26.3, silently: the
  menu theme's widget redirects, Menu Fade, the inventory theme's hotbar and background, Main Menu textures. Each now
  lists both paths (`method = {"...blaze3d...", "...renderpearl..."}`; an @Inject `at = {A, B}`). `@Redirect`,
  `@ModifyArg` and `@ModifyVariable` take ONE `@At`, so a redirect gets a twin handler on the 26.3 target that calls the
  original. `GameRenderer.render()`/`renderLevel()` and `GuiRenderer.draw()` lost their arguments on 26.3, so Lag
  Display's frame stats, Motion Blur's frame hook (per-version copy; `gameRenderState().shouldRenderLevel` replaces the
  old `renderLevel` argument) and the scoreboard blur needed the same. The scoreboard blur's `draw(GpuBufferSlice)` was
  26.1.2's: on 26.2 too it matched nothing and the blur disabled itself every session until 2026-11-01.
- **A handler whose arguments no longer match the target is a load-time crash, not a silent miss**: Mixin checks the
  handler descriptor whatever `require` says. 26.3 changed `ClientChunkCache.replaceWithPacketData` to `(int, int,
  ClientboundLevelChunkPacketData)` and `EntityRenderDispatcher.shouldRender` gained a `float`; the Chunk Cache handler
  now takes only the callback info, Object Hider's has one copy per version. `tools/mixin-target-survey.py` checks names
  only; `tools/mixin-descriptor-check.py <classes> <roots>` checks every full descriptor in mixin strings and
  `tools/mixin-handler-check.py` every @Inject handler's arguments against the target's overloads (both read a jar or an
  extracted class dir; generic-erasure lines such as `Packet<?>` vs `Packet` and `AvatarlikeEntity` vs `Avatar` are noise,
  compare against the other version's run).

## The mod's GLSL shaders on 26.3 (2026-11-01)

- 26.3 compiles every core and post shader through SPIR-V. A shader without `#extension GL_ARB_separate_shader_objects :
  require` and an explicit `layout(location = N)` on each vertex input, varying and fragment output fails with
  "'location' : SPIR-V requires location for user input/output", and `#moj_import <...>` is now `#include <...>`.
  Vertex inputs take the attribute's index in the pipeline's vertex format (POSITION_TEX_COLOR: Position 0, UV0 1,
  Color 2; POSITION_COLOR: Position 0, Color 1), as vanilla's own 26.3 shaders do. The DynamicTransforms block also
  reordered: ModelViewMat, TextureMat, ColorModulator, ModelOffset.
- One failing "required" shader fails the WHOLE resource reload: the game drops every resource pack, reloads, fails again,
  and is left without atlases, so the first particle crashes the client (NPE in ParticleResources$MutableSpriteSet) and
  menus throw "atlas is not initialized". The first 26.3 boot did exactly that. So the shaders live per version in
  `src/mc26_3/resources` and `src/mc26_2/resources` (build.gradle adds the compat resources dir); the 26.3 copies were
  generated from the 26.2 ones by adding the extension, the locations, `#include` and the new block order.


- **26.3: nothing in a world render event may bake a glyph or upload a texture.** 26.3 opens ONE render pass around
  `executeSolid` and `executeClassicTransparency`, and Fabric's `AFTER_TRANSLUCENT_FEATURES` / `AFTER_TRANSLUCENT_TERRAIN`
  fire inside it (javap, 26.3 LevelRenderer and fabric-rendering-v1 27.2.0). `Font.width` bakes a code point the first
  time it sees it, the bake calls `copyBufferToTexture`, and `FrontendCommandEncoder` throws "Close the existing render
  pass before performing additional commands" - FeatureGuard then disabled Auto Routes and AP3 on their first frame
  (tkport263 U-2/U-3, 2026-11-01). World renderers measure through `McRender.textWidth(font, text)`: outside a pass it is
  `font.width`, inside one it answers from widths measured earlier and queues a new string for the next COLLECT_SUBMITS
  or client tick (one frame's estimate; Auto Routes' mesh rebuilds when `textWidthEstimates()` moved). `submitText` is
  safe: its glyphs are prepared in `prepareFrame`, before the pass. `COLLECT_SUBMITS` runs outside the pass.

- **26.3: a pipeline built from `RenderPipelines.LINES_SNIPPET` needs `McRender.linesColorTarget(...)`.** 26.3's
  LINES_SNIPPET has no color target state (vanilla adds one per pipeline), 26.2's carries `ColorTargetState(TRANSLUCENT)`.
  A pipeline with none crashes the GAME (not just the feature: vanilla draws it) on its first draw in the main pass,
  "Render pass color attachment count must match pipeline color target state count" - Secret Waypoints' through-walls
  lines on 2026-11-01, the first 26.3 run that ever drew one (96-ar). DEBUG_FILLED, GUI and GUI_TEXTURED snippets carry
  the same target on both versions (javap). Wrap any new `RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)`.
- **26.3: the local player's own render state must stay an `AvatarRenderState`.** 26.3's `LevelExtractor.extractPlayerState`
  (new; 26.2 has none) extracts the local player a second time through `EntityRenderDispatcher.extractEntity` for the
  first-person hands and throws `IllegalStateException("Expected an AvatarRenderState for the local player")` otherwise.
  Play as Mob swapped that call too and crashed the render frame with My Mob set (UI 1018-mob-own, 2026-11-01).
  `MobSkinLocalPlayerStateMixin` flags the call so the swap skips it; anything else that replaces a player's state at
  `extractEntity` must do the same.
- A screen that hides the vanilla menu must never draw NOTHING. The custom Spirit Leap menu (spiritleap/SpiritLeapOverlayFeature)
  hides the chest and drew nothing until a head had settled, so a leap menu with no heads was an open screen that looked like
  none: mouse freed, camera and movement dead until Escape or the inventory key closed it - the shape of killer560's 2026-11-01
  Fast Leap report (not yet confirmed as its cause on Hypixel). It now draws a notice after 300 ms (testkit 1344, lastView EMPTY).
- **Per-frame screen state never hangs off a background hook another feature cancels.** The Terminal Solver refreshed at
  the HEAD of `ContainerScreen.extractBackground`; Inventory Theme cancels that same method at HEAD, and with it on
  (his 26.3 Untested Cheat config) no terminal was ever tracked - no solver, Custom GUI or Auto Terminals (doc-terms-1101,
  testkit 1101-menu-term-instcfg with that config: all six types untracked on main, tracked after). The refresh now runs
  at the HEAD of `Screen.extractRenderStateWithTooltipAndSubtitles` (final, calls extractBackground first; javap 26.3
  and 26.2), which nothing cancels. Five features cancel `extractBackground` at HEAD (Inventory Theme, Storage Overlay,
  AH/Bazaar reskins, Spirit Leap): read state anywhere else.
- The crosshair is `Hud.extractCrosshair(GuiGraphicsExtractor, DeltaTracker)` (private, same descriptor on 26.3 and 26.2,
  javap 2026-11-01; `Gui` no longer has it). A `pose().pushMatrix()/translate` at HEAD and `popMatrix` at RETURN moves
  exactly the crosshair and its attack indicator (Etherwarp Overlay's Crosshair On Server Aim, testkit 1101-ew-crosshair:
  measured -8.82 GUI px against a projected -8.81). `Camera.getFov()` is the vertical fov the level projection uses.
- `Map.of` takes at most ten key/value pairs; an eleventh is a compile error (no varargs form). `SearchAliases` hit it
  on 2026-11-01 and is `Map.ofEntries` now; use that for any alias or lookup table that grows.
- The menu sidebar's section rows (Legit, groups, Cheat) are read from the grid's HEADER items, so `ModScreen.rebuild`
  must run `buildItems()` BEFORE `layout()`; a new category needs nothing. A testkit harness key press never reaches the
  GLFW poll `KeyUtil.isBindDown` reads: in-game keys are tested through a `heldForTest` hook (ModuleKeybinds,
  LoadoutSwapper), menu key capture through `Screen.keyPressed` with `McKeys.nat`.
- Video Browser: YouTube Shorts loop, so a `<video>` never fires `ended`; Auto Scroll counts a `timeupdate` that finds
  the video under 1 s after one in its last second (caught in capture phase: `timeupdate` does not bubble). Checked in
  headless Chrome with ffmpeg clips (killer560s-mod-logs/docmenu-1101-vb/endhook_test.py).
