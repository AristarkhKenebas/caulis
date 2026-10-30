#!/usr/bin/env bash
# Builds and tests newm (the compositor built on Caulis) against THIS checkout of Caulis, the
# way a Caulis change reaches its biggest user before a release:
#
#   ci/build-newm.sh                 newm next to this folder (../newm), else a fresh clone
#   NEWM_DIR=~/src/newm ci/build-newm.sh
#   NEWM_REPO=https://... NEWM_REF=main ci/build-newm.sh
#
# newm's build swaps io.github.aristarkhkenebas:* for the sources in ../caulis when run with
# -Pcaulis.local=true. So newm and Caulis have to be next to each other: when they are not
# (CI, or newm somewhere else), both are linked into a temporary folder.
#
# Tests run headless (CAULIS_MODE=headless) with a temporary HOME: nothing of the desktop the
# script runs on is touched.
set -euo pipefail
caulis="$(cd "$(dirname "$0")/.." && pwd)"

gradle_options=(--console=plain -Pcaulis.local=true)
# On CI machines, a daemon that outlives the job only costs memory.
[[ -n "${CI:-}" ]] && gradle_options+=(--no-daemon)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

newm="${NEWM_DIR:-}"
if [[ -z "$newm" && -d "$caulis/../newm" ]]; then newm="$(cd "$caulis/../newm" && pwd)"; fi
if [[ -z "$newm" ]]; then
    echo "== Cloning newm (${NEWM_REPO:-https://github.com/AristarkhKenebas/newm})"
    git clone --quiet --depth 1 ${NEWM_REF:+--branch "$NEWM_REF"} "${NEWM_REPO:-https://github.com/AristarkhKenebas/newm}" "$work/newm"
    newm="$work/newm"
fi

# newm looks for Caulis in ../caulis. If that is not this checkout, newm's committed state is
# cloned next to a link to this checkout (a link to newm would not do: Gradle follows it, and
# ../caulis would then be looked for next to the real folder).
if ! [[ "$(cd -P "$newm/.." && pwd)/caulis" -ef "$caulis" ]]; then
    if [[ "$newm" != "$work/newm" ]]; then
        echo "== newm is not next to this Caulis: using a clone of its last commit"
        git clone --quiet "$newm" "$work/newm"
    fi
    ln -s "$caulis" "$work/caulis"
    newm="$work/newm"
fi
[[ -d "$newm/../caulis/caulis-core" ]] || { echo "No Caulis sources next to $newm" >&2; exit 1; }

mkdir -p "$work/home"
echo "== newm against Caulis in $caulis"
cd "$newm"
./gradlew "${gradle_options[@]}" installDist
# (Gradle's own files stay where they are: not downloaded again into the temporary HOME.)
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
CAULIS_MODE=headless HOME="$work/home" GRADLE_USER_HOME="$gradle_home" ./gradlew "${gradle_options[@]}" test

echo "== Done"
