#!/usr/bin/env bash
# Compiles the shared record store's pure classes with SharedRecordsTest and runs it (shared-stats-1101).
# Usage: tools/shared-records/run-test.sh   (from the repo root; needs JAVA_HOME = JDK 25+)
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GSON="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.google.code.gson/gson" -name 'gson-2.*.jar' ! -name '*sources*' | sort | tail -1)"
OUT="$(mktemp -d)"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) OUT="$(cygpath -w "$OUT")";; esac
"$JAVA_HOME/bin/javac" -d "$OUT" -cp "$GSON" \
  "$ROOT/src/main/java/com/killer560/hub/shared/JsonMerge.java" \
  "$ROOT/src/main/java/com/killer560/hub/shared/SharedFile.java" \
  "$ROOT/src/main/java/com/killer560/hub/shared/RecordCatalog.java" \
  "$ROOT/tools/shared-records/SharedRecordsTest.java"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; OUT="$(cygpath -w "$OUT")"; GSON="$(cygpath -w "$GSON")";; esac
"$JAVA_HOME/bin/java" -cp "$OUT$SEP$GSON" SharedRecordsTest
