package app.finn.deepsystem.background

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static guards: the only network code path is ModelDownloader; no telemetry SDKs. */
class PrivacyNoNetworkTest {
    private fun srcRoot(): File =
        listOf("src/main", "app/src/main").map { File(it) }.first { it.exists() }

    private val netPatterns = Regex(
        "java\\.net\\.(URL|HttpURLConnection|Socket)|HttpURLConnection|okhttp3|openConnection\\(|" +
            "java\\.net\\.Socket|javax\\.net|Retrofit|io\\.ktor|android\\.webkit\\.WebView|URL\\("
    )

    @Test
    fun onlyModelDownloaderTouchesNetwork() {
        val offenders = srcRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "ModelDownloader.kt" && it.name != "MiniAppWebView.kt" }
            .filter { netPatterns.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Network APIs found outside ModelDownloader: $offenders", offenders.isEmpty())
    }

    @Test
    fun noTelemetryOrAnalyticsDependencies() {
        val gradle = File(srcRoot().parentFile.parentFile, "build.gradle.kts").readText().lowercase()
        listOf("firebase", "crashlytics", "analytics", "sentry", "mixpanel", "amplitude", "appcenter")
            .forEach { assertTrue("Telemetry dependency '$it' present", !gradle.contains(it)) }
    }

    @Test
    fun cleartextDisabledInNetworkConfig() {
        val xml = File(srcRoot(), "res/xml/network_security_config.xml").readText()
        assertTrue(!xml.contains("cleartextTrafficPermitted=\"true\""))
    }

    @Test
    fun batteryStateLowOnlyWhenUnplugged() {
        assertTrue(BatteryState(10, false, false).isLowBattery)
        assertTrue(!BatteryState(10, true, false).isLowBattery)
        assertTrue(!BatteryState(50, false, false).isLowBattery)
    }

    @Test
    fun thermalLevelMapsAndOrders() {
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromApiStatus(0))
        assertTrue(ThermalLevel.CRITICAL.severity > ThermalLevel.SEVERE.severity)
    }
}
