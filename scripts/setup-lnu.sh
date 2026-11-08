#!/usr/bin/env bash
# Section 7.6 and 8.6 databases: the application's schema plus the anonymised deployment snapshot.
#   lnu_timetable  snapshot + benchmark caller 1001 (latency experiment)
#   lnu_cons       a scratch copy for the consistency tests (they add and remove their own fixture rows)
set -euo pipefail; source "$(dirname "$0")/env.sh"
for db in "$LNU_DB" "$CONS_DB"; do
  $PSQL -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
  $PSQL -d "$db" -f "$ROOT/app/src/main/resources/db/schema.sql" > /dev/null
  $PSQL -d "$db" -f "$ROOT/data/lnu/data-anonymised.sql" > /dev/null
done
$PSQL -d "$LNU_DB" -f "$ROOT/data/lnu/bench-caller.sql"
$PSQL -d "$LNU_DB" -c "ANALYZE"; $PSQL -d "$CONS_DB" -c "ANALYZE"
for db in "$LNU_DB" "$CONS_DB"; do
  echo "$db: $(psql -X -At -d "$db" -c "select (select count(*) from timetable_entries) || ' timetable entries, ' || (select count(*) from courses) || ' courses'")"
done
