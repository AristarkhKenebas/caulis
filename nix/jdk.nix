# The JDK Caulis is built and run with: 27. nixpkgs' own as soon as it has one; until then
# Eclipse Temurin's build (temurin-27.nix).
{ pkgs }:
if pkgs ? jdk27 then pkgs.jdk27 else pkgs.callPackage ./temurin-27.nix { }
