package cn.jinjingle.android

import android.app.Activity
import org.json.JSONObject

object DeviceAccount {
    fun register(activity: Activity, done: (Result<JSONObject>) -> Unit) {
        val body = JSONObject().put("deviceId", Session.deviceId(activity))
            .put("deviceSecret", Session.deviceSecret(activity))
        Api.post(activity, "/api/v1/android/users/register", body) { result ->
            done(result.map { value ->
                val response = Api.obj(value)
                val token = response.optString("token")
                if (token.isBlank()) error("设备登记响应缺少会话")
                Session.saveToken(activity, token)
                response.optJSONObject("user") ?: JSONObject()
            })
        }
    }

    fun status(activity: Activity, done: (Result<JSONObject>) -> Unit) {
        if (Session.token(activity).isBlank()) {
            register(activity) { result -> if (result.isSuccess) fetch(activity, false, done) else done(result) }
        } else fetch(activity, true, done)
    }

    private fun fetch(activity: Activity, canRetry: Boolean, done: (Result<JSONObject>) -> Unit) {
        Api.get(activity, "/api/v1/android/users/me", true) { result ->
            val error = result.exceptionOrNull()
            if (canRetry && error is ApiException && error.status == 401) {
                register(activity) { registered -> if (registered.isSuccess) fetch(activity, false, done) else done(registered) }
            } else done(result.map { Api.obj(it).optJSONObject("user") ?: JSONObject() })
        }
    }
}
