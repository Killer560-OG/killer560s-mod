# AP3 node labels + node editing - integration notes

Built 2026-09-16 for killer560: "for ap3 specifically have it label nodes that i make. For instance the very first
node is 1 the second is 2 and so on. It should be toggleable for color and if it shows and make it easier to edit them."

Nothing in `ap3/**` or `gui/tab/Ap3Tab.java` depends on these lines existing - without them the new controls just
have no hover tooltip.

## `gui/SettingTooltipsData.java` - new tooltip lines

Keys are `SettingTooltips.key(label)` form (formatting stripped, cut at the first `:`, lowercased). Keys that would
collide with existing generic entries (`edit`, `delete`, `width`, `move up`, `move down`, `length`, `wait`) are
scoped `ap3/` (the AP3 tab's name is "AP3"); the AP3-specific ones are plain like the existing AP3 entries.
`< back` already has a generic entry that fits, so it is not repeated. The edit page's wait box is titled
"Node Wait ms" because the existing `wait ms` entry describes the Add section's box (the NEXT node).

```java
d.put("node labels", "The floating text above each AP3 node in the world: its number in the chain (the first node is 1), its type and its modifier. Same numbers as /ap3 list, /ap3 delete <n> and the rows above.");
d.put("show node labels", "Master switch for the world labels above AP3 nodes. OFF hides every label; the three toggles below pick what a label says.");
d.put("show node numbers", "Puts each node's number in the chain on its world label - the first node is #1, the second #2, and so on. That number is the one /ap3 delete <n>, /ap3 move <n> and /ap3 set <n> want.");
d.put("show node type", "Puts the node's type (Line, Walk, Leap...) on its world label next to the number.");
d.put("show node details", "Puts the node's modifier on its world label - length x width, wait ms, leap target, leap count, breaker block count.");
d.put("label colour", "Node's Colour: each label is drawn in its own node's marker colour, so the number matches the box it sits on. Fixed: every label uses the one Fixed Label Colour.");
d.put("fixed label colour", "The one colour every AP3 node label is drawn in while Label Colour is set to Fixed.");
d.put("label scale", "Size of the world labels above AP3 nodes. 100% is the size they have always been.");
d.put("label height", "How many blocks above the node marker its label floats. 0 keeps it where it has always been, just above the box.");
d.put("ap3/edit", "Opens this node's own page: move it up or down the chain, re-place it where you stand, and change the fields its type uses (length, width, wait ms, leap target, leap count, breaker blocks, colour).");
d.put("ap3/delete", "Removes this node from the chain. Same as /ap3 delete <n> with this row's number.");
d.put("edit node", "One node's own settings. The number at the top is its position in the chain - the same number on its world label and in /ap3 list.");
d.put("ap3/move up", "Swaps this node with the one before it, so it runs one step earlier. Its number goes down by one. Same as /ap3 move <n> up.");
d.put("ap3/move down", "Swaps this node with the one after it, so it runs one step later. Its number goes up by one. Same as /ap3 move <n> down.");
d.put("move to my position", "Moves this node to where you are standing (snapped to the half block) and points it the way you are looking. Keeps its number and every modifier. An Axis Line re-measures its wall and refuses if there is none. Same as /ap3 replace <n>.");
d.put("set look to mine", "Points this node the way you are looking without moving it - a Walk/Run's travel direction, a Line's axis, a Look node's target. Same as /ap3 replace <n> look.");
d.put("corridor", "Line / Axis Line: the span the node stays active for and the band it still corrects inside.");
d.put("ap3/length", "Line / Axis Line: how far along the line the node stays active. Walk / Run: how far to travel. Same as /ap3 set <n> length <v>.");
d.put("ap3/width", "Line / Axis Line: the full width of the band the node still corrects your position inside. Same as /ap3 set <n> width <v>.");
d.put("travel", "Walk / Run: how far the node moves you in its recorded direction, without turning your camera.");
d.put("ap3/wait", "Wait node: milliseconds to stand still before the next node. A manual left-click skips it while the chain runs.");
d.put("node wait ms", "Milliseconds this Wait node stands still. Same as /ap3 set <n> wait <ms>.");
d.put("leap target", "Who this Leap node leaps to: Fast Leap target (whoever Fast Leap's P3 target for this section resolves to), a Class (first alive teammate of that class, class overrides apply) or an IGN. Same as /ap3 set <n> leap default|class <c>|ign <name>.");
d.put("leap class", "The class this Leap node targets while Leap Target is Class. Click to cycle.");
d.put("leap ign", "The exact player name this Leap node targets while Leap Target is IGN.");
d.put("leap detector", "Leap Detector: waits until this many teammates have leapt TO you. A manual left-click also counts as done.");
d.put("leap count", "How many teammates must leap to you before this Leap Detector node lets the chain move on. Same as /ap3 set <n> count <k>.");
d.put("breaker blocks", "The blocks this Breaker node breaks, in order. Turn on Breaker Edit Mode, then right-click blocks to add them and shift-right-click to remove.");
d.put("clear breaker blocks", "Removes every block from this Breaker node so you can pick them again from scratch.");
d.put("node colour", "This one node's own marker (and label) colour, overriding its type colour and the uniform colour. Same as /ap3 set <n> colour <hex>.");
d.put("use type colour", "Drops this node's own colour so it goes back to its type's colour (or the uniform colour). Same as /ap3 set <n> colour reset.");
d.put("delete node", "Removes this node from the chain and goes back to the list. Same as /ap3 delete <n>.");
d.put("re-place last chain node key", "Keybind that moves the LAST node in this section's chain to where you stand and look - the one you just added. The command form, /ap3 replace <n> [pos|look], takes a number.");
```

## Nothing else

No change to `Killer560ModClient`, `ProfileManager`, `fabric.mod.json`, `NewTab`, `ModScreen` or `README.md` (the
README has no AP3 section): the new keybind id (`replace_last`) is read from `Ap3Config.KEYBIND_IDS`, the new
setting keys load per-key through `ConfigJson` with defaults, and the tab's edit page is tab state, not a new screen.
