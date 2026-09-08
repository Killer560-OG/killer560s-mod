# Handoff: Killer560's Mod

Paste this into a new chat to resume work with full context, without needing the long prior conversation.

## ⚠️ Non-negotiable privacy rule — read this first

killer560 is public as **"killer560"** only. His real first name and real personal email must **NEVER** appear anywhere public-facing again: not in code, comments, docs, commit messages, commit author metadata, or Discord.

- Git identity for this repo is already set correctly (local `.git/config`): `user.name = killer560`, `user.email = killer560.hypixelskyblock@gmail.com`. Never change these back to his real name/email, and never let a commit go out under any other identity.
- If you ever need to reference him in code comments/docs, use "killer560" only.
- **Do not run `git filter-branch` / history rewrites as a "fix" if his real name ever leaks again.** It was tried twice already and failed — GitHub keeps old commits reachable via direct SHA/compare links even after a rewrite + force-push, until the repo is deleted. The only reliable fix if this ever happens again is deleting and recreating the GitHub repo from scratch (see below).
- Per standing rule, Claude must **never personally delete a GitHub repo** (or perform any permanent data deletion) even if asked — state the rule, hand killer560 the direct settings URL, and let him click delete himself. He has done this once already without pushback when it was necessary.

## Project basics

- **Location:** `C:\Users\killer560\killer560s-mod` (this is fine locally — only what's pushed/posted publicly matters for the privacy rule above).
- **Mod:** Fabric mod, id `killer560smod`, package base `com.killer560.hub`, targets **Minecraft 26.1.2**, Java 25, Mojang mappings.
- Started as killer560's personal combined mod, now **public** — merges several previously-separate mods (Account Switcher, Proxy, Spotify Lyrics, RNG Meter, Experiments automation, etc.) into one.
- There's a **separate, older, unrelated mod** at `C:\Users\killer560\killer560smod` (no hyphen) — the mining macro mod, "Killer560's Macro Mod." Don't confuse the two folders. This handoff is entirely about the hyphenated `killer560s-mod` project.

## Git / GitHub state (as of 2026-09-08)

- **Repo:** `https://github.com/Killer560-OG/killer560s-mod` — public, freshly created (old repo was deleted by killer560 himself after a real-name leak was found in an orphaned/dangling commit; this is a from-scratch history with one initial commit, no baggage).
- **Legit/cheat build split:** `build.gradle` has a `-PcheatBuild=true/false` project property that generates a compile-time `BuildVariant.CHEAT_FEATURES_ENABLED` constant. The single gate point is `ExperimentsConfig.isAutonomousMode()` — legit build strips Experimentation Table Autonomous Mode out entirely (not just disables it). `jar { archiveClassifier }` produces `killer560smod-1.0.0-legit.jar` / `-cheat.jar`.
  - **Gradle gotcha already fixed once:** the `generateBuildVariant` task MUST declare `inputs.property "cheatBuild", cheatBuild` or Gradle's up-to-date check caches stale output across different `-PcheatBuild` values. Already fixed — don't reintroduce the bug.
- **Current release:** `v1.0.0 - Initial Release`, published and marked Latest, both jars attached.
- **Discord webhook** is wired up: `#github-updates` channel has a live webhook registered as the repo's GitHub webhook (Payload URL ends in `/github`, content type `application/json`, "Send me everything"). New commits/releases/etc. will auto-post there. Don't need to touch this again unless it breaks.
- **Discord invite:** permanent, non-expiring invite `https://discord.gg/hkQMF5fE84` is in the README (with a badge) and set as the repo's "Website" link in the About sidebar.

## Discord server state ("killer560's server")

- Channels of note: `#releases`, `#github-updates`, `#mod-features` (posted as two messages: legit-version feature list, and a second "what the cheat version adds" message), `#how-to-install`, `#bug-reports-and-suggestions`, `#dev-bot` (new, Dev-category-only, same visibility scheme as `#dev-general`/`#dev-mod-discussion`).
- Bot roles (`Claude_Bot`, `Bot`, `carl-bot`, `SkyHelper`, `Kuudra Gang`) are all colored `#9B59B6` (purple), distinct from the default blurple.
- `default_message_notifications` is set to "Only @mentions" server-wide.
- A `channels.json` / `roles.json` cache of IDs lives in the scratchpad dir from this session if you need channel/role IDs again — otherwise just ask Claude to look them up fresh via the Discord API.

## Mandatory workflow for every code change

1. Make the code change.
2. `cd "/c/Users/killer560/killer560s-mod" && ./gradlew build --console=plain` — must say BUILD SUCCESSFUL. Use `-PcheatBuild=true` if testing cheat-only features.
3. Deploy the built jar to **all 4 Prism Launcher instances**:
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 (Dungeons)\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 (Dungeons) (duo)\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\26.1.2 ALT\minecraft\mods\`
   - `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\Taunahi\minecraft\mods\`
   - For each: `rm -f` the old jar, `cp` the new one, `md5sum` both source and dest and confirm MATCH, `unzip -t` the dest to confirm ZIP OK.
4. **Known gotcha:** if Minecraft is currently running on the `26.1.2 (Dungeons)` instance (the one killer560 live-tests on), `rm` will fail with "Device or resource busy." The `cp` usually still succeeds and MD5 still matches, but the **already-running game keeps using the OLD in-memory code** until killer560 fully closes and relaunches it. Always flag this explicitly when it happens — don't just report "deployed" silently.
5. Commit + push to GitHub (this will now auto-post to `#github-updates` via the webhook — that's expected and fine).
6. Update the project memory file at `C:\Users\killer560\.claude\projects\C--Users-killer560\memory\project_killer560s_consolidation.md` if the change is significant enough to matter for future sessions.
7. Report back to killer560 what changed, the build result, and whether all 4 instances deployed cleanly.

## ⏰ Pending test — remind killer560 to check this

killer560 hasn't field-tested this yet (deployed 2026-09-08, needs a few hours before he's back on). **If he opens a new chat about this project, proactively remind him to test Experimentation Table Chronomatron/Ultrasequencer Solver Only mode** and report back:

