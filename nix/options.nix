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
      "masters" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (20)); default = null; description = "how many masters (master-stack)"; };
      "newWindow" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "stack-end" "stack-top" "master" ]); default = null; description = "where a new window goes (master-stack)"; };
      "splitRatio" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 0.9) // { description = "a number from 0.1 to 0.9"; }); default = null; description = "the old window's share when a new one splits it (tree)"; };
      "columnWidths" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; })) (l: builtins.length l >= 1 && builtins.length l <= 10)); default = null; description = "the widths column-width goes through (scrolling-columns)"; };
      "newColumnWidth" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; }); default = null; description = "a new column's width (scrolling-columns)"; };
      "centerFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the focused column always in the middle (scrolling-columns)"; };
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
          "masters" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (20)); default = null; description = "how many masters (master-stack)"; };
          "newWindow" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "stack-end" "stack-top" "master" ]); default = null; description = "where a new window goes (master-stack)"; };
          "splitRatio" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 0.9) // { description = "a number from 0.1 to 0.9"; }); default = null; description = "the old window's share when a new one splits it (tree)"; };
          "columnWidths" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; })) (l: builtins.length l >= 1 && builtins.length l <= 10)); default = null; description = "the widths column-width goes through (scrolling-columns)"; };
          "newColumnWidth" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; }); default = null; description = "a new column's width (scrolling-columns)"; };
          "centerFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the focused column always in the middle (scrolling-columns)"; };
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
      "transform" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "normal" "90" "180" "270" "flipped" "flipped-90" "flipped-180" "flipped-270" ]); default = null; description = "turned clockwise (a screen on its side: 90)"; };
      "maxBpc" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (6) (16)); default = null; description = "the most bits per colour the link may use (default 8)"; };
      "adaptiveSync" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "variable refresh rate (FreeSync) if the monitor has it"; };
    }; })); default = null; description = "settings per monitor, by connector (\"DP-2\", \"eDP-1\", ...)"; };
    "keyboard" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "layouts" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "as for setxkbmap: \"us,ru\""; };
      "variants" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "one per layout: \",phonetic\""; };
      "options" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "e.g. \"grp:alt_shift_toggle\""; };
      "model" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "e.g. \"pc105\""; };
      "repeatRate" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (100)); default = null; description = "held keys repeat this many times a second (0: never)"; };
      "repeatDelay" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (100) (2000)); default = null; description = "milliseconds before a held key repeats"; };
      "numLock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "Num Lock on (true) or off (false) from the start (else as the keyboard is)"; };
    }; }); default = null; description = "keyboard layouts and key repeat"; };
    "input" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "focusFollowsMouse" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the window under the pointer gets the keyboard"; };
      "pointers" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: not used at all"; };
        "offWithMouse" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while a mouse is plugged in"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -1 && x <= 1) // { description = "a number from -1 to 1"; }); default = null; description = "pointer speed, -1 to 1 (0: the device's normal)"; };
        "accelProfile" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "flat" "adaptive" ]); default = null; description = "flat: as fast as the hand; adaptive: faster when moved fast"; };
        "naturalScroll" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "content follows the fingers"; };
        "scrollFactor" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 10) // { description = "a number from 0.1 to 10"; }); default = null; description = "scrolling goes this many times as far"; };
        "scrollMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "two-finger" "edge" "button" ]); default = null; description = "how it scrolls"; };
        "scrollButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with scrollMethod button: the button (\"MouseMiddle\")"; };
        "leftHanded" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "buttons swapped; tablets: turned around"; };
        "middleEmulation" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "left and right together are a middle click"; };
        "tap" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a tap is a click"; };
        "tapButtons" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "left-right-middle" "left-middle-right" ]); default = null; description = "touchpads: what two and three fingers tap"; };
        "tapDrag" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: tap, touch again and move to drag"; };
        "dragLock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a drag goes on when the finger lifts for a moment"; };
        "disableWhileTyping" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while typing"; };
        "clickMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "areas" "fingers" ]); default = null; description = "touchpads: a click by the area pressed or by the number of fingers"; };
      }; }); default = null; description = "every mouse, trackball and touchpad"; };
      "touchpads" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: not used at all"; };
        "offWithMouse" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while a mouse is plugged in"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -1 && x <= 1) // { description = "a number from -1 to 1"; }); default = null; description = "pointer speed, -1 to 1 (0: the device's normal)"; };
        "accelProfile" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "flat" "adaptive" ]); default = null; description = "flat: as fast as the hand; adaptive: faster when moved fast"; };
        "naturalScroll" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "content follows the fingers"; };
        "scrollFactor" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 10) // { description = "a number from 0.1 to 10"; }); default = null; description = "scrolling goes this many times as far"; };
        "scrollMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "two-finger" "edge" "button" ]); default = null; description = "how it scrolls"; };
        "scrollButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with scrollMethod button: the button (\"MouseMiddle\")"; };
        "leftHanded" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "buttons swapped; tablets: turned around"; };
        "middleEmulation" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "left and right together are a middle click"; };
        "tap" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a tap is a click"; };
        "tapButtons" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "left-right-middle" "left-middle-right" ]); default = null; description = "touchpads: what two and three fingers tap"; };
        "tapDrag" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: tap, touch again and move to drag"; };
        "dragLock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a drag goes on when the finger lifts for a moment"; };
        "disableWhileTyping" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while typing"; };
        "clickMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "areas" "fingers" ]); default = null; description = "touchpads: a click by the area pressed or by the number of fingers"; };
      }; }); default = null; description = "touchpads (on top of pointers)"; };
      "tablets" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: not used at all"; };
        "leftHanded" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "buttons swapped; tablets: turned around"; };
        "monitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the monitor it covers (else a pen screen its laptop's, others all monitors as one)"; };
        "keepAspect" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "tablets: keep the monitor's shape, a strip of the tablet unused (default true)"; };
        "area" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
          "x" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "where it starts across, 0 to 1 of the tablet"; };
          "y" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "where it starts down, 0 to 1 of the tablet"; };
          "width" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "how wide, 0 to 1 of the tablet (else to its edge)"; };
          "height" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "how tall, 0 to 1 of the tablet (else to its edge)"; };
        }; }); default = null; description = "tablets: only this part of the tablet is used"; };
        "pointerOnly" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "tablets: the pen is only ever the pointer (no pressure or tilt for programs)"; };
        "tipPressure" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "tablets: how hard the pen presses to touch, 0.01 to 1 (else libinput decides)"; };
        "lowerButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the pen's lower button as a mouse button (\"MouseRight\", \"none\")"; };
        "upperButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the pen's upper button as a mouse button (\"MouseMiddle\", \"none\")"; };
        "thirdButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: a third button of the pen as a mouse button (\"MouseSide\", \"none\")"; };
      }; }); default = null; description = "drawing tablets and their pens"; };
      "devices" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: not used at all"; };
        "offWithMouse" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while a mouse is plugged in"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -1 && x <= 1) // { description = "a number from -1 to 1"; }); default = null; description = "pointer speed, -1 to 1 (0: the device's normal)"; };
        "accelProfile" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "flat" "adaptive" ]); default = null; description = "flat: as fast as the hand; adaptive: faster when moved fast"; };
        "naturalScroll" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "content follows the fingers"; };
        "scrollFactor" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 10) // { description = "a number from 0.1 to 10"; }); default = null; description = "scrolling goes this many times as far"; };
        "scrollMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "two-finger" "edge" "button" ]); default = null; description = "how it scrolls"; };
        "scrollButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with scrollMethod button: the button (\"MouseMiddle\")"; };
        "leftHanded" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "buttons swapped; tablets: turned around"; };
        "middleEmulation" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "left and right together are a middle click"; };
        "tap" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a tap is a click"; };
        "tapButtons" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "left-right-middle" "left-middle-right" ]); default = null; description = "touchpads: what two and three fingers tap"; };
        "tapDrag" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: tap, touch again and move to drag"; };
        "dragLock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: a drag goes on when the finger lifts for a moment"; };
        "disableWhileTyping" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "touchpads: off while typing"; };
        "clickMethod" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "areas" "fingers" ]); default = null; description = "touchpads: a click by the area pressed or by the number of fingers"; };
        "monitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the monitor it covers (else a pen screen its laptop's, others all monitors as one)"; };
        "keepAspect" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "tablets: keep the monitor's shape, a strip of the tablet unused (default true)"; };
        "area" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
          "x" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "where it starts across, 0 to 1 of the tablet"; };
          "y" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "where it starts down, 0 to 1 of the tablet"; };
          "width" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "how wide, 0 to 1 of the tablet (else to its edge)"; };
          "height" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "how tall, 0 to 1 of the tablet (else to its edge)"; };
        }; }); default = null; description = "tablets: only this part of the tablet is used"; };
        "pointerOnly" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "tablets: the pen is only ever the pointer (no pressure or tilt for programs)"; };
        "tipPressure" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 1) // { description = "a number from 0.01 to 1"; }); default = null; description = "tablets: how hard the pen presses to touch, 0.01 to 1 (else libinput decides)"; };
        "lowerButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the pen's lower button as a mouse button (\"MouseRight\", \"none\")"; };
        "upperButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: the pen's upper button as a mouse button (\"MouseMiddle\", \"none\")"; };
        "thirdButton" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "tablets: a third button of the pen as a mouse button (\"MouseSide\", \"none\")"; };
      }; })); default = null; description = "single devices by name (--devices lists them; * matches anything)"; };
    }; }); default = null; description = "mice, touchpads, tablets, focus"; };
    "idle" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
      "after" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (86400)); default = null; description = "seconds without input"; };
      "action" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what happens then (\"dpms off\", \"lock\", \"exec ...\")"; };
      "resume" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what happens when someone is back"; };
    }; })); default = null; description = "what happens when nobody uses the computer"; };
    "focusOnActivate" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a window asking for the focus with a good token gets it (false: only marked urgent)"; };
    "fullscreenKeepsAwake" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a fullscreen window keeps idle timers off"; };
    "wakeScreensOnInput" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "input switches dark screens on again"; };
    "wakeScreensAfterMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "input this soon after screens went dark does not wake them"; };
    "scratchpadStartMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "how long a scratchpad's program may take before its key starts it again"; };
    "scriptCallLimitMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (10) (60000)); default = null; description = "how long one call into config.lua's code may run"; };
    "compiledScriptsKept" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (100)); default = null; description = "how many compiled versions of config.kts stay in its cache"; };
    "lid" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "close" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "when the laptop's lid closes"; };
      "open" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "when it opens"; };
      "turnsScreenOff" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the laptop's screen is off while the lid is closed and another monitor is in use"; };
    }; }); default = null; description = "the laptop's lid"; };
    "sleep" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "lock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "lock the screen before the computer sleeps"; };
      "lockWaitMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (30000)); default = null; description = "how long sleep waits for the lock screen to show"; };
      "ownLockShowMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long the compositor's own lock screen shows before sleep, when the locker did not"; };
      "before" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "just before it sleeps"; };
      "after" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "when it woke up"; };
    }; }); default = null; description = "sleep and hibernation"; };
    "displays" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "chooserOnPlug" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a monitor never seen before shows the display modes"; };
    }; }); default = null; description = "a laptop with a monitor or projector"; };
    "cursor" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "theme" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "an installed cursor theme"; };
      "size" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (8) (256)); default = null; description = "its size"; };
    }; }); default = null; description = "the cursor"; };
    "programs" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "environment" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf lib.types.str); default = null; description = "more environment for programs started inside (wins over Caulis's own choices)"; };
      "xwayland" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "run X11 programs too (only at the start)"; };
      "waylandFirst" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "start programs the Wayland way, through X11 when that fails"; };
      "x11Programs" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "programs always started through X11"; };
      "x11Fallback" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a program that connected and failed at once is started again through X11"; };
      "x11FallbackMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "how soon it must fail for that"; };
      "stopOnQuit" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "programs started inside are asked to end when the compositor quits"; };
      "sharpJavaScaling" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "Java programs sharp on fractional scales (only at the start)"; };
      "desktopName" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what programs are told the desktop is called (XDG_CURRENT_DESKTOP)"; };
      "exportEnvironment" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "hand the environment to systemd and D-Bus activation at the start (default: on a real screen only)"; };
      "portalsToRestart" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "services started again with this compositor's environment when they had another one"; };
      "output" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "discard" "log" "file" ]); default = null; description = "where started programs' output goes"; };
      "outputFile" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "the file for output \"file\" (default ~/.cache/<name>/programs.log)"; };
      "start" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "native" "java" ]); default = null; description = "how programs are started: native (posix_spawn; survives Java being updated meanwhile) or java (ProcessBuilder)"; };
      "javaUpdatedNotice" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "shown once when Java was updated under the running compositor (\"\": not shown)"; };
    }; }); default = null; description = "programs started inside"; };
    "performance" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "stallReportMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "the main loop busy this long with one thing is logged with what it was (0: not watched)"; };
      "trimMemory" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "memory the C library freed is given back to the system after the start and now and then"; };
      "trimMemoryEveryMinutes" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (1440)); default = null; description = "how often after the start (0: only after the start)"; };
      "mallocPools" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (64)); default = null; description = "at most this many malloc pools for the compositor (0: glibc's own; only at the start)"; };
      "quickCursor" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the cursor moves the moment the pointer does, not with the next frame"; };
      "mallocMapAboveKb" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (65536)); default = null; description = "blocks of this many kB or more are given back to the system the moment they are freed (0: glibc's own; only at the start)"; };
      "inputThread" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "keyboards, mice and touchpads are read on a thread of their own; the cursor keeps moving while the compositor is busy"; };
      "glVendorByDriver" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "only the EGL driver for the GPU in use is loaded, not every one installed (only at the start)"; };
      "widgetThreads" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (16)); default = null; description = "threads the compositor's own widgets (bars, menus) draw their pixels on (0: its own thread)"; };
    }; }); default = null; description = "watching the main loop, giving memory back"; };
    "systemKeys" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "volumeStep" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (100)); default = null; description = "percent per press of the volume keys"; };
      "maxVolume" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 4) // { description = "a number from 0.1 to 4"; }); default = null; description = "the loudest they go (1.0: 100 %)"; };
      "unmuteOnVolumeChange" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "changing the volume switches the sound on again"; };
      "brightnessStep" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (100)); default = null; description = "percent per press of the brightness keys"; };
      "minBrightnessPercent" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (100)); default = null; description = "the darkest they go"; };
      "backlight" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the backlight, by its name in /sys/class/backlight (default: the first)"; };
      "wpctl" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the program for the volume and the microphone"; };
      "brightnessctl" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the program for the brightness without logind"; };
      "playerctl" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the program for music players"; };
      "timeoutMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (100) (60000)); default = null; description = "how long these programs may take"; };
    }; }); default = null; description = "what the laptop's keys do: steps, limits, programs"; };
    "screen" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "hardwareCursor" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the pointer on the display's cursor plane (only at the start)"; };
      "directScanout" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "fullscreen programs' pictures directly on the monitor (only at the start)"; };
      "touchscreenMonitor" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the monitor touch screens cover (default: the laptop's own)"; };
      "builtInConnectors" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf lib.types.str) (l: builtins.length l >= 1 && builtins.length l <= 1000)); default = null; description = "how the laptop's own screen's connector begins (default eDP, LVDS, DSI)"; };
    }; }); default = null; description = "on a real screen"; };
    "titleBarStyle" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "height" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (12) (120)); default = null; description = "how high, in units"; };
      "font" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the title's font"; };
      "fontSize" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (6) (72)); default = null; description = "the title's size"; };
      "bold" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a bold title"; };
      "centerTitle" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the title in the middle"; };
      "buttons" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.enum [ "minimize" "maximize" "close" ])); default = null; description = "the buttons, in order"; };
      "buttonsLeft" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the buttons on the left"; };
      "focusedBackground" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind the title with the focus"; };
      "unfocusedBackground" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind it without"; };
      "focusedText" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "the title with the focus"; };
      "unfocusedText" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "the title without"; };
      "buttonHover" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind a button under the pointer"; };
      "closeHover" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind the close button under the pointer"; };
    }; }); default = null; description = "how the compositor's title bars look (titleBars \"compositor\")"; };
    "terminal" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the terminal the terminal key opens"; };
    "lockCommand" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the screen locker"; };
    "lockShowMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (100) (60000)); default = null; description = "how long a lock may take to show before it counts as failed (lockFailure)"; };
    "lockFailure" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "own-lock" "notice" ]); default = null; description = "when a lock does not show: the compositor's own plain lock screen, or only a notice (and sleep unlocked)"; };
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
      "volumeUp" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the volumeUp key (false: off)"; };
      "volumeDown" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the volumeDown key (false: off)"; };
      "volumeMute" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the volumeMute key (false: off)"; };
      "micMute" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the micMute key (false: off)"; };
      "brightnessUp" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the brightnessUp key (false: off)"; };
      "brightnessDown" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the brightnessDown key (false: off)"; };
      "touchpad" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the touchpad key (false: off)"; };
      "displays" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the displays key (false: off)"; };
      "mediaPlay" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the mediaPlay key (false: off)"; };
      "mediaNext" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the mediaNext key (false: off)"; };
      "mediaPrevious" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the mediaPrevious key (false: off)"; };
      "windowDrag" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) lib.types.str); default = null; description = "the windowDrag key (false: off)"; };
      "quitWhileLocked" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the quit key works on a locked screen too (default false: it would get round the lock)"; };
    }; }); default = null; description = "the usual keys, changed or turned off"; };
    "bind" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.either lib.types.str (lib.types.submodule { options = {
        "action" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what it does (\"exec firefox\", \"workspace 3\", ...)"; };
        "repeat" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "repeats while held"; };
        "release" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "runs when the key is let go"; };
        "locked" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "works on a locked screen too"; };
        "description" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what it does, in words"; };
      }; }))); default = null; description = "your own keys: combination → action (\"exec firefox\", \"workspace 3\", ...) or { action, repeat, release, locked, description }"; };
    "modes" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.attrsOf (lib.types.either lib.types.str (lib.types.submodule { options = {
        "action" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what it does (\"exec firefox\", \"workspace 3\", ...)"; };
        "repeat" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "repeats while held"; };
        "release" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "runs when the key is let go"; };
        "locked" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "works on a locked screen too"; };
        "description" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "what it does, in words"; };
      }; })))); default = null; description = "key modes (\"resize\"): their own keys, on with the action \"mode resize\", off with \"mode reset\""; };
    "rules" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
      "appId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this app id (case does not matter)"; };
      "appIdPattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id fits this"; };
      "title" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contains this"; };
      "titlePattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title fits this"; };
      "program" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows of this program (as ps shows it)"; };
      "initialAppId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id was this when they opened"; };
      "initialTitle" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contained this when they opened"; };
      "hasTag" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this tag (from an earlier rule's tags, or the action tag)"; };
      "isFloating" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it floats (true) or is tiled (false)"; };
      "isFullscreen" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is fullscreen (or not)"; };
      "isMaximized" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is maximized (or not)"; };
      "isFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it has the keyboard (or not)"; };
      "isPinned" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is pinned (or not)"; };
      "isXwayland" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "X11 programs (true) or Wayland ones (false)"; };
      "onWorkspace" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "while it is on this workspace"; };
      "unless" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "appId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this app id (case does not matter)"; };
        "appIdPattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id fits this"; };
        "title" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contains this"; };
        "titlePattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title fits this"; };
        "program" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows of this program (as ps shows it)"; };
        "initialAppId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id was this when they opened"; };
        "initialTitle" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contained this when they opened"; };
        "hasTag" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this tag (from an earlier rule's tags, or the action tag)"; };
        "isFloating" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it floats (true) or is tiled (false)"; };
        "isFullscreen" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is fullscreen (or not)"; };
        "isMaximized" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is maximized (or not)"; };
        "isFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it has the keyboard (or not)"; };
        "isPinned" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is pinned (or not)"; };
        "isXwayland" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "X11 programs (true) or Wayland ones (false)"; };
        "onWorkspace" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "while it is on this workspace"; };
      }; }); default = null; description = "windows that do NOT fit this"; };
      "anyOf" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.submodule { options = {
        "appId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this app id (case does not matter)"; };
        "appIdPattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id fits this"; };
        "title" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contains this"; };
        "titlePattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title fits this"; };
        "program" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows of this program (as ps shows it)"; };
        "initialAppId" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose app id was this when they opened"; };
        "initialTitle" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows whose title contained this when they opened"; };
        "hasTag" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "windows with this tag (from an earlier rule's tags, or the action tag)"; };
        "isFloating" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it floats (true) or is tiled (false)"; };
        "isFullscreen" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is fullscreen (or not)"; };
        "isMaximized" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is maximized (or not)"; };
        "isFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it has the keyboard (or not)"; };
        "isPinned" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "while it is pinned (or not)"; };
        "isXwayland" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "X11 programs (true) or Wayland ones (false)"; };
        "onWorkspace" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (99)); default = null; description = "while it is on this workspace"; };
      }; })) (l: builtins.length l >= 1 && builtins.length l <= 1000)); default = null; description = "windows that fit at least one of these"; };
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
      "pinned" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "opens pinned: floating, on every workspace of its monitor"; };
      "inactiveOpacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid it is while another window has the keyboard"; };
      "cornerRadius" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (200)); default = null; description = "how round its corners are, in pixels"; };
      "border" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: no border; true: the usual one"; };
      "borderWidth" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (50)); default = null; description = "its border's width in pixels"; };
      "borderColor" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 2))); default = null; description = "its border's colour, or [with the keyboard, without]"; };
      "borderPaint" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
        "focused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "with the keyboard"; };
        "unfocused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "without"; };
        "urgent" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "while it wants attention"; };
      }; }); default = null; description = "its border's paints: gradients, shaders (focused, unfocused, urgent)"; };
      "specialWorkspace" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the special workspace it opens in, unseen (unless goToWorkspace)"; };
      "shadow" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: no shadow; true: the usual one"; };
      "blur" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: nothing blurred behind it; true: frosted glass"; };
      "dimInactive" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how much darker it is without the keyboard (0: never)"; };
      "minSize" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (1) (10000))) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[width, height] it is at least while floating"; };
      "maxSize" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.ints.between (1) (10000))) (l: builtins.length l >= 2 && builtins.length l <= 2)); default = null; description = "[width, height] it is at most while floating"; };
      "keepAwake" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the screen stays on while it is shown"; };
      "animated" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: no animations when it opens, closes or moves"; };
      "hiddenFromCapture" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "screenshots and screen sharing show a black box in its place"; };
      "takesFocus" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: it never gets the keyboard"; };
      "ignore" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.enum [ "fullscreen" "maximize" "minimize" "activate" ])); default = null; description = "what its program asks that is not done"; };
      "tags" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "tags it has while it fits (for later rules with hasTag)"; };
    }; })); default = null; description = "rules for some windows"; };
    "panelRules" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
      "namespace" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "panels that call themselves this (waybar, fuzzel, ...)"; };
      "namespacePattern" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "panels whose name fits this"; };
      "layer" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "background" "bottom" "top" "overlay" ]); default = null; description = "panels on this layer"; };
      "blur" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "frosted glass behind it"; };
      "cornerRadius" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (200)); default = null; description = "how round its corners are"; };
      "shadow" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a shadow under it"; };
      "opacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid it is"; };
      "dimAround" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "everything behind it this much darker"; };
      "hiddenFromCapture" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "screenshots and screen sharing show a black box in its place"; };
    }; })); default = null; description = "rules for some panels (bars, launchers, notifications)"; };
    "specialWorkspaces" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "dim" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how much darker everything behind a shown one is"; };
      "blur" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "size" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (64)); default = null; description = "how strong"; };
        "passes" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (6)); default = null; description = "how smooth"; };
        "tone" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "hyprland" ]); default = null; description = "start from no toning, or Hyprland's"; };
        "noise" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "grain (a little hides banding)"; };
        "contrast" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 2) // { description = "a number from 0 to 2"; }); default = null; description = "1: as it is"; };
        "brightness" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 2) // { description = "a number from 0 to 2"; }); default = null; description = "1: as it is"; };
        "vibrancy" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "stronger colours (0: as they are)"; };
      }; })); default = null; description = "what is behind it blurred"; };
      "gap" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (1000)); default = null; description = "space between it and the monitor's edges"; };
      "size" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.2 && x <= 1) // { description = "a number from 0.2 to 1"; }); default = null; description = "how much of the monitor it takes"; };
      "animation" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "windows" "windowsIn" "windowsOut" "windowsMove" "scratchpads" "minimize" "restore" "workspaces" "overview" "border" "screenshot" "fade" "fadeScale" "fadeBackground" "fadeNotices" "fadeOsd" ]); default = null; description = "the animation its windows come and go with"; };
      "hideWhenEmpty" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "hide when its last window closes or leaves"; };
      "newWindowsJoin" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "windows opening while it is shown go into it"; };
      "hideOnLock" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "hide when the screen locks"; };
      "clickOutsideHides" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "a click on the dimmed area hides it"; };
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
        "masters" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (20)); default = null; description = "how many masters (master-stack)"; };
        "newWindow" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "stack-end" "stack-top" "master" ]); default = null; description = "where a new window goes (master-stack)"; };
        "splitRatio" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 0.9) // { description = "a number from 0.1 to 0.9"; }); default = null; description = "the old window's share when a new one splits it (tree)"; };
        "columnWidths" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; })) (l: builtins.length l >= 1 && builtins.length l <= 10)); default = null; description = "the widths column-width goes through (scrolling-columns)"; };
        "newColumnWidth" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 1) // { description = "a number from 0.1 to 1"; }); default = null; description = "a new column's width (scrolling-columns)"; };
        "centerFocused" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the focused column always in the middle (scrolling-columns)"; };
      }; }); default = null; description = "its own layout (else the usual one)"; };
    }; }); default = null; description = "special workspaces (Hyprland's special): shown over the current workspace with special-workspace toggle [name]"; };
    "screenShader" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "preset" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "grayscale" "invert" ]); default = null; description = "a ready one"; };
      "warm" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "a night light: this much less blue"; };
      "file" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "your own: vec4 effect(vec4 color, vec2 at) (read again when saved)"; };
      "moving" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "drawn every frame (it uses time)"; };
    }; }); default = null; description = "a shader the whole screen goes through last"; };
    "effects" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "preset" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "modern" ]); default = null; description = "start from no effects, or the modern look"; };
      "cornerRadius" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (100)); default = null; description = "how round the corners are"; };
      "border" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "width" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (50)); default = null; description = "its width"; };
        "focused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "with the focus"; };
        "unfocused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "without"; };
        "urgent" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "a window that wants attention (else as the others)"; };
        "groupedFocused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "the focused window of a group (else focused)"; };
        "groupedUnfocused" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
          "gradient" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours across the window"; };
          "conic" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) (l: builtins.length l >= 2 && builtins.length l <= 8)); default = null; description = "colours around the window's middle"; };
          "degrees" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -3600 && x <= 3600) // { description = "a number from -3600 to 3600"; }); default = null; description = "the gradient's direction or start"; };
          "turnsPerSecond" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -10 && x <= 10) // { description = "a number from -10 to 10"; }); default = null; description = "how fast it turns"; };
          "rainbow" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.5 && x <= 600) // { description = "a number from 0.5 to 600"; }); default = null; description = "a rainbow going round once every so many seconds"; };
          "shader" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a paint shader file (vec4 paint(vec2 at))"; };
        }; })); default = null; description = "the other windows of a group (else unfocused)"; };
        "animate" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "false: turning gradients and shaders using time stand still"; };
      }; })); default = null; description = "a frame around windows"; };
      "shadow" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ false ]) (lib.types.submodule { options = {
        "softness" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (200)); default = null; description = "how far it fades out"; };
        "offsetX" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved right"; };
        "offsetY" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (-200) (200)); default = null; description = "moved down"; };
        "color" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
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
        "color" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.submodule { options = {
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
        "tone" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "none" "hyprland" ]); default = null; description = "start from no toning, or Hyprland's"; };
        "noise" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "grain (a little hides banding)"; };
        "contrast" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 2) // { description = "a number from 0 to 2"; }); default = null; description = "1: as it is"; };
        "brightness" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 2) // { description = "a number from 0 to 2"; }); default = null; description = "1: as it is"; };
        "vibrancy" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "stronger colours (0: as they are)"; };
      }; })); default = null; description = "blur behind see-through windows"; };
      "dimInactive" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how much darker windows without the focus are (0: not at all)"; };
      "opacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid the focused window is"; };
      "inactiveOpacity" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how solid the others are"; };
      "focusChangeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long border and shadow change when the focus moves"; };
    }; }); default = null; description = "round corners, borders, shadows, blur"; };
    "animations" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "animations on (default false: everything changes at once; one turned on by name runs all the same)"; };
      "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "the global time in milliseconds, of those without one of their own (0: none at all)"; };
      "windowOpen" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]) (lib.types.submodule { options = {
        "style" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "how it looks"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long it takes (else animations.millis, or its spring's own time)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
      }; })); default = null; description = "a window appearing, as code's look (older; windowsIn is the same by name)"; };
      "windowClose" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]) (lib.types.submodule { options = {
        "style" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "pop" "fade" "scale" "slide-up" "genie" "fly" "none" ]); default = null; description = "how it looks"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long it takes (else animations.millis, or its spring's own time)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
      }; })); default = null; description = "a window going away, as code's look (older; windowsOut is the same by name)"; };
      "windowMove" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ "move" "none" ]) (lib.types.submodule { options = {
        "style" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "move" "none" ]); default = null; description = "how it looks"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long it takes (else animations.millis, or its spring's own time)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
      }; })); default = null; description = "windows gliding to new places (older; windowsMove is the same by name)"; };
      "workspaceSwitch" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ "slide" "slide-vertical" "fade" "none" ]) (lib.types.submodule { options = {
        "style" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "slide" "slide-vertical" "fade" "none" ]); default = null; description = "how it looks"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "how long it takes (else animations.millis, or its spring's own time)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
      }; })); default = null; description = "another workspace shown (older; workspaces is the same by name)"; };
      "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the global curve: how animations move"; };
      "curves" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.submodule { options = {
        "bezier" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf (lib.types.addCheck lib.types.number (x: x >= -2 && x <= 3) // { description = "a number from -2 to 3"; })) (l: builtins.length l >= 4 && builtins.length l <= 4)); default = null; description = "[x1, y1, x2, y2] as Hyprland's bezier (CSS cubic-bezier)"; };
        "spring" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
          "stiffness" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 1 && x <= 5000) // { description = "a number from 1 to 5000"; }); default = null; description = "how strongly it pulls"; };
          "damping" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.1 && x <= 500) // { description = "a number from 0.1 to 500"; }); default = null; description = "how much it is held back (less: it bounces)"; };
          "mass" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0.01 && x <= 100) // { description = "a number from 0.01 to 100"; }); default = null; description = "how heavy"; };
        }; }); default = null; description = "a spring: an animation takes as long as it needs to rest"; };
      }; })); default = null; description = "your own curves by name, for curve fields"; };
      "overviewMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "the overview zooming (older; overview.millis)"; };
      "scaleChangeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (5000)); default = null; description = "the picture melting when the scale changes (older; fadeScale.millis)"; };
      "windows" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none"; };
      }; }))); default = null; description = "every animation of windows below"; };
      "windowsIn" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none"; };
      }; }))); default = null; description = "a window appearing (Animations.windowOpen)"; };
      "windowsOut" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none"; };
      }; }))); default = null; description = "a window going away (Animations.windowClose)"; };
      "windowsMove" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: move, none"; };
      }; }))); default = null; description = "windows gliding to a new place or size: a layout change, maximize, a neighbour closing, the view settling after a swipe (Animations.windowMove)"; };
      "scratchpads" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: move, none"; };
      }; }))); default = null; description = "a scratchpad sliding in and out (Animations.windowMove's look)"; };
      "minimize" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none"; };
      }; }))); default = null; description = "a window going into the dock (Animations.minimize)"; };
      "restore" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none"; };
      }; }))); default = null; description = "a minimized window coming back (Animations.restore)"; };
      "workspaces" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look: slide, slidevert, fade, none"; };
      }; }))); default = null; description = "another workspace shown, also after a swipe (Animations.workspaceSwitch)"; };
      "overview" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "the overview zooming out and in, also finishing a pinch (Animations.overviewMillis; 1.5 × the global time)"; };
      "border" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "border, shadow, opacity and dimming changing when the focus moves (Effects.focusChangeMillis, 150)"; };
      "screenshot" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "the screenshot chooser coming in: the screen darkening, its toolbar rising (caulis-screenshot; ChooserBehaviour.appearMillis, 170)"; };
      "fade" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "every fade below"; };
      "fadeScale" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "the picture melting into the new one when a monitor's scale changes (Animations.scaleChangeMillis, 250)"; };
      "fadeBackground" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "one background melting into the next (backgroundChangeMillis, 400)"; };
      "fadeNotices" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "the compositor's notices fading in and out (NoticeStyle.fadeMillis, 180)"; };
      "fadeOsd" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.bool (lib.types.either lib.types.str (lib.types.submodule { options = {
        "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "on or off (else as the one above it, in the end animations.enabled)"; };
        "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (10000)); default = null; description = "how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)"; };
        "speed" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 100) // { description = "a number from 0 to 100"; }); default = null; description = "how long it takes in tenths of a second, as Hyprland's speed (instead of millis)"; };
        "curve" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "how it moves"; };
        "style" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its look (it has one only)"; };
      }; }))); default = null; description = "the volume and brightness display fading in and out (150)"; };
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
      "errorRoom" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "push-all" "push-windows" "cover" ]); default = null; description = "what makes way for an error's banner (else as room)"; };
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
    "appearance" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "colorScheme" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "default" "dark" "light" ]); default = null; description = "light or dark for programs (default: each its own)"; };
      "darkFrom" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with lightFrom: dark from this time of day"; };
      "lightFrom" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with darkFrom: light from this time of day"; };
      "latitude" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -90 && x <= 90) // { description = "a number from -90 to 90"; }); default = null; description = "with longitude: dark from sunset to sunrise there"; };
      "longitude" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= -180 && x <= 180) // { description = "a number from -180 to 180"; }); default = null; description = "with latitude: the place (east positive)"; };
      "accentColor" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "the accent colour programs are told"; };
      "gtkLightTheme" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with gtkDarkTheme: GTK 3 programs' theme when light (\"Adwaita\")"; };
      "gtkDarkTheme" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "with gtkLightTheme: their theme when dark (\"Adwaita-dark\")"; };
      "toPrograms" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "tell programs (default: on a real screen only, not nested)"; };
    }; }); default = null; description = "how programs look: light or dark, by a schedule"; };
    "hideCursorWhileTyping" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "hide the cursor while typing"; };
    "hideCursorAfterSeconds" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3600)); default = null; description = "hide the cursor when the mouse is still (0: never)"; };
    "lockColor" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "behind the screen locker"; };
    "backgroundColor" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "under everything (where no window or wallpaper is)"; };
    "focusStealing" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "same-program" "always" "never" ]); default = null; description = "which programs may take the keyboard by themselves"; };
    "unlockFocus" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "focused-before" "newest-before" ]); default = null; description = "which window gets the keyboard after unlocking (never one opened while locked)"; };
    "idleInhibitors" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "programs' idle inhibitors (video players: idle-inhibit) keep the screen on (default true)"; };
    "idleInhibitorsWhileLocked" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "idle inhibitors keep the screen on while it is locked too"; };
    "maxForwardedStringBytes" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (16) (3000)); default = null; description = "the longest title or app id passed on to other programs, in bytes"; };
    "maxRegionParts" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (100000)); default = null; description = "rectangles a program's region keeps exactly"; };
    "windowListPrograms" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "the programs that may see the list of windows (default: the privileged ones)"; };
    "permissions" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "rules" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
        "program" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the program's path (\"/usr/bin/grim\") or name (\"grim\"), a regular expression"; };
        "feature" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "screencopy" "window-list" "clipboard" "panels" "lock" "monitors" "gamma" ]); default = null; description = "what it wants"; };
        "mode" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "allow" "ask" "deny" ]); default = null; description = "allow, ask (screencopy only) or deny"; };
      }; })); default = null; description = "the rules, the first that matches decides"; };
      "defaults" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf (lib.types.enum [ "allow" "ask" "deny" ])); default = null; description = "what a feature gets when no rule matches (default: the privileged rule)"; };
    }; }); default = null; description = "which programs may take pictures of the screen, see the window list, read the clipboard, show panels, lock, arrange monitors (as Hyprland's permissions)"; };
    "primarySelection" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "middle-click paste (default true; only at the start)"; };
    "adaptiveSync" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "variable refresh rate on every monitor that has it, unless its own says otherwise (default false)"; };
    "bindingModifiers" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "xkb" "physical" ]); default = null; description = "modifiers in bindings as the keyboard layout says, or the physical keys"; };
    "xwaylandRestart" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "millis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (600000)); default = null; description = "wait after a failed start (doubled each time)"; };
      "maxMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3600000)); default = null; description = "the longest wait"; };
      "maxFailedStarts" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (1000)); default = null; description = "failed starts in a row before giving up (until xwayland-retry)"; };
    }; }); default = null; description = "when Xwayland fails to start"; };
    "startup" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "programs started with the compositor"; };
    "autostart" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "staggerMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "the time between two programs starting (default 400)"; };
      "afterReveal" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "start them once the desktop is shown (default true)"; };
      "desktopEntries" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "also the .desktop files of ~/.config/autostart and /etc/xdg/autostart (default false)"; };
      "afterTimeoutMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (600000)); default = null; description = "how long an entry waits for those it starts after (default 10000)"; };
      "entries" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf (lib.types.submodule { options = {
        "app" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str (lib.types.addCheck (lib.types.listOf lib.types.str) (l: builtins.length l >= 1 && builtins.length l <= 1000))); default = null; description = "a program and its arguments (no shell): [\"nm-applet\", \"--indicator\"]"; };
        "script" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "an executable file (~ is your home)"; };
        "shell" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "a piece of shell (/bin/sh -c)"; };
        "arguments" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "the script's arguments"; };
        "name" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "its name (for after, ifNotRunning and the status; default the program's)"; };
        "delayMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3600000)); default = null; description = "wait this long more before it starts"; };
        "ifNotRunning" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "not started when a program of its name runs already"; };
        "restart" = lib.mkOption { type = lib.types.nullOr (lib.types.either (lib.types.enum [ "never" "on-crash" "always" ]) (lib.types.submodule { options = {
          "when" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "never" "on-crash" "always" ]); default = null; description = "when it is started again"; };
          "maxTimes" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (1000)); default = null; description = "at most this many times (default 3)"; };
          "withinMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1) (86400000)); default = null; description = "within this time (default 60000)"; };
        }; })); default = null; description = "started again when it ended: never (default), on-crash, always, or { when, maxTimes, withinMillis }"; };
        "after" = lib.mkOption { type = lib.types.nullOr (lib.types.listOf lib.types.str); default = null; description = "start only once these entries have started"; };
        "env" = lib.mkOption { type = lib.types.nullOr (lib.types.attrsOf lib.types.str); default = null; description = "more environment for it"; };
        "dir" = lib.mkOption { type = lib.types.nullOr lib.types.str; default = null; description = "the folder it starts in (~ is your home)"; };
      }; })); default = null; description = "the programs, in order"; };
    }; }); default = null; description = "programs started with the session, one after another, once per session (a reload starts only new entries)"; };
    "reveal" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "dark until warm, then the desktop fades in (default false)"; };
      "fadeMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "how long the fade takes (default 600)"; };
      "maxWaitMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (120000)); default = null; description = "the longest the screen stays dark (default 4000)"; };
      "color" = lib.mkOption { type = lib.types.nullOr (lib.types.strMatching "#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"); default = null; description = "the colour until then (default black)"; };
      "warmFrames" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (120)); default = null; description = "frames drawn dark before it fades in (default 3)"; };
      "hideCursor" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "the pointer hidden while dark (default true)"; };
    }; }); default = null; description = "the start, smooth: dark until warm, then the desktop fades in"; };
    "startupSound" = lib.mkOption { type = lib.types.nullOr (lib.types.submodule { options = {
      "enabled" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "play it (default false)"; };
      "preset" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "airy" "classic" "glass" "soft" ]); default = null; description = "one of the sounds Caulis makes itself (default airy)"; };
      "file" = lib.mkOption { type = lib.types.nullOr (lib.types.either lib.types.str lib.types.path); default = null; description = "a sound file of yours instead (ogg, wav, flac: what the player plays)"; };
      "volume" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck lib.types.number (x: x >= 0 && x <= 1) // { description = "a number from 0 to 1"; }); default = null; description = "how loud, 0 to 1 (default 0.8)"; };
      "delayMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (60000)); default = null; description = "wait this long more before it plays"; };
      "at" = lib.mkOption { type = lib.types.nullOr (lib.types.enum [ "reveal" "start" ]); default = null; description = "when the desktop fades in (default), or right at the start"; };
      "whenHeadless" = lib.mkOption { type = lib.types.nullOr lib.types.bool; default = null; description = "played in the headless backend too (default false)"; };
      "player" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf lib.types.str) (l: builtins.length l >= 1 && builtins.length l <= 1000)); default = null; description = "your player's command, its words ({file}, {volume}, {paVolume}, {percent})"; };
      "players" = lib.mkOption { type = lib.types.nullOr (lib.types.addCheck (lib.types.listOf lib.types.str) (l: builtins.length l >= 1 && builtins.length l <= 1000)); default = null; description = "the players tried in order (default pw-play, paplay, aplay)"; };
      "playerTimeoutMillis" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (1000) (600000)); default = null; description = "how long a player may play before it is stopped (default 30000)"; };
    }; }); default = null; description = "a sound when the session starts"; };
    "logLevel" = lib.mkOption { type = lib.types.nullOr (lib.types.ints.between (0) (3)); default = null; description = "how much the log says"; };
  }; });
in
settingsType
