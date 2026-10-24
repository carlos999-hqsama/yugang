// 内置 Harness 运行时（一体化 APK）：原生前台服务解包并启动 dsh，自动登录。
// 页面只关心：正在启动 / 首次准备进度 / 已就绪 / 出错（一个「重试」）。诊断信息在 设置 › 高级。
import { native, on } from './native.js';
import { dsh, startDsh, pauseDsh, onDsh } from './dsh.js';

export const rt = { state: 'idle', progress: 0, message: '', firstRun: false, embedded: true, keySet: true, loaded: false, configError: '', configLoading: false };
const CONFIG_DEADLINE_MS = 8000;
const STATUS_DEADLINE_MS = 6000;
const subs = new Set();
export const onRt = (fn) => { subs.add(fn); return () => subs.delete(fn); };
const notify = () => subs.forEach((f) => f(rt));

/** 桥接调用必须有界：没有回调时也要让启动流程结束并显示可重试的错误。 */
function withDeadline(request, ms, message) {
  const pending = Promise.resolve(request);
  if (typeof setTimeout !== 'function') return pending;
  let timer = null;
  return new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error(message)), ms);
    pending.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
  });
}

function configErrorText(e) {
  return String((e && e.message) || e || '读取配置失败')
    .replace(/([?&]token=)[^&\s]+/gi, '$1…')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, 'sk-…');
}

let lastState = '';
function apply(v) {
  if (!v) return;
  Object.assign(rt, { state: v.state || rt.state, progress: v.progress ?? rt.progress, message: v.message || '', firstRun: !!v.firstRun });
  // 运行时刚就绪（或重启后再次就绪）：原生侧已经换好了登录 Cookie，直接连
  if (rt.embedded && rt.state !== 'ready' && lastState === 'ready') pauseDsh();
  if (rt.state === 'ready' && lastState !== 'ready') startDsh();
  lastState = rt.state;
  notify();
}

let listenersReady = false;
let statusRead = false;
export async function initRt() {
  if (!listenersReady) {
    listenersReady = true;
    on('runtime', apply);
    on('config', () => { refreshCfg().catch(() => {}); });
    // dsh 登录失效（例如运行时被系统杀掉后重启，旧 Cookie 失效）：配置读取成功的内置模式下自动重启换新 token。
    // 配置未知时不做自动重启，避免把“读不到配置”误当成“没有 Key”并陷入重启/重复写。
    let lastAuto = 0;
    onDsh((w) => {
      if (w !== 'state' || !rt.loaded || !rt.keySet || !rt.embedded || dsh.state !== 'unauthorized' || rt.state !== 'ready') return;
      if (Date.now() - lastAuto <= 60000) return;
      lastAuto = Date.now();
      rt.state = 'starting'; rt.message = '正在启动 Harness…'; notify();
      native.rtRestart().catch((e) => { rt.state = 'error'; rt.message = e.message; notify(); });
    });
  }
  await refreshCfg();
  if (!statusRead) {
    statusRead = true;
    if (rt.embedded) {
      try { apply(await withDeadline(native.rtStatus(), STATUS_DEADLINE_MS, '读取运行状态超时，请重试。')); }
      catch (e) {
        // 状态桥接超时/失败：保留已知配置，显式报错并提供手动重试，而不是无限空白。
        if (!rt.loaded) return;
        if (rt.state === 'idle') { rt.state = 'error'; rt.message = configErrorText(e); notify(); }
      }
    }
  }
}

let cfgSeq = 0;
export async function refreshCfg() {
  const seq = ++cfgSeq;
  rt.configLoading = true;
  try {
    const c = await withDeadline(native.getConfig(), CONFIG_DEADLINE_MS, '读取配置超时，请重试。');
    if (seq !== cfgSeq) return c;
    // 只有真正读到的配置才更新；失败时保留上一次已知的 Key/模式状态，绝不按空配置覆盖。
    rt.embedded = c.dshMode !== 'external';
    rt.keySet = !!c.deepseekKeySet;
    rt.loaded = true;
    rt.configError = '';
    return c;
  } catch (e) {
    if (seq === cfgSeq) rt.configError = configErrorText(e);
    throw e;
  } finally {
    if (seq === cfgSeq) { rt.configLoading = false; notify(); }
  }
}

export async function saveKey(key) {
  await native.setConfig({ deepseekKey: key.trim() });
  rt.keySet = !!key.trim(); rt.state = 'starting'; rt.message = '正在启动 Harness…'; notify();
}

export async function retryRt() {
  rt.state = 'starting'; rt.message = '正在启动 Harness…'; notify();
  try {
    // 启动时配置没读到时，先在有界时间内补读配置，再重启运行时。
    if (!rt.loaded) await initRt();
    return await native.rtRestart();
  } catch (e) {
    rt.state = 'error'; rt.message = configErrorText(e); notify();
    throw e;
  }
}

/** 给用户看的一句话（不出现 DSHBox / 配对 / 端口等术语） */
export function rtLine() {
  if (!rt.loaded && rt.configError) return '读取配置超时，请重试';
  if (rt.state === 'stopped') return 'Harness 已停止';
  if (rt.state === 'installing') return rt.firstRun ? '首次使用，正在准备 Harness…' : '正在更新 Harness…';
  if (rt.state === 'error' || rt.state === 'missing') return rt.message || 'Harness 没能启动';
  return '正在启动 Harness…';
}
