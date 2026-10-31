package dev.caulis.wm

import dev.caulis.log.Logger

/**
 * Window rules at work: the facts about a window, its tags, what was applied to it.
 */
internal class RunningRules(private val running: Running) {

    // Which rules (by what they say) each window got its placement from already: a rule is
    // applied once, when the window opens or first fits it.
    internal val rulesApplied = java.util.WeakHashMap<dev.caulis.core.Window, MutableSet<String>>()

    // The margins each window was arranged with last (a rule may change them with its title).
    internal val shownMargins = java.util.WeakHashMap<dev.caulis.core.Window, dev.caulis.core.Margins?>()

    // A window's margins: the last rule that fits and says.
    internal fun marginsOf(window: dev.caulis.core.Window): dev.caulis.core.Margins? =
        running.config.rules.lastOrNull { it.margins != null && fits(it, window) }?.margins

    // The title bars each window was last told (a rule may change them with its title).
    internal val shownTitleBars = java.util.WeakHashMap<dev.caulis.core.Window, dev.caulis.core.TitleBars?>()

    internal val rulesLog = Logger("windows")

    internal fun fits(rule: WindowRule, window: dev.caulis.core.Window): Boolean = running.safely("$rule", false) { rule.fits(window, factsOf(window)) }

    // Tags given by hand (the action `tag`), and each window's app id and title when it opened.
    private val manualTags = java.util.WeakHashMap<dev.caulis.core.Window, MutableSet<String>>()

    private val initials = java.util.WeakHashMap<dev.caulis.core.Window, Pair<String, String>>()

    // What rules may ask about [window]: its tags are those given by hand, then those of each
    // rule in turn that fits it (a rule sees the tags of the ones before).
    internal fun factsOf(window: dev.caulis.core.Window, rules: List<WindowRule> = running.config.rules): WindowRule.Facts {
        val (appId, title) = initials.getOrPut(window) { window.appId to window.title }
        val tags = LinkedHashSet(manualTags[window].orEmpty())
        for (rule in rules) {
            if (rule.tags.isEmpty() || rule.tags.all { it in tags }) continue
            if (running.safely("$rule", false) { rule.fits(window, WindowRule.Facts(running.desktop, tags, appId, title)) }) tags += rule.tags
        }
        return WindowRule.Facts(running.desktop, tags, appId, title)
    }

    /** [window]'s tags now: given by hand and by the rules that fit it. */
    fun tagsOf(window: dev.caulis.core.Window): Set<String> = factsOf(window).tags

    /** Gives [window] the tag [name] (true), takes it away (false), or toggles it (null). */
    fun tag(window: dev.caulis.core.Window, name: String, on: Boolean? = null) {
        val own = manualTags.getOrPut(window) { LinkedHashSet() }
        val give = on ?: (name !in own)
        if (give) own += name else own -= name
        rulesLog.info { "$window: tag $name ${if (give) "given" else "taken away"}" }
        rulesChanged(window)
    }

    // A window's tags or state changed: its rules' margins and title bar may be others now.
    internal fun rulesChanged(window: dev.caulis.core.Window) {
        val margins = marginsOf(window)
        if (shownMargins.put(window, margins) != margins) running.desktop.marginsChanged(window)
        val bars = titleBarsOf(window)
        if (shownTitleBars.put(window, bars) != bars) running.desktop.titleBarsChanged()
        running.desktop.requestRedraw()
    }

    // Who draws a window's title bar: the last rule that fits and says, then titleBarsFor.
    internal fun titleBarsOf(window: dev.caulis.core.Window): dev.caulis.core.TitleBars? =
        running.config.rules.lastOrNull { it.titleBars != null && fits(it, window) }?.titleBars
            ?: running.config.titleBarRules.firstNotNullOfOrNull { rule -> running.safely("titleBarsFor", null) { rule(window) } }

    /** Rules changed (one added while running): who draws title bars, and each window's margins and bars. */
    fun rulesChanged() {
        running.desktop.titleBarsFor = if (running.config.titleBarRules.isEmpty() && running.config.rules.none { it.titleBars != null }) null else ::titleBarsOf
        for (window in running.desktop.allWindows) rulesChanged(window)
    }
}
