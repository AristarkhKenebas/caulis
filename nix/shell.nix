# The development shell: JDK 27, Xwayland, and the C libraries Caulis looks up while
# running. ./gradlew brings the right Gradle; inside the shell it uses this JDK and
# downloads none.
{
  pkgs,
  jdk ? import ./jdk.nix { inherit pkgs; },
}:
pkgs.mkShell {
  packages = [
    jdk
    pkgs.xwayland # for X11 programs
    pkgs.wayland-utils # wayland-info: what a compositor offers
  ];

  env = {
    JAVA_HOME = "${jdk.home}";
    # The libraries, and the system's GPU drivers (NixOS keeps them in /run/opengl-driver).
    LD_LIBRARY_PATH = "${pkgs.lib.makeLibraryPath (import ./libraries.nix pkgs)}:/run/opengl-driver/lib";
  };
}
