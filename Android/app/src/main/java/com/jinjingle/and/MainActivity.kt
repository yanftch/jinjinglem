package com.jinjingle.and

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var map: AMap
    private lateinit var search: EditText
    private lateinit var searchStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var mapRequest = 0
    private var searchRequest = 0
    private var markers = mutableMapOf<Marker, Camera>()
    private var matches = emptyList<Camera>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(application as JinjingleApp).privacyAccepted()) {
            AlertDialog.Builder(this).setTitle("地图与定位服务")
                .setMessage("进京了会使用高德地图展示探头与路线；定位权限用于当前位置和路线起点。App 还会提交生成的设备标识、厂商、型号及系统版本，用于登记设备、统计用户和保存管理员设置的会员有效期。原始 Android ID 不上传。是否同意继续？")
                .setNegativeButton("暂不使用") { _, _ -> finish() }
                .setPositiveButton("同意并继续") { _, _ ->
                    (application as JinjingleApp).acceptPrivacy()
                    setup(savedInstanceState)
                    mapView.onResume()
                }.setCancelable(false).show()
            return
        }
        setup(savedInstanceState)
    }

    private fun setup(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)
        map = mapView.map
        map.uiSettings.isZoomControlsEnabled = true
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.909088, 116.397643), 12f))
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) = Unit
            override fun onCameraChangeFinish(position: CameraPosition?) { if (search.text.isBlank()) loadViewport() }
        })
        map.setOnMarkerClickListener { marker -> marker.showInfoWindow(); true }
        map.setOnInfoWindowClickListener { marker -> markers[marker]?.let(::openCamera) }
        search = findViewById(R.id.search)
        searchStatus = findViewById(R.id.searchStatus)
        searchStatus.setOnClickListener {
            if (matches.isEmpty()) return@setOnClickListener
            val shown = matches.take(30)
            AlertDialog.Builder(this).setTitle("搜索结果（${matches.size}）")
                .setItems(shown.map { "${it.name} · ${it.typeName}" }.toTypedArray()) { _, index ->
                    val camera = shown[index]
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(camera.point, 17f))
                    search.clearFocus()
                    openCamera(camera)
                }.setNegativeButton("关闭", null).show()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                val keyword = s.toString().trim()
                if (keyword.isEmpty()) { matches = emptyList(); searchStatus.visibility = View.GONE; loadViewport(); return }
                searchRunnable = Runnable { searchCameras(keyword) }.also { handler.postDelayed(it, 300) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<View>(R.id.route).setOnClickListener { open(RouteActivity::class.java) }
        findViewById<View>(R.id.member).setOnClickListener { open(MemberActivity::class.java) }
        findViewById<View>(R.id.profile).setOnClickListener { open(ProfileActivity::class.java) }
        findViewById<View>(R.id.locate).setOnClickListener { locate() }
        DeviceAccount.startup(this) { result ->
            result.onFailure { toast(it.message ?: "设备登记失败，请检查网络") }
        }
        requestLocationPermission()
        loadLatest()
        mapView.post { loadViewport() }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
        else map.isMyLocationEnabled = true
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) map.isMyLocationEnabled = true
    }
    private fun locate() {
        val point = map.myLocation?.let { LatLng(it.latitude, it.longitude) }
        if (point == null) { requestLocationPermission(); toast("定位尚未就绪，请稍后重试"); return }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
    }
    private fun loadViewport() {
        val bounds = map.projection?.visibleRegion?.latLngBounds ?: return
        val serial = ++mapRequest
        val path = "/api/v1/cameras/map?west=${bounds.southwest.longitude}&south=${bounds.southwest.latitude}" +
            "&east=${bounds.northeast.longitude}&north=${bounds.northeast.latitude}&limit=10000"
        Api.get(this, path) { result ->
            if (serial != mapRequest || search.text.isNotBlank()) return@get
            result.onSuccess { value -> render(Api.obj(value).optJSONArray("items") ?: JSONArray()) }
                .onFailure { toast(it.message ?: "探头加载失败") }
        }
    }
    private fun searchCameras(keyword: String) {
        val serial = ++searchRequest
        Api.get(this, "/api/v1/cameras/map/search?name=${Api.encode(keyword)}") { result ->
            if (serial != searchRequest || search.text.toString().trim() != keyword) return@get
            result.onSuccess { value ->
                val rows = Api.array(value)
                render(rows)
                matches = rows.objects().mapNotNull(Camera::from)
                searchStatus.visibility = View.VISIBLE
                searchStatus.text = if (rows.length() == 0) "未找到相关探头" else "找到 ${rows.length()} 个点位；点击地图标记查看详情"
                val points = rows.objects().mapNotNull { Camera.from(it)?.point }
                if (points.isNotEmpty()) map.fit(points)
            }.onFailure { toast(it.message ?: "搜索失败") }
        }
    }
    private fun render(rows: JSONArray) {
        markers.keys.forEach { it.remove() }
        markers.clear()
        rows.objects().mapNotNull(Camera::from).forEach { camera ->
            val marker = map.addCamera(camera)
            if (marker != null) markers[marker] = camera
        }
    }
    private fun openCamera(camera: Camera) = startActivity(Intent(this, CameraActivity::class.java).putExtra("id", camera.id))
    private fun loadLatest() {
        Api.get(this, "/api/v1/cameras?offset=0&limit=20") { result ->
            val rows = result.getOrNull()?.let(Api::array)?.objects() ?: return@get
            val newestId = rows.firstOrNull()?.optLong("id") ?: return@get
            val seen = Session.seenCamera(this)
            if (newestId == seen) return@get
            val latest = (if (seen > 0 && seen < newestId) rows.filter { it.optLong("id") > seen } else rows).take(5)
            if (latest.isEmpty()) return@get
            val names = latest.map { "${it.optString("name")}  ·  ${Camera.from(it)?.typeName ?: "探头"}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("最近新增的探头")
                .setItems(names) { _, index ->
                    Session.setSeenCamera(this, newestId)
                    startActivity(Intent(this, CameraActivity::class.java).putExtra("id", latest[index].optLong("id")))
                }.setPositiveButton("查看地图") { _, _ -> Session.setSeenCamera(this, newestId) }
                .setOnCancelListener { Session.setSeenCamera(this, newestId) }.show()
        }
    }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) mapView.onSaveInstanceState(outState) }
    override fun onDestroy() { searchRunnable?.let { handler.removeCallbacks(it) }; if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy() }
}
