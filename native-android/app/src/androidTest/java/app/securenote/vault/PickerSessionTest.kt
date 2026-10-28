package app.securenote.vault

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PickerSessionTest {
    @Test
    fun pickerResultPreservesSessionButTimeoutScreenOffAndUnexpectedResumeRevokeIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (scenario in
            listOf("result", "timeout", "screen-off", "unexpected-resume", "normal-background")) {
            val name = "test-picker-${UUID.randomUUID()}"
            val store = VaultStore(context, name)
            var clock = 1000L
            lateinit var model: VaultModel
            try {
                store.create("Synthetic picker session password".toByteArray()).fill(0)
                instrumentation.runOnMainSync {
                    model = VaultModel(context.applicationContext as Application, store) { clock }
                    model.unlocked.value = true
                    if (scenario != "normal-background") assertTrue(model.beginDocumentPicker())
                    model.onActivityPaused()
                    if (scenario != "normal-background") assertTrue(model.unlocked.value)
                    when (scenario) {
                        "result" -> {
                            model.finishDocumentPicker()
                            model.onActivityResumed()
                        }
                        "timeout" -> {
                            clock += 120001
                            model.finishDocumentPicker()
                        }
                        "screen-off" -> model.lock()
                        "unexpected-resume" -> model.onActivityResumed()
                    }
                    assertEquals(scenario == "result", model.unlocked.value)
                    assertEquals(scenario == "result", store.isOpen)
                    if (scenario == "result") {
                        // The exception is single-use: subsequent normal backgrounding must lock.
                        model.onActivityPaused()
                        assertFalse(model.unlocked.value)
                        assertFalse(store.isOpen)
                    }
                    androidx.lifecycle.ViewModelStore().apply {
                        put("picker", model)
                        clear()
                    }
                }
                model.flushWrites()
            } finally {
                store.close()
                File(context.noBackupFilesDir, name).deleteRecursively()
            }
        }
    }
}
