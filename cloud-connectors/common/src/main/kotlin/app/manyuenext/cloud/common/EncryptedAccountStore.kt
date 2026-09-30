package app.manyuenext.cloud.common

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

class EncryptedAccountStore(private val alias: String) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun read(preferences: android.content.SharedPreferences): String? {
        val encoded = preferences.getString(KEY, null) ?: return null
        return try {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Treat stale/corrupt ciphertext as an absent session. This lets
            // the host offer a fresh login instead of crashing the connector.
            preferences.edit().remove(KEY).apply()
            null
        }
    }

    fun write(preferences: android.content.SharedPreferences, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        preferences.edit().putString(KEY, "$iv:$payload").apply()
    }

    fun clear(preferences: android.content.SharedPreferences) = preferences.edit().remove(KEY).apply()

    private fun key(): java.security.Key {
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private companion object {
        const val KEY = "cloud_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
