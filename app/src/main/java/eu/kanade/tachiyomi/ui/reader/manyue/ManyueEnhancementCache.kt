package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Disk cache for complete AI-enhanced images.
 * Layout: <cacheDir>/manyue_ai_anime_fixed2_v7/<key>/{manifest.json,image.webp|image.png}
 */
object ManyueEnhancementCache {

    // Native target resizing and the optional overlay order change invalidate older results.
    const val MODEL_VERSION = "anime_native_target_v8"

    // Retain the directory so existing bundles still count toward the budget and can be evicted.
    private const val CACHE_DIR_NAME = "manyue_ai_anime_fixed2_v7"
    private const val LEGACY_CACHE_DIR_NAME = "manyue_ai_anime_fixed2_v6"
    private const val MAX_CACHE_BYTES = 805_306_368L // 768 MiB
    private const val TRIM_TO_BYTES = 671_088_640L // 640 MiB
    private const val NEAR_PREVIOUS_PAGES = 2
    const val DEFAULT_PREFETCH_RESERVATION_BYTES = 64_777_216L // 12 MP RGBA + 16 MiB overhead

    private val cacheLock = Any()
    private val diskLock = Any()
    private val waitersLock = Any()
    private val leasesByBundle = mutableMapOf<String, Int>()
    private val deletingBundles = mutableSetOf<String>()
    private val reservations = linkedMapOf<Long, ReservationRecord>()
    private val budgetWaiters = mutableListOf<CancellableContinuation<Unit>>()
    private var nextReservationId = 1L
    private var budgetEventVersion = 0L
    private var readingPosition: ReadingPosition? = null
    private var protectedPageSnapshot: Set<Pair<Long, Int>> = emptySet()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ReadingIdentity(val mangaId: Long, val chapterId: Long, val pageIndex: Int)

    private data class ReadingPosition(val mangaId: Long, val chapterId: Long, val pageIndex: Int)

    private data class ReservationRecord(val identity: ReadingIdentity, val bytes: Long)

