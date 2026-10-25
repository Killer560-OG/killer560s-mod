# Mod menu and main menu lessons

The mod's own screens and menu (layout, rows, sliders, tooltips, themes, animation, cards) and the painted main menu.
Problem, then fix; verified only. Part of the GUI lessons: [LESSONS-GUI.md](LESSONS-GUI.md); HUD elements are in
[LESSONS-HUD.md](LESSONS-HUD.md).

## Mod screens: scale, input and layout

- The mod's own screens are laid out at `guiSize / factor` and drawn under a pose scale (`hud/mixin/AutoScaleScreenMixin`);
  every GUI mouse coordinate, drag deltas and the render mouseX/Y included, passes through the static
  `MouseHandler.getScaledXPos/YPos(Window, double)` (javap both versions), which `AutoScaleMouseMixin` divides. A mod screen
  takes its size from `this.width/height`, never `getWindow().getGuiScaledWidth()`, and reads the mouse from its event
  arguments, never `mouseHandler.xpos()`. Container reskins are not mod screens (see [LESSONS-GUI.md](LESSONS-GUI.md)).
- **A one-unit grid on a mod screen is not even.** A mod screen's GUI unit is guiScale x Auto Scale screen pixels (2.25 at
  1920x1080 GUI 3), so the map designer's 1-unit lines came out 2 and 3 px wide and its cells 112 and 113 px apart (testkit
  75-sim-designer-grid). Anything that must line up to the pixel is laid out in its own whole-pixel unit
  (`SimMapEditorScreen.layoutGrid`: pose scaled by `gk / pixelsPerUnit`, `gk = round(pixelsPerUnit)`), mouse hits mapped
  through the same numbers.
- In the mod menu a drag is delivered only to the widget that took the PRESS (`ContainerEventHandler` routes `mouseDragged`
  and `mouseReleased` to the focused child, javap 26.1.2), and every change rebuilds every widget. So a reorderable list is
  ONE widget with its scroll kept outside it (`gui/DragListWidget`), not a widget per row. `ModScreen` scrolls the page on a
  wheel turn before any child sees it; a widget that scrolls itself implements `gui/WheelTarget`.
- **Two mod-menu rows on one rectangle look like the top one and act as the bottom one.** ModScreen's content pane draws
  children in order (last on top) but gives a press to the FIRST under the cursor. Breaker Aura's `y += 20 ... y -= 20`
  side-step left Cooldown on Side Reach's rect and Auto Swap on Multi Break's, so dragging "Cooldown" moved Side Reach.
  Testkit `386-ui-sliders` checks every tab, toggles flipped, for overlapping rows.
- **A hand-drawn card under real buttons in a menu page is a `gui/PaintedPanel`** (Profiles page): because the content pane
  presses the FIRST child under the cursor, the card is `active = false` (its `mouseClicked` declines and the press falls
  through) and `hoveredChild` skips it when a real widget sits on it, or the buttons lose their tooltips. (Its name box's
  shadow is in [LESSONS-GUI.md](LESSONS-GUI.md).)
- **A themed text box is an unbordered EditBox inside the box you draw, so widen its hit area.** Unbordered, vanilla draws the
  text at the box's top-left and only that line takes clicks; `AbstractWidget.mouseClicked` asks `isMouseOver` (javap
  26.1.2), so `gui/ThemedEditBox` overrides it with the drawn well.
- **A table column sized by a fixed number overlaps the moment its heading grows.** Load a Room's columns were 30-64 units,
  and "Crypts ▼" (the sort arrow adds a space and a glyph) is 44 against Crypts' 34. `SimMenuScreen.columns()` measures
  each heading with either arrow and every listed cell, keeps 8 units between columns, gives Name the rest with "...", and
  drops whole columns before anything can touch (testkit 97-sim-menu-tidy).
- A field made lazy behind a getter must not be read raw anywhere else. `GreenTheme.gradient` became null-until-`gradient()`
  (d7bdc71e) while `background()` still switched on the field, so the first Green menu draw threw NPE and the themed main
  menu turned itself off for the session. Grep the class for the bare field.

## The menu (Option A): tabs, rows and drawers

