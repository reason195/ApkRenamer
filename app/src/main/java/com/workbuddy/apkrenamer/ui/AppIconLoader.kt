package com.workbuddy.apkrenamer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用图标的轻量异步加载器（零第三方库）。
 *
 * 图标是解析阶段提取好、按缓存 Key 落盘的 PNG 文件，展示时只需解码：
 * - **内存 LruCache**：图标文件按 Key 稳定，解码一次后直接命中，滚动不再触发 I/O；
 * - **IO 线程解码 + 采样**：按目标尺寸 `inSampleSize`，避免把几百像素的大图整张解出来；
 * - **tag 防错位**：RecyclerView 复用 ViewHolder 时，旧行的异步结果用 `tag != id` 拦掉。
 *
 * 不持有任何 Activity / Fragment 引用，App 进程级单例即可，解码任务短小无泄漏风险。
 */
object AppIconLoader {

    private const val MEMORY_CACHE_KB = 4 * 1024
    private const val TARGET_SIZE = 96

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 把 [path] 指向的图标异步加载进 [view]。[tag] 用稳定 id（documentId）防错位；
     * 无图标或尚未解码完成时保持占位背景。
     */
    fun bind(view: ImageView, path: String?, tag: String) {
        view.tag = tag
        if (path == null) {
            view.setImageDrawable(null)
            return
        }
        memory.get(path)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(path) }
            // 视图已被复用到其它行：丢弃过期结果
            if (view.tag == tag && bitmap != null) {
                memory.put(path, bitmap)
                view.setImageBitmap(bitmap)
            }
        }
    }

    /** 按目标尺寸采样解码，避免整张大图占内存。 */
    private fun decodeSampled(path: String, target: Int = TARGET_SIZE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target &&
            bounds.outHeight / (sample * 2) >= target
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }
}
