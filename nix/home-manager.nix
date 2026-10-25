# Home Manager: a Caulis compositor's settings, written in Nix.
#
#   imports = [ caulis.homeModules.default ];
#   programs.caulis = {
#     enable = true;
#     package = my-compositor.packages.${pkgs.system}.default;
#     configName = "my-compositor";
#     settings.layout.name = "scrolling-columns";
#   };
#
# They go to ~/.config/<configName>/config.json; the compositor takes them on when the file
# changes (home-manager switch), without restarting.
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
    home.packages = lib.optional (cfg.package != null) cfg.package;
    xdg.configFile = lib.mapAttrs (_: source: { inherit source; }) (common.folder cfg);
  };
}
