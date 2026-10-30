#!/usr/bin/env bash
# The public API of all published modules together, one class after another in name order,
# whichever module a class is in:
#
#   ci/abi-union.sh > before.txt     (then move files between modules, ./gradlew updateKotlinAbi)
#   ci/abi-union.sh > after.txt
#   diff before.txt after.txt        (nothing: a pure move; users of the artifacts see no change)
#
# Moving a file to another module keeps its package, so its classes stay the same classes;
# only the api/<module>.api file they are listed in changes. This compares what users can call.
set -euo pipefail
cd "$(dirname "$0")/.."

# Each class is a paragraph of an .api file (blank lines between): one per line, sorted, then
# turned back into paragraphs.
for dump in caulis-*/api/*.api; do
    awk 'BEGIN { RS = ""; ORS = "\n" } { gsub(/\n/, "\x1f"); print }' "$dump"
done | LC_ALL=C sort | awk '{ gsub(/\x1f/, "\n"); print; print "" }'
