package cn.jinjingle.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.AMapException
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult

class PlacePickerActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var selected: TextView
    private var point = LatLng(39.909088, 116.397643)
    private var name = "地图选点"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_place_picker)
        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)
        selected = findViewById(R.id.selected)
        mapView.map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 13f))
        mapView.map.setOnMapClickListener { choose(it, "地图选点") }
        val search = findViewById<EditText>(R.id.search)
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchRoad(search.text.toString().trim()); true } else false
        }
        findViewById<View>(R.id.confirm).setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra("name", name)
                .putExtra("latitude", point.latitude).putExtra("longitude", point.longitude))
            finish()
        }
    }
    private fun choose(location: LatLng, title: String) {
        point = location; name = title
        mapView.map.clear()
        mapView.map.addMarker(MarkerOptions().position(point).title(name))
        selected.text = "$name · ${point.longitude}, ${point.latitude}"
    }
    private fun searchRoad(keyword: String) {
        if (keyword.isBlank()) return
        val status = findViewById<TextView>(R.id.searchResult)
        status.visibility = View.VISIBLE; status.text = "正在搜索…"
        val geocoder = GeocodeSearch(this)
        geocoder.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
            override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                val first = result?.geocodeAddressList?.firstOrNull()
                if (code != 1000 || first == null) { status.text = "没有找到地点，请更换关键词或在地图上点选"; return }
                val location = LatLng(first.latLonPoint.latitude, first.latLonPoint.longitude)
                choose(location, first.formatAddress ?: keyword)
                mapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
                status.text = "已定位到 ${first.formatAddress}，可继续拖动地图并点选准确位置"
            }
            override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) = Unit
        })
        geocoder.getFromLocationNameAsyn(GeocodeQuery(keyword, "北京"))
    }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}
