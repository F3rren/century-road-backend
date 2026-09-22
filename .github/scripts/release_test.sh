#!/usr/bin/env bash
# Tests for release.sh. `gh` and `docker` are replaced by stand-ins that answer from files and
# record every call, so what the script decides, and what it would have written to the registry
# or to GitHub, can be checked without either. Run it: bash .github/scripts/release_test.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/release.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

SHA="d273f0d1111111111111111111111111111111aa"
SHA7="d273f0d"
OWNER_REPO="F3rren/century-road-backend"
PREFIX="ghcr.io/f3rren/century-road-backend"
SERVICES="auth-service gateway history-service"
DIGEST="sha256:aaaa"

# ---- the stand-ins ------------------------------------------------------------------------

mkdir -p "$WORK/bin"

cat > "$WORK/bin/gh" <<'SHIM'
#!/usr/bin/env bash
echo "gh $*" >> "$STATE/log"
case "$1" in
  api)
    case "$2" in
      */commits/*)
        [ -n "${SHIM_NO_TAG:-}" ] && { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
        echo "$SHIM_SHA" ;;
      */compare/*) echo "${SHIM_COMPARE:-behind}" ;;
      */git/matching-refs/*) [ -n "${SHIM_TAG_REFS:-}" ] && printf '%s\n' "$SHIM_TAG_REFS" ; exit 0 ;;
    esac ;;
  run)
    # Each call answers with the next line of the script and stays on the last one.
    line="$(head -n 1 "$STATE/ci")"
    [ "$(wc -l < "$STATE/ci")" -gt 1 ] && sed -i '1d' "$STATE/ci"
    echo "$line" ;;
  release)
    case "$2" in
      view) [ "${SHIM_RELEASE_EXISTS:-}" = 1 ] ;;
      create) : ;;
    esac ;;
esac
SHIM

cat > "$WORK/bin/docker" <<'SHIM'
#!/usr/bin/env bash
echo "docker $*" >> "$STATE/log"
file_of() { echo "$STATE/images/${1//[\/:]/_}"; }
case "$1 $2 $3" in
  "buildx imagetools inspect")
    f="$(file_of "$4")"
    if [ -f "$f" ]; then cat "$f"; else echo "ERROR: $4: not found" >&2; exit 1; fi ;;
  "buildx imagetools create")
    shift 3
    tags=(); source=""
    while [ $# -gt 0 ]; do
      if [ "$1" = "--tag" ]; then tags+=("$2"); shift 2; else source="$1"; shift; fi
    done
    [ -f "$(file_of "$source")" ] || { echo "ERROR: $source: not found" >&2; exit 1; }
    for t in "${tags[@]}"; do cp "$(file_of "$source")" "$(file_of "$t")"; done ;;
esac
SHIM
chmod +x "$WORK/bin/gh" "$WORK/bin/docker"

# ---- a case -------------------------------------------------------------------------------

# new_case: a clean state where the three images of $SHA7 exist and CI has succeeded.
new_case() {
  STATE="$WORK/case-$1"
  rm -rf "$STATE"; mkdir -p "$STATE/images"
  : > "$STATE/log"
  echo "completed/success" > "$STATE/ci"
  for s in $SERVICES; do echo "$DIGEST" > "$STATE/images/${PREFIX//[\/:]/_}-${s}_$SHA7"; done
  export STATE SHIM_SHA="$SHA"
  unset SHIM_NO_TAG SHIM_COMPARE SHIM_TAG_REFS SHIM_RELEASE_EXISTS DRY_RUN
}

# run_release TAG: runs the script with the stand-ins first on PATH.
run_release() {
  ( cd "$STATE" && PATH="$WORK/bin:$PATH" GH_TOKEN=x REPOSITORY="$OWNER_REPO" TAG="$1" \
      POLL_SECONDS=0 CI_WAIT_SECONDS="${CI_WAIT_SECONDS:-2}" bash "$SCRIPT" ) > "$STATE/out" 2>&1
  EXIT=$?
}

ok()   { PASSED=$((PASSED + 1)); echo "  ok    $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }

expect_success() {
  if [ "$EXIT" -eq 0 ]; then ok "$1"; else fail "$1" "exit $EXIT: $(tail -n 3 "$STATE/out" | tr '\n' ' ')"; fi
}
expect_failure() {
  if [ "$EXIT" -ne 0 ]; then ok "$1"; else fail "$1" "expected a failure, got exit 0"; fi
}
expect_output() {
  if grep -qF -- "$2" "$STATE/out"; then ok "$1"; else fail "$1" "output lacks: $2 | got: $(tail -n 3 "$STATE/out" | tr '\n' ' ')"; fi
}
expect_logged() {
  if grep -qF -- "$2" "$STATE/log"; then ok "$1"; else fail "$1" "never called: $2"; fi
}
expect_not_logged() {
  if grep -qF -- "$2" "$STATE/log"; then fail "$1" "called, but should not have: $2"; else ok "$1"; fi
}
nothing_written() { # neither the registry nor GitHub was written to
  expect_not_logged "$1: no image was tagged" "imagetools create"
  expect_not_logged "$1: no release was created" "release create"
}

# ---- what is not a version ---------------------------------------------------------------

echo "a tag that is not a version"
for bad in "v1.2" "1.2.3" "v1.2.3-rc1" "vx.y.z" "v1.2.3;rm" "v01.2.3" "v1.02.3" "v1.2.3x" ""; do
  new_case bad
  run_release "$bad"
  expect_failure "'$bad' is refused"
  nothing_written "'$bad'"
done
new_case bad-message; run_release "v1.2"
expect_output "the refusal says what a version tag looks like" "vMAJOR.MINOR.PATCH"

# ---- where the tag points ---------------------------------------------------------------

echo "a tag that cannot be released"
new_case missing-tag; export SHIM_NO_TAG=1; run_release "v1.2.3"
expect_failure "a tag that does not exist"; nothing_written "missing tag"

for status in ahead diverged; do
  new_case "off-main-$status"; export SHIM_COMPARE="$status"; run_release "v1.2.3"
  expect_failure "a commit that is $status of main"
  expect_output "it says the commit is not on main" "not on main"
  nothing_written "$status"
done

# ---- CI --------------------------------------------------------------------------------

echo "the CI run of that commit"
new_case ci-failed; echo "completed/failure" > "$STATE/ci"; run_release "v1.2.3"
expect_failure "a failed run"; expect_output "it says the run did not succeed" "did not succeed"; nothing_written "ci failed"

new_case ci-none; echo "none" > "$STATE/ci"; CI_WAIT_SECONDS=1 run_release "v1.2.3"
expect_failure "no run at all after waiting"; expect_output "it says none was found" "no CI run"; nothing_written "no ci"

new_case ci-waits; printf 'in_progress/\nin_progress/\ncompleted/success\n' > "$STATE/ci"; run_release "v1.2.3"
expect_success "a run still going is waited for"
expect_output "it says it is waiting rather than going ahead" "waiting for CI"
expect_logged "and the release goes ahead once it succeeds" "imagetools create"

new_case ci-stuck; echo "in_progress/" > "$STATE/ci"; CI_WAIT_SECONDS=1 run_release "v1.2.3"
expect_failure "a run that never finishes is given up on"
expect_output "it says the run has not finished" "has not finished"
nothing_written "ci stuck"

# ---- the images ------------------------------------------------------------------------

echo "the images"
new_case image-missing; rm "$STATE/images/${PREFIX//[\/:]/_}-gateway_$SHA7"; run_release "v1.2.3"
expect_failure "one service has no image for the commit"
expect_output "it names the one that is missing" "gateway"
nothing_written "image missing"

new_case version-taken; echo "sha256:other" > "$STATE/images/${PREFIX//[\/:]/_}-auth-service_1.2.3"; run_release "v1.2.3"
expect_failure "a version already published for a different image"
expect_output "it says the version tag is not to be overwritten" "already points at a different image"
nothing_written "version taken"

new_case version-same; for s in $SERVICES; do echo "$DIGEST" > "$STATE/images/${PREFIX//[\/:]/_}-${s}_1.2.3"; done; run_release "v1.2.3"
expect_success "the same release run again is fine"

# ---- doing it --------------------------------------------------------------------------

echo "releasing"
new_case dry; export DRY_RUN=true; run_release "v1.2.3"
expect_success "a dry run succeeds"; expect_output "and says it was one" "dry run"; nothing_written "dry run"

new_case happy; run_release "v1.2.3"
expect_success "a release goes through"
for s in $SERVICES; do
  expect_logged "$s: :1.2.3 and :1.2 made from the commit's image, not rebuilt" \
    "imagetools create --tag $PREFIX-$s:1.2.3 --tag $PREFIX-$s:1.2 $PREFIX-$s:$SHA7"
done
expect_logged "the GitHub release is created for the tag" "release create v1.2.3"
expect_logged "it refuses to create the tag itself" "--verify-tag"
expect_logged "with the notes GitHub generates" "--generate-notes"
expect_not_logged "the moving :latest is left to main" ":latest"

new_case older-patch; export SHIM_TAG_REFS=$'refs/tags/v1.2.3\nrefs/tags/v1.2.4'; run_release "v1.2.3"
expect_success "an older patch can still be released"
expect_logged ":1.2.3 is tagged" "--tag $PREFIX-gateway:1.2.3 $PREFIX-gateway:$SHA7"
expect_not_logged ":1.2 stays with the newer patch" "--tag $PREFIX-gateway:1.2 "

new_case release-exists; export SHIM_RELEASE_EXISTS=1; run_release "v1.2.3"
expect_success "a release that already exists is left alone"
expect_not_logged "and is not created again" "release create"
expect_logged "the images are still tagged" "imagetools create"

# ------------------------------------------------------------------------------------------

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
