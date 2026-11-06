package cn.jinjingle.android

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        findViewById<android.view.View>(R.id.editName).setOnClickListener { editName() }
        findViewById<android.view.View>(R.id.member).setOnClickListener { open(MemberActivity::class.java) }
        findViewById<android.view.View>(R.id.route).setOnClickListener { open(RouteActivity::class.java) }
        findViewById<android.view.View>(R.id.report).setOnClickListener { open(ReportActivity::class.java) }
        findViewById<android.view.View>(R.id.traffic).setOnClickListener {
            startActivity(Intent(this, WebPageActivity::class.java).putExtra("title", "限行说明")
                .putExtra("url", "https://jtgl.beijing.gov.cn/jgj/lszt/659722/659725/index.html"))
        }
        findViewById<android.view.View>(R.id.help).setOnClickListener {
            AlertDialog.Builder(this).setTitle("使用说明").setMessage("在首页搜索道路或路口，点击地图上的探头可查看详情和发表评论。路线规划可选择起终点及途经点。地图点位仅供参考，实际通行请以现场信息为准。")
                .setPositiveButton("知道了", null).show()
        }
        findViewById<android.view.View>(R.id.about).setOnClickListener {
            AlertDialog.Builder(this).setTitle("关于进京了").setMessage("进京了提供探头位置查询与路线规划。数据持续更新，详情请以现场信息为准。网站：jinjingle.yixiangshe.com")
                .setPositiveButton("知道了", null).show()
        }
        findViewById<android.view.View>(R.id.share).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "进京了 · 探头分布与路线规划\nhttps://jinjingle.yixiangshe.com"), "分享进京了"))
        }
    }
    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.name).text = Session.nickname(this)
        if (Session.token(this).isNotBlank()) Api.get(this, "/api/v1/members/me", true) { result ->
            result.onSuccess {
                val member = Api.obj(it).optJSONObject("member")
                findViewById<TextView>(R.id.memberStatus).text = if (member?.optBoolean("active") == true)
                    "会员有效期至 ${member.string("expiresAt").beijingTime()}" else "查看试用、套餐和订单"
            }
        }
    }
    private fun editName() {
        val field = EditText(this).apply { setText(Session.nickname(this@ProfileActivity)); filters = arrayOf(android.text.InputFilter.LengthFilter(30)) }
        AlertDialog.Builder(this).setTitle("修改昵称").setView(field).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) toast("请输入昵称") else {
                    Session.saveNickname(this, name); findViewById<TextView>(R.id.name).text = name
                }
            }.show()
    }
}
