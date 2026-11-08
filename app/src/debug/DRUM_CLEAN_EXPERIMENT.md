# 桶自洁离线实验

该目录只编入 debug。桌面独立入口为“简洗·自洁离线实验”；正式版没有该入口。

全部响应、SKU、预约编号、机器和金额都是虚构夹具，时间固定为上海时间 2026-11-07 12:00。此页不创建 LaundryViewModel、LaundryApi、SessionVault 或任何支付/网络客户端，不读取登录，也没有启机操作。

切换场景后，可对比原版列表/详情入口显示条件；“强制展示诊断入口”只改变实验入口可见性。查看本地分流会弹出机器、模拟金额、检查结果和停止说明。零元不是免费权益证明；本原型永远停在本地检查。

原版列表同时看内层 `appointmentOrder.skuItems` 与外层 `skuItems`，详情和详情扫码后的路由使用外层。本实验单独保留两种显示结果，绝不把内层列表当作详情资格。异常时间、缺失标记、异常金额、多项 SKU 会被保守拒绝；这些是实验约束，不代表服务端完整规则。

在 `D:/project/jianxi` 使用 JDK 21：

```powershell
$env:JAVA_HOME='D:/Java/JDK-21'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

JVM 检查输出 `app/build/reports/drum-clean-experiment/matrix.json`。`DrumCleanInspectorTest` 包含 13 个场景的强制前后不变性、截止边界、机器别名、订单绑定、畸形价格和普通扫码解析回归。

运行界面验证仅使用无真实账号的专用模拟器 `emulator-5560`，预先打开飞行模式：

```powershell
$taskAdb='C:/Users/YuCheng/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $taskAdb -s emulator-5560 shell cmd connectivity airplane-mode enable
& $taskAdb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
& $taskAdb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $taskAdb -s emulator-5560 shell am instrument -w -e class dev.rain.laundry.DrumCleanExperimentRuntimeTest dev.rain.laundry.test/androidx.test.runner.AndroidJUnitRunner
```

debug APK 使用开发证书，同包名正式版可能无法覆盖安装；勿为实验卸载真实用户的正式版。最初实验基于 5.0.0 / 30 / 内部 0.1.29，未发布或替换 GitHub Release；后续构建版本以 app/build.gradle.kts 为准。

目前能证明入口显示和离线检查，不能证明目标账号/设备能从真实协议获得自洁 SKU 或权益。后续先取得本人预约订单的只读详情，核对 `skuItems`、`tzjFlag`、`tzjCanEndTime` 和机器身份，再决定是否实现正式流程。建单、付款、设备检查/同步和启机均不属于本原型的只读阶段。
