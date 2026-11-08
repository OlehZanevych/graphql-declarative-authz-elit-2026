#!/usr/bin/env bash
# Section 7.7 databases: one PostgreSQL database itc_<instance> per ITC 2019 instance, with the
# application's schema (users, groups, permissions), the five ITC tables and the constructed grants.
set -euo pipefail; source "$(dirname "$0")/env.sh"
for inst in $ITC_INSTANCES; do
  db=itc_${inst//-/_}; d="$ROOT/data/itc2019/ids/$inst"
  $PSQL -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
  $PSQL -d "$db" -f "$ROOT/app/src/main/resources/db/schema.sql" > /dev/null
  $PSQL -d "$db" -f "$ROOT/data/itc2019/schema-itc.sql"
  for t in courses configs subparts classes rooms class_rooms; do
    $PSQL -d "$db" -c "\\copy itc_$t FROM '$d/$t.tsv'"
  done
  $PSQL -d "$db" -f "$ROOT/data/itc2019/grants.sql" > /dev/null
  echo "$db: $(psql -X -At -d "$db" -c 'select count(*) from itc_classes') classes"
done