- **The Option A menu draws the tabs' own widgets as rows, centrally.** `SettingsButtonWidget`, `ThemedSliderButton` and
  `RangeSliderWidget` take the row look (label left; switch, value pill or track right) only while `ModScreen` is the screen
  (`SettingsButtonWidget.inMenu`); every other screen keeps the plain box. The look is read from the message:
  `"Label: ON/OFF"` is a switch, a `cycling(...)` button a pill, `"Label: value"` a chip, `▶/▼` a section header, anything
  else a raised button. A tab never needs to know.
- **The menu builds every tab off-screen** (card switches, the on/total counts, the search), so a one-shot flag a tab
  consumes in `buildWidgets` is eaten by whichever build runs first: `TranslateTab.openPickerOnNextBuild` (`/language`)
  opened the picker in a throwaway build. The real drawer is built first on open, and the Basic/Advanced strip's room is
  learned from that real build.
- **Never find a menu tab by its top-level index.** A testing build's `TestingMenu.arrange` moves every untested tab into
  Untested, Profiles included, so Home's Profiles button (`indexOf(ProfilesTab.class)`) did nothing there. Open the leaf from
  `allLeaves()` with `openFeature(leaf)`: a page normally, a drawer in Untested (testkit 948).
- **A mod-menu tab's buttons act on the config object they were built with.** Health and Mana Bars' lambdas capture
  `PlayerStatsConfig.getInstance()`, and `PlayerStatsConfig.load()` (a profile apply) replaces the instance, so until the tab
  rebuilds a press changes the old object (testkit 893: Bar Style "did nothing" after a reload).
- **A drawer can leave out its card's switch row.** `InventoryThemeTab` builds the switch but never adds it to the widget
  list and returns it from `masterToggle`/`masterToggles`, so the card and the drawer title still flip the setting
  (killer560: "if the setting itself is on then that is on"). Nothing reading a drawer's first row may assume it is the switch.
- **A narrow drawer stacks side-by-side rows** (`ModScreen.stackRows`): only a line made purely of setting rows sharing one y,
  with nothing else overlapping it, is stacked full width, and everything below moves down by the same amount; a line with a
  text box, a preview or a list keeps its layout. A half-width row with no partner (hidden, like Door Keys' Show Tracer with
  the tracer off, or never planned, like Names Holding Leap) takes the drawer's full width, stacked or not (`widenLoneRows`;
  testkit 1015-ui-switch-align sweeps every drawer with each switch flipped).
- **The menu "remembered" its drawer, but Escape closed the drawer first,** so the menu could not be closed with a drawer
  open from the keyboard. Escape closes the menu and the X closes the drawer (testkit 942/986).
- `ModScreenDriver`'s merged layouts (a group like Secrets or Puzzle Solvers) only take real screen clicks on the drawer
  opened last, so 806 reports their cyclers as "single value"; 987 checks the pill halves on one drawer directly.
- **A card's description has to be written for the narrowest card.** The grid lays cards out between
  `FeatureCards.CARD_MIN_W` (104) and about 131 wide, so a card has 90 px of text: three lines (~38 characters) when its name
  takes one line, two (~26) when it takes two. The first sentence of the master toggle's tooltip (60-150 characters) was cut
  by `MenuStyle.wrap` with "..." on all 169 cards (testkit 1060). The long tooltip is the hover text (`FeatureCards.longDescription`);
  `FeatureCards.cardText` is the one place a card's text is laid out, and `autoCut()` lists any tab the menu had to trim,
  which 1060 requires to be empty. A new tab needs a blurb in `CardBlurbsData` (or `cardDescription()`).

## Sliders

- **A restyled slider must map the mouse onto the track it draws.** `AbstractSliderButton.setValueFromMouse` is private and
  maps the WHOLE widget width (`getX() + 4`, `width - 8`; javap both versions), so a row whose track is its middle third put
  the knob a third of a row from the mouse. `ThemedSliderButton` overrides `onClick` (super for the drag flag, then
  `setValue` at the track) and `onDrag`, from the same `trackRange()` its drawing uses.
