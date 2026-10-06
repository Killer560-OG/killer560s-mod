#!/bin/sh
# Offline: Auto Teleport Maze's pad choice (old QUOI pick vs MazeRoute) against N sim mazes. See RouteCheck.java.
#   tools/mazecheck/route.sh [N]        default 20000
set -e
cd "$(dirname "$0")/../.."
rm -rf build/routecheck
javac -nowarn -d build/routecheck src/main/java/com/killer560/hub/roomsim/puzzles/TeleportMazeLinks.java \
  src/main/java/com/killer560/hub/autopuzzles/MazeRoute.java tools/mazecheck/MazeCheck.java tools/mazecheck/RouteCheck.java
java -cp build/routecheck RouteCheck "${1:-20000}"
