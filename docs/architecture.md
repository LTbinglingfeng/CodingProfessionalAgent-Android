# Android 架构与决策

## 已确认
- 独立 Git 仓库、单 `app` 模块，Kotlin + Compose，自建设计系统；MVVM + StateFlow 单向数据流，不使用全局 Redux/MVI、跨平台运行时或本地 Agent。
- 包名 `dev.cpa.android`；用户委托选择较新最低版本，采用 Android 13 / API 33。compile/target 与工具版本见 [环境记录](migration/environment.md)。只生成 debug 签名，不配置发布凭据。
- iOS 只读基线 `16b14eadc815e091698a034db5d32dbe4f2a7474`。经用户明确许可仅桌面 `dev` 快进至 `6673a2550cc0a03d9b3ac2ac67b3739844c47004`。

## 职责边界
`feature Route → ViewModel → Repository → DesktopRpcClient → DesktopTransport (Direct / Tunnel)`。
- Route 做生命周期订阅、导航和平台入口；Screen 只收状态/发操作，不访问网络。
- Foundation 组件与 CPA token 实现视觉，系统返回、Insets、IME、语义遵循 Android。
- 应用级组件拥有连接；页面不能释放其他观察者共享的 socket。
- 连接 ID + session ID 是远程状态与导航主键。草稿和稳定路由可恢复；焦点、菜单、滚动属于局部状态；远端内容由桌面权威管理。
- 初期样板使用明确标记的fixture Repository。阶段2默认入口已切到真实RemoteDesktopRepository；fixture通过独立演示入口保留，不能将演示Ask或静态流式计为远程功能。

## 通信与恢复约束
- Cookie jar、RPC pending map、认证和会话状态按连接隔离；连接更换使用 generation 防止迟到回调污染。
- RPC 只负责请求 ID、响应匹配、超时、取消、错误和事件分流。断开清理所有 pending；不自动重发发送消息/启动任务。
- 前台恢复：重新鉴权/连接、订阅，再获取权威快照；快照与实时事件的竞态规则必须按协议审计实施，不能假设服务器有事件序号。
- 错误凭据进入需用户修复状态，不无限重试。临时网络故障采用有上限退避；后台不依赖永久连接。
- Tunnel 从接口起保留；未实现时明确拒绝，不能降级伪装直连。

## 存储与安全
- 暂不引入 Room。后续连接元数据与草稿使用轻量持久化；秘密用 Android Keystore 保护的加密存储，不把密码写入 SavedStateHandle、日志、备份或测试fixture。
- 删除连接须同时移除密码、Cookie、会话缓存及订阅。禁用包含敏感数据的备份。
- 局域网 HTTP 不具备保密性。阶段2经用户确认采用系统network-security-config允许任意域名明文、Repository逐连接allowCleartext检查、编辑地址重新授权的组合；默认HTTPS，禁止登录重定向及trust-all。API37另需系统本地网络运行时权限。静态明文warning明确保留。
- SDK、缓存、日志、local.properties、签名材料不跟踪；禁止提交个人地址与凭据。

## 当前边界
本文件描述目标与已选决策，并不声明全架构已经实现。实际功能状态以功能矩阵、代码和验证报告为准。无真机/iOS截图时不声称视觉验收通过。
