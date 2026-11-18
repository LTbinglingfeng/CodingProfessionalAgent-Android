# CPA Android 开发指南

本仓库是 CPA 的原生 Android 客户端：Kotlin + Jetpack Compose，单一 `:app` 模块，最低支持 Android 13（API 33）。先用 [README](README.md) 了解运行方式；接续迁移任务时，查阅[功能矩阵](docs/migration/current-feature-matrix.md)，区分已实现的行为和尚未验证的场景。

## 去哪里改

业务源码位于 `app/src/main/java/dev/cpa/android/`：

- `feature/remote/`：连接、项目和会话界面及其 ViewModel。调整 Compose 界面时，优先复用 `core/designsystem/` 的主题和组件；涉及 iOS 交互对齐时参考 [UI 对齐说明](docs/ui-parity.md)。
- `data/repository/`：桌面连接和会话状态的协调。修改发送、停止或重连行为时，在仓库层覆盖回执丢失场景：结果未知不代表发送失败，不要自动重发。
- `core/network/`、`core/model/`：传输实现与数据模型。修改桌面消息格式前先核对[协议说明](docs/protocol.md)，并判断是否同时影响直连与隧道。
- `core/storage/`：本地偏好与凭据存储。连接凭据沿用现有 Keystore 加密路径，不写入日志、测试样例或版本库。

新增用户可见文案时，同步维护 `app/src/main/res/values/` 和 `app/src/main/res/values-b+zh+Hans/` 的同名资源；桌面返回的原始内容不要当成本地文案翻译。开始编辑前先看 `git status --short`，保留与当前任务无关的未提交改动。

## 如何验证

从仓库根目录执行。若尚未配置 SDK，先看[环境说明](docs/migration/environment.md)。

| 变更范围 | 验证方式 |
| --- | --- |
| 状态、协议或其他 JVM 逻辑 | 在 `app/src/test/` 添加或更新回归用例；运行 `./gradlew :app:testDebugUnitTest`。 |
| 构建与主机端综合检查 | 运行 `./scripts/verify.sh`：构建 debug APK、运行 JVM 测试、编译设备测试 APK 并执行 lint。**编译设备测试不等于运行设备测试。** |
| Compose 交互或 Android 平台行为 | 在 `app/src/androidTest/` 补对应场景，连接模拟器或测试设备后运行 `./gradlew :app:connectedDebugAndroidTest`。 |
| 真实桌面、隧道或模型行为 | 按 [README](README.md) 选择对应的 `scripts/` 联调入口，使用隔离的测试项目。不要用主机或模拟数据的结果代替真实联调结论。 |

交付时写明实际运行的检查、结果，以及因缺少设备或服务未验证的部分。不要提交 `local.properties`、`.local/`、签名文件或构建产物。
