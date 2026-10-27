package dev.caulis.wm

import dev.caulis.core.WindowRequest
import dev.caulis.render.Color
import dev.caulis.wm.config.ConfigFile
import dev.caulis.wm.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rule { } asking for a window's state and tags, and changing its looks and behaviour while it fits. */
class RuleStatesTest {

    private val harness = CaulisTest()

    @Test
    fun `looks follow the window's state - floating, focused`() = with(harness) {
        withCaulis({
            effects = dev.caulis.core.Effects.NONE
            layout = dev.caulis.tiling.Dwindle()
            rule { isFloating(); cornerRadius = 16; shadow = true; borderWidth = 3; borderColor(Color.hex("#ff0000")) }
            rule { isFocused(false); opacity = 0.8f }
        }) { caulis, client ->
            client.window { it.setAppId("a") }.draw()
            client.window { it.setAppId("b") }.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.size == 2 && r.desktop.focusedWindow?.appId == "b" } }
            caulis.onLoop { r ->
                val (a, b) = listOf("a", "b").map { id -> r.desktop.windows.single { it.appId == id } }
                assertEquals(0, r.desktop.windowEffects(a).cornerRadius, "tiled: not yet")
                assertEquals(0.8f, r.desktop.windowEffects(a).opacity, "without the keyboard")
                assertEquals(1f, r.desktop.windowEffects(b).opacity)
                r.desktop.setFloating(a, true)
                val fx = r.desktop.windowEffects(a)
                assertEquals(16, fx.cornerRadius, "floating now")
                assertTrue(fx.shadow != null)
                assertEquals(3, fx.border?.width)
                assertEquals(dev.caulis.render.Paint.solid(Color.hex("#ff0000")), fx.border?.focused)
            }
        }
    }

    @Test
    fun `tags from rules and by hand, unless and anyOf`() = with(harness) {
        withCaulis({
            rule { anyOf { appId("mpv"); appId("vlc") }; tags("video") }
            rule { unless { hasTag("video") }; tags("other") }
            rule { hasTag("video"); keepAwake = true; animated = false }
            rule { hasTag("starred"); cornerRadius = 30 }
        }) { caulis, client ->
            client.window { it.setAppId("vlc") }.draw()
            client.window { it.setAppId("editor") }.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.size == 2 } }
            caulis.onLoop { r ->
                val (vlc, editor) = listOf("vlc", "editor").map { id -> r.desktop.windows.single { it.appId == id } }
                assertEquals(setOf("video"), r.tagsOf(vlc))
                assertEquals(setOf("other"), r.tagsOf(editor))
                assertTrue(r.desktop.behaviorOf(vlc).keepsAwake)
                assertFalse(r.desktop.behaviorOf(vlc).animated)
                assertFalse(r.desktop.behaviorOf(editor).keepsAwake)
                assertTrue(r.idle.inhibited(), "a video on screen keeps it awake")

                r.config.run("tag +starred") // the focused one: the editor
                assertEquals(setOf("starred", "other"), r.tagsOf(editor))
                assertEquals(30, r.desktop.windowEffects(editor).cornerRadius)
                r.config.run("tag starred") // toggled off again
                assertEquals(setOf("other"), r.tagsOf(editor))
            }
        }
    }

    @Test
    fun `a pinned rule, and pin as an action`() = with(harness) {
        withCaulis({
            rule { title("Picture-in-Picture"); pinned = true; takesFocus = false; size(320, 180) }
        }) { caulis, client ->
            client.window { it.setAppId("editor") }.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "editor" }
            client.window { it.setTitle("Picture-in-Picture") }.draw(320, 180)
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.size } == 2 }
            caulis.onLoop { r ->
                val pip = r.desktop.windows.single { it.title == "Picture-in-Picture" }
                assertTrue(r.desktop.isPinned(pip))
                assertEquals("editor", r.desktop.focusedWindow?.appId, "it took no keyboard")
                r.desktop.switchTo(4)
                assertEquals(listOf(pip), r.desktop.windows)
                r.desktop.switchTo(1)
                r.config.run("pin") // the editor
                val editor = r.desktop.windows.single { it.appId == "editor" }
                assertTrue(r.desktop.isPinned(editor))
                r.config.run("pin off")
                assertFalse(r.desktop.isPinned(editor))
            }
        }
    }

    @Test
    fun `the same rules in config_json`() {
        val read = ConfigFile.read(
            """
            { "rules": [
              { "anyOf": [ { "appId": "mpv" }, { "appId": "vlc", "isFloating": true } ], "tags": ["video"], "keepAwake": true,
                "ignore": ["maximize", "activate"], "minSize": [320, 200], "hiddenFromCapture": true, "takesFocus": false },
              { "hasTag": "video", "unless": { "isXwayland": true }, "cornerRadius": 12, "border": false, "shadow": false, "blur": true,
                "inactiveOpacity": 0.7, "pinned": true, "animated": false, "borderColor": ["#ff0000", "#00ff00"] },
              { "onWorkspace": 2, "isFocused": true, "isPinned": false, "isMaximized": false, "isFullscreen": false,
                "initialAppId": "x", "initialTitle": "y", "borderWidth": 4, "maxSize": [800, 600] }
            ] }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        val (video, looks, third) = caulis.rules
        val usual = dev.caulis.core.WindowBehavior.USUAL
        video.behaviorOf(usual).let { b ->
            assertTrue(b.keepsAwake && b.hiddenFromCapture && !b.takesFocus)
            assertEquals(setOf(WindowRequest.MAXIMIZE, WindowRequest.ACTIVATE), b.ignored)
            assertEquals(320 to 200, b.minWidth to b.minHeight)
        }
        assertEquals(setOf("video"), video.tags)
        looks.effectsOf(dev.caulis.core.Effects.MODERN).let { fx ->
            assertEquals(12, fx.cornerRadius)
            assertNull(fx.border)
            assertNull(fx.shadow)
            assertTrue(fx.blur != null)
            assertEquals(0.7f, fx.inactiveOpacity)
        }
        assertEquals(true, looks.placement().pinned)
        assertEquals(4, third.effectsOf(dev.caulis.core.Effects.NONE).border?.width)
        assertEquals(800, third.behaviorOf(usual).maxWidth)
    }

    @Test
    fun `panels, dimming, blur tone and the screen shader in config_json`() {
        val read = ConfigFile.read(
            """
            {
              "effects": { "dimInactive": 0.2, "blur": { "size": 6, "tone": "hyprland", "vibrancy": 0.5 } },
              "panelRules": [
                { "namespace": "fuzzel", "dimAround": 0.4, "blur": true, "cornerRadius": 12 },
                { "namespacePattern": "^notif", "layer": "overlay", "hiddenFromCapture": true, "shadow": true, "opacity": 0.9 }
              ],
              "rules": [ { "appId": "mpv", "dimInactive": 0 } ],
              "screenShader": { "warm": 0.4 }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        assertEquals(0.2f, caulis.effects.dimInactive)
        val tone = caulis.effects.blur!!.tone
        assertEquals(dev.caulis.render.BlurTone.HYPRLAND.contrast, tone.contrast)
        assertEquals(0.5f, tone.vibrancy)
        val (launcher, notifications) = caulis.panelRules
        assertEquals(0.4f, launcher.behaviorOf(dev.caulis.core.LayerBehavior.USUAL).dimAround)
        assertEquals(12, launcher.effectsOf(dev.caulis.core.Effects.NONE).cornerRadius)
        assertTrue(notifications.behaviorOf(dev.caulis.core.LayerBehavior.USUAL).hiddenFromCapture)
        assertEquals(0f, caulis.rules.single().effectsOf(caulis.effects).dimInactive)
        assertEquals("warm 0.4", caulis.screenShader?.name)
    }

    @Test
    fun `named curves and animations with their own time and curve in config_json`() {
        val read = ConfigFile.read(
            """
            { "animations": {
                "curves": { "snappy": { "bezier": [0.05, 0.9, 0.1, 1.05] }, "bouncy": { "spring": { "stiffness": 300, "damping": 12 } } },
                "curve": "snappy",
                "windowOpen": { "style": "pop", "curve": "bouncy" },
                "windowClose": { "style": "fade", "millis": 120 },
                "workspaceSwitch": { "style": "slide-vertical", "millis": 300, "curve": "ease-in-out" },
                "windowMove": "move"
            } }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        val a = caulis.animations
        assertTrue(a.curve.at(0.5) > 0.9, "the snappy curve is the usual one")
        assertTrue(a.windowOpen.curve!!.naturalMillis != null, "the spring")
        assertEquals(null, a.windowOpen.millis, "as long as the spring needs")
        assertEquals(120L, a.windowClose.millis)
        assertEquals(300L, a.workspaceSwitch.millis)
        assertEquals(dev.caulis.core.Curve.EASE_IN_OUT, a.workspaceSwitch.curve)
    }

    @Test
    fun `animations by name in config_json, Hyprland-like, off by default`() {
        assertEquals(false, Caulis().animations.enabled, "off unless turned on")
        val read = ConfigFile.read(
            """
            { "animations": {
                "enabled": true,
                "curves": { "snappy": { "bezier": [0.05, 0.9, 0.1, 1.05] } },
                "windows": { "millis": 300, "curve": "snappy" },
                "windowsIn": "popin 80%",
                "minimize": "genie",
                "fade": false,
                "workspaces": { "speed": 3, "style": "slidevert" },
                "border": { "enabled": true, "style": "wobble" }
            } }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        val s = caulis.animations.settings(caulis.animationMillis)
        assertEquals(true, caulis.animations.enabled)
        assertEquals(300L, s.resolve("windowsMove").millis)
        assertTrue(s.resolve("windowsOut").curve.at(0.5) > 0.9, "the snappy curve, from windows")
        assertEquals("popin 80%", s.resolve("windowsIn").style)
        assertEquals("genie", s.resolve("minimize").style)
        assertEquals(false, s.resolve("fadeOsd").on, "off with fade")
        assertEquals(300L, s.resolve("workspaces").millis)
        assertEquals("slidevert", s.resolve("workspaces").style)
        assertEquals(null, s.resolve("border").style, "a look it cannot take is left out (with a warning)")
    }
}
