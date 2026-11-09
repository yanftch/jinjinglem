package com.jinjingle.and

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MemberActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_member)
        findViewById<TextView>(R.id.deviceId).text = "设备标识：${Session.deviceId(this)}\n长按可复制给管理员"
        findViewById<android.view.View>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<android.view.View>(R.id.openMembership).setOnClickListener { open(ContactActivity::class.java) }
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        val status = findViewById<TextView>(R.id.status)
        status.text = "正在查询设备会员状态…"
        DeviceAccount.status(this) { result ->
            result.onSuccess { user ->
                status.text = if (user.optBoolean("active"))
                    "会员有效\n到期时间：${user.string("memberExpiresAt").beijingTime()}"
                else "尚未开通会员或已到期\n请联系管理员开通 1 个月或 1 年会员"
            }.onFailure { status.text = "查询失败：${it.message ?: "请检查网络"}" }
        }
    }
}
