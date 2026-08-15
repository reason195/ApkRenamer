package com.workbuddy.apkrenamer.core

import android.net.Uri
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ApkMeta
import com.workbuddy.apkrenamer.model.ArchiveType
import com.workbuddy.apkrenamer.model.MetaSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 命名相关单测共用的测试数据工厂。
 *
 * `Uri` 经 `android.net.Uri.parse` 构造真实实例（测试跑在 Robolectric 沙箱下）；
 * 其余为纯数据构造，不触碰其它 Android 运行时 API。
 */

/** 固定时间戳，保证 [NameComposer.Field.DATE] 字段可预期 */
internal const val FIXED_TIME: Long = 1_700_000_000_000L

/**
 * [FIXED_TIME] 对应的日期串。
 *
 * 注意：`NameComposer` 使用默认时区格式化，故这里以同样方式计算期望值，
 * 避免测试结果随 CI 机器时区漂移。
 */
internal val DATE_STAMP: String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(FIXED_TIME))

internal fun metaOf(
    appName: String = "Demo App",
    packageName: String = "com.example.demo",
    versionName: String = "1.2.3",
    versionCode: Long = 4210L,
    minSdk: Int = 21,
    targetSdk: Int = 34
): ApkMeta = ApkMeta(
    appName = appName,
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    minSdk = minSdk,
    targetSdk = targetSdk,
    archiveType = ArchiveType.APK,
    source = MetaSource.PACKAGE_MANAGER
)

internal fun apkItem(
    displayName: String = "raw name.apk",
    meta: ApkMeta? = metaOf(),
    lastModified: Long = FIXED_TIME
): ApkItem = ApkItem(
    uri = Uri.parse("content://tests/$displayName"),
    documentId = "doc:$displayName",
    displayName = displayName,
    size = 1024L,
    lastModified = lastModified,
    meta = meta
)
