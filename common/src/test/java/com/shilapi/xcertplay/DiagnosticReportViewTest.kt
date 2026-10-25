package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class DiagnosticReportViewTest {
    private var activity: DiPlayActivity? = null

    @After fun tearDown() { activity?.finish() }

    // Same shape as the exported report: a header, then 8 logs capped at SessionLogFile.MAX_BYTES each.
    private fun maxSizeReport(): String = buildString {
        appendLine("DiPlay 1.0 · private beta diagnostic report")
        for (name in SessionLogFile.REPORT_NAMES) {
            appendLine("--- $name ---")
            var bytes = 0L
            var index = 0
            while (bytes < SessionLogFile.MAX_BYTES) {
                val line = "12:00:00.000  $name line=${index++} Video: video stats rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000"
                appendLine(line)
                bytes += line.length + 1
            }
        }
    }

    private fun showReport(report: String): Pair<String, Long> {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        val started = System.nanoTime()
        DiPlayActivity::class.java.getDeclaredMethod("showDiagnosticReport", String::class.java)
            .apply { isAccessible = true }.invoke(screen, report)
        val millis = (System.nanoTime() - started) / 1_000_000
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val views = generateSequence(listOf(dialog.window!!.decorView)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }
                .takeIf { it.isNotEmpty() }
        }.flatten()
        val reportView = views.filterIsInstance<TextView>().single { it.isTextSelectable }
        return reportView.text.toString() to millis
    }

    @Test fun viewShowsOnlyTheNewestPartOfALargeReport() {
        val report = maxSizeReport()
        val (shown, millis) = showReport(report)
        println("REPORT_VIEW reportChars=${report.length} viewChars=${shown.length} showMs=$millis")
        assertTrue("view holds ${shown.length} of ${report.length} chars", shown.length <= 250_000)
        assertTrue(shown.endsWith(report.takeLast(1_000)))
    }

    @Test fun smallReportIsShownInFull() {
        val report = "DiPlay 1.0\n--- diplay.log ---\n12:00:00.000  CarPlay connected\n"
        assertEquals(report, showReport(report).first)
    }
}
