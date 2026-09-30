package app.manyuenext.cloud.copymanga

import eu.kanade.tachiyomi.source.cloud.CloudFavorite
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CopyMangaApi {
    private const val AUTH_SECRET_B64 = "M2FmMDg1OTAzMTEwMzJlZmUwNjYwNTUwYTA1NjNhNTM="
    fun offset(page: Int): Int { require(page > 0); return (page - 1) * 21 }

    fun loginToken(body: String): String {
        val root = JSONObject(body)
        val results = root.optJSONObject("results")
        return sequenceOf(
            root.optString("token"), root.optString("access_token"), root.optString("accessToken"), root.optString("jwt"),
            results?.optString("token").orEmpty(), results?.optString("access_token").orEmpty(),
        ).map(String::trim).firstOrNull { it.isNotBlank() }
            ?: throw IllegalArgumentException("CopyManga 登录响应没有令牌")
    }

    fun parseFavorites(body: String, page: Int): CloudFavoritesPage {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException("CopyManga response is not JSON", it)
        }
        val data = root.optJSONObject("data") ?: root
        val values = data.optJSONArray("results") ?: data.optJSONArray("list") ?: JSONArray()
        val items = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val nested = item.optJSONObject("comic") ?: item
                val path = nested.optString("path_word", nested.optString("slug", nested.optString("id"))).trim()
                val title = nested.optString("name", nested.optString("title")).trim()
                if (path.isBlank() || title.isBlank()) continue
                add(CloudFavorite(path, "/comic/$path", title, nested.optString("cover").takeIf { it.isNotBlank() }))
            }
        }
        val total = data.optInt("total", -1)
        val limit = data.optInt("limit", 21).coerceAtLeast(1)
        val hasNext = if (total >= 0) page * limit < total else items.size >= limit
        return CloudFavoritesPage(page, items, hasNext)
    }

    fun signature(timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(AUTH_SECRET_B64), "HmacSHA256"))
        return mac.doFinal(timestamp.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

