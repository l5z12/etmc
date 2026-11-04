#!/usr/bin/env bash
# Checks that every Stonecutter node registered in settings.gradle.kts matches exactly one CI shard's
# `nodes` regexes in .github/workflows/build.yml. A node matched by no shard would never be built or
# released; one matched by two would be built twice. Needs yq (preinstalled on GitHub's Ubuntu runners).
#
# The regexes are Kotlin full matches (Regex.matches) in Gradle; they stick to syntax that means the same
# in POSIX ERE, so `grep -Ex` (whole-line match) checks them the same way.
set -euo pipefail
cd "$(dirname "$0")/.."

mapfile -t nodes < <(grep -oP '^\s*node\("\K[^"]+' settings.gradle.kts)
mapfile -t shards < <(yq '.jobs.assemble.strategy.matrix.include[] | .shard + " " + .nodes' .github/workflows/build.yml)

[ "${#nodes[@]}" -gt 0 ] || { echo "::error::no node(...) registrations found in settings.gradle.kts"; exit 1; }
[ "${#shards[@]}" -gt 0 ] || { echo "::error::no shards found in build.yml's assemble matrix"; exit 1; }

bad=0
declare -A count=()
for n in "${nodes[@]}"; do
  hits=()
  for s in "${shards[@]}"; do
    name=${s%% *}
    IFS=, read -ra res <<< "${s#* }"
    for re in "${res[@]}"; do
      if grep -Exq -- "$re" <<< "$n"; then hits+=("$name"); break; fi
    done
  done
  if [ "${#hits[@]}" -ne 1 ]; then
    echo "::error file=settings.gradle.kts::node $n matches ${#hits[@]} shards (${hits[*]:-none})"
    bad=1
  else
    count[${hits[0]}]=$(( ${count[${hits[0]}]:-0} + 1 ))
  fi
done

for s in "${shards[@]}"; do
  name=${s%% *}
  echo "$name: ${count[$name]:-0} nodes"
done
[ "$bad" -eq 0 ] && echo "All ${#nodes[@]} nodes map to exactly one shard."
exit "$bad"
