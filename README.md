# CodingProfessionalAgent for Android

启动器图标源自 iOS 项目的 `CodingProfessionalAgent/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png`，（缩放为 512px，`drawable-nodpi/ios_app_icon.png`），并保留安全边距以适配 Android 启动器的不同图标形状；主题图标使用由同一原图描出的单色剪影 `drawable-nodpi/ic_launcher_monochrome.png`。桌面显示名与 iOS 一致为 `CPA`。

独立原生 Android 客户端迁移工程。Kotlin + Jetpack Compose，自建设计系统，单 app 模块。开发包名 `dev.cpa.android`，最低 Android 13（API 33）。

默认入口连接真实桌面，支持直连和共享隧道、项目与会话管理、发送/停止/恢复、排队与引导、Ask、消息编辑、附件、技能、分叉、子会话，以及 Git 和文件只读面板。连接凭据保存在 Android Keystore 支持的加密存储中。功能与验证边界见[当前功能矩阵](docs/migration/current-feature-matrix.md)。

远程推送通知尚未实现；真实模型对图片附件的识别能力尚未验证。

## 构建与测试

JDK、SDK 与固定工具版本见环境文档。项目本地 SDK 放 `.local/android-sdk`，通过不跟踪的 `local.properties` 配置；不要提交个人绝对路径。

```sh
./scripts/check-repository.sh
./scripts/verify.sh  # 构建debug/测试APK、单元测试及lint，记录本地日志
# 或直接运行 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 有已授权设备/模拟器时：
./scripts/gradle-local :app:connectedDebugAndroidTest
```

构建成功后的调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。可用 `.local/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装到已授权的 Android 13+ 设备；已在项目本地 Android 17 模拟器安装运行并执行 UI/Keystore/网络契约测试。

## 分支与发布

日常开发在 `dev`，发版时通过 PR 将 `dev` 合并到 `main`。推送这两个分支或向它们提 PR 都会运行 `Android checks`（仓库检查、debug 构建、JVM 测试、设备测试 APK 编译、lint），不会发布。建议为 `main` 设置分支保护，要求 PR 和该检查通过。

首次发布前，在 GitHub **Settings → Environments → release** 设置以下 environment secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 编码（不是文件路径） |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 发布签名别名 |
| `ANDROID_KEY_PASSWORD` | 该私钥的密码 |

妥善备份并持续使用同一发布密钥，否则安装用户无法覆盖升级。限制 `release` 环境只允许 `v*` 标签部署，并用 tag ruleset 限制这些标签的创建、修改和删除权限。

PR 合并后，从已更新的 `main` 推送单个新标签，例如：

```sh
git switch main
git pull --ff-only origin main
git tag -a v1.2.3 -m 'Release v1.2.3' origin/main
git push origin refs/tags/v1.2.3
```

[Android CI](.github/workflows/android.yml) 只接受 `v主.次.补丁` 格式的发布标签，且对应提交必须已进入 `main`。检查通过后构建 release APK（`versionName` 取标签去掉 `v`，`versionCode` 取工作流运行序号加一），签名、验签后创建 GitHub Release，附上 ARM32（`CPA-Android-v1.2.3-armeabi-v7a.apk`）、ARM64（`CPA-Android-v1.2.3-arm64-v8a.apk`）、两份最终签名 APK 的哈希清单 `SHA256SUMS.txt` 及自动生成的发布说明。将三个附件下载到同一目录，运行 `shasum -a 256 -c SHA256SUMS.txt`（macOS）或 `sha256sum -c SHA256SUMS.txt`（Linux），两个文件都应显示 `OK`。缺少签名配置不会回退到 debug 签名。若日后更换工作流导致运行序号重置，应确保新 `versionCode` 大于历史发布值。

## 连接说明
桌面端开启 Web 服务后，手机与桌面需网络可达；模拟器访问开发机一般使用 `10.0.2.2`，真机使用桌面局域网地址。HTTP `/api/auth/status` → `/api/auth/login`，按连接 Cookie 握手 `/api/ws`，收到 `hello` 后调用位置参数 RPC。

Android 17 起需先授权本地网络访问。直连地址与 iOS 一致，未写协议时按 HTTP 处理；使用 HTTP 必须在编辑器里单独确认风险，修改地址后要重新确认。局域网 HTTP/WS 没有加密，口令也会明文传输；不要在不可信网络使用。HTTPS/WSS 不绕过证书验证。不要将服务暴露公网而无可靠访问控制。使用隧道时，在“设置 → 添加连接”中选择隧道，填写网关地址和凭据，从发现列表中选择桌面，再保存桌面登录信息。

编辑时密码留空保留旧值，明确勾选清空才删除密码。操作结果未知时不会自动重发；应刷新会话、核对桌面后显式确认再操作。返回前台会重连并同步，页面退出不关闭共享连接；后台释放socket。临时模型配置可放在忽略的 `.local/test-model.json`（baseUrl/apiKey/modelId），仅用于后续获授权的隔离联调，不是应用自动读取的个人配置。

受控真实桌面协议测试：`node scripts/test-desktop-contract.mjs`。这是最新桌面 WebServerService 与明确的业务fixture registry，不调用真实模型。

## 仓库边界
不复制 Apple 专有字体或 SF Symbols；不在 Android 执行 Agent；iOS 仅作只读参考。密钥、密码、Cookie、个人连接、local.properties、SDK、日志与签名文件不得提交。CI 不自动合并、打标签或推送分支。

## 文档
- [当前功能矩阵](docs/migration/current-feature-matrix.md)
- [架构和安全边界](docs/architecture.md)
- [协议](docs/protocol.md)
- [视觉基线](docs/ui-parity.md)
- [开发环境与版本](docs/migration/environment.md)
- [真实桌面契约验证](docs/migration/desktop-contract.md)
- [开发指南（AGENTS.md）](AGENTS.md)

## 许可证
[MIT](LICENSE)
