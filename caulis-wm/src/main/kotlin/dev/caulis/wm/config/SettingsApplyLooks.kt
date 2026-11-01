package dev.caulis.wm.config

import dev.caulis.core.Background
import dev.caulis.core.Blur
import dev.caulis.core.Border
import dev.caulis.core.Effects
import dev.caulis.core.NoticePlacement
import dev.caulis.core.NoticeStyle
import dev.caulis.core.Paints
import dev.caulis.core.Shadow
import dev.caulis.core.TitleBars
import dev.caulis.core.FixedMovingParts
import dev.caulis.core.MovingArea
import dev.caulis.core.WallpaperShader
import dev.caulis.core.WindowAnimation
import dev.caulis.render.Color
import dev.caulis.render.Paint
import dev.caulis.wm.Caulis

/**
 * Settings for how things look, made into what they say: rules, effects, paints, backgrounds, notices.
 */
internal object SettingsApplyLooks {

    internal fun windowAnimation(name: String): WindowAnimation = when (name) {
        "pop" -> WindowAnimation.pop()
        "fade" -> WindowAnimation.fade()
        "scale" -> WindowAnimation.scale()
        "slide-up" -> WindowAnimation.fade() + WindowAnimation.slide()
        "genie" -> WindowAnimation.genie()
        "fly" -> WindowAnimation.flyTo()
        else -> WindowAnimation.NONE
    }

