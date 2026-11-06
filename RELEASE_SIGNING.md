# 正式版签名

当前发布版本：5.0.0；内部版本：0.1.29；Android versionCode：30。


5.0.0 新增最近 20 条本机订单与周分组、主题开屏设置折叠，保留屏幕二维码扫码、透明开屏及饮水签约修复。正式包支持覆盖原正式证书版本。

后续发布只提供正式签名 APK 和源码 ZIP，按 [发布与版本规则](RELEASE_POLICY.md) 执行。正式版继续沿用 1.0.0 起使用的同一份私有正式密钥，证书 SHA-256 为：

```text
74c2680384903d371b0a8a2e0ccd17de687ec3ecd12b4266c41faae30e565770
```

## 签名方案

正式 APK 显式启用 v1（JAR）、v2 和 v3 签名。v1 会生成 `META-INF/MANIFEST.MF`、`.SF` 和证书文件；v2/v3 保持完整 APK 签名校验。三种方案使用相同的正式证书。

1.0.4 补齐了 v1 证书文件，以适配 Mate 70 RS / HarmonyOS 4.3.0 报告的安装失败；该机型实际安装仍需复测。历史 1.0.3 只有 v2 签名。

## 构建

真实私钥和密码由作者私下保管。现有私有备份为 `简洗_1.0.0_正式签名备份_请勿公开.zip`，包含 PKCS12 私钥、密码和证书；源码和 Release 附件均排除此备份及其内容。

在本地设置以下环境变量：

```text
JIANXI_RELEASE_STORE_FILE=<原正式 p12 文件的绝对路径>
JIANXI_RELEASE_STORE_PASSWORD=<仓库密码>
JIANXI_RELEASE_KEY_ALIAS=jianxi-release
JIANXI_RELEASE_KEY_PASSWORD=<密钥密码>
```

```sh
./gradlew :app:assembleProduction :app:testProductionUnitTest :app:lintProduction
```

输出为 `app/build/outputs/apk/production/app-production.apk`。打包前会执行 `verifyProductionSigning`，缺少正式签名配置时构建失败。发布名为 `jianxi-<版本号>-release.apk`。

`production` 和 `release` 均使用正式签名，不启用调试、代码混淆或资源删除，以保留订单序列化字段和完整离线许可文档。本地 `debug` 使用 Android Gradle Plugin 的默认开发签名。

## 发布前验证

发布验证程序固定校验上述原正式证书，同时要求 v1/v2/v3 全部有效、独立 v1 验证通过、包名及版本匹配，APK 非调试且 ZIP 完整。将参数替换成实际发布版本和构建号：

```sh
python scripts/verify_release_apk.py path/to/app.apk --build-tools path/to/sdk/build-tools/35.0.0 --version 5.0.0 --version-code 30
```

## 旧覆盖升级版的迁移

历史覆盖升级版使用公开测试证书，与正式证书不同。后续正式 APK 可直接覆盖旧正式版；已有覆盖升级版不能直接切换签名。迁移至正式版需要重新安装，卸载会删除本机登录和本机订单记录；迁移前先在原平台核对未完成订单。
