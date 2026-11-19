# CPA 移动端协议：源码审计基线

## 证据与适用范围

- **确认（静态源码）**：iOS 仓库 `CodingProfessionalAgent-iOS`，HEAD `16b14eadc815e091698a034db5d32dbe4f2a7474`；桌面仓库 `CodingProfessionalAgent`，HEAD `6673a2550cc0a03d9b3ac2ac67b3739844c47004`。审计时两者 `git status --short` 为空。
- 下文 I = iOS `CodingProfessionalAgent/`，D = 桌面仓库根目录。主要依据 I `Services/DesktopClient.swift`、`Models/{Models,RemoteModels,JSONValue}.swift`、`Views/Thread/ThreadModel.swift`、`Services/Tunnel{Protocol,ClientManager}.swift`。
- **确认不等于联调通过**：本次修订只读源码；主代理已执行下述服务端源码测试，但未做 Android 真实联调、未连接生产网关、未读取个人配置/Keychain。示例为构造值，不是抓包。
- **更新基线**：桌面已由主代理获授权 fast-forward 到上述版本；现有 ask main RPC 注册及 tunnel bridge/codec，不再适用旧版“无 ask/无 tunnel”结论。iOS 版本与行为事实保留。
- **测试证据（主代理执行）**：`pnpm exec vitest run --config vitest.electron.config.ts test/webServerService.test.ts test/cliProxyTunnelProtocol.test.ts test/cliProxyTunnelService.test.ts`，3 文件、51 测试全部通过，1.27 秒。这是服务端源码测试，不是 Android 真实联调；仍无生产隧道地址或个人凭据。

## 1. 直连与鉴权（确认）

I `Models.swift` 构造 `http://host:port` 和 `ws://host:port/api/ws`；IPv6 加方括号，默认解码端口 18080。iOS 直连不支持配置 HTTPS scheme。

1. GET `/api/auth/status`，可带本连接的 `Cookie`，响应 `{ "required": true, "authenticated": false }`。
2. required=false：不登录，清空本次 cookie；authenticated=true：复用 cookie。
3. 否则 POST `/api/auth/login`，`Content-Type: application/json`，body `{ "password": "原样密码" }`。
4. HTTP 401 登录错误停止自动重试；其他非 2xx 按连接错误处理。取 `Set-Cookie` 第一条值的首个 `;` 前 cookie pair，附在 WS 握手的 `Cookie`，不是 Authorization Bearer。
5. 等收到 `hello` 才标记 connected，然后 resync；WS 建立不等于同步完成。

D `src/main/services/webServerAuthService.ts` / `webServerService.ts`：真实 cookie 名 **`cpa_web_session`**，32 随机字节转 64 位 hex；HttpOnly、SameSite=Strict、Path=/；TLS 或 `x-forwarded-proto=https` 才 Secure；无 Max-Age。密码精确比较（不 trim），SHA-256 后 timingSafeEqual；token 集合在内存，密码改变清空；只有 public 与内部 loopback listener 均停止后才清空，单独 stop public 不清 loopback 使用的会话。无密码等于开放访问，不是匿名只读。启用/更换密码踢出 public 与 loopback WS，close 4401。受保护路由/WS 在有密码时检查 cookie 和 same-origin；没有 Origin 的原生客户端允许通过。测试中的 `cpa_sid` 是 mock 名，不能硬编码成生产名。

每个 DesktopClient 使用 ephemeral HTTP session，禁用自动 cookie 共享；cookie 仅内存、按 connection 隔离。WS 401/4401 清 cookie 后重连；隧道 ws_open 401 有一次立即重新登录。桌面口令与网关 API Key 是两个独立凭据。

## 2. 真实 RPC envelope（不是 JSON-RPC 2.0）

I `DesktopClient.swift:578–839`；D `src/main/services/web/rpcTransport.ts`：

```json
{"type":"hello","clientId":"client_ws_1_1700000000000"}
{"type":"rpc","id":"android_1","method":"session:get","args":["s1"],"clientId":"client_ws_1_1700000000000"}
{"type":"rpc_result","id":"android_1","result":{"id":"s1","entries":[]}}
{"type":"rpc_error","id":"android_1","error":"Unknown RPC method ..."}
{"type":"event","event":{"operationId":"stream-s1","sequence":1700000000000,"kind":"session:stream-event","data":"{\"sessionId\":\"s1\",\"runId\":\"r1\",\"event\":{\"type\":\"assistant-update\",\"entry\":{\"id\":\"a1\",\"kind\":\"assistant\",\"content\":[]}}}"}}
```

