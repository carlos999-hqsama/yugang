// dsh web 协议客户端（默认连 App 内置运行时 127.0.0.1:3090 的 dsh；高级选项可连外部 DSHBox 3080）。
// 一元调用：POST /api/<endpoint>  {type:'client-request', rpcId, method, payload:{args}}
// 流：WebSocket /api/remote.mux，逻辑流 open/item/end/cancel；$events 负责通知与需要客户端作答的 waterfall（审批等）。
// 传输全部经原生层（带 Cookie、无 Origin 头，满足 dsh 的 Host/Origin 防护）。
import { native, ws as openWs } from './native.js';
import { uuid } from './util.js';

export const dsh = {
  state: 'idle', // idle | unpaired | connecting | online | offline | unauthorized
  error: '',
  home: '',
  clientId: null,
  sessions: new Map(), // sessionId -> row（session/list 项，含 projections.values）
  workspaces: [], // [{workspaceId,path,title,sessionIds}]
  pinned: [],
  archived: new Set(),
  running: new Set(),
  approvals: new Map(), // eventId -> {eventId, agentId, request}
  questions: new Map(),
  catalog: null,
  presets: null,
  subs: new Set(),
};
const notify = (what) => dsh.subs.forEach((f) => f(what));
export const onDsh = (fn) => { dsh.subs.add(fn); return () => dsh.subs.delete(fn); };

export class DshError extends Error { constructor(e, status) { super(e?.message || String(e)); this.code = e?.code; this.details = e?.details; this.status = status; } }

export async function call(endpoint, args = {}) {
  const body = { type: 'client-request', rpcId: uuid(), method: endpoint, payload: { args } };
  const r = await native.http('dsh', 'POST', '/api/' + endpoint, body, { timeoutMs: 60000 });
  if (r.status === 401 || r.status === 403) { setUnauthorized(); throw new DshError({ code: 'unauthorized', message: 'Harness 登录已失效，正在重新连接' }, r.status); }
  let j; try { j = JSON.parse(r.text); } catch { throw new DshError({ code: 'bad-response', message: `dsh 返回 ${r.status}` }, r.status); }
  const res = j.result || j;
  if (res.ok === false) throw new DshError(res.error, r.status);
  return res.value;
}

/* ------------------------------------------------------------ mux */
let startEpoch = 0;
let sock = null, sockOpen = false, retry = 0, retryTimer = null, stopped = true;
const streams = new Map(); // streamId -> {endpoint,args,onItem,onEnd,onError,persistent}
let connectionEpoch = 0, liveStatusSeq = 0;
const liveStatus = new Map(); // sessionId -> 本连接内最后一次 api-session/status 的序号
function resetLiveStatus() { connectionEpoch++; liveStatus.clear(); }

function sendFrame(f) { if (sock && sockOpen) sock.send(JSON.stringify(f)); }
export function openStream(endpoint, args, { onItem, onEnd, onError, global: isGlobal = false } = {}) {
  const h = { id: uuid(), cancel() { if (!streams.has(h.id)) return; streams.delete(h.id); sendFrame({ type: 'cancel', streamId: h.id }); } };
  streams.set(h.id, { endpoint, args, onItem, onEnd, onError, global: isGlobal, h });
  sendFrame({ type: 'open', streamId: h.id, endpoint, payload: { args } });
  return h;
}
function onFrame(text) {
  let f; try { f = JSON.parse(text); } catch { return; }
  const s = streams.get(f.streamId);
  if (!s) return;
  if (f.type === 'item') s.onItem && s.onItem(f.value);
  else if (f.type === 'error') { streams.delete(f.streamId); s.onError && s.onError(f.error); }
  else if (f.type === 'end') { streams.delete(f.streamId); s.onEnd && s.onEnd(); }
}
function connectSocket() {
  clearTimeout(retryTimer);
  if (stopped) return;
  resetLiveStatus();
  dsh.state = dsh.state === 'online' ? 'online' : 'connecting'; notify('state');
  const mySock = openWs('dsh', '/api/remote.mux', {
    onOpen: () => {
      if (mySock !== sock) { mySock.close(); return; }
      sockOpen = true; retry = 0;
      // 重连：重新打开所有逻辑流（会话 follow 会重新收到 snapshot）
      const old = [...streams.values()]; streams.clear();
      openGlobalStreams();
      for (const st of old) {
        if (st.global) continue;
        const id = uuid(); st.h.id = id; streams.set(id, st);
        sendFrame({ type: 'open', streamId: id, endpoint: st.endpoint, payload: { args: st.args } });
      }
      refreshSessions().then(() => { if (mySock !== sock || stopped) return; dsh.state = 'online'; dsh.error = ''; notify('state'); });
    },
    onMessage: (t) => { if (mySock === sock) onFrame(t); },
    onClose: (code, reason) => {
      if (mySock !== sock) return;
      resetLiveStatus();
      sockOpen = false; sock = null;
      detachQuestionEvents();
      if (code === 401 || code === 403 || code === 4401) { setUnauthorized(); return; }
      if (stopped) return;
      dsh.state = 'offline'; dsh.error = reason || '暂时连不上 Harness'; notify('state');
      const delay = Math.min(15000, 1000 * 2 ** retry++) * (0.8 + Math.random() * 0.4);
      retryTimer = setTimeout(connectSocket, delay);
    },
  });
  sock = mySock;
}
function setUnauthorized() { dsh.state = 'unauthorized'; dsh.error = 'dsh 登录已失效或未配对'; stopped = true; if (sock) sock.close(); notify('state'); }

