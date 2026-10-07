# Compiling

Moved out of CLAUDE.md (2026-10-05), then out of LESSONS.md (2026-10-07). Problem, then fix; verified only.

- **Never write a Minecraft API call from memory - grep for a call site in this repo first.** A cloud session
  cannot compile (the network policy blocks `maven.fabricmc.net` and Mojang's hosts), so a wrong method name is
  not caught until killer560 runs the build, and it costs him a whole round trip. Three in one batch on
  2026-10-01: `Entity.moveTo` is `snapTo` in 26.1.2, `EntityType.BAT` belongs behind `McEntities.BAT` because it
  is one of the names that moved in 26.2, and `BlockState.isCollisionShapeFullBlock` was a guess at a predicate
  that could have been several things. Every one of them had a working equivalent already in the tree -
  `SimMiniboss.snapTo`, `SimMobs`' bat spawn, `TeleportUtils`' `getCollisionShape(...).max(...)`. The rule is
  mechanical: before using a vanilla method or constant that does not already appear in `src/`, either find it
  there or pick something that does. METHOD names are what move between versions - and so do some block
  constants: a coloured block (`Blocks.RED_WOOL`) does not exist in 26.2 and must be `McBlocks.RED_WOOL`, which
  broke only the 26.2 build of `a4e563a`. The same commit also broke 26.1.2 with
  `SoundEvents.ELDER_GUARDIAN_HURT.value()`: only some `SoundEvents` are holders (`NOTE_BLOCK_PLING`,
  `GENERIC_EXPLODE`); mob sounds like `BLAZE_HURT` are plain `SoundEvent`s. Copy the shape of an existing use.
- **A cloud session CAN check far more than it parses.** `javac -XDshould-stop.ifNoError=PARSE` only checks
  syntax, which is why `List<Integer> pool = live;` shipped into a method whose own parameter was already called
  `pool` and broke the build. Run the FULL compile on each changed file and filter the noise instead - without
  the Minecraft jar every type is unresolved, but everything structural is still reported:
  ```
  javac -proc:none -nowarn -Xmaxerrs 2000 -d /tmp/out F.java 2>&1 | grep "error:" \
    | grep -vE "cannot find symbol|package .* does not exist|cannot access|incompatible types|method does not override|no suitable method|cannot be applied|is not abstract|bad operand|cannot be dereferenced|array required|unexpected type|not a statement|cannot infer type"
  ```
  What survives that filter is real: "already defined", "missing return statement", "unreachable statement",
  "cannot assign a value to final variable", "might not have been initialized", duplicate methods. Verified by
  reintroducing the `pool` collision into a scratch copy and watching the filter print it. This does NOT replace
  the rule below about API names - an unresolved method is indistinguishable from a misspelt one here.

Automation runtime lessons (Auto Routes on GrimAC, chain timing, ViewFreeze, held camera, key range) moved to
[LESSONS-AUTOMATION.md](LESSONS-AUTOMATION.md) on 2026-10-06 to keep this file under its size limit.
