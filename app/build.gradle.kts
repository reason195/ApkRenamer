plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.workbuddy.apkrenamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.workbuddy.apkrenamer"
        // SAF (ACTION_OPEN_DOCUMENT_TREE) 自 API 21 起可用
        minSdk = 21
        // targetSdk 35：Android 15 起强制 edge-to-edge，insets 统一由 ui/InsetsExt.kt 处理
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            // Robolectric 需要读取合并后的 Manifest / 资源
            isIncludeAndroidResources = true

            // CI 友好的并行：每个测试类在独立 JVM fork 里跑 Robolectric 沙箱（互不干扰），
            // 全量耗时从串行的 ~19s 压到与核数相关。fork 数默认 = 核数/2（封顶 4），
            // CI 上可用 GRADLE_TEST_PARALLEL_FORKS 环境变量显式覆盖。
            all { test ->
                test.maxParallelForks = System.getenv("GRADLE_TEST_PARALLEL_FORKS")?.toIntOrNull()
                    ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                // 每个 fork 独立限堆：Robolectric 需要加载/插桩 android-all，避免多 fork 争内存
                test.maxHeapSize = "1g"
            }
        }
    }

    // 本地隔离 debug 签名：避免与机器上其他 Gradle / Android Studio 实例争用
    // ~/.android/debug.keystore 的文件锁（之前因此 AccessDenied 导致打包失败）。
    // 仅影响本地构建、不影响应用逻辑；恢复默认签名只需删除本块。
    signingConfigs {
        getByName("debug") {
            storeFile = file("$rootDir/.buildcheck/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // 协程：Dispatchers.IO / async / awaitAll / Semaphore / Mutex
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── P3 新增 ───────────────────────────────────────────────────────
    // T7：本迭代唯一允许新增的 artifact（RQ-T7-1 / R6 明文放行）。
    // BrowseSupportFragment / Presenter / GuidedStepSupportFragment / ProgressBarManager
    implementation("androidx.leanback:leanback:1.0.0")
    // Q8：ApkListViewModel。activity-ktx:1.9.2 已通过 api 传递引入该模块，
    //     此处仅显式声明并与 lifecycle-runtime-ktx 对齐版本，不新增任何 artifact。
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    testImplementation("junit:junit:4.13.2")
    // Robolectric：仅用于需要真实 Android 框架的测试（ApkListViewModel 的
    // pickerInitialUri 依赖 DocumentsContract / ContentResolver）。
    // 纯逻辑测试（NameComposer / RulePrefs / ListProjection）继续用毫秒级 JVM 测试。
    testImplementation("org.robolectric:robolectric:4.14.1")
}
