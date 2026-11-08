#!/usr/bin/env bash
# Summaries of all experiments: results/summary/*.json and the Markdown tables in results/summary/tables.md.
set -euo pipefail; source "$(dirname "$0")/env.sh"
A="$ROOT/analysis"; S="$RESULTS/summary"; mkdir -p "$S"
python3 "$A/analyse.py" "$S/latency-summary.json" "loopback=$RESULTS/latency/loopback-run*.csv" "relay=$RESULTS/latency/relay-run*.csv" > /dev/null
python3 "$A/analyse.py" "$S/itc2019-summary.json" "itc=$RESULTS/itc2019/itc-run*.csv" > /dev/null
python3 "$A/protocol.py" > /dev/null
python3 "$A/consistency.py" > /dev/null
python3 "$A/environment.py" > /dev/null
python3 "$A/tables.py"
