# What the Home Manager and NixOS modules share: the options, and config.json made from
# them — written by Nix, checked by the compositor itself while building.
{ lib, pkgs }:

rec {
  options = {
    enable = lib.mkEnableOption "a compositor built with Caulis";

    package = lib.mkOption {
      type = lib.types.nullOr lib.types.package;
      default = null;
      description = ''
        Your compositor. Installed, and asked to check the settings while building
        (`--check-config`), so a mistake fails the build instead of showing up on screen.
      '';
    };

    configName = lib.mkOption {
      type = lib.types.strMatching "[^/]+";
      default = "caulis";
      example = "my-compositor";
      description = "Its folder of settings (the `configName` in its Main.kt).";
    };

    settings = lib.mkOption {
      type = import ./options.nix { inherit lib; };
      default = { };
      example = lib.literalExpression ''
        {
          layout = { name = "master-stack"; gaps = { inner = 8; outer = 12; }; };
          keys.quit = false;
          bind."Super+B" = "exec firefox";
          rules = [ { appId = "mpv"; floating = true; center = true; } ];
          background.shader = ./aurora.glsl;
        }
      '';
      description = ''
        The settings, written to config.json. Each one is checked with the limits the
        compositor itself uses; what is not set stays as the compositor's code says.
        Files (shaders, pictures) can be Nix paths.
      '';
    };

    files = lib.mkOption {
      type = lib.types.attrsOf lib.types.path;
      default = { };
      example = lib.literalExpression ''{ "aurora.glsl" = ./aurora.glsl; "walls/sea.jpg" = ./sea.jpg; }'';
      description = "Other files for its folder, by name (what settings name without a path).";
    };
  };

  # The settings without the ones not set (null), down through sections and lists.
  clean =
    value:
    if builtins.isAttrs value then
      lib.mapAttrs (_: clean) (lib.filterAttrs (_: v: v != null) value)
    else if builtins.isList value then
      map clean value
    else
      value;

  configFile =
    cfg:
    let
      json = (pkgs.formats.json { }).generate "config.json" (clean cfg.settings);
    in
    if cfg.package == null then
      json
    else
      pkgs.runCommand "config.json" { nativeBuildInputs = [ cfg.package ]; } ''
        export HOME=$TMPDIR
        ${lib.getExe cfg.package} --check-config ${json}
        cp ${json} $out
      '';

  # The folder's files: config.json and [files], by path inside the folder.
  folder =
    cfg:
    { "${cfg.configName}/config.json" = configFile cfg; }
    // lib.mapAttrs' (name: path: lib.nameValuePair "${cfg.configName}/${name}" path) cfg.files;
}
