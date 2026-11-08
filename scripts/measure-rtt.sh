#!/usr/bin/env bash
# Round trip per statement, directly and through the relay: 1 000 sequential "SELECT 1" in one psql session.
# The relay (DelayProxy) must be running on $RELAY_PORT. Output: results/rtt.txt.
set -euo pipefail; source "$(dirname "$0")/env.sh"
for rep in 1 2; do for port in "$PGPORT" "$RELAY_PORT"; do
  t0=$(date +%s%N); printf "SELECT 1;\n%.0s" $(seq 1000) | psql -X -q -At -p "$port" -d "$LNU_DB" > /dev/null; t1=$(date +%s%N)
  echo "port $port: $(( (t1 - t0) / 1000 / 1000 )) us per select"
done; done | tee "$RESULTS/rtt.txt"
