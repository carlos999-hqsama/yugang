# 鱼缸 Core 架构

鱼缸是运行在 ARM64 Android 上的对话与通用插件底座。以 DeepSeek Agent Harness 为执行基础，把 Bot 看作各有职责的鱼，把独立插件看作用户布置的场景。公开工程提供对话和完整通用宿主；业务插件、私人服务、账号配置和使用数据独立于源码与 APK。

## 1. 分层与调用方向

```mermaid
flowchart TB
  User[用户] --> UI[Android 界面 / 对话 WebView]
  UI --> Harness[本机 DeepSeek Agent Harness]
  Harness --> Context[动态上下文 / 工具与技能]
  Context --> Bot[当前 Bot 工作区]
  Harness --> IPC[同 UID 本地 socket]
  UI --> Host[Android 通用宿主]
  IPC --> Host
  Host --> Framework[版本化公共页面框架 /framework/v1]
  Framework --> Plugin[用户独立安装的插件]
  Plugin --> Host
  Host --> Native[网络 / 模型 / 媒体 / TTS / 预览 / 后台与通知]
```

| 层 | 职责 | 不归这一层的内容 |
| --- | --- | --- |
| Android 宿主 | 界面、凭据、运行时启动、Bot 配置、插件生命周期、原生服务 | 各业务插件的页面与偏好 |
| 本地 Harness | 会话执行、工具调用、上下文组装、检索与 Bot 通信 | 私人远端中继与业务服务器 |
| 公共插件框架 | `/framework/v1` 资源、组件、主题、页面生命周期与能力调用 | 插件自身的业务逻辑与数据 |
| 独立插件 | 业务页面、原生动作、私有资源与插件内设置 | 宿主签名密钥、账号凭据与系统设置 |

Android 前台服务负责本地运行时生命周期。PRoot Debian 内的 Node 运行已打补丁的 DSH，仅监听 `127.0.0.1:3090`；模型推理仍使用用户配置的 DeepSeek 服务，并不意味着模型离线运行。运行时输入由锁文件和下载摘要固定。

## 2. Bot、人格与渐进式上下文

每个 Bot 绑定自己的工作区和内部会话。界面面向固定 Bot，不向用户摊开历史会话；历史检索、归档和接续由所属 Bot 内部管理。

上下文不是创建 Bot 时复制的一份固定长提示词。宿主在系统提示词组装、每次执行步骤前读取当前 Bot 配置：

1. 读取 APK 默认人格、手动人格补充、共同学习人格、当前 Bot 职责、记忆、工具开关和压缩阈值。
2. 组装身份与工作区规则、默认人格、学习表达层、手动人格补充、职责和阶段反馈要求；记忆作为参考资料，不能授予权限或覆盖人格。
3. 只暴露启用的工具与相关技能说明；需按需加载的技能，在加载成功后再披露工具和手册。
4. 工具真正执行前再次检查当前 Bot 的启用配置。人格或职责修改在后续组装/步骤生效，不追改已发送给模型的上下文。

系统提示词的事实源是当前配置，而不是复制到会话里的静态人格文件：DSH 标准预设提供框架身份与 `{{cwd}}` 后缀；`bot-context` 从宿主读取当前 APK `defaultPersona`、可为空的手动 `sharedPersona`、可选学习表达、职责、记忆和开关；`bot-workspace` 在组装瀑布中按命名顺序投影为 Bot 身份、职责、记忆规则、`bot:default-persona`、`bot:learned-persona`、非空时的 `bot:persona` 和反馈合同。默认层只在 `bot:default-persona` 出现一次，学习层和手动层也各自至多出现一次；空的职责、空记忆、空手动层或没有学习候选不伪造配置内容，空记忆仅保留写入/更正规则。Android 权限、凭据、私有运行时目录和“只使用当前工具列表”等宿主边界只在 `host:android-boundary` 出现一次；插件、历史和会话工具手册只保留各自操作语义，并用共享排序常量避免同号依赖名称兜底排序。

文件写入默认使用当前工作区相对路径（与 `documents/AGENTS.md` 等项目内约定一致）；面向用户交付 Markdown 链接时使用该文件在工作区内的绝对路径。两者分别描述执行路径和交付引用，不互为改写规则。

