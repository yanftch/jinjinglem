package cn.jinjingle.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ContactActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)
        findViewById<TextView>(R.id.deviceId).text = Session.deviceId(this)
        findViewById<View>(R.id.copyDeviceId).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("进京了设备标识", Session.deviceId(this)))
            toast("设备标识已复制")
        }
        findViewById<View>(R.id.retryQr).setOnClickListener { loadQr() }
        loadQr()
    }

    private fun loadQr() {
        val status = findViewById<TextView>(R.id.qrStatus)
        val image = findViewById<ImageView>(R.id.wechatQr)
        status.text = "正在加载微信二维码…"
        image.visibility = View.GONE
        Api.contactQr(this) { result ->
            result.onSuccess {
                image.setImageBitmap(it)
                image.visibility = View.VISIBLE
                status.text = "请扫码添加微信，付款后发送设备标识，由管理员开通会员。"
            }.onFailure {
                status.text = it.message ?: "二维码加载失败，请稍后重试"
            }
        }
    }
}
