# iOS → Android UI 对齐：只读源码基线

## 证据与边界

- iOS 仓库：`CodingProfessionalAgent-iOS`；审计 HEAD：`16b14eadc815e091698a034db5d32dbe4f2a7474`，审计时工作区干净。
- 已检查 `project.yml`、`CodingProfessionalAgent/App`、`Theme`、`Views` 全部 26 个 Swift 文件、`Services/GitDiff.swift`，以及 UI 相关测试；为解释引用补查 Models、CodeHighlighter 和测试清单。
- **以下是源码定义/静态行为推导，不是实际运行验证。未启动 iOS/Android、未执行测试、没有真实截图或录像对照。** 注释中的 IMG_5449、IMG_5472、IMG_5473、“sampled/measured”只是作者注释，不构成本审计的测量证据。
- **2026-11-18 更新**：Android 已按本基线和 iOS 模拟器实机截图完成界面复刻，并通过设备和真实模型测试。下面的“未运行验证”只指本文最初的源码审计。
- 初次审计时 Android 仅有 docs；目前已开始独立 fixture 样板。本文数值仍是迁移目标，不代表 Android 已达成；当前功能与验收状态见 [当前功能矩阵](migration/current-feature-matrix.md)。
- 下文 `Views/...`、`App/...`、`Theme/...`、`Services/...` 均相对 iOS `CodingProfessionalAgent/`；测试路径相对 iOS 根目录。定位采用文件 + 类型/方法名，可直接搜索。
- 所有尺寸为 SwiftUI/UIKit **pt**，字体为源码 point size；迁移可先按 dp/sp 建模，但不是物理像素等值。系统字体度量、安全区、键盘、原生 List/Form/Toolbar/Sheet、玻璃材质未提供固定像素规格，须单独实机验证。
- 不复制 Apple 字体、SF Symbols、MenuIcon、AppIcon、截图或系统材质资源。Android 图标自行绘制/使用许可兼容资源，字体用平台字体；保留轮廓、层级、语义及点击行为，而非移植 Apple 资产。

## 1. App 与页面骨架

`project.yml`：Swift 6、iOS 26.0、zh-Hans、iPhone only（device family 1）、竖屏、显示名 CPA，版本 1.0.0/build 1。允许任意 ATS 加载（直连桌面为 HTTP）；声明本地网络与拍照用途。不应把这些 Apple 配置原样搬到 Android，也不把任意明文访问视作 Android 安全设计结论。

`App/CodingProfessionalAgentApp.swift`：Home 为根，注入 Router/ConnectionStore，scene active 时 `store.wake()`。
`App/Router.swift`：stack 路由为 thread(connectionId, sessionId)、newThread(context)、subAgent(connectionId,parentSessionId,agentId,sessionId)；创建成功替换栈顶而不是叠加页面。NewThreadContext 携带 prompt、skillReferences、speed、opensModelSettings。

`NavigationLayout.titleWidth` = max(有效容器宽 − reservedWidth, 120)；容器优先继承根导航宽，否则当前 window。Thread 预留 240，SubAgent 预留 120。440 宽时分别 200/320；393 宽子页 273。子智能体外层容器和 toolbar 必须稳定，不能因首条消息到达而重建标题。

## 2. 精确颜色基线

`Theme/Theme.swift`（RGB 为源码归一化通道，alpha 默认 1）：

| token | 浅色 | 深色 | 色彩空间/用途 |
|---|---|---|---|
| accent | (0.933,0.525,0.427) | 同浅色 | Display P3；珊瑚色发送/推理/推荐 |
| online | (0.353,0.745,0.510) | 同浅色 | Display P3；已连接 |
| userBubble | (0.988,0.945,0.933) | (0.235,0.165,0.145) | Display P3；用户泡泡 |
| userBubbleText | (0.776,0.220,0.157) | (0.961,0.686,0.608) | Display P3；用户文字 |
| chipBackground | UIColor white=0.96 | secondarySystemBackground | 自适应；选中设备 |
| groupedRow | black × 0.06 alpha | 同一黑色透明叠加 | 高级设置组，不是深色专用 token |
| skillChip | (110/255,168/255,254/255) | 同浅色 | sRGB `#6EA8FE` |

