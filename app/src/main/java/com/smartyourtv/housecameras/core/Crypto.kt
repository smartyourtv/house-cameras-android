package com.smartyourtv.housecameras.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-256-GCM encryption for credentials at rest
 *  (the Android counterpart of the Windows sibling's DPAPI).
 *  Stored form: "v1:" + Base64(iv || ciphertext). Plaintext that does
 *  not carry the prefix (legacy/first run) passes through unchanged. */
object Crypto {
    private const val ALIAS = "housecameras-cred-key"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun protect(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    fun unprotect(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        return try {
            val body = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val iv = body.copyOfRange(0, 12)
            val ct = body.copyOfRange(12, body.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { "" }  // corrupt entry -> empty, never crash (self-repair logs at store level)
    }
}
