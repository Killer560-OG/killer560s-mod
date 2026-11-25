# Third-Party Notices

This project's Experimentation Table solver logic
(`src/main/java/com/killer560/hub/experiments/ExperimentSolver.java`) was ported from
[AzureSky0116/astrail-experiment](https://github.com/AzureSky0116/astrail-experiment), used under the
MIT License below.

```
MIT License

Copyright (c) 2026 AzureSky0116

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Profile Viewer Styles (textures)

The /pv Styles draw the original mods' own GUI sprites, unchanged except for crops
(`src/main/resources/assets/killer560smod/textures/gui/sprites/pv/`). No code was copied; the layout numbers
were read from their sources.

- `pv/neu/*`: from [NotEnoughUpdates](https://github.com/NotEnoughUpdates/NotEnoughUpdates) (`pv_bg.png`,
  `pv_elements.png`, `pv_basic.png`, `pv_dung.png`, `pv_invs.png`, `pv_pets.png`, `pv_cols.png`, `pv_dropdown.png`, `slot.png` (one cell of `pv_invs.png`), and
  `inv_tab.png`, `inv_tab_on.png`, `pet_sel.png` (one `pv_elements.png` cell: as drawn, turned, and tinted as NEU tints it),
  `recent_top.png`, `recent_top_on.png`, `recent.png`, `recent_on.png` (`pv_elements.png` cells), `pv_ironman.png`, `pv_bingo.png`, `pv_trophy.png` (`pv_trophy_fish_tab.png`), `pv_bingo_tab.png`,
  `pv_stranded.png`), Copyright NotEnoughUpdates contributors, licensed under the
  GNU Lesser General Public License v3.0 (https://www.gnu.org/licenses/lgpl-3.0.html).
- `pv/sbpv/light`, `pv/sbpv/dark`, `pv/sbpv/clear`: from [SkyBlockPv](https://github.com/meowdding/skyblock-pv)
  (its default, darkmode and neu theme sprites; `slot.png` is one cell of each theme's `inventory/mono.png`, `side_tab.png` and
  `side_tab_on.png` are its `tabs/right` sprites, `title.png` its `box/title`, `chip.png` its `box/rounded_box_thin`, `player_bg.png` its `buttons/disabled`, and `bar_*.png` its `progressbar` sprites), and in `pv/sbpv` itself
  `icon_clipboard.png` and `icon_sword.png` (its `icon/item/clipboard` and `icon/item/sword` title icons) and `slot_bone.png` and
  `slot_bait.png` (its `icon/slot/bone` and `icon/slot/bait` empty-slot icons; `slot_necklace`, `slot_cloak`, `slot_belt`,
  `slot_glove`, `slot_rod`, `slot_pickaxe`, `slot_axe`, `slot_hoe`, `slot_watering_can`, `slot_armorstand` and `slot_minecart` are its other `icon/slot` icons). SkyBlockPv License v1.1: non-code assets are licensed under
  Creative Commons Attribution 4.0 International (https://creativecommons.org/licenses/by/4.0/). Portions of these
  assets are from the SkyBlockPv mod.
- `pv/sbpv/button*.png`: from [Olympus](https://github.com/terrarium-earth/Olympus) (`buttons/dark/*`), used under
  the MIT License: Copyright (c) 2025 terrarium-earth. Permission is hereby granted, free of charge, to any person
  obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software
  without restriction, subject to the condition that the above copyright notice and this permission notice shall
  be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT
  WARRANTY OF ANY KIND.

## Profile Viewer data tables

`src/main/resources/assets/killer560smod/profileviewer/extras.json` carries tables from
[meowdding-repo](https://github.com/meowdding/meowdding-repo) (`repo/pv`: Crimson Isle, Rift, corpse milestones, fossils,
garden (levels, contest perks, chips, crop tools, visitors and visitor upgrades, composter and greenhouse upgrades, plots, plot costs and barn skins), chocolate factory, foraging, essence perks, gear lists, and the HotM/HotF node positions, perk texts, level formulas, costs and tier rewards from
`repo/mining/hotmperks` and `repo/foraging/hotfperks`), used under the MIT License: Copyright (c) 2026 meowdding. Permission is hereby granted, free
of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in
the Software without restriction, subject to the condition that the above copyright notice and this permission notice shall
be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND.