    /** A reservation prevents other prefetches from spending this page's cache budget. */
    class PrefetchReservation internal constructor(
        private val id: Long?,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) id?.let(::releaseReservation)
        }
    }

    /** Pins a cache bundle while an image view or decoder may still read it. */
    class CacheLease internal constructor(
        private val bundleKey: String?,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) bundleKey?.let(::releaseBundleLease)
        }
    }

    data class PinnedCachedImage(val image: CachedImage, val lease: CacheLease)

    data class CachedImage(
        val file: File,
        val width: Int,
        val height: Int,
        val overlayRequested: Boolean = false,
        val overlayApplied: Boolean = false,
        val detail: String? = null,
        val readingIdentity: ReadingIdentity? = null,
    )

    fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /** Small in-memory reader snapshot; safe to call from the reader thread. */
    fun updateReadingPosition(mangaId: Long, chapterId: Long, pageIndex: Int) {
        synchronized(cacheLock) {
            readingPosition = ReadingPosition(mangaId, chapterId, pageIndex.coerceAtLeast(0))
        }
        signalBudgetWaiters()
    }

    fun clearReadingPosition(mangaId: Long? = null, chapterId: Long? = null) {
        synchronized(cacheLock) {
            val current = readingPosition ?: return
            if (mangaId != null && current.mangaId != mangaId) return
            if (chapterId != null && current.chapterId != chapterId) return
            readingPosition = null
            protectedPageSnapshot = emptySet()
        }
        signalBudgetWaiters()
    }

    /** Visible pairs are `(chapterId, pageIndex)` within the active manga. */
    fun updateProtectedPages(visiblePages: Set<Pair<Long, Int>>) {
        synchronized(cacheLock) {
            protectedPageSnapshot = visiblePages.toSet()
        }
        signalBudgetWaiters()
    }

    /**
     * Reserve disk budget before starting a prefetch. This may suspend while protected results
     * occupy the budget; cancellation removes the waiter and returns immediately.
     */
    suspend fun awaitPrefetchBudget(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        estimatedBytes: Long = DEFAULT_PREFETCH_RESERVATION_BYTES,
    ): PrefetchReservation {
        require(estimatedBytes in 1..MAX_CACHE_BYTES) { "Invalid prefetch budget reservation" }
        val identity = ReadingIdentity(mangaId, chapterId, pageIndex)
        var acquired: PrefetchReservation? = null
        try {
            return withContext(Dispatchers.IO) {
                acquirePrefetchReservation(context, identity, estimatedBytes) { acquired = it }
            }
        } catch (error: Throwable) {
            acquired?.close()
            throw error
        }
    }

    /**
     * Pin a cache-owned file before handing it to an image view or decoder. Files outside this
     * AI cache receive a no-op lease; a bundle currently being deleted returns null.
     */
    fun pinFile(context: Context, file: File): CacheLease? {
        val key = managedBundleKey(context, file) ?: return CacheLease(null)
        synchronized(cacheLock) {
            if (key in deletingBundles) return null
            leasesByBundle[key] = (leasesByBundle[key] ?: 0) + 1
        }
        val lease = CacheLease(key)
        if (!file.isFile) {
            lease.close()
            return null
        }
        return lease
    }

    /** Atomically pins a cache bundle before reading its manifest/image paths. */
    fun pinCachedImage(context: Context, key: String, classicStrength: Int? = null): PinnedCachedImage? {
        if (!key.matches(Regex("[0-9a-f]{32}"))) return null
        val lease = synchronized(cacheLock) {
            if (key in deletingBundles) return null
            leasesByBundle[key] = (leasesByBundle[key] ?: 0) + 1
            CacheLease(key)
        }
        val image = runCatching { cachedImage(File(cacheDir(context), key), classicStrength) }.getOrNull()
        if (image == null || !image.file.isFile) {
            lease.close()
            return null
        }
        File(image.file.parentFile, "").setLastModified(System.currentTimeMillis())
        return PinnedCachedImage(image, lease)
    }

    internal fun isManagedCacheFile(context: Context, file: File): Boolean =
        managedBundleKey(context, file) != null

    private fun managedBundleKey(context: Context, file: File): String? {
        val rootPath = File(context.cacheDir, CACHE_DIR_NAME).absoluteFile.path.trimEnd(File.separatorChar)
        val filePath = file.absoluteFile.path
        val prefix = rootPath + File.separator
        if (!filePath.startsWith(prefix)) return null
        val parts = filePath.removePrefix(prefix).split(File.separatorChar)
        val key = parts.firstOrNull() ?: return null
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return key.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    }

    private fun releaseBundleLease(key: String) {
        synchronized(cacheLock) {
            val count = leasesByBundle[key] ?: return
            if (count <= 1) leasesByBundle.remove(key) else leasesByBundle[key] = count - 1
        }
        signalBudgetWaiters()
    }

    private fun releaseReservation(id: Long) {
        synchronized(cacheLock) { reservations.remove(id) } ?: return
        signalBudgetWaiters()
    }

    private suspend fun acquirePrefetchReservation(
        context: Context,
        identity: ReadingIdentity,
        estimatedBytes: Long,
        onAcquired: (PrefetchReservation) -> Unit,
    ): PrefetchReservation {
        while (true) {
            val observedVersion = synchronized(waitersLock) { budgetEventVersion }
            val reservation = synchronized(diskLock) {
                if (!ensureCapacity(context, estimatedBytes, identity)) {
                    null
                } else {
                    synchronized(cacheLock) {
                        val id = nextReservationId++
                        reservations[id] = ReservationRecord(identity, estimatedBytes)
                        PrefetchReservation(id)
                    }
                }
            }
            if (reservation != null) {
                onAcquired(reservation)
                return reservation
            }
            awaitBudgetChange(observedVersion)
        }
    }

    private suspend fun awaitBudgetChange(observedVersion: Long) = suspendCancellableCoroutine<Unit> { continuation ->
        synchronized(waitersLock) {
            if (budgetEventVersion != observedVersion) {
                continuation.resume(Unit)
            } else {
                budgetWaiters += continuation
            }
        }
        continuation.invokeOnCancellation {
            synchronized(waitersLock) { budgetWaiters.remove(continuation) }
        }
    }

    private fun signalBudgetWaiters() {
        val waiters = synchronized(waitersLock) {
            budgetEventVersion++
            budgetWaiters.toList().also { budgetWaiters.clear() }
        }
        waiters.forEach { continuation ->
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    fun cacheKey(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        mode: Int,
        targetMode: Int,
        targetWidth: Int,
        classicStrength: Int,
        sourceFingerprint: String = "",
        targetScaleTenths: Int = 20,
        modelId: String = "legacy",
        anime4kOverlay: Boolean = false,
        aiDetailStrength: Int = 0,
    ): String {
        // v9.5 overlay=true entries could contain a plain AI image after a silent overlay failure.
        // MODEL_VERSION also isolates results encoded before native target resizing.
        val overlayKeyVersion = if (anime4kOverlay) "overlay-v8" else "v7"
        val raw = "$MODEL_VERSION|$mangaId|$chapterId|$pageIndex|$mode|$targetMode|$targetWidth|" +
            "$targetScaleTenths|$classicStrength|$sourceFingerprint|$modelId|" +
            "overlay=$anime4kOverlay|$overlayKeyVersion|detail=$aiDetailStrength"
        val md = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /** Returns the bundle dir if its complete image and dimension manifest exist. */
    fun getBundle(context: Context, key: String): File? {
        val dir = File(cacheDir(context), key)
        if (isBundleDeleting(key)) return null
        if (cachedImage(dir) == null) return null
        dir.setLastModified(System.currentTimeMillis())
        return dir
    }

    fun getImage(context: Context, key: String): CachedImage? =
        getBundle(context, key)?.let { cachedImage(it) }

    fun getClassicImage(context: Context, key: String, strength: Int): CachedImage? {
        val bundle = getBundle(context, key) ?: return null
        return cachedImage(bundle, strength)
    }

    /** Internal pure helper so cache file validation can be covered without an Android Context. */
    internal fun cachedImage(bundle: File, classicStrength: Int? = null): CachedImage? {
        if (!bundle.isDirectory) return null
        val manifest = runCatching { File(bundle, "manifest.json").readText() }.getOrNull() ?: return null
        val name = Regex("\\\"image\\\"\\s*:\\s*\\\"(image\\.(?:webp|png))\\\"")
            .find(manifest)?.groupValues?.get(1) ?: return null
        val width = Regex("\\\"width\\\"\\s*:\\s*(\\d+)")
            .find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = Regex("\\\"height\\\"\\s*:\\s*(\\d+)")
            .find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val overlayRequested = jsonBoolean(manifest, "overlayRequested") ?: false
        val overlayApplied = overlayRequested && (jsonBoolean(manifest, "overlayApplied") ?: false)
        val storedDetail = jsonString(manifest, "detail")
        val detail = if (overlayRequested && !overlayApplied) {
            storedDetail ?: OVERLAY_NOT_APPLIED_DETAIL
        } else {
            storedDetail
        }
        val readingIdentity = parseReadingIdentity(manifest)
        if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(width, height, ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS)) {
            return null
        }
        val image = if (classicStrength == null) {
            File(bundle, name)
        } else {
            classicVariantFile(bundle, classicStrength) ?: return null
        }
        if (!image.isFile || image.length() <= 0L) return null
        return CachedImage(image, width, height, overlayRequested, overlayApplied, detail, readingIdentity)
    }

    internal fun imageFile(bundle: File): File? = cachedImage(bundle)?.file

    private fun classicVariantFile(bundle: File, strength: Int): File? {
        if (strength !in 1..100) return null
        return File(bundle, "classic_luma_v2_$strength.webp")
    }

    /** Atomically copy one complete encoded image into the cache bundle. */
    fun putImage(
        context: Context,
        key: String,
        source: File,
        width: Int,
        height: Int,
        overlayRequested: Boolean = false,
        overlayApplied: Boolean = false,
        detail: String? = null,
        readingIdentity: ReadingIdentity? = null,
        consumeSource: Boolean = false,
    ): Boolean {
        require(source.isFile && source.length() > 0L) { "AI output file is missing" }
        require(ManyueAiSafetyPolicy.isPixelBudgetSafe(width, height, ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS))
        require(!overlayApplied || overlayRequested) { "Anime4K overlay cannot apply unless requested" }
        val imageName = when (source.extension.lowercase()) {
            "png" -> "image.png"
            "webp" -> "image.webp"
            else -> error("unsupported AI output format: ${source.extension}")
        }
        val base = cacheDir(context)
        val finalDir = File(base, key)
        val pending = File(base, "$key.pending")
        val manifest = mutableListOf<Pair<String, Any>>(
            "image" to imageName,
            "width" to width,
            "height" to height,
            "overlayRequested" to overlayRequested,
            "overlayApplied" to overlayApplied,
        )
        val finalDetail = detail ?: if (overlayRequested && !overlayApplied) {
            OVERLAY_NOT_APPLIED_DETAIL
        } else {
            null
        }
        if (finalDetail != null) manifest += "detail" to finalDetail
        readingIdentity?.let {
            manifest += "mangaId" to it.mangaId
            manifest += "chapterId" to it.chapterId
            manifest += "pageIndex" to it.pageIndex
        }
        val manifestText = buildJsonObject(*manifest.toTypedArray())
        val bundleBytes = source.length() + manifestText.toByteArray(Charsets.UTF_8).size
        return synchronized(diskLock) {
            if (cachedImage(finalDir) != null) {
                consumeOneReservation(readingIdentity)
                finalDir.setLastModified(System.currentTimeMillis())
                return true
            }
            if (finalDir.exists() && !deleteBundleIfUnleased(
                    context,
                    key,
                    parseReadingIdentity(manifestText),
                    respectReadingProtection = false,
                )
            ) {
                return false
            }
            val ownReservedBytes = reservationBytes(readingIdentity)
            if (!ensureCapacity(context, bundleBytes, readingIdentity, ownReservedBytes)) return false
            if (pending.exists()) pending.deleteRecursively()
            pending.mkdirs()
            try {
                val pendingImage = File(pending, imageName)
                val consumed = consumeSource && isGeneratedWorkOutput(context, source) && source.renameTo(pendingImage)
                if (!consumed) {
                    FileOutputStream(pendingImage).use { out ->
                        FileInputStream(source).use { it.copyTo(out) }
                    }
                }
                File(pending, "manifest.json").writeText(manifestText)
                if (!pending.renameTo(finalDir)) error("failed to commit cache bundle $key")
                consumeOneReservation(readingIdentity)
                signalBudgetWaiters()
                true
            } catch (t: Throwable) {
                pending.deleteRecursively()
                throw t
            }
        }
    }

    /** Atomically cache a classic-enhanced AI variant beside its base output. */
    fun putClassicImage(context: Context, key: String, strength: Int, bitmap: Bitmap): CachedImage? {
        if (strength !in 1..100) return null
        val pinnedBase = pinCachedImage(context, key) ?: return null
        var temporary: File? = null
        return try {
            val bundle = File(cacheDir(context), key)
            val base = pinnedBase.image
            if (bitmap.width != base.width || bitmap.height != base.height) return null
            val image = classicVariantFile(bundle, strength) ?: return null
            if (image.isFile && image.length() > 0L) return cachedImage(bundle, strength)
            temporary = File.createTempFile("manyue-ai-classic-", ".tmp", context.cacheDir)
            val encoded = FileOutputStream(temporary).use {
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 96, it)
            }
            check(encoded && temporary.length() > 0L) { "classic AI cache encoding failed" }
            synchronized(diskLock) {
                if (!ensureCapacity(context, temporary.length(), base.readingIdentity)) return null
                if (image.exists() && isBundleLeased(key)) return null
                val pending = File(bundle, "${image.name}.pending")
                if (pending.exists()) pending.delete()
                if (image.exists() && !image.delete()) return null
                if (!temporary.renameTo(pending)) {
                    FileOutputStream(pending).use { out -> FileInputStream(temporary).use { it.copyTo(out) } }
                }
                check(pending.length() > 0L && pending.renameTo(image)) { "failed to commit classic AI image" }
                bundle.setLastModified(System.currentTimeMillis())
                signalBudgetWaiters()
                cachedImage(bundle, strength)
            }
        } catch (t: Throwable) {
            null
        } finally {
            temporary?.delete()
            pinnedBase.lease.close()
        }
    }

    fun removeClassicImage(context: Context, key: String, strength: Int) {
        synchronized(diskLock) {
            val bundle = getBundle(context, key) ?: return
            val marked = synchronized(cacheLock) {
                if (key in deletingBundles || (leasesByBundle[key] ?: 0) > 0) {
                    false
                } else {
                    deletingBundles += key
                    true
                }
            }
            if (!marked) return
            val deleted = try {
                classicVariantFile(bundle, strength)?.delete() == true
            } finally {
                synchronized(cacheLock) { deletingBundles.remove(key) }
            }
            if (deleted) signalBudgetWaiters()
        }
    }

    internal fun buildJsonObject(vararg pairs: Pair<String, Any>): String =
        pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            if (value is String) "\"$key\":\"${escapeJsonString(value)}\"" else "\"$key\":$value"
        }

    private fun jsonBoolean(json: String, key: String): Boolean? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun jsonString(json: String, key: String): String? {
        val encoded = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            .find(json)?.groupValues?.get(1) ?: return null
        val result = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val char = encoded[index++]
            if (char != '\\' || index >= encoded.length) {
                result.append(char)
                continue
            }
            when (val escaped = encoded[index++]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (index + 4 <= encoded.length) {
                        encoded.substring(index, index + 4).toIntOrNull(16)?.let { result.append(it.toChar()) }
                        index += 4
                    }
                }
                else -> result.append(escaped)
            }
        }
        return result.toString()
    }

    private fun jsonLong(json: String, key: String): Long? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
            .find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun parseReadingIdentity(json: String): ReadingIdentity? {
        val mangaId = jsonLong(json, "mangaId") ?: return null
        val chapterId = jsonLong(json, "chapterId") ?: return null
        val pageIndex = jsonLong(json, "pageIndex")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null
        return ReadingIdentity(mangaId, chapterId, pageIndex)
    }

    private fun escapeJsonString(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    internal const val OVERLAY_NOT_APPLIED_DETAIL =
        "Anime4KCPP 未生效，已保留 AI 超分"

    fun clearCache(context: Context) {
        ioScope.launch {
            synchronized(diskLock) {
                val current = cacheDir(context)
                current.listFiles()?.forEach { entry ->
                    val key = entry.name.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
                    if (key == null) {
                        entry.deleteRecursively()
                    } else {
                        deleteBundleIfUnleased(
                            context,
                            key,
                            cachedImage(entry)?.readingIdentity,
                            respectReadingProtection = false,
                        )
                    }
                }
                File(context.cacheDir, LEGACY_CACHE_DIR_NAME).deleteRecursively()
            }
            signalBudgetWaiters()
        }
    }

    fun cacheSizeBytes(context: Context): Long {
        val current = File(context.cacheDir, CACHE_DIR_NAME)
        val legacy = File(context.cacheDir, LEGACY_CACHE_DIR_NAME)
        return directoryBytes(current) + directoryBytes(legacy)
    }

    fun cacheSizeText(context: Context): String {
        val bytes = cacheSizeBytes(context)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.getDefault(), "%.1f MiB", mb)
    }

    /** Schedule cache scanning and trimming away from the reader/main thread. */
    fun trimCache(context: Context) {
        ioScope.launch {
            synchronized(diskLock) { trimCacheOnIo(context) }
        }
    }

    private fun trimCacheOnIo(context: Context) {
        val reserved = reservedBytes()
        val currentSize = cacheSizeBytes(context)
        if (currentSize + reserved <= MAX_CACHE_BYTES) return
        evictUntil(
            roots = listOf(cacheDir(context), File(context.cacheDir, LEGACY_CACHE_DIR_NAME)),
            startingBytes = currentSize,
            targetBytes = TRIM_TO_BYTES - reserved,
        )
    }

    /** Called only while diskLock is held, from IO or a worker-side cache write. */
    private fun ensureCapacity(
        context: Context,
        incomingBytes: Long,
        incomingIdentity: ReadingIdentity?,
        ownReservationBytes: Long = 0L,
    ): Boolean {
        if (incomingBytes < 0L || incomingBytes > MAX_CACHE_BYTES) return false
        val currentSize = cacheSizeBytes(context)
        val reserved = (reservedBytes() - ownReservationBytes).coerceAtLeast(0L)
        if (currentSize + reserved + incomingBytes <= MAX_CACHE_BYTES) return true
        val target = minOf(TRIM_TO_BYTES, MAX_CACHE_BYTES - reserved - incomingBytes)
        evictUntil(
            roots = listOf(cacheDir(context), File(context.cacheDir, LEGACY_CACHE_DIR_NAME)),
            startingBytes = currentSize,
            targetBytes = target,
        )
        return cacheSizeBytes(context) + reserved + incomingBytes <= MAX_CACHE_BYTES
    }

    private data class EvictionCandidate(
        val file: File,
        val key: String?,
        val identity: ReadingIdentity?,
        val lastModified: Long,
        val rank: Int,
        val pageOrder: Int,
        val bytes: Long,
    )

    private fun evictUntil(
        roots: List<File>,
        startingBytes: Long,
        targetBytes: Long,
    ) {
        val state = readingSnapshot()
        val candidates = roots.flatMap { root ->
            root.listFiles().orEmpty().map { entry ->
                val isCurrentBundle = root.name == CACHE_DIR_NAME && entry.isDirectory &&
                    entry.name.matches(Regex("[0-9a-f]{32}"))
                val image = if (isCurrentBundle) cachedImage(entry) else null
                val identity = image?.readingIdentity
                val rank = evictionRank(identity, state.first, state.second)
                EvictionCandidate(
                    file = entry,
                    key = entry.name.takeIf { isCurrentBundle },
                    identity = identity,
                    lastModified = entry.lastModified(),
                    rank = rank.first,
                    pageOrder = rank.second,
                    bytes = directoryBytes(entry),
                )
            }
        }.sortedWith(compareBy<EvictionCandidate>({ it.rank }, { it.pageOrder }, { it.lastModified }))
        var size = startingBytes
        for (candidate in candidates) {
            if (size <= targetBytes) break
            if (deleteCandidate(candidate)) size -= candidate.bytes
        }
    }

    private fun evictionRank(
        identity: ReadingIdentity?,
        position: ReadingPosition?,
        visiblePages: Set<Pair<Long, Int>>,
    ): Pair<Int, Int> {
        if (identity == null) return 0 to 0 // old manifests and interrupted temp entries first
        if (position == null || identity.mangaId != position.mangaId) return 1 to 0
        if (identity.chapterId != position.chapterId) return 1 to 0
        if (isIdentityProtected(identity, position, visiblePages)) return 5 to 0
        return 2 to identity.pageIndex // farthest-read (smallest page index) first
    }

    private fun isIdentityProtected(
        identity: ReadingIdentity?,
        position: ReadingPosition?,
        visiblePages: Set<Pair<Long, Int>>,
    ): Boolean {
        if (identity == null) return false
        if (position != null && identity.mangaId == position.mangaId && identity.chapterId == position.chapterId &&
            identity.pageIndex >= position.pageIndex - NEAR_PREVIOUS_PAGES
        ) {
            return true
        }
        return position?.mangaId?.let { mangaId ->
            identity.mangaId == mangaId && (identity.chapterId to identity.pageIndex) in visiblePages
        } ?: false
    }

    private fun deleteCandidate(
        candidate: EvictionCandidate,
        respectReadingProtection: Boolean = true,
    ): Boolean {
        val key = candidate.key
        if (key == null) {
            val deleted = candidate.file.deleteRecursively()
            if (deleted) signalBudgetWaiters()
            return deleted
        }
        synchronized(cacheLock) {
            if (key in deletingBundles || (leasesByBundle[key] ?: 0) > 0) return false
            if (respectReadingProtection &&
                isIdentityProtected(candidate.identity, readingPosition, protectedPageSnapshot)
            ) {
                return false
            }
            deletingBundles += key
        }
        val deleted = try {
            candidate.file.deleteRecursively()
        } finally {
            synchronized(cacheLock) { deletingBundles.remove(key) }
        }
        if (deleted) signalBudgetWaiters()
        return deleted
    }

    private fun deleteBundleIfUnleased(
        context: Context,
        key: String,
        identity: ReadingIdentity?,
        respectReadingProtection: Boolean = true,
    ): Boolean {
        val bundle = File(cacheDir(context), key)
        if (!bundle.exists()) return true
        val candidate = EvictionCandidate(bundle, key, identity, bundle.lastModified(), 0, 0, directoryBytes(bundle))
        return deleteCandidate(candidate, respectReadingProtection)
    }

    private fun isBundleDeleting(key: String): Boolean = synchronized(cacheLock) { key in deletingBundles }

    private fun isBundleLeased(key: String): Boolean = synchronized(cacheLock) { (leasesByBundle[key] ?: 0) > 0 }

    private fun readingSnapshot(): Pair<ReadingPosition?, Set<Pair<Long, Int>>> = synchronized(cacheLock) {
        readingPosition to protectedPageSnapshot
    }

    private fun reservedBytes(): Long = synchronized(cacheLock) { reservations.values.sumOf { it.bytes } }

    internal fun pendingBudgetWaiterCountForTest(): Int = synchronized(waitersLock) {
        budgetWaiters.count { it.isActive }
    }

    private fun reservationBytes(identity: ReadingIdentity?): Long {
        if (identity == null) return 0L
        return synchronized(cacheLock) { reservations.values.filter { it.identity == identity }.sumOf { it.bytes } }
    }

    private fun consumeOneReservation(identity: ReadingIdentity?) {
        if (identity == null) return
        val removed = synchronized(cacheLock) {
            val id = reservations.entries.firstOrNull { it.value.identity == identity }?.key ?: return
            reservations.remove(id)
        }
        if (removed != null) signalBudgetWaiters()
    }

    private fun directoryBytes(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun isGeneratedWorkOutput(context: Context, source: File): Boolean = runCatching {
        val canonical = source.canonicalFile
        val parent = canonical.parentFile ?: return false
        val cacheRoot = context.cacheDir.canonicalFile
        parent.parentFile?.canonicalFile == cacheRoot &&
            parent.name.matches(Regex("manyue_work_[A-Za-z0-9_-]+"))
    }.getOrDefault(false)
}
