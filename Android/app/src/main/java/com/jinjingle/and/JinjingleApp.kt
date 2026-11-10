package com.jinjingle.and

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.amap.api.maps.MapsInitializer
import com.amap.api.location.AMapLocationClient
import com.amap.api.services.core.ServiceSettings
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class JinjingleApp : Application() {
    companion object { lateinit var instance: JinjingleApp; private set }
    override fun onCreate() {
        super.onCreate()
        instance = this
        updateMapPrivacy(privacyAccepted())
    }
    fun privacyAccepted(): Boolean = getSharedPreferences("jinjingle_privacy", 0).getBoolean("agreed_device_profile_v2", false)
    fun acceptPrivacy() {
        getSharedPreferences("jinjingle_privacy", 0).edit().putBoolean("agreed_device_profile_v2", true).apply()
        updateMapPrivacy(true)
    }
    private fun updateMapPrivacy(agreed: Boolean) {
        MapsInitializer.updatePrivacyShow(this, agreed, agreed)
        MapsInitializer.updatePrivacyAgree(this, agreed)
        AMapLocationClient.updatePrivacyShow(this, agreed, agreed)
        AMapLocationClient.updatePrivacyAgree(this, agreed)
        ServiceSettings.updatePrivacyShow(this, agreed, agreed)
        ServiceSettings.updatePrivacyAgree(this, agreed)
    }
}

object Session {
    private const val PREFS = "jinjingle_user"
    private const val KEY_ALIAS = "jinjingle_session_key_v1"
    fun token(context: Context): String = readSecret(context, "token")
    fun saveToken(context: Context, token: String) = writeSecret(context, "token", token)
    fun deviceProfileUploaded(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("device_profile_uploaded_v1", false)
    fun markDeviceProfileUploaded(context: Context) = context.getSharedPreferences(PREFS, 0).edit().putBoolean("device_profile_uploaded_v1", true).apply()
    fun nickname(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("nickname", "出行访客") ?: "出行访客"
    fun saveNickname(context: Context, name: String) = context.getSharedPreferences(PREFS, 0).edit().putString("nickname", name).apply()
    fun seenCamera(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong("seen_camera", 0)
    fun setSeenCamera(context: Context, id: Long) = context.getSharedPreferences(PREFS, 0).edit().putLong("seen_camera", id).apply()
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getString("device_id", null) ?: run {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val installationSalt = randomHex()
            val id = sha256("${context.packageName}:$androidId:$installationSalt")
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }
    fun deviceSecret(context: Context): String {
        return readSecret(context, "device_secret").ifBlank {
            randomHex().also { writeSecret(context, "device_secret", it) }
        }
    }
    private fun readSecret(context: Context, name: String): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val stored = prefs.getString(name, null) ?: return ""
        if (!stored.startsWith("enc:v1:")) {
            // Upgrade credentials created by older app versions in place.
            writeSecret(context, name, stored)
            return stored
        }
        return runCatching {
            val packed = Base64.decode(stored.removePrefix("enc:v1:"), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(encrypted), Charsets.UTF_8)
            }
        }.getOrDefault("")
    }
    private fun writeSecret(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = "enc:v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, 0).edit().putString(name, encoded).apply()
    }
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
    private fun randomHex(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