- **A slider's knob position must come from what was SAVED, not from the mouse.** 160 menu sliders each round in their own
  `applyValue` (whole numbers, steps of 25 or 50k, tenths, a list) and none told the widget, so 186 knobs rested between two
  values (testkit 1598). `ThemedSliderButton.setValue` (vanilla's mouse and key paths both call it) asks
  `ModScreen.twinSliderValue`: the tab is built again off-screen and the same row's constructor turns the saved setting into
  the knob position, used only when its text matches. A slider must save what its label says: the sweep found a label
  showing the unsnapped drag ("Width: 0.51x" saved as 0.50) and Turn Speed and Timer Text Scale storing more precision than
  they show.
- **A widget that resizes the screen it sits in can stay live if the drag is handed over.** HUD Scale sizes the mod's
  screens (`AutoScale.menuFactor`), and `AutoScaleScreenMixin` relays a screen out when its factor changes, rebuilding every
  widget, the dragged slider included. The slider keeps its press-time track in WINDOW units (`ThemedSliderButton.LiveDrag`,
  layout x times the applied factor) and `ModScreen.resumeLiveDrag` re-focuses the rebuilt slider, so the menu follows the
  drag without the value feeding back through the moving track (testkit 985; the small test window snaps every HUD Scale
  under 100% to one factor, so 985 measures at 1920x1080). ModScreen's sidebar does not scroll, so the menu factor is capped
  where the layout canvas stays 430 tall (at 280 the sidebar ran out of the panel at HUD Scale 200%, testkit 615).

## Tooltips

- **Moving or renaming a setting silently orphans its scoped tooltip.** `SettingTooltips.describe` looks up
  `"<sub-tab name>/<label>"` first and falls back to the bare label, so a `d.put("experiments/set", ...)` entry stops being
  found when that button is built by another tab: no error, the hover text changes or disappears. Re-key the entry in
  `SettingTooltipsData` in the same session as the move. Category-scoped keys (`dungeon/mode`, `hud elements/scale`,
  `helpers/mode`) survived the category regroup only because `MenuCategories` remembers each tab's OLD category and the
  drawer looks tooltips up with it (`legacyCategory`); rename a key only if the text should change.
- **An unscoped key is the row's label alone, so a new label can take another row's text.** The per-solver rows were first
  called "Highlight Style"/"Highlight Through Walls"; "highlight style" was already Breaker Aura's bare key and Tic Tac Toe
  has a row of that name, so the new tooltip overwrote theirs. `grep '"<label lowercased>"'` in `SettingTooltipsData` before
  naming a row.

## Themes and palette

- **The menu's colors are one palette derived from five.** `MenuStyle`'s colors are mutable statics that `MenuStyle.use()`
  re-derives from the active `MenuTheme` (accent, panel, card, text, on-switch) whenever `MenuConfig.version()` moves, so a
  Custom set gets the same bevels, wells and hover shades as a preset; a panel above mid luminance switches on the light
  treatment (gray outlines, shadows at a third, no text shadow). A new piece of the menu takes its colors from those statics;
  a literal is a color the theme cannot change. Danger red and Discord/GitHub's own colors are the only fixed ones.
  `MenuStyle.use()` reads the open `MenuThemedScreen`'s `themeId()` through `MenuTheme.forScreen`, so every helper that calls
  `use()` gets that screen's palette.
- **A tab's `StringWidget` colors its own text** (§ codes, drawn through `visitLines`, left-aligned at `getX()`), so a light
  theme cannot reach it: the drawer pane draws section headings itself in the accent / cheat red, and on a light theme every
  other line too, with the washed-out codes (§7 §f §a §b §e) swapped for darker partners (`MenuStyle.lightReadable`).
  `ColorSwatch`'s square is a styled sibling, not a § code, so `getString()` loses it: the row reads the sibling's
  `TextColor` and paints the swatch.
- **A container reskin is not a MenuThemedScreen, so it sets its palette itself.** The Bazaar and AH reskins draw over a
  vanilla `ContainerScreen`, where `MenuStyle.use()` falls back to the menu's colors; they call
  `MenuStyle.use(MenuTheme.forScreen(id))` before reading the palette, and the next `use()` re-derives the menu's. Their
  frame is `ScreenChrome.frameTight`: the reskin keeps a 4-8 unit margin whose outer 6 units testkit 496 requires to be pure
  backdrop at Auto Scale 1.125, and a 3-unit shadow reached 2 pixel rows into it.
- **A per-screen or per-overlay Theme choice names a saved theme by a stable id and is listed live.** `ScreenThemes` offered
  the presets and ONE "Custom", so a saved theme never appeared, and a list captured when the tab was built would miss one
  saved after. A choice is `SAVED:<id>` (`MenuConfig.SavedTheme.id`, "T1"..., persisted; a file from before ids gets them in
  list order) and `ScreenThemes.choices()` is read when the button is pressed. Overlays over the game (`OverlayThemes`,
  setting "Menu Theme") use the same list, but "Default" means the overlay's own look and `OverlayThemes.of(id)` is null
  there; `MenuStyle.snapshot` derives a palette without changing the menu's statics. Testkit 1421 walks `OverlayThemes.ids()`.
- **One Theme per mod section.** A section's screens and overlays read and write the one choice stored under the section's
  id: `ScreenThemes.section` maps the ids that used to have a Theme of their own (`chest_profit`, `start_buttons` ->
  `croesus`; `experiments_start` -> `profit`; `leap_menu` -> `leaporder`) and `load()` moves an old saved choice to its
  section. A new part of a section gets a row in that map, not a second Theme button (testkit 1018-ui-theme-one-per-section).
- **Vanilla menus kept the old dark-and-orange look whatever the theme** because `mainmenu/MenuChrome` and `MenuWidgets` held
  it in `static final` colors. They are plain statics re-derived by `MenuWidgets.sync()` from the main menu's theme
  (`MainMenuLook`), checked at the top of every draw helper, so a theme change reaches an open pause menu on the next frame
  (testkit 1570: Back to Game 41,35,29 in Amber, 33,42,52 in Ocean, 231,229,226 in Light). Inside the mod's own screens they
  read that screen's palette instead; re-deriving `MenuStyle` there would repaint the mod screen's frame in the main menu's
  colors.
- **Color bands cut with `enableScissor` leave a gap under any non-integer scale** ("a line through Full Toggle"):
  `MenuStyle.gradientText` drew the text three times through scissor bands at 0/3/6/9, and the scissor rounds each band to
  whole pixels on its own, so at 1.5x (Profiles), or 1x/4/3x inside the HUD Scale pose, rows between bands drew nothing - one
  row at GUI 1, up to five at GUI 4 (testkit 1436: 20 of 40 draws, then 0). The whole text is drawn in the middle color
  first (136effd5). It is the only banded text in the mod; never split text by scissor without a full-text pass under it.

## Animation and frame rate

- **A UI animation must start when the input happened, not at the next frame that notices it.** A tween retargeted from the
  draw starts at that frame's time, so at 30 FPS a switch's knob ran 33 ms behind the same click at 300 FPS (testkit 1590:
  0.83 against 0.96 at 100 ms). `MenuAnim.input()` stamps clicks, keys and the wheel, and a change seen at the next frame
  starts from that stamp. Two draws at one clock value are one frame, or the stamp is lost.
- **`now - Long.MIN_VALUE` overflows to a negative elapsed time.** The "never started" sentinel made `progress()` return 0,
  so a fresh menu's cards drew at opacity 0 and every drawer row at 25% (found because 1594's per-frame allocation was 100 KB
  LOWER with Smooth than with Off: invisible text is never built). Guard the sentinel first.
