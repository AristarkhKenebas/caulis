# JDK 27 (Eclipse Temurin's build), made to work on Nix the way nixpkgs' own temurin-bin is.
# Used only while nixpkgs has no jdk27 (see jdk.nix).
{
  lib,
  stdenv,
  fetchurl,
  autoPatchelfHook,
  alsa-lib,
  fontconfig,
  freetype,
  zlib,
  libx11,
  libxext,
  libxi,
  libxrender,
  libxtst,
  cups,
}:

let
  sources = {
    x86_64-linux = {
      version = "27.0.1.0.2+1";
      url = "https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.0.2%2B1/OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.0.2_1.tar.gz";
      hash = "sha256-MMsSn2zymchSzsD+yp6ipZbS7p8m2GBdOiU5UfbIsg8=";
    };
    aarch64-linux = {
      version = "27+35";
      url = "https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_aarch64_linux_hotspot_27_35.tar.gz";
      hash = "sha256-5OxccnYpDHveC67LqaKb5JYvU4hoQpZAapeZ1GWTYIc=";
    };
  };
  source =
    sources.${stdenv.hostPlatform.system}
      or (throw "JDK 27: no Temurin build for ${stdenv.hostPlatform.system}");
in
stdenv.mkDerivation (finalAttrs: {
  pname = "temurin-jdk";
  inherit (source) version;

  src = fetchurl { inherit (source) url hash; };

  nativeBuildInputs = [ autoPatchelfHook ];
  buildInputs = [
    alsa-lib
    fontconfig
    freetype
    (lib.getLib stdenv.cc.cc) # libstdc++
    libx11
    libxext
    libxi
    libxrender
    libxtst
    zlib
  ];
  # Opened while running (printing), not linked.
  runtimeDependencies = [ cups ];

  dontStrip = true;

  installPhase = ''
    runHook preInstall
    mkdir -p $out
    cp -r . $out
    # jni.h expects jni_md.h beside it.
    ln -s $out/include/linux/*_md.h $out/include/
    # The system's freetype instead of the bundled one.
    find $out -name 'libfreetype.so*' -delete
    mkdir -p $out/nix-support
    cat > $out/nix-support/setup-hook <<EOF
    if [ -z "\''${JAVA_HOME-}" ]; then export JAVA_HOME=$out; fi
    EOF
    runHook postInstall
  '';

  preFixup = ''
    find $out -name libfontmanager.so -exec patchelf --add-needed libfontconfig.so {} \;
  '';

  passthru.home = finalAttrs.finalPackage;

  meta = {
    description = "Eclipse Temurin, prebuilt OpenJDK 27";
    homepage = "https://adoptium.net";
    license = with lib.licenses; [
      gpl2Only
      classpathException20
    ];
    sourceProvenance = with lib.sourceTypes; [
      binaryNativeCode
      binaryBytecode
    ];
    platforms = builtins.attrNames sources;
    mainProgram = "java";
  };
})
