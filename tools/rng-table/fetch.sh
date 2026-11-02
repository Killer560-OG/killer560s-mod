#!/usr/bin/env bash
# Rebuilds How Lucky Are You's drop table (src/main/resources/assets/killer560smod/rngtracker/drop-table.json) from
# hypixelskyblock.minecraft.wiki (the owner's wiki; never wiki.hypixel.net or Fandom).
#
#   bash tools/rng-table/fetch.sh            # downloads raw wikitext into tools/rng-table/wiki/ (gitignored) and builds
#
# index.php?action=raw returns the page source; /w/<page>?action=raw is behind a bot check, index.php is not (2026-11-01).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/wiki"
ua="killer560smod-rng-tracker/1.0 (drop table)"
get() { curl -sf -A "$ua" "https://hypixelskyblock.minecraft.wiki/index.php?title=$1&action=raw" -o "$here/wiki/$2" < /dev/null; }
for p in Revenant_Horror Tarantula_Broodfather Sven_Packmaster Voidgloom_Seraph Inferno_Demonlord Riftstalker_Bloodfiend Dyes; do
  get "$p" "$p.wiki"
done
for r in I II III IV V VI VII; do
  get "The_Catacombs_-_Floor_$r/Loot" "loot_F_$r.wiki"
  get "The_Catacombs_-_Floor_$r/Master_Mode_Loot" "loot_M_$r.wiki"
done
get "Kuudra/Loot" "Kuudra_Loot.wiki"
get "Crystal_Nucleus" "Crystal_Nucleus.wiki"
python -I "$here/fetch_mobs.py" < /dev/null   # every Mob Drops Table page + the ultra-rare Experiments rewards
python -I "$here/parse_slayers.py" "$here/wiki/slayers.json"
python -I "$here/build_table.py" "$here/wiki/slayers.json" "$here/../../src/main/resources/assets/killer560smod/rngtracker/drop-table.json"
