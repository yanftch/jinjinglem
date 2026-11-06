package cn.jinjingle.android

import android.app.Application
import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.location.AMapLocationClient
import com.amap.api.services.core.ServiceSettings
import java.util.UUID

class JinjingleApp : Application() {
    companion object { lateinit var instance: JinjingleApp; private set }
    override fun onCreate() {
        super.onCreate()
        instance = this
        updateMapPrivacy(privacyAccepted())
    }
    fun privacyAccepted(): Boolean = getSharedPreferences("jinjingle_privacy", 0).getBoolean("agreed", false)
    fun acceptPrivacy() {
        getSharedPreferences("jinjingle_privacy", 0).edit().putBoolean("agreed", true).apply()
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
    fun logout(context: Context) = saveToken(context, "")
    fun nickname(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("nickname", "出行访客") ?: "出行访客"
    fun saveNickname(context: Context, name: String) = context.getSharedPreferences(PREFS, 0).edit().putString("nickname", name).apply()
    fun seenCamera(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong("seen_camera", 0)
    fun setSeenCamera(context: Context, id: Long) = context.getSharedPreferences(PREFS, 0).edit().putLong("seen_camera", id).apply()
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}
