#!/usr/bin/env bash
# Builds and tests everything, the same way on every CI system (GitHub, GitLab, ...) and
# on your own computer:
#
#   ci/build.sh
#
# Results: test reports in */build/reports/tests, the library jars in caulis-*/build/libs.
set -euo pipefail
cd "$(dirname "$0")/.."

gradle_options=(--console=plain --stacktrace)
# On CI machines, a daemon that outlives the job only costs memory.
# (And --continue: one run shows every failing module, not just the first.)
[[ -n "${CI:-}" ]] && gradle_options+=(--no-daemon --continue)
# A release (CAULIS_VERSION=0.1.7): the jars carry that version, not the catalog's.
[[ -n "${CAULIS_VERSION:-}" ]] && gradle_options+=(-Pcaulis.version="$CAULIS_VERSION")

echo "== Caulis: build and test"
./gradlew "${gradle_options[@]}" build

echo "== The project template still builds"
template_dir="$(mktemp -d)"
templates/new-compositor.sh --sources "$template_dir/template-check" > /dev/null
(cd "$template_dir/template-check" && ./gradlew "${gradle_options[@]}" build)
rm -rf "$template_dir"

echo "== Done"
ls -l caulis-*/build/libs
