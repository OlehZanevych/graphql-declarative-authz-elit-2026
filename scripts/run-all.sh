#!/usr/bin/env bash
# Everything, in order. About 1.5 hours on a 2-vCPU machine (most of it the per-row baselines).
set -euo pipefail; D="$(dirname "$0")"
"$D/build.sh" "$@"
"$D/setup-lnu.sh"; "$D/setup-itc2019.sh"
"$D/environment.sh"
"$D/run-replay.sh"
"$D/run-latency.sh"
"$D/run-itc2019.sh"
"$D/run-consistency.sh"
"$D/run-protocol-cost.sh"
"$D/analyse.sh"
