# 真实桌面 WebServerService 跨端契约验证

## 运行

在 Android 仓库执行。默认会运行 Gradle，请先协调构建互斥：

```sh
node scripts/test-desktop-contract.mjs
```

默认子命令为 `./scripts/gradle-local :app:testDebugUnitTest --tests dev.cpa.android.integration.DesktopServiceContractTest --rerun`，仅定向执行桌面契约测试。需要本仓库 Gradle/JDK/SDK 环境以及同级 `CodingProfessionalAgent` 已安装的 TypeScript / ws；无需准备独立 JVM jars。可通过 `CPA_DESKTOP_SOURCE` 指定符合锁文件的桌面源码目录。仍可在脚本名之后显式指定其他子命令（例如 `./scripts/verify.sh`）。

常规单测未提供环境变量时该集成测试跳过，不会自动连接任何桌面。

## Android API 37 设备路径（已安装 APK）

由主代理统一 build 并安装当前 app APK 与 androidTest APK 后运行；此脚本**不运行 Gradle、不安装 APK、不清数据、不操作 UI**：

```sh
ANDROID_SERIAL=emulator-5580 scripts/test-desktop-device-contract.sh
# 等价显式启动同一真实 source service harness：
ANDROID_SERIAL=emulator-5580 node scripts/test-desktop-contract.mjs bash scripts/test-desktop-device-contract.sh
```

仅允许 `emulator-5580`，并检查 emulator 标记与 API 37；显式指定其他 serial 直接拒绝，不会选择真机。默认 adb 来自 `${ANDROID_HOME:-$PWD/.local/android-sdk}/platform-tools/adb`，不存在才查 PATH。需要已启动的指定无个人数据模拟器、已安装 `dev.cpa.android` / `dev.cpa.android.test`，以及声明 `ACCESS_LOCAL_NETWORK` 的新 APK。脚本对目标包执行 `pm grant android.permission.ACCESS_LOCAL_NETWORK`（此权限授权会保留），通过 `adb reverse tcp:0 tcp:<host随机端口>` 分配独立设备端口，结束只移除本次映射，不影响其他映射。

`am instrument` 只运行 `dev.cpa.android.integration.DeviceDesktopContractTest`。URL、一次性随机 password 和 fixture opt-in 标记经 instrumentation args 传入；测试不读个人设置，未提供这些 args 的常规 instrumentation 运行会 **skip**，不连接任何服务。设备脚本反而要求真实执行一个测试；skip、instrumentation 异常或断言失败均返回非零。

设备测试覆盖下述同一完整顺序场景，包括设备端生产 `DirectTransport` 的 HTTP 登录、WebSocket hello、RPC 成功/失败与身份校验、真实广播事件、轮换后 **4401** 和旧 cookie 401。仍是**真实源码 service + 明确业务 fixture，不是模型回答**。

安全限制：设备路径无法避免 `adb shell am instrument -e` 参数在 host/device 进程列表中短暂可见；仅传本次生成的随机 fixture password，不得代入任何真实秘密。脚本关闭 shell tracing，不打印 password/cookie/URL，捕获但不输出原始 instrumentation 文本，成功只输出摘要，失败也仅输出无凭据提示。不要以外部 tracing/debug wrapper 或抓取进程参数的工具执行。没有保存原始输出文件。异常强杀（SIGKILL）可能阻止 reverse 清理，必要时只对指定 serial 手动移除本次映射，禁止 `--remove-all`。

设备路径已由主代理统一编译、安装并在API37模拟器实际执行：**OK (1 test)**。常规connected suite中的条件跳过项已在此脚本单独通过，不重复计数。交接时模拟器已停止，本次文档交接未重跑设备测试。

## 来源和边界

来源版本：`6673a2550cc0a03d9b3ac2ac67b3739844c47004`（桌面 HEAD）。`integration/desktop-contract/source-lock.json` 固定七个原始 TS 文件 SHA-256，运行前逐个验证；不符立即失败，不能静默用新源码。版本说明对应这组锁定的源码，不依赖桌面构建产物。

测试运行真实 `WebServerService`、`WebServerAuthService`、`WebRpcTransport`、`WebIdentityAdapter` 和 `WebRouteRegistry`。使用桌面现有 TypeScript 将原文件转译到临时目录；唯一模块路径重定位为已安装 ws 的 ESM 入口，不替换 service 逻辑。运行时 import 限制为锁定文件、Node 内置模块和 ws。profiling 源码在锁定范围内，但不启动 profiler。

**这不是完整真实模型 Agent 会话联调。** `setRpcDispatcher` 注入明确标为 fixture 的 Map registry，仅有 `fixture.echo/fail/event/rotate`。不启动 Electron、不加载插件、不实例化业务 Agent、不调用模型、工具、会话数据库或个人 settings。事件 payload 也是 fixture，但由真实 service 的 `broadcastEvent` 编码并经过真实 WebSocket 送达 Android 客户端；绝非 MockWebServer 替代桌面。

## 隔离与安全

- service 工作目录、转译产物、静态资源目录、HOME/USERPROFILE/XDG_CONFIG_HOME 均指向临时目录；不访问个人 `~/.coding-professional-agent/`。
- 仅绑定 `127.0.0.1`，以端口 `0` 请求系统随机端口；不使用绕过身份验证的 loopback listener。
- 进程内生成随机临时 password，经子进程环境变量 `CPA_CONTRACT_URL` / `CPA_CONTRACT_PASSWORD` 传入 runner；JVM 路径不作为命令行参数。设备路径进一步通过 instrumentation args 传入，存在上述短暂进程可见限制。两条路径均不写密码文件、不打印 password/cookie。
- 子进程最长 180 秒，退出或失败后 stop service 并删除临时目录。
- 桌面源码和依赖只读；所有脚本、测试和文档都在 Android 所分配目录内。无 core/data/ui/build/App 改动。

## 验证内容

1. HTTP auth status；错误 password 返回 401；成功登录真实 `cpa_web_session` cookie，包含 HttpOnly，cookie 可恢复 authenticated 状态。
2. HTTP RPC 无 cookie / 伪造 cookie 返回 401；成功 cookie 请求返回原 id 和 result envelope，HTTP clientId 进入真实 invocation context。
3. 无 cookie 的 WS upgrade 返回 HTTP 401（**不是** 4401）。Android `DesktopRpcClient.connect` 错误 password 失败；正确 password 经真实 `DirectTransport` 登录后收到 hello。
4. `connect/call/events` 稳定接口；观察真实 hello/clientId、rpc_result id/result、rpc_error；发送包含中文与数字的 JSON args。
5. 观察 transport 故意将 RPC 帧 clientId 改成 forged-client，真实 service 仍按该 socket 的 hello 身份生成 clientId/documentId/transport/pluginId。此观察器不模拟网络、不生成响应。
6. service 广播 `{type:event,event:{kind,data}}`，确认 wire data 是 JSON 字符串，Android events 解码为保留中文、嵌套对象及 fixture 标记的 JSON。
7. fixture 触发真实 `configure` password 轮换，已连接 Android transport 收到真实 close **4401**；此前 cookie 再请求返回 401。

定向 Gradle 单测不验证 Android 设备网络策略、UI、APK 或模型流式协议。共享服务轮换密码使该测试必须作为单个顺序场景执行，不能复用服务并发跑其它测试。
