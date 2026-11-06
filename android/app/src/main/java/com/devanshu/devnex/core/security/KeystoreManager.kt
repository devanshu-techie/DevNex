package com.devanshu.devnex.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates a random 32-byte database passphrase once, wraps it with an AES-256-GCM key
 * that never leaves the Android Keystore, and stores only the wrapped bytes.
 */
class KeystoreManager(private val context: Context) {
    private val alias = "devnex_db_key"
    private val prefs by lazy { context.getSharedPreferences("devnex_secure", Context.MODE_PRIVATE) }

    fun databasePassphrase(): ByteArray {
        val stored = prefs.getString("wrapped", null)
        if (stored != null) {
            runCatching { return unwrap(stored) }
            // Keystore key lost (e.g. device restore): the cache is disposable, start fresh.
            context.deleteDatabase("devnex.db")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("wrapped", wrap(raw)).apply()
        return raw
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(s: String): ByteArray {
        val all = Base64.decode(s, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return c.doFinal(all.copyOfRange(12, all.size))
    }
}