function openGlobalStreams() {
  openStream('$events', {}, { onItem: onEventItem, global: true });
  openStream('workspace/follow', {}, { onItem: onWorkspaceItem, global: true });
  openStream('session/control', {}, { onItem: onControlItem, global: true });
}

/* ------------------------------------------------------------ $events */
function answer(eventId, outcome) {
  return call('$events/result', { clientId: dsh.clientId, eventId, outcome }).catch((e) => console.warn('$events/result', e));
}
function onEventItem(v) {
  if (!v) return;
  if (v.type === 'ready') { dsh.clientId = v.clientId; dsh.home = v.host?.home || ''; notify('ready'); return; }
  if (v.type === 'emit') {
    const [a0, a1] = v.args || [];
    if (v.event === 'api-session/status' && typeof a0 === 'string') { liveStatus.set(a0, ++liveStatusSeq); a1 ? dsh.running.add(a0) : dsh.running.delete(a0); notify('running'); }
    else if (v.event === 'api-session/added' && a0 && a0.sessionId) { dsh.sessions.set(a0.sessionId, a0); notify('sessions'); }
    else if (v.event === 'api-session/activity' && typeof a0 === 'string') { const r = dsh.sessions.get(a0); if (r) { r.updatedAt = a1 || Date.now(); notify('sessions'); } else refreshSessionsSoon(); }
    else if (/^api-session\//.test(v.event)) refreshSessionsSoon();
    notify({ emit: v.event, args: v.args });
    return;
  }
  if (v.type === 'waterfall') {
    if (v.event === 'approval/request') { dsh.approvals.set(v.eventId, { eventId: v.eventId, agentId: v.agentId, request: v.request || {} }); notify('approvals'); return; }
    if (v.event === 'user-questions/request' && receiveQuestion(v)) return;
    // 其它需要客户端处理的 waterfall：本应用不处理，交给下一个监听者
    answer(v.eventId, { kind: 'next' });
    return;
  }
  if (v.type === 'cancel') {
    if (dsh.approvals.delete(v.eventId)) notify('approvals');
    for (const q of dsh.questions.values()) if (q.eventId === v.eventId) {
      releaseQuestionEvent(q);
      if (!q.projected) q.state = 'closed';
      q.canAnswer = q.state === 'continued'; notify('questions');
    }
  }
}
export async function decideApproval(eventId, allow) {
  const a = dsh.approvals.get(eventId); if (!a) return;
  dsh.approvals.delete(eventId); notify('approvals');
  await answer(eventId, { kind: 'result', value: allow ? 'allowed-once' : 'rejected' });
}

/* Live questions answer the waterfall; only continued questions use the late-reply RPC. */
const questionKey = (agentId, callId) => JSON.stringify([agentId, callId]);
export const questionFor = (agentId, callId) => dsh.questions.get(questionKey(agentId, callId)) || null;
export const questionsFor = (agentId) => [...dsh.questions.values()].filter((q) => q.agentId === agentId);
export function matchQuestionCall(agentId, callId, questions) {
  const exact = questionFor(agentId, callId); if (exact) return exact;
  // Legacy tool requests omit wait.callId. Bind the live card to its matching transcript call.
  const signature = (qs) => JSON.stringify((qs || []).map((q) => [q.id, q.question, (q.options || []).map(o => [o.label, o.description || '']), !!(q.multiSelect ?? q.multi_select)]));
  const q = questionsFor(agentId).find((r) => r.unbound && r.eventId && r.state === 'open' && signature(r.questions) === signature(questions));
  if (!q) return null;
  dsh.questions.delete(questionKey(agentId, q.callId)); q.callId = callId; q.unbound = false;
  dsh.questions.set(questionKey(agentId, callId), q); return q;
}
function releaseQuestionEvent(q) {
  clearTimeout(q.timer); q.timer = null;
  q.claim?.cancel(); q.claim = null; q.eventId = null; q.clientId = null;
}
function detachQuestionEvents() {
  for (const q of dsh.questions.values()) {
    releaseQuestionEvent(q);
    q.canAnswer = q.state === 'continued';
  }
  notify('questions');
}
function syncQuestions(agentId, view) {
  if (!view || !Array.isArray(view.active) || !Array.isArray(view.settled)) return;
  const present = new Set();
  for (const p of view.active) {
    const key = questionKey(agentId, p.callId); present.add(key);
    const q = dsh.questions.get(key) || { agentId, callId: p.callId };
    q.questions = p.questions; q.projected = true;
    if (q.state !== 'submitted') q.state = p.state;
    q.canAnswer = q.state === 'continued' || (q.state === 'open' && !!q.eventId);
    dsh.questions.set(key, q);
  }
  for (const p of view.settled) {
    const key = questionKey(agentId, p.callId); present.add(key);
    const q = dsh.questions.get(key) || { agentId, callId: p.callId, questions: [] };
    releaseQuestionEvent(q);
    Object.assign(q, { state: 'settled', answers: p.answers, canAnswer: false, projected: true });
    dsh.questions.set(key, q);
  }
  for (const [key, q] of dsh.questions) if (q.agentId === agentId && q.projected && !present.has(key)) {
    releaseQuestionEvent(q); q.state = 'closed'; q.canAnswer = false;
  }
  notify('questions');
}
function receiveQuestion(v) {
  const callId = v.request?.wait?.callId || `event:${v.eventId}`;
  if (!v.agentId || !Array.isArray(v.request?.questions)) return false;
  const key = questionKey(v.agentId, callId);
  const q = questionFor(v.agentId, callId) || { agentId: v.agentId, callId };
  releaseQuestionEvent(q);
  Object.assign(q, { eventId: v.eventId, clientId: dsh.clientId, questions: v.request.questions,
    unbound: !v.request.wait?.callId, state: q.state === 'submitted' ? 'submitted' : 'open' });
  q.canAnswer = q.state === 'open'; dsh.questions.set(key, q);
  if (v.request.wait?.timed) {
    // The host suspends its deadline while a client claims this stream. Honour its remaining duration locally.
    q.claim = openStream('userQuestions/attachWait', { agentId: q.agentId, callId }, {
      global: true,
      onItem: (frame) => {
        if (q.eventId !== v.eventId || !Number.isFinite(frame.remainingMs)) return;
        q.deadline = Date.now() + frame.remainingMs;
        q.timer = setTimeout(() => expireQuestion(q, v.eventId), Math.max(0, frame.remainingMs));
      },
      onError: () => { if (q.eventId === v.eventId) { releaseQuestionEvent(q); q.canAnswer = false; notify('questions'); refreshSessionsSoon(); } },
    });
  }
  notify('questions'); return true;
}
function expireQuestion(q, eventId) {
  if (q.eventId !== eventId || q.submitting) return;
  const clientId = q.clientId;
  releaseQuestionEvent(q); q.canAnswer = false;
  call('$events/result', { clientId, eventId, outcome: { kind: 'rejected', error: {
    name: 'UserQuestionError', code: 'ASK_TIMED_OUT', message: '等待回答超时，仍可稍后回复',
  } } }).catch(() => {}).finally(refreshSessionsSoon);
  notify('questions');
}
async function answerQuestion(agentId, callId, answers) {
  const q = questionFor(agentId, callId);
  if (!q?.canAnswer || q.submitting) throw new Error('问题状态已变化，请等待同步后重试');
  q.submitting = true;
  try {
    if (q.eventId) {
      clearTimeout(q.timer); q.timer = null;
      await call('$events/result', { clientId: q.clientId, eventId: q.eventId,
        outcome: { kind: 'result', value: { answers } } });
    } else {
      const accepted = await call('userQuestions/answer', { agentId, callId, answer: { answers } });
      if (accepted !== true) throw new Error('这次回答未被接收，问题可能已结束');
    }
    releaseQuestionEvent(q);
    if (q.state !== 'settled' && q.state !== 'closed') q.state = 'submitted';
    q.canAnswer = false; notify('questions'); refreshSessionsSoon(); return true;
  } catch (e) {
    // A failed send retains the card and its draft. The host remains authoritative after reconnect.
    if (q.eventId && Number.isFinite(q.deadline)) q.timer = setTimeout(() => expireQuestion(q, q.eventId), Math.max(0, q.deadline - Date.now()));
    q.canAnswer = q.state === 'continued' || (q.state === 'open' && !!q.eventId);
    refreshSessionsSoon();
    throw e;
  } finally { q.submitting = false; notify('questions'); }
}

/* ------------------------------------------------------------ 工作区 / 会话列表 */
function onWorkspaceItem(raw) {
  if (!raw) return;
  // 帧形如 {type:'baseline', value:{items,pinnedSessionIds,archivedSessionIds}} 或 {type:'upsert', workspace}
  const v = raw.value && typeof raw.value === 'object' && !Array.isArray(raw.value) ? { ...raw.value, type: raw.type } : raw;
  const t = v.type;
  if (Array.isArray(v.items) && (t === undefined || t === 'baseline' || t === 'replace' || t === 'snapshot')) {
    dsh.workspaces = v.items;
    if (v.pinnedSessionIds) dsh.pinned = v.pinnedSessionIds;
    if (v.archivedSessionIds) dsh.archived = new Set(v.archivedSessionIds);
  } else if (t === 'upsert' && (v.workspace || v.item)) {
    const w = v.workspace || v.item;
    const i = dsh.workspaces.findIndex((x) => x.workspaceId === w.workspaceId);
    i >= 0 ? (dsh.workspaces[i] = w) : dsh.workspaces.push(w);
  } else if (t === 'remove') {
    const id = v.workspaceId || (typeof raw.value === 'string' ? raw.value : null);
    dsh.workspaces = dsh.workspaces.filter((w) => w.workspaceId !== id);
  } else if (t === 'order' && Array.isArray(v.workspaceIds)) {
    const m = new Map(dsh.workspaces.map((w) => [w.workspaceId, w]));
    dsh.workspaces = v.workspaceIds.map((id) => m.get(id)).filter(Boolean);
  }
  if (v.pinnedSessionIds) dsh.pinned = v.pinnedSessionIds;
  if (v.archivedSessionIds) dsh.archived = new Set(v.archivedSessionIds);
  notify('workspaces');
}
function onControlItem(v) {
  if (!v || v.type !== 'projection') return;
  const row = dsh.sessions.get(v.sessionId);
  if (row) { row.projections = row.projections || { values: {} }; row.projections.values = { ...(row.projections.values || {}), [v.key]: v.value }; }
  if (v.key === 'userQuestions') syncQuestions(v.sessionId, v.value);
  notify({ projection: v.sessionId, key: v.key });
}
let sessionListSeq = 0;
export async function refreshSessions() {
  const seq = ++sessionListSeq, epoch = connectionEpoch, statusAtStart = liveStatusSeq;
  try {
    const r = await call('session/list', { _request: {} });
    // 只让当前连接里最后一次 list 落地；更早的响应既不能替换 Map，也不能改 running。
    if (seq !== sessionListSeq || epoch !== connectionEpoch) return;
    const items = r.items || [];
    dsh.sessions = new Map(items.map((s) => [s.sessionId, s]));
    for (const s of items) syncQuestions(s.sessionId, s.projections?.values?.userQuestions);
    for (const s of items) {
      // 请求发出后收到的实时状态更新，不接受这份旧快照再改回去。
      if ((liveStatus.get(s.sessionId) || 0) > statusAtStart) continue;
      s.running ? dsh.running.add(s.sessionId) : dsh.running.delete(s.sessionId);
    }
    notify('sessions');
  } catch (e) { if (e.code !== 'unauthorized') console.warn('session/list', e); }
}
let rsT = null;
function refreshSessionsSoon() { clearTimeout(rsT); rsT = setTimeout(refreshSessions, 300); }

export async function loadCatalogs() {
  try { dsh.catalog = await call('session/modelCatalog', {}); } catch { /* 旧版本 dsh 可能没有 */ }
  try { dsh.presets = await call('permissionPresets/catalog', {}); } catch { /* ignore */ }
  notify('catalog');
}

/* ------------------------------------------------------------ 生命周期 */
export async function startDsh() {
  stopDsh();
  const epoch = startEpoch;
  const cfg = await native.getConfig();
  if (epoch !== startEpoch) return;
  if (cfg.dshMode !== "external") {
    const status = await native.rtStatus();
    if (epoch !== startEpoch) return;
    if (status.state !== "ready") { dsh.state = "connecting"; notify("state"); return; }
  }
  if (cfg.harnessMode === 'web') { dsh.state = 'idle'; notify('state'); return; }
  if (!cfg.dshPaired) { dsh.state = 'unpaired'; dsh.error = ''; notify('state'); return; }
  stopped = false; retry = 0; dsh.state = 'connecting'; notify('state');
  connectSocket();
  loadCatalogs();
}
export function stopDsh() { startEpoch++; resetLiveStatus(); stopped = true; clearTimeout(retryTimer); detachQuestionEvents(); if (sock) { const s = sock; sock = null; sockOpen = false; s.close(); } streams.clear(); }
export function reconnectNow() { if (!stopped && !sockOpen) { retry = 0; connectSocket(); } }

export function pauseDsh() { stopDsh(); dsh.state = "connecting"; notify("state"); }

/* ------------------------------------------------------------ 会话操作 */
export const api = {
  attachment: (sessionId, attachmentId) => call('session/attachment', { request: { sessionId, attachmentId } }),
  searchSessions: (query) => call('session/search', { request: { query } }),
  createWorkspace: (path = '/BOT/default') => call('workspace/create', { request: { path } }),
  rename: (sessionId, title) => call('session/rename', { request: { sessionId, title } }),
  preview: (sessionId) => call('session/page', { request: { address: { kind: 'session', sessionId }, turnWindow: { minMessages: 1, minTurns: 1 } } }),
  createSession: (req) => call('session/create', { request: req }),
  prompt: (sessionId, content, mode = 'queue', requestId = uuid()) => call('session/prompt', { request: { requestId, sessionId, mode, content, clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone } }),
  cancel: (sessionId) => call('session/cancel', { request: { sessionId } }),
  selectModel: (sessionId, provider, model, reasoningEffort) => call('session/selectModel', { request: { sessionId, provider, model, reasoningEffort } }),
  command: (sessionId, line) => call('commands/execute', { agentId: sessionId, line, submittedAttachments: [] }),
  answerQuestion,
  pin: (sessionId) => call('workspace/pinSession', { request: { sessionId } }),
  unpin: (sessionId) => call('workspace/unpinSession', { request: { sessionId } }),
  archive: async (sessionId, stopActivity = false) => {
    const r = await call('workspace/archiveSession', { request: { sessionId, stopActivity } });
    dsh.archived = new Set(r.archivedSessionIds); notify('workspaces'); return r;
  },
  unarchive: async (sessionId) => {
    const r = await call('workspace/unarchiveSession', { request: { sessionId } });
    dsh.archived = new Set(r.archivedSessionIds); notify('workspaces'); return r;
  },
  page: (sessionId, throughSeq, beforeSeq) => call('session/page', { request: { address: { kind: 'session', sessionId }, throughSeq, beforeSeq, turnWindow: { minMessages: 30, minTurns: 2 } } }),
};
/** 关注一个会话：先收到 snapshot，再收到增量事件和 assistant-stream 帧。 */
export function follow(sessionId, onItem) {
  const h = openStream('session/follow', { request: { address: { kind: 'session', sessionId }, assistantStream: true, turnWindow: { minMessages: 50, minTurns: 2 } } }, { onItem, onError: (e) => onItem({ type: '$error', error: e }), onEnd: () => onItem({ type: '$end' }) });
  return h;
}
