package com.aura.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** A run of track points drawn in one colour (for example one heart-rate zone). */
class Seg(val pts: List<GeoPoint>, val color: Int)

private object MapInit {
    @Volatile private var done = false
    fun ensure(c: Context) {
        if (done) return
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = c.packageName
        val base = File(c.cacheDir, "osmdroid"); cfg.osmdroidBasePath = base; cfg.osmdroidTileCache = File(base, "tiles")
        cfg.tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        done = true
    }
}

/** OpenStreetMap tiles, inverted and tinted so they suit the dark theme. */
private val DARK_FILTER = android.graphics.ColorMatrixColorFilter(
    android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        .apply { postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.3f) }) }
        .apply { postConcat(android.graphics.ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, 4f, 0f, 0f, 0f, 1f, 0f))) }
)

/** In light mode the tiles are only muted, so the route is what stands out. */
private val LIGHT_FILTER = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.25f) })

private class TrackMapView(c: Context) : MapView(c) {
    @JvmField var interactive = true
    /** what the camera was last framed on, so a recomposition doesn't undo the user's panning */
    @JvmField var framed: String = ""
    @JvmField var placed = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        // keep the page from scrolling while a finger is on the map
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.onTouchEvent(e)
    }

    /** Runs [f] now if the map has a size, otherwise once it's laid out: framing a zero-size map does nothing. */
    fun whenLaidOut(f: () -> Unit) {
        if (width > 0 && height > 0) post(f) else addOnFirstLayoutListener { _, _, _, _, _ -> post(f) }
    }
}

private fun far(a: GeoPoint, b: GeoPoint) = Math.abs(a.latitude - b.latitude) > 0.005 || Math.abs(a.longitude - b.longitude) > 0.005

/**
 * Map with [segments] as coloured lines, [me] as a dot and [pins] as coloured markers.
 * With [follow] the map keeps the dot centred; with [fit] it zooms to show every segment and pin, once per change of them.
 * Otherwise it opens on [center] (or [me]). [onTap] gets the point that was tapped.
 */
@Composable
fun TrackMap(
    modifier: Modifier, segments: List<Seg>, me: GeoPoint? = null, follow: Boolean = false, interactive: Boolean = true,
    fit: Boolean = false, pins: List<Pair<GeoPoint, Int>> = emptyList(), center: GeoPoint? = null, onTap: ((GeoPoint) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val dark = C.dark
    val map = remember {
        MapInit.ensure(ctx)
        TrackMapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBackgroundColor(android.graphics.Color.BLACK)
            overlayManager.tilesOverlay.setColorFilter(DARK_FILTER)
            overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.BLACK
            overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.BLACK
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0; maxZoomLevel = 19.0; controller.setZoom(16.5)
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(modifier = modifier, factory = { map }, update = { m ->
        m.interactive = interactive
        val ground = if (dark) android.graphics.Color.BLACK else 0xFFEAE3D5.toInt()
        m.setBackgroundColor(ground)
        m.overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_FILTER else LIGHT_FILTER)
        m.overlayManager.tilesOverlay.loadingBackgroundColor = ground
        m.overlayManager.tilesOverlay.loadingLineColor = ground
        m.overlays.clear()
        for (s in segments) if (s.pts.size >= 2) m.overlays.add(Polyline().apply {
            setPoints(s.pts); outlinePaint.color = s.color; outlinePaint.strokeWidth = 11f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.isAntiAlias = true
        })
        if (me != null) m.overlays.add(dot(me, 0xFFFF6A3D.toInt(), 22f, 14f))
        for ((g, col) in pins) m.overlays.add(dot(g, col, 24f, 16f))
        if (onTap != null) m.overlays.add(MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        val all = segments.flatMap { it.pts } + pins.map { it.first }
        when {
            fit && all.size >= 2 -> {
                val key = "fit:${all.size}:${all.first()}:${all.last()}"
                if (m.framed != key) {
                    m.framed = key; m.placed = true
                    m.whenLaidOut { try { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), true, 110) } catch (_: Exception) {} }
                }
            }
            follow && me != null -> {
                // jump on the first fix (an animation from the default 0,0 never arrives), glide after that
                if (!m.placed || far(m.mapCenter as GeoPoint, me)) { m.placed = true; m.whenLaidOut { m.controller.setCenter(me) } }
                else m.controller.animateTo(me)
            }
            else -> {
                val target = (if (fit) all.firstOrNull() else null) ?: center ?: me
                if (target != null && !m.placed) { m.placed = true; m.whenLaidOut { m.controller.setCenter(target) } }
            }
        }
        m.invalidate()
    })
}

private fun dot(g: GeoPoint, color: Int, outer: Float, inner: Float) = object : Overlay() {
    override fun draw(c: Canvas, v: MapView, shadow: Boolean) {
        if (shadow) return
        val p = Point(); v.projection.toPixels(g, p)
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFF4EFE6.toInt() })
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}
