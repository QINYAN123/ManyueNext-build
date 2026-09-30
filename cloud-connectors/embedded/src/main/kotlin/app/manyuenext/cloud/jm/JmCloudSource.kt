package app.manyuenext.cloud.jm

import app.manyuenext.cloud.common.CloudConnectorBase
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFailure
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesException
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import eu.kanade.tachiyomi.source.cloud.CloudPasswordLoginSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource
import java.util.UUID
import java.util.concurrent.CancellationException

class JmCloudSource : CloudConnectorBase(), CloudPasswordLoginSource {
    override val id = 1264765706795493282L
    override val name = "JM 云收藏"
    override val lang = "zh"
    override val providerKey = CloudProviderKey.JM
    override val targetSource = CloudTargetSource(6286738698187452081L, "eu.kanade.tachiyomi.extension.zh.jinmantiantang", "JM")

    // Only persist a host obtained from the encrypted v0.7.4 host-list response.
    private val savedHost: String?
        get() = preferences.getString("jm_api_host", null)?.trim()?.takeIf { it.startsWith("https://") }

    override suspend fun accountState(): CloudAccountState =
        if (session.isNullOrBlank()) CloudAccountState.LoginRequired else CloudAccountState.Ready(null)

    override suspend fun login(username: String, password: CharArray): CloudAccountState {
        try {
            val user = username.trim()
            require(user.isNotBlank() && password.isNotEmpty()) { "请输入 JM 账号和密码" }
            val timestamp = System.currentTimeMillis() / 1000L
            val boundary = "----Manyue${UUID.randomUUID().toString().replace("-", "")}"
            val body = JmApi.multipart(mapOf("username" to user, "password" to String(password)), boundary)
            val headers = authHeaders(timestamp).toMutableMap().apply {
                put("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            val host = apiHosts().firstOrNull()
                ?: throw CloudFavoritesException(CloudFailure.Network, "JM 服务线路暂时不可用")
            val response = execute(
                "${host}login",
                headers,
                method = "POST",
                body = body,
                contentType = "multipart/form-data; boundary=$boundary",
            )
            if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
            val (jwt, avs) = try {
                JmApi.parseLogin(response.body, timestamp)
            } catch (e: Exception) {
                throw CloudFavoritesException(CloudFailure.Parsing, "JM 登录响应无法解析", e)
            }
            saveSession("$jwt|$avs")
            return CloudAccountState.Ready(null)
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun getCloudFavorites(page: Int): CloudFavoritesPage {
        require(page > 0)
        val parts = session.orEmpty().split('|', limit = 2)
        val jwt = parts.firstOrNull().orEmpty().trim()
        if (jwt.isBlank()) throw CloudFavoritesException(CloudFailure.Authentication, "请先配置 JM 会话")
        val avs = parts.getOrNull(1).orEmpty().trim()
        val timestamp = System.currentTimeMillis() / 1000L
        var last: CloudFavoritesException? = null
        for (host in apiHosts()) {
            try {
                val headers = authHeaders(timestamp).toMutableMap().apply {
                    put("Authorization", "Bearer $jwt")
                    if (avs.isNotBlank()) put("Cookie", "AVS=$avs")
                }
                val response = execute("${host}favorite?page=$page&folder_id=0&o=mr", headers)
                if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
                return try {
                    JmApi.parseFavorites(response.body, page, timestamp)
                } catch (e: CloudFavoritesException) {
                    throw e
                } catch (e: Exception) {
                    throw CloudFavoritesException(CloudFailure.Parsing, "JM 收藏响应无法解析", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudFavoritesException) {
                if (e.failure == CloudFailure.Authentication || e.failure == CloudFailure.RateLimited || e.failure == CloudFailure.Parsing) throw e
                last = e
            }
        }
        throw last ?: CloudFavoritesException(CloudFailure.Network, "JM 服务线路暂时不可用")
    }

    private fun authHeaders(timestamp: Long): Map<String, String> = mapOf(
        "Accept" to "application/json,text/html,*/*",
        "User-Agent" to "ManyueHD/0.3 Android",
        "Tokenparam" to "$timestamp,2.0.21",
        "Token" to JmApi.token(timestamp),
    )

    private suspend fun apiHosts(): List<String> {
        val result = linkedSetOf<String>()
        savedHost?.let(result::add)
        var last: CloudFavoritesException? = null
        for (file in JmApi.hostFiles) {
            try {
                val response = execute(file)
                if (response.code !in 200..299) continue
                val discovered = JmApi.hosts(response.body)
                if (discovered.isNotEmpty()) {
                    result.addAll(discovered)
                    preferences.edit().putString("jm_api_host", discovered.first()).apply()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudFavoritesException) {
                last = e
            } catch (_: Exception) {
                // Try the next signed host-list mirror.
            }
        }
        if (result.isEmpty() && last != null) throw last
        return result.toList()
    }
}

