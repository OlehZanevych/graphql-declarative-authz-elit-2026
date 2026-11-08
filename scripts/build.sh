#!/usr/bin/env bash
# Build the application together with the experiment classes (src/experiments/java) into one jar.
# Requires JDK 25 and Maven 3.9+. Extra Maven arguments are passed through (e.g. -o for an offline build).
set -euo pipefail; source "$(dirname "$0")/env.sh"
cd "$ROOT/app" && mvn -B -Pexperiments -DskipTests "$@" package
ls -l "$JAR"
