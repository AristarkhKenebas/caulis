# The C libraries Caulis calls while running (through Java's Foreign Function & Memory API,
# so they are looked up by name at run time rather than linked).
pkgs: [
  pkgs.libglvnd # libEGL, libGLESv2
  pkgs.libgbm # GPU buffers
  pkgs.libdrm # monitors
  pkgs.libinput # keyboards, mice, touchpads
  pkgs.systemdLibs # libudev: devices coming and going
  pkgs.seatd # libseat: the screen and input without root
  pkgs.libxkbcommon # keyboard layouts
  pkgs.libxcb # Xwayland's window manager
  pkgs.libpng # screenshots
]