- args 是**位置参数数组**，不是 params 对象。iOS ID 为 `ios_<递增序号>_<epoch毫秒>`；建议 Android 自己命名，唯一关联即可。
- result 任意 JSON；服务端 undefined 经 JSON 序列化可缺省，iOS 当 null。iOS error 提取顺序：error 字符串 → error.message → errorDto.message → 通用错误；当前 D 发送字符串 error。
- event.data 在当前 D 是 JSON **字符串**；iOS 也容忍已经是对象，解析一次后按 kind 分发。operationId/sequence/sourceClientId 可存在，iOS 没有据此排序或补放。
- D WS 身份来自服务端分配的 wsContext.clientId，**忽略请求体伪造 clientId**；HTTP `/api/rpc` 则接受 body.clientId（不等于认证身份）。iOS 不用 HTTP RPC。D 支持应用层 `{type:"ping"}` → `{type:"pong"}`；iOS唤醒使用 WS 控制 ping。
- 无 wire 协议版本协商、事件 ACK、cursor 或断线续传保证。重连必须重新取快照。
- iOS RPC 默认 30 秒；readDir/gitBranches 15 秒、skill/environment read 10 秒、fork set 60 秒、writeFile 300 秒。断线立即 fail 所有 pending，不自动重放有副作用调用。大附件后台编码也必须保持发送顺序；超时/旧 generation 队列不得再发。
- iOS 直连入站消息上限 64 MiB，不是服务端所有入口的统一上限；D HTTP body 通常 16 MiB、login 16 KiB。

## 3. iOS 使用的方法全集与位置参数

以下是 I DesktopClient 全部字面量 RPC 调用，不代表桌面的全部 API。返回列是 iOS 使用的部分；忽略返回不代表服务端一定返回 null。

| 方法 | args | 消费的结果 / 行为 |
|---|---|---|
| session:getActiveRuns | `[]` | ActiveRun[] |
| kvstore:get | `["projects" / "cachedModels" / "app-state"]` | 项目数组 / 模型数组 / settings 对象 |
| kvstore:set | `["projects", rawProjects]` | 整表写；保留未知字段 |
| session:listSessions | `[]` | RemoteSession[]，顶层列表 |
| session:get | `[sessionId]` | null 或 `{id,version,entriesRevision,entries,subAgents?,workLocation?,worktreePath?,environmentId?,worktreeSetup?}`；iOS 忽略 revision |
| session:setMeta | `[partialMeta含id]` | 创建/更新元数据，等待广播更新 UI |
| session:set | `[newSessionId,{...meta,entries}]` | fork 写快照，随后 setMeta 公告 |
| session:search | `[query,limit]` | `{sessionId,title,projectId?,branch?,updatedAt,matchType,snippet?}[]` |
| session:delegateRun | `[request]` | D 返回 sessionId；只表示交接请求，不表示运行完成 |
| session:abortRun | `[sessionId]` | D 广播 abort-run；不保证即刻停止 |
| session:queryMetrics | `[{"topSkillsLimit":10000}]` | `summary.topSkills[{name,count}]` |
| native:runtimeInfo | `[]` | homeDir,userConfigDir,appConfigDirName,isDebug 等 |
| native:readDir | `[path]` | `{name,isDir,isSymbolicLink,...}[]` |
| native:readFile / native:readFileIfExists | `[path]` | `{dataBase64,...}`；后者可不存在 |
| native:fileExists | `[path]` | bool |
| native:writeFile | `[path,base64]` | 上传附件，iOS 忽略返回 |
| native:runProcess | `[{operationId,executable:"git",args:[...],cwd,env:null}]` | exitCode,stdoutBase64 等 |
| ask:getRequests | `[{sessionId}]` | `{request:AskRequest或null,resolved:null}`（iOS 查询不带 toolCallId）；D main 已注册 |
| ask:submitAnswer | `[sessionId,toolCallId,decision]` | D 返回 bool，iOS 忽略返回 |
| ask:cancelCountdown | `[sessionId,toolCallId]` | D 返回 bool；iOS 严格 bool true 确认停止 |

