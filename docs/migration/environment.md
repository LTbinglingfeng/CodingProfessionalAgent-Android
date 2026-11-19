# Android 构建环境与版本依据

核验日期：2026-11-14。本文件记录官方 HTTP metadata、兼容表及本机执行结果，不以 Context7 返回值作为发布证据。主代理已查询 Context7，构建代理另以官方 metadata 与实际构建核验版本。

> 本文件工具版本仍有效；下方最初构建结果属于阶段1历史。阶段2已安装并测试API37模拟器，主机56项通过。

## 选定版本

| 项目 | 固定版本 / 依据 |
|---|---|
| AGP | **9.3.1**：Google Maven 实际 POM 可下载；处于 Kotlin 官方完整兼容区间上界 |
| Kotlin / Compose compiler / serialization plugin | **2.4.21**：Maven Central metadata；官方表支持 Gradle 7.6.3–9.7.0、AGP 8.5.2–9.3.1 |
| Gradle wrapper | **9.7.0**：上述完整兼容区间上界；AGP 9.3 要求 >=9.5.0 |
| compileSdk | **37.1**（API 37 minor 1）：主代理最终复核改为 AGP 9.3.1 实际已测试上限；37.2虽为稳定平台，但会触发AGP兼容警告，不隐瞒或抑制警告 |
| targetSdk / minSdk | **37 / 33**（Android 13 起）；target 是整数主 API，compile 使用当前插件已测试的稳定 minor |
| SDK Build Tools | **36.0.0**，AGP 9.3 兼容表默认值；平台编译版本不要求 build-tools 同号 |
| SDK command-line tools | **23.0**，mac arm64 archive `16111833` |
| platform-tools | **37.0.1** |
| Compose BOM | **2026.09.00**，包含 foundation、UI、Material3；不强制任何应用主题 |
| activity-compose | **1.13.0** |
| lifecycle runtime/viewmodel compose / process | **2.11.0** |
| navigation-compose | **2.10.2** |
| kotlinx coroutines / test | **1.11.0** |
| kotlinx serialization JSON | **1.13.0** |
| OkHttp / legacy mockwebserver API | **5.5.1** |
| JUnit / AndroidX test ext / runner | **4.13.2 / 1.3.0 / 1.7.0** |
| Espresso | **3.7.0**，阶段2显式覆盖旧传递依赖，修复API37 InputManager反射问题 |
| Emulator / AVD | **37.2.13 / API37.0 default arm64**，本地cpa-api37、端口5580，交接时已停止 |

官方最新 AGP metadata 实际已列出 **9.5.1**，其 POM GET 成功（不是假设未来版本）；Gradle current API 为 **9.9.1**。但 Kotlin 2.4.21 官方完整兼容表尚未覆盖这两者，所以没有盲目采用绝对最新版本。选择最新的完整兼容组合 AGP 9.3.1 + Gradle 9.7.0。AGP 9.3 官方表最大支持 API 37。AGP 9 内置 Kotlin，因此 app 不重复应用 kotlin-android；根插件声明 `apply false` 提升 KGP 依赖到 2.4.21，Compose/serialization compiler 同版本。实测完整编译通过。

## 官方证据

- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1.pom
- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.1/gradle-9.5.1.pom
- https://developer.android.com/build/releases/agp-9-3-0-release-notes （Gradle 9.5.0、JDK 17、Build Tools 36.0.0、最大 API 37）
- https://developer.android.com/build/releases/gradle-plugin （请求当日指向 AGP 9.5）
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://kotlinlang.org/docs/gradle-configure-project.html （KGP compatibility table）
- https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml
- https://dl.google.com/android/repository/repository2-3.xml （稳定 channel-0 archive、平台与校验值）
- https://services.gradle.org/versions/current
- https://services.gradle.org/distributions/gradle-9.7.0-bin.zip.sha256

依赖 metadata 均通过对应 `https://dl.google.com/dl/android/maven2/<group path>/<artifact>/maven-metadata.xml` 或 `https://repo.maven.apache.org/maven2/<group path>/<artifact>/maven-metadata.xml` 查询。原始 HTML/XML/POM/JSON 保留在忽略目录 `.local/metadata/`。

## 本机隔离环境

