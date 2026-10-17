# 本项目不开启混淆（build.gradle.kts 中 release 的 minifyEnabled = false）。
# 保留占位，便于后续开启时补充 CameraX / Compose 需要的 keep 规则。

# CameraX 通过反射加载 Camera2 实现
-keep class androidx.camera.camera2.** { *; }
-dontwarn androidx.camera.**