D 另有 `session:delete`，iOS 此 DesktopClient 没有调用，不要将监听 deleted 误写成调用实现。

### delegateRun（确认 iOS shape）

```json
{"requestId":"req-u1","sessionId":"s1","text":"分析 $audit","projectId":null,"branch":null,"userEntryId":"u1","userEntryCreatedAt":1700000000000,"skillReferences":[{"start":3,"name":"audit"}],"modelId":"model-id","reasoningEffort":"medium","speed":"standard","images":[{"data":"BASE64","mimeType":"image/png","name":"image.png"}],"followUpMode":"queue"}
```

- projectId/branch 明确 null；modelId/reasoningEffort/speed 没有时省略；images 无图省略；skillReferences **总发送数组**，空数组也必须保留，不能由文本猜芯片。
- `start` 是 UTF-16 offset；芯片长度是 `$` + name 的 UTF-16 长度，非 UTF-8 字节数。
- 活跃运行追加可 `followUpMode: queue|steer`；无运行省略。编辑复用 userEntryId，增加 `editMessageId` 和新的 `requestId:"edit-..."`。
- resume/compact 简化为 `{requestId:"resume-..."或"compact-...",sessionId,text:"",action:"resume"或"compact"}`，不是独立 RPC。
- D session-manager 将请求放 pending 并广播 delegate-run；requestId 有去重逻辑，但不能宣称永久 exactly-once。失败/超时可能已经交接，重试应先核对状态。

### 数据兼容

- 所有时间为 epoch **毫秒**；JSONValue 数值用 Double，容忍未知字段，decodeList 跳过坏项。
- RemoteProject：id,name,path?,paths?,pinned,updatedAt；allPaths 去空去重，缺失的 legacy path 插到首位。
- RemoteSession：id,title,projectId?,scheduleId?,parentSessionId?,branch?,baseBranch?,pinned,unread,archivedAt?,firstPromptAt?,modelId?,reasoningEffort?,speed?,workLocation?,worktreePath?,environmentId?,worktreeSetup?,createdAt,updatedAt。unread 是 **true / false / "error"**，不是单纯 bool。parentSessionId 非空或 archivedAt>0 不显示为顶层活动会话。
- ActiveRun：sessionId,runId,clientId,status,updatedAt。status 非 idle 均视为运行中。
- ConversationEntry：id,kind(user/assistant/toolResult/compaction/未知),createdAt,content[],status?,stopReason?,errorMessage?,usage.totalTokens?,toolCallId?,toolName?,isError,interrupted,summary?,pendingStatus?,skillReferences?,completedAt?,pausedMs?,displayMetadata?。
- ContentBlock：`{type:"text",text}`、`{type:"thinking",thinking}`、`{type:"image",mimeType,data}`、`{type:"toolCall",id,name,arguments:任意JSON}`。未知类型不得使整会话失败。
- SubAgentRecord：id,parentSessionId,sessionId,name,modelId,status,createdAt,updatedAt，以及 color/icon/reasoningEffort/speed/parentToolCallId/errorMessage/completedAt/pausedMs 可选；queued/running 视为活跃。子会话 ID 优先 sessionId，否则 agent id。
- RemoteModel：id,label,supportsFast,reasoningLevels[{id,fallbackLabel?}],contextWindow,input[]；`input` 含 image 才支持图片。app-state.settings 下读取 modelId/reasoningLevel/speed/modelSettings/skills。模型顺序与禁用、reasoning 过滤属于 UI 策略；iOS 全禁用时回退全部模型，并过滤 ultra，不是 wire 约束。

## 4. 事件与会话合并（确认 iOS 行为，非理想化协议）

| kind | data 解码后 | 处理 |
|---|---|---|
| projects:updated | 项目数组 | 替换列表 |
| session:meta-updated | RemoteSession | 按 id upsert；空标题保旧，createdAt=0 保旧时间；其他缺省字段并非逐字段 patch |
| session:deleted | `{sessionId}` | 移除 session/run，Thread 标记 deleted |
| session:run-status | ActiveRun | 更新运行；旧 runId 的 idle 不清除新 run（optimistic 除外） |
| session:entries-updated | `{sessionId}` | 刷技能统计；不运行才权威 reload |
| session:subagent-state | `{parentSessionId,agents:[...]}` | 逐记录 updatedAt 合并 |
| session:stream-event | `{sessionId,runId,event}` | Thread 按 sessionId 过滤；当前实现未按 runId 过滤 |
| ask:state-changed | `{sessionId,request}` | D main 已注册并发出；iOS 防御性接收、另有轮询 |
| client:synced | null | **客户端合成**，不是服务器事件；触发权威 reload |

