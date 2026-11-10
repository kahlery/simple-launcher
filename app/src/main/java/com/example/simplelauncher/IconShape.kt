package com.example.simplelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/** Köşe yarıçapı, ikon kenarının bu oranı; ekrandaki şekil de aynı oranı kullanır. */
const val ICON_CORNER_FRACTION = 0.2f

/**
 * Uygulama ikonunu köşeleri yuvarlatılmış kare bir bitmap'e çevirir. Uyarlanabilir ikonlarda
 * ham katmanlar çizilip bu şekle kesilir; eski tip ikonlar beyaz karenin ortasına yerleştirilir.
 */
fun roundedIcon(icon: Drawable, px: Int): Bitmap {
    val content = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(content)
    if (icon is AdaptiveIconDrawable) {
        // Katmanlar 108dp; görünen alan ortadaki 72dp. Her yandan %25 taşırarak çiz.
        val extra = px / 4
        for (layer in listOfNotNull(icon.background, icon.foreground)) {
            layer.setBounds(-extra, -extra, px + extra, px + extra)
            layer.draw(canvas)
        }
    } else {
        canvas.drawColor(AndroidColor.WHITE)
        val inset = (px * 0.2f).toInt()
        icon.setBounds(inset, inset, px - inset, px - inset)
        icon.draw(canvas)
    }
    // Önce yumuşak kenarlı şekil çizilir, ikon yalnızca onun üstüne (SRC_IN) basılır.
    val result = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        val r = px * ICON_CORNER_FRACTION
        drawRoundRect(0f, 0f, px.toFloat(), px.toFloat(), r, r, Paint(Paint.ANTI_ALIAS_FLAG))
        drawBitmap(content, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
    }
    content.recycle()
    return result
}
