#!/usr/bin/env bash
# Section 8.6 (Table 5): schedules S0-S10 against the released evaluator and the prototype, on the
# scratch database lnu_cons. S10 makes N randomized write attempts (default 400).
# Outputs: results/consistency/consistency.tsv (one row per schedule) and consistency.log.
set -euo pipefail; source "$(dirname "$0")/env.sh"
O="$RESULTS/consistency"; mkdir -p "$O"
JOPTS="-Xmx1g" run_main org.lnu.timetable.cons.ConsistencyTests "$CONS_DB" "${N:-400}" "$O/consistency.tsv" 2>&1 | tee "$O/consistency.log"
