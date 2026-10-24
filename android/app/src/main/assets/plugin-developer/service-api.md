# 宿主模型与后台服务

模型密钥由鱼缸原生宿主持有，插件不保存或接收 Key。能力在插件 manifest.capabilities 声明，页面身份由当前插件和 revision 绑定，不能传入别人的 ID。

## 模型与翻译：plugin.model

```js
import {native} from '/framework/v1/js/native.js';
const status = await native.model({op:'status'});
// 先展示 configured、allowedModels、limits、budget；额度不足时不要自动循环重试。
const result = await native.translate('Text to translate', {targetLanguage:'zh-CN',maxOutputTokens:512});
const answer = await native.model({op:'chat',messages:[{role:'user',content:'Summarize this text'}],maxOutputTokens:512});
if (!answer.ok) showError(answer.error.message);
```

只使用宿主已有 DeepSeek 配置，固定官方接口与受支持模型，不接受自带 endpoint/provider。用量与账户实际账单分开：宿主只根据供应商响应记入真实 token，用量未知的请求明确显示未确认预算，不声称没有消耗。完整限额和日预算以 status 返回为准。调整预算的界面放插件自己的设置中，不能替用户静默加额度。

```js
await native.model({op:'budget',dailyTokenLimit:4000});
```

## 后台调度：plugin.background

```js
await native.background({op:'schedule',action:'refresh',intervalMinutes:15,payload:{}});
const state = await native.background({op:'status'});
await native.background({op:'cancel'});
```

调度执行插件包的原生 `run({action,inputs,state},sdk)` 动作，与是否显示页面无关。仅有页面 JS 定时器不等于后台任务；原生动作必须真实抓取、保存和处理数据。每插件仅一个周期任务，最短15分钟；Android可能延迟或抢占，不保证准点。页面可见时的60秒刷新是另一种交互，不替代后台任务。

停用、卸载或 revision 失效会取消计划与在途动作；App重新进入时对齐系统计划。系统重启后要打开 App 恢复计划，当前不额外申请开机权限。检查 status 的实际 last-run 状态，不能把系统接受计划当成业务已更新。

## 通知：plugin.notification

```js
await native.notify({title:'发现新内容',body:'有新条目，点击查看。'});
// 原生后台动作：
await sdk.call('plugin.notification',{title:'发现新内容',body:'有新条目，点击查看。'});
```

通知使用已安装插件身份，用户点击才进入插件，发通知不会抢走前台。系统通知许可未开或超过频率限制返回 shown:false 和 reason，插件应在自己的设置中解释；不伪报已送达、不循环请求权限。

模型、后台状态与通知的原生动作能力名称分别是 plugin.model、plugin.background、plugin.notification。它们受当前安装 revision 和取消边界管理。

原生同步动作仍受单次 72 秒总预算约束，不因 TTS 延长进程寿命。宿主在合成后用真实音频时长核对该动作剩余时间，并预留 2 秒播放启动与结果回传；无法完整播放时，在出声前返回 `action_budget_exceeded`，临时音频随之清理。调用方应按句分段，或使用页面播放。这个预算由宿主动作开始时间及外层 RPC 的最早剩余期限决定，排队不重置外层期限，插件不能自行传入延长；页面播放不受原生动作预算限制。

草稿预览可调用模型状态、翻译和对话，按同一插件 ID 计入真实预算，不因临时 revision 获得额外额度；草稿不能修改预算、安排后台计划或发系统通知。断网模拟会在原生层阻止模型请求，返回明确的未发出结果。


## 当前 Bot 的语音回复事件（plugin.voice）

声明 `plugin.voice` 后，仅可见插件页面可以调用：

```js
const voice = native.voice({op:'subscribe',
  onToken({text,index,at,attemptId,firstTokenAt}) {},
  onSentence({text,at,attemptId,firstTokenAt,firstSentenceAt}) {},
  onEnd({at,attemptId,firstTokenAt,firstSentenceAt}) {},
  onError({error}) {}
});
await voice.ready; // 宿主显示开启确认，绑定当前 Bot 与当前会话
voice.cancel();
```

只从确认后的下一段新生成回复开始，不回放已到达 token，不读历史、推理或工具参数，不写聊天。`at` 是核心 JS 桥收到该帧时的 `elapsedRealtimeMs` 单调时间，sentence 按句号、问叹号、分号、换行或160个 Unicode 码点边界分段，不是模型承诺的语义句子。插件处理慢导致缓冲溢出会明确报错，不静默截断。隐藏插件、离开 App、切换 Bot/会话或更新/停用插件即取消，需要重新开启，不跨 Bot 自动续订。仅页面可订阅，离屏预览和后台动作不能绕过确认。