stream event 的 `user-entry / assistant-start / assistant-update / assistant-end / compaction-end` 带 **完整 entry 替换**，不是 text delta。`tool-start` 含 toolCallId/toolName；`tool-update` 的 result.details.nestedTools 存 live details；`tool-display-update` 合并 displayMetadata；`tool-end` 存 entry 并清运行标记；`agent-start` 清未完成尾消息；`error` 读 message。nested=true 或有 parentToolCallId 的事件不套入本 Thread。工具匹配 ID 截取首个 `|` 前部分。

重连按顺序取 runs → projects → sessions → cachedModels → app-state，generation 防旧连接覆盖；单项失败保旧值，最后仍可能 hasSynced=true，**不是事务性全量成功**。同 generation 的重叠 resync/事件没有 revision 屏障。

Thread reload 串行化，记录请求期间 updatedDuringReload；以 snapshot 顺序为基底，覆盖这期间的 live entry。权威 reload 只保留 snapshot 外的本次 live；普通 reload 保留未持久化 + live。idle 后等 0.8 秒普通 reload，避免最后消息尚未落盘丢失；entries-updated 默认 debounce 0.3 秒。编辑 overlay 隐藏后续旧 entry，直到快照确认新文本/skillReferences 且不含 droppedIds；20 秒未启动放弃 overlay 并权威重载，保留晚到 user-entry 的修正信息。resume 清 dangling assistant 或将带 toolCall 的尾消息规范为 done/toolUse，维护 removedEntryIds 防旧快照复活；失败回滚。

SubAgent 按 updatedAt 拒绝旧实时记录；快照缺少的现有 agent 保留，新快照缺 completedAt/pausedMs 时保留 live 值，按 createdAt 排序。fork 从原始 JSON 复制（保未知字段），定位 entry 或 assistant content block ID、连带后续 toolResult；重建 entry/session ID，streaming→done；worktree fork 不共享旧 worktreePath。未知截断 ID 会复制全会话；顶层元数据找不到则拒绝子代理 fork。

Ask 只在顶层运行中轮询：初期 0.5 秒，后续 3 秒，另有 3 秒发现监控；lookup failure ≠ request=null；sequence 防旧请求复活。decision 为 `{type:"selected",option:{title,description?,recommended?},index}` / `{type:"custom",text}` / `{type:"skipped"}`。当前 D `plugins/bundled/cpa.core.ask/main/index.ts` 已实现这些 RPC：submitAnswer 参数不足或当前 toolCallId 不匹配返回 false，否则记录解决动作并发 ask:action；有当前请求时还清请求并发 state-changed。没有当前请求时也可返回 true，故 true 不等于 agent 已消费。cancelCountdown 要求当前匹配，清 countdownDeadline 并发事件、返回 true。getRequests 可额外传 toolCallId 取 resolved；解决记录为内存最多 256 条，不是持久 ACK。

### 真实发送确认丢失观察（后续P0故障验收）

测试专用转发层在完整桌面已返回匹配delegateRun的rpc_result后丢弃确认并断开客户端：桌面模型仍完成任务，Android保留unknown与草稿；提前确认被拒，恢复权威刷新后仍需人工acknowledgeOutcome，确认前后Activity重建均无额外delegateRun。此为实际故障验收证据，不新增ACK/cursor/恢复协议，也不意味着生产客户端能知道丢失的确认。最终计数为1次发送/1次转发/1条用户消息/1个runId。

### 真实完整宿主停止观察（后续P0联调）

Android收到实际模型文本后点击停止，权威session:get末尾assistant的status/stopReason均为aborted，run状态回idle；本轮没有收到同名aborted流事件。桌面 `useAgentStream.ts / abortFlightAndCleanup` 会在本地adapter应用aborted/agent-end，并不保证该清理路径广播相同流事件。验收停止须结合运行状态和权威快照，不能强求一个未承诺的wire事件，也不能仅凭abortRun的RPC成功推断已停止。

