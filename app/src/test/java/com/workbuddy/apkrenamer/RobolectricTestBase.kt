package com.workbuddy.apkrenamer

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全部单测共享的 Robolectric 基类（见 README 测试分层）。
 *
 * - 统一 runner 与 SDK：显式 `@Config(sdk = [35])` 把模拟 SDK 钉在编译目标上，
 *   不随 `targetSdk` 变化而漂移；子类不再需要任何注解——JUnit 的 `@RunWith` 与
 *   Robolectric 的 `@Config` 都会被子类继承。
 * - 纯逻辑测试类（NameComposer / RulePrefs / ListProjection / VolumeLogic /
 *   PickerInitialResolver）也继承本类：它们仍只做逻辑断言，运行时需要真实
 *   `android.net.Uri` 构造 `ApkItem`，统一沙箱环境比各自注解更不易分叉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class RobolectricTestBase
