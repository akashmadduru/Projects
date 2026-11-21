#!/bin/bash
# Runs Gradle tasks inside an eclipse-temurin:25 container with Testcontainers access.
# usage: .openforge/scripts/gradle.sh [-C <checkout dir>] <gradle tasks...>
# Formats (spotlessApply) every module named in the tasks first. Prints errors and gradle_rc=<code>.
# Run one at a time: parallel containers crash Docker Desktop on this machine.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ "${1:-}" = "-C" ]; then ROOT=$(cd "$2" && pwd); shift 2; fi
R=$( (cd "$ROOT" && pwd -W) 2>/dev/null || echo "$ROOT")
TASKS="$*"
ID=$$
# Host-wide lock: one build at a time across all checkouts and agents (mkdir is atomic).
# A lock whose owner process is gone is taken over; waits up to FORGE_GRADLE_LOCK_WAIT seconds (default 1800).
LOCK="${TMPDIR:-/tmp}/openforge-gradle.lock"
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  owner=$(cat "$LOCK/pid" 2>/dev/null || echo "")
  if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then rm -rf "$LOCK"; continue; fi
  if [ "$waited" -ge "${FORGE_GRADLE_LOCK_WAIT:-1800}" ]; then echo "gradle.sh: another build holds $LOCK (pid $owner); gave up"; echo "gradle_rc=75"; exit 75; fi
  [ "$waited" -eq 0 ] && echo "gradle.sh: waiting for another build (pid $owner) to finish"
  sleep 5; waited=$((waited + 5))
done
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT
APPLY=$(echo "$TASKS" | tr ' ' '\n' | grep -oE '^:(services|libs):[^:]+(:[^:]+-app)?' | sort -u | sed 's/$/:spotlessApply/' | tr '\n' ' ')
MSYS_NO_PATHCONV=1 docker run --rm -v "$R:/repo" -v gradle-cache2:/root/.gradle \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  --add-host=host.docker.internal:host-gateway -w /repo eclipse-temurin:25-jdk bash -c \
  "sed -i 's/\r$//' gradlew; ./gradlew -q --no-daemon --continue --project-cache-dir /tmp/pc$ID $APPLY >/dev/null 2>&1; ./gradlew --no-daemon --continue --project-cache-dir /tmp/pc$ID $TASKS 2>&1 | grep -v classfile | grep -E 'error:|warning: \[|What went wrong|Execution failed|FAILED|tests completed|BUILD' | head -60; exit \${PIPESTATUS[0]}"
rc=$?
# Windows checkouts use CRLF; the container rewrites some files (gradlew, Spotless output) with LF.
# Restore every file whose only change is its line endings, so builds never dirty the checkout.
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  git -C "$ROOT" status --porcelain | grep '^ M ' | cut -c4- | while read -r f; do
    # same content once line endings are ignored: rewrite it from the index (clears the stale entry)
    if git -C "$ROOT" diff --quiet --ignore-cr-at-eol -- "$f" 2>/dev/null; then rm -f "${ROOT:?}/${f:?}" && git -C "$ROOT" checkout -q -- "$f"; fi
  done
fi
echo "gradle_rc=$rc"
exit $rc
