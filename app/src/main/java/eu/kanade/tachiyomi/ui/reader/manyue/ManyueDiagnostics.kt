package eu.kanade.tachiyomi.ui.reader.manyue

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small in-process diagnostic surface; never stores image bytes, URLs, or account data. */
object ManyueDiagnostics {
    private val mutableLatest = MutableStateFlow("尚无 AI 任务")
    val latest = mutableLatest.asStateFlow()

    fun record(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        mutableLatest.value = "$time · $message"
    }
}
