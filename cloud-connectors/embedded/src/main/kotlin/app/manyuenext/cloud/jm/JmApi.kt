package app.manyuenext.cloud.jm

import eu.kanade.tachiyomi.source.cloud.CloudFavorite
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JmApi {
    private const val API_SECRET = "185Hcomic3PAPP7R"
    private const val HOST_SECRET = "diosfjckwpqpdfjkvnqQjsik"
    private val HOST_FILES = listOf(
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
    )

    val hostFiles: List<String> get() = HOST_FILES

    fun hosts(body: String): List<String> {
        val decoded = decrypt(body.trim().removePrefix("\uFEFF"), md5(HOST_SECRET))
        val values = JSONObject(decoded).optJSONArray("Server") ?: throw IllegalArgumentException("JM host list missing")
        return buildList {
            for (index in 0 until values.length()) {
                val raw = values.optString(index).trim().trimEnd('/')
                if (raw.isNotBlank() && raw.matches(Regex("[A-Za-z0-9.-]+"))) add("https://$raw/")
            }
        }
    }

    fun token(timestamp: Long): String = md5("$timestamp$API_SECRET")

    fun parseFavorites(body: String, page: Int, timestamp: Long = System.currentTimeMillis() / 1000L): CloudFavoritesPage {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException("JM response is not JSON", it)
        }
        val data = decodeData(root.opt("data"), timestamp) ?: root
        val values = data.optJSONArray("list") ?: data.optJSONArray("favorites") ?: data.optJSONArray("results") ?: JSONArray()
        val items = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("id", item.optString("aid", item.optString("album_id"))).trim()
                val title = item.optString("name", item.optString("title")).trim()
                if (id.isBlank() || title.isBlank()) continue
                add(CloudFavorite(id, "/album/$id/", title, item.optString("thumb").takeIf { it.isNotBlank() }))
            }
        }
        val total = data.optInt("total", -1)
        val limit = data.optInt("limit", 24).coerceAtLeast(1)
        return CloudFavoritesPage(page, items, if (total >= 0) page * limit < total else items.size >= limit)
    }

    fun parseLogin(body: String, timestamp: Long): Pair<String, String> {
        val root = JSONObject(body)
        val data = decodeData(root.opt("data"), timestamp) ?: root
        val jwt = sequenceOf("jwttoken", "token", "jwt").map { data.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val avs = sequenceOf("s", "avs").map { data.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (jwt.isBlank()) throw IllegalArgumentException("JM login response missing token")
        return jwt to avs
    }

    fun multipart(fields: Map<String, String>, boundary: String): String = buildString {
        fields.forEach { (name, value) ->
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            append(value).append("\r\n")
        }
        append("--").append(boundary).append("--\r\n")
    }

    private fun decodeData(raw: Any?, timestamp: Long): JSONObject? {
        if (raw is JSONObject) return raw
        if (raw !is String || raw.isBlank()) return null
        val decoded = decrypt(raw.trim(), md5("$timestamp$API_SECRET"))
        return JSONObject(decoded)
    }

    private fun decrypt(raw: String, keyText: String): String {
        val key = keyText.toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return String(cipher.doFinal(Base64.decode(raw, Base64.DEFAULT)), StandardCharsets.UTF_8).trim()
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

