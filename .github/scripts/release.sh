#!/usr/bin/env bash
# Releases a version: gives the images CI already built for a commit on main their version tags,
# and creates the GitHub release. Run by .github/workflows/release.yml; tested by release_test.sh.
#
# It does not build anything. The images of a version are the ones CI built and tested when the
# commit reached main, tagged :<short commit> at the time, so what is released is byte for byte
# what was tested, and the immutable commit tag is never written again.
#
# Environment:
#   TAG         the version tag, such as v1.2.3 (required)
#   REPOSITORY  owner/name (required)
#   GH_TOKEN    a token for gh (required)
#   DRY_RUN     "true": check everything and say what would be done, write nothing
set -euo pipefail

SERVICES=(auth-service gateway history-service)
MAIN_BRANCH="${MAIN_BRANCH:-main}"
CI_WORKFLOW="${CI_WORKFLOW:-ci.yml}"
CI_WAIT_SECONDS="${CI_WAIT_SECONDS:-1800}"
POLL_SECONDS="${POLL_SECONDS:-20}"
DRY_RUN="${DRY_RUN:-false}"

: "${TAG?TAG is not set}"
: "${REPOSITORY:?REPOSITORY is not set}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

# ---- the tag ----------------------------------------------------------------------------

# Strict on purpose: the tag ends up in image names and in a release, and a release is not
# something to get wrong by typing v1.2 or v1.2.3-rc1. (Pre-releases are not supported yet.)
VERSION_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if [[ ! "$TAG" =~ $VERSION_PATTERN ]]; then
  fail "'$TAG' is not a version tag. It has to look like vMAJOR.MINOR.PATCH, for example v1.2.3."
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
version="$major.$minor.$patch"
minor_tag="$major.$minor"

# refs/tags/ on purpose: a branch that happens to be called v1.2.3 must not pass for the tag.
sha="$(gh api "repos/$REPOSITORY/commits/refs/tags/$TAG" --jq .sha)" \
  || fail "There is no tag $TAG in $REPOSITORY."
sha7="${sha:0:7}"
echo "$TAG is commit $sha7 ($sha)"

# ---- only what is on main ---------------------------------------------------------------

# "behind" and "identical": the commit is main itself or in its history. Anything else is work
# that has not been through main, and a version is not made from that.
relation="$(gh api "repos/$REPOSITORY/compare/$MAIN_BRANCH...$sha" --jq .status)"
case "$relation" in
  identical|behind) echo "commit $sha7 is on $MAIN_BRANCH" ;;
  *) fail "Commit $sha7 is not on $MAIN_BRANCH (it is $relation of it). Tag a commit of $MAIN_BRANCH." ;;
esac

# ---- its CI run must have passed --------------------------------------------------------

# The run that built and pushed the images. Waited for, because the natural moment to tag is
# right after the merge, while that run is still going.
deadline=$(( $(date +%s) + CI_WAIT_SECONDS ))
while true; do
  state="$(gh run list --repo "$REPOSITORY" --workflow "$CI_WORKFLOW" --branch "$MAIN_BRANCH" \
      --commit "$sha" --event push --limit 1 --json status,conclusion \
      --jq 'if length == 0 then "none" else "\(.[0].status)/\(.[0].conclusion)" end')"
  case "$state" in
    completed/success)
      echo "CI passed for $sha7"
      break ;;
    completed/*)
      fail "The CI run for commit $sha7 did not succeed ($state). Nothing was released." ;;
    none)
      [ "$(date +%s)" -lt "$deadline" ] || fail "There is no CI run of $CI_WORKFLOW for commit $sha7 on $MAIN_BRANCH, so there are no images to release." ;;
    *)
      [ "$(date +%s)" -lt "$deadline" ] || fail "The CI run for commit $sha7 has not finished after ${CI_WAIT_SECONDS}s ($state). Run this again when it has." ;;
  esac
  echo "waiting for CI on $sha7 ($state)"
  sleep "$POLL_SECONDS"
done

# ---- the images -------------------------------------------------------------------------

image_of() { echo "ghcr.io/${REPOSITORY,,}-$1"; }

declare -A digest
missing=()
for service in "${SERVICES[@]}"; do
  if found="$(docker buildx imagetools inspect "$(image_of "$service"):$sha7" --format '{{.Manifest.Digest}}' 2>/dev/null)"; then
    digest[$service]="$found"
  else
    missing+=("$service")
  fi
done
[ "${#missing[@]}" -eq 0 ] \
  || fail "There is no image tagged $sha7 for: ${missing[*]}. Nothing was released."

# A published version is not overwritten. The same image again is fine, so a run can be repeated.
for service in "${SERVICES[@]}"; do
  taken="$(docker buildx imagetools inspect "$(image_of "$service"):$version" --format '{{.Manifest.Digest}}' 2>/dev/null || true)"
  if [ -n "$taken" ] && [ "$taken" != "${digest[$service]}" ]; then
    fail "$(image_of "$service"):$version already points at a different image ($taken, not ${digest[$service]}). A published version is not overwritten."
  fi
done

# The moving :MAJOR.MINOR tag follows the newest patch. Releasing an older one afterwards must
# not pull it back.
move_minor=true
newer="$(gh api "repos/$REPOSITORY/git/matching-refs/tags/v$major.$minor." --jq '.[].ref' \
    | sed 's|.*/v[0-9]*\.[0-9]*\.||' | awk -v p="$patch" '$1 + 0 > p + 0 { print; exit }')" || true
if [ -n "$newer" ]; then
  move_minor=false
  echo "v$major.$minor.$newer exists, so :$minor_tag is left where it is"
fi

# ---- the plan ---------------------------------------------------------------------------

echo
echo "Release $TAG (commit $sha7):"
for service in "${SERVICES[@]}"; do
  line="  $(image_of "$service"):$sha7  ->  :$version"
  [ "$move_minor" = true ] && line="$line and :$minor_tag"
  echo "$line"
done

if [ "$DRY_RUN" = "true" ]; then
  echo
  echo "This was a dry run: nothing was tagged and no release was created."
  exit 0
fi

# ---- doing it ---------------------------------------------------------------------------

# Retagging a manifest copies no layers and builds nothing.
for service in "${SERVICES[@]}"; do
  image="$(image_of "$service")"
  tags=(--tag "$image:$version")
  [ "$move_minor" = true ] && tags+=(--tag "$image:$minor_tag")
  docker buildx imagetools create "${tags[@]}" "$image:$sha7"
done

if gh release view "$TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
  echo "The release $TAG exists already: left as it is."
else
  notes="$(cat <<EOF
Container images, public, the ones CI built and tested for commit \`$sha7\`:

\`\`\`
$(for service in "${SERVICES[@]}"; do echo "$(image_of "$service"):$version"; done)
\`\`\`

Pin the full version in production. \`:$minor_tag\` moves to the newest $major.$minor.x.
EOF
)"
  # --verify-tag: the tag must already be there, this never creates one.
  gh release create "$TAG" --repo "$REPOSITORY" --title "$TAG" --verify-tag --generate-notes --notes "$notes"
fi

echo
echo "Released $TAG."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Released $TAG"
    echo
    echo "Commit \`$sha7\`."
    for service in "${SERVICES[@]}"; do echo "- \`$(image_of "$service"):$version\`"; done
  } >> "$GITHUB_STEP_SUMMARY"
fi
