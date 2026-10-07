package ai.jarvis.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ============================  SECURE STORE  ============================
 * Credentials (the AI API key) must NOT sit in plain SharedPreferences —
 * on a rooted or backed-up device that is readable.
 *
 * This stores values AES/GCM-encrypted with a key that lives in the Android
 * Keystore and never leaves it. The ciphertext + IV go to SharedPreferences;
 * the key material stays inside the TEE/StrongBox where the OS protects it.
 * ======================================================================
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT requiring user auth: JARVIS must be able to
                // read its key in the background. Add setUserAuthenticationRequired(true)
                // if you want the key unlocked per-session instead.
                .build()
        )
        return generator.generateKey()
    }

    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(key), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun get(key: String): String? {
        val ciphertext = prefs.getString(key, null) ?: return null
        val iv = prefs.getString(ivKey(key), null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key rotated / data restored from another device — treat as absent.
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).remove(ivKey(key)).apply()
    }

    private fun ivKey(key: String) = "$key.iv"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "jarvis_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
