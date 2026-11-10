#!/usr/bin/env bash
# Uploads a build's jars to the downloads bucket, where the site serves them at https://etmc.l5z12.dev/downloads/.
#
#   KIND=release REF=v0.1.0 SHA=<commit>                     publish-downloads.sh <jar dir>
#   KIND=main    REF=main   SHA=<commit>                     publish-downloads.sh <jar dir>
#   KIND=pr      REF=<number> SHA=<head commit> PR_TITLE=… PR_AUTHOR=…  publish-downloads.sh <jar dir>
#
# Needs R2_ACCOUNT_ID, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and BUCKET, from the job's downloads or
# downloads-fork environment (see CONTRIBUTING.md).
#
# The bucket's layout is described in site/src/lib/downloads.ts, and the patterns below must match it. The jars go up
# first and the manifest (index/<path>.json) last, so the site lists a build only once all of it is there. Older
# builds of main and of each pull request are removed, manifest first; releases are kept.
#
# The jar dir can come from a build that a fork's pull request controlled. Only files named like etmc's jars are
# taken, and nothing in the dir is run.
set -euo pipefail

SITE=https://etmc.l5z12.dev
KEEP_MAIN=5   # builds of main
KEEP_PR=3     # builds of each pull request

die() { echo "::error::$*"; exit 1; }

dir=${1:?usage: publish-downloads.sh <jar dir>}
for var in R2_ACCOUNT_ID AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY BUCKET KIND REF SHA; do
  [ -n "${!var:-}" ] || die "$var isn't set. The downloads secrets live in the downloads environments (see CONTRIBUTING.md)."
done
[[ $SHA =~ ^[0-9a-f]{40}$ ]] || die "SHA isn't a full commit hash: $SHA"
case $KIND in
  release)
    [[ $REF =~ ^v[0-9][0-9A-Za-z.+_-]{0,62}$ ]] || die "Not a release tag: $REF"
    path="release/$REF"; scope=""; keep=0 ;;
  main)
    path="ci/main/$SHA"; scope="ci/main"; keep=$KEEP_MAIN ;;
  pr)
    [[ $REF =~ ^[1-9][0-9]{0,6}$ ]] || die "Not a pull request number: $REF"
    path="ci/pr/$REF/$SHA"; scope="ci/pr/$REF"; keep=$KEEP_PR ;;
  *) die "KIND must be release, main or pr, not $KIND" ;;
esac

export AWS_DEFAULT_REGION=auto
# aws-cli v2.23+ adds CRC32 checksums that R2 can reject — only send them when required.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
endpoint=${R2_ENDPOINT:-https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com}   # R2_ENDPOINT: to test against another S3
s3() { aws s3 "$@" --endpoint-url "$endpoint" --only-show-errors; }
s3api() { aws s3api "$@" --endpoint-url "$endpoint" --output json; }

# Copy the jars into a flat staging dir, recording each one for the manifest.
jar_name='^etmc-(fabric|forge|neoforge|paper)-([0-9]+(\.[0-9]+)+)-([0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.]+)?)\.jar$'
classifier='-(sources|javadoc|dev|all|slim)\.jar$'
stage=$(mktemp -d)
entries=$(mktemp)
version=""
while IFS= read -r -d '' file; do
  name=$(basename "$file")
  if [[ $name =~ $classifier ]] || [[ ! $name =~ $jar_name ]]; then
    echo "::warning::Skipping $name: not an etmc jar."
    continue
  fi
  loader=${BASH_REMATCH[1]} mc=${BASH_REMATCH[2]} jar_version=${BASH_REMATCH[4]}
  [ -e "$stage/$name" ] && die "Two jars are named $name."
  [ -z "$version" ] && version=$jar_version
  [ "$jar_version" = "$version" ] || echo "::warning::$name is version $jar_version, not $version."
  cp -- "$file" "$stage/$name"
  jq -nc --arg name "$name" --arg loader "$loader" --arg mc "$mc" \
    --argjson size "$(stat -c %s "$file")" --arg sha256 "$(sha256sum "$file" | cut -d' ' -f1)" \
    '{name: $name, loader: $loader, mc: $mc, size: $size, sha256: $sha256}' >> "$entries"
done < <(find "$dir" -type f -name '*.jar' -print0)
count=$(wc -l < "$entries")
[ "$count" -gt 0 ] || die "No etmc jars in $dir."

manifest=$(mktemp)
jq -n --arg path "$path" --arg kind "$KIND" --arg ref "$REF" --arg sha "$SHA" --arg version "$version" \
  --arg date "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg title "${PR_TITLE:-}" --arg author "${PR_AUTHOR:-}" \
  --slurpfile files "$entries" '
  {path: $path, kind: $kind, ref: $ref, sha: $sha, date: $date, version: $version}
  + (if $kind == "pr" then {pr: {number: ($ref | tonumber), title: $title, author: $author}} else {} end)
  + {files: ($files | sort_by(.loader, (.mc | split(".") | map(tonumber))))}' > "$manifest"

# The catalog is built from the index's listing alone, so the summary rides along as R2 metadata. That only holds
# ASCII, so text is percent-encoded (the Worker decodes it), and the title is cut short to fit R2's 2 KB.
metadata=$(jq -nc --arg version "$version" --arg files "$count" --arg title "${PR_TITLE:-}" --arg author "${PR_AUTHOR:-}" '
  {version: $version, files: $files}
  + (if $title != "" then {"pr-title": ($title[0:120] | @uri), "pr-author": ($author[0:60] | @uri)} else {} end)')

echo "Uploading $count jars to $path"
s3 sync "$stage" "s3://$BUCKET/$path/" --delete --content-type application/java-archive
s3 cp "$manifest" "s3://$BUCKET/index/$path.json" --content-type application/json --cache-control no-cache \
  --metadata "$metadata"

if [ -n "$scope" ]; then
  # Newest first. Past the newest $keep, the manifest goes first, so the site stops listing a build before its jars go.
  kept=" $SHA "
  n=0
  while IFS= read -r key; do
    sha=${key##*/}; sha=${sha%.json}
    n=$((n + 1))
    if [ "$n" -le "$keep" ]; then kept+="$sha "; continue; fi
    echo "Removing $scope/$sha"
    s3 rm "s3://$BUCKET/$key"
  done < <(s3api list-objects-v2 --bucket "$BUCKET" --prefix "index/$scope/" | jq -r '
    [.Contents // [] | .[] | select(.Key | test("/[0-9a-f]{40}\\.json$"))] | sort_by(.LastModified) | reverse | .[].Key')
  # Then the jars of every build that no longer has a manifest, including uploads that stopped partway.
  while IFS= read -r prefix; do
    sha=${prefix%/}; sha=${sha##*/}
    [[ $kept == *" $sha "* ]] && continue
    s3 rm "s3://$BUCKET/$prefix" --recursive
  done < <(s3api list-objects-v2 --bucket "$BUCKET" --prefix "$scope/" --delimiter / | jq -r '.CommonPrefixes // [] | .[].Prefix')
fi

url="$SITE/downloads/$path/"
echo "Published $count jars: $url"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "Published $count jars to [$url]($url)." >> "$GITHUB_STEP_SUMMARY"
exit 0
