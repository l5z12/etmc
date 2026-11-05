#!/usr/bin/env bash
# Resolves an EasyTier ref (branch, tag, full ref name or 40-hex sha) to a commit sha on stdout.
#
# `git ls-remote <url> <pattern>` matches patterns against the END of ref names, so a bare "main"
# also matches refs like refs/for/main (EasyTier has one, pointing at a 2024 commit), and which one
# `head -1` picks depends on sort order. So we list candidates and keep only exact names, preferring
# a branch, then a tag (peeled to its commit for annotated tags), then a full ref name.
set -euo pipefail

ref="${1:?usage: resolve_easytier_ref.sh <ref>}"
url="${EASYTIER_REPO_URL:-https://github.com/EasyTier/EasyTier.git}"

if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
  echo "$ref"
  exit 0
fi

refs="$(git ls-remote "$url" "refs/heads/$ref" "refs/tags/$ref" "$ref")"
for want in "refs/heads/$ref" "refs/tags/$ref^{}" "refs/tags/$ref" "$ref"; do
  commit="$(awk -v w="$want" '$2 == w { print $1; exit }' <<<"$refs")"
  if [ -n "$commit" ]; then
    echo "$commit"
    exit 0
  fi
done

echo "::error::cannot resolve EasyTier ref '$ref'" >&2
exit 1