**不要把 P3 三通道乘 255 后当成精确 sRGB hex。** 广色域实现保留 P3；sRGB fallback 应明确经过色彩转换并记录渲染环境。系统 primary/secondary、label、separator、systemGray2…6、systemBackground、secondarySystemFill、blue/green/red/orange/purple 等均为动态语义色，源码没有固定 hex。表内透明度是叠加参数，不是最终屏幕像素。

补充颜色：SubAgentAvatar 默认 sRGB `#9B7DFF`、底色 tint alpha 0.13；支持三/六位 hex。未读点正常 blue、异常 red；connecting orange，failed red（首页失败图标 orange），disabled/disconnected systemGray3。Settings 开关明确 tint green，并非 Theme.online。

`SessionPanels.swift / DiffLineRow`：新增行 green alpha 浅/深=0.12/0.20，删除 red=0.10/0.18；gutter 新增=0.24/0.32、删除=0.20/0.30；普通 gutter systemGray6×0.6；行号新增/删除色×0.95，普通 secondary×0.8。卡片 systemGray6×0.9，hunk header systemGray5×0.7。

`Services/CodeHighlighter.swift / Palette` 补查：sRGB light keyword=(.61,.16,.70)、string=(.77,.10,.09)、comment=(.16,.51,.24)、number=(.11,.29,.85)、type=(.16,.45,.55)；dark 对应 (.85,.47,.92)、(.98,.55,.52)、(.47,.75,.47)、(.55,.67,.98)、(.45,.78,.78)，base=label。聊天 CodeBlock 是普通等宽文本，不调用该语法高亮器；diff/文件预览才调用。

## 3. 间距、字体、形态清单

### Home / 搜索 / 表单

| 源位置 | 源码规格 |
|---|---|
| HomeView.body/filterBar | 页面内容 top 13；filter 底 14；横向 chip gap 8、左右 16；chip 高43、内横16、内部gap6、字17、设备图14、状态点7；选中 Capsule |
| HomeView.sectionTitle/projectSection/threadRow | section 字17 semibold、高48、横20；项目行高52、图18/槽22、gap14；普通会话 minHeight52、字17、gap8、缩进36，置顶缩进0；未读点8；组横20/底5；显示全部行最小40、字15 |
| HomeView.safeAreaInset | compact composer 横38、底4；无 enabled client 不显示 |
| HomeView.EmptyStateView | top140、横40、gap14；图40 light、标题20 semibold、说明15、按钮额外top6 |
| ChatSearchView.row | 竖gap3、横gap6、row竖padding2；标题16；分支图13 purple；详情13/最大宽160；snippet13单行；匹配 semibold+underline |
| SettingsView.ConnectionRow | gap14、图24/槽34；文字竖gap3；状态字15、点9、gap8；row竖6；整体字17；section标题17 semibold |
| ConnectionEditorSheet / TunnelServerDetailView.iconPicker | gap18、按钮36×36、图24；选中semibold/primary，未选regular/systemGray3 |
| ProjectEditSheet / WorktreeForkSheet | 字17、路径/说明13；行gap12、文字竖gap2；路径 middle 截断；编辑large，worktree medium/large，提交中禁止交互关闭 |

Home 项目/会话长按使用透明 Menu overlay，原行不抬升；primaryAction 是普通点击；另用 0.42s 长按模拟 medium impact（不是菜单实际打开时刻保证）。搜索和 worktree 的跳转先保存 pendingRoute，sheet dismiss 后才 push。

### Composer 与附件

