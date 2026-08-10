package com.androidspect.server.routes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts strings using AES-256-GCM with a key stored in the Android
 * Keystore. Shared by every route module that persists a user-supplied secret
 * on disk (AI provider API keys, the YesWeHack JWT, ...). Each caller passes
 * its own key alias so secrets stay isolated per feature.
 *
 * Wire format: [IV_LENGTH(1 byte)][IV][ciphertext]
 */
internal object KeystoreCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALGO     = "AES/GCM/NoPadding"
    private const val GCM_TAG  = 128

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).also { it.load(null) }
        ks.getKey(alias, null)?.let { return it as SecretKey }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).also {
            it.init(spec)
        }.generateKey()
    }

    fun encrypt(plaintext: String, keyAlias: String): ByteArray {
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    fun decrypt(data: ByteArray, keyAlias: String): String {
        val ivLen = data[0].toInt() and 0xFF
        val iv = data.copyOfRange(1, 1 + ivLen)
        val ciphertext = data.copyOfRange(1 + ivLen, data.size)
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(keyAlias), GCMParameterSpec(GCM_TAG, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}
