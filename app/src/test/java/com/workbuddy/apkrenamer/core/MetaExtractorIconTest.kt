package com.workbuddy.apkrenamer.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MetaExtractor.drawableToBitmap] 的单测。
 *
 * 回归点：旧实现只认 `drawable as? BitmapDrawable`，而现代 APK 的 icon 基本是
 * AdaptiveIconDrawable / VectorDrawable，一律被判空丢弃 → 真机列表恒显示占位图。
 */
class MetaExtractorIconTest : RobolectricTestBase() {

    private fun argb(w: Int, h: Int, color: Int = Color.TRANSPARENT): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Suppress("DEPRECATION")
    private fun bitmapDrawable(bitmap: Bitmap): BitmapDrawable = BitmapDrawable(bitmap)

    /** 无固有尺寸、draw 时自己填色的 Drawable，等价于矢量图走的「画到 Canvas」分支。 */
    private class SquareRedDrawable : Drawable() {
        private val paint = Paint().apply { color = Color.RED }
        override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable 契约要求实现")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /* ================================================================ */
    /* 位图图标                                                          */
    /* ================================================================ */

    @Test
    fun `位图图标超过上限时等比压缩`() {
        val out = MetaExtractor.drawableToBitmap(bitmapDrawable(argb(1024, 512)))
        assertNotNull(out)
        assertEquals(256, out!!.width)
        assertEquals(128, out.height)
    }

    @Test
    fun `小位图图标原样返回不放大不复制`() {
        val src = argb(100, 50)
        assertSame(src, MetaExtractor.drawableToBitmap(bitmapDrawable(src)))
    }

    @Test
    fun `回收过的位图返回 null 而不是崩溃`() {
        val src = argb(64, 64).apply { recycle() }
        assertEquals(null, MetaExtractor.drawableToBitmap(bitmapDrawable(src)))
    }

    /* ================================================================ */
    /* 非位图图标：旧实现的漏网之鱼                                       */
    /* ================================================================ */

    @Test
    fun `AdaptiveIconDrawable 被栅格化而不是判空丢弃`() {
        val adaptive = AdaptiveIconDrawable(
            bitmapDrawable(argb(432, 432, Color.WHITE)),
            ColorDrawable(Color.GREEN)
        )
        val out = MetaExtractor.drawableToBitmap(adaptive)
        assertNotNull(out)
        assertTrue("最长边须压在 256 内，实际 ${out!!.width}x${out.height}",
            out.width in 1..256 && out.height in 1..256)
    }

    @Test
    fun `无固有尺寸的矢量图退化为正方形并真的画上像素`() {
        val out = MetaExtractor.drawableToBitmap(SquareRedDrawable())
        assertNotNull(out)
        assertEquals(256, out!!.width)
        assertEquals(256, out.height)
        assertEquals(Color.RED, out.getPixel(128, 128))
    }
}
