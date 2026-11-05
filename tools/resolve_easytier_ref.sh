#!/usr/bin/env bash
# Resolves an EasyTier ref (branch, tag, full ref name or 40-hex sha) to a commit sha on stdout.
#
# `git ls-remote <url> <pattern>` matches patterns against the END of ref names, so a bare "main"
# also matches refs like refs/for/main (EasyTier has one, pointing at a 2024 commit), and which one
# `head -1` picks depends on sort order. So we list candidates and keep only exact names:
#   - a full ref name (refs/...) means exactly that ref;
#   - a short name prefers a branch, then a tag, then an exact top-level ref such as HEAD.
# Tags are peeled (the ^{} row), so an annotated tag yields its commit, not the tag object.
set -euo pipefail

ref="${1:?usage: resolve_easytier_ref.sh <ref>}"
url="${EASYTIER_REPO_URL:-https://github.com/EasyTier/EasyTier.git}"

if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
  echo "$ref"
  exit 0
fi

if [[ "$ref" == refs/* ]]; then
  names=("$ref")
else
  names=("refs/heads/$ref" "refs/tags/$ref" "$ref")
fi

# With patterns, ls-remote prints a tag's peeled ^{} row only if a pattern names it too.
patterns=()
for name in "${names[@]}"; do patterns+=("$name" "$name^{}"); done
refs="$(git ls-remote "$url" "${patterns[@]}")"
for name in "${names[@]}"; do
  for want in "$name^{}" "$name"; do
    commit="$(awk -v w="$want" '$2 == w { print $1; exit }' <<<"$refs")"
    if [ -n "$commit" ]; then
      echo "$commit"
      exit 0
    fi
  done
done

echo "::error::cannot resolve EasyTier ref '$ref'" >&2
exit 1
