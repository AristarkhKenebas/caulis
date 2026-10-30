---@meta caulis
-- The caulis table of config.lua, for the Lua language server (LuaLS). Generated: my-compositor --print-lua-stubs

---@class caulis.Settings
---@field layout? caulis.Settings.layout how windows are arranged
---@field scale? number how big everything is (1.0, 1.25, 1.5, 2.0, ...)
---@field workspaceMode? "per-monitor"|"pinned"|"separate"|"spanning"|"primary-only" shared numbers on any monitor, pinned to monitors, each monitor its own, one over all, or only on the primary monitor
---@field primaryMonitor? string the primary monitor ("DP-2")
---@field workspaces? caulis.Settings.workspaces how many workspaces, and how each is
---@field monitorEdges? "touching"|"proportional" how the mouse goes between monitors of different sizes
---@field monitors? table<string, caulis.Settings.monitors> settings per monitor, by connector ("DP-2", "eDP-1", ...)
---@field keyboard? caulis.Settings.keyboard keyboard layouts and key repeat
---@field input? caulis.Settings.input mice, touchpads, tablets, focus
---@field idle? (caulis.Settings.idle)[] what happens when nobody uses the computer
---@field focusOnActivate? boolean a window asking for the focus with a good token gets it (false: only marked urgent)
---@field fullscreenKeepsAwake? boolean a fullscreen window keeps idle timers off
---@field wakeScreensOnInput? boolean input switches dark screens on again
---@field wakeScreensAfterMillis? integer input this soon after screens went dark does not wake them
---@field scratchpadStartMillis? integer how long a scratchpad's program may take before its key starts it again
---@field scriptCallLimitMillis? integer how long one call into config.lua's code may run
---@field compiledScriptsKept? integer how many compiled versions of config.kts stay in its cache
---@field lid? caulis.Settings.lid the laptop's lid
---@field sleep? caulis.Settings.sleep sleep and hibernation
---@field displays? caulis.Settings.displays a laptop with a monitor or projector
---@field cursor? caulis.Settings.cursor the cursor
---@field programs? caulis.Settings.programs programs started inside
---@field performance? caulis.Settings.performance watching the main loop, giving memory back
---@field systemKeys? caulis.Settings.systemKeys what the laptop's keys do: steps, limits, programs
---@field screen? caulis.Settings.screen on a real screen
---@field titleBarStyle? caulis.Settings.titleBarStyle how the compositor's title bars look (titleBars "compositor")
---@field terminal? string the terminal the terminal key opens
---@field lockCommand? string the screen locker
---@field lockShowMillis? integer how long a lock may take to show before it counts as failed (lockFailure)
---@field lockFailure? "own-lock"|"notice" when a lock does not show: the compositor's own plain lock screen, or only a notice (and sleep unlocked)
---@field titleBars? "program"|"compositor"|"none" who draws title bars
---@field keys? caulis.Settings.keys the usual keys, changed or turned off
---@field bind? table<string, string|{ action: string, repeat?: boolean, release?: boolean, locked?: boolean, description?: string }> your own keys: combination → action ("exec firefox", "workspace 3", ...) or { action, repeat, release, locked, description }
---@field modes? table<string, table<string, string|{ action: string, repeat?: boolean, release?: boolean, locked?: boolean, description?: string }>> key modes ("resize"): their own keys, on with the action "mode resize", off with "mode reset"
---@field rules? (caulis.Settings.rules)[] rules for some windows
---@field panelRules? (caulis.Settings.panelRules)[] rules for some panels (bars, launchers, notifications)
---@field specialWorkspaces? caulis.Settings.specialWorkspaces special workspaces (Hyprland's special): shown over the current workspace with special-workspace toggle [name]
---@field screenShader? caulis.Settings.screenShader a shader the whole screen goes through last
---@field effects? caulis.Settings.effects round corners, borders, shadows, blur
---@field animations? caulis.Settings.animations animations
---@field background? caulis.Settings.background what shows behind everything
---@field notices? caulis.Settings.notices the compositor's own messages
---@field gestures? caulis.Settings.gestures touchpad gestures
---@field overviewFill? number how much of the monitor the overview's windows take
---@field resizeBorder? integer the edge to grab floating windows by
---@field doubleClickMillis? integer a double click on a title bar
---@field appearance? caulis.Settings.appearance how programs look: light or dark, by a schedule
---@field hideCursorWhileTyping? boolean hide the cursor while typing
---@field hideCursorAfterSeconds? integer hide the cursor when the mouse is still (0: never)
---@field lockColor? string behind the screen locker
---@field backgroundColor? string under everything (where no window or wallpaper is)
---@field focusStealing? "same-program"|"always"|"never" which programs may take the keyboard by themselves
---@field unlockFocus? "focused-before"|"newest-before" which window gets the keyboard after unlocking (never one opened while locked)
---@field idleInhibitors? boolean programs' idle inhibitors (video players: idle-inhibit) keep the screen on (default true)
---@field idleInhibitorsWhileLocked? boolean idle inhibitors keep the screen on while it is locked too
---@field maxForwardedStringBytes? integer the longest title or app id passed on to other programs, in bytes
---@field maxRegionParts? integer rectangles a program's region keeps exactly
---@field windowListPrograms? (string)[] the programs that may see the list of windows (default: the privileged ones)
---@field permissions? caulis.Settings.permissions which programs may take pictures of the screen, see the window list, read the clipboard, show panels, lock, arrange monitors (as Hyprland's permissions)
---@field primarySelection? boolean middle-click paste (default true; only at the start)
---@field adaptiveSync? boolean variable refresh rate on every monitor that has it, unless its own says otherwise (default false)
---@field bindingModifiers? "xkb"|"physical" modifiers in bindings as the keyboard layout says, or the physical keys
---@field xwaylandRestart? caulis.Settings.xwaylandRestart when Xwayland fails to start
---@field startup? (string)[] programs started with the compositor
---@field autostart? caulis.Settings.autostart programs started with the session, one after another, once per session (a reload starts only new entries)
---@field reveal? caulis.Settings.reveal the start, smooth: dark until warm, then the desktop fades in
---@field startupSound? caulis.Settings.startupSound a sound when the session starts
---@field logLevel? integer how much the log says

---@class caulis.Settings.layout
---@field name? "floating"|"dwindle"|"spiral"|"grid"|"columns"|"rows"|"monocle"|"master-stack"|"centered-master"|"master-strips"|"tree"|"tree-spiral"|"manual"|"scrolling-columns"|"canvas" how windows are arranged
---@field gaps? caulis.Settings.layout.gaps space around and between windows
---@field ratio? number the main window's share (master layouts)
---@field side? "left"|"right"|"top"|"bottom" the main window's side (master-stack)
---@field masters? integer how many masters (master-stack)
---@field newWindow? "stack-end"|"stack-top"|"master" where a new window goes (master-stack)
---@field splitRatio? number the old window's share when a new one splits it (tree)
---@field columnWidths? (number)[] the widths column-width goes through (scrolling-columns)
---@field newColumnWidth? number a new column's width (scrolling-columns)
---@field centerFocused? boolean the focused column always in the middle (scrolling-columns)

---@class caulis.Settings.layout.gaps
---@field inner? integer space between tiled windows
---@field outer? integer space between them and the screen's edges
---@field top? integer space at the top edge (else outer)
---@field right? integer space at the right edge (else outer)
---@field bottom? integer space at the bottom edge (else outer)
---@field left? integer space at the left edge (else outer)

---@class caulis.Settings.workspaces
---@field count? integer how many there are (1: no workspaces, one desktop)
---@field rules? (caulis.Settings.workspaces.rules)[] how single workspaces are

---@class caulis.Settings.workspaces.rules
---@field workspace? integer its number
---@field name? string its name for bars ("web")
---@field monitor? string its home monitor ("DP-2")
---@field layout? caulis.Settings.workspaces.rules.layout its own layout
---@field persistent? boolean it always exists, even empty

---@class caulis.Settings.workspaces.rules.layout
---@field name? "floating"|"dwindle"|"spiral"|"grid"|"columns"|"rows"|"monocle"|"master-stack"|"centered-master"|"master-strips"|"tree"|"tree-spiral"|"manual"|"scrolling-columns"|"canvas" how windows are arranged
---@field gaps? caulis.Settings.workspaces.rules.layout.gaps space around and between windows
---@field ratio? number the main window's share (master layouts)
---@field side? "left"|"right"|"top"|"bottom" the main window's side (master-stack)
---@field masters? integer how many masters (master-stack)
---@field newWindow? "stack-end"|"stack-top"|"master" where a new window goes (master-stack)
---@field splitRatio? number the old window's share when a new one splits it (tree)
---@field columnWidths? (number)[] the widths column-width goes through (scrolling-columns)
---@field newColumnWidth? number a new column's width (scrolling-columns)
---@field centerFocused? boolean the focused column always in the middle (scrolling-columns)

---@class caulis.Settings.workspaces.rules.layout.gaps
---@field inner? integer space between tiled windows
---@field outer? integer space between them and the screen's edges
---@field top? integer space at the top edge (else outer)
---@field right? integer space at the right edge (else outer)
---@field bottom? integer space at the bottom edge (else outer)
---@field left? integer space at the left edge (else outer)

---@class caulis.Settings.monitors
---@field scale? number its own scale
---@field position? (integer)[] [x, y] on the desktop, in units
---@field mode? (number)[] [width, height] or [width, height, hz]
---@field enabled? boolean false: not used
---@field transform? "normal"|"90"|"180"|"270"|"flipped"|"flipped-90"|"flipped-180"|"flipped-270" turned clockwise (a screen on its side: 90)
---@field maxBpc? integer the most bits per colour the link may use (default 8)
---@field adaptiveSync? boolean variable refresh rate (FreeSync) if the monitor has it

---@class caulis.Settings.keyboard
---@field layouts? string as for setxkbmap: "us,ru"
---@field variants? string one per layout: ",phonetic"
---@field options? string e.g. "grp:alt_shift_toggle"
---@field model? string e.g. "pc105"
---@field repeatRate? integer held keys repeat this many times a second (0: never)
---@field repeatDelay? integer milliseconds before a held key repeats
---@field numLock? boolean Num Lock on (true) or off (false) from the start (else as the keyboard is)

---@class caulis.Settings.input
---@field focusFollowsMouse? boolean the window under the pointer gets the keyboard
---@field pointers? caulis.Settings.input.pointers every mouse, trackball and touchpad
---@field touchpads? caulis.Settings.input.touchpads touchpads (on top of pointers)
---@field tablets? caulis.Settings.input.tablets drawing tablets and their pens
---@field devices? table<string, caulis.Settings.input.devices> single devices by name (--devices lists them; * matches anything)

---@class caulis.Settings.input.pointers
---@field enabled? boolean false: not used at all
---@field offWithMouse? boolean touchpads: off while a mouse is plugged in
---@field speed? number pointer speed, -1 to 1 (0: the device's normal)
---@field accelProfile? "flat"|"adaptive" flat: as fast as the hand; adaptive: faster when moved fast
---@field naturalScroll? boolean content follows the fingers
---@field scrollFactor? number scrolling goes this many times as far
---@field scrollMethod? "none"|"two-finger"|"edge"|"button" how it scrolls
---@field scrollButton? string with scrollMethod button: the button ("MouseMiddle")
---@field leftHanded? boolean buttons swapped; tablets: turned around
---@field middleEmulation? boolean left and right together are a middle click
---@field tap? boolean touchpads: a tap is a click
---@field tapButtons? "left-right-middle"|"left-middle-right" touchpads: what two and three fingers tap
---@field tapDrag? boolean touchpads: tap, touch again and move to drag
---@field dragLock? boolean touchpads: a drag goes on when the finger lifts for a moment
---@field disableWhileTyping? boolean touchpads: off while typing
---@field clickMethod? "areas"|"fingers" touchpads: a click by the area pressed or by the number of fingers

---@class caulis.Settings.input.touchpads
---@field enabled? boolean false: not used at all
---@field offWithMouse? boolean touchpads: off while a mouse is plugged in
---@field speed? number pointer speed, -1 to 1 (0: the device's normal)
---@field accelProfile? "flat"|"adaptive" flat: as fast as the hand; adaptive: faster when moved fast
---@field naturalScroll? boolean content follows the fingers
---@field scrollFactor? number scrolling goes this many times as far
---@field scrollMethod? "none"|"two-finger"|"edge"|"button" how it scrolls
---@field scrollButton? string with scrollMethod button: the button ("MouseMiddle")
---@field leftHanded? boolean buttons swapped; tablets: turned around
---@field middleEmulation? boolean left and right together are a middle click
---@field tap? boolean touchpads: a tap is a click
---@field tapButtons? "left-right-middle"|"left-middle-right" touchpads: what two and three fingers tap
---@field tapDrag? boolean touchpads: tap, touch again and move to drag
---@field dragLock? boolean touchpads: a drag goes on when the finger lifts for a moment
---@field disableWhileTyping? boolean touchpads: off while typing
---@field clickMethod? "areas"|"fingers" touchpads: a click by the area pressed or by the number of fingers

---@class caulis.Settings.input.tablets
---@field enabled? boolean false: not used at all
---@field leftHanded? boolean buttons swapped; tablets: turned around
---@field monitor? string tablets: the monitor it covers (else a pen screen its laptop's, others all monitors as one)
---@field keepAspect? boolean tablets: keep the monitor's shape, a strip of the tablet unused (default true)
---@field area? caulis.Settings.input.tablets.area tablets: only this part of the tablet is used
---@field pointerOnly? boolean tablets: the pen is only ever the pointer (no pressure or tilt for programs)
---@field tipPressure? number tablets: how hard the pen presses to touch, 0.01 to 1 (else libinput decides)
---@field lowerButton? string tablets: the pen's lower button as a mouse button ("MouseRight", "none")
---@field upperButton? string tablets: the pen's upper button as a mouse button ("MouseMiddle", "none")
---@field thirdButton? string tablets: a third button of the pen as a mouse button ("MouseSide", "none")

---@class caulis.Settings.input.tablets.area
---@field x? number where it starts across, 0 to 1 of the tablet
---@field y? number where it starts down, 0 to 1 of the tablet
---@field width? number how wide, 0 to 1 of the tablet (else to its edge)
---@field height? number how tall, 0 to 1 of the tablet (else to its edge)

---@class caulis.Settings.input.devices
---@field enabled? boolean false: not used at all
---@field offWithMouse? boolean touchpads: off while a mouse is plugged in
---@field speed? number pointer speed, -1 to 1 (0: the device's normal)
---@field accelProfile? "flat"|"adaptive" flat: as fast as the hand; adaptive: faster when moved fast
---@field naturalScroll? boolean content follows the fingers
---@field scrollFactor? number scrolling goes this many times as far
---@field scrollMethod? "none"|"two-finger"|"edge"|"button" how it scrolls
---@field scrollButton? string with scrollMethod button: the button ("MouseMiddle")
---@field leftHanded? boolean buttons swapped; tablets: turned around
---@field middleEmulation? boolean left and right together are a middle click
---@field tap? boolean touchpads: a tap is a click
---@field tapButtons? "left-right-middle"|"left-middle-right" touchpads: what two and three fingers tap
---@field tapDrag? boolean touchpads: tap, touch again and move to drag
---@field dragLock? boolean touchpads: a drag goes on when the finger lifts for a moment
---@field disableWhileTyping? boolean touchpads: off while typing
---@field clickMethod? "areas"|"fingers" touchpads: a click by the area pressed or by the number of fingers
---@field monitor? string tablets: the monitor it covers (else a pen screen its laptop's, others all monitors as one)
---@field keepAspect? boolean tablets: keep the monitor's shape, a strip of the tablet unused (default true)
---@field area? caulis.Settings.input.devices.area tablets: only this part of the tablet is used
---@field pointerOnly? boolean tablets: the pen is only ever the pointer (no pressure or tilt for programs)
---@field tipPressure? number tablets: how hard the pen presses to touch, 0.01 to 1 (else libinput decides)
---@field lowerButton? string tablets: the pen's lower button as a mouse button ("MouseRight", "none")
---@field upperButton? string tablets: the pen's upper button as a mouse button ("MouseMiddle", "none")
---@field thirdButton? string tablets: a third button of the pen as a mouse button ("MouseSide", "none")

---@class caulis.Settings.input.devices.area
---@field x? number where it starts across, 0 to 1 of the tablet
---@field y? number where it starts down, 0 to 1 of the tablet
---@field width? number how wide, 0 to 1 of the tablet (else to its edge)
---@field height? number how tall, 0 to 1 of the tablet (else to its edge)

---@class caulis.Settings.idle
---@field after? integer seconds without input
---@field action? string what happens then ("dpms off", "lock", "exec ...")
---@field resume? string what happens when someone is back

---@class caulis.Settings.lid
---@field close? string when the laptop's lid closes
---@field open? string when it opens
---@field turnsScreenOff? boolean the laptop's screen is off while the lid is closed and another monitor is in use

---@class caulis.Settings.sleep
---@field lock? boolean lock the screen before the computer sleeps
---@field lockWaitMillis? integer how long sleep waits for the lock screen to show
---@field ownLockShowMillis? integer how long the compositor's own lock screen shows before sleep, when the locker did not
---@field before? string just before it sleeps
---@field after? string when it woke up

---@class caulis.Settings.displays
---@field chooserOnPlug? boolean a monitor never seen before shows the display modes

---@class caulis.Settings.cursor
---@field theme? string an installed cursor theme
---@field size? integer its size

---@class caulis.Settings.programs
---@field environment? table<string, string> more environment for programs started inside (wins over Caulis's own choices)
---@field xwayland? boolean run X11 programs too (only at the start)
---@field waylandFirst? boolean start programs the Wayland way, through X11 when that fails
---@field x11Programs? (string)[] programs always started through X11
---@field x11Fallback? boolean a program that connected and failed at once is started again through X11
---@field x11FallbackMillis? integer how soon it must fail for that
---@field stopOnQuit? boolean programs started inside are asked to end when the compositor quits
---@field sharpJavaScaling? boolean Java programs sharp on fractional scales (only at the start)
---@field desktopName? string what programs are told the desktop is called (XDG_CURRENT_DESKTOP)
---@field exportEnvironment? boolean hand the environment to systemd and D-Bus activation at the start (default: on a real screen only)
---@field portalsToRestart? (string)[] services started again with this compositor's environment when they had another one
---@field output? "discard"|"log"|"file" where started programs' output goes
---@field outputFile? string the file for output "file" (default ~/.cache/<name>/programs.log)
---@field start? "native"|"java" how programs are started: native (posix_spawn; survives Java being updated meanwhile) or java (ProcessBuilder)
---@field javaUpdatedNotice? string shown once when Java was updated under the running compositor ("": not shown)

---@class caulis.Settings.performance
---@field stallReportMillis? integer the main loop busy this long with one thing is logged with what it was (0: not watched)
---@field trimMemory? boolean memory the C library freed is given back to the system after the start and now and then
---@field trimMemoryEveryMinutes? integer how often after the start (0: only after the start)
---@field mallocPools? integer at most this many malloc pools for the compositor (0: glibc's own; only at the start)
---@field quickCursor? boolean the cursor moves the moment the pointer does, not with the next frame
---@field mallocMapAboveKb? integer blocks of this many kB or more are given back to the system the moment they are freed (0: glibc's own; only at the start)
---@field inputThread? boolean keyboards, mice and touchpads are read on a thread of their own; the cursor keeps moving while the compositor is busy
---@field glVendorByDriver? boolean only the EGL driver for the GPU in use is loaded, not every one installed (only at the start)
---@field widgetThreads? integer threads the compositor's own widgets (bars, menus) draw their pixels on (0: its own thread)

---@class caulis.Settings.systemKeys
---@field volumeStep? integer percent per press of the volume keys
---@field maxVolume? number the loudest they go (1.0: 100 %)
---@field unmuteOnVolumeChange? boolean changing the volume switches the sound on again
---@field brightnessStep? integer percent per press of the brightness keys
---@field minBrightnessPercent? integer the darkest they go
---@field backlight? string the backlight, by its name in /sys/class/backlight (default: the first)
---@field wpctl? string the program for the volume and the microphone
---@field brightnessctl? string the program for the brightness without logind
---@field playerctl? string the program for music players
---@field timeoutMillis? integer how long these programs may take

---@class caulis.Settings.screen
---@field hardwareCursor? boolean the pointer on the display's cursor plane (only at the start)
---@field directScanout? boolean fullscreen programs' pictures directly on the monitor (only at the start)
---@field touchscreenMonitor? string the monitor touch screens cover (default: the laptop's own)
---@field builtInConnectors? (string)[] how the laptop's own screen's connector begins (default eDP, LVDS, DSI)

---@class caulis.Settings.titleBarStyle
---@field height? integer how high, in units
---@field font? string the title's font
---@field fontSize? integer the title's size
---@field bold? boolean a bold title
---@field centerTitle? boolean the title in the middle
---@field buttons? ("minimize"|"maximize"|"close")[] the buttons, in order
---@field buttonsLeft? boolean the buttons on the left
---@field focusedBackground? string behind the title with the focus
---@field unfocusedBackground? string behind it without
---@field focusedText? string the title with the focus
---@field unfocusedText? string the title without
---@field buttonHover? string behind a button under the pointer
---@field closeHover? string behind the close button under the pointer

---@class caulis.Settings.keys
---@field terminal? false|string the terminal key (false: off)
---@field close? false|string the close key (false: off)
---@field lock? false|string the lock key (false: off)
---@field dumpState? false|string the dumpState key (false: off)
---@field maximize? false|string the maximize key (false: off)
---@field fullscreen? false|string the fullscreen key (false: off)
---@field minimize? false|string the minimize key (false: off)
---@field float? false|string the float key (false: off)
---@field nextWindow? false|string the nextWindow key (false: off)
---@field overview? false|string the overview key (false: off)
---@field focusLeft? false|string the focusLeft key (false: off)
---@field focusRight? false|string the focusRight key (false: off)
---@field workspace? false|string the workspace key (false: off)
---@field moveToWorkspace? false|string the moveToWorkspace key (false: off)
---@field move? false|string the move key (false: off)
---@field resize? false|string the resize key (false: off)
---@field preselect? false|string the preselect key (false: off)
---@field rotate? false|string the rotate key (false: off)
---@field balance? false|string the balance key (false: off)
---@field columnWidth? false|string the columnWidth key (false: off)
---@field splitBeside? false|string the splitBeside key (false: off)
---@field splitBelow? false|string the splitBelow key (false: off)
---@field quit? false|string the quit key (false: off)
---@field consoles? false|string the consoles key (false: off)
---@field restoreShortcuts? false|string the restoreShortcuts key (false: off)
---@field volumeUp? false|string the volumeUp key (false: off)
---@field volumeDown? false|string the volumeDown key (false: off)
---@field volumeMute? false|string the volumeMute key (false: off)
---@field micMute? false|string the micMute key (false: off)
---@field brightnessUp? false|string the brightnessUp key (false: off)
---@field brightnessDown? false|string the brightnessDown key (false: off)
---@field touchpad? false|string the touchpad key (false: off)
---@field displays? false|string the displays key (false: off)
---@field mediaPlay? false|string the mediaPlay key (false: off)
---@field mediaNext? false|string the mediaNext key (false: off)
---@field mediaPrevious? false|string the mediaPrevious key (false: off)
---@field windowDrag? false|string the windowDrag key (false: off)
---@field quitWhileLocked? boolean the quit key works on a locked screen too (default false: it would get round the lock)

---@class caulis.Settings.rules
---@field appId? string windows with this app id (case does not matter)
---@field appIdPattern? string windows whose app id fits this
---@field title? string windows whose title contains this
---@field titlePattern? string windows whose title fits this
---@field program? string windows of this program (as ps shows it)
---@field initialAppId? string windows whose app id was this when they opened
---@field initialTitle? string windows whose title contained this when they opened
---@field hasTag? string windows with this tag (from an earlier rule's tags, or the action tag)
---@field isFloating? boolean while it floats (true) or is tiled (false)
---@field isFullscreen? boolean while it is fullscreen (or not)
---@field isMaximized? boolean while it is maximized (or not)
---@field isFocused? boolean while it has the keyboard (or not)
---@field isPinned? boolean while it is pinned (or not)
---@field isXwayland? boolean X11 programs (true) or Wayland ones (false)
---@field onWorkspace? integer while it is on this workspace
---@field unless? caulis.Settings.rules.unless windows that do NOT fit this
---@field anyOf? (caulis.Settings.rules.anyOf)[] windows that fit at least one of these
---@field workspace? integer the workspace it opens on
---@field monitor? string the monitor it opens on
---@field goToWorkspace? boolean show its workspace when it opens
---@field floating? boolean float (true) or tile (false)
---@field size? (integer)[] [width, height] when floating
---@field position? (integer)[] [x, y] when floating, from the monitor's free area
---@field center? boolean in the middle of its monitor when floating
---@field maximized? boolean starts maximized
---@field fullscreen? boolean starts fullscreen
---@field focus? boolean false: opens without taking the keyboard
---@field titleBars? "program"|"compositor"|"none" who draws its title bar
---@field opacity? number how solid it is
---@field margin? integer|(integer)[] room around it: one number, or [top, right, bottom, left]
---@field pinned? boolean opens pinned: floating, on every workspace of its monitor
---@field inactiveOpacity? number how solid it is while another window has the keyboard
---@field cornerRadius? integer how round its corners are, in pixels
---@field border? boolean false: no border; true: the usual one
---@field borderWidth? integer its border's width in pixels
---@field borderColor? string|(string)[] its border's colour, or [with the keyboard, without]
---@field borderPaint? caulis.Settings.rules.borderPaint its border's paints: gradients, shaders (focused, unfocused, urgent)
---@field specialWorkspace? string the special workspace it opens in, unseen (unless goToWorkspace)
---@field shadow? boolean false: no shadow; true: the usual one
---@field blur? boolean false: nothing blurred behind it; true: frosted glass
---@field dimInactive? number how much darker it is without the keyboard (0: never)
---@field minSize? (integer)[] [width, height] it is at least while floating
---@field maxSize? (integer)[] [width, height] it is at most while floating
---@field keepAwake? boolean the screen stays on while it is shown
---@field animated? boolean false: no animations when it opens, closes or moves
---@field hiddenFromCapture? boolean screenshots and screen sharing show a black box in its place
---@field takesFocus? boolean false: it never gets the keyboard
---@field ignore? ("fullscreen"|"maximize"|"minimize"|"activate")[] what its program asks that is not done
---@field tags? (string)[] tags it has while it fits (for later rules with hasTag)

---@class caulis.Settings.rules.unless
---@field appId? string windows with this app id (case does not matter)
---@field appIdPattern? string windows whose app id fits this
---@field title? string windows whose title contains this
---@field titlePattern? string windows whose title fits this
---@field program? string windows of this program (as ps shows it)
---@field initialAppId? string windows whose app id was this when they opened
---@field initialTitle? string windows whose title contained this when they opened
---@field hasTag? string windows with this tag (from an earlier rule's tags, or the action tag)
---@field isFloating? boolean while it floats (true) or is tiled (false)
---@field isFullscreen? boolean while it is fullscreen (or not)
---@field isMaximized? boolean while it is maximized (or not)
---@field isFocused? boolean while it has the keyboard (or not)
---@field isPinned? boolean while it is pinned (or not)
---@field isXwayland? boolean X11 programs (true) or Wayland ones (false)
---@field onWorkspace? integer while it is on this workspace

---@class caulis.Settings.rules.anyOf
---@field appId? string windows with this app id (case does not matter)
---@field appIdPattern? string windows whose app id fits this
---@field title? string windows whose title contains this
---@field titlePattern? string windows whose title fits this
---@field program? string windows of this program (as ps shows it)
---@field initialAppId? string windows whose app id was this when they opened
---@field initialTitle? string windows whose title contained this when they opened
---@field hasTag? string windows with this tag (from an earlier rule's tags, or the action tag)
---@field isFloating? boolean while it floats (true) or is tiled (false)
---@field isFullscreen? boolean while it is fullscreen (or not)
---@field isMaximized? boolean while it is maximized (or not)
---@field isFocused? boolean while it has the keyboard (or not)
---@field isPinned? boolean while it is pinned (or not)
---@field isXwayland? boolean X11 programs (true) or Wayland ones (false)
---@field onWorkspace? integer while it is on this workspace

---@class caulis.Settings.rules.borderPaint
---@field focused? any|caulis.Settings.rules.borderPaint.focused.more with the keyboard
---@field unfocused? any|caulis.Settings.rules.borderPaint.unfocused.more without
---@field urgent? any|caulis.Settings.rules.borderPaint.urgent.more while it wants attention

---@class caulis.Settings.rules.borderPaint.focused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.rules.borderPaint.unfocused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.rules.borderPaint.urgent.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.panelRules
---@field namespace? string panels that call themselves this (waybar, fuzzel, ...)
---@field namespacePattern? string panels whose name fits this
---@field layer? "background"|"bottom"|"top"|"overlay" panels on this layer
---@field blur? boolean frosted glass behind it
---@field cornerRadius? integer how round its corners are
---@field shadow? boolean a shadow under it
---@field opacity? number how solid it is
---@field dimAround? number everything behind it this much darker
---@field hiddenFromCapture? boolean screenshots and screen sharing show a black box in its place

---@class caulis.Settings.specialWorkspaces
---@field dim? number how much darker everything behind a shown one is
---@field blur? false|caulis.Settings.specialWorkspaces.blur what is behind it blurred
---@field gap? integer space between it and the monitor's edges
---@field size? number how much of the monitor it takes
---@field animation? "windows"|"windowsIn"|"windowsOut"|"windowsMove"|"scratchpads"|"minimize"|"restore"|"workspaces"|"overview"|"border"|"screenshot"|"fade"|"fadeScale"|"fadeBackground"|"fadeNotices"|"fadeOsd" the animation its windows come and go with
---@field hideWhenEmpty? boolean hide when its last window closes or leaves
---@field newWindowsJoin? boolean windows opening while it is shown go into it
---@field hideOnLock? boolean hide when the screen locks
---@field clickOutsideHides? boolean a click on the dimmed area hides it
---@field layout? caulis.Settings.specialWorkspaces.layout its own layout (else the usual one)

---@class caulis.Settings.specialWorkspaces.blur
---@field size? integer how strong
---@field passes? integer how smooth
---@field tone? "none"|"hyprland" start from no toning, or Hyprland's
---@field noise? number grain (a little hides banding)
---@field contrast? number 1: as it is
---@field brightness? number 1: as it is
---@field vibrancy? number stronger colours (0: as they are)

---@class caulis.Settings.specialWorkspaces.layout
---@field name? "floating"|"dwindle"|"spiral"|"grid"|"columns"|"rows"|"monocle"|"master-stack"|"centered-master"|"master-strips"|"tree"|"tree-spiral"|"manual"|"scrolling-columns"|"canvas" how windows are arranged
---@field gaps? caulis.Settings.specialWorkspaces.layout.gaps space around and between windows
---@field ratio? number the main window's share (master layouts)
---@field side? "left"|"right"|"top"|"bottom" the main window's side (master-stack)
---@field masters? integer how many masters (master-stack)
---@field newWindow? "stack-end"|"stack-top"|"master" where a new window goes (master-stack)
---@field splitRatio? number the old window's share when a new one splits it (tree)
---@field columnWidths? (number)[] the widths column-width goes through (scrolling-columns)
---@field newColumnWidth? number a new column's width (scrolling-columns)
---@field centerFocused? boolean the focused column always in the middle (scrolling-columns)

---@class caulis.Settings.specialWorkspaces.layout.gaps
---@field inner? integer space between tiled windows
---@field outer? integer space between them and the screen's edges
---@field top? integer space at the top edge (else outer)
---@field right? integer space at the right edge (else outer)
---@field bottom? integer space at the bottom edge (else outer)
---@field left? integer space at the left edge (else outer)

---@class caulis.Settings.screenShader
---@field preset? "grayscale"|"invert" a ready one
---@field warm? number a night light: this much less blue
---@field file? string your own: vec4 effect(vec4 color, vec2 at) (read again when saved)
---@field moving? boolean drawn every frame (it uses time)

---@class caulis.Settings.effects
---@field preset? "none"|"modern" start from no effects, or the modern look
---@field cornerRadius? integer how round the corners are
---@field border? false|caulis.Settings.effects.border a frame around windows
---@field shadow? false|caulis.Settings.effects.shadow the shadow (of the focused window)
---@field inactiveShadow? false|caulis.Settings.effects.inactiveShadow the shadow of windows without the focus
---@field blur? false|caulis.Settings.effects.blur blur behind see-through windows
---@field dimInactive? number how much darker windows without the focus are (0: not at all)
---@field opacity? number how solid the focused window is
---@field inactiveOpacity? number how solid the others are
---@field focusChangeMillis? integer how long border and shadow change when the focus moves

---@class caulis.Settings.effects.border
---@field width? integer its width
---@field focused? any|caulis.Settings.effects.border.focused.more with the focus
---@field unfocused? any|caulis.Settings.effects.border.unfocused.more without
---@field urgent? any|caulis.Settings.effects.border.urgent.more a window that wants attention (else as the others)
---@field groupedFocused? any|caulis.Settings.effects.border.groupedFocused.more the focused window of a group (else focused)
---@field groupedUnfocused? any|caulis.Settings.effects.border.groupedUnfocused.more the other windows of a group (else unfocused)
---@field animate? boolean false: turning gradients and shaders using time stand still

---@class caulis.Settings.effects.border.focused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.border.unfocused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.border.urgent.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.border.groupedFocused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.border.groupedUnfocused.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.shadow
---@field softness? integer how far it fades out
---@field offsetX? integer moved right
---@field offsetY? integer moved down
---@field color? any|caulis.Settings.effects.shadow.color.more its colour or paint (a coloured one is a glow)
---@field spread? integer how much bigger than the window

---@class caulis.Settings.effects.shadow.color.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.inactiveShadow
---@field softness? integer how far it fades out
---@field offsetX? integer moved right
---@field offsetY? integer moved down
---@field color? any|caulis.Settings.effects.inactiveShadow.color.more its colour or paint (a coloured one is a glow)
---@field spread? integer how much bigger than the window

---@class caulis.Settings.effects.inactiveShadow.color.more
---@field gradient? (string)[] colours across the window
---@field conic? (string)[] colours around the window's middle
---@field degrees? number the gradient's direction or start
---@field turnsPerSecond? number how fast it turns
---@field rainbow? number a rainbow going round once every so many seconds
---@field shader? string a paint shader file (vec4 paint(vec2 at))

---@class caulis.Settings.effects.blur
---@field size? integer how strong
---@field passes? integer how smooth
---@field tone? "none"|"hyprland" start from no toning, or Hyprland's
---@field noise? number grain (a little hides banding)
---@field contrast? number 1: as it is
---@field brightness? number 1: as it is
---@field vibrancy? number stronger colours (0: as they are)

---@class caulis.Settings.animations
---@field enabled? boolean animations on (default false: everything changes at once; one turned on by name runs all the same)
---@field millis? integer the global time in milliseconds, of those without one of their own (0: none at all)
---@field windowOpen? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.windowOpen.more a window appearing, as code's look (older; windowsIn is the same by name)
---@field windowClose? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.windowClose.more a window going away, as code's look (older; windowsOut is the same by name)
---@field windowMove? "move"|"none"|caulis.Settings.animations.windowMove.more windows gliding to new places (older; windowsMove is the same by name)
---@field workspaceSwitch? "slide"|"slide-vertical"|"fade"|"none"|caulis.Settings.animations.workspaceSwitch.more another workspace shown (older; workspaces is the same by name)
---@field curve? string the global curve: how animations move
---@field curves? table<string, caulis.Settings.animations.curves> your own curves by name, for curve fields
---@field overviewMillis? integer the overview zooming (older; overview.millis)
---@field scaleChangeMillis? integer the picture melting when the scale changes (older; fadeScale.millis)
---@field windows? boolean|string|caulis.Settings.animations.windows.more.more every animation of windows below
---@field windowsIn? boolean|string|caulis.Settings.animations.windowsIn.more.more a window appearing (Animations.windowOpen)
---@field windowsOut? boolean|string|caulis.Settings.animations.windowsOut.more.more a window going away (Animations.windowClose)
---@field windowsMove? boolean|string|caulis.Settings.animations.windowsMove.more.more windows gliding to a new place or size: a layout change, maximize, a neighbour closing, the view settling after a swipe (Animations.windowMove)
---@field scratchpads? boolean|string|caulis.Settings.animations.scratchpads.more.more a scratchpad sliding in and out (Animations.windowMove's look)
---@field minimize? boolean|string|caulis.Settings.animations.minimize.more.more a window going into the dock (Animations.minimize)
---@field restore? boolean|string|caulis.Settings.animations.restore.more.more a minimized window coming back (Animations.restore)
---@field workspaces? boolean|string|caulis.Settings.animations.workspaces.more.more another workspace shown, also after a swipe (Animations.workspaceSwitch)
---@field overview? boolean|string|caulis.Settings.animations.overview.more.more the overview zooming out and in, also finishing a pinch (Animations.overviewMillis; 1.5 × the global time)
---@field border? boolean|string|caulis.Settings.animations.border.more.more border, shadow, opacity and dimming changing when the focus moves (Effects.focusChangeMillis, 150)
---@field screenshot? boolean|string|caulis.Settings.animations.screenshot.more.more the screenshot chooser coming in: the screen darkening, its toolbar rising (caulis-screenshot; ChooserBehaviour.appearMillis, 170)
---@field fade? boolean|string|caulis.Settings.animations.fade.more.more every fade below
---@field fadeScale? boolean|string|caulis.Settings.animations.fadeScale.more.more the picture melting into the new one when a monitor's scale changes (Animations.scaleChangeMillis, 250)
---@field fadeBackground? boolean|string|caulis.Settings.animations.fadeBackground.more.more one background melting into the next (backgroundChangeMillis, 400)
---@field fadeNotices? boolean|string|caulis.Settings.animations.fadeNotices.more.more the compositor's notices fading in and out (NoticeStyle.fadeMillis, 180)
---@field fadeOsd? boolean|string|caulis.Settings.animations.fadeOsd.more.more the volume and brightness display fading in and out (150)

---@class caulis.Settings.animations.windowOpen.more
---@field style? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.windowClose.more
---@field style? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.windowMove.more
---@field style? "move"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.workspaceSwitch.more
---@field style? "slide"|"slide-vertical"|"fade"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.curves
---@field bezier? (number)[] [x1, y1, x2, y2] as Hyprland's bezier (CSS cubic-bezier)
---@field spring? caulis.Settings.animations.curves.spring a spring: an animation takes as long as it needs to rest

---@class caulis.Settings.animations.curves.spring
---@field stiffness? number how strongly it pulls
---@field damping? number how much it is held back (less: it bounces)
---@field mass? number how heavy

---@class caulis.Settings.animations.windows.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none

---@class caulis.Settings.animations.windowsIn.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none

---@class caulis.Settings.animations.windowsOut.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none

---@class caulis.Settings.animations.windowsMove.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: move, none

---@class caulis.Settings.animations.scratchpads.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: move, none

---@class caulis.Settings.animations.minimize.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none

---@class caulis.Settings.animations.restore.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: popin [80%], pop [90%], fade, scale [90%], slide [up|down|left|right] [40], slide-up, genie, fly, move, none

---@class caulis.Settings.animations.workspaces.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look: slide, slidevert, fade, none

---@class caulis.Settings.animations.overview.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.border.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.screenshot.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.fade.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.fadeScale.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.fadeBackground.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.fadeNotices.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.animations.fadeOsd.more.more
---@field enabled? boolean on or off (else as the one above it, in the end animations.enabled)
---@field millis? integer how long it takes in milliseconds (else as the one above it, its own usual, or animations.millis)
---@field speed? number how long it takes in tenths of a second, as Hyprland's speed (instead of millis)
---@field curve? string how it moves
---@field style? string its look (it has one only)

---@class caulis.Settings.background
---@field color? string one colour
---@field gradient? (string)[] [from, to]
---@field degrees? number the gradient's direction
---@field image? string a picture (next to the configuration, ~/..., or /...)
---@field fit? "fill"|"fit"|"stretch"|"center"|"tile" how the picture covers the monitor
---@field behind? string the colour around a picture that does not cover it
---@field span? boolean one picture over all monitors
---@field shader? string a wallpaper shader file (vec4 wallpaper(vec2 at))
---@field framesPerSecond? integer how often a moving shader is drawn
---@field changeMillis? integer how long one background melts into the next

---@class caulis.Settings.notices
---@field placement? "banner"|"toast"|"hidden" a strip, cards in a corner, or none
---@field bottom? boolean a banner at the bottom
---@field room? "push-all"|"push-windows"|"cover" what makes way for a banner
---@field errorRoom? "push-all"|"push-windows"|"cover" what makes way for an error's banner (else as room)
---@field corner? "top-left"|"top"|"top-right"|"bottom-left"|"bottom"|"bottom-right" where toasts go
---@field fontSize? integer their text size
---@field everyMonitor? boolean on every monitor
---@field infoSeconds? number how long information stays (0: until clicked)
---@field warningSeconds? number how long warnings stay
---@field errorSeconds? number how long errors stay

---@class caulis.Settings.gestures
---@field dragViewFingers? integer fingers that drag the view (0: off)
---@field overviewPinchFingers? integer fingers whose pinch opens the overview (0: off)
---@field workspaceSwipeFingers? integer fingers whose swipe changes the workspace (0: off)
---@field swipeDistance? number how far fingers move for a swipe

---@class caulis.Settings.appearance
---@field colorScheme? "default"|"dark"|"light" light or dark for programs (default: each its own)
---@field darkFrom? any with lightFrom: dark from this time of day
---@field lightFrom? any with darkFrom: light from this time of day
---@field latitude? number with longitude: dark from sunset to sunrise there
---@field longitude? number with latitude: the place (east positive)
---@field accentColor? string the accent colour programs are told
---@field gtkLightTheme? string with gtkDarkTheme: GTK 3 programs' theme when light ("Adwaita")
---@field gtkDarkTheme? string with gtkLightTheme: their theme when dark ("Adwaita-dark")
---@field toPrograms? boolean tell programs (default: on a real screen only, not nested)

---@class caulis.Settings.permissions
---@field rules? (caulis.Settings.permissions.rules)[] the rules, the first that matches decides
---@field defaults? table<string, "allow"|"ask"|"deny"> what a feature gets when no rule matches (default: the privileged rule)

---@class caulis.Settings.permissions.rules
---@field program? string the program's path ("/usr/bin/grim") or name ("grim"), a regular expression
---@field feature? "screencopy"|"window-list"|"clipboard"|"panels"|"lock"|"monitors"|"gamma" what it wants
---@field mode? "allow"|"ask"|"deny" allow, ask (screencopy only) or deny

---@class caulis.Settings.xwaylandRestart
---@field millis? integer wait after a failed start (doubled each time)
---@field maxMillis? integer the longest wait
---@field maxFailedStarts? integer failed starts in a row before giving up (until xwayland-retry)

---@class caulis.Settings.autostart
---@field staggerMillis? integer the time between two programs starting (default 400)
---@field afterReveal? boolean start them once the desktop is shown (default true)
---@field desktopEntries? boolean also the .desktop files of ~/.config/autostart and /etc/xdg/autostart (default false)
---@field afterTimeoutMillis? integer how long an entry waits for those it starts after (default 10000)
---@field entries? (caulis.Settings.autostart.entries)[] the programs, in order

---@class caulis.Settings.autostart.entries
---@field app? string|(string)[] a program and its arguments (no shell): ["nm-applet", "--indicator"]
---@field script? string an executable file (~ is your home)
---@field shell? string a piece of shell (/bin/sh -c)
---@field arguments? (string)[] the script's arguments
---@field name? string its name (for after, ifNotRunning and the status; default the program's)
---@field delayMillis? integer wait this long more before it starts
---@field ifNotRunning? boolean not started when a program of its name runs already
---@field restart? "never"|"on-crash"|"always"|caulis.Settings.autostart.entries.restart.more started again when it ended: never (default), on-crash, always, or { when, maxTimes, withinMillis }
---@field after? (string)[] start only once these entries have started
---@field env? table<string, string> more environment for it
---@field dir? string the folder it starts in (~ is your home)

---@class caulis.Settings.autostart.entries.restart.more
---@field when? "never"|"on-crash"|"always" when it is started again
---@field maxTimes? integer at most this many times (default 3)
---@field withinMillis? integer within this time (default 60000)

---@class caulis.Settings.reveal
---@field enabled? boolean dark until warm, then the desktop fades in (default false)
---@field fadeMillis? integer how long the fade takes (default 600)
---@field maxWaitMillis? integer the longest the screen stays dark (default 4000)
---@field color? string the colour until then (default black)
---@field warmFrames? integer frames drawn dark before it fades in (default 3)
---@field hideCursor? boolean the pointer hidden while dark (default true)

---@class caulis.Settings.startupSound
---@field enabled? boolean play it (default false)
---@field preset? "airy"|"classic"|"glass"|"soft" one of the sounds Caulis makes itself (default airy)
---@field file? string a sound file of yours instead (ogg, wav, flac: what the player plays)
---@field volume? number how loud, 0 to 1 (default 0.8)
---@field delayMillis? integer wait this long more before it plays
---@field at? "reveal"|"start" when the desktop fades in (default), or right at the start
---@field whenHeadless? boolean played in the headless backend too (default false)
---@field player? (string)[] your player's command, its words ({file}, {volume}, {paVolume}, {percent})
---@field players? (string)[] the players tried in order (default pw-play, paplay, aplay)
---@field playerTimeoutMillis? integer how long a player may play before it is stopped (default 30000)

---@class caulis.Window
---@field id integer the same while it is open
---@field appId string
---@field title string
---@field pid integer
---@field workspace integer 0: none (a scratchpad)
---@field monitor string its monitor's connector
---@field focused boolean
---@field floating boolean
---@field maximized boolean
---@field fullscreen boolean
---@field minimized boolean
---@field x11 boolean
---@field urgent boolean
---@field x integer
---@field y integer
---@field width integer
---@field height integer

---@class caulis.Monitor
---@field name string
---@field workspace integer
---@field scale number
---@field x integer
---@field y integer
---@field width integer
---@field height integer
---@field focused boolean
---@field primary boolean

---@class caulis.State
---@field windows caulis.Window[]
---@field monitors caulis.Monitor[]
---@field focused integer? the focused window's id
---@field workspace integer
---@field locked boolean

---@class caulis.Key
---@field unbind fun() takes the key away for good
---@field setEnabled fun(on: boolean) off: the key goes to programs until it is on again

---@class caulis.Handle
---@field cancel fun() stops it (a timer, a listener)
---@field remove fun() the same

---@class caulis.LayoutWindow
---@field appId string
---@field title string
---@field id integer?

---@class caulis.Rect
---@field x integer
---@field y integer
---@field width integer
---@field height integer

---@class caulis.BindOptions
---@field repeating? boolean repeats while held
---@field release? boolean on letting go
---@field locked? boolean also while the screen is locked
---@field description? string
---@field mode? string a key mode's own key

---@alias caulis.Event "start"|"reload"|"unload"|"window-opened"|"window-closed"|"window-changed"|"focus-changed"|"workspace-changed"|"monitor-added"|"monitor-removed"|"notice"|"lid"|"color-scheme"|"quit"

---@class caulis
caulis = {}

---What config.json can say.
---@param settings caulis.Settings
function caulis.settings(settings) end

---A rule for some windows.
---@param rule caulis.Settings.rules
function caulis.rule(rule) end

---A key: an action as text ("exec firefox") or a function.
---@param combination string
---@param action string|fun()
---@param options? caulis.BindOptions
---@return caulis.Key
function caulis.bind(combination, action, options) end

---Turns a key mode on (nil or "reset": the usual keys).
---@param name? string
function caulis.mode(name) end

---Runs fn when the event happens.
---@param event caulis.Event
---@param fn function
---@return caulis.Handle
function caulis.on(event, fn) end

---Runs an action ("workspace 2"), on the window with that id or the focused one.
---@param action string
---@param window? integer
function caulis.run(action, window) end

---@return caulis.State
function caulis.state() end

---@return caulis.Window[]
function caulis.windows() end

---@param id integer
---@return caulis.Window?
function caulis.window(id) end

---@return caulis.Window?
function caulis.focusedWindow() end

---@return caulis.Monitor[]
function caulis.monitors() end

---@return integer
function caulis.workspace() end

---What programs are told now: "dark", "light" or "default".
---@return "dark"|"light"|"default"
function caulis.colorScheme() end

---Runs fn once, millis from now.
---@param millis integer
---@param fn fun()
---@return caulis.Handle
function caulis.after(millis, fn) end

---Runs fn every millis (at least 10) until cancelled or reloaded.
---@param millis integer
---@param fn fun()
---@return caulis.Handle
function caulis.every(millis, fn) end

---A layout of your own (it becomes the layout): one rectangle per window.
---@param name string
---@param fn fun(windows: caulis.LayoutWindow[], area: caulis.Rect, focused: integer): caulis.Rect[]
function caulis.layout(name, fn) end

---@param text string
---@param level? "info"|"warning"|"error"
function caulis.notify(text, level) end

---@param text string
function caulis.log(text) end

---Starts a program (run by /bin/sh) inside the compositor, without waiting for it. At the
---top of the file: once, when the compositor starts. (os.execute does the same.)
---@param command string
function caulis.launch(command) end

---A file next to the configuration, as a full path.
---@param name string
---@return string
function caulis.file(name) end