| 源位置 | 源码规格 |
|---|---|
| ComposerBar | HStack gap0，外padding2，连续圆角24 glass regular；文本17，最多5行，UITextView inset top12/left6/bottom12/right0；不是外层可点击假padding |
| ExpandedComposer | VStack gap16、连续圆角30 glass regular；文本最多6行，横18，没附件top16/有附件top0；附件横8/top8；底部工具gap4、横2/底4；页面外横12/底12 |
| ComposerIconButton / AccentCircleButton | 图20、点击框44×44；发送圆32，外点击框44、白色图15 semibold，Theme.accent背景 |
| ExpandedComposer 模型按钮 | 模型字15 secondary、内部gap2、推理按钮槽44×44 |
| ReasoningGauge | 画布22、线宽2、hub5、针末端6.8；135°起始扫270°，进度clamp 0…1；未选systemGray3 |
| ComposerSuggestionMenu | maxHeight236、内部padding7、圆角26 glass；行横/竖10，gap12；图18/槽26×22；标题17、说明15最多2行、类型13；第一行 primary×0.06/圆角18；菜单底距输入顶部10，测量前透明 |
| TransientNoticeModifier | 字15 medium、横16、高38、top8、glass capsule；2s后消失，穿透触摸 |
| AttachmentMenuOverlay | 宽300、圆角34 glass、横24/竖13、左15/底15；行竖11、gap17；图19 medium/46圆形systemGray6；字20 |
| ComposerAttachmentStrip | 图120×120、圆角22、间隔8；删除24圆、黑×0.6/白图11 bold、外padding6；文件高40、圆角16、内leading6/trailing4、gap8；图28/圆角7、字15 middle截断、删除框28×40 |
| 文件列表溢出 | >3个文件才竖滚动，高(40+8)×3+20=164；处理附件时图片区也显示120×120进度占位 |
| SentImageGallery | 单图220×220，多图110×110、每行2个、gap6、圆角18；pending时alpha0.8；固定大小避免解码时跳动 |

附件图标按文件类型使用系统 green（表格）、orange（演示）、red（PDF）、blue（文档）、purple（图像）、pink（影音）、brown（压缩包）、indigo（代码）、gray（文本/未知），不是复制平台图标的授权。

### NewThread / 推理

`NewThreadView`：上下文区左右26、底8；selector高48，图18/槽22/右13，字17，chevron12 medium/左8，整体secondary；设备、项目、本地/工作树模式、存在分支时的分支依次排列，断线字14 orange/高32。

`ReasoningSlider`：固定340×70，inset11，innerHeight48，knob40，dot11；首中心35，步长=(340−22−48)/(N−1)=270/(N−1)。拖动 minimumDistance=0，按最近档四舍五入并clamp；0/1档不可拖换。已过圆点白×0.35，未过systemGray4，当前点隐藏；白knob shadow黑×0.12/r3/y1。选择 `.snappy` + selection haptic。

`ReasoningOverlay`：regularMaterial mask 在归一化y=.55透明、.75黑；VStack gap24、bottom2；标题22 medium，说明regular secondary、chevron15 semibold；title横20。点外部dismiss；运行中模型名吞掉点击，推理名仍可开高级设置。

`AdvancedSettingsSheet`：medium、显示drag indicator；横20；标题17 semibold/高44/top20/bottom10；行高52、横20、gap6、字17/chevron13 medium；分组圆角26；速度区top34，仅支持fast时存在。运行中模型和速度禁用+alpha0.4，说明字13/top8，仅推理可改。

### Thread / 内容

