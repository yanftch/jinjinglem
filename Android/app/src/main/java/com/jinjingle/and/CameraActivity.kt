package com.jinjingle.and

import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import org.json.JSONObject
import java.net.URL

class CameraActivity : AppCompatActivity() {
    private var id = 0L
    private lateinit var mapView: MapView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        id = intent.getLongExtra("id", 0L)
        if (id <= 0) { finish(); return }
        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)
        mapView.map.uiSettings.setAllGesturesEnabled(false)
        findViewById<android.view.View>(R.id.writeComment).setOnClickListener { commentDialog() }
        findViewById<android.view.View>(R.id.share).setOnClickListener {
            val title = findViewById<TextView>(R.id.name).text.toString()
            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,
                    "进京了 · $title\n探头编号 $id\n${BuildConfig.API_BASE_URL}"), "分享探头"))
        }
        load()
    }
    private fun load() {
        Api.get(this, "/api/v1/cameras/$id") { result ->
            result.onSuccess { value ->
                val camera = Api.obj(value)
                val point = LatLng(camera.optDouble("latitude"), camera.optDouble("longitude"))
                findViewById<TextView>(R.id.name).text = camera.optString("name")
                val type = Camera.from(camera)?.typeName ?: "探头"
                findViewById<TextView>(R.id.info).text = "探头编号 $id · $type\n经度 ${point.longitude}  纬度 ${point.latitude}\n点位仅供参考，实际通行请以现场信息为准。"
                mapView.map.clear()
                mapView.map.addCamera(Camera(id, camera.optString("name"), point, camera.optString("cameraType")))
                mapView.map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
            }.onFailure { toast(it.message ?: "详情加载失败") }
        }
        Api.get(this, "/api/v1/cameras/$id/comments?offset=0&limit=500") { result ->
            result.onSuccess { value ->
                val parent = findViewById<LinearLayout>(R.id.comments)
                parent.removeAllViews()
                val rows = Api.array(value).objects()
                if (rows.isEmpty()) parent.addView(textRow("暂无评论"))
                rows.forEach { row -> parent.addView(textRow("${row.optString("nickname")} · ${row.optString("raw_time")}\n${row.optString("content")}")) }
            }.onFailure { toast(it.message ?: "评论加载失败") }
        }
        Api.get(this, "/api/v1/cameras/$id/images") { result ->
            result.onSuccess { value ->
                val parent = findViewById<LinearLayout>(R.id.images)
                parent.removeAllViews()
                val rows = Api.array(value).objects()
                if (rows.isEmpty()) parent.addView(textRow("暂无图片"))
                rows.forEach { row ->
                    val url = row.optString("url").let { if (it.startsWith("/")) BuildConfig.API_BASE_URL + it else it }
                    val image = ImageView(this).apply { adjustViewBounds = true; maxHeight = 500; contentDescription = "探头图片" }
                    parent.addView(image, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    if (url.startsWith("https://")) Thread {
                        val bitmap = runCatching { URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                        runOnUiThread { if (!isDestroyed && bitmap != null) image.setImageBitmap(bitmap) }
                    }.start() else parent.addView(textRow("图片地址不支持安全加载：$url"))
                    image.setOnClickListener { AlertDialog.Builder(this).setView(ImageView(this).apply { image.drawable?.let(::setImageDrawable); adjustViewBounds = true }).setPositiveButton("关闭", null).show() }
                }
            }
        }
    }
    private fun textRow(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f; setPadding(0, 12, 0, 12)
    }
    private fun commentDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 0) }
        val name = EditText(this).apply { hint = "昵称"; setText(Session.nickname(this@CameraActivity)); filters = arrayOf(android.text.InputFilter.LengthFilter(30)) }
        val content = EditText(this).apply { hint = "评论内容"; minLines = 4; gravity = android.view.Gravity.TOP; filters = arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        fields.addView(name); fields.addView(content)
        val dialog = AlertDialog.Builder(this).setTitle("发表评论").setView(fields).setNegativeButton("取消", null)
            .setPositiveButton("提交", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nickname = name.text.toString().trim(); val message = content.text.toString().trim()
                if (nickname.isBlank() || message.isBlank()) { toast("请填写昵称和评论"); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Api.post(this, "/api/v1/cameras/$id/comments", JSONObject().put("nickname", nickname).put("content", message)) { result ->
                    result.onSuccess { Session.saveNickname(this, nickname); dialog.dismiss(); toast("评论已提交"); load() }
                        .onFailure { toast(it.message ?: "提交失败"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) mapView.onSaveInstanceState(outState) }
    override fun onDestroy() { if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy() }
}
