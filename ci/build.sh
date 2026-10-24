#!/usr/bin/env bash
# Builds and tests everything, the same way on every CI system (GitHub, GitLab, ...) and
# on your own computer:
#
#   ci/build.sh
#
# Results: test reports in */build/reports/tests, the irisWM archives in
# apps/iriswm/build/distributions.
set -euo pipefail
cd "$(dirname "$0")/.."

gradle_options=(--console=plain --stacktrace)
# On CI machines, a daemon that outlives the job only costs memory.
[[ -n "${CI:-}" ]] && gradle_options+=(--no-daemon)

echo "== Caulis: build and test"
./gradlew "${gradle_options[@]}" build

echo "== irisWM: build, test and package"
(cd apps/iriswm && ./gradlew "${gradle_options[@]}" build distTar distZip)

echo "== The project template still builds"
template_dir="$(mktemp -d)"
templates/new-compositor.sh "$template_dir/template-check" > /dev/null
(cd "$template_dir/template-check" && ./gradlew "${gradle_options[@]}" build)
rm -rf "$template_dir"

echo "== Done"
ls -l apps/iriswm/build/distributions
