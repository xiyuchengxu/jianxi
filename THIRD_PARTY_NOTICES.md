# 第三方组件

简洗自身的源码采用 [MIT License](LICENSE)。MIT 声明不替代第三方组件的许可、版权声明或服务条款。构建时由 Gradle 下载的依赖以及随项目提供的 Gradle Wrapper，适用其各自的许可。

| 组件 | 版本 | 官方项目或发布信息 |
|---|---|---|
| AndroidX / Jetpack Compose / Material 3 | 见 `app/build.gradle.kts` | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Miuix Compose Multiplatform | 0.3.4 | [官方项目 v0.3.4](https://github.com/compose-miuix-ui/miuix/tree/677a9cf2930ca7df6e912fafc812cdc64ee5cd50)、[Maven Central](https://central.sonatype.com/artifact/top.yukonga.miuix.kmp/miuix-android/0.3.4)，Apache-2.0，完整许可随 APK 保存在 `assets/miuix-license.txt` |
| AndroidX ExifInterface | 1.4.2 | [Android 官方发布说明](https://developer.android.com/jetpack/androidx/releases/exifinterface) |
| Shizuku API / Provider | 13.1.5 | [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)，MIT，原文随 APK 保存在 `assets/shizuku-api-license.txt` |
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

## Miuix 界面组件

MIUIX 模式使用正式依赖 `top.yukonga.miuix.kmp:miuix-android:0.3.4` 的 `MiuixTheme`、`Scaffold`、`TopAppBar`、`Button`、`Card`、`Switch`、`Checkbox`、`IconButton`、`TextField`、`NavigationBar` 和 `BasicComponent`。Material 模式继续使用原 Jetpack Compose Material 3 组件；共享业务界面通过本项目的组件适配层选择当前工具包。为使尚未迁移的 Material 组件正确显示，主题桥接将实际 Miuix 颜色和文字样式传入 MaterialTheme，不再使用此前仿制的橙色配色。

固定版本 0.3.4 基于 Compose Multiplatform 1.7.3，适配本项目既有 Compose 运行时；此版本不是 Miuix 最新版本。上游标签 `v0.3.4` 对应提交 `677a9cf2930ca7df6e912fafc812cdc64ee5cd50`，依赖从 Maven Central 获取，未复制或修改其组件实现。该项目作者/贡献者为 YuKongA 及 Miuix contributors；Apache-2.0 完整许可原文随 APK 和源码包保留。许可文件 SHA-256 为 `50e6751797c50dedd75ef1b8a0d9e42f5f8472e9fbce91f34718e9f97b0c780a`。

组件迁移保留本应用的点击回调、启用状态、设备及金额确认和现有语言资源，不改变平台协议。Miuix 组件使用库自身的交互、圆角和默认配色。输入框透传数字过滤、密码掩码、IME 动作和多行编辑行为；需要库尚未提供的独立占位提示槽时仍使用兼容输入框。自定义玻璃导航和系统模态确认框保留既有实现；固定导航在库原生视觉之上补足其旧版本缺失的可访问选择语义及禁用状态。

Material Color Utilities 的七个源文件保留原始版权和许可头；仅调整 Java 包名和对应 import，以避免命名冲突，没有修改颜色算法。上游提交、文件范围与修改说明见 `app/src/main/java/dev/rain/laundry/vendor/material/PROVENANCE.md`。完整 Apache-2.0 许可随 APK 打包，可在关于页的“配色组件开源许可”离线查看。

本工程不包含胖乖生活原版应用的图标、广告组件或整包反编译源码。原服务平台及支付服务的名称和商标归各自权利人所有。

## 云篆度人经字体

- 作者：执笔之龙，Copyright (c) 2026 执笔之龙。
- 上游：[Lawrence-of-AnKing/yunzhuan-duren](https://github.com/Lawrence-of-AnKing/yunzhuan-duren)，固定提交 `bcedee13ee0cb6b491f59f781ae1e5cdf5276dd8`，v1.0.0。
- 使用上游 `dist/YunZhuanDuRen-Unmerged.ttf` 完整原文件，资源名为 `app/src/main/res/font/yunzhuan_duren.ttf`；未改写、合并、裁剪或重新构建字体。
- 字体 SHA-256：`861288b479ebc3818583734573af97ef329f3018d760320bba0fe2894a0c52f2`，4,718,052 字节，已核对上游 `dist/SHA256SUMS`。
- 字体适用 **SIL Open Font License 1.1**，不由简洗源码的 MIT 授权替代。原版权与许可全文保存在 [`app/src/main/res/raw/yunzhuan_duren_license.txt`](app/src/main/res/raw/yunzhuan_duren_license.txt)，随 APK 和源码打包，可在应用关于页离线查看。未复制上游构建代码或扫描资料。
- 字体只收录部分汉字；未覆盖文字由系统字体回退。应用保存原始 Unicode 内容，不转写输入文字。

## 开屏图片

`app/src/main/res/drawable-nodpi/splash_mascot_1.png` 与 `splash_mascot_2.png` 为作者提供插画经内置 ImageGen 背景提取后的透明人物立绘，保留主体人物、随身道具及与主体重叠的署名。图片版权归原权利人，不包含在项目代码的 MIT 授权中。自定义开屏图片由用户在本机选择并保存。
