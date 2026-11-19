# CPA Android 开发指南

本仓库是 CPA 的原生 Android 客户端：Kotlin + Jetpack Compose，单一 `:app` 模块，最低支持 Android 13（API 33）。先用 [README](README.md) 了解运行方式；接续迁移任务时，查阅[功能矩阵](docs/migration/current-feature-matrix.md)，区分已实现的行为和尚未验证的场景。

## 开始前

先运行 `git status --short`，保留与当前任务无关的改动。版本与安装步骤以[环境说明](docs/migration/environment.md)为准；不要把本机的 SDK 路径或模拟器配置写入仓库。

## 去哪里改

业务源码位于 `app/src/main/java/dev/cpa/android/`：

- `feature/remote/`：连接、项目和会话界面及其 ViewModel。调整 Compose 界面时，优先复用 `core/designsystem/` 的主题和组件；涉及 iOS 交互对齐时参考 [UI 对齐说明](docs/ui-parity.md)。
- `data/repository/`：桌面连接和会话状态的协调。修改发送、停止或重连行为时，在仓库层覆盖回执丢失场景：结果未知不代表发送失败，不要自动重发。
- `core/network/`、`core/model/`：传输实现与数据模型。修改桌面消息格式前先核对[协议说明](docs/protocol.md)，并判断是否同时影响直连与隧道。
- `core/storage/`：本地偏好与凭据存储。连接凭据沿用现有 Keystore 加密路径，不写入日志、测试样例或版本库。

新增用户可见文案时，同步维护 `app/src/main/res/values/` 和 `app/src/main/res/values-b+zh+Hans/` 的同名资源；桌面返回的原始内容不要当成本地文案翻译。新增回归测试优先放在 `app/src/test/`；依赖 Compose 界面或 Android 平台行为的测试放在 `app/src/androidTest/`。

## 如何验证

从仓库根目录执行。使用安装在项目本地的 `.local/android-sdk/` 时，下表中的 `./gradlew` 可换成 `./scripts/gradle-local`；使用已配置的系统 SDK 时，直接用 `./gradlew`。

| 变更范围 | 验证方式 |
| --- | --- |
| 状态、协议或其他 JVM 逻辑 | 在 `app/src/test/` 添加或更新回归用例；运行 `./gradlew :app:testDebugUnitTest`。 |
| 构建与主机端综合检查 | 运行 `./scripts/verify.sh`：构建 debug APK、运行 JVM 测试、编译设备测试 APK 并执行 lint。**编译设备测试不等于运行设备测试。** |
| Compose 交互或 Android 平台行为 | 在 `app/src/androidTest/` 补对应场景，连接模拟器或测试设备后运行 `./gradlew :app:connectedDebugAndroidTest`。 |
| 桌面与隧道协议 | 查阅[桌面契约说明](docs/migration/desktop-contract.md)和[隧道契约说明](integration/tunnel-contract/README.md)，区分主机契约检查与设备端验证。 |
| 真实模型联调 | 仅在获得授权后使用隔离测试项目和 `scripts/test-real-model-device.mjs`；不要用受控测试数据的结果代替真实模型结论。 |

交付时写明实际运行的检查、结果和未验证的部分。仅改文档时，检查相对链接并运行 `git diff --check`，无需重跑 Android 构建。

## 提交与发布

- 提交前运行 `./scripts/check-repository.sh`，并检查暂存差异（`git diff --cached`）。脚本只检查忽略规则和已跟踪的产物路径，不会扫描文件中的秘密。
- 不提交个人绝对路径、凭据、`local.properties`、`.local/`、日志、签名文件或构建产物。清理仓库时，已忽略的本地 SDK、AVD、缓存和联调配置默认保留。
- 未经明确要求，不提交、不重写历史、不推送。发布时只推送指定分支，不使用 `git push --mirror`，避免上传内部引用。
