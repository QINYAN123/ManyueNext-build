package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** ADB measurement fixture. Android's DUMP permission excludes ordinary third-party apps. */
class ManyueBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val operation = intent.getStringExtra("operation") ?: return
        val reader = ManyueBenchmarkControl.reader.get()
        if (operation != "export" && (reader == null || reader.isFinishing || reader.isDestroyed)) {
            resultCode = 1
            return
        }
        if (operation == "clearai") {
            // Restore original images and release enhancement leases before a cold measurement.
            // This clears only the two Manyue enhancement roots, never loader/download/user data.
            if (ManyueRuntimeState.modeInt != ManyueEnhancementMode.OFF.value) {
                resultCode = 2
                return
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ManyueEnhancementCache.clearCache(context.applicationContext).join()
                    val bytes = ManyueEnhancementCache.cacheSizeBytes(context.applicationContext)
                    pending.resultData = bytes.toString()
                    pending.resultCode = if (bytes == 0L) 0 else 4
                } catch (_: Throwable) {
                    pending.resultCode = 3
                } finally {
                    pending.finish()
                }
            }
            return
        }
        when (operation) {
            "begin", "configure", "jump", "end" -> {
                if (reader?.onManyueBenchmarkCommand(operation, intent) != true) {
                    resultCode = 2
                    return
                }
                if (operation != "end") return
            }
            "export" -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ManyuePerformanceDiagnostics.export(context.applicationContext)
                pending.resultCode = 0
            } catch (_: Throwable) {
                pending.resultCode = 3
            } finally {
                pending.finish()
            }
        }
    }
}