默认人格由当前 APK 的 `bot-persona.md` 提供，不写入 `CONFIG/PERSONA.md`；手动文件只保存主人明确补充，可以为空。共同学习层和手动层由所有 Bot 继承，职责提示词补充各自工作方式。表达优先级固定为：主人明确手动要求 > 共同学习层 > APK 默认人格；插件、技能或职责声明的场景风格只能在对应场景细化默认层，不能覆盖学习层或手动层，也不能改写全局人格。规则仍依赖模型遵循；能由程序约束的工具开关、会话归属、参数与生命周期另由代码检查。Bot 消息通过宿主路由到目标 Bot，在对应上下文内处理；发信、收信和读取消息不等于允许任意访问其他 Bot 的目录。

Core 只保存和组装学习结果，不读取历史、不调用模型、不接入反思服务。可信同 UID 私有适配可在完成自己的证据反思后调用 `BotWorkspaceStore.call` 的 `op=learning`，提交严格结构化候选：短 `revision`、非空 `traits` 子集（`directness`、`verbosity`、`warmth`、`humor`、`reflection` 的固定枚举）、正整数 `evidenceCount` 和 ISO8601 `updatedAt`。未知字段、自由形式模型指令或用户原文会被拒绝。候选原子保存为 `CONFIG/PERSONA_LEARNED.json`；同一 revision 的相同候选重复提交返回未应用回执，不覆盖 `CONFIG/PERSONA.md`。后续所有 Bot 的 context 收到固定中文映射生成的 `learnedPersona` 与 `learnedPersonaRevision`，不包含证据原文、时间或计数字段。该通道不是恶意同 UID Agent 隔离边界。不同 revision 表示适配器提供的当前候选快照，按提交替换；`updatedAt` 是证据时间，不承担版本排序。适配器须串行提交当前结果并拒绝已取消或过期任务；Core 不把不同 revision 自动解释为按时间递增，也不宣称直接重放旧候选会被拦截。

旧安装的一次性人格迁移由实际读取的 `CONFIG/PERSONA_MIGRATION.json` 版本控制。迁移只把完整匹配已知旧初始模板或当前 APK seed 的手动文件归还为空手动层；其他文档只精确移除两处已知旧版“禁止演化”固定子句，自定义内容逐字保留。迁移完成后，主人再次保存含这些句子的个性文本不会被悄悄改写。

阶段反馈和工具执行按事件顺序展示：反馈 → 执行 → 下一段反馈 → 执行，最后给最终结果并折叠过程。用户中途插话保留在原顺序中。该机制不能靠把全部工具过程常驻展开代替。

所有 Bot 对话共用阅读位置保持逻辑。用户已滚动离开底部时，消息、图片、尺寸或状态刷新保留当前阅读锚点；浏览器尚未派发滚动事件时也以实际阅读位置为准。顶部下拉回弹和内容暂时收缩产生的滚动事件不会重新开启底部跟随，尚未恢复的锚点保留到内容恢复。历史前插使用事件序号和消息块标识稳定定位，不能把数组下标当作消息身份；显式切换会话和回到底部的原有行为保留。

用户图片消息保留结构化内容：发送中的图片直接显示缩略图，已保存的图片按会话和附件 ID 通过 `session/attachment` 读取，由运行时校验该会话确实引用附件。界面展示读取中、失败重试与放大查看；缓存按会话授权隔离，切换 Bot 后可复用图片节点和附件字节；非当前会话最多保留 3 个，缓存预算 48 MiB，删除会话时清理。过期请求不更新新会话。文件名仅用于显示，不作为附件路径。

模型配置遵循“先 Key、后模型”：原生宿主用填写的 Key 请求固定官方模型列表，成功后才保存 Key 并允许选择真实返回且 Harness 支持的模型。页面不接收原生存储的 Key；重定向被拒绝，错误正文不回显。默认模型保存到 Harness 的 `agent-default-model` 活配置，携带 revision，保存后重读运行时默认值确认；不借用旧会话保存，也不覆盖旧会话明确选择的模型。

## 3. 数据归属

| 数据 | 运行时位置或归属 | 是否随公开源码 / APK 分发 |
| --- | --- | --- |
| 核心代码、公共框架、默认模板 | APK assets | 是 |
| 用户手动人格补充、迁移版本、非秘密全局配置 | `CONFIG` | 否 |
| 共同学习人格结构化候选 | `CONFIG/PERSONA_LEARNED.json` | 否 |
| Bot 职责、记忆、归档、工具配置、产物 | `BOT/<BotID>` | 否 |
| 独立插件源码、资源、安装注册信息 | `PLUGINS` 与宿主私有目录 | 否 |
| DeepSeek Key、登录状态 | Android 私有加密配置 | 否 |
| 会话、附件、缓存、模型预算与后台状态 | 宿主私有目录 | 否 |