- **Fixed:** Chronomatron highlight/click-tracking desyncing ("wanted me to immediately click it twice") when the same note color reappeared later in the sequence with a gap in between — was matching by color across the whole board instead of restricting to the same column.
- **Fixed:** Shift-click override of the misclick protection — previously let the click through to the game but never advanced the solver's own tracked index, so every click after a Shift-click looked wrong too. Now confirms first, then applies Shift only to the block decision.
- **Added:** a "Click Protection" ON/OFF toggle in the Experiments tab (Solver Only mode) — this was previously always-on with no setting.
- Deployed jar md5 `b351e050fabc11c27502aeeb40983a94` on all 4 Prism instances (cheat-variant jar copied in as `killer560smod-1.0.0.jar`, matching what was already installed pre-legit/cheat-split). Commit: `19a50e4`.
- Files touched: `ExperimentSolver.java` (`confirmManualChronomatronClick`), `ExperimentsFeature.java` (`highlightMatchingChronomatronSlots`, `shouldBlockManualMisclick`), `ExperimentsConfig.java` + `ExperimentsTab.java` (new `clickProtectionEnabled` setting).
- If either issue is still present after this fix, get a real log per the debugging discipline below before proposing another fix — don't guess a third time on the same mechanism.

## Roadmap

Feature ideas and planned work live in [ROADMAP.md](ROADMAP.md) in the repo — add to it as killer560 thinks of things, rather than tracking ideas only in chat. Current "Planned" list is long; check it before starting new feature work so nothing's duplicated. One item worth flagging: **a "Join Discord" button on the mod's main menu screen** (opens `https://discord.gg/hkQMF5fE84`) was just added to the roadmap and not yet built — likely next small feature to pick up.

## Debugging discipline established in prior sessions (keep following it)

- **Read killer560's actual Minecraft logs directly** rather than guessing from his verbal description. Logs live at `C:\Users\killer560\AppData\Roaming\PrismLauncher\instances\<instance>\minecraft\logs\` — `latest.log` for the current/most recent session, `YYYY-MM-DD-N.log.gz` for rotated ones (`zcat file.gz | grep ...`). Check mtimes to make sure you're reading the log for the test he's actually describing.
- **Never guess Hypixel's exact tooltip/lore text.** Get a real log/screenshot first, or add diagnostic logging and wait for the next real test.
- **Verify unfamiliar Minecraft/Mojang-mapped APIs via `javap`** against the real decompiled jar before relying on training-era assumptions — MC 26.1.2 has diverged from older/vanilla APIs in several places. Real jar for javap:
  `C:\Users\killer560\killer560s-mod\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-043a8b3edf\26.1.2\minecraft-merged-043a8b3edf-26.1.2.jar`
  Extract with `unzip -o jar "path/to/Class.class" -d dir`, then `javap -p [-c] dir/path/to/Class.class`.
- **Prefer hard, unconditional gates over precise attribution logic** when parsing Hypixel lore.
- Be skeptical of AI-generated answers on protocol/API specifics — verify against the real jar.
