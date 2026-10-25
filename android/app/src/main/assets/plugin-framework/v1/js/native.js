// Native bridge. Secrets and account operations stay behind PDNative.
import { uuid } from './util.js';

const bridge = window.PDNative || null;
const pending = new Map();
const listeners = new Map();
let sequence = 0;

function readPreviewContext() {
  try { return bridge?.isPreview?.() === true; }
  catch { return false; }
}

export const isPreview = readPreviewContext();

window.__pd_cb = (id, json) => {
  const request = pending.get(id);
  if (!request) return;
  pending.delete(id);
  const value = typeof json === 'string' ? JSON.parse(json) : json;
  if (value && value.error) request.reject(Object.assign(new Error(value.error), value));
  else request.resolve(value ? value.value : undefined);
};

window.__pd_ev = (channel, json) => {
  const value = typeof json === 'string' ? JSON.parse(json) : json;
  for (const listener of listeners.get(channel) || []) {
    try { listener(value); } catch (error) { console.error(error); }
  }
};

export function on(channel, fn) {
  if (!listeners.has(channel)) listeners.set(channel, new Set());
  listeners.get(channel).add(fn);
  return () => listeners.get(channel).delete(fn);
}

function call(method, args = {}) {
  if (!bridge) return Promise.reject(new Error('PDNative 协议不可用'));
  return new Promise((resolve, reject) => {
    const id = `c${++sequence}`;
    pending.set(id, {resolve, reject});
    bridge.call(method, id, JSON.stringify(args));
  });
}

export const isAndroid = !!bridge;
export const FILE_BASE = bridge ? 'https://dshfile.pd.local' : 'https://dshfile.pd.local';
export const fileUrl = absolute => FILE_BASE + (String(absolute || '/').startsWith('/') ? String(absolute || '/') : `/${absolute}`).split('/').map(encodeURIComponent).join('/');
export const fileFromUrl = url => (() => { try { return typeof url === 'string' && url.startsWith(FILE_BASE + '/') ? decodeURIComponent(url.slice(FILE_BASE.length)) : null; } catch { return null; } })();

function requestError(result, code=result.code || 'http_error', message=result.error || `请求失败：HTTP ${result.status}`) {
  return Object.assign(new Error(message), {code,status:result.status || 0,finalUrl:result.finalUrl || '',timeout:result.timeout === true,cancelled:result.cancelled === true,retryable:result.retryable === true});
}

function isExpectedPreviewFailure(error) {
  return error?.reason === 'preview' && typeof error?.code === 'string';
}

function previewBrowserResult(error) {
  return {opened:false, closed:false, reason:'preview', code:error.code};
}

