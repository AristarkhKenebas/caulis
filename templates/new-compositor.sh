#!/usr/bin/env bash
# Creates a new compositor project from the starter template:
#
#   templates/new-compositor.sh ~/my-compositor
#
# Then: cd ~/my-compositor && ./gradlew run
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <folder for the new project>" >&2
    exit 2
fi
target="$1"
if [[ -e "$target" ]]; then
    echo "$target already exists; choose a new folder." >&2
    exit 1
fi

caulis="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
name="$(basename "$target")"

mkdir -p "$(dirname "$target")"
cp -r "$caulis/templates/starter" "$target"
# The Gradle wrapper: the right Gradle version, downloaded on first use.
cp "$caulis/gradlew" "$caulis/gradlew.bat" "$target/"
mkdir -p "$target/gradle"
cp -r "$caulis/gradle/wrapper" "$target/gradle/"
# Where the Caulis sources are (until Caulis is published, they are built along).
echo "caulis.sources=$caulis" > "$target/gradle.properties"
sed -i "s/^rootProject.name = .*/rootProject.name = \"$name\"/" "$target/settings.gradle.kts"
sed -i "s/build\/install\/my-compositor\/bin\/my-compositor/build\/install\/$name\/bin\/$name/" "$target/README.md"

echo "Created $target. Next:"
echo "  cd $target"
echo "  ./gradlew run"