| 源位置 | 源码规格 |
|---|---|
| ThreadView.messageList | 非lazy VStack，turn间24，横16/top13/bottom12；子智能体bottom24；底composer容器gap8、横12/bottom12 |
| ThreadContent背景 | systemBackground到systemGray6纵向渐变，stops .6/1，忽略安全区 |
| toolbar | title17 semibold、subtitle13 secondary、竖gap2、单行；宽度公式见前 |
| UserBubble | 左Spacer最小60，右对齐；内容竖gap4；文字17、横15/竖11、连续圆角22；pending caption12 |
| UserMessageEditor | max8行、输入minHeight60；竖gap12；横15/top13/bottom10；圆角22 secondarySystemBackground，separator描边0.5；按钮gap8/capsule |
| AssistantTurnView / TurnHeader | turn内gap12；header字13 secondary、gap8、separator高0.5、bottom4；工具行字15、gap6、图13/槽18、chevron11/槽12；history左12+外左6、线1 |
| CompactionDivider | 字12、gap10、两侧separator高0.5 |
| MessageActions | gap10；图15/框17×24（源码实际小目标，不伪称44）；时间字12/左2，zh_CN `M月d日 HH:mm` |
| SubAgentPills | flow gap8；内部gap6，avatar20、名字最大160/字14、queued12；left6/right11/vertical5，capsule底secondarySystemBackground，描边separator0.5；header avatar28 |
| AskBanner / AskSheet | banner高42、横16、字15 medium、gap8、accent/glass capsule；sheet medium/large；问题17可选；推荐12 medium/横7竖2/accent×.15 capsule；说明14 |
| 回到底部 | 44圆形interactive glass、图17 medium、距底12；入场scale .6+opacity |

### Markdown 与代码

`MarkdownView.swift / MarkdownStyle`：正文17、lineSpacing5.5、一般block间28；标题后10；段落→list/code/table为12；紧凑块/列表item间8；h1/h2/h3=22/20/18 semibold，其余17。列表marker槽25、gap4；无序点6×6，嵌套描边1.2，baseline偏移5.5；有序marker宽=max(25,(最后序号位数+1)×10)。quote leading14、竖线3。rule高0.5/竖padding4。

表格横滚、单元字15/行距3/横12竖9；任意单元格字符数>24的列固定内容宽240，其余自然宽；边框0.5/圆角12、外padding0.5。CodeBlock字13等宽、行距3、padding12、圆角12/secondarySystemBackground。链接保持primary并追加薄空格+↗（箭头14），不额外画下划线；inline code为secondary等宽。

Markdown是子集解析，不应宣称完整 CommonMark/HTML renderer：支持1…6级ATX标题、段落、嵌套有/无序列表、quote、rule、pipe table及反引号/波浪线fence；未闭合fence一直读到末尾以支持streaming，语言标签不展示；table支持转义pipe，缺列补空，多列截掉，alignment delimiter不产生对齐样式。

### 更改/文件面板

`SessionPanels.swift`：large sheet、隐藏drag indicator、systemBackground；header top16/bottom26、标题20 semibold/subtitle14/gap2与6、标题横留110；右侧边20，双按钮各50×44共glass capsule，单关闭44圆；tabs横16、内部padding2、底8、圆角17/systemGray6、选中systemBackground capsule shadow黑×.10/r3/y1，字15（选中semibold）、竖5。

Diff卡列表gap10/横12/竖8；卡圆角14；header leading16/trailing10/竖11、标题17 semibold/路径14、竖gap3；open按钮38×38/图20；stat14 medium monospacedDigit/gap5。hunk header横14/竖7/字14；行号字12等宽、槽34+右8、top2；代码13等宽、leading6/trailing8/竖2。底分支条字16、gap8、横16/top8/bottom14、bar材质。

文件树：行字16、图18/槽22、chevron13 semibold/槽12、gap10，每层缩进20，row insets top/bottom11、left/right16。底搜索高44/横16、外横16/底8、glass capsule、字15。文件预览：等宽13，行号宽44/右12/top1/secondary×.6，代码top1，行横12，整体竖8。

**重要实际差异：** `wrapDiffLines` 偏好传给聊天 Markdown/工具 CodeBlock；SessionPanels 的 DiffLineRow 和 FilePreview 没有读取此偏好，始终按可用宽度换行。不能因为设置叫“代码差异行自动换行”就认定它控制 Git 面板。

## 4. 页面真实行为与非功能项

