package dev.caulis.core

/**
 * The locked session: a locker program (ext-session-lock) or the compositor's own lock screen,
 * what may be shown and take input meanwhile, and where the focus goes after unlocking.
 */
internal class DesktopLock(private val desktop: Desktop) {

    // Locked by a locker program (ext-session-lock).
    internal var sessionLocked = false

    // Behind our own lock screen: whether [surface] (a panel's, a menu's, an unmanaged one's) is drawn.
    internal fun drawnBehindLock(surface: Surface): Boolean =
        desktop.panelsShowWhileLocked || ownLock == null || lockAllows(surface)

    // The locker in charge (it may have crashed).
    internal var lock: SessionLock? = null

    // Waiting for every screen to show the locked picture before telling the locker.
    internal var lockPending = false

    internal val lockShownOn = HashSet<DesktopView>()

    private val lockListeners = ArrayList<(Boolean) -> Unit>()

    // The current locker took over a session already locked (its locker crashed): giving up
    // before its lock is shown leaves the session locked, not unlocked.
    private var lockTakenOver = false

    fun onLockChanged(listener: (Boolean) -> Unit): AutoCloseable {
        lockListeners += listener
        return AutoCloseable { lockListeners -= listener }
    }

    internal fun lockSurfaces(): List<LockSurface> = lock?.surfaces?.filter { it.mapped } ?: emptyList()

    // Surfaces that may get input while locked: the current locker's (with their subsurfaces).
    private fun belongsToLocker(surface: Surface): Boolean {
        var current: Surface = surface
        while (true) current = current.subsurface?.parent ?: break
        val owner = current.roleObject as? LockSurface ?: return false
        return owner.lock === lock
    }

    // The compositor's own lock screen ([lockInternally]).
    internal class OwnLock(val screen: Widget, val hidesDesktop: Boolean, val surfaces: (Surface) -> Boolean, val addedForTheLock: Boolean)

    internal var ownLock: OwnLock? = null

    // Nothing of the desktop is drawn while locked: a locker program's lock, or our own with hideDesktop.
    internal val lockHidesDesktop: Boolean get() = sessionLocked || ownLock?.hidesDesktop == true

    // The lock's rule for programs' surfaces: while a locker program has locked only its
    // surfaces may have input; behind our own lock screen only those its author allows.
    internal fun lockAllows(surface: Surface): Boolean {
        if (sessionLocked) return belongsToLocker(surface)
        val own = ownLock ?: return true
        return userCode("Your lock screen (surfaces)", false) { own.surfaces(surface) }
    }

    // Our widgets usable while locked: our own lock screen (unless a locker program locked on
    // top of it), and the ones that say so (Widget.showsWhileLocked).
    internal fun widgetUsable(widget: Widget): Boolean {
        if (!desktop.locked) return true
        if (!sessionLocked && widget === ownLock?.screen) return true
        return userCode("Your widget (showsWhileLocked)", false) { widget.showsWhileLocked }
    }

    fun lockInternally(screen: Widget, hideDesktop: Boolean = false, allowSurface: (Surface) -> Boolean = { false }) {
        val wasLocked = desktop.locked
        val old = ownLock
        if (old != null && old.addedForTheLock && old.screen !== screen) desktop.widgetHost.widgets.remove(old.screen)
        // Shown for the time of the lock only if it was not one of the widgets already.
        val added = (old != null && old.addedForTheLock && old.screen === screen) || screen !in desktop.widgetHost.widgets
        if (screen !in desktop.widgetHost.widgets) desktop.widgetHost.widgets += screen
        if (!wasLocked) rememberFocusForUnlock()
        ownLock = OwnLock(screen, hideDesktop, allowSurface, added)
        desktop.lockLog.info { "Locked by the compositor's own lock screen ($screen)${if (hideDesktop) ", nothing of the desktop shown" else ""}${if (sessionLocked) " (behind a locker program)" else ""}" }
        nothingBehindTheLock()
        desktop.seat.lockAllows = ::lockAllows
        if (!sessionLocked) {
            desktop.keyboardWidget?.takeIf { it !== screen && !widgetUsable(it) }?.let { desktop.focusWidget(null) }
            desktop.focusWidget(screen)
        }
        desktop.updateWidgets()
        desktop.pointerMoved((System.nanoTime() / 1_000_000).toInt(), desktop.pointerX, desktop.pointerY)
        if (!wasLocked) notifyEach(lockListeners, true, "lock")
    }

