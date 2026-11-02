package com.cztr.routinealarm.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.cztr.routinealarm.ui.alarm.*
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.history.*
import com.cztr.routinealarm.ui.home.*
import com.cztr.routinealarm.ui.plan.*
import com.cztr.routinealarm.ui.shell.AppDestination
import com.cztr.routinealarm.ui.shell.AppShellView
import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.studio.*
import com.cztr.routinealarm.ui.system.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** First rendered baseline, not a golden comparison or a device/TalkBack acceptance. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class C3ReleaseVisualSmokeTest(private val surface: String, private val theme: String, private val mode: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun scenarios(): List<Array<Any>> = listOf("home", "plan", "studio", "history", "system", "alarm")
            .flatMap { surface -> listOf("light", "dark").flatMap { theme ->
                listOf("normal", "large").map { mode -> arrayOf<Any>(surface, theme, mode) }
            } }
    }

    class VisualHostActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(android.R.style.Theme_Material_NoActionBar)
            super.onCreate(savedInstanceState)
        }
    }

    @Test fun renderNativeImagesAndValidateFiniteCriticalControls() {
        val widthDp = if (mode == "large") 320 else 360
        val heightDp = if (mode == "large") 480 else 640
        val scale = if (mode == "large") 2f else 1f
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-mdpi")
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(VisualHostActivity::class.java).setup()
        val problems = mutableListOf<String>()
        val callbacks = mutableListOf<String>()
        val controls = JSONArray()
        val files = JSONArray()
        val scenario = "${surface}_${theme}_${widthDp}x${heightDp}_${(scale * 100).toInt()}"
        val output = File(System.getProperty("c3.visual.output")
            ?: File(System.getProperty("java.io.tmpdir"), "r52-c3-release-visual").path).apply { mkdirs() }
        try {
            val activity = controller.get()
            val colors = if (theme == "dark") R52Colors.Dark else R52Colors.Light
            val content = screen(activity, colors, callbacks)
            val root = if (surface == "alarm") content else {
                val padded = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(this, 20), dp(this, 24), dp(this, 20), dp(this, 24))
                    addView(content)
                }
                val destination = when (surface) {
                    "home" -> AppDestination.TODAY
                    "plan" -> AppDestination.PLAN
                    "studio" -> AppDestination.STUDIO
                    "history" -> AppDestination.HISTORY
                    else -> AppDestination.MORE
                }
                AppShellView(activity, destination, colors, padded) { callbacks += "navigation:$it" }
            }
            root.setBackgroundColor(colors.background)
            activity.setContentView(root)
            val decor = activity.window.decorView
            val width = dp(root, widthDp)
            val height = dp(root, heightDp)
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Screenshots require a real attached Android window", root.isAttachedToWindow)
            assertEquals(scale, activity.resources.configuration.fontScale, 0.01f)
            assertEquals(width, decor.width)
            assertEquals(height, decor.height)
            files.put(capture(decor, File(output, "${scenario}_top.png"), problems))

            val buttons = descendants(root).filterIsInstance<Button>()
            assertTrue("Representative fixture must expose actual controls", buttons.isNotEmpty())
            for (button in buttons) {
                reveal(button)
                val visible = Rect()
                val isVisible = button.getGlobalVisibleRect(visible)
                val density = button.resources.displayMetrics.density
                val itemProblems = mutableListOf<String>()
                if (button.isEnabled) {
                    if (!isVisible || visible.width() != button.width || visible.height() != button.height)
                        itemProblems += "not fully reachable after scroll"
                    if (button.width < 48 * density || button.height < 48 * density)
                        itemProblems += "touch target below48dp"
                    val layout = button.layout
                    if (layout == null) itemProblems += "missing actual text layout" else {
                        val usableWidth = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                        val usableHeight = button.height - button.compoundPaddingTop - button.compoundPaddingBottom
                        if (layout.height > usableHeight) itemProblems += "clipped label height"
                        if (layout.getLineEnd(layout.lineCount - 1) != button.text.length)
                            itemProblems += "missing label suffix"
                        for (line in 0 until layout.lineCount) {
                            if (layout.getEllipsisCount(line) != 0) itemProblems += "ellipsized label"
                            if (layout.getLineMax(line) > usableWidth + 1f) itemProblems += "clipped label width"
                        }
                    }
                }
                problems += itemProblems.map { "${button.text}: $it" }
                controls.put(JSONObject().put("label", button.text.toString()).put("enabled", button.isEnabled)
                    .put("widthDp", button.width / density).put("heightDp", button.height / density)
                    .put("fullyVisible", isVisible && visible.width() == button.width && visible.height() == button.height)
                    .put("problems", JSONArray(itemProblems)))
            }
            // Capture the actual page bottom after measured action reachability, not a stitched image.
            descendants(root).filterIsInstance<ScrollView>().forEach { scroll ->
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
            files.put(capture(decor, File(output, "${scenario}_bottom.png"), problems))
            if (callbacks.isNotEmpty()) problems += "Layout/scroll dispatched callbacks: $callbacks"
            val record = JSONObject().put("scenario", scenario).put("surface", surface).put("theme", theme)
                .put("mode", mode).put("widthDp", widthDp).put("heightDp", heightDp).put("fontScale", scale)
                .put("graphicsMode", "NATIVE").put("sdk", 31).put("windowAttached", root.isAttachedToWindow)
                .put("comparison", "FIRST_BASELINE_NO_GOLDEN_REFERENCE").put("fixture", "Existing Views and pure mappers; representative explicit immutable input")
                .put("callbacksDuringRenderAndScroll", JSONArray(callbacks)).put("controls", controls)
                .put("images", files).put("problems", JSONArray(problems))
            File(output, "$scenario.json").writeText(record.toString(2))
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun screen(activity: Activity, colors: R52Colors, callbacks: MutableList<String>): View {
        val now = ZonedDateTime.parse("2026-10-31T09:00:00+01:00[Europe/Berlin]")
        return when (surface) {
            "home" -> HomeScreenView(activity, HomeUiMapper.map(HomeUiInput(now, true, true,
                listOf(HomeProjectedItem("morning", "Morgenroutine", "Morgen", now.plusMinutes(15)),
                    HomeProjectedItem("water", "Ein Glas Wasser trinken", "Persönlich", now.plusHours(1)),
                    HomeProjectedItem("move", "Bewegung und frische Luft", "Training", now.plusHours(2))))), colors) {
                callbacks += "home:$it"
            }
            "plan" -> PlanScreenView(activity, PlanUiMapper.map(PlanUiInput(true, true, 2, DayOfWeek.SATURDAY,
                listOf(PlanProjectedItem("morning", "Morgenroutine", 555, setOf(DayOfWeek.SATURDAY), true, "Morgenroutine"),
                    PlanProjectedItem("water", "Ein Glas Wasser trinken", 600, setOf(DayOfWeek.SATURDAY), true, "Persönlich")),
                listOf(PlanProjectedItem("training", "Bewegungspause", 660, setOf(DayOfWeek.SATURDAY), true, "Training")), true)),
                colors, { callbacks += "plan:primary" }, { callbacks += "plan:day:$it" }, { callbacks += "plan:training" })
            "studio" -> StudioScreenView(activity, StudioUiMapper.map(StudioUiInput(listOf(
                StudioProjectedRoutine("morning", "Morgenroutine", "Bitte aufstehen und ein Glas Wasser trinken.",
                    555, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), true, "Morgenroutine", "Routine"),
                StudioProjectedRoutine("evening", "Abendroutine", "Den Tag ruhig abschließen.",
                    1200, setOf(DayOfWeek.SATURDAY), false, "Abendroutine", "Routine")), StudioFilter.ALL, "morning")),
                colors, { callbacks += "studio:filter:$it" }, { callbacks += "studio:routine:$it" })
            "history" -> HistoryScreenView(activity, HistoryUiMapper.map(HistoryUiInput.available(3, 1, 1,
                "Nächster gespeicherter Test: heute, 09:30 Uhr", false)), colors,
                { callbacks += "history:open" }, { callbacks += "history:retry" }, { callbacks += "history:system" })
            "system" -> SystemScreenView(activity, SystemUiMapper.map(SystemUiInput(true, false, false, false,
                false, "0.2.0-alpha14-test", "com.cztr.routinealarm.dev",
                if (theme == "dark") ThemeChoice.DARK else ThemeChoice.LIGHT)), colors) { callbacks += "system:$it" }
            "alarm" -> AlarmScreenView(activity, AlarmUiMapper.map(AlarmUiInput("Morgenroutine",
                "Bitte aufstehen und ein Glas Wasser trinken.", LocalDateTime.parse("2026-10-31T09:00:00"),
                true, null, true)), colors) { callbacks += "alarm:$it" }
            else -> error("Unsupported requested surface: $surface")
        }
    }

    private fun reveal(target: View) {
        var parent = target.parent
        while (parent is ViewGroup) {
            val position = Rect(0, 0, target.width, target.height)
            if (parent is HorizontalScrollView) {
                parent.offsetDescendantRectToMyCoords(target, position)
                parent.scrollTo(position.left, 0)
            } else if (parent is ScrollView) {
                parent.offsetDescendantRectToMyCoords(target, position)
                parent.scrollTo(0, position.top)
            }
            parent = parent.parent
        }
    }

    private fun capture(view: View, file: File, problems: MutableList<String>): JSONObject {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val samples = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5)
                samples += bitmap.getPixel(x, y)
            if (samples.size < 8) problems += "PNG has insufficient rendered color variation: ${file.name} (${samples.size})"
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            return JSONObject().put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
                .put("bytes", file.length()).put("sha256", sha).put("sampledColors", samples.size)
        } finally { bitmap.recycle() }
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