- **A closing animation cannot keep the screen open.** Escape must hand the game its input at once (testkit 942/986), so the
  menu closes immediately and `MenuCloseFade` draws the closed ModScreen's picture fading over whatever is next (a HUD layer
  with no screen, `ScreenEvents.afterExtract` over one).
- **Out-of-world menus run at 60 fps whatever Max Framerate says** (javap both versions): `FramerateLimitTracker.
  getFramerateLimit()` returns `OUT_OF_LEVEL_MENU_LIMIT` 60 with no level and a screen or overlay open, and 30 (`SHORT_AFK`)
  after 60 s without input under the default Inactivity FPS Limit AFK. `mixin/MainMenuFramerateMixin` lifts only the first to
  the Max Framerate while the themed scene shows (a setting under 120 is kept, killer560 2026-10-16; light-hq-1016: 59.7 ->
  119.3 fps, 2262 unlimited). A frame-rate test after a long session reads the AFK 30 unless it calls `onInputReceived()`.

## Title screen and painted scenes

- **26.2's title screen has a row of icons the themed layout must name.** `TitleScreen.init` adds `FriendsButton` (key
  `gui.friends.open`), language and accessibility between the column and Options/Quit, and Mod Menu 20's icon style adds
  `SmallModMenuButtonWidget` (18 on 26.1.2 used `UpdateCheckerTexturedButtonWidget`; javap). Unrecognized, they sat on top of
  Swap Accounts. `MainMenuTitleLayout` removes them and every other Mod Menu button, classic row included (Friends via
  `McCompat.isFriendsButton`); testkit 341 checks every visible widget and every pair's overlap, Mod Menu loaded, both styles,
  both versions.