    fun unlockInternally() {
        val own = ownLock ?: return
        ownLock = null
        if (own.addedForTheLock) desktop.widgetHost.widgets.remove(own.screen)
        if (desktop.keyboardWidget === own.screen) {
            desktop.keyboardWidget = null
            userCode("Your widget (keyboardFocus)", Unit) { own.screen.keyboardFocus(false) }
        }
        if (desktop.widgetHost.hoveredWidget === own.screen || desktop.widgetHost.pressedWidget?.widget === own.screen) desktop.widgetHost.leaveWidgets()
        if (sessionLocked) {
            desktop.lockLog.info("The compositor's own lock screen is gone; the locker program still locks the session")
            desktop.seat.lockAllows = ::lockAllows
            desktop.updateWidgets()
            return
        }
        desktop.lockLog.info("Unlocked (the compositor's own lock screen)")
        unlocked()
        desktop.updateWidgets()
    }

    // Nothing may go on behind a lock: no menus, no overview, no dragging, no overlay, our
    // widgets lose the pointer and the keyboard (unless they may be used while locked).
    private fun nothingBehindTheLock() {
        desktop.pads.hideSpecialsForLock()
        desktop.seat.cancelDrag()
        desktop.seat.forgetInput()
        desktop.popupHost.grabs.toList().asReversed().forEach { it.dismiss() }
        desktop.popupHost.grabs.clear()
        desktop.pointerGrab.end()
        // Fingers on a program's window: the program forgets them (else they went on under the lock).
        if (desktop.touch.touchingPrograms) desktop.touchCancel((System.nanoTime() / 1_000_000).toInt())
        if (desktop.inOverview) {
            desktop.inOverview = false
            desktop.overview.cameraFrom = DesktopOverview.Camera.NONE
            desktop.overview.cameraTo = DesktopOverview.Camera.NONE
        }
        desktop.overview.overviewTarget = null
        desktop.closeOverlay()
        desktop.widgetHost.leaveWidgets()
        desktop.keyboardWidget?.takeIf { !widgetUsable(it) }?.let { old ->
            desktop.keyboardWidget = null
            userCode("Your widget (keyboardFocus)", Unit) { old.keyboardFocus(false) }
        }
    }

    // Windows get a number as they open: those up to [mappedBeforeLock] were open before the
    // session was locked; [focusBeforeLock] had the focus then.
    internal var mapCount = 0L

    internal val mapNumbers = java.util.WeakHashMap<Window, Long>()

    private var mappedBeforeLock = Long.MAX_VALUE

    private var focusBeforeLock: java.lang.ref.WeakReference<Window>? = null

    // The session is getting locked (it was not): which windows were there, which had the focus.
    private fun rememberFocusForUnlock() {
        mappedBeforeLock = mapCount
        focusBeforeLock = desktop.focusedWindow?.let { java.lang.ref.WeakReference(it) }
    }

    // The window to focus after unlocking (see [unlockFocus]), or null.
    private fun windowForUnlock(): Window? {
        fun usable(window: Window) = window.mapped && (window in desktop.windows || desktop.scratchpadOf(window) != null) &&
            (mapNumbers[window] ?: Long.MAX_VALUE) <= mappedBeforeLock
        val newest = desktop.windows.filter(::usable).maxByOrNull { mapNumbers[it] ?: 0L }
        return when (desktop.unlockFocus) {
            UnlockFocus.FOCUSED_BEFORE -> focusBeforeLock?.get()?.takeIf(::usable) ?: newest
            UnlockFocus.NEWEST_BEFORE -> newest
        }
    }

    // The session is not locked any more: the window that had the focus before (see
    // [unlockFocus]; or the panel that keeps the keyboard) gets it back; windows that came or
    // went meanwhile did not take the focus.
    private fun unlocked() {
        desktop.seat.lockAllows = { true }
        val layer = desktop.layerHost.exclusiveLayer()
        val window = windowForUnlock()
        focusBeforeLock = null
        mappedBeforeLock = Long.MAX_VALUE
        if (layer != null) desktop.widgetHost.keyboardTo(layer.surface)
        else {
            desktop.lockLog.info { "Unlocked: the focus goes to ${window ?: "nothing"}" }
            desktop.focus(window)
        }
        desktop.changed()
        desktop.pointerMoved((System.nanoTime() / 1_000_000).toInt(), desktop.pointerX, desktop.pointerY)
        notifyEach(lockListeners, false, "unlock")
    }