- Home：enabled连接过滤；全部/单设备；置顶会话独立区、项目置顶优先、会话更新时间倒序；孤儿项目会话进“对话”；每组默认5条，显示全部是内存状态，折叠组跨启动持久化，折叠重置显示全部。未连接也可保留已有列表。刷新逐个 resync。
- 首页发送只清空草稿并打开预填 NewThread，**不直接发送**。首页 plus 没传 onAttach，当前是无动作按钮；搜索快捷“打开文件夹”“搜索文件”仅显示“即将推出”，不是已实现功能。
- 搜索：空query最近30；150ms debounce，多桌面并行服务端搜索；单端失败退化本地标题匹配；标题命中优先、组内更新时间倒序、全局30；过时任务不落结果。搜索使用所有enabled clients，不受首页当前设备filter限制。
- 项目编辑可删除全部源路径，非空trim名称即可保存；路径去重；移除项目仅解除归属，不删除对话/目录。工作树fork需项目primaryPath，先尝试session.worktreePath再project path探测分支；环境可不选，实际建worktree/环境脚本在复制对话下一条消息前由桌面执行。
- 目录浏览是远程文件系统，非手机目录；支持POSIX、Windows盘符和UNC。获取home失败退`/`；隐藏点目录默认不显；失败保留旧目录；最新generation获胜；路径trim，当前目录非空且非loading可选。
- 新对话选项目默认pinned/first，显式空字符串表示不使用项目；按同步后的设备+项目应用最近模型/推理，speed仅首次取桌面默认或首页覆盖；模型不支持fast则回standard；切项目清空旧分支并防迟到结果。文件先上传，后createSession，再delegateRun；仅附件可发送，标题取prompt/首附件名第一行、最多50字符+…；上传失败不建空会话，create成功后delegate失败没有看到回滚删除。
- 设置：连接排序/删除/启停/全部断开；keyboard collapse=true、context usage=false、followUp=queue、wrap=true、liveActivity=perComputer。实时活动只有枚举选择及本地存储，未发现 ActivityKit/卡片实现，不应承诺后台活动功能。
- 直连编辑：默认端口18080；名称选填；host接受IP、http/ws地址及裸IPv6，拒绝https/wss；地址内端口优先于端口字段，范围1…65535；密码可空且走Keychain。删除连接直接执行，无二次确认。
- Tunnel：URL/API Key验证后连接发现服务器，连接成功记住网关/Key；配置改动后旧服务器不可用，需“切换网关并搜索”；connecting/reconnecting/failed/首列表等待/空列表/已添加各有状态；同配置刷新或retry；选服务器→名称/图标/可选桌面密码→保存。退出释放发现lease；不是每次导航新建独立网关连接的语义。
- Thread：列表加载、断线、删除、错误/stream事件；发送乐观插入，运行中queue/steer；空草稿才显示stop/resume；正常完成不能resume，aborted/特定restart错误/未完成工具等可resume，普通error未必可恢复。模型/速度运行中锁，推理可改；slider关时提交、串行等待前次设置，send/edit/resume等待推理更新。
- 内容：思考块不展示；纯thinking/skill read阶段只有计时头；连续工具折叠到最新行、可展开历史及参数/结果；参数最多4000字符，结果6000；运行结束活动自动折叠，留最终答复和压缩分隔。子智能体包括nested工具派生的pill，未拿到record不导航；失败启动不留假卡；详情只读但可按assistant消息fork为顶层对话。
- Ask：自动弹出，稍后关闭留banner；推荐项、custom、skip各由请求控制；首次非空输入尝试停止倒计时，只收到桌面确认才清deadline；失败可手动重试。显示倒计时不在手机端自行提交默认项；轮询早期0.5s，之后3s，另3s发现monitor，sequence防旧问题复活。
- Scroll：完成历史从首prompt打开，运行中跟底；离底容差48；滚动期间新输出不抢位置，新增用户消息只在idle/非编辑时恢复跟随；编辑暂停自动跟随，键盘出现/编辑长高时滚入可视区；列表重建只因空→非空，不应重建外层toolbar。
- Git面板只读，无stage/commit/revert；默认working tree相对HEAD+untracked，分支比较必须`origin/base...target`，失败不偷偷改本地base；初始只自动展开首文件且≤400行（所有hunk行总数），展开全部不受该阈值限制。切project重置比较/文件/展开状态；diff打开路径相对repoRoot而非project子目录。
- 文件树仅惰性加载，搜索仅过滤已加载目录/后代，不是递归远程全文搜索；跳过`.git,node_modules,dist,.next,coverage`。请求失败转空数组，当前会显示空/未匹配而非专用错误。预览只读、可复制绝对路径，最多200000 Swift字符，无截断提示；CRLF去尾CR。

