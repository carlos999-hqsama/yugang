# 插件宿主契约

## 入口与按需参考

本文件是随 App 更新的当前框架规则。开始插件工作先读本文件；旧技能正文与此冲突时以本文件为准，用户当前明确要求优先。仅加载当前步骤需要的参考，不一次读完全部文档。

| 当前工作 | 按需参考 |
|---|---|
| 需求拆解、实现方式与状态管理 | [通用软件开发规范](software-development.md) |
| 创建或改动页面 | [统一视觉规范](visual-standard.md)、[页面接口](page-api.md) |
| 远程请求、图片和错误处理 | [联网接口](network-api.md) |
| 模型、翻译、后台任务或通知 | [宿主服务](service-api.md) |
| 草稿交互、截图、错误态与交付验证 | [独立预览](preview-api.md) |
| 安装、更新、启停、卸载 | 本文件的管理与清理章节 |

先核对需求与现有插件，修改时保留原 ID，按当前步骤读相应文档。迭代优先用未安装草稿预览，修复失败场景再交付；明确报告“草稿预览通过（未安装）”或“安装版通过”，不能混为一谈。工具回执、代码推断与设计图都不代替真实页面和业务验证。规则文案负责指导；包格式、目录、能力和 revision 仍由宿主执行时校验。

## 包与页面

左侧菜单名称最多 4 个字。超过 4 个字时宿主仅显示前 3 个字加单个省略号“…”；完整 manifest.title 不改写，插件管理和无障碍朗读保留全名。

API 版本 1。插件包是 JSON：

```json
{"manifest":{"apiVersion":1,"id":"my-plugin","title":"我的插件","version":"1.0.0","kind":"native","capabilities":[]},"view":{"type":"column","children":[{"type":"title","text":"我的插件"},{"type":"button","text":"检查","action":"check"}]},"code":"export async function run({action,inputs,state},sdk){return {view:{type:'text',text:'已执行'},state,result:{ok:true}}}","state":{}}
```

- ID 为小写字母开头，后续小写字母/数字/短横线，共2–48字符；`harness`、`settings`、`plugins` 是宿主保留ID。
- 只有 `kind:native`。禁止 `kind:builtin` 和 `manifest.entry`。整包最大2MiB，动作code最大64KiB，state最大32KiB；state不放秘密。
- 原生组件：column/row/card(children)、text/title(text)、input(key/value/hint)、button(text/action)、link(text/url)、image(threadId/mediaId/name)、attachments(key/threadId/value)。公共链接仅HTTP/S。
- 动作导出 `run({action,inputs,state},sdk)`，返回 `{view,state,result?}`。`sdk.call(name,args)` 使用宿主已实现能力：app.call、plugin.state、plugin.media、plugin.model、plugin.background、plugin.notification、plugin.tts。具体参数以当前插件源码与已安装包为准。安装新包不意味着宿主突然支持新Android权限、原生系统接口或外部协议。
- 页面统一使用 `manifest.frameworkVersion:1`、`view:{type:"page",entry:"main.js",styles:["page.css"]}` 与 `resources:{"main.js":"...","page.css":"..."}`，最多128项业务js/css/svg/json。不要打包HTML、框架、原生桥或宿主旧页面。
- 页面入口导出 default 页面定义，通过 `/framework/v1/page.js` 的公共接口组织数据、渲染和生命周期；组件和样式由宿主基础库提供。具体接口与模板见 [页面接口](page-api.md)，不猜未实现方法。复杂页面可定义业务视图，但仍使用统一容器/导航/生命周期。
- APK只有核心和公共基础库；插件单独安装在手机私有目录。分享插件时只导出代码和公开声明，不含state、账号、凭据、聊天和运行文件。普通read导出是用于自己维护的完整包，不等于对外发布包。
- 加载只认本包的准确资源路径，不支持任意本机文件和外部脚本。插件代码与App同Android UID，属于受信任代码；这不是恶意插件隔离沙箱。
- 插件页面与原生动作都有官方通用外网请求通道（宿主代发，带超时、状态、大小与取消），页面另有真实逐块读取的 `native.fetchStream`，原生动作可用同一 start/poll/cancel 协议；远程图片取回后作为 data URL 渲染。页面可直接发出 HTTPS/WSS 请求，远程脚本仍由 CSP 禁止。具体参数见 [网络请求](network-api.md)。
- 插件 Bot 可以在独立离屏会话里自查插件页面（DOM、控制台错误、真实截图、按选择器点击/输入/滚动），不影响前台页面与已安装插件状态；详见 [自查预览](preview-api.md)。
- 插件更新、停用、移除会让旧revision失效。不要保留失效通道或把迟到结果写回新版本。App重启不会复活已移除插件。

## 管理工具

