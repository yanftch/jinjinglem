package cn.jinjingle.android

import android.app.Activity
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class ApiException(val status: Int, message: String) : Exception(message)

object Api {
    private val executor = Executors.newFixedThreadPool(6)
    private val base = BuildConfig.API_BASE_URL

    fun get(activity: Activity, path: String, auth: Boolean = false, done: (Result<Any>) -> Unit) =
        call(activity, "GET", path, null, auth, done)

    fun post(activity: Activity, path: String, body: JSONObject, auth: Boolean = false, done: (Result<Any>) -> Unit) =
        call(activity, "POST", path, body, auth, done)

    fun call(activity: Activity, method: String, path: String, body: JSONObject?, auth: Boolean, done: (Result<Any>) -> Unit) {
        executor.execute {
            val result = runCatching { request(activity, method, path, body, auth) }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) done(result) }
        }
    }

    private fun request(context: Context, method: String, path: String, body: JSONObject?, auth: Boolean): Any {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = if (path.startsWith("/api/v1/routes")) 170000 else 30000
            connection.setRequestProperty("Accept", "application/json")
            if (auth) {
                val token = Session.token(context)
                if (token.isBlank()) throw ApiException(401, "设备会话尚未就绪，请稍后重试")
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            if (status !in 200..299) {
                val message = (parsed as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "服务请求失败（$status）"
                throw ApiException(status, message)
            }
            return parsed ?: JSONObject()
        } finally { connection.disconnect() }
    }

    fun uploadReport(activity: Activity, nickname: String, content: String, image: Uri?, done: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val boundary = "jinjingle-${System.currentTimeMillis()}"
                val connection = URL("$base/api/v1/camera-reports").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    connection.outputStream.use { out ->
                        fun field(name: String, value: String) {
                            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                        }
                        field("nickname", nickname)
                        field("content", content)
                        if (image != null) {
                            val bytes = activity.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: error("无法读取截图")
                            if (bytes.size > 1024 * 1024) error("截图不能超过 1MB")
                            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"screenshot\"; filename=\"report.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                            out.write(bytes)
                            out.write("\r\n".toByteArray())
                        }
                        out.write("--$boundary--\r\n".toByteArray())
                    }
                    val status = connection.responseCode
                    val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    val response = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    if (status !in 200..299) throw ApiException(status, response.optString("error", "上报失败（$status）"))
                    response
                } finally { connection.disconnect() }
            }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) done(result) }
        }
    }

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    fun array(value: Any): JSONArray = value as? JSONArray ?: JSONArray()
    fun obj(value: Any): JSONObject = value as? JSONObject ?: JSONObject()
}
