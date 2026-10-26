#!/usr/bin/env bash
# Creates a new compositor project from the starter template:
#
#   templates/new-compositor.sh ~/my-compositor
#   templates/new-compositor.sh --sources ~/my-compositor   (Caulis built from this folder)
#
# Then: cd ~/my-compositor && ./gradlew run
set -euo pipefail

sources=false
if [[ "${1:-}" == "--sources" ]]; then
    sources=true
    shift
fi
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 [--sources] <folder for the new project>" >&2
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
# Caulis from Maven Central; with --sources, built along from this folder (its changes at once).
if $sources; then
    echo "caulis.sources=$caulis" > "$target/gradle.properties"
    flake_caulis="path:$caulis"
else
    flake_caulis="github:AristarkhKenebas/caulis"
fi
# Its flake: that Caulis, its own name.
sed -i "s|path:CAULIS|$flake_caulis|; s/my-compositor/$name/g" "$target/flake.nix" "$target/package.nix"
sed -i "s/^rootProject.name = .*/rootProject.name = \"$name\"/" "$target/settings.gradle.kts"
sed -i "s/configName = \"my-compositor\"/configName = \"$name\"/; s|~/.config/my-compositor|~/.config/$name|" "$target/src/main/kotlin/Main.kt"
sed -i "s/build\/install\/my-compositor\/bin\/my-compositor/build\/install\/$name\/bin\/$name/" "$target/README.md"

echo "Created $target. Next:"
echo "  cd $target"
echo "  ./gradlew run"
