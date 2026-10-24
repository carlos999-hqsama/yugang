// 原生桥：Android 上走 window.PDNative（Kotlin addJavascriptInterface）；
// 桌面浏览器开发/截图时走 tools/devserver.mjs 提供的 /__dev/* 接口（行为等价）。
// 约定：所有密钥（dsh cookie、模型 Key）只保存在原生侧，JS 只拿到“是否已设置”。
import { uuid } from './util.js';

const A = window.PDNative || null;
const pending = new Map();
const listeners = new Map(); // channel -> Set(fn)
let seq = 0;

window.__pd_cb = (id, json) => { const p = pending.get(id); if (!p) return; pending.delete(id); const r = typeof json === 'string' ? JSON.parse(json) : json; r && r.error ? p.reject(Object.assign(new Error(r.error), r)) : p.resolve(r ? r.value : undefined); };
window.__pd_ev = (channel, json) => { const v = typeof json === 'string' ? JSON.parse(json) : json; (listeners.get(channel) || []).forEach((fn) => { try { fn(v); } catch (e) { console.error(e); } }); };
export function on(channel, fn) { if (!listeners.has(channel)) listeners.set(channel, new Set()); listeners.get(channel).add(fn); return () => listeners.get(channel).delete(fn); }
const emit = (ch, v) => window.__pd_ev(ch, v);

function call(method, args = {}) {
  if (A) return new Promise((resolve, reject) => { const id = `c${++seq}`; pending.set(id, { resolve, reject }); A.call(method, id, JSON.stringify(args)); });
  return dev.call(method, args);
}

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
    if (method === 'browser' || method === 'dshWeb') return devPanes[method](args);
    const r = await fetch('/__dev/call', { method: 'POST', body: JSON.stringify({ method, args }) });
    const j = await r.json(); if (j.error) throw Object.assign(new Error(j.error), j); return j.value;
  },
};
// 开发模式下用 iframe 模拟原生浏览器覆盖层
const frames = new Map();
const devPanes = {
  browser(st) {
    const host = document.querySelector('#scr-harness .bview');
    if (!host) return true;
    for (const [id, f] of frames) if (!st.tabs.some((t) => t.id === id)) { f.remove(); frames.delete(id); }
    for (const t of st.tabs) {
      let f = frames.get(t.id);
      if (!f) {
        f = document.createElement('iframe'); f.dataset.tab = t.id; host.appendChild(f); frames.set(t.id, f);
        f.addEventListener('load', () => { let title = ''; try { title = f.contentDocument.title; } catch { /* cross-origin */ } emit('browser', { tabId: t.id, url: f.dataset.url, title, loading: false, progress: 100, canBack: false, canFwd: false }); });
      }
      if (t.url && f.dataset.url !== t.url) { f.dataset.url = t.url; f.src = devUrl(t.url); }
      f.style.display = st.visible && t.id === st.active ? 'block' : 'none';
    }
    if (st.cmd === 'reload' && frames.get(st.active)) frames.get(st.active).src = frames.get(st.active).src;
    return true;
  },
  dshWeb(st) {
    const vp = document.querySelector('#scr-harness .dshweb .vp');
    if (!vp) return true;
    let f = vp.querySelector('iframe');
    if (st.visible && !f) { f = document.createElement('iframe'); f.src = '/__dev/dshweb/'; vp.appendChild(f); }
    if (f) f.style.display = st.visible ? 'block' : 'none';
    return true;
  },
};
function devUrl(u) {
  if (u.startsWith(FILE_BASE)) return u;
  if (/^https?:\/\/(127\.0\.0\.1|localhost):3080\//.test(u)) return '/__dev/dshweb/' + u.replace(/^https?:\/\/[^/]+\//, '');
  return u;
}

/* ------------------------------------------------------------------ public API */
export const isAndroid = !!A;
/** dsh 产物文件的本地代理地址（原生拦截该主机名并用 cookie 去 dsh /api/file 取文件，去掉 CSP sandbox） */
export const FILE_BASE = A ? 'https://dshfile.pd.local' : `${location.origin}/__dev/dshfile`;
export const fileUrl = (abs) => FILE_BASE + (abs.startsWith('/') ? abs : '/' + abs).split('/').map(encodeURIComponent).join('/');
export const fileFromUrl = (u) => {
  if (!u || !u.startsWith(FILE_BASE + '/')) return null;
  try { return decodeURIComponent(u.slice(FILE_BASE.length)); } catch { return null; }
};

export const native = {
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
  voice: () => call('voice'),
  browser: (state) => call('browser', state).catch(() => {}),
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
