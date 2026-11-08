#!/usr/bin/env bash
# Section 7.6 (Table 3): per-row baseline versus set-at-a-time evaluation on the deployment snapshot.
# Five JVM runs co-located with the database, then five through the relay (DelayProxy, 250 us each way).
# Each run checks that both strategies return identical maps, records statement counts, then does
# WARM warm-up rounds and PAIRS measured paired rounds. Outputs: results/latency/*.csv, *.log, rtt.txt.
set -euo pipefail; source "$(dirname "$0")/env.sh"
O="$RESULTS/latency"; mkdir -p "$O"; RUNS="${RUNS:-1 2 3 4 5}"
if (exec 3<>/dev/tcp/127.0.0.1/"$RELAY_PORT") 2>/dev/null; then echo "port $RELAY_PORT is already in use" >&2; exit 1; fi
EXEC=1 run_main org.lnu.timetable.bench.DelayProxy "$RELAY_PORT" "$PGHOST" "$PGPORT" "$RELAY_DELAY_US" > "$O/delay-proxy.log" 2>&1 &
PROXY=$!; trap 'kill $PROXY 2>/dev/null || true' EXIT; sleep 5
"$(dirname "$0")/measure-rtt.sh"; mv "$RESULTS/rtt.txt" "$O/rtt.txt"
for r in $RUNS; do
  JOPTS="-Xms1g -Xmx2g -Ddb=$LNU_DB" run_main org.lnu.timetable.bench.LatencyBench "$r" "${PAIRS:-30}" "${WARM:-10}" "$O/loopback-run$r.csv" > "$O/loopback-run$r.log" 2>&1
  echo "co-located run $r: $(tail -1 "$O/loopback-run$r.log")"
done
for r in $RUNS; do
  BENCH_PORT="$RELAY_PORT" JOPTS="-Xms1g -Xmx2g -Ddb=$LNU_DB" run_main org.lnu.timetable.bench.LatencyBench "$r" "${PAIRS:-30}" "${WARM:-10}" "$O/relay-run$r.csv" > "$O/relay-run$r.log" 2>&1
  echo "relay run $r: $(tail -1 "$O/relay-run$r.log")"
done
