#!/usr/bin/env bash
# Installs the system libraries Caulis calls (through FFM) on Debian/Ubuntu, so tests can
# run on a CI machine. No GPU is needed: tests that need one skip themselves.
set -euo pipefail
sudo_if_needed() { if [[ $(id -u) -eq 0 ]]; then "$@"; else sudo "$@"; fi; }
sudo_if_needed apt-get update -qq
sudo_if_needed apt-get install -y -qq --no-install-recommends \
    libdrm2 libgbm1 libegl1 libgles2 libxkbcommon0 xkb-data \
    libinput10 libudev1 libseat1 wayland-utils > /dev/null
