package app.manyuenext.cloud.picacg

import eu.kanade.tachiyomi.source.cloud.CloudFavorite
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Locale

object PicacgApi {
    private const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    private val API_SECRET = "~d}" + '$' + "Q7" + '$' + "eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"

    fun signature(pathAndQuery: String, time: String, nonce: String, method: String): String {
        val canonical = (pathAndQuery.removePrefix("/") + time + nonce + method + API_KEY).lowercase(Locale.ROOT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun loginToken(body: String): String {
        val root = JSONObject(body)
        val data = root.optJSONObject("data")
        return sequenceOf(root.optString("token"), data?.optString("token").orEmpty())
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .also { require(it.isNotBlank()) { "PICACG 登录响应没有令牌" } }
    }

    fun parseFavorites(body: String, page: Int): CloudFavoritesPage {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException("PICACG response is not JSON", it)
        }
        val data = root.optJSONObject("data") ?: root
        val comics = data.optJSONObject("comics")
        val values = comics?.optJSONArray("docs") ?: data.optJSONArray("docs") ?: data.optJSONArray("list") ?: JSONArray()
        val items = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("_id", item.optString("id")).trim()
                val title = item.optString("title", item.optString("name")).trim()
                if (id.isBlank() || title.isBlank()) continue
                val thumbObject = item.optJSONObject("thumb")
                val fileServer = thumbObject?.optString("fileServer").orEmpty().trim()
                val path = thumbObject?.optString("path").orEmpty().trim().trimStart('/')
                val thumb = thumbnailUrl(fileServer, path)
                add(CloudFavorite(id, "https://picaapi.picacomic.com/comics/$id", title, thumb))
            }
        }
        val pages = comics?.optInt("pages", data.optInt("pages", page)) ?: data.optInt("pages", page)
        return CloudFavoritesPage(page, items, page < pages)
    }

    private fun thumbnailUrl(fileServer: String, path: String): String? {
        if (fileServer.isBlank()) return null
        val absolute = fileServer.startsWith("http://") || fileServer.startsWith("https://")
        if (path.isNotBlank()) {
            val base = if (absolute) fileServer.trimEnd('/') else "https://picaapi.picacomic.com/${fileServer.trim('/')}"
            return if (base.endsWith("/static")) "$base/$path" else "$base/static/$path"
        }
        return if (absolute) fileServer else "https://picaapi.picacomic.com/static/${fileServer.trimStart('/')}"
    }
}

