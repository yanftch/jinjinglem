package com.jinjingle.and

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Camera(val id: Long, val name: String, val point: LatLng, val type: String) {
    companion object {
        fun from(value: JSONObject): Camera? {
            val lat = value.optDouble("latitude", Double.NaN)
            val lng = value.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lng.isFinite()) return null
            if (value.has("coordinate_system") && value.optString("coordinate_system") != "GCJ02") return null
            return Camera(value.optLong("id"), value.optString("name"), LatLng(lat, lng),
                value.optString("camera_type", value.optString("cameraType", "SIX_RING_IN")))
        }
    }
    val typeName: String get() = when (type) {
        "DISCARD" -> "已废弃"; "SIX_RING_OUT" -> "六环外"; "TWO_RING_IN" -> "二环内"; else -> "六环内"
    }
    val icon: Int get() = when (type) {
        "DISCARD" -> R.drawable.fq; "SIX_RING_OUT" -> R.drawable.location_six_ring
        "TWO_RING_IN" -> R.drawable.location_two_ring; else -> R.drawable.location
    }
}

data class Place(val name: String, val point: LatLng) {
    fun json() = JSONObject().put("name", name).put("address", name)
        .put("latitude", point.latitude).put("longitude", point.longitude)
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
fun Context.open(activity: Class<*>) = startActivity(Intent(this, activity))
fun JSONObject.string(key: String, fallback: String = "—") = optString(key).takeIf { it.isNotBlank() && it != "null" } ?: fallback
fun String.beijingTime(): String {
    if (this == "—") return this
    val instant = runCatching { Instant.parse(this) }.getOrElse {
        runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
    } ?: return this
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(instant)
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun AMap.fit(points: List<LatLng>) {
    if (points.isEmpty()) return
    if (points.size == 1) moveCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(points[0], 16f))
    else animateCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(
        LatLngBounds.Builder().also { b -> points.forEach { b.include(it) } }.build(), 80))
}

private object MapIcons {
    private val cache = mutableMapOf<Int, BitmapDescriptor>()
    fun get(resource: Int): BitmapDescriptor = cache.getOrPut(resource) {
        val context = JinjingleApp.instance
        val bitmap = BitmapFactory.decodeResource(context.resources, resource)
        val density = context.resources.displayMetrics.density
        BitmapDescriptorFactory.fromBitmap(Bitmap.createScaledBitmap(bitmap,
            (24 * density).toInt(), (36 * density).toInt(), true))
    }
}

fun AMap.addCamera(camera: Camera) = addMarker(MarkerOptions().position(camera.point)
    .title(camera.name).snippet(camera.typeName).icon(MapIcons.get(camera.icon)))
