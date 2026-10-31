#!/usr/bin/env bash
# Creates a new compositor project from a template:
#
#   templates/new-compositor.sh ~/my-compositor                      (the starter)
#   templates/new-compositor.sh --template tiling ~/my-compositor
#   templates/new-compositor.sh --list                               (the templates there are)
#   templates/new-compositor.sh --sources ~/my-compositor            (Caulis built from this folder)
#
# Then: cd ~/my-compositor && ./gradlew run
#
# Every project starts from templates/starter. A template is a folder next to it with what is
# different: its own files over the starter's, "about" (a line saying what it is), and "modules"
# (the Caulis modules it needs, a name a line).
set -euo pipefail

caulis="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"

# Each template with its words: its file "about".
list_templates() {
    for folder in "$caulis"/templates/*/; do
        [[ -f "$folder/about" ]] || continue # (templates/plugins: not compositors)
        name="$(basename "$folder")"
        printf '  %-10s %s\n' "$name" "$(cat "$folder/about")"
    done
}

usage() {
    echo "Usage: $0 [--template <name>] [--sources] <folder for the new project>"
    echo "       $0 --list"
    echo
    echo "Templates:"
    list_templates
}

sources=false
template=starter
target=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --sources) sources=true ;;
        --template)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            template="$2"
            shift
            ;;
        --template=*) template="${1#--template=}" ;;
        --list) list_templates; exit 0 ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "Unknown option $1" >&2; usage >&2; exit 2 ;;
        *)
            [[ -z "$target" ]] || { usage >&2; exit 2; }
            target="$1"
            ;;
    esac
    shift
done
if [[ -z "$target" ]]; then
    usage >&2
    exit 2
fi
if [[ ! "$template" =~ ^[a-z0-9-]+$ || ! -f "$caulis/templates/$template/about" ]]; then
    echo "There is no template \"$template\". The templates:" >&2
    list_templates >&2
    exit 2
fi
if [[ -e "$target" ]]; then
    echo "$target already exists; choose a new folder." >&2
    exit 1
fi

name="$(basename "$target")"
# The name goes into the project's files (and names its settings folder): plain characters only.
if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "\"$name\" cannot be a compositor's name: use letters, digits, '.', '_' and '-' (e.g. my-compositor)." >&2
    exit 2
fi

mkdir -p "$(dirname "$target")"
cp -r "$caulis/templates/starter" "$target"
rm -f "$target/about"
if [[ "$template" != starter ]]; then
    cp -r "$caulis/templates/$template/." "$target/"
    rm -f "$target/about"
    # The modules it needs: their lines in build.gradle.kts turned on (each is there, off).
    if [[ -f "$target/modules" ]]; then
        while read -r module; do
            [[ -n "$module" ]] || continue
            sed -i "s|^\( *\)// \(implementation(\"io.github.aristarkhkenebas:caulis-$module:\)|\1\2|" "$target/build.gradle.kts"
            if [[ "$module" == compose ]]; then
                sed -i 's|^\( *\)// \(kotlin("plugin.compose")\)|\1\2|' "$target/build.gradle.kts"
                sed -i 's|^\( *\)// \(google()\)|\1\2|' "$target/settings.gradle.kts"
            fi
        done < "$target/modules"
        rm "$target/modules"
    fi
fi
# The Gradle wrapper: the right Gradle version, downloaded on first use.
cp "$caulis/gradlew" "$caulis/gradlew.bat" "$target/"
mkdir -p "$target/gradle"
cp -r "$caulis/gradle/wrapper" "$target/gradle/"
# Caulis from Maven Central or its GitHub Pages mirror (settings.gradle.kts); with --sources, built along from this folder (its changes at once).
if $sources; then
    echo "caulis.sources=$caulis" > "$target/gradle.properties"
    flake_caulis="path:$caulis"
else
    flake_caulis="github:AristarkhKenebas/caulis"
fi
# Its flake: that Caulis, its own name.
sed -i "s|path:CAULIS|$flake_caulis|; s/my-compositor/$name/g" "$target/flake.nix" "$target/package.nix"
sed -i "s/^rootProject.name = .*/rootProject.name = \"$name\"/" "$target/settings.gradle.kts"
find "$target/src" -type f -exec sed -i "s/configName = \"my-compositor\"/configName = \"$name\"/; s|~/.config/my-compositor|~/.config/$name|g" {} +
sed -i "s/build\/install\/my-compositor\/bin\/my-compositor/build\/install\/$name\/bin\/$name/; s|~/.config/my-compositor|~/.config/$name|g" "$target/README.md"

echo "Created $target. Next:"
echo "  cd $target"
echo "  ./gradlew run"