- macOS 27.2、aarch64；已有 Zulu OpenJDK **21.0.11+10-LTS**，路径 `/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home`。AGP 要求 JDK >=17；Java/Kotlin 字节码目标为 17。
- SDK：项目 `.local/android-sdk`；工具/下载/日志：`.local/`。
- Gradle cache：`.local/gradle-user-home`；Android user home：`.local/android-user`。
- `scripts/gradle-local` 为单次进程设置这些路径，不修改 shell profile、全局 Gradle 配置或全局 SDK。
- `local.properties` 只保存本机 SDK 路径，已忽略；签名文件、签名属性及 `.env*` 也忽略。未设置 release 签名或密码。
- 用户已明确授权下载及接受 SDK 许可；已实际执行 `sdkmanager --licenses` 并完成所需包安装。

## 可执行命令

在项目根目录：

```sh
./scripts/gradle-local --version
./scripts/gradle-local :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
# 有 API >=33 的设备/模拟器后：
./scripts/gradle-local :app:connectedDebugAndroidTest
```

`gradlew`、`gradlew.bat`、wrapper JAR 均由官方 Gradle 9.7.0 的 `wrapper` task 生成。直接 `./gradlew` 也可执行，但若希望所有缓存保持项目本地，应使用上述脚本或显式设置 `GRADLE_USER_HOME`、`ANDROID_USER_HOME`。

SDK 安装复现（命令行工具解压于 `.local/android-sdk/cmdline-tools/23.0` 后）：

```sh
export ANDROID_USER_HOME="$PWD/.local/android-user"
yes | .local/android-sdk/cmdline-tools/23.0/bin/sdkmanager --sdk_root="$PWD/.local/android-sdk" --licenses
.local/android-sdk/cmdline-tools/23.0/bin/sdkmanager --sdk_root="$PWD/.local/android-sdk" \
  'platforms;android-37.1' 'build-tools;36.0.0' 'platform-tools'
printf 'sdk.dir=%s/.local/android-sdk\n' "$PWD" > local.properties
```

工具下载地址：`https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip`。
其 SHA-1 与官方 SDK metadata 一致：`ad03dc49bfacfd52c110b14104ea548b8a07e830`。
Gradle ZIP SHA-256 已实际核验并写入 wrapper properties：
`84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`。

## 实测结果与网络证据

主代理最终修订：安装 `platforms;android-37.1` 并调整 compileSdk，消除37.2未测试兼容警告；移除 `unitTests.isReturnDefaultValues`，不以静默Android桩默认值掩盖单元测试问题。随后 `./scripts/verify.sh`（assembleDebug + testDebugUnitTest + assembleDebugAndroidTest + lintDebug）实际成功，22项单元测试均通过。

- Google SDK / Maven Central / Google Maven 可达，SDK 安装成功，所有构建依赖解析成功。
- Gradle 单连接下载非常慢：services.gradle.org 跟随重定向后 595 秒仅下载约 23.2 MiB / 143 MiB，约 40 KiB/s；downloads.gradle.org 直接请求 200 秒仅约 12 MiB。日志保留 `.local/downloads/gradle-download.log`、`gradle-direct.log`。这是低吞吐，不是 artifact 不存在。
- 改用官方 downloads.gradle.org HTTP Range 并发分块下载，合并后对照官方 SHA-256 校验成功；完整 ZIP 已放入项目 wrapper cache，当前执行命令不会再次下载发行包。分块脚本位于 `.local/download-gradle.py`，无第三方镜像。
- **首次 assembleDebug + testDebugUnitTest：BUILD SUCCESSFUL，2m 7s。**
- **通过正式 wrapper 运行 assembleDebug + testDebugUnitTest + assembleDebugAndroidTest：BUILD SUCCESSFUL，4s。**
- JUnit：**22 tests，0 failures，0 errors**（DirectTransport 12、DesktopRpcClient 7、ThreadViewModel 3）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
- `adb devices` 无设备，因此仅编译 instrumentation tests，**未运行设备测试**。
- 非阻塞警告：应用网络代码的多余 safe-call、Compose createComposeRule 旧 API 弃用、graphics path native library 保持未 strip。未更改其他代理的源代码。

本构建代理仅创建/修改构建配置、wrapper、工具脚本、忽略规则及本文件；未创建 Kotlin、Manifest 或 UI 资源。

## 阶段3附件传输依赖增量

WebSocket改用 `org.java-websocket:Java-WebSocket:1.6.0`，HTTP仍用OkHttp；测试增加 `com.squareup.okhttp3:okhttp-tls:5.5.1`。原因、版本来源和验证见 Git 历史中的附件记录。

## 阶段3 Markdown依赖增量

行内AST解析使用 `org.commonmark:commonmark:0.32.0` 和 `org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0`。官方来源、范围与设备验证见 Git 历史中的Markdown记录。
