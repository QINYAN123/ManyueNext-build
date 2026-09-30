package app.manyuenext.cloud.common

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFailure
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesException
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class CloudConnectorBase : CloudFavoritesSource {
    protected val client = OkHttpClient.Builder()
        .connectTimeout(16, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val store by lazy { EncryptedAccountStore("manyuenext.cloud.$id") }
    protected val preferences: SharedPreferences by lazy { getSourcePreferences() }
    protected val session: String? get() = store.read(preferences)

    override suspend fun accountState(): CloudAccountState = if (session.isNullOrBlank()) {
        CloudAccountState.LoginRequired
    } else CloudAccountState.Ready(null)

    override suspend fun refreshSession(): CloudAccountState = accountState()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "cloud_session"
            isPersistent = false
            title = "高级备用：导入会话令牌"
            summary = "仅在账号登录不可用时使用；令牌会通过 Android Keystore 加密保存在本机"
            dialogTitle = "粘贴会话令牌（高级备用）"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, value ->
                val token = value?.toString()?.trim().orEmpty()
                if (token.isNotEmpty()) saveSession(token)
                false
            }
        }.also(screen::addPreference)
        Preference(screen.context).apply {
            key = "cloud_session_clear"
            title = "清除云收藏会话"
            summary = "移除本机保存的加密会话，之后需要重新登录或导入"
            setOnPreferenceClickListener {
                clearSession()
                true
            }
        }.also(screen::addPreference)
    }

    protected fun saveSession(value: String) {
        if (value.isNotBlank()) store.write(preferences, value.trim())
    }

    protected fun clearSession() = store.clear(preferences)

    /** A raw request for providers whose protocol owns Authorization, Cookie, or signatures. */
    protected suspend fun rawRequest(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(url, headers).body

    protected data class RawResponse(val code: Int, val body: String, val headers: Map<String, String>)

    protected suspend fun execute(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json; charset=utf-8",
    ): RawResponse {
        val requestBody = body?.toRequestBody(contentType.toMediaType())
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            if (requestBody == null && method.equals("GET", ignoreCase = true)) get()
            else method(method, requestBody)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(CloudFavoritesException(CloudFailure.Network, "网络连接失败", e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val raw = response.use {
                            RawResponse(it.code, it.body?.string().orEmpty(), it.headers.toMap())
                        }
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CloudFavoritesException(CloudFailure.Network, "读取网络响应失败", error),
                            )
                        }
                    }
                }
            })
        }
    }

    protected suspend fun request(url: String, headers: Map<String, String> = emptyMap()): String {
        val authHeaders = headers.toMutableMap()
        session?.takeIf { it.isNotBlank() }?.let { authHeaders["Authorization"] = "Token $it" }
        val response = execute(url, authHeaders)
        if (response.code !in 200..299) throw mapHttpFailure(response.code)
        return response.body
    }

    companion object {
        fun mapHttpFailure(code: Int): CloudFavoritesException = CloudFavoritesException(
            when {
                code == 401 || code == 403 -> CloudFailure.Authentication
                code == 429 -> CloudFailure.RateLimited
                code in 400..499 -> CloudFailure.Remote
                else -> CloudFailure.Network
            },
            when {
                code == 401 || code == 403 -> "登录已失效"
                code == 429 -> "请求过于频繁，请稍后再试"
                else -> "图源暂时不可用"
            },
        )
    }
}