## 5. Tunnel wire 与生命周期

**确认：iOS codec/manager 与最新 D `src/main/services/{cliProxyTunnelService.ts,tunnel/tunnelProtocol.ts}` 均有实现；下文客户端生命周期仍指 iOS。生产网关部署及 Android 互操作待验证。**

外层 WS：`ws[s]://host[:port]/[basePath/]v1/tunnel?role=client&uuid=<encoded>&name=<encoded>`，header `Authorization: Bearer <API Key>`，不把 key 放 query。支持 http/https/ws/wss 转换，去默认端口与末尾 /v1 或 /v1/tunnel；拒绝 userinfo/query/fragment、无 scheme、非法端口。UUID 实际允许 1–128 字节可打印 ASCII 0x21–0x7e，并非强制 RFC UUID；name 清控制符、最长 256 UTF-8 bytes。API Key trim 后拒绝控制符。

文本控制帧：

```json
{"type":"list_servers","request_id":"sync-1"}
{"type":"server_list","request_id":"sync-1","servers":[{"uuid":"srv1","name":"Mac","type":"CodingProfessionalAgent"}]}
{"type":"server_online","server":{"uuid":"srv1","name":"Mac","type":"CodingProfessionalAgent"}}
{"type":"server_offline","server":{"uuid":"srv1"}}
{"type":"error","code":"target_not_found","target_uuid":"srv1","message":"offline"}
```

只展示 type 精确等于 CodingProfessionalAgent 的在线服务；offline 只按 uuid 删除。不要求 server_list 的 request_id 匹配才应用。收到首次 server_list 才 ready；server_online 本身不是首次 ready。

二进制外层（按字节）：`version:u8=1 | payloadType:u8=2 | srcLen:u8 | dstLen:u8 | srcASCII | dstASCII | payload`。无 UUID 固定 16 字节、无 base64 包裹、无外层 payload 长度字段。

payload 为 mux：`metaLen:u32 big-endian | UTF-8 JSON metadata | raw body`。metaLen 1..65536；**整个 mux** ≤67,108,864 bytes（body 还需扣 4+metaLen）；外层接收 ceiling 67,109,376 bytes。出站控制帧限 4096；入站 server_list 可超过 4096，至 transport ceiling。

| metadata.type | 字段 | body |
|---|---|---|
| http_request | id,method,path,headers? | HTTP 原始 body |
| http_response | id,status,headers? | HTTP 原始 body |
| ws_open | connId,path,headers? | 通常空；鉴权 cookie 在 headers |
| ws_open_result | connId,ok,status?,error?,headers? | 通常空 |
| ws_data | connId,opcode（客户端发 text） | 原始 RPC/event JSON 字节 |
| ws_close | connId,code?,reason? | 通常空 |

headers 值容忍字符串或字符串数组，大小写无关提取；status 必须整数 100..999，close code 整数 0..65535（codec 不是完整 RFC close-code 校验器）。iOS 接收 ws_data 忽略 opcode，Android 应明确定义 text/binary 策略。

Manager 以 **规范化 WS URL + trim 后 API Key** 共用一个外层连接，lease 引用计数；不同桌面用独立 connId、HTTP id、cookie。每个响应校验 dstUUID=本客户端、payloadType=2、srcUUID=该 pending/stream 的目标 serverUUID，不可只用 connId。最后 lease 释放先发虚拟 close，再 drain，最长 250ms 关外层。

HTTP/ws_open 默认总预算 15 秒（包括等 ready），不是每阶段重新 15 秒。外层断线 fail 所有 pending/stream；清理旧虚拟连接记录最多 128，重连/新 open 前重发 close；超时 open 的晚到成功也 close。target_not_found/invalid_target_role 清离线目标；其他有 target 的错误（如 target_buffer_full）立即失败该目标的请求，发 close 并记下重试清理，不牵连其他 server。

普通重试基数 1.5 秒，409 基数 2 秒，指数上限基数 20 秒再乘 0.85–1.15 jitter；连续 3 个 409 轮换持久化 clientUUID。401/403、400、1008 且 reason 含 api key revoked 为终止错误；wake 不自动恢复 terminal failed，重新 acquire 可显式 retry。桌面离线保留 tunnel lease，server_online 可立即重连桌面。

### 最新桌面桥（服务端行为，不替代 iOS 策略）

