// 顶层构建脚本：只声明插件版本，具体配置写在各模块。
// 版本按实现时的最新稳定版锁定，与交接文档 3. 的要求一致。
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 起 Compose 编译器由该插件提供，不再使用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