export const native = {
  isPreview,
  call,
  getConfig: () => call('getConfig'),
  setConfig: patch => call('setConfig', patch),
  http: (target, method, path, body, options = {}) => call('http', {
    target, method, path,
    body: body == null ? null : typeof body === 'string' ? body : JSON.stringify(body),
    timeoutMs: options.timeoutMs || 20000,
  }),
  copy: text => call('copy', {text}),
  toast: text => call('toast', {text}).catch(() => {}),
  openExternal: url => call('openExternal', {url}),
  openDshbox: () => call('openDshbox'),
  dshPair: input => call('dshPair', {input}),
  dshUnpair: () => call('dshUnpair'),
  rtStatus: () => call('rtStatus'),
  rtDiag: () => call('rtDiag'),
  rtStart: () => call('rtStart'),
  rtRestart: () => call('rtRestart'),
  backgroundSettings: which => call('backgroundSettings', {which}),
  bots: (args = {op: 'list'}) => call('bots', args),
  codexFile: args => call('codexFile', args),
  previewFile: args => call('previewFile', args),
  openFile: args => call('openFile', args),
  documents: (action, args = {}) => call('documents', {action, ...args}),
  miniTools: (action, args = {}) => call('miniTools', {action, ...args}),
  quota: (args = {}) => call('quota', args),
  voice: request => subscribeVoice(request),
  browser: request => {
    const site = request && typeof request.url === 'string';
    return call(site ? 'siteBrowserOpen' : 'browser', request).catch(error => {
      if (isExpectedPreviewFailure(error)) return previewBrowserResult(error);
      // Keep the legacy fire-and-forget entry point, but never hide real site browser errors.
      if (!site) return undefined;
      throw error;
    });
  },
  dshWeb: state => call('dshWeb', state).catch(() => {}),
  ready: () => call('ready').catch(() => {}),
  model: (request = {op:'status'}) => call('pluginModel', request),
  translate: (text, options = {}) => call('pluginModel', {...options,op:'translate',text}),
  background: (request = {op:'status'}) => call('pluginBackground', request),
  notify: request => call('pluginNotify', request),
  apiSession: (request = {op:'status'}) => call('pluginApiSession', request),
  tts: (request = {op:'status'}) => {
    const value = {...request};
    if ((value.op || 'status') === 'speak' && !value.id) value.id = uuid();
    const promise = call('pluginTts', value);
    if (value.id) promise.id = value.id;
    return promise;
  },
  /**
   * 通用外网请求：由宿主代发，不携带任何宿主凭据。
   * HTTPS fetch 和图片可直接使用；需要跨域或 HTTP 时由此通道代发。
   * 返回 {status, ok, contentType, bytes, truncated, text | base64, headers, finalUrl}。
   */
  fetch: (url, options = {}) => {
    const requestId = uuid();
    const request = {
      action: 'fetch',
      requestId,
      url: String(url || ''),
      method: String(options.method || 'GET').toUpperCase(),
      timeoutMs: options.timeoutMs || 15000,
      responseType: options.responseType || 'auto',
    };
    // Only send what the caller really set: an explicit null must not become a body.
    if (options.credentials !== undefined) request.credentials = options.credentials;
    if (options.headers) request.headers = options.headers;
    if (options.body != null) request.body = String(options.body);
    if (options.bodyBase64) request.bodyBase64 = options.bodyBase64;
    if (options.contentType) request.contentType = options.contentType;
    if (options.maxBytes) request.maxBytes = options.maxBytes;
    const promise = call('plugins', request);
    promise.cancel = () => call('plugins', {action: 'fetchCancel', requestId}).catch(() => {});
    return promise;
  },
  fetchJson: async (url, options = {}) => {
    const result = await native.fetch(url, options);
    if (!result.ok) throw requestError(result);
    try { return JSON.parse(result.text ?? atob(result.base64 || '')); }
    catch { throw requestError(result, 'invalid_response', '响应不是有效 JSON'); }
  },
  /** 远程图片：取回后转成 data URL，可直接放进 img.src。 */
  fetchImage: async (url, options = {}) => {
    const result = await native.fetch(url, {...options, responseType: 'base64'});
    if (!result.ok) throw requestError(result);
    const mediaType = result.contentType || 'application/octet-stream';
    if (!mediaType.startsWith('image/')) throw requestError(result, 'invalid_response', '响应不是图片');
    return `data:${mediaType};base64,${result.base64}`;
  },
  /**
   * Host-side streaming fetch. The native bridge uses bounded start/poll/cancel batches;
   * chunks are delivered in order and a slow consumer receives an explicit backpressure error.
   */
  fetchStream: (url, options = {}) => {
    const requestId = uuid();
    let closed = false;
    let firstChunkSent = false;
    let startConfirmed = false;
    const request = {
      requestId,
      url: String(url || ''),
      method: String(options.method || 'GET').toUpperCase(),
      timeoutMs: options.timeoutMs || 15000,
    };
    if (options.credentials !== undefined) request.credentials = options.credentials;
    if (options.headers) request.headers = options.headers;
    if (options.body != null) request.body = String(options.body);
    if (options.bodyBase64) request.bodyBase64 = options.bodyBase64;
    if (options.contentType) request.contentType = options.contentType;
    if (options.maxBytes) request.maxBytes = options.maxBytes;
    const fail = (event, fallbackMessage = '流式请求失败') => {
      if (closed) return;
      closed = true;
      options.onError?.(Object.assign(
        new Error(event?.error || fallbackMessage),
        {
          code: event?.code || 'network_error',
          status: event?.status || 0,
          contentType: event?.contentType || '',
          bytesSoFar: event?.bytesSoFar || 0,
          firstChunkMs: event?.firstChunkMs ?? null,
          finalUrl: event?.finalUrl || '',
          timeout: event?.timeout === true,
          cancelled: event?.cancelled === true,
          retryable: event?.retryable === true,
          reason: event?.reason,
          expected: event?.expected === true,
        },
      ));
    };
    const dispatch = events => {
      for (const event of events || []) {
        if (closed) return;
        if (event.kind === 'chunk') {
          if (event.first && !firstChunkSent) {
            firstChunkSent = true;
            try { options.onFirstChunk?.(event); } catch (error) { console.error(error); }
          }
          try { options.onChunk?.(event); } catch (error) { console.error(error); }
        } else if (event.kind === 'done') {
          closed = true;
          try { options.onDone?.(event); } catch (error) { console.error(error); }
          return;
        } else if (event.kind === 'error') {
          fail(event);
          return;
        }
      }
    };
    const poll = async () => {
      while (!closed) {
        let result;
        try { result = await call('fetchStreamPoll', {requestId}); }
        catch (error) { fail(error); return; }
        dispatch(result.events);
        if (closed) return;
        if (result.done) {
          closed = true;
          return;
        }
        await new Promise(resolve => setTimeout(resolve, result.events?.length ? 0 : 10));
      }
    };
    call('fetchStreamStart', request)
      .then(result => {
        startConfirmed = true;
        if (closed) {
          call('fetchStreamCancel', {requestId}).catch(() => {});
          return;
        }
        poll();
      })
      .catch(error => fail(error));
    return {
      cancel() {
        if (closed) return;
        closed = true;
        call('fetchStreamCancel', {requestId}).catch(() => {});
      },
    };
  },
};

