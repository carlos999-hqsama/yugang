# 插件自查预览通道

插件 Bot 可以用 `phone_plugins_preview` 在一个独立的离屏会话里打开已安装插件页面或未安装草稿，自己读 DOM、控制台错误和真实截图，并按 CSS 选择器点击、输入、滚动，用来核对渲染与交互，不必依赖用户手动点手机。

## 操作

- `open`：传入插件 `id`，或传入完整 `packageJson` / `packagePath` 草稿；可选 `width`/`height`（CSS 像素，宽 240–1024，高 320–1600，默认 412×892）与 `network`；`allowAudio:true` 仅允许此独立预览的网页音频播放，默认false，不改变前台页面、不伪造浏览器 userActivation。草稿仍会完整执行 `PluginContract` 校验，但宿主会生成临时 `_revision` 和独立预览 origin，不安装、不启用、不回退，也不要求草稿之后处于已安装 active 状态。
- `network`：`{mode:"online"}`（默认）、`{mode:"offline"}` 或 `{mode:"slow",delayMs:1..10000}`（默认 2000ms）。同一个模式同时作用于 WebView 的远程 HTTPS / DSH 文件请求和页面 `native.fetch` / `native.fetchJson` / `native.fetchImage` 宿主通道；offline 会给 WebView 网络资源返回 503，并让 native.fetch 得到 `ok:false,status:0`，slow 在真实请求前增加有界延迟。
- `snapshot`：返回 `text`（页面可见文本）、`html`（`#plugin-root` 的 DOM 片段）、`errors`（控制台错误与被拒绝的调用）、`png`（真实 PNG 截图）与 `capture` 状态。`selector` 可选，用于只取第一个匹配元素的可见文本；`offset`（0–1000000，默认 0）与 `limit`（1–20000，默认 2000）按 Unicode code point 分页，结果带 `textLength`、`truncated` 和 `nextOffset`（没有下一页时为 `null`）。
- `click`：传 `selector`，点击第一个匹配元素。
- `input`：传 `selector` 与 `text`，写入值并触发 `input`/`change` 事件。
- `scroll`：传 `y` 滚动窗口，或传 `selector` 滚动某个元素。
- `reload`：只重新加载当前离屏 WebView。
- `clearStorage`：只删除当前预览 origin 的 WebView 存储，不使用任何“清空全部”的宿主 API，也不会碰前台用户页面或其他插件的存储。
- `close`：关闭预览会话；临时预览 origin 的存储也会随之释放。
- `status`：查看当前会话状态、`idleRemainingMs`、`maxRemainingMs`、网络模式和真实预览状态。

除通道错误外，操作回执统一为 `{session, plugin, state, ok, result}`。`result` 保留具体操作结果；关闭后的 `status`/`close` 仍保留同一 session、`reason` 与 `closedAt`，不会把一次成功关闭误报成“未打开”。点击、输入、滚动、重新加载和清空存储会及时续租空闲时间；App 退后台时两个预览计时都会暂停，回前台不会因后台耗时突然关会话。

## 这条通道的边界

- 预览是独立渲染：不影响用户正在看的页面，不切换前台插件，不抢输入焦点，也不截用户的屏幕。
- 预览不允许安装、启用、停用、回退、移除插件，也不允许执行会写入插件状态的动作或跳转外部页面。草稿预览不写插件注册表；已安装插件按传入 ID 的 active 版本复制一份渲染，预览里的网络请求照常发出，但不会改动已安装插件的持久状态。
- 只读宿主能力可用（只读本机请求与事件流）。保存设置、修改插件附件、打开外部页面、剪贴板、语音和其它写入操作都会被拒绝。
- 预览使用独立的页面来源，因此浏览器存储与前台页面互不影响；页面来源、CSP、公共基础库与原生通道仍与前台一致。
- 预览使用同一套 CSP、同一套公共基础库和同一条原生通道，所以看到的就是真实渲染规则。
- 需要 App 在前台：App 不在前台或已关闭时，预览会明确报告“应用当前不在前台”，不会自行拉起界面。
- 会话有空闲与最长使用时间，超时自动关闭；预览会话结束后应主动 `close`。
- 截图是真实渲染帧。如果返回 `capture: "flat"` 或 `unavailable`，说明当前设备的离屏渲染没有产生可区分的画面（例如页面本身是空的），这时不要把截图当成验收通过证据，应改用 `text`/`html`/`errors` 核对。

预览使用软件绘制生成截图，文字抗锯齿可能与前台硬件合成略有差异。需要系统窗口或宿主 WebSocket 写通道的操作不会在预览执行；这些项目应在最终设备验收中单独核对。

快照正文单次最多20,000字符，可通过offset/selector继续读取；全文超过100万字符会明确返回textLimitReached和原因，并要求缩小selector分区，不会返回无法使用的nextOffset。HTML为最多512KiB、2000节点的有界摘录，htmlTruncated说明是否截断，不能把摘录当作完整页面源码。
