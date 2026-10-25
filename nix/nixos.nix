# NixOS: a Caulis compositor for the whole system — installed, with what it needs (graphics,
# a seat, polkit), its session offered at login if the package has one, and its settings
# in /etc/xdg/<configName>/config.json (each user's ~/.config/<configName>/config.json,
# when there is one, comes first).
#
#   imports = [ caulis.nixosModules.default ];
#   programs.caulis = {
#     enable = true;
#     package = my-compositor.packages.${pkgs.system}.default;
#     configName = "my-compositor";
#     settings.scale = 1.25;
#   };
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.programs.caulis;
  common = import ./common.nix { inherit lib pkgs; };
in
{
  options.programs.caulis = common.options;

  config = lib.mkIf cfg.enable {
    environment.systemPackages = lib.optional (cfg.package != null) cfg.package;
    environment.etc = lib.mapAttrs' (path: source: lib.nameValuePair "xdg/${path}" { inherit source; }) (
      common.folder cfg
    );

    hardware.graphics.enable = lib.mkDefault true;
    security.polkit.enable = lib.mkDefault true;
    # A login session, for packages that bring one (share/wayland-sessions/*.desktop).
    services.displayManager.sessionPackages = lib.optional (
      cfg.package != null && cfg.package ? providedSessions
    ) cfg.package;
  };
}
