package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReadingCoverage.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Main-thread snapshot. The lease remains held until the receiver's IO copy completes. */
internal data class ManyueBenchmarkPairDescriptor(
    val pageIndex: Int,
    val openSource: () -> InputStream,
    val result: Result,
    val modelId: String,
    val pinnedImage: ManyueEnhancementCache.PinnedCachedImage,
)

/** ADB measurement fixture. Android's DUMP permission excludes ordinary third-party apps. */
class ManyueBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val operation = intent.getStringExtra("operation") ?: return
        val reader = ManyueBenchmarkControl.reader.get()
        if (operation == "exportpair") {
            val pageIndex = intent.getIntExtra("page", -1)
            val descriptor = if (pageIndex >= 0) {
                reader?.prepareManyueBenchmarkPair(pageIndex, intent.getBooleanExtra("allowOverlay", false))
            } else {
                null
            }
            if (descriptor == null) {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ManyueBenchmarkPairExporter.markRejected(context.applicationContext, "pair_unavailable")
                        pending.resultCode = 2
                        pending.resultData = "pair_unavailable"
                    } catch (_: Throwable) {
                        pending.resultCode = 3
                        pending.resultData = "export_failed"
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val outcome = ManyueBenchmarkPairExporter.export(context.applicationContext, descriptor)
                    pending.resultCode = if (outcome.first) 0 else 3
                    pending.resultData = outcome.second
                } catch (_: Throwable) {
                    pending.resultCode = 3
                    pending.resultData = "export_failed"
                } finally {
                    descriptor.pinnedImage.lease.close()
                    pending.finish()
                }
            }
            return
        }
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
            "state" -> {
                resultData = reader?.manyueBenchmarkReaderSnapshot()
                return
            }
            "begin", "observe", "comfort", "configure", "filters", "layer", "sampling", "prewarm", "jump", "end" -> {
                if (reader?.onManyueBenchmarkCommand(operation, intent) != true) {
                    resultCode = 2
                    return
                }
                resultData = "accepted:$operation"
                if (operation != "end") return
            }
            "export" -> Unit
            else -> {
                resultCode = 2
                return
            }
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

/** Benchmark-only byte-copy fixture. A successful pair proves file provenance, not a screen draw. */
private object ManyueBenchmarkPairExporter {
    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L

    fun markRejected(context: Context, reason: String) {
        val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw PairExportFailure("external_documents_unavailable")
        val directory = File(documents, "bw-pair")
        if (!directory.exists() && !directory.mkdirs()) throw PairExportFailure("output_directory_unavailable")
        if (!directory.isDirectory) throw PairExportFailure("output_directory_unavailable")
        val metadata = File(directory, "metadata.json")
        // Overwrite stale success first; a cleanup failure must not leave paired=true behind.
        metadata.writeText(
            JSONObject().apply {
                put("version", 1)
                put("paired", false)
                put("status", reason)
                put("provenance", "reader_page_stream_and_active_ai_cache;not_actual_draw")
            }.toString(2),
            Charsets.UTF_8,
        )
        listOf("source.bin", "output.bin", "source.bin.tmp", "output.bin.tmp", "metadata.json.tmp")
            .map { File(directory, it) }
            .forEach { if (!it.delete() && it.exists()) throw PairExportFailure("output_cleanup_failed") }
    }

    fun export(context: Context, descriptor: ManyueBenchmarkPairDescriptor): Pair<Boolean, String> {
        val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: return false to "external_documents_unavailable"
        val directory = File(documents, "bw-pair")
        if (!directory.exists() && !directory.mkdirs()) return false to "output_directory_unavailable"
        if (!directory.isDirectory) return false to "output_directory_unavailable"

        val sourceFile = File(directory, "source.bin")
        val outputFile = File(directory, "output.bin")
        val metadataFile = File(directory, "metadata.json")
        val sourceTemp = File(directory, "source.bin.tmp")
        val outputTemp = File(directory, "output.bin.tmp")
        val metadataTemp = File(directory, "metadata.json.tmp")

        // Invalidate any previous success before work begins, so stale metadata cannot imply a pair.
        if (!metadataFile.delete() && metadataFile.exists()) {
            runCatching {
                writeMetadataDirect(metadataFile, descriptor, paired = false, status = "metadata_unavailable")
            }
            return false to "metadata_unavailable"
        }
        listOf(sourceFile, outputFile, sourceTemp, outputTemp, metadataTemp).forEach {
            if (!it.delete() && it.exists()) return false to "output_cleanup_failed"
        }
        writeMetadata(metadataFile, metadataTemp, descriptor, paired = false, status = "preparing")

        var sourceSha: String? = null
        var outputSha: String? = null
        var sourceSize = 0L
        var outputSize = 0L
        var sourceDimensions: Pair<Int, Int>? = null
        var outputDimensions: Pair<Int, Int>? = null
        var failure = "export_failed"
        try {
            val sourceCopy = copyBounded(descriptor.openSource(), sourceTemp, "source_too_large")
            sourceSha = sourceCopy.first
            sourceSize = sourceCopy.second

            val cached = descriptor.pinnedImage.image
            if (!cached.file.isFile) throw PairExportFailure("output_unavailable")
            val outputCopy = cached.file.inputStream().use { copyBounded(it, outputTemp, "output_too_large") }
            outputSha = outputCopy.first
            outputSize = outputCopy.second

            val result = descriptor.result
            if (result.source.fingerprint.isNullOrBlank() || sourceSha != result.source.fingerprint) {
                throw PairExportFailure("source_hash_mismatch")
            }
            val actualSourceDimensions = readDimensions(sourceTemp)
            sourceDimensions = actualSourceDimensions
            if (actualSourceDimensions.first != result.source.sourceWidth ||
                actualSourceDimensions.second != result.source.sourceHeight
            ) {
                throw PairExportFailure("source_dimensions_mismatch")
            }
            val actualOutputDimensions = readDimensions(outputTemp)
            outputDimensions = actualOutputDimensions
            if (cached.width != result.outputWidth || cached.height != result.outputHeight ||
                actualOutputDimensions.first != result.outputWidth || actualOutputDimensions.second != result.outputHeight
            ) {
                throw PairExportFailure("output_dimensions_mismatch")
            }
            if (!resultStillCurrent(result)) throw PairExportFailure("result_no_longer_current")

            replaceFile(sourceTemp, sourceFile)
            replaceFile(outputTemp, outputFile)
            writeMetadata(
                metadataFile,
                metadataTemp,
                descriptor,
                paired = true,
                status = "paired",
                sourceSha = sourceSha,
                outputSha = outputSha,
                sourceSize = sourceSize,
                outputSize = outputSize,
                sourceDimensions = sourceDimensions,
                outputDimensions = outputDimensions,
            )
            return true to "paired"
        } catch (error: PairExportFailure) {
            failure = error.reason
        } catch (_: Throwable) {
            failure = "export_failed"
        } finally {
            sourceTemp.delete()
            outputTemp.delete()
        }

        // Fixed filenames are either a validated pair or explicitly marked unusable.
        sourceFile.delete()
        outputFile.delete()
        runCatching {
            writeMetadata(
                metadataFile,
                metadataTemp,
                descriptor,
                paired = false,
                status = failure,
                sourceSha = sourceSha,
                outputSha = outputSha,
                sourceSize = sourceSize.takeIf { it > 0L },
                outputSize = outputSize.takeIf { it > 0L },
                sourceDimensions = sourceDimensions,
                outputDimensions = outputDimensions,
            )
        }
        return false to failure
    }

