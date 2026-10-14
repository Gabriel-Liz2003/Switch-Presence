package dev.gabriel.switchpresence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Presence URLs are bearer-like identifiers; do not put them in plain SharedPreferences. */
class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("switch_presence_settings", Context.MODE_PRIVATE)
    private val alias = "switch_presence_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    var presenceUrl: String
        get() {
            return try {
                val raw = prefs.getString("encrypted_url", null) ?: return ""
                val bytes = Base64.decode(raw, Base64.NO_WRAP)
                require(bytes.size > 12)
                val iv = bytes.copyOfRange(0, 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
            } catch (_: Exception) { "" }
        }
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            prefs.edit().putString("encrypted_url", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        }

    var discordId: String
        get() = prefs.getString("discord_id", "") ?: ""
        set(value) { prefs.edit().putString("discord_id", value).apply() }

    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) { prefs.edit().putBoolean("running", value).apply() }

    var status: String
        get() = prefs.getString("status", "Aguardando configuração") ?: ""
        set(value) { prefs.edit().putString("status", value.take(240)).apply() }
}
