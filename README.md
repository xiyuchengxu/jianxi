# 简洗 Jianxi

简洗是独立开发的 Android 客户端，可使用本人已有的胖乖生活账户扫码洗衣、烘干和饮水，查看设备报价、付款状态和本机订单。使用前请确认设备运营方支持相应服务，并阅读应用中的使用约定。[使用约定](USAGE_AGREEMENT.md)

## 安装

**11.1.0** 适用于 Android 8.0 及以上，包名为 `dev.rain.laundry`，构建号为 `49`。在版本页面下载 `jianxi-11.1.0-release.apk`。已安装相同签名旧正式版的用户可直接覆盖安装。[下载正式版](https://github.com/xiyuchengxu/jianxi/releases/tag/v11.1.0)

## 使用

- **洗衣、烘干和饮水**。扫描或粘贴设备码后可查看可用项目。烘干支持选择时长，常用设备按类型保存；洗衣、烘干、饮水订单分别归类。付款前会核对金额与渠道，不采用积分抵扣。
- **洗衣进度与提醒**。在订单详情查看预计剩余时间。在“设置 → 通知”开启本机进度通知。需要云端微信提醒时，可按页面说明绑定服务并授权；预计结束时间和平台确认完成会分别显示。
- **主题与壁纸**。在“设置 → 主题”选择 Material 或 MIUIX、明暗模式和主页文案。“壁纸”可使用当前桌面壁纸，调节壁纸亮暗及控件背景透明度。长按单个控件可单独调整，选择“跟随全局”可恢复。输入框从右侧边缘长按。
- **语言和排错**。“设置 → 语言”支持跟随系统及六种语言。在“设置 → 排错日志”可查看最近 200 条操作记录，并导出 TXT。首页右上角进入“关于”，登录后在设置中可以退出账号。

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。工程自带 Gradle 8.9 Wrapper。首次构建需要下载 Gradle 及 Maven 依赖。用 Android Studio 打开解压后的目录，或在项目根目录运行以下命令。

```sh
./gradlew :app:assembleDebug :app:testProductionUnitTest :app:lintProduction
```

Windows PowerShell 使用 `.\gradlew.bat` 替换 `./gradlew`。开发版输出到 `app/build/outputs/apk/debug/app-debug.apk`，Android 会为其使用开发证书。发行构建及官方 APK 校验方法见[签名与验证](RELEASE_SIGNING.md)。

简洗源码使用 MIT 许可。图片、字体、随附库及其他组件各自适用相应授权。[MIT 许可](LICENSE) · [第三方声明](THIRD_PARTY_NOTICES.md) · [反馈问题](https://github.com/xiyuchengxu/jianxi/issues)
