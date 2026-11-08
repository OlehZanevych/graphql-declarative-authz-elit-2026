# Common settings for every script; source it, do not run it. Override any variable from the environment.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}" PGPASSWORD="${PGPASSWORD:-postgres}"
JAVA="${JAVA:-java}"                                   # Java 25 runtime (the application targets Java 25)
JAR="${JAR:-$ROOT/app/target/timetable-0.0.1-SNAPSHOT.jar}"
RESULTS="${RESULTS:-$ROOT/results}"
RELAY_PORT="${RELAY_PORT:-6545}"                       # DelayProxy listens here in the relay condition
RELAY_DELAY_US="${RELAY_DELAY_US:-250}"                # one-way delay added by DelayProxy
LNU_DB="${LNU_DB:-lnu_timetable}"; CONS_DB="${CONS_DB:-lnu_cons}"
ITC_INSTANCES="agh-ggis-spr17 bet-fal17 iku-fal17 lums-spr18 mary-spr17 muni-fi-spr16 nbi-spr18 pu-llr-spr17 tg-fal17 yach-fal17"
PSQL="psql -X -q -v ON_ERROR_STOP=1"

# run_main <main class> [args...]: run one experiment class from the fat jar built with -Pexperiments
# (EXEC=1 replaces the calling shell, so a background job's PID is the JVM's).
run_main() {
  local main="$1"; shift
  ${EXEC:+exec} env -u JAVA_TOOL_OPTIONS "$JAVA" ${JOPTS:-} -Dpg.host="$PGHOST" -Dpg.port="${BENCH_PORT:-$PGPORT}" -Dpg.user="$PGUSER" \
    -Dpg.password="$PGPASSWORD" -Dloader.main="$main" -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}
mkdir -p "$RESULTS"
