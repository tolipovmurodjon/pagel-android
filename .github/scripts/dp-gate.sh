#!/usr/bin/env bash
# The spacing gate (docs/RULES.md "Layout" rule 2, spacing audit "How CI can catch layout jumps"): an off-scale dp
# (1 3 5 7 9 10 13 14 15 18 22 26) in a padding / spacedBy / Spacer line of ui/** outside ui/glass/ may never grow.
# Each file's count is held in .github/scripts/dp-baseline.txt; a file over its count, or a new file with any, fails.
# Run with --write to record the counts after a cleanup (counts may only go down).
set -euo pipefail
cd "$(dirname "$0")/../.."
UI=app/src/main/java/org/pagel/ai/ui
BASE=.github/scripts/dp-baseline.txt
count() {
  find "$UI" -name '*.kt' -not -path "$UI/glass/*" | sort | while read -r f; do
    n=$(grep -E 'padding\(|spacedBy\(|Spacer\(' "$f" | grep -oE '(^|[^0-9.])(1|3|5|7|9|10|13|14|15|18|22|26)\.dp' | wc -l || true)
    echo "${f#$UI/} $n"
  done
}
if [ "${1:-}" = "--write" ]; then count > "$BASE"; echo "baseline written: $(awk '{s+=$2} END {print s}' "$BASE") off-scale"; exit 0; fi
fail=0
while read -r f n; do
  b=$(awk -v f="$f" '$1 == f {print $2}' "$BASE"); b=${b:-0}
  if [ "$n" -gt "$b" ]; then echo "::error file=$UI/$f::$n off-scale dp in spacing (baseline $b): use Space tokens (ui/glass/Space.kt)"; fail=1; fi
done < <(count)
total=$(count | awk '{s+=$2} END {print s}')
echo "off-scale spacing dp outside ui/glass: $total (baseline $(awk '{s+=$2} END {print s}' "$BASE"))"
exit $fail