export function sse(target, path, {onEvent, onState}) {
  if (!bridge) return {close() {}};
  const id = uuid();
  const off = on(`sse:${id}`, event => {
    if (event.kind === 'event') onEvent?.(event.event, event.data);
    else {
      if (event.kind === 'error') off();
      onState?.(event.kind, event.error, event);
    }
  });
  bridge.call('sseOpen', id, JSON.stringify({target, path}));
  return {
    close() {
      off();
      bridge.call('sseClose', id, '{}');
    },
  };
}

export function ws(target, path, {onOpen, onMessage, onClose}) {
  if (!bridge) {
    queueMicrotask(() => onClose?.(0, 'PDNative 协议不可用'));
    return {send() {}, close() {}};
  }
  const id = uuid();
  const off = on(`ws:${id}`, event => {
    if (event.kind === 'open') onOpen?.();
    else if (event.kind === 'message') onMessage?.(event.data);
    else if (event.kind === 'close') {
      off();
      onClose?.(event.code, event.reason, event);
    }
  });
  bridge.call('wsOpen', id, JSON.stringify({target, path}));
  return {
    send: text => bridge.call('wsSend', id, JSON.stringify({text})),
    close: () => bridge.call('wsClose', id, '{}'),
  };
}


// Only a current visible page may subscribe. Native confirmation binds one Bot/session.
function subscribeVoice(request={}) {
  if(request.op!=='subscribe')return call('pluginVoice',request);
  const requestId=crypto.randomUUID();
  let cancelled=false,timer=null,subscription=null;
  const stop=()=>{if(cancelled)return;cancelled=true;clearTimeout(timer);document.removeEventListener('visibilitychange',visibility);window.removeEventListener('pd-plugin-hidden',stop);window.removeEventListener('pagehide',stop);call('pluginVoice',{op:'unsubscribe',id:requestId}).catch(()=>{});};
  const visibility=()=>{if(document.visibilityState==='hidden')stop();};
  const poll=async()=>{
    if(cancelled)return;
    try {
      const r=await call('pluginVoice',{op:'poll',id:subscription.id});
      if(cancelled)return;
      for(const e of r.events||[]) {
        if(e.type==='token')request.onToken?.(e);
        else if(e.type==='sentence')request.onSentence?.(e);
        else if(e.type==='end')request.onEnd?.(e);
        else if(e.type==='error'){request.onError?.(e);stop();return;}
      }
      timer=setTimeout(poll,80);
    } catch(error){if(!cancelled)request.onError?.({error:error.message});stop();}
  };
  document.addEventListener('visibilitychange',visibility);window.addEventListener('pd-plugin-hidden',stop);window.addEventListener('pagehide',stop);
  const ready=call('pluginVoice',{op:'subscribe',requestId}).then(r=>{subscription=r;if(!cancelled)poll();return r;},error=>{const wasCancelled=cancelled;stop();if(!wasCancelled)request.onError?.({error:error.message});throw error;});
  return {ready,cancel:stop};
}
