package com.remotecontrol.controller

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session 10b: pairing tokens at rest. AES-256-GCM with a key in the Android Keystore (never leaves it), stored as
 * `enc1:<base64 iv+ciphertext>`. [decrypt] returns values without the prefix unchanged, so tokens saved by Session 2-10a
 * still work and are upgraded the next time they are saved through [encrypt]. If the Keystore key is lost (restore to a
 * new phone), [decrypt] returns null: treat the device as unpaired and pair again.
 * Hook: `DeviceStore` (not in the upload) writes `TokenVault.encrypt(token)` and reads `TokenVault.decrypt(stored)`.
 */
object TokenVault {
    private const val ALIAS = "rc_controller_token_key"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    /** Encrypted form of [plain]; on any Keystore problem the plain text is returned (better a working pairing than a lost one). */
    fun encrypt(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (_: Exception) { plain }

    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
}
