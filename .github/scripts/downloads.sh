#!/usr/bin/env bash
# The downloads bucket, which the site serves at https://etmc.l5z12.dev/downloads/.
#
#   downloads.sh publish <jar dir>   uploads a build's jars (.github/actions/publish-downloads runs this):
#     KIND=release REF=v0.1.0     SHA=<commit>
#     KIND=main    REF=main       SHA=<commit>
#     KIND=pr      REF=<number>   SHA=<head commit>   PR_TITLE=…   PR_AUTHOR=…
#   downloads.sh sweep               removes the test builds of closed pull requests (downloads-sweep.yml).
#                                    Needs GH_TOKEN and GITHUB_REPOSITORY.
#
# Both need R2_ACCOUNT_ID, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and BUCKET, from the job's downloads or
# downloads-fork environment (see CONTRIBUTING.md).
#
# The bucket's layout is described in site/src/lib/downloads.ts, and the patterns below must match it. The jars go up
# first and the manifest (index/<path>.json) last, so the site lists a build only once all of it is there. A build is
# removed manifest first, so the site stops listing it before its jars go.
#
# The jar dir can come from a build that a fork's pull request controlled. Only files named like etmc's jars are
# taken, and nothing in the dir is run.
set -euo pipefail

SITE=https://etmc.l5z12.dev
KEEP_MAIN=5   # builds of main
KEEP_PR=3     # builds of each pull request

die() { echo "::error::$*"; exit 1; }

for var in R2_ACCOUNT_ID AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY BUCKET; do
  [ -n "${!var:-}" ] || die "$var isn't set. The downloads secrets live in the downloads environments (see CONTRIBUTING.md)."
done

export AWS_DEFAULT_REGION=auto
# aws-cli v2.23+ adds CRC32 checksums that R2 can reject — only send them when required.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
endpoint=${R2_ENDPOINT:-https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com}   # R2_ENDPOINT: to test against another S3
s3() { aws s3 "$@" --endpoint-url "$endpoint" --only-show-errors; }
s3api() { aws s3api "$@" --endpoint-url "$endpoint" --output json; }

# Every listing below goes into a variable before anything is removed: there, a failed listing stops the script,
# where one read through a pipe or <(…) would pass for an empty bucket, and the builds it missed would be deleted.
# The sub-prefixes (one per build, or per pull request) directly under a prefix.
dirs() { s3api list-objects-v2 --bucket "$BUCKET" --prefix "$1" --delimiter / | jq -r '.CommonPrefixes // [] | .[].Prefix'; }

publish() {
  local dir=${1:?usage: downloads.sh publish <jar dir>}
  for var in KIND REF SHA; do
    [ -n "${!var:-}" ] || die "$var isn't set."
  done
  [[ $SHA =~ ^[0-9a-f]{40}$ ]] || die "SHA isn't a full commit hash: $SHA"
  local path scope keep
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

  # Copy the jars into a flat staging dir, recording each one for the manifest.
  local jar_name='^etmc-(fabric|forge|neoforge|paper)-([0-9]+(\.[0-9]+)+)-([0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.]+)?)\.jar$'
  local classifier='-(sources|javadoc|dev|all|slim)\.jar$'
  local stage entries version="" file name loader mc jar_version count
  stage=$(mktemp -d)
  entries=$(mktemp)
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

  local manifest metadata
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
    # Newest first. Past the newest $keep, the manifest goes.
    local manifests builds key prefix sha kept=" $SHA " n=0
    manifests=$(s3api list-objects-v2 --bucket "$BUCKET" --prefix "index/$scope/" | jq -r '
      [.Contents // [] | .[] | select(.Key | test("/[0-9a-f]{40}\\.json$"))] | sort_by(.LastModified) | reverse | .[].Key')
    builds=$(dirs "$scope/")
    while IFS= read -r key; do
      [ -n "$key" ] || continue
      sha=${key##*/}; sha=${sha%.json}
      n=$((n + 1))
      if [ "$n" -le "$keep" ]; then kept+="$sha "; continue; fi
      echo "Removing $scope/$sha"
      s3 rm "s3://$BUCKET/$key"
    done <<< "$manifests"
    # Then the jars of every build that has no manifest left, including uploads that stopped partway.
    while IFS= read -r prefix; do
      [ -n "$prefix" ] || continue
      sha=${prefix%/}; sha=${sha##*/}
      [[ $kept == *" $sha "* ]] && continue
      s3 rm "s3://$BUCKET/$prefix" --recursive
    done <<< "$builds"
  fi

  local url="$SITE/downloads/$path/"
  echo "Published $count jars: $url"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "Published $count jars to [$url]($url)." >> "$GITHUB_STEP_SUMMARY"; fi
}

sweep() {
  for var in GH_TOKEN GITHUB_REPOSITORY; do
    [ -n "${!var:-}" ] || die "$var isn't set."
  done
  # Every pull request with something in the bucket: a manifest, or jars left by an upload that stopped partway.
  local indexed uploaded numbers number state removed=0
  indexed=$(dirs index/ci/pr/)
  uploaded=$(dirs ci/pr/)
  numbers=$(printf '%s\n%s\n' "$indexed" "$uploaded" | sed -nE 's#^(index/)?ci/pr/([1-9][0-9]{0,6})/$#\2#p' | sort -un)
  for number in $numbers; do
    if ! state=$(gh api "repos/$GITHUB_REPOSITORY/pulls/$number" --jq .state); then
      echo "::warning::Couldn't look up pull request #$number, so its builds stay."
      continue
    fi
    [ "$state" = closed ] || continue
    echo "Removing the builds of #$number, which is closed"
    s3 rm "s3://$BUCKET/index/ci/pr/$number/" --recursive
    s3 rm "s3://$BUCKET/ci/pr/$number/" --recursive
    removed=$((removed + 1))
  done
  echo "Removed the builds of $removed closed pull requests."
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "Removed the builds of $removed closed pull requests." >> "$GITHUB_STEP_SUMMARY"; fi
}

case ${1:-} in
  publish) shift; publish "$@" ;;
  sweep) sweep ;;
  *) die "usage: downloads.sh publish <jar dir> | downloads.sh sweep" ;;
esac
