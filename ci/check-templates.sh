#!/usr/bin/env bash
# Creates a project from every template (templates/new-compositor.sh) and builds it against THIS
# checkout of Caulis, so a change that breaks a template shows at once:
#
#   ci/check-templates.sh                    all of them
#   ci/check-templates.sh tiling kiosk       these
#   ci/check-templates.sh --run              and start each, headless, for a few seconds
#
# Nothing of your desktop is touched: the projects are in a temporary folder, and the runs have a
# temporary home (for Java too: it takes user.home from the system, not from $HOME), their own bus
# and software rendering.
set -euo pipefail
caulis="$(cd "$(dirname "$0")/.." && pwd)"

run=false
names=()
for argument in "$@"; do
    case "$argument" in
        --run) run=true ;;
        *) names+=("$argument") ;;
    esac
done
if [[ ${#names[@]} -eq 0 ]]; then
    for folder in "$caulis"/templates/*/; do names+=("$(basename "$folder")"); done
fi

gradle_options=(--console=plain)
# On CI machines, a daemon that outlives the job only costs memory.
[[ -n "${CI:-}" ]] && gradle_options+=(--no-daemon)
# Gradle's own files stay where they are: not downloaded again.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for name in "${names[@]}"; do
    echo "== Template $name"
    project="$work/check-$name"
    "$caulis/templates/new-compositor.sh" --sources --template "$name" "$project" > /dev/null
    (cd "$project" && ./gradlew "${gradle_options[@]}" build installDist)
    # Nothing of the placeholder name may stay behind.
    if grep -rl "my-compositor" "$project/src" "$project/README.md" "$project/settings.gradle.kts"; then
        echo "Template $name: the placeholder name is left in the files above" >&2
        exit 1
    fi
    if $run; then
        home="$work/home-$name"
        mkdir -p "$home/run"
        chmod 700 "$home/run"
        # It should still be running when timeout ends it (exit code 124).
        status=0
        env -i PATH="$PATH" HOME="$home" XDG_RUNTIME_DIR="$home/run" JAVA_HOME="${JAVA_HOME:-}" \
            XDG_CONFIG_HOME="$home/.config" XDG_STATE_HOME="$home/.local/state" XDG_CACHE_HOME="$home/.cache" XDG_DATA_HOME="$home/.local/share" \
            JAVA_TOOL_OPTIONS="-Duser.home=$home" \
            CAULIS_MODE=headless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_LOADER_DRIVER_OVERRIDE=kms_swrast \
            dbus-run-session -- timeout 20 "$project/build/install/check-$name/bin/check-$name" > "$home/log" 2>&1 || status=$?
        if [[ $status -ne 124 ]] || grep -qE "Exception|config.*(problem|error)" "$home/log" || [[ "$name" == lua && ! -f "$home/.config/check-lua/config.lua" ]]; then
            echo "Template $name did not run well (exit $status):" >&2
            tail -n 30 "$home/log" >&2
            exit 1
        fi
        echo "(ran for 20 seconds)"
    fi
done

echo "== All templates build: ${names[*]}"
