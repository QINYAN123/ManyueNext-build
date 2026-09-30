package app.manyuenext.cloud.copymanga

import app.manyuenext.cloud.common.CloudConnectorBase
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFailure
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesException
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import eu.kanade.tachiyomi.source.cloud.CloudPasswordLoginSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CancellationException

class CopyMangaCloudSource : CloudConnectorBase(), CloudPasswordLoginSource {
    private val hosts = listOf(
        "https://api.copy202602.com",
        "https://api.manga2026.xyz",
        "https://mapi.mangacopy.com",
        "https://api.copy202601.com",
        "https://api.copy2000.online",
        "https://api.copy-manga.com",
    )
    private val webLoginHosts = listOf("copy4000.com", "copy3000.com")
    override val id = 2071416177830019694L
    override val name = "拷贝漫画云收藏"
    override val lang = "zh"
    override val providerKey = CloudProviderKey.COPY_MANGA
    override val targetSource = CloudTargetSource(6696312508930833206L, "eu.kanade.tachiyomi.extension.zh.copymanga", "拷贝漫画")

    override suspend fun accountState(): CloudAccountState =
        if (session.isNullOrBlank()) CloudAccountState.LoginRequired else CloudAccountState.Ready(null)

    /** Uses the verified v0.7.4 website endpoint; never sends credentials to API mirrors. */
    override suspend fun login(username: String, password: CharArray): CloudAccountState {
        try {
            val user = username.trim()
            require(user.isNotBlank() && password.isNotEmpty()) { "请输入拷贝漫画账号和密码" }
            var last: CloudFavoritesException? = null
            for (host in webLoginHosts) {
                try {
                    val salt = 100000L + SecureRandom().nextInt(900000)
                    val encodedPassword = java.util.Base64.getEncoder().encodeToString(
                        (String(password) + "-" + salt).toByteArray(StandardCharsets.UTF_8),
                    )
                    val body = listOf(
                        "username" to user,
                        "password" to encodedPassword,
                        "salt" to salt.toString(),
                        "platform" to "2",
                        "version" to "2025.12.10",
                        "source" to "freeSite",
                    ).joinToString("&") { (key, value) ->
                        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }
                    val response = execute(
                        "https://$host/api/kb/web/login",
                        loginHeaders(host),
                        method = "POST",
                        body = body,
                        contentType = "application/x-www-form-urlencoded; charset=utf-8",
                    )
                    if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
                    val token = try {
                        CopyMangaApi.loginToken(response.body)
                    } catch (e: Exception) {
                        throw CloudFavoritesException(CloudFailure.Parsing, "拷贝漫画登录响应无法解析", e)
                    }
                    saveSession(token)
                    return CloudAccountState.Ready(null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CloudFavoritesException) {
                    if (e.failure == CloudFailure.Authentication || e.failure == CloudFailure.RateLimited || e.failure == CloudFailure.Parsing) throw e
                    last = e
                }
            }
            throw last ?: CloudFavoritesException(CloudFailure.Network, "拷贝漫画登录线路暂时不可用")
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun getCloudFavorites(page: Int): CloudFavoritesPage {
        require(page > 0)
        val path = "/api/v3/member/collect/comics?limit=21&offset=${CopyMangaApi.offset(page)}&ordering=-datetime_modifier"
        var last: CloudFavoritesException? = null
        for (host in hosts) {
            try {
                val timestamp = (System.currentTimeMillis() / 1000L).toString()
                val headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "COPY/3.0.6",
                    "source" to "copyApp",
                    "platform" to "3",
                    "version" to "3.0.6",
                    "webp" to "1",
                    "referer" to "com.copymanga.app-3.0.6",
                    "region" to "0",
                    "x-auth-timestamp" to timestamp,
                    "x-auth-signature" to CopyMangaApi.signature(timestamp),
                    "umstring" to "b4c89ca4104ea9a97750314d791520ac",
                    "deviceinfo" to stableId("deviceinfo"),
                    "device" to stableId("device"),
                    "pseudoid" to stableId("pseudoid"),
                    "Authorization" to "Token ${session.orEmpty()}",
                )
                val response = execute(host + path, headers)
                if (response.code !in 200..299) throw CloudConnectorBase.mapHttpFailure(response.code)
                return CopyMangaApi.parseFavorites(response.body, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudFavoritesException) {
                if (e.failure == CloudFailure.Authentication || e.failure == CloudFailure.RateLimited) throw e
                last = e
            } catch (e: Exception) {
                last = CloudFavoritesException(CloudFailure.Parsing, "收藏响应无法解析", e)
            }
        }
        throw last ?: CloudFavoritesException(CloudFailure.Network, "拷贝漫画服务线路暂时不可用")
    }

    private fun loginHeaders(host: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "platform" to "2",
        "Origin" to "https://$host",
        "Referer" to "https://$host/web/login/loginByAccount?url=person%2Fhome",
        "Cookie" to "webp=1",
    )

    private fun stableId(key: String): String = preferences.getString("copy_$key", null) ?: run {
        val random = SecureRandom()
        val value = when (key) {
            "deviceinfo" -> "${1000000 + random.nextInt(9000000)}V-${1000 + random.nextInt(9000)}"
            "device" -> "MN${random.nextInt(10)}.${random.nextInt(1000000)}.${random.nextInt(1000)}"
            else -> (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)] }.joinToString("")
        }
        preferences.edit().putString("copy_$key", value).apply()
        value
    }
}