- 桌面以 `role=server&uuid=...&name=...&type=CodingProfessionalAgent` 加 Bearer key 连接网关，使用同一 v1/0x02 framing。内部 listener 为引用计数的 `127.0.0.1:随机端口`，复用 Web 鉴权、RPC、事件；public Web 可保持 stopped，停止 public 不断开桥。
- bridge 校验目的 UUID/payloadType，以 srcUUID 分 peer，再以 id/connId 隔离；源 UUID 可信性仍依赖网关。HTTP/WS 仅转发同 loopback origin 的相对路径，拒绝绝对/跨域路径；**并非仅 auth/RPC 的路径 allowlist**，可转发静态资源及其他已注册路由。剥离 Host/Origin、hop-by-hop、forwarded/x-forwarded-* 等并注入 loopback Host/Origin，保留 Cookie；Web 路由继续执行其鉴权。
- 每 peer 最多 64 个并发 HTTP、32 个 WS，超限 429；重复在途 HTTP id 为 400，同 connId 新 open 替换旧流。先发 ws_open_result 成功再发 hello；text/binary 双向代理，4401 保留，非法 close code 规范化、reason 限 123 UTF-8 bytes。
- 编码 mux 总量上限 64 MiB，HTTP response 默认 body 预算为 64 MiB−64 KiB，最终编码仍可返回 413；超大 WS 出站只关闭该 connId（1009）。桌面外层接收 maxPayload 为 64 MiB+1024，与 iOS ceiling 不同；decoder 本身没有统一强制 64 MiB 总长，不能承诺所有入口同限额。preOpen 队列也无总字节限额。
- 桌面重试基数 1 秒/409 为 2 秒，上限基数 30 秒、jitter 0.8–1.2；不照搬 iOS 三次409轮换 UUID。401/403/400 或1008 api key revoked 终止，相同配置保留终止状态。外层断开清 peers；控制错误明确处理 target_not_found，不能将 iOS 对其他 target 错误的行为归到桌面。

## 6. 安全与实现边界

- **确认**：直连明文 HTTP/WS；I Info.plist 开启 NSAllowsArbitraryLoads。不能把口令登录解释成链路加密。Android 应明确 TLS/受信 LAN 政策，不盲目全局放开明文。
- **确认**：ConnectionStore 仅将连接元数据写 desktop.connections；Keychain 分服务保存桌面密码和 tunnel key，按 connection UUID；AfterFirstUnlockThisDeviceOnly。删除连接删除两个连接凭据，但 lastTunnelAPIKey 独立默认账户不会随之删除。Keychain 写入先删后加且忽略状态码，Android 不应照搬静默失败。
- **确认**：D Web dispatcher 普通方法直达 registry.dispatchRpc；descriptor capability 标签本身不构成此分支的授权检查（另有 capability:grant/invoke/subscribe/unsubscribe 分支，订阅事件为 capability_event，iOS 未使用）。native 文件/进程方法按参数委派底层服务。因此此连接应按远程桌面高权限入口对待，不是聊天只读 API。
- **推断**：该 mux 没有端到端加密/签名层；WSS 保护到网关的传输，不能据此保证网关看不到桌面口令、cookie、代码、附件。路由源 UUID 校验不等同密码学设备认证，生产网关源地址绑定与租户隔离待验证。
- 附件普通文件通过 native:writeFile(base64) 写桌面附件目录，图片在 delegateRun 中内联。iOS filename 清 `/\\:`、控制符和 `.`/`..`；fileExists 再写并非原子排他创建，不能保证多客户端不覆盖。限制文件大小需考虑 base64 膨胀及 JSON/mux 头，不存在分块上传协议。
- UI 不应任意执行服务端返回的命令/链接；native:* 路径不是 Android 本地路径。不要在日志记录 cookie、Authorization、密码、base64、完整 app-state；iOS tunnel redaction 只针对 API Key 明文/URL编码，不是全字段脱敏。
- **待验证**：真实网关 ACL/源 UUID 防伪、部署的路由暴露策略（当前桥只有同源路径限制，无 auth/RPC 专用 allowlist）、TLS证书策略、登录限流、进程/文件系统沙箱、长期内存背压与大包互操作。iOS bufferedFrames 无总量上限，需给 Android 加限额/背压，不以 64 MiB 单帧限额代替总内存预算。
