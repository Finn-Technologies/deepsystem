package app.finn.deepsystem.background

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun taskRecordsPersistInSqlite() {
        val db = TaskDatabaseHelper.getInstance(ctx)
        val id = "test-" + System.nanoTime()
        db.insertOrUpdateTask(BackgroundTaskRecord(id = id, title = "T", progress = 40, status = TaskStatus.RUNNING))
        val got = db.getTaskById(id)
        assertNotNull(got)
        assertEquals(40, got!!.progress)
        db.insertOrUpdateTask(got.copy(status = TaskStatus.COMPLETED, progress = 100))
        assertEquals(TaskStatus.COMPLETED, db.getTaskById(id)!!.status)
    }

    @Test
    fun cancelInvokesListenerAndMarksCancelled() {
        val tm = TaskManager.getInstance(ctx)
        val id = "cancel-" + System.nanoTime()
        var cancelled = false
        tm.enqueue(BackgroundTaskRecord(id = id, title = "C"))
        tm.registerCancelListener(id) { cancelled = true }
        tm.cancelTask(id)
        assertTrue(cancelled)
        assertEquals(TaskStatus.CANCELLED, tm.getTask(id)!!.status)
    }

    @Test
    fun ledgerRecordsDownloadEntries() {
        val ledger = NetworkLedger.getInstance(ctx)
        val id = ledger.logStart("https://huggingface.co/x", "test")
        ledger.logSuccess(id, 123)
        assertTrue(TaskDatabaseHelper.getInstance(ctx).getNetworkLedgerEntries().isNotEmpty())
    }

    @Test
    fun assistIntentResolvesToFinn() {
        val i = Intent(Intent.ACTION_ASSIST).setPackage(ctx.packageName)
        assertNotNull(ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY))
    }
}
