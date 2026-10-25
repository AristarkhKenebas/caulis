{
  description = "my-compositor, a Wayland compositor made with Caulis";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    caulis = {
      url = "path:CAULIS";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs:
    let
      # Wayland compositors run on Linux only.
      linux = inputs.nixpkgs.lib.filterAttrs (
        system: _: inputs.nixpkgs.lib.hasSuffix "-linux" system
      ) inputs.nixpkgs.legacyPackages;
    in
    {
      # nix develop: JDK 27 and the libraries, then ./gradlew run
      devShells = builtins.mapAttrs (system: pkgs: {
        default = inputs.caulis.lib.devShell pkgs;
      }) linux;

      # nix build: the compositor in result/
      packages = builtins.mapAttrs (system: pkgs: {
        default = pkgs.callPackage ./package.nix {
          jdk = inputs.caulis.packages.${system}.jdk;
          caulis = inputs.caulis;
          libraries = inputs.caulis.lib.libraries pkgs;
        };
      }) linux;

      # Its settings written in Nix (Home Manager):
      #
      #   imports = [ my-compositor.homeModules.default ];
      #   programs.caulis.enable = true;
      #   programs.caulis.settings = { layout.name = "dwindle"; bind."Super+B" = "exec firefox"; };
      homeModules.default =
        { lib, pkgs, ... }:
        {
          imports = [ inputs.caulis.homeModules.default ];
          programs.caulis = {
            package = lib.mkDefault inputs.self.packages.${pkgs.stdenv.hostPlatform.system}.default;
            configName = lib.mkDefault "my-compositor";
          };
        };

      # The same for the whole system, with a login session (NixOS).
      nixosModules.default =
        { lib, pkgs, ... }:
        {
          imports = [ inputs.caulis.nixosModules.default ];
          programs.caulis = {
            package = lib.mkDefault inputs.self.packages.${pkgs.stdenv.hostPlatform.system}.default;
            configName = lib.mkDefault "my-compositor";
          };
        };
    };
}
