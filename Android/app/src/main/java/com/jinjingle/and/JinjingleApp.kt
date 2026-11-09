package com.jinjingle.and

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.amap.api.maps.MapsInitializer
import com.amap.api.location.AMapLocationClient
import com.amap.api.services.core.ServiceSettings
import java.security.MessageDigest
import java.security.SecureRandom

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
    fun token(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("token", "") ?: ""
    fun saveToken(context: Context, token: String) = context.getSharedPreferences(PREFS, 0).edit().putString("token", token).apply()
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
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getString("device_secret", null) ?: randomHex().also {
            prefs.edit().putString("device_secret", it).apply()
        }
    }
    private fun randomHex(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
