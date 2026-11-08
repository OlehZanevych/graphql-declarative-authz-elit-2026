#!/usr/bin/env bash
# Section 7.7 (Table 4): the same protocol on the ten ITC 2019 instances, one JVM per (run, instance)
# so that the per-row bursts of one pool never exceed max_connections. Outputs: results/itc2019/*.csv, *.log.
set -euo pipefail; source "$(dirname "$0")/env.sh"
O="$RESULTS/itc2019"; mkdir -p "$O"
for r in ${RUNS:-1 2 3 4 5}; do
  for i in $ITC_INSTANCES; do
    JOPTS="-Xms1g -Xmx2g" run_main org.lnu.timetable.itc.ItcBench "$r" "${PAIRS:-30}" "${WARM:-5}" "$O/itc-run$r-$i.csv" "$i" > "$O/itc-run$r-$i.log" 2>&1
  done
  echo "ITC run $r done"
done