`/BOT`、`/CONFIG`、`/PLUGINS` 和 `/opt/pd` 是内置运行时目录契约，不是开发者私人远端地址。分享 APK 不会附带设备上安装的插件或使用数据；用户主动导出文件仍需自行检查内容。

## 4. 插件契约与页面生命周期

独立包声明 manifest、API 版本、资源、页面、可选原生动作和能力。宿主校验资源路径、导入闭包和大小。系统设置负责添加、启停、删除；插件偏好留在插件内。页面共享宿主的黑白主题、控件、布局与响应式规范，业务脚本以 `definePage` 注册，不另带宿主兼容层。

切换菜单时隐藏并保留已打开 WebView，借助 `onHide` / `onShow` 暂停、恢复可见页面工作。更新、停用和移除按 revision 释放旧页面；内存压力只释放隐藏页，Activity 结束释放全部。后台计划独立于可见页面，由 JobScheduler 调度，最短周期 15 分钟，系统可以延后执行。

离屏预览复用真实渲染器，支持草稿包、截图与交互检查，不安装草稿、不切换用户前台页面、不修改安装状态。截图可以保存到当前 Bot 的 `documents/` 并通过附件服务交付；这不是实体手机所有交互与扬声器出声的替代验收。

首次插件引导由安装注册表驱动：没有任何已安装插件时显示“插件”空入口，点击后进入固定插件 Bot。AI 新闻只是一条可编辑的需求示例；点击示例仅填空草稿，不自动发送，不覆盖已有文字。创建、预览、测试和安装沿用现有 Agent 工具。成功注册真实插件后撤掉引导，停用插件不等于卸载，失败或取消不算完成。APK 不预装示例业务包，也不通过刷新整个 App 切换引导状态。

## 5. 通用能力与取消边界

- **模型与翻译**：宿主管理用户配置、调用与每日插件 token 预算，插件接口不返回 Key；结果未知的预留独立保留。
- **网络**：`native.fetch` / `fetchStream` 提供结构化状态、超时、大小上限与真实取消，不注入宿主凭据。流式首块时间与总耗时分别计量。
- **媒体与文件**：`plugin.media` 提供有界附件数据；普通文件代理只在用户明确打开文档后授权当前文档，离开时撤销。子资源检查同源来源。
- **语音**：TTS 优先系统中文引擎，负责音频焦点、合成文件、播放与停止；不可用时才回退百度在线语音。语音会话提示说明在线服务会接收待朗读文字；实际使用在线线路时，宿主每次进程运行按线路提示一次，不打断连续朗读。系统声音可能联网，只有确认离线才报告无需网络；状态查询不探测网络连通性。首音来自宿主实际音频时钟，无法确认不编造数值。用户确认后仅订阅当前 Bot 新生成的回复，隐藏/切换取消，不读取历史、不跨 Bot。
- **后台与通知**：宿主管理计划、generation、执行与系统通知；旧计划结果不能覆盖新计划，通知仍受系统授权和频率约束。

两个 abstract Unix socket 都校验同一 Android UID：`PD_APP_SOCKET` 提供本机配置和 Bot 路由，`PD_HOST_RUNTIME_SOCKET` 提供插件管理、能力和离屏预览。后者为整个连接实施总期限，覆盖读、处理、写；超时先关闭连接，再取消本请求的真实工作。动作各有独立子取消令牌，不会因一次失败关闭页面的长寿命信号或取消兄弟请求。TTS 播放遵守动作与外层 RPC 中最早到期的剩余预算。

## 6. 安全与信任模型

Bot 目录是组织边界。PRoot、WebView 和能力声明不是抵御恶意插件的操作系统沙箱：插件、页面脚本、Agent 工具共享 Android 应用身份。只安装审阅并信任的插件。原生加密存储和不向页面返回 Key 能减少秘密暴露，但不能据此宣称同身份恶意代码无法接触秘密。详见 [SECURITY.md](SECURITY.md)。

## 7. 公开源码与发布材料

公开源码导出采用固定公开模板、源码根目录和文本后缀规则，二进制逐项准入，排除业务插件、伴侣服务、私人连接、Git 历史、配置、缓存、工作区与设备数据。通用接口及其真实实现全部保留；不使用删除底座能力或空实现来完成剥离。

