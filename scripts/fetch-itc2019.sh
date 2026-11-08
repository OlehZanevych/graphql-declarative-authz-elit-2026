#!/usr/bin/env bash
# Optional: download the ten ITC 2019 instances, verify each file's git blob SHA-1 against
# data/itc2019/instances.tsv, and regenerate the identifier extracts in data/itc2019/ids and stats.json.
# The extracts are committed, so this step is needed only to check them; the XML files are not redistributed.
set -euo pipefail; source "$(dirname "$0")/env.sh"
XML="${XML_DIR:-$ROOT/data/itc2019/xml}"; mkdir -p "$XML"
tail -n +2 "$ROOT/data/itc2019/instances.tsv" | while IFS=$'\t' read -r inst set sha bytes url; do
  [ -f "$XML/$inst.xml" ] || curl -fsSL -o "$XML/$inst.xml" "$url"
  got=$(git hash-object "$XML/$inst.xml")
  [ "$got" = "$sha" ] || { echo "SHA-1 mismatch for $inst: $got (expected $sha)" >&2; exit 1; }
  echo "$inst: $(wc -c < "$XML/$inst.xml") bytes, git blob SHA-1 OK"
done
python3 "$ROOT/data/itc2019/extract.py" "$XML" "$ROOT/data/itc2019/ids" > /dev/null
mv "$ROOT/data/itc2019/ids/stats.json" "$ROOT/data/itc2019/stats.json"
git -C "$ROOT" diff --stat -- data/itc2019 || true
