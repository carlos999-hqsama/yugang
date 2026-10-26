// 原生桥：Android 上走 window.PDNative（Kotlin addJavascriptInterface）；
// 桌面浏览器开发/截图时走 tools/devserver.mjs 提供的 /__dev/* 接口（行为等价）。
// 约定：所有密钥（dsh cookie、模型 Key）只保存在原生侧，JS 只拿到“是否已设置”。
import { uuid } from './util.js';

const A = window.PDNative || null;
const pending = new Map();
const listeners = new Map(); // channel -> Set(fn)
let seq = 0;

window.__pd_cb = (id, json) => {
  const request = pending.get(id);
  if (!request) return;
  pending.delete(id);
  clearTimeout(request.timer);
  try {
    const value = typeof json === 'string' ? JSON.parse(json) : json;
    if (value && value.error) request.reject(Object.assign(new Error(value.error), value));
    else request.resolve(value ? value.value : undefined);
  } catch { request.reject(Object.assign(new Error('宿主返回了无效结果'), {code:'invalid_response'})); }
};
window.__pd_ev = (channel, json) => {
  let value;
  try { value = typeof json === 'string' ? JSON.parse(json) : json; }
  catch { return; }
  (listeners.get(channel) || []).forEach(fn => { try { fn(value); } catch (error) { console.error(error); } });
};
export function on(channel, fn) { if (!listeners.has(channel)) listeners.set(channel, new Set()); listeners.get(channel).add(fn); return () => listeners.get(channel).delete(fn); }
const emit = (ch, v) => window.__pd_ev(ch, v);

function call(method, args = {}) {
  if (!A) return dev.call(method, args);
  if (pending.size >= 64) return Promise.reject(Object.assign(new Error('页面调用过多，请稍后重试'),{code:'busy'}));
  return new Promise((resolve, reject) => {
    const id = `c${++seq}`;
    const interactive = ['siteBrowserOpen','voice','botAttachmentPick','contentOpen'].includes(method) || (method === 'hostCall' && ['pick','create','directory','send','request','configure','authorize','camera','calendar','contact','location','pair','unpair','connect'].includes(args.arguments?.op));
    const cancelNative = () => {
      try {
        if (method === 'hostCall') A.call('hostCancel', `cancel-${id}`, JSON.stringify({requestId:id}));
        else if (method === 'botAttachmentUpload' && args.requestId) A.call('botAttachmentCancel', `cancel-${id}`, JSON.stringify({requestId:args.requestId}));
      } catch {}
    };
    const timer = interactive ? null : setTimeout(() => {
      if (!pending.delete(id)) return;
      cancelNative();
      reject(Object.assign(new Error('宿主调用超时'),{code:'timeout',timeout:true}));
    },90000);
    pending.set(id, {resolve, reject,timer,cancelNative});
    try { A.call(method, id, JSON.stringify(args)); }
    catch (error) { pending.delete(id);clearTimeout(timer);reject(error); }
  });
}
function releasePendingCalls() {
  for (const request of pending.values()) {clearTimeout(request.timer);request.cancelNative();request.reject(Object.assign(new Error('页面已关闭'),{code:'cancelled',cancelled:true}));}
  pending.clear();
}
window.addEventListener('pagehide',releasePendingCalls);
window.addEventListener('pd-plugin-dispose',releasePendingCalls);