## 5. 输入编辑契约（不可只仿外观）

1. `ComposerDraft` canonical text使用`$name`及references，所有offset/length/caret采用UTF-16，不是Unicode scalar或grapheme数。validReferences要求真实token、边界有效、有序无重叠。显示chip是单个attachment字符，canonical caret进token内部时映射到chip后；选区发布取NSMaxRange。
2. slashQuery仅允许前导空白后一个完整无空白`/command`，有chip不匹配。skillQuery读取光标前`[a-z0-9-]`（大小写均可）；起始或空白/标点边界有效，但`#$/\\`前缀不算边界。`$`/`#`菜单按名字substring，`/`按prefix；内置commands排在skills前；只有focused才显示，Return选第一条，不存在键盘上下选中状态的实现。
3. 选command清空草稿并立即执行；model/compact收键盘，fast保留。skill替换当前query为`$name `，更新后方reference偏移，caret移到空格后；chip显示cube+人类可读name、蓝色medium17、图13、gap4，并非胶囊背景。
4. 发送payload识别已知skill（不区分大小写，输出canonical name），trim首尾空白并重算offset；slash开头`compact/compress/model/models`保留命令文本，显式`/skill:name`可作skill。已保存消息有references则优先用之；旧消息可解析`$name`/`/skill:name`和整段skill command。
5. compact Return无菜单则发送trim非空内容、默认收键盘；Expanded/历史编辑无菜单时Return插入换行。IME markedText存在时不截获Return；外部apply不同draft时会unmarkText重建attributedText，不应声称“所有IME外部更新均无干扰”。粘贴禁rich attributes；copy/cut把chip序列化为当前trigger（slash用`/skill:`），cut走deleteBackward保留undo/delegate路径；chip后的普通输入重置typingAttributes。这些都需要中文输入法、emoji和撤销实测。
6. UITextView lineFragmentPadding=0，隐藏竖滚条；高度=min(max(fitting,ceil(font.lineHeight)+inset),ceil(font.lineHeight)×maxLines+inset)。首响应者变更异步且校验最新focus/window；placeholder不吃触摸。
7. 按钮优先级是 **hasContent → send；否则running → stop；否则canResume → resume；否则无动作send**。空态箭头仍珊瑚色，不自动变灰。附件processing有内容时send禁触+alpha.5；sending显示44进度并禁附件增删，输入本身没有全局disabled。
8. 历史编辑只在连接且非running、非pending用户消息开放；替换泡泡为max8行输入，隐藏底composer；cancel不发送；trim空禁发。编辑会丢弃该prompt后续历史并重跑，沿用entry ID，保留原图片；提交接受前保留编辑框；request失败回滚，已收到started事件则不回滚；接受后未真正启动会恢复草稿，20s未启动放弃本地overlay并重载权威历史，迟到start仍须处理。
9. 附件reader每个≤25×1024²字节；支持的JPEG/PNG/GIF/WebP/BMP且≤2000×2000保留原字节；大图或其他可解码格式转最长边2000/JPEG质量.85，缩略图360px。照片可转HEIC；文件选择仅jpg/jpeg/png/gif/webp/bmp扩展名尝试图片解析，失败当普通文件（HEIC文件不会按照片入口等同处理）。读取按选择顺序串行追加，失败alert仍保留成功项。
10. 图片base64随prompt、普通文件先上传桌面并在text后追加`\n\n@path ...`（无文字只引用）。不支持图片模型阻止发送。Thread先清空草稿/附件，失败时仅在新草稿仍空时恢复旧文字，旧附件放回新附件前；含附件发送有顺序锁，等待期间再次校验图片支持。新对话上传失败与已存在Thread发送失败不可混为一种回滚。

