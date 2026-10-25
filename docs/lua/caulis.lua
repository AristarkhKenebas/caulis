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
---@field input? caulis.Settings.input mice, touchpads, focus
---@field idle? (caulis.Settings.idle)[] what happens when nobody uses the computer
---@field focusOnActivate? boolean a window asking for the focus with a good token gets it (false: only marked urgent)
---@field fullscreenKeepsAwake? boolean a fullscreen window keeps idle timers off
---@field wakeScreensOnInput? boolean input switches dark screens on again
---@field lid? caulis.Settings.lid the laptop's lid
---@field cursor? caulis.Settings.cursor the cursor
---@field terminal? string the terminal the terminal key opens
---@field lockCommand? string the screen locker
---@field titleBars? "program"|"compositor"|"none" who draws title bars
---@field keys? caulis.Settings.keys the usual keys, changed or turned off
---@field bind? table<string, string|{ action: string, repeat?: boolean, release?: boolean, locked?: boolean, description?: string }> your own keys: combination → action ("exec firefox", "workspace 3", ...) or { action, repeat, release, locked, description }
---@field modes? table<string, table<string, string|{ action: string, repeat?: boolean, release?: boolean, locked?: boolean, description?: string }>> key modes ("resize"): their own keys, on with the action "mode resize", off with "mode reset"
---@field rules? (caulis.Settings.rules)[] rules for some windows
---@field panelRules? (caulis.Settings.panelRules)[] rules for some panels (bars, launchers, notifications)
---@field screenShader? caulis.Settings.screenShader a shader the whole screen goes through last
---@field effects? caulis.Settings.effects round corners, borders, shadows, blur
---@field animations? caulis.Settings.animations animations
---@field background? caulis.Settings.background what shows behind everything
---@field notices? caulis.Settings.notices the compositor's own messages
---@field gestures? caulis.Settings.gestures touchpad gestures
---@field overviewFill? number how much of the monitor the overview's windows take
---@field resizeBorder? integer the edge to grab floating windows by
---@field doubleClickMillis? integer a double click on a title bar
---@field hideCursorWhileTyping? boolean hide the cursor while typing
---@field hideCursorAfterSeconds? integer hide the cursor when the mouse is still (0: never)
---@field lockColor? string behind the screen locker
---@field startup? (string)[] programs started with the compositor
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

---@class caulis.Settings.keyboard
---@field layouts? string as for setxkbmap: "us,ru"
---@field variants? string one per layout: ",phonetic"
---@field options? string e.g. "grp:alt_shift_toggle"
---@field model? string e.g. "pc105"
---@field repeatRate? integer held keys repeat this many times a second (0: never)
---@field repeatDelay? integer milliseconds before a held key repeats

---@class caulis.Settings.input
---@field focusFollowsMouse? boolean the window under the pointer gets the keyboard
---@field pointers? caulis.Settings.input.pointers every mouse, trackball and touchpad
---@field touchpads? caulis.Settings.input.touchpads touchpads (on top of pointers)
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
---@field leftHanded? boolean buttons swapped
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
---@field leftHanded? boolean buttons swapped
---@field middleEmulation? boolean left and right together are a middle click
---@field tap? boolean touchpads: a tap is a click
---@field tapButtons? "left-right-middle"|"left-middle-right" touchpads: what two and three fingers tap
---@field tapDrag? boolean touchpads: tap, touch again and move to drag
---@field dragLock? boolean touchpads: a drag goes on when the finger lifts for a moment
---@field disableWhileTyping? boolean touchpads: off while typing
---@field clickMethod? "areas"|"fingers" touchpads: a click by the area pressed or by the number of fingers

---@class caulis.Settings.input.devices
---@field enabled? boolean false: not used at all
---@field offWithMouse? boolean touchpads: off while a mouse is plugged in
---@field speed? number pointer speed, -1 to 1 (0: the device's normal)
---@field accelProfile? "flat"|"adaptive" flat: as fast as the hand; adaptive: faster when moved fast
---@field naturalScroll? boolean content follows the fingers
---@field scrollFactor? number scrolling goes this many times as far
---@field scrollMethod? "none"|"two-finger"|"edge"|"button" how it scrolls
---@field scrollButton? string with scrollMethod button: the button ("MouseMiddle")
---@field leftHanded? boolean buttons swapped
---@field middleEmulation? boolean left and right together are a middle click
---@field tap? boolean touchpads: a tap is a click
---@field tapButtons? "left-right-middle"|"left-middle-right" touchpads: what two and three fingers tap
---@field tapDrag? boolean touchpads: tap, touch again and move to drag
---@field dragLock? boolean touchpads: a drag goes on when the finger lifts for a moment
---@field disableWhileTyping? boolean touchpads: off while typing
---@field clickMethod? "areas"|"fingers" touchpads: a click by the area pressed or by the number of fingers

---@class caulis.Settings.idle
---@field after? integer seconds without input
---@field action? string what happens then ("dpms off", "lock", "exec ...")
---@field resume? string what happens when someone is back

---@class caulis.Settings.lid
---@field close? string when the laptop's lid closes
---@field open? string when it opens

---@class caulis.Settings.cursor
---@field theme? string an installed cursor theme
---@field size? integer its size

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
---@field windowDrag? false|string the windowDrag key (false: off)

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
---@field focused? string|caulis.Settings.effects.border.focused.more with the focus
---@field unfocused? string|caulis.Settings.effects.border.unfocused.more without

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

---@class caulis.Settings.effects.shadow
---@field softness? integer how far it fades out
---@field offsetX? integer moved right
---@field offsetY? integer moved down
---@field color? string|caulis.Settings.effects.shadow.color.more its colour or paint (a coloured one is a glow)
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
---@field color? string|caulis.Settings.effects.inactiveShadow.color.more its colour or paint (a coloured one is a glow)
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
---@field millis? integer how long animations take (0: none at all)
---@field windowOpen? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.windowOpen.more a window appearing
---@field windowClose? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.windowClose.more a window going away
---@field minimize? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.minimize.more a window going into the dock
---@field restore? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none"|caulis.Settings.animations.restore.more a window coming back out of the dock
---@field windowMove? "move"|"none"|caulis.Settings.animations.windowMove.more windows gliding to new places
---@field workspaceSwitch? "slide"|"slide-vertical"|"fade"|"none"|caulis.Settings.animations.workspaceSwitch.more another workspace shown
---@field curve? string how animations move
---@field curves? table<string, caulis.Settings.animations.curves> your own curves by name, for curve fields
---@field overviewMillis? integer the overview zooming
---@field scaleChangeMillis? integer the picture melting when the scale changes

---@class caulis.Settings.animations.windowOpen.more
---@field style? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.windowClose.more
---@field style? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.minimize.more
---@field style? "pop"|"fade"|"scale"|"slide-up"|"genie"|"fly"|"none" how it looks
---@field millis? integer how long it takes (else animations.millis, or its spring's own time)
---@field curve? string how it moves

---@class caulis.Settings.animations.restore.more
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

---@alias caulis.Event "start"|"reload"|"unload"|"window-opened"|"window-closed"|"window-changed"|"focus-changed"|"workspace-changed"|"monitor-added"|"monitor-removed"|"notice"|"lid"

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

---A file next to the configuration, as a full path.
---@param name string
---@return string
function caulis.file(name) end