`phone_plugins_list`、`phone_plugins_read(id,exportPath?)`、`phone_plugins_install(packagePath或packageJson)`、`phone_plugins_enable`、`phone_plugins_call(id,actionName,inputsJson?)`、`phone_plugins_rollback`、`phone_plugins_remove`。

页面插件read默认只返回元数据与资源清单；需要改完整资源时明确导出。工作文件可由文件工具编辑，实际安装必须经管理工具，不修改宿主注册表。

## 卸载与清理

卸载由插件 Bot 在核对用户意图后发起，或由用户在宿主管理页移除；宿主按插件 ID 精确清理，不按标题或关键词全局搜索。清理使用上一节已有的管理工具，不新增未实现的宿主工具、能力名或返回字段。

`phone_plugins_remove(id)` 后，宿主应完成：

- 安装态：删除该插件的安装记录、当前版本、可回退版本与随包状态副本。`phone_plugins_list` 不再返回该 ID，`phone_plugins_read`、`phone_plugins_call` 与页面/菜单入口都不能再使用它；App 重启不会复活已移除插件。
- 页面与在途调用：释放该插件已打开的页面及其 revision，取消该插件的原生动作、页面动作、宿主代发请求和媒体读取等在途调用；之后迟到的旧结果不写回任何状态。
- 计划与通知：取消该插件的后台计划、在途后台动作，以及属于该插件的已排队通知和待点击通知入口（见 [服务契约](service-api.md)）。
- 专属状态与缓存：删除该插件的持久状态与随包初始状态副本，以及该插件专属的草稿预览缓存、附件图片和业务数据缓存。
- 浏览器存储：清理该插件在前台的页面来源（当前为 `https://plugin-<id>.pd.local`）对应的 WebView 浏览器存储，例如 localStorage、IndexedDB 与 Cache Storage；只清该插件自己的来源，不使用清空全部存储的接口。

移除先原子提交安装态失效标记，再清理资源。回执 `removed:true` 表示已移除；`cleanupPending:true` 或 `warning` 表示资源清理尚未完成，不能据此宣称彻底清理。回到 App 时宿主在后台重试；同 ID 重装必须先完成旧清理，不能用反复移除或改注册表绕过。只有 `cleanupPending:false` 且实际状态核对通过，才能报告清理完成。

停用 `phone_plugins_enable(id,enabled:false)` 与卸载不同：停用会按 revision 失效该插件的页面与计划，但保留安装记录与插件数据，重新启用后继续可用；卸载才清理安装态。

不在清理范围：共享 `/CONFIG`、插件 Bot 自己的 `/BOT` 工作区（记忆、归档、开发草稿）、`/PLUGINS/<id>` 源码、其他插件的记录与数据、由插件自行提交到外部服务的任务，以及模型预算记录（删除插件不重置当天用量）。卸载不会撤销远端任务，也不删除开发源码；用户明确要求清理源码时才按 SKILL.md 的边界处理。

本节是宿主清理契约，不是实现或验收结论：清理结果必须用真实工具逐项核对（见 [SKILL.md](SKILL.md)），不能只看菜单是否消失；实际行为以当前 App 版本的宿主为准。

## 宿主模型和后台服务

`plugin.model`、`plugin.background`、`plugin.notification` 分别提供模型/翻译、定时动作、系统通知。页面由宿主绑定当前插件与 revision，原生动作用 `sdk.call` 同名能力。密钥不出原生，限额、预算和错误按 [服务契约](service-api.md) 处理。

通用音频能力：`plugin.tts` 与 `plugin.voice` 的调用、计时及生命周期见 service-api.md；流式网络见 network-api.md。底座保留真实实现，不携带业务连接或凭据。


## 插件自己的回复风格

插件需要生成评论、摘要或回复时，在自己的设置页管理相应风格，不把业务风格放进系统设置，也不改写共同人格。非秘密风格跟随插件自身配置保存；生成当次输出前读取当前值，不把创建任务时的旧值永久锁进排队任务。

上下文按职责分层：宿主边界与主人的明确要求、共同人格及学习倾向、当前任务、该插件的场景风格、带来源标识的外部素材。场景风格可以细化默认表达，例如希望公开评论有观点、有共鸣、方便接话；它只作用于对应输出字段，不能改变客观证据、事实、输出格式或权限。没有该场景或没有风格内容时不注入占位说明，不重复拼接同一模块。

自定义风格使用有界文本并校验类型与长度。保存必须核对实际回执；刷新不能覆盖正在编辑的草稿，切换连接或插件失效后，旧回执不能改写新状态。修改风格后，至少用合成数据核对下一次生成实际读取新值、未授权/非目标场景不注入、客观字段和其他插件不受影响。不能只因设置字段存在就宣称生效。
