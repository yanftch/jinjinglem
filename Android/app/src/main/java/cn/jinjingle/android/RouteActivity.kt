package cn.jinjingle.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import org.json.JSONArray
import org.json.JSONObject

class RouteActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var map: AMap
    private val handler = Handler(Looper.getMainLooper())
    private var runId = 0
    private var planning = false
    private var checkingMembership = false
    private var memberActive = false
    private var start: Place? = null
    private var end: Place? = null
    private val waypoints = mutableListOf<Place>()
    private var results = mutableMapOf<String, RouteOutcome>()
    private val licences = listOf("无任何证件", "六环外进京证", "六环内进京证")
    private val preferences = listOf("速度最快", "距离最短", "不走高速")
    private data class RouteOutcome(val result: JSONObject? = null, val error: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route)
        mapView = findViewById(R.id.map); mapView.onCreate(savedInstanceState); map = mapView.map
        map.uiSettings.isZoomControlsEnabled = true
        map.moveCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(LatLng(39.909088, 116.397643), 12f))
        findViewById<Spinner>(R.id.licence).adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, licences)
        findViewById<Spinner>(R.id.preference).adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, preferences)
        findViewById<View>(R.id.start).setOnClickListener { pick(1) }
        findViewById<View>(R.id.end).setOnClickListener { pick(2) }
        findViewById<View>(R.id.waypoint).setOnClickListener { if (waypoints.size < 5) pick(3) else toast("最多添加 5 个途经点") }
        findViewById<View>(R.id.swap).setOnClickListener {
            if (planning || checkingMembership) return@setOnClickListener
            val old = start; start = end; end = old; refreshPoints()
        }
        findViewById<View>(R.id.memberStatus).setOnClickListener { open(MemberActivity::class.java) }
        findViewById<View>(R.id.plan).setOnClickListener { plan() }
        findViewById<View>(R.id.compare).setOnClickListener { showComparison() }
        initLocation()
    }
    override fun onResume() { super.onResume(); mapView.onResume(); refreshMember() }
    private fun refreshMember() {
        DeviceAccount.status(this) { result ->
            result.onSuccess { member ->
                memberActive = member.optBoolean("active")
                findViewById<TextView>(R.id.memberStatus).text = if (memberActive)
                    "会员有效期至 ${member.string("memberExpiresAt").beijingTime()} ›" else "Android 会员未开通或已到期 ›"
            }.onFailure {
                memberActive = false
                findViewById<TextView>(R.id.memberStatus).text = "会员状态获取失败，点击查看 ›"
            }
        }
    }
    private fun initLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 22)
            return
        }
        map.isMyLocationEnabled = true
        handler.postDelayed({
            if (start == null) map.myLocation?.let {
                start = Place("当前位置", LatLng(it.latitude, it.longitude)); refreshPoints()
            }
        }, 2500)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initLocation()
    }
    private fun pick(request: Int) {
        if (planning || checkingMembership) return
        startActivityForResult(Intent(this, PlacePickerActivity::class.java), request)
    }
    @Deprecated("Replaced by Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        val place = Place(data.getStringExtra("name") ?: "地图选点", LatLng(data.getDoubleExtra("latitude", 0.0), data.getDoubleExtra("longitude", 0.0)))
        when (requestCode) { 1 -> start = place; 2 -> end = place; 3 -> waypoints.add(place) }
        refreshPoints()
    }
    private fun refreshPoints() {
        runId++
        results.clear()
        findViewById<View>(R.id.compare).visibility = View.GONE
        findViewById<Button>(R.id.start).text = "起点：${start?.name ?: "点击选择"}"
        findViewById<Button>(R.id.end).text = "终点：${end?.name ?: "点击选择"}"
        findViewById<TextView>(R.id.status).text = "地点已更新，请重新规划"
        val box = findViewById<LinearLayout>(R.id.waypoints); box.removeAllViews()
        waypoints.toList().forEachIndexed { index, place ->
            val button = Button(this).apply { text = "途经点 ${index + 1}：${place.name}　删除" }
            button.setOnClickListener { if (!planning && !checkingMembership) { waypoints.removeAt(index); refreshPoints() } }
            box.addView(button)
        }
        map.clear()
        val points = listOfNotNull(start, *waypoints.toTypedArray(), end)
        points.forEach { map.addMarker(MarkerOptions().position(it.point).title(it.name)) }
        map.fit(points.map { it.point })
    }
    private fun plan() {
        if (planning || checkingMembership) return
        val from = start; val to = end
        if (from == null || to == null) { toast("请先选择起点和终点"); return }
        checkingMembership = true
        findViewById<TextView>(R.id.status).text = "正在核验会员身份…"
        DeviceAccount.status(this) { result ->
            checkingMembership = false
            result.onSuccess { user ->
                memberActive = user.optBoolean("active")
                if (memberActive) startPlanning(from, to)
                else { findViewById<TextView>(R.id.status).text = "Android 会员未开通或已到期"; open(MemberActivity::class.java) }
            }.onFailure { findViewById<TextView>(R.id.status).text = it.message ?: "会员查询失败，请重试" }
        }
    }
    private fun startPlanning(from: Place, to: Place) {
        planning = true; results.clear()
        findViewById<Button>(R.id.plan).isEnabled = false
        findViewById<TextView>(R.id.status).text = "两条路线正在同时规划，最多等待 3 分钟…"
        val current = ++runId
        val common = JSONObject().put("preference", findViewById<Spinner>(R.id.preference).selectedItemPosition + 1)
            .put("startPoint", from.json()).put("endPoint", to.json())
            .put("waypoints", JSONArray().also { a -> waypoints.forEach { a.put(it.json()) } })
        val dmm = JSONObject(common.toString()).put("licence", findViewById<Spinner>(R.id.licence).selectedItemPosition + 1)
        Api.post(this, "/api/v1/routes/plan", dmm, true) { outcome ->
            if (current != runId) return@post
            outcome.onSuccess { value ->
                val result = Api.obj(value)
                if (result.optString("status") == "PENDING") poll(result.optString("requestId"), current, System.currentTimeMillis() + 180000)
                else settle("duomaomao", RouteOutcome(result), current)
            }.onFailure { settle("duomaomao", RouteOutcome(error = it.message ?: "规划失败"), current) }
        }
        Api.post(this, "/api/v1/routes/amap/plan", common, true) { outcome ->
            if (current != runId) return@post
            outcome.onSuccess { settle("amap", RouteOutcome(Api.obj(it)), current) }
                .onFailure { settle("amap", RouteOutcome(error = it.message ?: "规划失败"), current) }
        }
        handler.postDelayed({
            if (current == runId && planning) {
                if (!results.containsKey("duomaomao")) results["duomaomao"] = RouteOutcome(error = "规划超时")
                if (!results.containsKey("amap")) results["amap"] = RouteOutcome(error = "规划超时")
                finishPlanning()
            }
        }, 180000)
    }
    private fun poll(requestId: String, current: Int, deadline: Long) {
        if (requestId.isBlank()) { settle("duomaomao", RouteOutcome(error = "缺少任务编号"), current); return }
        handler.postDelayed({
            if (current != runId || !planning) return@postDelayed
            if (System.currentTimeMillis() >= deadline) { settle("duomaomao", RouteOutcome(error = "规划超时"), current); return@postDelayed }
            Api.get(this, "/api/v1/routes/result?openid=${Api.encode(requestId)}", true) { outcome ->
                if (current != runId || !planning) return@get
                outcome.onSuccess { value ->
                    val response = Api.obj(value)
                    if (response.optBoolean("isSuccess")) settle("duomaomao", RouteOutcome(response.optJSONObject("result")), current)
                    else poll(requestId, current, deadline)
                }.onFailure { settle("duomaomao", RouteOutcome(error = it.message ?: "规划失败"), current) }
            }
        }, 5000)
    }
    private fun settle(key: String, value: RouteOutcome, current: Int) {
        if (current != runId || !planning) return
        results[key] = value
        if (results.size == 2) finishPlanning()
    }
    private fun finishPlanning() {
        planning = false
        findViewById<Button>(R.id.plan).isEnabled = true
        findViewById<View>(R.id.compare).visibility = View.VISIBLE
        map.clear()
        val all = mutableListOf<LatLng>()
        listOf("duomaomao", "amap").forEach { key ->
            val result = results[key]?.result ?: return@forEach
            val reference = key == "duomaomao" && !result.optBoolean("isSuccessOnPlanRoute", true)
            val color = if (reference) Color.GRAY else if (key == "duomaomao") Color.rgb(37, 99, 235) else Color.rgb(22, 133, 106)
            val segments = result.optJSONArray("routePlanResponse") ?: JSONArray()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                val points = segment.objects().mapNotNull {
                    val lat = it.optDouble("latitude", Double.NaN); val lng = it.optDouble("longitude", Double.NaN)
                    if (lat.isFinite() && lng.isFinite()) LatLng(lat, lng) else null
                }
                if (points.size > 1) { map.addPolyline(PolylineOptions().addAll(points).color(color).width(13f)); all.addAll(points) }
            }
        }
        listOfNotNull(start, *waypoints.toTypedArray(), end).forEach { map.addMarker(MarkerOptions().position(it.point).title(it.name)) }
        if (all.isNotEmpty()) map.fit(all)
        findViewById<TextView>(R.id.status).text = if (all.isEmpty()) "两条路线均未规划成功" else "规划结束：蓝色躲猫猫，绿色高德避让，灰色仅供参考"
        showComparison()
    }
    private fun showComparison() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        listOf("duomaomao" to "躲猫猫路线", "amap" to "高德避让路线").forEach { (key, title) ->
            val outcome = results[key]
            val route = outcome?.result
            val reference = key == "duomaomao" && route != null && !route.optBoolean("isSuccessOnPlanRoute", true)
            val state = outcome?.error ?: when { route == null -> "规划超时"; reference -> "证件条件下不可行，线路仅供参考"; key == "amap" -> "已避开已收录探头"; else -> "规划完成" }
            val text = TextView(this).apply {
                textSize = 17f; setPadding(16, 20, 16, 20)
                text = "$title\n$state" + (if (route != null && !reference) "\n${route.string("distance")} · ${route.string("useTime")}" else "") +
                    if (key == "amap") "\n仅避让已收录探头，不判断进京证条件" else "\n按所选进京证条件规划"
            }
            content.addView(text)
            if (route != null) text.setOnClickListener { showSteps(title, route) }
        }
        AlertDialog.Builder(this).setTitle("两种路线规划结果").setView(content)
            .setMessage("点击结果查看导航步骤；路线及探头信息仅供参考。")
            .setPositiveButton("查看地图", null).show()
    }
    private fun showSteps(title: String, route: JSONObject) {
        val steps = route.optJSONArray("navigationSteps")?.objects()?.mapIndexed { index, step ->
            "${index + 1}. ${step.optString("instruction", "继续行驶")}"
        } ?: emptyList()
        AlertDialog.Builder(this).setTitle(title).setMessage(if (steps.isEmpty()) "暂无导航步骤" else steps.joinToString("\n\n"))
            .setPositiveButton("关闭", null).show()
    }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() { runId++; handler.removeCallbacksAndMessages(null); mapView.onDestroy(); super.onDestroy() }
}
