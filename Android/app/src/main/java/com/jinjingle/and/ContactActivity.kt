package com.jinjingle.and

import android.Manifest
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ContactActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)
        findViewById<TextView>(R.id.deviceId).text = Session.deviceId(this)
        findViewById<ImageView>(R.id.wechatQr).setOnLongClickListener {
            saveQrToAlbum()
            true
        }
        findViewById<View>(R.id.copyDeviceId).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("进京了设备标识", Session.deviceId(this)))
            toast("设备标识已复制")
        }
    }

    private fun saveQrToAlbum() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), SAVE_PERMISSION_REQUEST)
            return
        }
        Thread {
            val result = runCatching { writeQrToAlbum() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed)
                    toast(if (result.isSuccess) "二维码已保存到相册，请在微信扫一扫中从相册识别" else "保存失败：${result.exceptionOrNull()?.message ?: "请重试"}")
            }
        }.start()
    }

    private fun writeQrToAlbum() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "进京了_客服微信_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/进京了")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册图片")
        try {
            val output = contentResolver.openOutputStream(uri) ?: error("无法写入相册")
            output.use { out -> resources.openRawResource(R.drawable.wechat_qr).use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SAVE_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) saveQrToAlbum()
            else toast("需要相册写入权限才能保存二维码")
        }
    }

    companion object {
        private const val SAVE_PERMISSION_REQUEST = 27
    }
}
