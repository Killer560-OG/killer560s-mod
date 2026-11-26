#!/bin/sh
# The auto pathfinder's island graph searches and Fairy Soul route order against exact answers (IslandBench.java).
#   tools/bench/island.sh DIR   (DIR holds <ISLAND>.json island graph files, as GraphRepository caches them)
set -e
cd "$(dirname "$0")/../.."
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
GSON=$(find "$G/com.google.code.gson" -name 'gson-2*.jar' ! -name '*sources*' | sort | tail -1)
SEP=":"
case "$(uname -s)" in
  *NT*|*MINGW*|*MSYS*) SEP=";"; GSON=$(cygpath -w "$GSON") ;;
esac
DIR="$1"
shift
rm -rf build/islandbench
javac -nowarn -proc:none -cp "$GSON" -d build/islandbench \
  src/main/java/com/killer560/hub/pathfinding/IslandGraph.java \
  src/main/java/com/killer560/hub/pathfinding/GraphPathfinder.java tools/bench/IslandBench.java
java -Xmx2g "$@" -cp "build/islandbench$SEP$GSON" IslandBench "$DIR"
