# 发行构建与 APK 验证

## 校验官方下载

正式包使用同一发行证书，SHA-256 指纹为 `74c2680384903d371b0a8a2e0ccd17de687ec3ecd12b4266c41faae30e565770`。发布说明列出了该版本 APK 和源码 ZIP 各自的 SHA-256。

安装 Python 3 及 Android SDK Build Tools 35.0.0 后，可在工程根目录验证正式 APK。下面的命令适用于已设置 `ANDROID_HOME` 的 macOS 或 Linux 终端。

```sh
python3 scripts/verify_release_apk.py jianxi-11.1.0-release.apk --build-tools "$ANDROID_HOME/build-tools/35.0.0" --version 11.1.0 --version-code 49
```

Windows PowerShell 使用以下命令。

```powershell
python scripts/verify_release_apk.py jianxi-11.1.0-release.apk --build-tools "$env:ANDROID_HOME/build-tools/35.0.0" --version 11.1.0 --version-code 49
```

验证脚本会检查包名、版本、非调试标志、ZIP 完整性、发行证书和 v1/v2/v3 签名。脚本固定使用官方证书指纹。

## 使用自己的证书构建

开发测试可直接运行 `:app:assembleDebug`。需要签名的发行构建时，准备自己的 PKCS#12 密钥库，并通过环境变量配置 Gradle。

| 环境变量 | 用途 |
|---|---|
| `JIANXI_RELEASE_STORE_FILE` | PKCS#12 密钥库的完整路径 |
| `JIANXI_RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `JIANXI_RELEASE_KEY_ALIAS` | 密钥别名，默认 `jianxi-release` |
| `JIANXI_RELEASE_KEY_PASSWORD` | 私钥密码，缺省时使用密钥库密码 |

```sh
./gradlew :app:assembleProduction
```

Windows 使用 `.\gradlew.bat`。产物位于 `app/build/outputs/apk/production/app-production.apk`。Android 仅允许使用匹配签名对已安装的应用执行覆盖更新。