    private fun resultStillCurrent(result: Result): Boolean {
        val runtime = ManyueRuntimeState
        return runtime.generation == result.generation &&
            runtime.aiModel.ordinal == result.model && runtime.modeInt == result.mode &&
            runtime.aiScalePercent == result.scalePercent &&
            ManyueAiUpscaler.coverageResultForToken(result.token) == result
    }

    private fun copyBounded(input: InputStream, destination: File, tooLargeReason: String): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.buffered().use { source ->
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_FILE_BYTES) throw PairExportFailure(tooLargeReason)
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }

    private fun readDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw PairExportFailure("invalid_image_dimensions")
        return options.outWidth to options.outHeight
    }

    private fun replaceFile(source: File, destination: File) {
        if (destination.exists() && !destination.delete()) throw PairExportFailure("output_write_failed")
        if (!source.renameTo(destination)) throw PairExportFailure("output_write_failed")
    }

    private fun writeMetadata(
        destination: File,
        temporary: File,
        descriptor: ManyueBenchmarkPairDescriptor,
        paired: Boolean,
        status: String,
        sourceSha: String? = null,
        outputSha: String? = null,
        sourceSize: Long? = null,
        outputSize: Long? = null,
        sourceDimensions: Pair<Int, Int>? = null,
        outputDimensions: Pair<Int, Int>? = null,
    ) {
        temporary.writeText(metadataJson(
            descriptor,
            paired,
            status,
            sourceSha,
            outputSha,
            sourceSize,
            outputSize,
            sourceDimensions,
            outputDimensions,
        ).toString(2), Charsets.UTF_8)
        replaceFile(temporary, destination)
    }

    private fun writeMetadataDirect(
        destination: File,
        descriptor: ManyueBenchmarkPairDescriptor,
        paired: Boolean,
        status: String,
    ) {
        destination.writeText(metadataJson(descriptor, paired, status).toString(2), Charsets.UTF_8)
    }

    private fun metadataJson(
        descriptor: ManyueBenchmarkPairDescriptor,
        paired: Boolean,
        status: String,
        sourceSha: String? = null,
        outputSha: String? = null,
        sourceSize: Long? = null,
        outputSize: Long? = null,
        sourceDimensions: Pair<Int, Int>? = null,
        outputDimensions: Pair<Int, Int>? = null,
    ): JSONObject {
        val result = descriptor.result
        return JSONObject().apply {
            put("version", 1)
            put("paired", paired)
            put("status", status)
            put("provenance", "reader_page_stream_and_active_ai_cache;not_actual_draw")
            put("overlayRequested", descriptor.pinnedImage.image.overlayRequested)
            put("overlayApplied", descriptor.pinnedImage.image.overlayApplied)
            put("pageIndex", descriptor.pageIndex)
            put("model", descriptor.modelId)
            put("mode", ManyueEnhancementMode.fromInt(result.mode).name)
            put("scalePercent", result.scalePercent)
            put("generation", result.generation)
            put("resultSourceFingerprint", result.source.fingerprint)
            sourceSha?.let { put("sourceSha256", it) }
            outputSha?.let { put("outputSha256", it) }
            sourceSize?.let { put("sourceBytes", it) }
            outputSize?.let { put("outputBytes", it) }
            put("expectedSourceWidth", result.source.sourceWidth)
            put("expectedSourceHeight", result.source.sourceHeight)
            put("expectedOutputWidth", result.outputWidth)
            put("expectedOutputHeight", result.outputHeight)
            sourceDimensions?.let {
                put("sourceWidth", it.first)
                put("sourceHeight", it.second)
            }
            outputDimensions?.let {
                put("outputWidth", it.first)
                put("outputHeight", it.second)
            }
        }
    }

    private class PairExportFailure(val reason: String) : Exception()
}