/* ------------------------------------------------------------------ dev shim */
const dev = {
  async call(method, args) {
    if (method === 'http') {
      const r = await fetch('/__dev/http', { method: 'POST', body: JSON.stringify(args) });
      const j = await r.json(); if (j.error) throw Object.assign(new Error(j.error), j); return j.value;
    }
    if (method === 'copy') { try { await navigator.clipboard.writeText(args.text); } catch { /* 截图环境无剪贴板 */ } return true; }
    if (method === 'toast') return true;
    if (method === 'openExternal') { window.open(args.url, '_blank'); return true; }
    if (method === 'openDshbox') { emit('toast', { text: '（开发模式）将打开 DSHBox' }); return false; }
    if (method === 'voice') throw new Error('开发模式不支持语音输入');
    if (method === 'dshWeb') return devPanes.dshWeb(args);
    const r = await fetch('/__dev/call', { method: 'POST', body: JSON.stringify({ method, args }) });
    const j = await r.json(); if (j.error) throw Object.assign(new Error(j.error), j); return j.value;
  },
};
// 开发模式下用 iframe 模拟原生浏览器覆盖层
const devPanes = {
  dshWeb(st) {
    const vp = document.querySelector('#scr-harness .dshweb .vp');
    if (!vp) return true;
    let f = vp.querySelector('iframe');
    if (st.visible && !f) { f = document.createElement('iframe'); f.src = '/__dev/dshweb/'; vp.appendChild(f); }
    if (f) f.style.display = st.visible ? 'block' : 'none';
    return true;
  },
};
/* ------------------------------------------------------------------ public API */
export const isAndroid = !!A;
/** dsh 文件的原生代理地址；交互文档使用独立、无同源权限的 sandbox。 */
export const FILE_BASE = A ? 'https://dshfile.pd.local' : `${location.origin}/__dev/dshfile`;
export const fileUrl = (abs) => FILE_BASE + (abs.startsWith('/') ? abs : '/' + abs).split('/').map(encodeURIComponent).join('/');
export const fileFromUrl = (u) => {
  if (!u || !u.startsWith(FILE_BASE + '/')) return null;
  try { return decodeURIComponent(u.slice(FILE_BASE.length)); } catch { return null; }
};

export const native = {
  capabilities: () => call('hostCapabilities'),
  host: (capability, arguments_ = {}) => call('hostCall',{capability,arguments:arguments_}),
  files: request => call('hostCall',{capability:'plugin.files',arguments:request}),
  transfer: request => call('hostCall',{capability:'plugin.transfer',arguments:request}),
  notifications: (request = {op:'status'}) => call('hostCall',{capability:'plugin.notifications.read',arguments:request}),
  share: request => call('hostCall',{capability:'plugin.share',arguments:request}),
  connections: request => call('hostCall',{capability:'plugin.connection',arguments:request}),
  system: request => call('hostCall',{capability:'plugin.system',arguments:request}),
  browserSnapshot: () => call('hostCall',{capability:'plugin.browser',arguments:{op:'snapshot'}}),

  call,
  getConfig: () => call('getConfig'),
  deepseekModels: (key = '') => call('deepseekModels', {key}),
  setConfig: (patch) => call('setConfig', patch),
  /** target: 'dsh'。原生侧补登录 Cookie，去掉 Origin。 */
  http: (target, method, path, body, opts = {}) => call('http', { target, method, path, body: body == null ? null : (typeof body === 'string' ? body : JSON.stringify(body)), timeoutMs: opts.timeoutMs || 20000 }),
  copy: (text) => call('copy', { text }),
  toast: (text) => { emit('toast', { text }); return A ? call('toast', { text }).catch(() => {}) : Promise.resolve(); },
  openExternal: (url) => call('openExternal', { url }),
  openDshbox: () => call('openDshbox'),
  dshPair: (input) => call('dshPair', { input }),
  dshUnpair: () => call('dshUnpair'),
  rtStatus: () => call('rtStatus'),
  rtDiag: () => call('rtDiag'),
  rtStart: () => call('rtStart'),
  rtRestart: () => call('rtRestart'),
  backgroundSettings: (which) => call('backgroundSettings', { which }),
  bots: (a = { op: 'list' }) => call('bots', a),
  chooseFiles: () => call('botAttachmentPick'),
  uploadAttachment: args => call('botAttachmentUpload', args),
  removeAttachment: handle => call('botAttachmentRemove', {handle}),
  cancelAttachment: requestId => call('botAttachmentCancel', {requestId}),
  forgetAttachmentReceipt: args => call('botAttachmentForgetReceipt', args),
  openContent: async args => {
    const value = await call('contentOpen', args);
    if (value?.opened === false && value.reason) throw Error(value.reason);
    return value;
  },
  voice: () => call('voice'),
  dshWeb: (state) => call('dshWeb', state).catch(() => {}),
  ready: () => (A ? call('ready').catch(() => {}) : Promise.resolve()),
};