宿主源码使用 GPL-3.0-only；第三方声明、对应源码和 SBOM 按实际构建的版本匹配。构建入口和固定运行时见 [BUILD.md](BUILD.md)，完整剥离记录见 [CORE_SPLIT.md](CORE_SPLIT.md)。通过构建不表示全部依赖没有已知风险，保留公告与限制见 SECURITY.md。

## English overview

YuGang Core separates the Android host, local DeepSeek Agent Harness, per-Bot workspaces and independently installed plugins. Shared persona, Bot role, local memory and tool settings are assembled from current host configuration; skill instructions are disclosed on demand and tool switches are checked again before execution. Plugins reuse a versioned framework and real host network, model, media, TTS, preview, background and notification implementations. They share the application UID and are trusted code, not sandboxed hostile extensions.

Only the conversation system and general foundation belong to the public source. Business packages, private companion services, credentials, installed plugins and user history are excluded. Build success, source publication readiness, physical-device acceptance and signed binary release are separate claims; see BUILD.md and SECURITY.md for their limits.

## 文本交付文件（2026-10-25）

`FileTextContent` 对 UTF-8 文本实施 2 MiB 流式上限；`FilePreviewDialog` 提供无宿主桥、无远程资源的 App 内查看器。`DownloadedFiles` 仅打开本 App 在 Download/鱼缸保存的 MediaStore 句柄。网络文本类型统一由 PluginMime 判定，包含 RSS/Atom 与 application 的 +xml/+json。连接器负责来源鉴权；Core 不含私人 Codex 适配。

## 插件浏览与会话（2026-10-25）

PluginSiteBrowser 使用 AndroidX WebKit MULTI_PROFILE，并由 PluginSiteBrowserHost 提供原生返回/关闭/标题和更多菜单域名。打开由 PluginPage 的真实触控准入，Bridge 持续到关闭才回执；下载事件回到原所属页面。profile 按稳定插件 ID 保留，卸载在既有清理事务中关闭视图，再定向清理对应 profile 数据：未加载时删除 profile，已加载时调用绑定 profile 的 WebStorageCompat 数据清理。无 MULTI_PROFILE 或定向 DELETE_BROWSING_DATA 时不回退共享 Cookie。

PluginApiSessions 为当前插件的普通/流式 fetch 提供显式 opt-in CookieJar，按宿主身份派生、Keystore AES/GCM 加密。匿名调用不能 include。页面请求登记为当前 revision 的 Run，卸载先取消请求并撤销旧 jar，再删私有会话。通用代码在公开 Core；Codex 私有连接器保持独立。

通知 request 由 Bridge 异步接管，MainActivity 转发真实系统结果；不在生命周期锁内等待 UI。后台只记录动作显式 result 的有界脱敏快照和错误说明，generation 保持并发与清理边界。

2026-10-25 浏览器原生内容页：挂载在左侧菜单以下的右侧内容 surface，菜单布局/样式/点击保持不变；原生返回、更多、关闭与进度不修改外站。预览上下文由 native.isPreview 提供；预期权限/策略拒绝按调用回执，不记为页面故障，也不关闭其他流。

## 对话多媒体与浏览器统一（2026-10-26）

对话附件入口为“图片”和“文件”。图片沿用上游图像规范化及会话附件 API；发送回显用同一 rpcId 交接原缩略图，读取持久化图片期间保留缩略图，失败可重试。草稿最多 6 项，自适应换行，预览与移除独立。

文件和视频由系统选择器交给 BotAttachmentTransfer，在原生私有缓存暂存，JS 只持有不透明 handle、名称、类型和大小。每条消息最多 6 项，跨草稿活动暂存最多 64 文件及 256 MiB，失败记录可放弃重试释放空间；上传以二进制流调用上游 uploadFileBinary，原生注入本机认证，验证 receipt 后才将 `{type:'file',receiptId}` 交给 session/prompt。取消阻止随后发消息，每次上传尝试使用独立取消编号；消息结果未知时保留消息身份及已上传凭据，明确 FILE_NOT_STAGED 才允许用户下一次重试重新上传。视频传输不等于模型已理解视频内容。