## 6. GitDiff 解析与fixture边界

`Services/GitDiff.swift / GitDiffParser`：LF分行，逐行剥CR；识别diff头、new/deleted/rename/copy/binary、hunk默认数量1；按old/new剩余计数停止hunk，不能把文件结尾空行算代码；忽略`\\ No newline at end of file`。add只有new号、delete只有old号、context双号；删除displayPath回退old，`/dev/null`不作文件标题。quoted路径处理八进制UTF-8与转义；含空格同名header用` b/`等值分隔，歧义交给marker/rename行。源码并非完美通用Git parser：畸形输入、copy无marker、异常八进制都须负例测试，不能凭注释宣称完全兼容。

`GitDiffLoader`：先diff HEAD；失败再status -z并verify HEAD，无HEAD时tracked/staged也视新文件；对untracked/新文件在repoRoot执行`diff --no-index -- /dev/null path`，可接受有输出的非零exit（diff差异常为1）；空/不可读untracked保留零行entry；无HEAD且文件已从工作区删除的失败diff跳过。repo root探测失败返回nil，UI把它显示为“不是 Git 仓库”（可能也包含网络失败）。工作区diff和status失败可退空结果，不能把“暂无更改”当后端已成功确认clean。

## 7. 测试证据与待验证

`project.yml`仅`bundle.unit-test`，**没有 UI testing bundle / XCUITest target，也未见截图golden**。所谓UI tests是hosted UIKit/SwiftUI XCTest：

- `ThemeTests.swift`：动态颜色转sRGB求相对亮度；dark chip/bubble<.15、light>.8，文字对比≥4.5。不是所有文本/按钮可访问性测试。
- `ComposerHitTestingTests.swift`：空输入在水平.1/.5/.75/.95、纵offset±20等可命中；多行padding命中；7行文本被限为129±1pt（当时默认字体环境）；左右按钮位置不被编辑器吞掉。不要把129当所有fontScale的固定值。
- `NavigationLayoutTests.swift`：440×956 hosted window，标题宽计算、窗口fallback、push中scroll全宽；首entry到达前后header identity/opacity稳定，~.22s中途到达，按60Hz采样，允许opacity误差.02/下降不超.05。是测试意图，本文未执行。
- `DesktopConnectionTests`、`TunnelProtocolTests`、`TunnelClientIntegrationTests`为连接/协议证据而非视觉证据：端点等价、凭据分离、边界/secret redaction、共享发现与连接、错误密码、服务器上线、lease交接、超时迟到结果、跨server注入防护、重连清理等有用回归样例。

未见独立ComposerDraft/ChatTurns/Markdown/GitDiff/UI端到端测试文件；需补测而不是标“已有覆盖”。

## 8. 建议 fixtures 与验收分层

只提出fixtures契约，本次不生成第三个文件或复制任何资源。建议纯合成JSON/文本/自己生成图片，固定clock/UUID/locale/timezone/字体缩放，凭据全用虚构值，不含真实主机或Key。每个fixture应包含：初始store、API响应、stream事件序列、delay/failure点、动作、期望route/visibleText/requests/state及预期尺寸token。

