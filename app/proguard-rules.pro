# 数据模型通过 org.json 手写序列化，无反射依赖，无需额外 keep 规则。
-dontwarn kotlinx.coroutines.**
-keepclassmembers class com.workbuddy.apkrenamer.model.** { *; }
