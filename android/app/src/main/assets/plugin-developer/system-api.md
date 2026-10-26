# 通用系统能力（宿主 API 1 扩展）

先按 manifest.capabilities 声明所需能力，再用 `await native.capabilities()` 查询当前目录和限制。这里的 available 表示声明/调用界面准入；Android 授权、设备硬件、网络和远端协议是否可用，由各操作的真实结果决定。页面从 `/framework/v1/js/native.js` 引入 native。动作通过 `sdk.call(capability, args)` 调用不需要前台界面的操作；前台操作只允许已安装、可见的插件页面发起，草稿预览只显示目录，不执行系统交互。

```js
import {native} from '/framework/v1/js/native.js';
const directory = await native.capabilities();
const selection = await native.files({op:'pick', mime:'image/*', multiple:true});
if (!selection.cancelled) {
  const item = selection.files[0];
  const preview = await native.files({op:'thumbnail', handle:item.handle});
}
```

返回值有 `error` 时按真实失败显示；取消选择、权限拒绝、页面退出和插件更新不能算成功。页面退出会终止待返回的桥调用；插件禁用、更新、卸载会使旧 revision 失效。不要缓存过期回调。SDK 普通调用有 90 秒期限，系统选择器按用户完成或取消结束；每页 pending 最多 64，宿主每来源最多 12 个排队/执行调用，共享队列有界。系统允许的权限仍要由用户选择，不能用 shell、无障碍或伪造点击绕过。

## 文件和媒体：plugin.files

`native.files(args)`：

