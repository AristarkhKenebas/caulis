{
  description = "Caulis: Wayland compositors on the JVM";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
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
      # nix develop: JDK 27 and the libraries, then ./gradlew build
      devShells = builtins.mapAttrs (system: pkgs: {
        default = import ./nix/shell.nix { inherit pkgs; };
      }) linux;

      packages = builtins.mapAttrs (system: pkgs: {
        jdk = import ./nix/jdk.nix { inherit pkgs; };
      }) linux;

      # Settings written in Nix: programs.caulis.settings = { ... };
      homeModules.default = ./nix/home-manager.nix;
      nixosModules.default = ./nix/nixos.nix;

      lib = {
        # The settings' Nix type (as the modules use it), for your own modules.
        settingsType = import ./nix/options.nix;
        # The development shell, for compositors built on Caulis.
        devShell = pkgs: import ./nix/shell.nix { inherit pkgs; };
        # The C libraries a Caulis compositor looks up while running (for its package).
        libraries = pkgs: import ./nix/libraries.nix pkgs;
      };
    };
}
