# Without flakes: nix-shell (with flakes: nix develop)
{
  pkgs ? import <nixpkgs> { },
}:
import ./nix/shell.nix { inherit pkgs; }
