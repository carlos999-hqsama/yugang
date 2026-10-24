# 插件通用网络请求

插件页面和原生动作都有一条官方外网请求通道，由宿主代发。页面不再被判定为“离线插件”，远程接口和远程图片都能实时使用。

## 页面里怎么用

公共基础库 `/framework/v1/js/native.js` 提供通用请求和流式请求：

```js
import {native} from '/framework/v1/js/native.js';

// 文本或 JSON
const result = await native.fetch('https://api.example.com/v1/items', {timeoutMs: 8000});
if (result.ok) console.log(result.status, result.text);

// 直接解析 JSON
const data = await native.fetchJson('https://api.example.com/v1/items');

// 远程图片：取回后是 data URL，可直接放进 img.src
const src = await native.fetchImage('https://cdn.example.com/cover.png');
document.querySelector('#cover').src = src;

// 真实逐块读取；句柄用于取消
const stream = native.fetchStream('https://api.example.com/items', {
  timeoutMs: 8000,
  maxBytes: 2 * 1024 * 1024,
  onFirstChunk(chunk) { /* 首个 body 字节已到达 */ },
  onChunk(chunk) { /* chunk.base64、chunk.bytesSoFar */ },
  onDone(result) { /* result.emptyBody 明确表示空响应体 */ },
});
stream.cancel();
```

参数（都可省略）：

- `method`：`GET`/`HEAD`/`POST`/`PUT`/`PATCH`/`DELETE`，默认 `GET`。
- `headers`：请求头对象，例如 `{Authorization:'Bearer 你的令牌'}`。
- `body` / `bodyBase64`：请求正文，正文最大 1 MiB。
- `contentType`：默认 `application/json; charset=utf-8`。
- `timeoutMs`：1000–60000，默认 15000。
- `maxBytes`：响应体上限，最大 4 MiB，默认 2 MiB。
- `responseType`：`auto`（按 Content-Type 判断）、`text`、`base64`。

返回值：`{status, ok, code, timeout, cancelled, retryable, contentType, bytes, truncated, finalUrl, headers, text | base64}`。文本响应给 `text`，二进制响应给 `base64`。

取消：`const p = native.fetch(url); p.cancel();` 会真正中断宿主正在进行的请求。

`native.fetchStream` 的可选回调还有 `onError`。每个 chunk 事件都带 `status`、`contentType`、`bytesSoFar`、`finalUrl` 和最多 8 KiB 原始字节的 Base64；首个 chunk 另带 `first:true` 和从宿主收到请求到首个 body 字节的单调毫秒数 `firstChunkMs`。空响应体不产生 chunk，`onDone` 的 `emptyBody:true` 与 `bytesSoFar:0` 明确标记。宿主最多保留 16 个未结束流，每个流最多排队 64 块；页面消费过慢时不丢块、不重排，会终止请求并给 `backpressure` 错误。页面隐藏、插件停用、更新或卸载会立即释放对应请求。

## 原生动作里怎么用

```js
const result = await sdk.call('app.call', {method: 'fetch', args: {
  url: 'https://api.example.com/v1/items', method: 'GET', timeoutMs: 8000,
}});
```

原生动作取消时，宿主会同时中断对应的 HTTP 请求。

原生动作没有 JS 回调通道，可用同一内部协议按需拉取：

```js
const start = await sdk.call('app.call', {method: 'fetchStreamStart', args: {
  url: 'https://api.example.com/v1/items', timeoutMs: 8000,
}});
const batch = await sdk.call('app.call', {
  method: 'fetchStreamPoll', args: {requestId: start.requestId},
});
// batch.done 前反复 poll；batch.events 与页面流式事件同协议
await sdk.call('app.call', {
  method: 'fetchStreamCancel', args: {requestId: start.requestId},
});
```

## 边界

- 通用网络请求不会附带 dsh Cookie 或任何宿主凭据。访问内置对话运行时使用 `app.call` 的 `http`，仅支持 `target: dsh`，地址限定为本机回环；它与不带宿主认证的 `native.fetch` 是不同通道。公开 Core 不提供业务服务目标。
- 只允许 `http`/`https`；地址里不能带账号信息；公网、局域网和本地服务均可访问，不自动附带宿主认证。
- 页面可以直接使用 HTTPS `fetch`、WSS 和 HTTPS 图片/媒体；正常浏览器跨域规则仍适用。接口未开放 CORS 或使用 HTTP 时，使用 `native.fetch`。远程脚本仍由 `script-src self` 限定，代码随插件包管理。
- 请求头有数量与长度上限，且不允许 `Cookie`、`Host`、`Content-Length` 等会破坏连接语义的字段。
- 响应超出上限会被截断或直接报错，`truncated` 会标记截断；不要静默当作完整数据。
- 按标准 HTTP 行为跟随重定向；跨主机时不转发 Authorization，不自动注入宿主凭据。

## 结构化失败

`native.fetch` 的 HTTP 非成功响应仍返回 `ok:false`，保留实际 `status` 和重定向后的 `finalUrl`。传输失败拒绝 Promise；`native.fetchJson` / `native.fetchImage` 也会在 HTTP 错误或无效正文时拒绝，Error 保留 `code/status/timeout/cancelled/retryable/finalUrl`。status=0 表示没有 HTTP 响应，不是成功。

```js
try {
  const value = await native.fetchJson(url);
} catch (error) {
  // 使用字段判断，不分析错误文案。不自动重发写操作。
  if (error.timeout) showRetry('请求超时');
  else if (error.status === 429) showRetry('服务限流，请稍后重试');
  else showError(error.message);
}
```

`code` 包括 `http_error`、`timeout`、`cancelled`、`dns_error`、`tls_error`、`network_error`、`invalid_response`、`response_too_large`；预览断网模拟使用 `offline`。`retryable` 是传输提示，不保证写操作可安全重发。

流式读取以插件、revision 和当前页面/动作实例区分内部编号；两个调用方使用同一个 requestId 不会互相读取或取消。最多16个未释放的流，每个最多64个待消费数据块；SDK持续轮询，原始桥调用方必须消费到结束或显式cancel。已完成但尚未消费的数据保留到消费、取消或owner隐藏/释放，仍计入16个槽位；不提供无限历史响应缓存。