/** SSE（dsh API）。原生：OkHttp EventSource；开发：EventSource 走代理。 */
export function sse(target, path, { onEvent, onState }) {
  const id = uuid();
  let closed = false;
  if (A) {
    const off = on('sse:' + id, (e) => { if (e.kind === 'event') onEvent(e.event, e.data); else onState && onState(e.kind, e.error); });
    A.call('sseOpen', id, JSON.stringify({ target, path }));
    return { close() { if (closed) return; closed = true; off(); A.call('sseClose', id, '{}'); } };
  }
  const es = new EventSource(`/__dev/sse?target=${target}&path=${encodeURIComponent(path)}`);
  es.onopen = () => onState && onState('open');
  es.onerror = () => onState && onState('error', 'SSE 断开');
  for (const ev of ['hello', 'change']) es.addEventListener(ev, (m) => onEvent(ev, m.data));
  return { close() { closed = true; es.close(); } };
}

/** WebSocket（dsh /api/remote.mux）。原生：OkHttp，带 Cookie、不带 Origin。 */
export function ws(target, path, { onOpen, onMessage, onClose }) {
  const id = uuid();
  if (A) {
    const off = on('ws:' + id, (e) => {
      if (e.kind === 'open') onOpen && onOpen();
      else if (e.kind === 'message') onMessage(e.data);
      else if (e.kind === 'close') { off(); onClose && onClose(e.code, e.reason); }
    });
    A.call('wsOpen', id, JSON.stringify({ target, path }));
    return { send: (t) => A.call('wsSend', id, JSON.stringify({ text: t })), close: () => A.call('wsClose', id, '{}') };
  }
  const s = new WebSocket(`${location.origin.replace(/^http/, 'ws')}/__dev/ws?target=${target}&path=${encodeURIComponent(path)}`);
  s.onopen = () => onOpen && onOpen();
  s.onmessage = (m) => onMessage(m.data);
  s.onclose = (e) => onClose && onClose(e.code, e.reason);
  return { send: (t) => s.readyState === 1 && s.send(t), close: () => s.close() };
}

// Foreground subscription. Hiding/unloading a plugin releases its notification feed.
native.watchNotifications = (packages, onEvent, {onError = () => {}, signal} = {}) => {
  let stopped=false, subscriptionId=null, timer=null;
  const report=error=>{try{onError(error);}catch(e){console.error(e);}};
  const stop=()=>{if(stopped)return;stopped=true;clearTimeout(timer);window.removeEventListener('pd-plugin-hidden',stop);signal?.removeEventListener('abort',stop);if(subscriptionId)native.notifications({op:'unsubscribe',subscriptionId}).catch(()=>{});};
  const poll=async()=>{
    if(stopped)return;
    try {
      if(!subscriptionId)subscriptionId=(await native.notifications({op:'subscribe',packages})).subscriptionId;
      const result=await native.notifications({op:'poll',subscriptionId});
      if(!stopped)for(const event of result.events||[])try{onEvent(event);}catch(error){report(error);}
      if(result.gap&&!stopped)report(Object.assign(new Error('通知事件有缺口，请刷新当前通知'),{code:'event_gap'}));
    }catch(error){subscriptionId=null;if(!stopped){report(error);try{const status=await native.notifications({op:'status'});if(!status.enabled)stop();}catch{}}}
    if(!stopped)timer=setTimeout(poll,2000);
  };
  window.addEventListener('pd-plugin-hidden',stop);signal?.addEventListener('abort',stop,{once:true});
  if(signal?.aborted)stop();else poll();
  return {stop};
};
