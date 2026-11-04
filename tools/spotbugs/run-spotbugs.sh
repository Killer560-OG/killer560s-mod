#!/usr/bin/env bash
# Static bug scan with SpotBugs over the compiled mod classes. Kept out of the normal build on purpose.
# Usage (from the repo root, after a ./gradlew build of the variant you want to scan):
#   bash tools/spotbugs/run-spotbugs.sh [extra gradle -P flags, e.g. -PcheatBuild=true]
# Needs SpotBugs unpacked at $SPOTBUGS_HOME (default C:/Users/Hunter/tools/spotbugs-4.10.5,
# from https://github.com/spotbugs/spotbugs/releases). Writes build/spotbugs/{report.xml,report.txt}.
set -u
SPOTBUGS_HOME="${SPOTBUGS_HOME:-C:/Users/Hunter/tools/spotbugs-4.10.5}"
OUT=build/spotbugs
mkdir -p "$OUT"
./gradlew --init-script tools/spotbugs/classpath.gradle writeSpotbugsClasspath "$@" -q < /dev/null || exit 1
AUX=$(paste -sd ';' "$OUT/aux-classpath.txt")
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx4g -jar "$SPOTBUGS_HOME/lib/spotbugs.jar" -textui -effort:max -medium \
  -exclude tools/spotbugs/exclude.xml \
  -auxclasspath "$AUX" -xml:withMessages -output "$OUT/report.xml" build/classes/java/main < /dev/null
python -I tools/spotbugs/summarize.py "$OUT/report.xml" > "$OUT/report.txt"
echo "spotbugs: $(grep -c '<BugInstance' "$OUT/report.xml") findings -> $OUT/report.xml, $OUT/report.txt"
