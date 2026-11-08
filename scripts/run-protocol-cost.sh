#!/usr/bin/env bash
# Section 8.6: latency and throughput cost of the domain-lock protocol, five runs.
# Outputs: results/protocol/protocol-{single,concurrent}-run<r>.csv and protocol-run<r>.log.
set -euo pipefail; source "$(dirname "$0")/env.sh"
O="$RESULTS/protocol"; mkdir -p "$O"
for r in ${RUNS:-1 2 3 4 5}; do
  JOPTS="-Xmx1g" run_main org.lnu.timetable.cons.ProtocolCost "$CONS_DB" "$r" "$O/protocol" > "$O/protocol-run$r.log" 2>&1
  echo "protocol run $r done"
done