    // Which windows: the fields of [matchFields].
    private fun dev.caulis.wm.WindowRule.match(r: Map<String, Any?>) {
        (r["appId"] as String?)?.let { appId(it) }
        (r["appIdPattern"] as String?)?.let { appId(Regex(it)) }
        (r["title"] as String?)?.let { title(it) }
        (r["titlePattern"] as String?)?.let { title(Regex(it)) }
        (r["program"] as String?)?.let { program(it) }
        (r["initialAppId"] as String?)?.let { initialAppId(it) }
        (r["initialTitle"] as String?)?.let { initialTitle(it) }
        (r["hasTag"] as String?)?.let { hasTag(it) }
        (r["isFloating"] as Boolean?)?.let { isFloating(it) }
        (r["isFullscreen"] as Boolean?)?.let { isFullscreen(it) }
        (r["isMaximized"] as Boolean?)?.let { isMaximized(it) }
        (r["isFocused"] as Boolean?)?.let { isFocused(it) }
        (r["isPinned"] as Boolean?)?.let { isPinned(it) }
        (r["isXwayland"] as Boolean?)?.let { isXwayland(it) }
        (r["onWorkspace"] as Int?)?.let { onWorkspace(it) }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun rule(caulis: Caulis, r: Map<String, Any?>): dev.caulis.wm.WindowRule {
        caulis.rule {
            match(r)
            (r["unless"] as Map<String, Any?>?)?.let { inside -> unless { match(inside) } }
            (r["anyOf"] as List<Map<String, Any?>>?)?.let { all -> anyOf { all.forEach { inside -> allOf { match(inside) } } } }
            (r["workspace"] as Int?)?.let { workspace = it }
            (r["specialWorkspace"] as String?)?.let { specialWorkspace = it }
            (r["monitor"] as String?)?.let { monitor = it }
            (r["goToWorkspace"] as Boolean?)?.let { goToWorkspace = it }
            (r["floating"] as Boolean?)?.let { floating = it }
            (r["size"] as List<Int>?)?.let { size(it[0], it[1]) }
            (r["position"] as List<Int>?)?.let { position(it[0], it[1]) }
            if (r["center"] == true) center()
            (r["maximized"] as Boolean?)?.let { maximized = it }
            (r["fullscreen"] as Boolean?)?.let { fullscreen = it }
            (r["focus"] as Boolean?)?.let { focus = it }
            (r["titleBars"] as String?)?.let { titleBars = TitleBars.valueOf(it.uppercase()) }
            (r["opacity"] as Double?)?.let { opacity = it.toFloat() }
            when (val m = r["margin"]) {
                is Int -> margin(m)
                is List<*> -> (m as List<Int>).let { margin(it[0], it[1], it[2], it[3]) }
            }
            (r["pinned"] as Boolean?)?.let { pinned = it }
            (r["inactiveOpacity"] as Double?)?.let { inactiveOpacity = it.toFloat() }
            (r["cornerRadius"] as Int?)?.let { cornerRadius = it }
            (r["border"] as Boolean?)?.let { border = it }
            (r["borderWidth"] as Int?)?.let { borderWidth = it }
            when (val c = r["borderColor"]) {
                is Color -> borderColor(c)
                is List<*> -> borderColor(c[0] as Color, c[1] as Color)
            }
            (r["borderPaint"] as Map<String, Any?>?)?.let { p ->
                val focused = p["focused"]?.let { paint(caulis, it) } ?: Border().focused
                borderPaint(focused, p["unfocused"]?.let { paint(caulis, it) } ?: focused, p["urgent"]?.let { paint(caulis, it) })
            }
            (r["shadow"] as Boolean?)?.let { shadow = it }
            (r["blur"] as Boolean?)?.let { blur = it }
            (r["dimInactive"] as Double?)?.let { dimInactive = it.toFloat() }
            (r["minSize"] as List<Int>?)?.let { minSize(it[0], it[1]) }
            (r["maxSize"] as List<Int>?)?.let { maxSize(it[0], it[1]) }
            (r["keepAwake"] as Boolean?)?.let { keepAwake = it }
            (r["animated"] as Boolean?)?.let { animated = it }
            (r["hiddenFromCapture"] as Boolean?)?.let { hiddenFromCapture = it }
            (r["takesFocus"] as Boolean?)?.let { takesFocus = it }
            (r["ignore"] as List<String>?)?.let { list -> ignore(*list.map { dev.caulis.core.WindowRequest.valueOf(it.uppercase()) }.toTypedArray()) }
            (r["tags"] as List<String>?)?.let { tags(*it.toTypedArray()) }
        }
        return caulis.rules.last()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun effects(caulis: Caulis, e: Map<String, Any?>): Effects {
        var fx = when (e["preset"] as String?) {
            "modern" -> Effects.MODERN
            "none" -> Effects.NONE
            else -> caulis.effects
        }
        (e["cornerRadius"] as Int?)?.let { fx = fx.copy(cornerRadius = it) }
        when (val b = e["border"]) {
            OffOrType.Off -> fx = fx.copy(border = null)
            is Map<*, *> -> {
                val base = fx.border ?: Border()
                fx = fx.copy(
                    border = Border(
                        width = b["width"] as Int? ?: base.width,
                        focused = b["focused"]?.let { paint(caulis, it) } ?: base.focused,
                        unfocused = b["unfocused"]?.let { paint(caulis, it) } ?: base.unfocused,
                        urgent = b["urgent"]?.let { paint(caulis, it) } ?: base.urgent,
                        groupedFocused = b["groupedFocused"]?.let { paint(caulis, it) } ?: base.groupedFocused,
                        groupedUnfocused = b["groupedUnfocused"]?.let { paint(caulis, it) } ?: base.groupedUnfocused,
                        animate = b["animate"] as Boolean? ?: base.animate,
                    ),
                )
            }
        }
        fun shadowOf(s: Any?, base: Shadow?): Shadow? = when (s) {
            OffOrType.Off -> null
            is Map<*, *> -> {
                val from = base ?: Shadow()
                Shadow(
                    softness = s["softness"] as Int? ?: from.softness,
                    offsetX = s["offsetX"] as Int? ?: from.offsetX,
                    offsetY = s["offsetY"] as Int? ?: from.offsetY,
                    color = s["color"]?.let { paint(caulis, it) } ?: from.color,
                    spread = s["spread"] as Int? ?: from.spread,
                )
            }
            else -> base
        }
        if ("shadow" in e) fx = fx.copy(shadow = shadowOf(e["shadow"], fx.shadow))
        if ("inactiveShadow" in e) fx = fx.copy(inactiveShadow = shadowOf(e["inactiveShadow"], fx.inactiveShadow) ?: Shadow.NONE)
        when (val b = e["blur"]) {
            OffOrType.Off -> fx = fx.copy(blur = null)
            is Map<*, *> -> fx = fx.copy(blur = blurOf(b))
        }
        (e["dimInactive"] as Double?)?.let { fx = fx.copy(dimInactive = it.toFloat()) }
        (e["opacity"] as Double?)?.let { fx = fx.copy(opacity = it.toFloat(), inactiveOpacity = (e["inactiveOpacity"] as Double?)?.toFloat() ?: it.toFloat()) }
        (e["inactiveOpacity"] as Double?)?.let { fx = fx.copy(inactiveOpacity = it.toFloat()) }
        (e["focusChangeMillis"] as Int?)?.let { fx = fx.copy(focusChangeMillis = it.toLong()) }
        return fx
    }

    // Blur from its section.
    internal fun blurOf(b: Map<*, *>): Blur {
        val base = if (b["tone"] == "hyprland") dev.caulis.render.BlurTone.HYPRLAND else dev.caulis.render.BlurTone.NONE
        val tone = dev.caulis.render.BlurTone(
            noise = (b["noise"] as Double?)?.toFloat() ?: base.noise,
            contrast = (b["contrast"] as Double?)?.toFloat() ?: base.contrast,
            brightness = (b["brightness"] as Double?)?.toFloat() ?: base.brightness,
            vibrancy = (b["vibrancy"] as Double?)?.toFloat() ?: base.vibrancy,
        )
        return Blur(b["size"] as Int? ?: 4, b["passes"] as Int? ?: 2, tone)
    }

    @Suppress("UNCHECKED_CAST")
    private fun paint(caulis: Caulis, value: Any): Paint = when (value) {
        is Paint -> value
        is Color -> Paint.solid(value)
        is Map<*, *> -> {
            val degrees = value["degrees"] as Double?
            val turns = value["turnsPerSecond"] as Double? ?: 0.0
            when {
                value["gradient"] != null -> Paint.Gradient(value["gradient"] as List<Color>, degrees ?: 45.0, conic = false, turnsPerSecond = turns)
                value["conic"] != null -> Paint.Gradient(value["conic"] as List<Color>, degrees ?: 0.0, conic = true, turnsPerSecond = turns)
                value["rainbow"] != null -> Paint.rainbow(value["rainbow"] as Double)
                value["shader"] != null -> Paints.file(caulis.file(value["shader"] as String))
                else -> Paint.solid(Color(1f, 1f, 1f))
            }
        }
        else -> Paint.solid(Color(1f, 1f, 1f))
    }

    // seed = "random": one for this run (a configuration read again keeps the picture).
    private val randomSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, Int.MAX_VALUE.toLong())

    @Suppress("UNCHECKED_CAST")
    internal fun background(caulis: Caulis, b: Map<String, Any?>): Background? {
        (b["shader"] as String?)?.let { name ->
            val fps = b["framesPerSecond"] as Int? ?: 30
            val shader = if (name == "aurora") WallpaperShader.AURORA else WallpaperShader.file(caulis.file(name), (b["image"] as String?)?.let(caulis::file), fps)
            return Background.shader(
                shader.with(
                    colors = b["colors"] as List<Color>? ?: emptyList(),
                    values = (b["values"] as Map<String, Double>?)?.mapValues { it.value.toFloat() } ?: emptyMap(),
                    fallback = b["fallback"] as Color?,
                    framesPerSecond = fps,
                    resolution = (b["resolution"] as Double?)?.toFloat() ?: 1f,
                    movingArea = (b["movingArea"] as List<Double>?)?.let { a ->
                        val burst = b["movingBurst"] as List<Double>?
                        MovingArea(a[0], a[1], a[2], a[3], b["movingRestFps"] as Int? ?: 0, burst?.get(0) ?: 0.0, burst?.get(1) ?: 0.0, (b["movingQuietArea"] as List<Double>?)?.let { q -> MovingArea(q[0], q[1], q[2], q[3]) }, b["movingKeptSeconds"] as Double? ?: 600.0, b["movingSettleSeconds"] as Double? ?: 0.3, b["movingMaxKeptSize"] as Double? ?: 2.0)
                    },
                    seed = when (val seed = b["seed"]) {
                        "random" -> randomSeed
                        is Int -> seed.toLong()
                        else -> 0L
                    },
                    movingParts = (b["movingParts"] as List<List<Double>>?)?.let { areas ->
                        FixedMovingParts(areas, b["movingPartsFps"] as Int? ?: 10, b["movingPartsEvery"] as Int? ?: 1, b["movingKeptSeconds"] as Double? ?: 600.0, b["movingSettleSeconds"] as Double? ?: 0.3, b["movingMaxKeptSize"] as Double? ?: 2.0)
                    },
                ),
            )
        }
        (b["image"] as String?)?.let { name ->
            val fit = (b["fit"] as String?)?.let { Background.Fit.valueOf(it.uppercase()) } ?: Background.Fit.FILL
            return Background.image(caulis.file(name), fit, b["behind"] as Color? ?: Color(0f, 0f, 0f), b["span"] as Boolean? ?: false)
        }
        (b["gradient"] as List<Color>?)?.let { return Background.gradient(it[0], it[1], b["degrees"] as Double? ?: 90.0) }
        (b["color"] as Color?)?.let { return Background.color(it) }
        return null
    }

    internal fun notices(base: NoticeStyle, n: Map<String, Any?>): NoticeStyle {
        val placement = when (n["placement"] as String?) {
            "banner" -> NoticePlacement.Banner(
                bottom = n["bottom"] as Boolean? ?: false,
                room = (n["room"] as String?)?.let { NoticePlacement.Banner.Room.valueOf(it.uppercase().replace('-', '_')) } ?: NoticePlacement.Banner.Room.PUSH_ALL,
                errorRoom = (n["errorRoom"] as String?)?.let { NoticePlacement.Banner.Room.valueOf(it.uppercase().replace('-', '_')) },
            )
            "toast" -> NoticePlacement.Toast((n["corner"] as String?)?.let { NoticePlacement.Toast.Corner.valueOf(it.uppercase().replace('-', '_')) } ?: NoticePlacement.Toast.Corner.BOTTOM_RIGHT)
            "hidden" -> NoticePlacement.Hidden
            else -> base.placement
        }
        return base.copy(
            placement = placement,
            fontSize = n["fontSize"] as Int? ?: base.fontSize,
            everyMonitor = n["everyMonitor"] as Boolean? ?: base.everyMonitor,
            infoSeconds = n["infoSeconds"] as Double? ?: base.infoSeconds,
            warningSeconds = n["warningSeconds"] as Double? ?: base.warningSeconds,
            errorSeconds = n["errorSeconds"] as Double? ?: base.errorSeconds,
        )
    }
}
