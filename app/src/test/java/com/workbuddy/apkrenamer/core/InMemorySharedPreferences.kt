package com.workbuddy.apkrenamer.core

import android.content.SharedPreferences

/**
 * 纯内存版 [SharedPreferences] 实现。
 *
 * `SharedPreferences` 在 android.jar 中是**接口**，可以脱离真实实现直接验证
 * [RulePrefs] 的读写往返（测试整体跑在 Robolectric 沙箱下，见 README 测试分层）。
 *
 * 行为对齐真实实现的关键点：
 * - `putString(key, null)` 等价于 `remove(key)`；
 * - `apply()` / `commit()` 之前的修改对读取方不可见（Editor 先攒批再落盘）；
 * - key 不存在时返回调用方给的默认值。
 */
internal class InMemorySharedPreferences(
    initial: Map<String, Any> = emptyMap()
) : SharedPreferences {

    private val store: MutableMap<String, Any> = LinkedHashMap(initial)

    /** 落盘次数，可用于断言「确实写过一次」 */
    var flushCount: Int = 0
        private set

    /* ------------- 供测试直接读取底层原始值（绕过 RulePrefs 的夹紧/回退） ------------- */

    fun rawString(key: String): String? = store[key] as? String

    fun rawInt(key: String, def: Int = Int.MIN_VALUE): Int = store[key] as? Int ?: def

    fun rawBoolean(key: String, def: Boolean): Boolean = store[key] as? Boolean ?: def

    /* ---------------------------------- 读 ---------------------------------- */

    private fun valueOf(key: String?): Any? = if (key == null) null else store[key]

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(store)

    override fun getString(key: String?, defValue: String?): String? =
        valueOf(key) as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        valueOf(key) as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = valueOf(key) as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = valueOf(key) as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = valueOf(key) as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        valueOf(key) as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key != null && store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    /* ---------------------------------- 写 ---------------------------------- */

    private inner class EditorImpl : SharedPreferences.Editor {

        private val pending: MutableMap<String, Any> = LinkedHashMap()
        private val removed: MutableSet<String> = LinkedHashSet()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            val k = key ?: return this
            if (value == null) {
                // 与真实实现一致：写入 null 视为删除
                removed += k
                pending -= k
            } else {
                pending[k] = value
                removed -= k
            }
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = put(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let {
                removed += it
                pending -= it
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() {
            flush()
        }

        private fun flush() {
            if (clearAll) store.clear()
            removed.forEach { store.remove(it) }
            store.putAll(pending)
            flushCount++
        }
    }
}
