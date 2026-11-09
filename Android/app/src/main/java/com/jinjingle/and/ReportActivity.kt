package com.jinjingle.and

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ReportActivity : AppCompatActivity() {
    private var screenshot: Uri? = null
    private var bytes: Long = 0
    private var submitting = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        findViewById<EditText>(R.id.nickname).setText(Session.nickname(this).takeUnless { it == "出行访客" } ?: "")
        findViewById<android.view.View>(R.id.pickImage).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 11)
        }
        findViewById<android.view.View>(R.id.removeImage).setOnClickListener { screenshot = null; bytes = 0; showImage() }
        findViewById<android.view.View>(R.id.submit).setOnClickListener { submit() }
    }
    @Deprecated("Replaced by Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 11 || resultCode != Activity.RESULT_OK) return
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
        if (size <= 0 || size > 1024 * 1024) { toast("截图不能超过 1MB，请重新选择"); return }
        screenshot = uri; bytes = size; showImage()
    }
    private fun showImage() {
        findViewById<TextView>(R.id.imageStatus).text = if (screenshot == null) "截图选填，不超过 1MB" else "已选择截图：${bytes / 1024} KB"
    }
    private fun submit() {
        if (submitting) return
        val nickname = findViewById<EditText>(R.id.nickname).text.toString().trim()
        val content = findViewById<EditText>(R.id.content).text.toString().trim()
        if (nickname.isBlank() || nickname.length > 30 || content.isBlank() || content.length > 2000) {
            toast("请填写昵称和探头位置线索"); return
        }
        if (screenshot != null && (bytes <= 0 || bytes > 1024 * 1024)) { toast("截图不能超过 1MB"); return }
        submitting = true
        Api.uploadReport(this, nickname, content, screenshot) { result ->
            submitting = false
            result.onSuccess {
                Session.saveNickname(this, nickname)
                findViewById<EditText>(R.id.content).text.clear()
                screenshot = null; bytes = 0; showImage()
                AlertDialog.Builder(this).setTitle("上报成功")
                    .setMessage("上报编号 #${it.optLong("id")}，信息已提交，审核通过后才会新增探头。")
                    .setPositiveButton("知道了", null).show()
            }.onFailure { toast(it.message ?: "提交失败") }
        }
    }
}
