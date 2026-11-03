# 第三方组件

简洗自身的源码采用 [MIT License](LICENSE)。MIT 声明不替代第三方组件的许可、版权声明或服务条款。构建时由 Gradle 下载的依赖以及随项目提供的 Gradle Wrapper，适用其各自的许可。

| 组件 | 版本 | 官方项目或发布信息 |
|---|---|---|
| AndroidX / Jetpack Compose / Material 3 | 见 `app/build.gradle.kts` | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Material Color Utilities（HCT / tonal palette Java 子集） | `bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3` | [Google 官方源码](https://github.com/material-foundation/material-color-utilities/tree/bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3/java)、[Apache-2.0 全文](app/src/main/res/raw/material_color_utilities_license.txt) |
| Kotlin | 2.0.21 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin Coroutines | 由 Gradle 解析；测试依赖 1.9.0 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp | 4.12.0 | [square/okhttp](https://github.com/square/okhttp) |
| Gson | 2.11.0 | [google/gson](https://github.com/google/gson) |
| ZXing Android Embedded | 4.3.0 | [journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) |
| ZXing Core | 由 Gradle 解析 | [zxing/zxing](https://github.com/zxing/zxing) |
| 支付宝 Android SDK | 15.8.42 | [Maven 发布信息](https://central.sonatype.com/artifact/com.alipay.sdk/alipaysdk-android/15.8.42)、[支付宝开放平台](https://open.alipay.com/) |
| Gradle Wrapper | 8.9 | [gradle/gradle](https://github.com/gradle/gradle) |

JUnit、MockWebServer 和 Kotlin Coroutines Test 仅用于测试；Android Gradle Plugin 和 Kotlin Compose 插件用于构建。完整依赖树可运行 `./gradlew :app:dependencies` 查看。

Material Color Utilities 的七个源文件保留原始版权和许可头；仅调整 Java 包名和对应 import，以避免命名冲突，没有修改颜色算法。上游提交、文件范围与修改说明见 `app/src/main/java/dev/rain/laundry/vendor/material/PROVENANCE.md`。完整 Apache-2.0 许可随 APK 打包，可在关于页的“配色组件开源许可”离线查看。

本工程不包含胖乖生活原版应用的图标、广告组件或整包反编译源码。原服务平台及支付服务的名称和商标归各自权利人所有。