历史文件通过宿主级 chat-attachments overlay 注册的 `/api/yugang/session-file` 读取，不依赖某个 Agent 首次激活。Bot 生成的工作区文件通过 `/api/yugang/workspace-file` 按 fs provider 的 resolve/contains/processPath 读取，支持最大 256 MiB 和 Range，避开上游 `/api/file` 的图像大小上限。历史附件服务先 inspect 指定会话，严格匹配已持久化 user/message 中的文件引用、名称和大小，再用官方 attachments provider 读取；支持 GET/HEAD、单段 Range、背压与中止。接口不接受文件路径，HTML/SVG 在认证 API 来源中受 CSP 限制。

普通网页和本地交付文件复用 PluginSiteBrowserHost 的内容 surface、返回/关闭、下载和生命周期；Core 网页、文档、各插件登录空间分别使用独立 profile。Bot 链接、主页面实际链接、插件页面真实锚点、原生插件链接、文本预览和 DSH 备用页外链均接入该底座，Panes 仅保留认证 DSH 备用页与图片代理。原生观察到的插件链接点击通过 openUserLink 进入该插件 profile，并检查当前 revision；JS browser API 仍保留原 capability/手势契约。

ChatContent 对本地文件授予当前 Bot 工作区或精确会话附件来源，不向网页提供宿主桥或认证 Cookie；普通 CDN 子资源按独立文档 profile 请求，受控文件地址始终经原生 provider。HTML/SVG 使用 `sandbox allow-scripts`，不授予同源权限，允许交互和普通 CDN 脚本，但脚本不能读取受控文件来源或使用其存储；原生不转发 CORS/Cookie。认证 API 的已知下载策略仅在独立文档 profile 转换，其他上游 CSP 保留并叠加该隔离策略。网页链接真实点击后关闭文档并交接普通浏览器。文本按 2 MiB UTF-8 上限显示，音视频提供原生 WebView 控件和保存入口；PDF/Office 等保存至 Download/鱼缸后交给系统查看器，下载不完整会清除半成品。系统不支持 MULTI_PROFILE 时明确提示更新 WebView。

本次复查同时修复普通 fetch 的取消先于登记竞态（有界、限时取消标记；只取消精确请求）及旧页面 apiSession 操作与更新的竞态（与更新/卸载共用生命周期锁并重新检查 revision 和取消）。两项均保留原有合成竞态复现为回归，避免旧页面清除新版登录会话。


## 2026-10-26 通用系统底座扩展

页面和原生动作共用 PluginHostServices；声明与操作目录由 PluginCapabilities 同时用于发现和准入，避免文档有能力而执行端没有实现。需要 Android 界面的操作仅允许已安装的可见前台插件，由 ActivityResult 接收并核对原 revision。网站无原生桥，浏览器正文提取只读取请求插件的当前页面。

桥使用进程共享、有界的 IO 队列与按页面的调用额度；退出只释放该页面排队工作。调用输入、执行信封、脚本进程并发和日志均有实质限制；取消回调在锁外执行，退出会拆除进程取消钩子，避免父子取消互锁和复用进程组的误杀。

安装态索引、不可变插件包、独立 state 分开保存。索引引用的长度和摘要在执行读取时校验；更新复用上一版本引用。旧注册表迁移前保留原始私有副本，逐插件隔离损坏；主文件不可用时读取已校验备份，均不可用时只读停写并在管理页/能力发现报告恢复状态。后台计划同样原子保存并可恢复备份，开机和更新仅恢复系统计划，不自行拉起会话或界面。

文件使用系统选择器和按插件隔离的不透明句柄，FileProvider仅暴露宿主专用导出缓存。传输独立于页面调用，以加密任务记录、真实流和 JobScheduler 跟踪状态；普通上传未知结果不自动重放，下载断点需强ETag/Range校验。系统分享通过宿主选目标，Bot只收草稿。通知监听必须由用户开启系统权限，插件再明确选择来源App，只保存短期内存事件。API连接由Keystore加密保存，宿主代加凭据，OAuth PKCE只接受当前版本的一次性流程，凭据请求不跟随重定向。

拍照、联系人单选、日程编辑、前台粗略定位通过系统交互；伴侣设备关联按插件归属，BLE会话在页面隐藏或版本失效时释放。系统/硬件支持和远端成功仍需读取真实结果，能力发现的available不代替授权和可用性检查。开发者参数见 APK 内 plugin-developer/system-api.md。

系统文件选择器和内容查看器等待用户完成，不受普通 90 秒调用期限限制；上传超时或页面销毁按原上传 requestId 取消原生任务，迟到回执不再更新页面。
