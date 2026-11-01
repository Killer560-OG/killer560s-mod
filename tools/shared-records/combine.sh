#!/usr/bin/env bash
# One-time combine of every Prism instance's records into the shared store (shared-stats-1101).
# Usage: tools/shared-records/combine.sh [instances dir] [store dir] [default account uuid]
# Reads the instance files only; safe to run again (a re-run adds only what grew since, a copied file never twice).
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTANCES="${1:-$APPDATA/PrismLauncher/instances}"
STORE="${2:-$APPDATA/killer560smod/shared}"
ACCOUNT="${3:-ab8b1ab3f83744f5889056107311b374}"
GSON="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.google.code.gson/gson" -name 'gson-2.*.jar' ! -name '*sources*' | sort | tail -1)"
OUT="$(mktemp -d)"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; OUT="$(cygpath -w "$OUT")"; GSON="$(cygpath -w "$GSON")";; esac
"$JAVA_HOME/bin/javac" -d "$OUT" -cp "$GSON" \
  "$ROOT/src/main/java/com/killer560/hub/shared/JsonMerge.java" \
  "$ROOT/src/main/java/com/killer560/hub/shared/SharedFile.java" \
  "$ROOT/src/main/java/com/killer560/hub/shared/RecordCatalog.java" \
  "$ROOT/tools/shared-records/CombineInstances.java"
"$JAVA_HOME/bin/java" -cp "$OUT$SEP$GSON" CombineInstances "$INSTANCES" "$STORE" "$ACCOUNT"
