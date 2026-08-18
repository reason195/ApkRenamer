plugins {
    // AGP 9 系当前稳定版。Kotlin 由 AGP 内置（kotl.in/gradle/agp-built-in-kotlin）：
    // 模块不再 apply org.jetbrains.kotlin.android，但保留在 classpath 上有两个作用——
    // ① 内置 Kotlin 编译器跟随此版本（当前 2.4.10）；② 提供 kotlin { compilerOptions } DSL。
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
