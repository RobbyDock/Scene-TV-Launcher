package com.robbydock.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.View

/** Known streaming devices: a friendly name and an optional product photo. */
object DeviceCatalog {
    class Device(val name: String, val photo: Int = 0)

    /** The best match for this device, or the name Android reports. */
    fun current(): Device {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty()
        if (model.contains("onn.", true)) {
            return if (model.contains("Box", true)) Device("onn. 4K Streaming Box", R.drawable.device_onn_box)
            else Device(model, R.drawable.device_onn_stick)
        }
        return Device(if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim())
    }
}

/** The product photo, fitted to the view, on a soft floor shadow. */
@SuppressLint("ViewConstructor")
class DeviceArt(ctx: Context, photoRes: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photo = ctx.getDrawable(photoRes)!!

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ar = photo.intrinsicWidth.toFloat() / photo.intrinsicHeight.coerceAtLeast(1)
        var ih = h * 0.94f; var iw = ih * ar
        if (iw > w * 0.94f) { iw = w * 0.94f; ih = iw / ar }
        val cx = w / 2
        val floor = (h + ih) / 2
        // Shadow under the product.
        val rad = iw * 0.6f
        c.save()
        c.scale(1f, 0.14f, cx, floor)
        p.shader = RadialGradient(cx, floor, rad, intArrayOf(Theme.withAlpha(Color.BLACK, 0.6f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, floor, rad, p)
        p.shader = null
        c.restore()
        photo.setBounds((cx - iw / 2).toInt(), (floor - ih).toInt(), (cx + iw / 2).toInt(), floor.toInt())
        photo.draw(c)
    }
}
