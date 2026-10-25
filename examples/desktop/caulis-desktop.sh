#!/bin/sh
# Starts the Caulis test desktop on the whole screen. Log in on a text console first
# (Ctrl+Alt+F3), then run this. Ctrl+Alt+BackSpace quits; Ctrl+Alt+F1..F12 switch consoles
# (the session you came from goes on there). What it says goes to
# ~/.local/state/caulis-desktop/log (and the one before to log.1).
set -eu
repo=$(cd "$(dirname "$0")/../.." && pwd)
config="$HOME/.config/caulis-desktop"
state="$HOME/.local/state/caulis-desktop"
mkdir -p "$config" "$state"
# The bar's settings, once (yours to change afterwards).
for file in waybar.json waybar.css; do
    [ -e "$config/$file" ] || cp "$repo/examples/desktop/waybar/$file" "$config/$file"
done
echo "Building..."
"$repo/gradlew" -p "$repo" -q :examples:desktop:installDist
[ -e "$state/log" ] && mv "$state/log" "$state/log.1"
# Java 27 (the system's default may be older).
[ -x /usr/lib/jvm/java-27-openjdk/bin/java ] && export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
export XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=caulis XDG_SESSION_DESKTOP=caulis
unset DISPLAY WAYLAND_DISPLAY
echo "Starting (Ctrl+Alt+BackSpace quits)..."
exec "$repo/examples/desktop/build/install/caulis-desktop/bin/caulis-desktop" >"$state/log" 2>&1