- A painted main menu that must move smoothly at full resolution cannot use `g.blit` tiles (GUI_TEXTURED samples NEAREST,
  so a sub-pixel offset snaps a whole pixel). Amber's Aurora submits one `BlitRenderState` with its own pipeline built on
  `RenderPipelines.GUI_TEXTURED_SNIPPET` (which already declares Sampler0 in 26.1.2 and 26.2, so no `withSampler0`) and a
  `TextureSetup` with the LINEAR clamp sampler; the clock rides in the vertex color (24-bit loop fraction) because a GUI
  element has no uniform of its own (testkit 1695, both versions). Pose-change cost in a `GuiRects` batch is in
  [LESSONS-GUI.md](LESSONS-GUI.md).
- **A vertex-color gradient cannot be dithered, so a faint one bands:** `GuiRects`/`fillGradient` colors are interpolated and
  rounded to the 8-bit framebuffer once per layer, so a 20-level sky gradient was 42-row bands with every column stepping on
  the same row (and R, G, B on different rows: colored stripes). `mainmenu/SceneShaders` composites a scene in float in its
  own fragment shader and adds blue noise once. Judge banding by how many columns step TOGETHER between two rows (1.00
  banded, 0.33 dithered), not by run length: correct +-0.5 LSB dither still holds a level for ~20 rows near an exact level.
- **Custom GUI shader parameters ride in a texture:** a `GuiElementRenderState` cannot bind a uniform block, so
  `SceneShaders` writes floats bit-exact into an RGBA8 `DynamicTexture` (`setPixelABGR(Float.floatToRawIntBits)`; the shader
  rebuilds them with `uintBitsToFloat(r | g<<8 | b<<16 | a<<24)`) and the VERTEX shader reads them into flat varyings, so
  fragments fetch nothing. `GUI_TEXTURED_SNIPPET` (position, uv, color, Sampler0) and the vanilla `position_tex_color` shader
  are byte-identical in 26.1.2 and 26.2. A PNG's `.png.mcmeta` `"blur": true` gives a GUI blit linear filtering on both
  (`ReloadableTexture` reads `TextureContents.blur()`).
- **A scene-shader particle carries everything but its color in its UVs:** a `SceneShaders.Quads` quad has only position, uv
  and one color, so `SimpleScenes` puts the parameter slot in u (`slot * 16 + local`) and the particle kind in v
  (`kind * 16 + local`), split in the vertex shader with `floor((uv + 8) / 16)`. That holds only while the local coordinate
  stays inside +-8, so a quad's reach is capped at 7.5 core radii (a firefly's wide glow is cut there, under 1% alpha). The
  backdrop's flat varyings stay at 52 components, under GL 3.3's guaranteed 60.
- **A scene clip can stutter when the scene does not:** x264 at default settings puts a keyframe every 250 frames (a quality
  pop every ~2 s at 120 fps) and holds a slow scene still for several frames, then jumps (Amber Aurora: raw per-frame change
  0.0068-0.0084, the CRF clip 0.0004-0.5). Judge smoothness on the PNG frames, and encode with
  `-g <frames> -x264-params scenecut=0`, at CRF 8 for a slow scene.

## Sound

- **Every vanilla button click is `minecraft:ui.button.click` through `SoundManager.play`**, so the Click Sound option is one
  `@ModifyVariable` on that method (`clicksound/mixin`), not a change per widget. An imported .ogg is not in any resource
  pack: `SoundBufferLibrary.getCompleteBuffer` is answered from the folder by a second mixin, and the decoded `SoundBuffer` is
  kept per file because each owns an OpenAL buffer freed only when the engine clears its library. An `Identifier` cannot hold
  capitals or spaces, so an imported file is named to the engine by a hashed key (`imported/s<hash>`).