| ID | 建议数据/动作 | 核心断言 |
|---|---|---|
| F01 home-states | 无连接/全禁用/connecting/失败/connected未sync；双主机同sessionId；pinned、orphan、7条项目会话、长中文名 | identity按connection+session；5条/全显/折叠持久化；禁用主机移除filter；首页plus无动作 |
| F02 search-race | 空query；150ms内连续输入；一端失败、一端正文命中；旧query晚返回；31+结果 | 最近30、标题优先、fallback只标题、不串结果；两个快捷占位只notice |
| F03 connection | http/ws带端口、https拒绝、裸/括号IPv6、65535/0/65536；Tunnel旧配置、空列表/已添加/重连 | 正确校验、端口优先；Key不进入fixture日志；配置改变不保存旧server |
| F04 project-path | POSIX/drive/UNC、隐藏目录、无权限、旧load迟到、重复path/零path；worktree无源路径 | root/parent边界；失败保旧列表；保存trim名；worktree延后执行 |
| F05 new-thread | 两设备两项目不同默认模型，fast支持变更、迟到branches；图片only、上传失败、delegate失败 | selection有效、无旧分支；标题50+…；upload在create前；不要虚构delegate失败自动删session |
| F06 editor-utf16 | `😀 中 $review x`，review reference start=5（UTF-16）；caret进入chip；多chip、重叠/负offset；#、$、/触发与保留命令 | canonical/display roundtrip；payload trim重算；copy/cut/undo；首候选Return；中文IME确认不误发 |
| F07 composer-layout | 空、1/5/7行compact；6+行expanded、8+行edit；键盘开关，1x/大字体 | 内边距可点，按钮不被输入覆盖；Return语义不同；sending/processing优先级 |
| F08 attachment | 自生成小PNG/大图/GIF/HEIC、伪坏jpg、25MiB/25MiB+1、4文件、多图、权限拒绝 | 格式/长度阈值、顺序、164文件滚区、图片only；发送失败期间输入新文字不覆盖；预览临时文件清理 |
| F09 stream | user→assistant→tool→result→compaction→final；pending queue/steer；重复id、snapshot晚于stream、idle先于persist | 无重复/旧snapshot覆盖；thinking不露出；最终fold；pause时间扣除、工具ID截`|`前段 |
| F10 edit-resume | 中间prompt编辑、保留图片、RPC失败/预检失败/20s后late-start；stop失败/resume失败/正常done | rollback/恢复draft；后续记录不复活；正常done不resume；部分assistant清理可回滚 |
| F11 ask | 推荐+倒计时/custom/skip；typing cancel失败→手动重试；外部已回答/旧lookup迟到 | 只确认才清deadline；稍后banner；旧问题不复活，不本地自动作答 |
| F12 nested-agent | nestedTools JSON字符串args、同ID后缀、queued/awaiting_approval/completed、无record/失败launch、长名 | 去重/placeholder/导航语义；只读详情，200ms左右首entry到达标题不闪 |
| F13 markdown | h1…h6、中英长段、链接/inline code、多层list、转义pipe、缺列、>24字符列、未闭合fence | 17/5.5/28排版token；240宽列横滚；不是HTML网页；wrap偏好仅聊天code |
| F14 git-parser | CRLF、无换行标记、0/省略count、多hunk、含空格/中文八进制路径、rename/copy/binary/delete/untracked/空文件 | totals/old-new号/displayPath精确；trailing newline不多计；不可读fallback |
| F15 git-loader | 未出生HEAD、project为repo子目录、no-index exit1、origin缺失、400/401行首文件、切project迟到结果 | repoRoot打开；分支失败不退local；401不自动展开；全展开仍可执行 |
| F16 file-tree | 未加载孙目录命中名、加载后再搜、跳过目录、读失败、200001字符/CRLF | 搜索不谎称全盘；错误空态与预览error分开；200000截断、路径copy |
| F17 visual-matrix | light/dark × 393/440逻辑宽+窄屏 × keyboard on/off × 长文/长标题 × reduce motion | token先验对齐；真实双端截图后才标视觉通过；340固定slider窄屏溢出作为待决偏差 |

验收顺序：①纯函数golden（draft/diff/grouping/path）→②请求/stream时序测试→③组件命中/布局测试→④双端实机或模拟器同fixture截图+交互录像。P3/sRGB、系统字号、density、OS版本、安全区和键盘高度必须随截图记录。系统glass/菜单/字体无法仅凭源码保证像素一致，应记录批准的Android平台差异，不偷偷改写iOS基线。Reduced motion源码只在Shimmer显式关闭动画；其他`.snappy`无自定义减弱分支，勿声称全局已覆盖。
