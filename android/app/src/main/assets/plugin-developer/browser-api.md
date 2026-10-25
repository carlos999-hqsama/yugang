# 站内浏览器与插件登录会话

## 真实网站浏览器

页面插件声明 `plugin.browser`，在真实点击处理里调用：

```js
import {native, on} from '/framework/v1/js/native.js';
button.onclick = async () => {
  const result = await native.browser({url:'https://example.test/', title:'网站'});
  // 此 Promise 在用户关闭浏览器后完成。
  console.log(result.opened, result.closed, result.finalUrl);
};
const stop = on('browserDownload', file => {
  // downloaded / uri / displayPath / name / mime / bytes；不含 Cookie。
  // 用户另点“打开”时可 native.openFile({uri:file.uri})。
});
```

- 这是独立原生 WebView，支持网页 JS、CSS、正常表单、历史返回和下载；不是 iframe 或静态 HTML 抓取。
- 用户原生触控时间由宿主记录并消耗一次。程序化 `.click()`、页面加载时自动调用、模型传 `userGesture:true` 均不能获得打开准入。不要先等一段网络请求再使用已过期的点击。
- 请求仅接受 `url` 与可选 `title`，仅限 HTTP/HTTPS；账号密码嵌入 URL、file/javascript/data/intent 等地址拒绝。没有站点脚本到宿主的桥，也不注入宿主账号凭据。
- 每个稳定插件 ID 对应独立 WebView profile，隔离 Cookie、localStorage、IndexedDB 与缓存。更新、回滚和同一安装下重启保留；卸载精确清理该插件的 profile 数据（未加载时删除，已加载时使用绑定 profile 的数据清理 API），不清宿主或其他插件。
- Android System WebView 必须支持 MULTI_PROFILE 与定向 DELETE_BROWSING_DATA。不支持时明确返回未打开，不用共享 profile 冒充隔离。预览通道拒绝打开真实网站。
- 原生顶部显示标题、返回、更多和关闭；更多菜单显示当前域名，没有可编辑地址栏。关闭回执 `{opened,closed,finalUrl}` 只说明浏览事实，不能把最后 URL 或有 Cookie 当作“已登录”。真实登录状态由网站自己的认证结果决定。
- 下载仅在原生侧读取该 profile 的 Cookie；文件保存到系统 `Download/鱼缸`，回执有真实 MediaStore URI。关闭/移除后不把迟到事件交给其他页面。

## API 型登录

页面或原生插件动作声明 `plugin.apiSession`。普通请求默认 `credentials:'omit'`；只有显式 include 且宿主已绑定当前插件身份时，才附带该插件的 API CookieJar：

```js
await native.fetchJson('https://example.test/login', {
  method:'POST', body:JSON.stringify(form), credentials:'include'
});
await native.fetchJson('https://example.test/me', {credentials:'include'});
const stream = native.fetchStream('https://example.test/events', {credentials:'include'});
const status = await native.apiSession({op:'status'});
await native.apiSession({op:'clear'}); // API 会话退出；不清站内浏览器 profile。
```

动作 SDK 可调用 `sdk.call('plugin.apiSession',{op:'status'})` / `clear`。通过 app.call 的 fetch 同样按宿主动作身份派生会话。请求不能借 `id/pluginId` 字段冒充另一个插件；匿名通道不能开启持久登录。

CookieJar 与 WebView profile、宿主 NAS/模型/DSH 登录相互独立。遵守真实域名、host-only、Path、Secure、过期与删除规则；会话 Cookie 和持久 Cookie 可在同设备重启后重载。数据用 Android Keystore AES/GCM 加密，不放 plugin.state、插件页面 localStorage、日志或插件导出包。不提供原始 Cookie getter，响应中的 Set-Cookie 也不交给插件 JS。

status 仅含计数、更新时间、hasCookies 等元数据，不是已认证声明。更新与启停保留会话；卸载或 clear 会撤销旧 CookieJar，晚到响应不能重建已清除数据。换机或清除 App 数据后需要重新登录，不导出或复制 Keystore 密钥。

## 宿主原生内容区与预览上下文

站内网页占 App 原生左侧菜单右边的内容区，不是全屏 Dialog。左菜单原样常驻、始终可点击；切换 App 菜单将关闭当前浏览器并返回 opened/closed/finalUrl，回执不会把导航抢回来。工具栏仅返回、标题、更多和关闭；当前域名、刷新和本次下载记录在内容区内的更多菜单。外站 HTML/CSS 不被宿主改写。加载进度、系统安全区与键盘避让由宿主负责。

`native.isPreview` 是只读布尔值，来自原生桥预览上下文，无需嗅探 origin。预览调用 `await native.browser({url,title})` 返回 `{opened:false,closed:false,reason:"preview",code:"preview_browser_disabled"}`，不打开真实站点；普通策略拒绝可 catch，错误含 code/reason/expected/retryable。预期策略拒绝不计为页面 errors、不销毁 preview session、不关闭其他 SSE/工具流；某个被拒绝的流只结束自身。真实页面或渲染异常仍会报告。

站内下载上限256 MiB：响应头和实际接收字节都会核对；超限会明确失败并删除未完成文件，关闭浏览器仍会取消在途下载。
