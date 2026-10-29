package app.securenote.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "app.securenote.vault.benchmark"

@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun lockedStartup() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode =
                CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable),
            iterations = 30,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
}

@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startup() =
        rule.collect(packageName = TARGET, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
        }

    @Test
    fun vaultJourneys() =
        rule.collect(packageName = TARGET, includeInStartupProfile = false, maxIterations = 3) {
            // This hard-coded benchmark package is separate from both production and preview data.
            device.executeShellCommand("pm clear $TARGET")
            startActivityAndWait()
            fun node(selector: androidx.test.uiautomator.BySelector) =
                checkNotNull(device.wait(Until.findObject(selector), 30000))
            node(By.res("vault-password")).text = "Synthetic profile password 123"
            node(By.res("vault-confirmation")).text = "Synthetic profile password 123"
            node(By.text("Create vault")).click()
            node(By.text("Record your recovery key"))
            node(By.checkable(true)).click()
            node(By.text("Continue")).click()
            node(By.desc("Add")).click()
            node(By.text("New note")).click()
            node(By.res("note-title")).text = "Synthetic profile note"
            node(By.text("Lock")).click()
            node(By.res("vault-password")).text = "Synthetic profile password 123"
            node(By.text("Unlock")).click()
            node(By.text("Synthetic profile note")).click()
            device.waitForIdle()
        }
}
