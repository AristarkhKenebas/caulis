# The compositor as a Nix package: nix build (then result/bin/my-compositor).
#
# Gradle downloads what the build needs; Nix builds without the network, so the downloads are
# listed in deps.json. Make or refresh that list after changing dependencies:
#
#   nix build .#default.mitmCache.updateScript && ./result
{
  lib,
  stdenv,
  gradle_9,
  makeWrapper,
  jdk,
  caulis,
  libraries,
}:

let
  # Gradle 9, running on JDK 27 (what the build's toolchain asks for).
  gradle = gradle_9.override { java = jdk; };
in

stdenv.mkDerivation (finalAttrs: {
  pname = "my-compositor";
  version = "0.1.0";

  src = lib.cleanSource ./.;

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  # Caulis from its flake (until it is published), built along: Gradle writes into an
  # included build, so it gets a copy it can write to.
  preConfigure = ''
    cp -r ${caulis} $NIX_BUILD_TOP/caulis
    chmod -R u+w $NIX_BUILD_TOP/caulis
    gradleFlagsArray+=("-Pcaulis.sources=$NIX_BUILD_TOP/caulis")
  '';

  gradleFlags = [
    # This JDK for Gradle and for the build; nothing downloaded.
    "-Dorg.gradle.java.home=${jdk.home}"
    "-Porg.gradle.java.installations.paths=${jdk.home}"
    "-Porg.gradle.java.installations.auto-detect=false"
    "-Porg.gradle.java.installations.auto-download=false"
  ];

  gradleBuildTask = "installDist";

  installPhase = ''
    runHook preInstall
    mkdir -p $out
    cp -r build/install/${finalAttrs.pname}/. $out
    wrapProgram $out/bin/${finalAttrs.pname} \
      --set JAVA_HOME ${jdk.home} \
      --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath libraries}:/run/opengl-driver/lib

    # A session to choose at login (display managers read these).
    mkdir -p $out/share/wayland-sessions
    cat > $out/share/wayland-sessions/${finalAttrs.pname}.desktop <<EOF
    [Desktop Entry]
    Name=${finalAttrs.pname}
    Comment=A Wayland compositor made with Caulis
    Exec=$out/bin/${finalAttrs.pname}
    Type=Application
    DesktopNames=${finalAttrs.pname}
    EOF
    runHook postInstall
  '';

  passthru.providedSessions = [ finalAttrs.pname ];

  meta = {
    description = "A Wayland compositor made with Caulis";
    platforms = lib.platforms.linux;
    mainProgram = finalAttrs.pname;
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      binaryBytecode # the downloads in deps.json
    ];
  };
})
