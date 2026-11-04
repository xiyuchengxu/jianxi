# 正式版与覆盖升级签名

对外版本：1.0.4；内部版本：0.1.20；Android versionCode：21。

两条签名均延续 1.0.0，没有生成新密钥；正式版覆盖正式版，兼容包覆盖旧测试版或已有兼容包。

从 1.0.4 起，两种安装包均显式启用 v1（JAR）、v2 和 v3 签名。v1 会生成 `META-INF/MANIFEST.MF`、`.SF` 和证书文件，供需要读取 JAR 证书的安装器检查；v2/v3 保持完整 APK 签名校验。增加签名方案不会更换证书。1.0.3 只有 v2 签名，华为 Mate 70 RS / HarmonyOS 4.3.0 的安装失败报告与缺少 v1 证书文件相符，但真机结果仍需复测。

## 发布前验证

构建后分别检查正式版和覆盖升级版，传入对应的原证书指纹。验证程序会要求三种签名全部通过，额外强制走 v1 验证路径，并检查版本、非调试状态及 ZIP 完整性：

```sh
python scripts/verify_release_apk.py path/to/app.apk --build-tools path/to/sdk/build-tools/35.0.0 --certificate-sha256 <对应原证书指纹> --version 1.0.4 --version-code 21
```

## 两种安装包

- `production`：独立正式签名，非调试版本。证书 SHA-256：`74c2680384903d371b0a8a2e0ccd17de687ec3ecd12b4266c41faae30e565770`。
- `optimized`：沿用 0.1.x 公开测试签名的兼容升级包，非调试版本。证书 SHA-256：`e381d632fcaca580539bad72a5193f1766129cc4a49e0ca7775e91b243417733`。

两者包名都是 `dev.rain.laundry`、功能和版本一致，区别是签名及 BuildConfig 的构建类型。独立正式签名不能直接覆盖已有测试签名版本。卸载旧版会删除本机登录和订单记录；需要保留它们时使用覆盖升级包，先在原平台核对未完成订单。

## 正式签名材料

真实私钥和密码不在源码中。作者另有私有备份文件 `简洗_1.0.0_正式签名备份_请勿公开.zip`，其中包含 PKCS12 私钥、密码和证书，应自行保管，不公开或提交仓库。后续正式版更新必须继续使用同一份正式密钥。

在本地安全设置这些环境变量后构建：

```text
JIANXI_RELEASE_STORE_FILE=<正式 p12 文件的绝对路径>
JIANXI_RELEASE_STORE_PASSWORD=<仓库密码>
JIANXI_RELEASE_KEY_ALIAS=jianxi-release
JIANXI_RELEASE_KEY_PASSWORD=<密钥密码>
```

```sh
./gradlew :app:assembleProduction :app:lintProduction
```

输出：`app/build/outputs/apk/production/app-production.apk`。没有提供正式签名配置时不要把未签名产物当成正式签名安装包。

兼容旧版的升级包使用 `:app:assembleOptimized`；公开测试密钥已随旧源码发布，不能代替私有生产密钥。

本项目的 `release` 仍是独立的开发模板；本次正式交付使用明确的 `production` 变体。不启用代码混淆或删除资源，以保留本机订单序列化字段和完整离线许可文档。