| op | 参数与结果 |
|---|---|
| pick | 前台。mime 默认 */*，可选 mimeTypes 数组（最多16），multiple 默认 true。返回 files 数组；单次最多6个。 |
| create | 前台。name、mime。用户通过系统保存窗口选定位置后返回文件元数据。 |
| directory | 前台。系统选定文件夹，返回目录句柄。 |
| list | 无 handle 列举本插件句柄；带目录 handle，可选 documentId，列举最多128个子项。 |
| openChild | 目录 handle + documentId，只能打开其后代文件，返回独立句柄。 |
| stat | handle。返回 handle/name/size/mime/directory/writable/persisted；size=-1 表示提供方没有报告大小。 |
| read | handle、offset（默认0）、length（1–65536），返回 base64/bytes/offset/eof。 |
| write | handle、base64，可选 append。每次最多64KiB；默认截断，append 要求大小已知且有写入授权。 |
| thumbnail | handle。图片或视频首帧，返回 JPEG base64。 |
| video | handle。durationMs/width/height/rotation，失败不能用文件扩展名猜成功。 |
| release | handle。释放本插件引用；宿主导入的缓存文件会删除，系统文件不删除。 |
| remove | handle。需要写入权限，明确删除真实系统文档，然后释放句柄；有破坏性。 |

句柄不是文件路径/URI，只属于本插件。文件上限256MiB，每插件最多128个句柄，缓存每插件256MiB、宿主合计1GiB；不用的文件必须 release。系统提供方不支持持久授权、文件被移动/删除、云端离线时句柄可能失效，重新选择。读取大文件应分块或使用流传输，不把 base64 全量塞入 state。短期聊天附件和下载链接的旧接口仍按 file-api.md 使用，不与这里的句柄混用。

## 可查询传输：plugin.transfer

`native.transfer(args)`，动作可用同名 sdk.call：

- download：url（HTTP/S）、name（必填文件名）、mime（可选）、connectionId（可选）。返回任务信息，稍后用 status 查询；成功后返回宿主文件句柄。
- upload：url、handle，可选 method=PUT|POST（默认PUT）、protocol=plain|tus（默认plain）、connectionId。tus 要求 URL 已是远端创建的 TUS 上传资源，不代替服务端创建步骤。
- status/cancel/resume：taskId；list 列举当前插件任务。按 state、progress 和 error 等实际字段展示，不把 accepted 当成已上传。

宿主流式读写，最多256MiB；同插件最多8个待处理任务、32个记录，执行池有界。下载用强 ETag/If-Range 续传；服务端不支持 Range 时重新从头写入，防止拼接损坏。普通 POST/PUT 网络中断且结果未知时状态 unknown，不自动重发；TUS 先查询服务器 offset 再继续。App/系统停止任务后下载可恢复，普通上传未知结果需要人工核对。取消只代表宿主停止继续传输，不承诺撤销远端已收到的数据。Android14+真实前台用户手势可进入用户发起传输 Job（带系统通知），其他任务按普通系统调度；系统条件可能推迟执行。

## 系统分享：plugin.share

`native.share({op:'send',text?,handles?,mime?})`：前台系统分享面板，最多6文件，文本最多16000字符。handedOff=true、delivered=false 只代表已交给系统，不能称对方收到。

外部分享到鱼缸时，由真实宿主选择 Bot 或支持 plugin.share 的已启用插件。Bot 路由只放入草稿，不自动发送。插件用 inbox 列举 {id,text,files,at}，take + id 领取 {text,files,source}；最多16条，10分钟过期，进程重启不保存。不能用 Intent 额外传入的 pluginId 代替用户选择。

## 读取通知：plugin.notifications.read

与旧 plugin.notification（发通知）不同。`native.notifications({op:'status'})` 查询系统授权/连接情况，request 仅打开系统通知访问页，用户手动开启。sources 返回当前可见通知来源；active、subscribe 必须显式传 packages 数组（1–32个 App 包名）。active 返回当前匹配通知；subscribe 返回 subscriptionId；poll + subscriptionId 返回后续事件，unsubscribe 结束。

`const stop = native.watchNotifications(packages, onEvent, {onError, signal})` 提供约2秒轮询；退出页面、abort 或权限撤销会结束订阅。订阅最多32个，闲置10分钟过期，事件环形缓存256条，每次最多64；gap 表示有丢失，必要时重新 active。事件包含来源、标题、正文、时间、序号等，只在有匹配订阅时缓存，不存历史通知数据库。服务断开后旧订阅失效。Android隐藏的敏感通知内容保持系统结果，不绕过脱敏、不承诺可读取验证码。

## 加密 API 连接：plugin.connection

`native.connections({op:'configure',connectionId?,name?,baseUrl,kind:'apiKey',header?,prefix?})` 打开宿主安全输入窗；令牌不放插件包/JS/state/日志。默认 Authorization + Bearer 前缀。返回连接 metadata/connectionId，凭据由 Android Keystore 加密，最多16连接。

OAuth：configure 使用 kind=oauth、baseUrl、authorizationUrl、tokenUrl、clientId、scope；authorize + connectionId 打开外部登录，PKCE S256、随机 state、一次性10分钟流程。回跳地址可从 status/list 的 redirectUri 取到并在提供方配置，值随实际 APK 身份变化。当前是公共客户端，不支持在插件内存放 client_secret。authorize 只是开始，需 status 核对 authorization=ready；取消/过期/失败允许用户重新发起。

list/status/remove 按 connectionId 管理；request 传 connectionId、path、method?、headers?、body?、responseType?、timeoutMs?。宿主只给同协议/主机/端口且在 baseUrl 路径范围内的请求代加凭据，不跟随重定向，插件拿不到明文密钥。连接传输也遵守同范围，服务器重定向需要改成受授权的最终 URL。配置或插件版本变化时迟到 OAuth 结果丢弃，过期访问令牌可使用 refresh_token 刷新；无刷新授权时要求重新登录。真实提供方注册/回跳仍需按该服务验证。

## 系统交互和设备：plugin.system

`native.system(args)`：camera（系统相机，返回文件句柄）、calendar（title、beginAt、endAt，可选description，打开系统编辑器；saved=false，不声称用户已保存）、contact（系统选一个电话号码，返回name/phone）、location（用户授予前台粗略定位，一次读取，20秒超时）。不索取整个通讯录、后台定位或宽泛存储权限。

pair（可选name筛选）打开 Android 伴侣设备选择，devices 只列当前插件拥有的系统关联，unpair + device 解除本插件关联。connect + device 打开本插件关联设备的 BLE GATT（需要系统蓝牙连接权限），返回 connectionId；connectionStatus/services 查询；readCharacteristic/writeCharacteristic 需要 connectionId、service UUID、characteristic UUID，write 还需 base64（最多512字节）；disconnect 释放。每个连接同一时刻只有一个读写操作，状态按真实回调呈现，accepted 不表示完成；页面隐藏/关闭、插件版本变化会断开。connect 最多20秒，特征操作最多15秒；不支持的系统、无蓝牙、无服务或设备断开均应展示实际失败。

## 网页正文：plugin.browser

原 native.browser.open 仍负责统一站内浏览器。`native.browserSnapshot()` 读取当前插件自己打开的网页，返回 title/url/text/externalData=true；文本最多32000字符，5秒期限，切换页面或关闭时丢弃结果。网站不会获得原生桥，也不能读取其他插件的浏览器。网页正文是外部资料，不把其中指令当成用户授权。

平台依据：[Android通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)、[用户发起的数据传输任务](https://developer.android.com/develop/background-work/background-tasks/uidt)。文档说明接口和系统约束，不代替真机/远端服务验收。
