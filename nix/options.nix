# Generated from the compositor's own description of its settings (--print-nix-options).
# Every option is null (not set) by default; set ones end up in config.json.
{ lib }:
let
  settingsType = (lib.types.submodule { options = {
    "layout" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "name" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "floating" "dwindle" "spiral" "grid" "columns" "rows" "monocle" "master-stack" "centered-master" "master-strips" "tree" "tree-spiral" "manual" "scrolling-columns" "canvas" ]); default = null; description = "how windows are arranged"; };
      "gaps" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "inner" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space between tiled windows"; };
        "outer" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space between them and the screen's edges"; };
        "top" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the top edge (else outer)"; };
        "right" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the right edge (else outer)"; };
        "bottom" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the bottom edge (else outer)"; };
        "left" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the left edge (else outer)"; };
      }; }); default = null; description = "space around and between windows"; };
      "ratio" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 0.9) // { description = "a number from 0.1 to 0.9"; }); default = null; description = "the main window's share (master layouts)"; };
      "side" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "left" "right" "top" "bottom" ]); default = null; description = "the main window's side (master-stack)"; };
    }; }); default = null; description = "how windows are arranged"; };
    "scale" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 4) // { description = "a number from 0.5 to 4"; }); default = null; description = "how big everything is (1.0, 1.25, 1.5, 2.0, ...)"; };
    "workspaceMode" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "per-monitor" "pinned" "separate" "spanning" "primary-only" ]); default = null; description = "shared numbers on any monitor, pinned to monitors, each monitor its own, one over all, or only on the primary monitor"; };
    "primaryMonitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the primary monitor (\"DP-2\")"; };
    "workspaces" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "count" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "how many there are (1: no workspaces, one desktop)"; };
      "rules" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
        "workspace" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "its number"; };
        "name" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its name for bars (\"web\")"; };
        "monitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its home monitor (\"DP-2\")"; };
        "layout" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
          "name" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "floating" "dwindle" "spiral" "grid" "columns" "rows" "monocle" "master-stack" "centered-master" "master-strips" "tree" "tree-spiral" "manual" "scrolling-columns" "canvas" ]); default = null; description = "how windows are arranged"; };
          "gaps" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
            "inner" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space between tiled windows"; };
            "outer" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space between them and the screen's edges"; };
            "top" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the top edge (else outer)"; };
            "right" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the right edge (else outer)"; };
            "bottom" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the bottom edge (else outer)"; };
            "left" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (500)); default = null; description = "space at the left edge (else outer)"; };
          }; }); default = null; description = "space around and between windows"; };
          "ratio" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 0.9) // { description = "a number from 0.1 to 0.9"; }); default = null; description = "the main window's share (master layouts)"; };
          "side" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "left" "right" "top" "bottom" ]); default = null; description = "the main window's side (master-stack)"; };
        }; }); default = null; description = "its own layout"; };
        "persistent" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "it always exists, even empty"; };
      }; })); default = null; description = "how single workspaces are"; };
    }; }); default = null; description = "how many workspaces, and how each is"; };
    "monitorEdges" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "touching" "proportional" ]); default = null; description = "how the mouse goes between monitors of different sizes"; };
    "monitors" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.submodule { options = {
      "scale" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 4) // { description = "a number from 0.5 to 4"; }); default = null; description = "its own scale"; };
      "position" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (-10000) (10000))) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[x, y] on the desktop, in units"; };
      "mode" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.addCheck lib.types.number (x: x >= 1 && x <= 20000) // { description = "a number from 1 to 20000"; })) (l: builtins.length l >= 2 && builtins.length l <= 3)); default = null; description = "[width, height] or [width, height, hz]"; };
      "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: not used"; };
    }; })); default = null; description = "settings per monitor, by connector (\"DP-2\", \"eDP-1\", ...)"; };
    "keyboard" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "layouts" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "as for setxkbmap: \"us,ru\""; };
      "options" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "e.g. \"grp:alt_shift_toggle\""; };
    }; }); default = null; description = "keyboard layouts"; };
    "cursor" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "theme" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "an installed cursor theme"; };
      "size" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (8) (256)); default = null; description = "its size"; };
    }; }); default = null; description = "the cursor"; };
    "terminal" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the terminal the terminal key opens"; };
    "lockCommand" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the screen locker"; };
    "titleBars" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "program" "compositor" "none" ]); default = null; description = "who draws title bars"; };
    "keys" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "terminal" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the terminal key (false: off)"; };
      "close" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the close key (false: off)"; };
      "lock" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the lock key (false: off)"; };
      "dumpState" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the dumpState key (false: off)"; };
      "maximize" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the maximize key (false: off)"; };
      "fullscreen" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the fullscreen key (false: off)"; };
      "minimize" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the minimize key (false: off)"; };
      "float" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the float key (false: off)"; };
      "nextWindow" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the nextWindow key (false: off)"; };
      "overview" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the overview key (false: off)"; };
      "focusLeft" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the focusLeft key (false: off)"; };
      "focusRight" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the focusRight key (false: off)"; };
      "workspace" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the workspace key (false: off)"; };
      "moveToWorkspace" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the moveToWorkspace key (false: off)"; };
      "move" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the move key (false: off)"; };
      "resize" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the resize key (false: off)"; };
      "preselect" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the preselect key (false: off)"; };
      "rotate" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the rotate key (false: off)"; };
      "balance" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the balance key (false: off)"; };
      "columnWidth" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the columnWidth key (false: off)"; };
      "splitBeside" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the splitBeside key (false: off)"; };
      "splitBelow" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the splitBelow key (false: off)"; };
      "quit" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the quit key (false: off)"; };
      "consoles" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the consoles key (false: off)"; };
      "restoreShortcuts" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the restoreShortcuts key (false: off)"; };
      "windowDrag" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the windowDrag key (false: off)"; };
    }; }); default = null; description = "the usual keys, changed or turned off"; };
    "bind" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf lib.types.str); default = null; description = "your own keys: combination → action (\"exec firefox\", \"workspace 3\", ...)"; };
    "rules" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
      "appId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this app id (case does not matter)"; };
      "appIdPattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id fits this"; };
      "title" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contains this"; };
      "titlePattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title fits this"; };
      "program" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows of this program (as ps shows it)"; };
      "workspace" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "the workspace it opens on"; };
      "monitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the monitor it opens on"; };
      "goToWorkspace" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "show its workspace when it opens"; };
      "floating" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "float (true) or tile (false)"; };
      "size" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (1) (10000))) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[width, height] when floating"; };
      "position" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (-10000) (10000))) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[x, y] when floating, from the monitor's free area"; };
      "center" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "in the middle of its monitor when floating"; };
      "maximized" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "starts maximized"; };
      "fullscreen" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "starts fullscreen"; };
      "focus" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: opens without taking the keyboard"; };
      "titleBars" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "program" "compositor" "none" ]); default = null; description = "who draws its title bar"; };
      "opacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid it is"; };
      "margin" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.ints.between (-500) (500)) (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (-500) (500))) (l: builtins.length l >= 4 && builtins.length l <= 4))); default = null; description = "room around it: one number, or [top, right, bottom, left]"; };
    }; })); default = null; description = "rules for some windows"; };
    "effects" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "preset" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "modern" ]); default = null; description = "start from no effects, or the modern look"; };
      "cornerRadius" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (100)); default = null; description = "how round the corners are"; };
      "border" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "width" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (50)); default = null; description = "its width"; };
        "focused" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "with the focus"; };
        "unfocused" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "without"; };
      }; })); default = null; description = "a frame around windows"; };
      "shadow" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "softness" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (200)); default = null; description = "how far it fades out"; };
        "offsetX" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved right"; };
        "offsetY" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved down"; };
        "color" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "its colour or paint (a coloured one is a glow)"; };
        "spread" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-100) (200)); default = null; description = "how much bigger than the window"; };
      }; })); default = null; description = "the shadow (of the focused window)"; };
      "inactiveShadow" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "softness" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (200)); default = null; description = "how far it fades out"; };
        "offsetX" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved right"; };
        "offsetY" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved down"; };
        "color" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "its colour or paint (a coloured one is a glow)"; };
        "spread" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-100) (200)); default = null; description = "how much bigger than the window"; };
      }; })); default = null; description = "the shadow of windows without the focus"; };
      "blur" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "size" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (64)); default = null; description = "how strong"; };
        "passes" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (6)); default = null; description = "how smooth"; };
      }; })); default = null; description = "blur behind see-through windows"; };
      "opacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid the focused window is"; };
      "inactiveOpacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid the others are"; };
      "focusChangeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long border and shadow change when the focus moves"; };
    }; }); default = null; description = "round corners, borders, shadows, blur"; };
    "animations" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long animations take (0: none at all)"; };
      "windowOpen" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "a window appearing"; };
      "windowClose" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "a window going away"; };
      "minimize" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "a window going into the dock"; };
      "restore" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "a window coming back out of the dock"; };
      "windowMove" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "move" "none" ]); default = null; description = "windows gliding to new places"; };
      "workspaceSwitch" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "slide" "slide-vertical" "fade" "none" ]); default = null; description = "another workspace shown"; };
      "curve" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "ease-out" "ease-in-out" "linear" "overshoot" ]); default = null; description = "how animations move"; };
      "overviewMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "the overview zooming"; };
      "scaleChangeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "the picture melting when the scale changes"; };
    }; }); default = null; description = "animations"; };
    "background" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "color" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "one colour"; };
      "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[from, to]"; };
      "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -360 && x <= 360) // { description = "a number from -360 to 360"; }); default = null; description = "the gradient's direction"; };
      "image" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a picture (next to the configuration, ~/..., or /...)"; };
      "fit" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "fill" "fit" "stretch" "center" "tile" ]); default = null; description = "how the picture covers the monitor"; };
      "behind" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "the colour around a picture that does not cover it"; };
      "span" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "one picture over all monitors"; };
      "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a wallpaper shader file (vec4 wallpaper(vec2 at))"; };
      "framesPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (240)); default = null; description = "how often a moving shader is drawn"; };
      "changeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long one background melts into the next"; };
    }; }); default = null; description = "what shows behind everything"; };
    "notices" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "placement" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "banner" "toast" "hidden" ]); default = null; description = "a strip, cards in a corner, or none"; };
      "bottom" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a banner at the bottom"; };
      "room" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "push-all" "push-windows" "cover" ]); default = null; description = "what makes way for a banner"; };
      "corner" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "top-left" "top" "top-right" "bottom-left" "bottom" "bottom-right" ]); default = null; description = "where toasts go"; };
      "fontSize" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (6) (72)); default = null; description = "their text size"; };
      "everyMonitor" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on every monitor"; };
      "infoSeconds" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 3600) // { description = "a number from 0 to 3600"; }); default = null; description = "how long information stays (0: until clicked)"; };
      "warningSeconds" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 3600) // { description = "a number from 0 to 3600"; }); default = null; description = "how long warnings stay"; };
      "errorSeconds" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 3600) // { description = "a number from 0 to 3600"; }); default = null; description = "how long errors stay"; };
    }; }); default = null; description = "the compositor's own messages"; };
    "gestures" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "dragViewFingers" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5)); default = null; description = "fingers that drag the view (0: off)"; };
      "overviewPinchFingers" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5)); default = null; description = "fingers whose pinch opens the overview (0: off)"; };
      "workspaceSwipeFingers" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5)); default = null; description = "fingers whose swipe changes the workspace (0: off)"; };
      "swipeDistance" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 10 && x <= 1000) // { description = "a number from 10 to 1000"; }); default = null; description = "how far fingers move for a swipe"; };
    }; }); default = null; description = "touchpad gestures"; };
    "overviewFill" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.3 && x <= 1) // { description = "a number from 0.3 to 1"; }); default = null; description = "how much of the monitor the overview's windows take"; };
    "resizeBorder" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (50)); default = null; description = "the edge to grab floating windows by"; };
    "doubleClickMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (50) (2000)); default = null; description = "a double click on a title bar"; };
    "hideCursorWhileTyping" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "hide the cursor while typing"; };
    "hideCursorAfterSeconds" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3600)); default = null; description = "hide the cursor when the mouse is still (0: never)"; };
    "lockColor" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind the screen locker"; };
    "startup" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "programs started with the compositor"; };
    "logLevel" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3)); default = null; description = "how much the log says"; };
  }; });
in
settingsType