    fun lockRequested(lock: SessionLock): Boolean {
        if (sessionLocked && this.lock?.alive == true) {
            desktop.lockLog.info { "${lock.resource.client.name} wants to lock, but another locker is in charge: refused" }
            return false
        }
        this.lock = lock
        val wasLocked = desktop.locked
        if (!wasLocked) rememberFocusForUnlock()
        lockTakenOver = sessionLocked
        sessionLocked = true
        nothingBehindTheLock()
        // (Our own lock screen, if up, gives way to the locker program's.)
        desktop.keyboardWidget?.takeIf { !widgetUsable(it) }?.let { old ->
            desktop.keyboardWidget = null
            userCode("Your widget (keyboardFocus)", Unit) { old.keyboardFocus(false) }
        }
        desktop.seat.lockAllows = ::lockAllows
        lockPending = true
        lockShownOn.clear()
        desktop.lockLog.info { "Locked by ${lock.resource.client.name}${if (lockTakenOver) " (taking over from a crashed locker)" else ""}: nothing of the desktop is shown now" }
        confirmLockIfShown()
        desktop.changed()
        if (!wasLocked) notifyEach(lockListeners, true, "lock")
        return true
    }

    // The locker hears "locked" once every screen shows the locked picture.
    internal fun confirmLockIfShown() {
        if (!lockPending || !desktop.views.all { it in lockShownOn }) return
        lockPending = false
        desktop.lockLog.info("Every screen shows the lock: telling the locker")
        lock?.confirmLocked()
    }

    fun lockSurfaceSize(output: Output?): Pair<Int, Int> =
        (output?.let(desktop::monitorOf) ?: desktop.monitorList.first()).area.let { it.width to it.height }

    fun lockSurfaceCommitted(surface: LockSurface) {
        if (surface.lock !== lock) return // an earlier locker's leftovers
        if (surface.mapped && desktop.seat.keyboardFocus == null && desktop.keyboardWidget == null) desktop.widgetHost.keyboardTo(surface.surface)
        desktop.changed()
        // The pointer may be over it now.
        val hit = desktop.hits.surfaceAt(desktop.pointerX, desktop.pointerY)
        desktop.seat.pointerMotion((System.nanoTime() / 1_000_000).toInt(), hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

    fun unlockRequested(lock: SessionLock) {
        if (lock !== this.lock) {
            desktop.lockLog.warn("An old locker (${lock.resource.client.name}) tried to unlock; ignored")
            return
        }
        if (!lock.confirmed && lockTakenOver) {
            // A new locker took over from one that crashed, then gave up before its lock was
            // on screen: the session was locked all along, and stays so (no password was asked).
            desktop.lockLog.warn("${lock.resource.client.name} took over the lock but gave up before it was shown: the session stays locked")
            lockPending = false
            desktop.changed()
            return
        }
        desktop.lockLog.info { "Unlocked by ${lock.resource.client.name}" }
        this.lock = null
        sessionLocked = false
        lockPending = false
        lockTakenOver = false
        val own = ownLock
        if (own != null) {
            // Our own lock screen was up below it: it is in charge again.
            desktop.lockLog.info("The compositor's own lock screen still locks the session")
            desktop.seat.lockAllows = ::lockAllows
            desktop.focusWidget(own.screen)
            desktop.changed()
            return
        }
        unlocked()
    }

    fun lockAbandoned(lock: SessionLock) {
        if (lock !== this.lock) return
        val own = ownLock
        if (own != null) {
            // A locker program crashed on top of our own lock screen: that one is in charge
            // again (it asks for the password itself), not a red screen without a way back.
            desktop.lockLog.warn("The screen locker quit without unlocking; the compositor's own lock screen is in charge again")
            this.lock = null
            sessionLocked = false
            lockPending = false
            lockTakenOver = false
            desktop.seat.lockAllows = ::lockAllows
            desktop.focusWidget(own.screen)
            desktop.updateWidgets()
            desktop.changed()
            return
        }
        // The locker crashed: the session stays locked (that is the point of a lock).
        lockPending = false
        desktop.lockLog.warn("The screen locker quit without unlocking; the session stays locked until a new locker is started")
        desktop.changed()
    }
}
