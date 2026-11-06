package cn.jinjingle.android

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MemberActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_member)
        findViewById<android.view.View>(R.id.login).setOnClickListener { login() }
        findViewById<android.view.View>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<android.view.View>(R.id.logout).setOnClickListener { Session.logout(this); refresh() }
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun login() {
        val code = findViewById<EditText>(R.id.code).text.toString().trim().uppercase()
        if (code.isEmpty()) { toast("请填写小程序生成的动态码"); return }
        val body = JSONObject().put("code", code).put("deviceId", Session.deviceId(this)).put("deviceName", "Android")
        Api.post(this, "/api/v1/members/app-login", body) { result ->
            result.onSuccess { value ->
                val token = Api.obj(value).optString("token")
                if (token.isBlank()) { toast("登录响应缺少会话"); return@onSuccess }
                Session.saveToken(this, token); findViewById<EditText>(R.id.code).text.clear()
                toast("会员账号已登录"); refresh()
            }.onFailure { toast(it.message ?: "登录失败") }
        }
    }
    private fun refresh() {
        val status = findViewById<TextView>(R.id.status)
        val orders = findViewById<LinearLayout>(R.id.orders)
        orders.removeAllViews()
        if (Session.token(this).isBlank()) { status.text = "尚未登录。请在小程序“会员服务”生成 10 分钟动态码，再填入此处。"; return }
        status.text = "正在查询会员状态…"
        Api.get(this, "/api/v1/members/me", true) { result ->
            result.onSuccess { value ->
                val payload = Api.obj(value)
                val member = payload.optJSONObject("member") ?: JSONObject()
                status.text = (if (member.optBoolean("active")) "会员有效" else "会员已到期") +
                    "\n权益到期：${member.string("expiresAt").beijingTime()}\n首登赠送至：${member.string("welcomeExpiresAt").beijingTime()}\n试用至：${member.string("trialExpiresAt").beijingTime()}"
                val rows = payload.optJSONArray("orders")?.objects() ?: emptyList()
                if (rows.isEmpty()) orders.addView(TextView(this).apply { text = "暂无订单"; setPadding(0, 12, 0, 12) })
                rows.forEach { row -> orders.addView(TextView(this).apply {
                    val state = when (row.optString("status")) { "PENDING" -> "待支付"; "PAID" -> "已开通"; "REFUNDED" -> "已退款"; "CLOSED" -> "已关闭"; else -> "状态未知" }
                    text = "${if (row.optString("plan_code") == "MONTH") "月会员" else "年会员"} · $state\n${row.string("order_no")}  ${row.string("created_at").beijingTime()}"
                    setPadding(0, 12, 0, 12)
                }) }
            }.onFailure { status.text = it.message ?: "会员状态获取失败" }
        }
    }
}
