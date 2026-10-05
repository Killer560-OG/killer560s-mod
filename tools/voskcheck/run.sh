#!/bin/sh
# Checks VoskLibrary - the on-demand Vosk download and child-loader load - with no game, on this machine.
#   tools/voskcheck/run.sh [modelDir]
# Needs one gradle compile first (for the generated BuildVariant) and the Gradle cache's vosk-0.3.45.jar, which
# the local server hands out as the "Maven Central" copy. Each case runs in its own JVM because VoskLibrary keeps
# what it loaded for the JVM's lifetime, as it does in game. With a modelDir (an extracted
# vosk-model-small-en-us-0.15), every successful case also constructs an org.vosk.Model and recognises a second
# of silence. VOSKCHECK_CENTRAL=1 adds a real download from repo1.maven.org.
#
# The run's output goes to a file and its status is checked before anything is read from it; the script then
# requires one PASS line per case, so a check that never ran cannot pass.
set -e
cd "$(dirname "$0")/../.."
C=${GRADLE_USER_HOME:-$HOME/scoop/persist/gradle/.gradle}/caches/modules-2/files-2.1
VOSK=$(ls "$C"/com.alphacephei/vosk/0.3.45/*/vosk-0.3.45.jar | head -1)
JNA=$(ls "$C"/net.java.dev.jna/jna/5.14.0/*/jna-5.14.0.jar | head -1)
SLF=$(ls "$C"/org.slf4j/slf4j-api/2.0.17/*/slf4j-api-2.0.17.jar | head -1)
GEN=build/generated/sources/buildVariant/java/main
[ -f "$GEN/com/killer560/hub/BuildVariant.java" ] || { echo "run ./gradlew compileJava first"; exit 1; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
OUT=build/voskcheck
rm -rf "$OUT"; mkdir -p "$OUT/classes"
JAVAC=javac; [ -n "$JAVA_HOME" ] && JAVAC="$JAVA_HOME/bin/javac"
"$JAVAC" -nowarn -implicit:class -d "$OUT/classes" -cp "$SLF" -sourcepath "src/main/java$SEP$GEN" \
  src/main/java/com/killer560/hub/voicetotext/VoskLibrary.java tools/voskcheck/VoskCheck.java
CP="$OUT/classes$SEP$SLF$SEP$JNA"
JAVA=java; [ -n "$JAVA_HOME" ] && JAVA="$JAVA_HOME/bin/java"
"$JAVA" -version 2>&1 | head -1
MODEL="$1"
LOG="$OUT/log.txt"
: > "$LOG"
cases="offline corrupt truncated missing download cached tampered"
[ "${VOSKCHECK_CENTRAL:-0}" = "1" ] && cases="$cases central"
for c in $cases; do
  dir="$OUT/$c"
  case "$c" in cached|tampered) dir="$OUT/download";; esac   # second launch reuses the first one's folder
  echo "== $c"
  if ! "$JAVA" -Dvoskcheck.jar="$VOSK" -cp "$CP" VoskCheck "$c" "$dir" $MODEL > "$OUT/$c.txt" 2>&1; then
    cat "$OUT/$c.txt"; echo "FAIL: case $c exited non-zero"; exit 1
  fi
  grep -E "^  |^PASS" "$OUT/$c.txt" || true
  cat "$OUT/$c.txt" >> "$LOG"
done
want=$(echo $cases | wc -w)
got=$(grep -c "^PASS" "$LOG")
[ "$got" -eq "$want" ] || { cat "$LOG"; echo "FAIL: $got PASS lines for $want cases"; exit 1; }
echo "voskcheck: all $want cases passed"
