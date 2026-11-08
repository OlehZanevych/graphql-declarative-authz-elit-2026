#!/usr/bin/env bash
# Record the measurement environment: results/environment.txt.
set -euo pipefail; source "$(dirname "$0")/env.sh"
{
  echo "date: $(date -u +%FT%TZ)"; uname -srm; echo "cpus: $(nproc)"; free -m | head -2
  grep -m1 "model name" /proc/cpuinfo || true
  env -u JAVA_TOOL_OPTIONS "$JAVA" -version 2>&1
  psql -X -At -d "$LNU_DB" -c "select version()"
  psql -X -At -d "$LNU_DB" -c "show max_connections" -c "show shared_buffers" -c "show default_transaction_isolation"
  node --version 2>/dev/null | sed 's/^/node /' || true
  python3 -c "import sys, scipy; print('python', sys.version.split()[0], 'scipy', scipy.__version__)" 2>/dev/null || true
} > "$RESULTS/environment.txt" 2>&1
cat "$RESULTS/environment.txt"