将同一回复的 `firstTokenAt`、`firstSentenceAt` 与 TTS 返回的宿主 `firstAudioAt` 相减，才可计算宿主观察口径的首 token→首音；若错过 start 或没有对应 TTS 回执，则时间未测得，不得用“文本就绪→首音”替代。它不含模型服务产生第一个 token 到手机收到该 token 之间的传输延迟。阶段反馈和最终回复均属可见 assistant 文本，按 attemptId 分开计时。
## 中文语音：plugin.tts

```js
import {native} from '/framework/v1/js/native.js';
const status = await native.tts({op:'status'});
const speaking = native.tts({op:'speak', text:'你好，鱼缸。'});
setTimeout(() => native.tts({op:'stop', id:speaking.id}).catch(() => {}), 100);
const spoken = await speaking;
const file = await native.tts({op:'synthesize', text:'你好，鱼缸。', format:'wav'});
// 原生动作：
const id = crypto.randomUUID();
setTimeout(() => sdk.call('plugin.tts',{op:'stop',id}).catch(() => {}), 100);
await sdk.call('plugin.tts',{op:'speak',text:'你好，鱼缸。',id});
```

manifest 必须声明 `plugin.tts`。宿主优先使用 Android 系统中文 TextToSpeech；只有系统中文语音不可用时才试用免费 Baidu fanyi gettts 回退，插件不能传入 endpoint、key 或凭据。`status` 只报告线路与格式，不探测网络；`speak` 由宿主请求音频焦点并播放，不依赖页面手势。`firstAudioMs` 从宿主收到请求的单调时钟到媒体播放时钟实际推进的首帧时间（`MediaTimestamp`，不是 `start()` 调用），`firstAudioAt` 与回复事件使用同一 `elapsedRealtimeMs` 时钟，`durationMs` 是媒体文件时长，失败结果不会编造数值。

系统语音也可能使用在线声音。`status.requiresNetwork` 仅在系统声音已确认离线时为 `false`；在线声音、无法确认是否离线及百度回退均为 `true`，不表示当前网络已连通，`networkChecked` 仍为 `false`。页面应按此字段显示“无需联网”或“需要或可能需要联网”，不能仅凭 `provider` 为 `android-system` 判定离线。语音会话提示会说明在线朗读文字的去向；直接 `speak` / `synthesize` 使用在线线路时，宿主也会提示系统语音服务或百度会接收待朗读文字，同一种提示在当前 App 进程内只显示一次，避免分段朗读反复打扰。在线语音与自动回退仍可正常使用。

文本最多600个字符/1800 UTF-8字节。`speak` 是到播放结束才 resolve 的结果Promise；页面助手会把将要使用的UUID挂在 `promise.id`，可在等待期间调用 stop。原生动作需要自行传入 `crypto.randomUUID()`。系统线路输出按文件头识别，通常是 WAV；免费回退输出 MP3。显式请求不支持的格式会返回 `ok:false` 和 `supportedFormats`，不会改标签或写空文件。`synthesize` 会落 `PLUGINS/<id>/media/tts/<revision>/` 私有文件；返回 `file.path`、`file.openPath`、页面可直接作为媒体源的 `file.url`、字节数、真实格式和时长。文件按插件与 revision 隔离，更新、停用或卸载会停止旧音频并清理旧 revision 文件。`stop` 只能停止当前插件当前 revision 对应 id，宿主会释放焦点、播放器和 TTS engine。插件与宿主同 Android UID，这不是操作系统级恶意代码沙箱。

草稿预览可调用模型状态、翻译和对话，按同一插件 ID 计入真实预算，不因临时 revision 获得额外额度；草稿不能修改预算、安排后台计划或发系统通知。断网模拟会在原生层阻止模型请求，返回明确的未发出结果。

免费回退的语速为离散 providerSpeed：`floor(rate * 3)`，限制1–9；返回实际 providerSpeed，不能把它称为精确连续语速。系统线路执行系统rate/pitch。原生动作和页面请求的时延都从对应可信宿主接收边界计时，插件不能提供自己的计时起点。

页面`speak.id`可立即stop：若停止先于请求入场，宿主返回`pendingCancelled:true`并阻止该id随后开始；不存在已开始播放时不伪报`stopped:true`。此取消仅匹配当前插件与revision，短期去重记录有界且不持久化。
