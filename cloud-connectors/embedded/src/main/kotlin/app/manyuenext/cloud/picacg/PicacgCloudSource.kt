package app.manyuenext.cloud.picacg

import app.manyuenext.cloud.common.CloudConnectorBase
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFailure
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesException
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import eu.kanade.tachiyomi.source.cloud.CloudPasswordLoginSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource
import org.json.JSONObject
import java.util.UUID

class PicacgCloudSource : CloudConnectorBase(), CloudPasswordLoginSource {
    private val appUuid: String
        get() = preferences.getString("pica_app_uuid", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            preferences.edit().putString("pica_app_uuid", it).apply()
        }
    override val id = 2951833901539335569L
    override val name = "PICACG 云收藏"
    override val lang = "zh"
    override val providerKey = CloudProviderKey.PICACG
    override val targetSource = CloudTargetSource(5768337869316468367L, "eu.kanade.tachiyomi.extension.zh.picacomic", "PICACG")

    override suspend fun accountState(): CloudAccountState =
        if (session.isNullOrBlank()) CloudAccountState.LoginRequired else CloudAccountState.Ready(null)

    override suspend fun login(username: String, password: CharArray): CloudAccountState {
        try {
            val email = username.trim()
            require(email.isNotBlank() && password.isNotEmpty()) { "请输入 PICACG 账号和密码" }
            val path = "auth/sign-in"
            var retried = false
            while (true) {
                val request = signedRequest(path, "POST", includeAuth = false)
                val body = JSONObject().put("email", email).put("password", String(password)).toString()
                val response = execute(
                    "https://picaapi.picacomic.com/$path",
                    request.headers,
                    method = "POST",
                    body = body,
                )
                if (response.code == 401 && !retried && updateServerOffset(response)) {
                    retried = true
                    continue
                }
                if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
                val token = try {
                    PicacgApi.loginToken(response.body)
                } catch (e: Exception) {
                    throw CloudFavoritesException(CloudFailure.Parsing, "PICACG 登录响应无法解析", e)
                }
                saveSession(token)
                return CloudAccountState.Ready(null)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun getCloudFavorites(page: Int): CloudFavoritesPage {
        require(page > 0)
        val token = session.orEmpty().substringBefore('|').trim()
        if (token.isBlank()) throw CloudFavoritesException(CloudFailure.Authentication, "请先配置 PICACG 会话")
        val path = "users/favourite?page=$page&s=dd"
        var retried = false
        while (true) {
            val request = signedRequest(path, "GET", includeAuth = true)
            val response = execute("https://picaapi.picacomic.com/$path", request.headers)
            if (response.code == 401 && !retried && updateServerOffset(response)) {
                retried = true
                continue
            }
            if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
            return try {
                PicacgApi.parseFavorites(response.body, page)
            } catch (e: CloudFavoritesException) {
                throw e
            } catch (e: Exception) {
                throw CloudFavoritesException(CloudFailure.Parsing, "PICACG 收藏响应无法解析", e)
            }
        }
    }

    private data class SignedRequest(val headers: Map<String, String>)

    private fun signedRequest(path: String, method: String, includeAuth: Boolean): SignedRequest {
        val offset = preferences.getLong("pica_time_offset", 0L)
        val time = ((System.currentTimeMillis() / 1000L) + offset).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        return SignedRequest(buildMap {
            put("api-key", "C69BAF41DA5ABD1FFEDC6D2FEA56B")
            put("accept", "application/vnd.picacomic.com.v1+json")
            put("app-channel", "1")
            put("time", time)
            put("nonce", nonce)
            put("signature", PicacgApi.signature(path, time, nonce, method))
            put("app-version", "2.3.1.20241111")
            put("app-uuid", appUuid)
            put("image-quality", "original")
            put("app-platform", "android")
            put("app-build-version", "20250144")
            put("User-Agent", "okhttp/3.8.1")
            if (includeAuth) put("authorization", session.orEmpty().substringBefore('|').trim())
        })
    }

    private fun updateServerOffset(response: RawResponse): Boolean {
        val serverTime = response.headers.entries.firstOrNull { it.key.equals("Server-Time", true) }?.value?.toLongOrNull() ?: return false
        preferences.edit().putLong("pica_time_offset", serverTime - System.currentTimeMillis() / 1000L).apply()
        return true
    }
}

