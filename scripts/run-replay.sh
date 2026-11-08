#!/usr/bin/env bash
# Sections 5-7.5: parse the permission cascade from the sources, replay the statement counts and the
# level trace on the snapshot (Table 2), and check Algorithm 1 against a reference evaluator.
# Requires Node.js 20+. Outputs: results/replay/*.tex and results/replay/*.log.
set -euo pipefail; source "$(dirname "$0")/env.sh"
O="$RESULTS/replay"; mkdir -p "$O"; cd "$ROOT/replay"
node permedges.mjs --write | tee "$O/permedges.log"
node permgraph.mjs "$O" | tee "$O/permgraph.log"
node instancegraph.mjs --data "$ROOT/data/lnu/data-anonymised.sql" --out "$O" | tee "$O/instancegraph.log"
node instancegraph.mjs --data "$ROOT/data/lnu/data-anonymised.sql" --emit-dataset "$ROOT/data/lnu/permission-instance.tsv" > /dev/null
node algocheck.mjs --graphs "${GRAPHS:-2000}" --out "$O" | tee "$O/algocheck.log"
