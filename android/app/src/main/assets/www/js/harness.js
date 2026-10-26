import {voiceScope,voiceFrame,voiceInterrupted} from './agent-voice.js';
// Harness 页：原生界面对接 App 内置运行时里的 dsh（或高级选项里的外部 dsh）：会话侧栏 + 对话 + 内置浏览器预览，
// 备用模式为内嵌 dsh web（明确标注）。
import { S } from './icons.js';
import { esc, linkText, md as renderMarkdown, uuid, basename, fmtBytes, debounce, homeAbbr } from './util.js';
import { dsh, onDsh, api, follow, decideApproval, startDsh, reconnectNow } from './dsh.js';
import { toolQuestionHtml, extraQuestionHtml, captureQuestionCards, restoreQuestionCards, isQuestionTextarea, questionInput, questionClick, hasOpenQuestion } from './questions.js';
import { previewArtifact, Conv, category, resolvePath } from './conv.js';
import { native, on, fileUrl, fileFromUrl } from './native.js';
import { menu, toast, promptSheet, sheet, closeSheet } from './ui.js';
import { pair } from './settings.js';
import { rt, onRt, rtLine, retryRt } from './rt.js';
import { inlineImageSrc, inlineImageSrcFrom, isInlineImageSrc, userImagesHtml } from './user-images.js';

const LS = (k, d) => { try { const v = localStorage.getItem('pd.h.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } };
const SV = (k, v) => localStorage.setItem('pd.h.' + k, JSON.stringify(v));
const md = text => renderMarkdown(text, {image: raw => {
  if (/^[a-z][a-z0-9+.-]*:/i.test(raw) || raw.startsWith('//')) return null;
  const path = resolvePath(raw, H.conv?.header?.cwd);
  if (!/^\/BOT\/[^/]+\/.+\.(?:png|jpe?g|webp|gif)$/i.test(path || '') || path.split('/').some(part => part === '..') || /[\\\x00-\x1f]/.test(path)) return null;
  return {path, url:fileUrl(path)};
}, code: () => `<button type="button" class="code-copy" aria-label="复制代码原文" title="复制原文">${S.copy}</button>`});
const EFFORT = { off: '关', low: '低', medium: '中', high: '高', max: '极高', xhigh: '极高', minimal: '最低' };


import { createChatScroll } from './chat-scroll.js';
import {botName,botListHtml,ownerOfSession,sessionsForBot} from './bot-list.js';
import {inspectorSnapshot,inspectorHtml} from './bot-inspector.js';
import {openBotSettingsSheet,promptBotCreation} from './bot-settings.js';


const H = {
  sel: LS('sel', null), mobileChat: !!LS('sel', null), conv: null, fh: null,
  filter: '', searching: false, noside: LS('noside', false), expand: false,

  drafts: {}, pending: [], openTools: new Set(), attach: [], attachmentDrafts: new Map(), imageReading: new Map(), pickKey: null, creating: false, sessionCreating: new Set(),
  draftModels: new Map(), sizes: {}, mode: 'native', cfg: {},
  disclosures: new Map(), historyMore: false, historyBusy: false, historyEpoch: 0, navigationEpoch: 0,
  stoppingSid: '', approvalViews: new Map(),
  bots: null, botId: LS('botId','default'), botError: '',
  readings: new Map(), inspector: LS('inspector', false),
};
let el, cover = true, pageVisible = true, chatScroll = null, mountReady = null;
export const PLUGIN_DEVELOPER_BOT_ID = '00000000-0000-4000-8000-000000000001';
const PLUGIN_EXAMPLE_REQUEST = '创建一个能联网更新的 AI 新闻插件，并实际预览、测试后安装。';

/* Durable user images are fetched only through the session-authorized API and cached by session. */
const attachmentSessions = new Map();
function clearAttachmentSession(sid) {
  const session = attachmentSessions.get(sid);
  if (!session) return;
  session.cancelled = true;
  session.items.clear();
  attachmentSessions.delete(sid);
}
function readDurableAttachment(sid, image, force = false) {
  if (!sid) return { status: 'loading' };
  let session = attachmentSessions.get(sid);
  if (!session) {
    session = { cancelled: false, items: new Map() };
    attachmentSessions.set(sid, session);
  }
  let entry = session.items.get(image.attachmentId);
  if (entry?.status === 'loading') return entry;
  if (entry && !force && (!entry.optimistic || entry.refreshing)) return entry;
  const fallback = entry?.src || '';
  entry = { status: fallback ? 'loaded' : 'loading', error: '', src: fallback, refreshing: true };
  session.items.set(image.attachmentId, entry);
  (async () => {
    const result = await api.attachment(sid, image.attachmentId);
    const attachment = result?.attachment || {};
    if (attachment.attachmentId !== image.attachmentId) throw Error('附件返回身份不匹配');
    const src = inlineImageSrcFrom(attachment.mediaType, result?.data);
    if (!src) throw Error('附件返回的图片数据无效');
    return src;
  })().then(src => {
    if (session.cancelled) return;
    entry.status = 'loaded'; entry.src = src; entry.error = ''; entry.refreshing = false;
    if (H.sel === sid) renderMsgsSoon();
  }, error => {
    if (session.cancelled) return;
    entry.status = fallback ? 'loaded' : 'error'; entry.refreshing = false;
    entry.error = String(error?.message || error || '请重试').slice(0, 200);
    if (H.sel === sid) renderMsgsSoon();
  });
  return entry;
}

/* ============================================================ 骨架 */
export async function mount(root) {
  el = root;
  pageVisible = true;
  chatScroll = createChatScroll({ threshold: 60, isVisible: () => pageVisible && !!el && el.classList.contains('on') && document.visibilityState !== 'hidden' });
  if (H.sel) chatScroll.enter(H.sel);
  root.addEventListener('click', onClick);
  root.addEventListener('error', event => {
    const image = event.target;
    if (String(image?.tagName || '').toUpperCase() !== 'IMG' || !image.closest('.chat-image')) return;
    const holder = image.closest('.chat-image');
    if (holder?.dataset?.attachmentId) holder.dataset.status = 'error';
    image.hidden = true;const parent=image.parentElement || image.parentNode;const label=parent?.querySelector('.chat-image-failure');if(label)label.hidden=false;
  }, true);
  root.addEventListener('input', onInput);
  root.addEventListener('keydown', onKey);
  root.addEventListener('contextmenu', onCtx);
  root.addEventListener('toggle', (e) => {
    if (e.target.matches('details[data-disclosure]')) H.disclosures.set(e.target.dataset.disclosure, e.target.open);
  }, true);
  onDsh(onDshChange);
  on('shareDraft', shared => {
    if (!H.bots?.bots.some(bot => bot.id === shared.botId)) return;
    selectBot(shared.botId);
    const key = draftKey();
    H.drafts[key] = [H.drafts[key] || '', shared.text || ''].filter(Boolean).join('\n');
    const files = H.attachmentDrafts.get(key) || [];
    for (const file of shared.files || []) {
      if (files.length < 6) files.push({type:'file',...file});
      else native.removeAttachment(file.handle).catch(()=>{});
    }
    H.attachmentDrafts.set(key,files);H.attach=files;
    const input=el.querySelector('.input textarea');if(input)input.value=H.drafts[key];
    renderMsgsSoon();updateInputRow();
  });
  on('attachmentProgress', progress => {
    const pending = H.pending.find(p => p.uploadIds?.includes(progress.requestId));
    if (pending) { pending.uploadText = `正在上传 ${fmtBytes(progress.bytes)} / ${fmtBytes(progress.total)}`; renderMsgsSoon(); }
  });
  onRt(() => { if (H.mode === 'web' || !el?.querySelector('.list')) return; renderBanner(); renderMsgsSoon(); updateInputRow(); });
  on('paired', async () => { H.cfg = await native.getConfig(); RT.at = 0; renderMsgs(); renderBanner(); });
  document.getElementById('filepick').addEventListener('change', onPick);
  window.__pd_harnessMode = () => refreshMode();
  new ResizeObserver(() => syncOverlays()).observe(root);
  mountReady = refreshMode();
  await mountReady;
}
async function refreshMode() {
  H.cfg = await native.getConfig();
  const nextMode = 'native';
  if (H.mode !== nextMode && H.fh) { H.fh.cancel(); H.fh = null; }
  H.mode = nextMode;
  try { await botCall({op:'list'}); H.botError=''; if(!H.bots.bots.some(b=>b.id===H.botId))H.botId=H.bots.defaultBotId; if(H.sel)H.botId=ownerOfSession(H.bots,H.sel,dsh.sessions.get(H.sel)); }
  catch(e){H.botError=e.message;}
  build();
}
export function onLayout() {
  cover = document.body.classList.contains('cover');
  if (!el) return;
  // 折叠只调整布局，保留输入、光标、阅读位置和原生预览对应的 DOM。
  el.classList.toggle('mobile-chat', H.mobileChat);

  el.classList.add('nobrowser');
  applyWidths();
  renderHead();
  const ta = el.querySelector('.input textarea'); if (ta) autosize(ta);
  syncOverlays();
  chatScroll?.refresh();

}
export function onShow() {
  pageVisible = true;
  chatScroll?.refresh();
  renderSide();
  syncOverlays();
}
export function onHide() {
  pageVisible = false;
  chatScroll?.remember();
  syncOverlays();
}

function build() {
  if (!el) return;
  cover = document.body.classList.contains('cover');
  el.classList.toggle('noside', !!H.noside);
  el.classList.toggle('mobile-chat', H.mobileChat);

  if (H.mode === 'web') {
    chatScroll?.attach(null);
    el.classList.add('nobrowser');
    el.innerHTML = `<div class="dshweb"><div class="lbl">${S.warn}<span class="tx">备用模式：dsh 自带网页界面（非本应用原生 UI）</span><button type="button" class="btn" data-act="toNative">切回原生界面</button></div><div class="vp"></div></div>`;
    syncOverlays();
    return;
  }
  const previousMsgs = el.querySelector('.msgs');
  chatScroll?.remember();
  const cards = previousMsgs ? captureQuestionCards(previousMsgs) : null;
  const ta = el.querySelector('.input textarea');
  const focus = ta && document.activeElement === ta ? [ta.selectionStart, ta.selectionEnd, ta.selectionDirection] : null;
  const keepText = ta ? ta.value : (H.drafts[draftKey()] || '');
  el.classList.add('nobrowser');
  el.innerHTML = `
<div class="side"><div class="sidehead"><b>鱼缸</b><span><button type="button" class="clickable" data-act="search" aria-label="搜索 Bot">${S.search}</button><button type="button" class="clickable" data-act="newBot" aria-label="添加 Bot">${S.plus}</button></span></div>
<input class="sfilter${H.searching ? '' : ' hide'}" placeholder="搜索 Bot" aria-label="搜索 Bot 名称" maxlength="500" value="${esc(H.filter)}">
<div class="list"></div></div>
<div class="dv a" data-drag="side"></div>
<div class="chat"><div class="chead"></div><div class="bn"></div><div class="msgs"></div><div class="apvw"></div>
<div class="input"><textarea rows="1" placeholder="给对话运行时发消息"></textarea><div class="atts"></div><div class="row"></div></div></div>
<aside class="bot-detail" aria-label="当前 Bot 的任务详情"></aside>
`;
  const nta = el.querySelector('.input textarea'); nta.value = keepText; autosize(nta);
  applyWidths();
  const nextMsgs = el.querySelector('.msgs');
  chatScroll?.attach(nextMsgs);
  renderSide(); renderHead(); renderBanner();
  // build 已替换滚动容器；先渲染内容再让 end() 恢复锚点，避免 begin()
  // 把空容器的顶部误判成用户滚动并覆盖旧锚点。
  renderMsgs(false, { beginScroll: false }); renderApprovals(); updateInputRow();
  if (cards) restoreQuestionCards(nextMsgs, cards);
  if (focus) { nta.focus({ preventScroll: true }); nta.setSelectionRange(...focus); }
  if (H.sel && (!H.conv || H.conv.sid !== H.sel || (!H.fh && dsh.state === 'online'))) select(H.sel, { keep: true });
}

/* ============================================================ dsh 状态变化 */
function onDshChange(w) {
  if (H.mode === 'web' || !el || !el.querySelector('.list')) return;
  if (w === 'state' && dsh.state !== 'online' && H.fh) { H.fh.cancel(); H.fh = null; }
  if (w === 'state' && H.searching && H.filter.trim()) scheduleSearch();
  if ((w === 'sessions' || w === 'workspaces') && H.sel && dsh.sessions.has(H.sel) && dsh.archived.has(H.sel)) {
    const sid=activeSessionForBot(H.botId);
    if(sid!==H.sel)select(sid,{keep:true,botId:H.botId});
  }
  if (w === 'workspaces') { renderBanner(); renderHead(); updateInputRow(); }
  if (w === 'state') { renderBanner(); renderMsgsSoon(); if (dsh.state === 'online' && H.sel && !H.fh) select(H.sel, { keep: true }); }
  if (w === 'state' || w === 'sessions' || w === 'workspaces' || w === 'running' || w === 'approvals' || w?.key === 'title') renderSideSoon();
  if (w === 'running' || w === 'sessions' || (w && w.projection === H.sel) || w === 'catalog') { renderHead(); renderBanner(); updateInputRow(); }
  if (w === 'running' || w === 'questions') renderMsgsSoon();
  if (w === 'questions') { renderSideSoon(); renderHead(); }
  if (w === 'approvals') renderApprovals();
  if (w && w.projection === H.sel && w.key === 'inbox') renderMsgsSoon();
}
const renderSideSoon = debounce(() => renderSide(), 60);
let msgRaf = 0; function renderMsgsSoon() { if (!msgRaf) msgRaf = requestAnimationFrame(() => { msgRaf = 0; renderMsgs(); }); }

function renderBanner() {
  const b = el.querySelector('.bn'); if (!b) return;
  const st = dsh.state;
  // 运行时不可用且未选会话：外屏收起侧栏与输入框，把空间留给状态卡片
  let html = '';
  const pluginGuide = pluginGuideHtml();
  if (rt.embedded) {
    // 一体化：没有配对/外部应用的概念；只在已选中会话且运行时未就绪时给一条简短状态（出错时一个「重试」）
    // 外屏：未选会话且还没就绪（或还没填 Key）时收起侧栏和输入框，把整屏留给启动状态 / Key 输入
    el.classList.toggle('rtoff', !H.sel && (st !== 'online' || !rt.keySet));
    if (!rt.keySet) {
      b.innerHTML = H.sel ? pluginGuide + notice({ icon: S.gear, title: '先配置 DeepSeek API Key', body: '在模型页面配置后即可开始对话。', acts: [['modelsettings', '配置模型', true]] }) : pluginGuide;
      return;
    }
    if (H.sel && st !== 'online') html = rtBad() ? notice({ icon: S.warn, title: rtLine(), body: '', acts: [['rtRetry', '重试', true]] }) : notice({ icon: '<i class="spin"></i>', title: rtLine(), body: '', acts: [], tone: 'info' });
    b.innerHTML = pluginGuide + html; return;
  }
  el.classList.toggle('rtoff', !H.sel && (st === 'unpaired' || st === 'unauthorized' || st === 'offline'));
  // 未配对 / 未连接且没选中会话时，由对话区中央的“运行时状态”卡片承担引导，这里不再重复
  if (st === 'unauthorized' && H.sel) html = notice({ icon: S.warn, title: 'dsh 登录已失效', body: 'Cookie 已过期或 DSH_HOME 被重置，需要重新配对。', acts: [['openDshbox', '打开 DSHBox', true], ['paste', '粘贴启动链接']] });
  else if (st === 'offline' && H.sel) html = notice({ icon: S.warn, title: '连不上 dsh', body: `${esc(H.cfg.dshUrl || 'http://127.0.0.1:3080')} 无响应，请确认 DSHBox 正在运行。`, acts: [['openDshbox', '打开 DSHBox', true], ['retry', '重试']] });
  else if (st === 'unpaired' && H.sel) html = notice({ icon: S.link, title: '尚未连接对话运行时', body: '与 DSHBox 配对后才能继续这个会话。', acts: [['openDshbox', '打开 DSHBox', true], ['paste', '粘贴启动链接']] });
  b.innerHTML = pluginGuide + html;
}

function pluginGuideHtml() {
  if (H.botId !== PLUGIN_DEVELOPER_BOT_ID) return '';
  return `<div class="ntc info plugin-guide"><div class="ntc-h">${S.box}<div class="ntc-t"><b>试试做一个新的插件，例如 AI 新闻</b><p>确认需求后发送。</p></div></div><div class="acts stk"><button type="button" class="btn dk" data-act="pluginExample">AI 新闻</button></div></div>`;
}

/** 通用提示条：图标 + 标题/正文（全宽、可换行）+ 下方按钮行（窄容器自动纵向堆叠） */
function notice({ icon, title, body, acts = [], tone = 'warn' }) {
  return `<div class="ntc ${tone}"><div class="ntc-h">${icon || ''}<div class="ntc-t"><b>${title}</b>${body ? `<p>${body}</p>` : ''}</div></div>${acts.length ? `<div class="acts stk">${acts.map(([a, label, primary]) => `<button type="button" class="btn${primary ? ' dk' : ''}" data-act="${a}">${label}</button>`).join('')}</div>` : ''}</div>`;
}

/* 一体化：首次使用 / 启动中 / 出错 的内联状态（替代原来的 DSHBox 配对面板） */
const rtBad = () => rt.state === 'error' || rt.state === 'missing';
function rtInline() {
  const st = dsh.state;
  let html = '';
  if (st !== 'online') {
    if (rtBad()) html += `<div class="rtx bad"><p>${esc(rtLine())}</p><div class="acts stk"><button type="button" class="btn dk" data-act="rtRetry">重试</button></div></div>`;
    else {
      const pct = rt.state === 'installing' ? Math.round((rt.progress || 0) * 100) : null;
      html += `<div class="rtx"><p><i class="spin"></i>${esc(rtLine())}</p>${pct != null ? `<div class="bar"><i style="width:${pct}%"></i></div><small>${pct}% · 只在首次安装或更新后需要，之后秒开</small>` : ''}</div>`;
    }
  }
  if (!rt.keySet) html += keyCard();
  return html;
}
function keyCard() {
  return `<div class="rtx key"><b>配置 DeepSeek 开始对话</b><p>在设置中配置宿主 DeepSeek Key。</p><button type="button" class="btn dk" data-act="modelsettings">配置模型</button></div>`;
}

/* （外部 dsh，设置 › 高级）运行时状态卡片（替代原来挤在一行里的“尚未与 DSHBox 配对”横幅） */
const RT = { probing: false, reachable: null, at: 0 };
function probeRuntime() {
  if (RT.probing || Date.now() - RT.at < 5000) return;
  RT.probing = true;
  native.http('dsh', 'GET', '/', null, { timeoutMs: 3000 }).then(() => { RT.reachable = true; }, () => { RT.reachable = false; })
    .finally(() => { RT.probing = false; RT.at = Date.now(); renderMsgs(); });
}
function runtimeCard() {
  const st = dsh.state, cfg = H.cfg || {};
  if (st !== 'online' && st !== 'connecting') probeRuntime();
  const row = (ok, label, detail) => `<li class="${ok === true ? 'ok' : ok === false ? 'bad' : 'unk'}"><i></i><span><b>${label}</b>${detail ? `<small>${detail}</small>` : ''}</span></li>`;
  const installed = cfg.dshboxInstalled; const reach = st === 'online' ? true : RT.reachable;
  const paired = st === 'unauthorized' ? false : !!cfg.dshPaired;
  const title = st === 'unauthorized' ? 'dsh 登录已失效' : st === 'offline' && paired ? '对话运行时未运行' : '连接对话运行时';
  const body = st === 'unauthorized' ? '需要重新配对：在 DSHBox 里点「用外部浏览器打开」并选择本应用。'
    : !paired ? '对话运行时使用手机上 DSHBox 运行的 dsh。打开 DSHBox 启动 dsh，然后点「用外部浏览器打开」并选择「鱼缸」完成配对；也可以把启动链接粘贴进来。'
    : '已配对，但 dsh 当前没有响应。请在 DSHBox 里启动 dsh，然后重试。';
  const acts = [['openDshbox', '打开 DSHBox', true]];
  if (!paired) acts.push(['paste', '粘贴启动链接']); else acts.push(['retry', '重试连接']);
  acts.push(['settings', '设置']);
  return `<div class="rtc"><div class="rtc-h">${S.link}<b>${title}</b></div><p>${body}</p>
<ul class="rts">${row(installed === null || installed === undefined ? null : installed, 'DSHBox 应用', installed === false ? '未检测到 com.dshbox.app' : installed ? '已安装' : '')}${row(reach, 'dsh 服务', `${esc(cfg.dshUrl || 'http://127.0.0.1:3080')}${reach === null ? ' · 检测中…' : reach ? ' · 有响应' : ' · 无响应'}`)}${row(paired, '配对', paired ? '已配对' : st === 'unauthorized' ? '登录已失效' : '未配对')}</ul>
<div class="acts stk">${acts.map(([a, l, p]) => `<button type="button" class="btn${p ? ' dk' : ''}" data-act="${a}">${l}</button>`).join('')}</div></div>`;
}

/* ============================================================ 侧栏 */
const draftKey = () => H.sel || `bot:${H.botId}:new`;
const currentBot = () => H.bots?.bots.find(b=>b.id===H.botId);
let botQueue=Promise.resolve();
function botCall(input){
  const call=botQueue.then(()=>native.bots(input)).then(registry=>{H.bots=registry;return registry;});
  botQueue=call.catch(()=>{});return call;
}
function activeSessionForBot(id){
  const b=H.bots?.bots.find(b=>b.id===id);
  if(!b)return null;
  const rows=sessionsForBot(H.bots,id,dsh.sessions).filter(s=>!dsh.archived.has(s.sessionId));
  return rows.find(s=>s.sessionId===b.selectedSessionId)?.sessionId||rows[0]?.sessionId||null;
}
function selectBot(id){
  if(!H.bots?.bots.some(b=>b.id===id))return;
  select(activeSessionForBot(id),{botId:id});
}
export async function openPluginOnboarding() {
  try { await mountReady; }
  catch { toast('插件 Bot 尚未就绪，请稍后重试'); return false; }
  if (!pageVisible) return false;
  if (!H.bots?.bots.some((b) => b.id === PLUGIN_DEVELOPER_BOT_ID)) {
    toast('插件 Bot 尚未就绪，请稍后重试');
    return false;
  }
  selectBot(PLUGIN_DEVELOPER_BOT_ID);
  return true;
}
function fillPluginExample() {
  if (H.botId !== PLUGIN_DEVELOPER_BOT_ID) return false;
  const ta = el.querySelector('.input textarea');
  const existing = (ta?.value ?? H.drafts[draftKey()] ?? '').trim();
  if (existing) {
    toast('当前已有草稿，可直接编辑后发送；示例未覆盖。');
    ta?.focus({ preventScroll: true });
    return false;
  }
  if (!ta) return false;
  H.drafts[draftKey()] = PLUGIN_EXAMPLE_REQUEST;
  ta.value = PLUGIN_EXAMPLE_REQUEST;
  ta.setSelectionRange(PLUGIN_EXAMPLE_REQUEST.length, PLUGIN_EXAMPLE_REQUEST.length);
  autosize(ta);
  updateInputRow();
  ta.focus({ preventScroll: true });
  return true;
}
const approvalWaiting = (sid) => !!sid && [...dsh.approvals.values()].some((a) => a.agentId === sid);
const isWaiting = (sid) => approvalWaiting(sid) || hasOpenQuestion(sid);
function scheduleSearch(){renderSide();}
function renderSide(){
  const list=el?.querySelector('.side .list');if(!list)return;
  if(H.botError){list.innerHTML=`<div class="bot-empty">机器人列表未能读取，请重试。<button type="button" class="btn" data-act="retryBots">重试</button></div>`;return;}
  list.innerHTML=botListHtml({registry:H.bots,selected:H.botId,query:H.filter.trim(),sessions:dsh.sessions,running:dsh.running,waiting:isWaiting});
}

/* ============================================================ 选择 / 关注会话 */
function select(sid, { keep = false, botId = null } = {}) {
  if (H.sel && H.sel !== sid) clearAttachmentSession(H.sel);
  if (!keep) { H.mobileChat = true; el.classList.toggle('mobile-chat', true); }
  if (!keep && (!botId||botId===H.botId) && sid === H.sel && H.fh) { chatScroll?.refresh(); return; }
  if(H.sel&&H.sel!==sid){chatScroll?.remember();H.readings.set(H.sel,{...chatScroll.state});}
  const ta = el.querySelector('.input textarea'); if (ta) H.drafts[draftKey()] = ta.value;
  H.attachmentDrafts.set(draftKey(), H.attach);
  if (H.fh) { H.fh.cancel(); H.fh = null; }
  H.navigationEpoch++; H.historyEpoch++;
  H.botId = botId || (sid ? ownerOfSession(H.bots,sid,dsh.sessions.get(sid)) : H.botId) || 'default'; SV('botId',H.botId);
  H.sel = sid; SV('sel', sid);
  voiceScope(H.botId,sid);
  if(sid&&H.bots)void botCall({op:'select',id:H.botId,sessionId:sid}).catch(e=>toast('会话选择未保存：'+e.message));
  if (!keep){const saved=H.readings.get(sid);if(saved)chatScroll?.resume(sid,saved);else chatScroll?.enter(sid||'');}
  H.attach = H.attachmentDrafts.get(draftKey()) || [];
  H.attachmentDrafts.set(draftKey(), H.attach);
  H.conv = sid ? (keep && H.conv?.sid === sid ? H.conv : new Conv(sid)) : null;
  H.openTools.clear(); H.historyMore = false; H.historyBusy = false;
  if (ta) { const value = H.drafts[draftKey()] || ''; if (ta.value !== value) ta.value = value; autosize(ta); }
  if (sid && (dsh.state === 'online' || dsh.state === 'connecting')) {
    const conv = H.conv;
    H.fh = follow(sid, (v) => {
      if (H.conv !== conv) return;
      if (v.type === 'snapshot') { conv.error = ''; conv.snapshot(v); H.historyEpoch++; H.historyMore = !!v.hasMore; H.historyBusy = false; for (const e of conv.events.values()) if (e.type === 'user/message') dropPending(e, conv.sid); renderHead(); renderMsgs(); renderApprovals(); return; }
      if (v.type === 'event') { conv.add(v.event); if (v.event.type === 'session/title' || v.event.type === 'model/selection' || v.event.type === 'permission/preset') renderHead(); if (v.event.type === 'user/message') dropPending(v.event, conv.sid); if (v.event.type === 'tool/call') renderApprovals(); }
      else if (v.type === 'assistant-stream') {voiceFrame(H.botId,sid,v.frame);conv.frame(v.frame);}
      else if (v.type === '$error') { voiceInterrupted(H.botId,sid); conv.error = v.error?.message || '会话读取失败'; H.fh = null; }
      else if (v.type === '$end') { voiceInterrupted(H.botId,sid); H.fh = null; conv.error ||= '对话更新已中断，请重新连接'; }
      renderMsgsSoon();
    });
  }
  renderSide(); renderHead(); renderBanner(); renderMsgs(); renderApprovals(); updateInputRow();
}
async function loadOlder() {
  const conv = H.conv, epoch = H.historyEpoch;
  if (!conv?.loaded || !H.historyMore || H.historyBusy || dsh.state !== 'online') return;
  H.historyBusy = true; renderMsgs();
  let added = false;
  try {
    const page = await api.page(conv.sid, conv.cursor, conv.oldest);
    if (H.conv !== conv || H.historyEpoch !== epoch) return;
    conv.prepend(page.records); H.historyMore = !!page.hasMore; added = true;
  } catch (e) { if (H.conv === conv && H.historyEpoch === epoch) toast('加载历史失败：' + e.message); }
  finally {
    if (H.conv === conv && H.historyEpoch === epoch) {
      H.historyBusy = false; renderMsgs(false, { prepend: added });
    }
  }
}
function dropPending(ev, sid) {
  const rpc = ev.data?.source?.rpcId;
  if (!rpc) return;
  H.pending = H.pending.filter(p => {
    if (p.sid !== sid || p.rpcId !== rpc) return true;
    // The receipt can arrive before the normalized bytes. Keep the already-visible thumbnail
    // while the session-authorized read runs, instead of replacing it with a filename/loading gap.
    const originals = p.content.filter(b => b.type === 'image');
    const saved = (ev.data?.content || []).filter(b => b.type === 'image' && b.attachment);
    if (saved.length && originals.length === saved.length) {
      let session = attachmentSessions.get(sid);
      if (!session) { session = {cancelled:false,items:new Map()};attachmentSessions.set(sid,session); }
      saved.forEach((block,index) => {
        const id = block.attachment.attachmentId, src = inlineImageSrc(originals[index]);
        if (src && !session.items.has(id)) session.items.set(id,{status:'loaded',src,error:'',optimistic:true});
      });
    }
    p.echoed = true;
    return !!p.sending || !!p.error;
  });
}

/* ============================================================ 头部 */
function curModel() {
  const s = dsh.sessions.get(H.sel); const ms = s?.projections?.values?.modelSelection;
  const m = (H.sel ? ms?.next || ms?.lastUsed : null) || H.draftModels.get(draftKey()) || dsh.catalog?.default;
  return m || null;
}
function modelLabel(m, short) {
  if (!m) return short ? '模型' : '选择模型';
  const id = m.model || ''; const e = EFFORT[m.reasoningEffort] || m.reasoningEffort || '';
  return `${short ? id.replace(/^deepseek-/, '') : id}${e ? ' ' + e : ''}`;
}
function renderHead() {
  const h = el.querySelector('.chead'); if (!h) return;
  const running = H.sel && dsh.running.has(H.sel);
  const qWaiting = H.sel && hasOpenQuestion(H.sel);
  const aWaiting = H.sel && approvalWaiting(H.sel);
  const waiting = qWaiting || aWaiting;
  const waitLabel = qWaiting && aWaiting ? '等待回答与批准' : qWaiting ? '等待回答' : '等待批准';
  const title = botName(currentBot());
  const reopen = `<button type="button" class="cbtn reopen" data-act="side" aria-label="展开或收起 Bot 列表">${S.side}</button>`;
  if (cover) {
    const sub = running ? '<span class="spin" style="width:9px;height:9px"></span>运行中' : waiting ? `${qWaiting ? S.q : S.clock}${waitLabel}` : '当前聊天';
    h.innerHTML = `${reopen}<div class="t"><div>${esc(title)}</div><small>${sub}</small></div><button type="button" class="btn" data-act="settings">${S.gear}设置</button><button type="button" class="cbtn" data-act="more" aria-label="更多操作">${S.more}</button>`;
  } else {
    h.innerHTML = `${reopen}<div class="t"><div>${esc(title)}</div> <small>${running ? '运行中' : waiting ? waitLabel : '当前聊天'}</small></div><button type="button" class="btn" data-act="settings">${S.gear}设置</button><button type="button" class="clickable" data-act="more" style="color:#888;display:flex" aria-label="更多操作">${S.more}</button>`;
  }
  h.innerHTML+=`<button type="button" class="cbtn bot-detail-toggle" data-act="inspector" aria-label="查看任务详情" aria-expanded="${H.inspector}">${S.list}</button>`;
  renderInspector();
}

function renderInspector(){
  if(!el)return;
  el.classList.toggle('bot-detail-open',H.inspector);
  const pane=el.querySelector('.bot-detail');if(!pane||!H.inspector)return;
  const title=botName(currentBot()),key=JSON.stringify([H.botId,H.sel,H.conv?.version,dsh.running.has(H.sel)]);
  if(pane.dataset.key===key)return;
  const top=pane.dataset.sid===H.sel?pane.querySelector('.bot-detail-scroll')?.scrollTop||0:0;
  pane.innerHTML=inspectorHtml(inspectorSnapshot(H.conv,dsh.running.has(H.sel)),title);
  pane.dataset.key=key;pane.dataset.sid=H.sel||'';
  pane.querySelector('.bot-detail-scroll').scrollTop=top;
}

/* ============================================================ 对话 */
function lineFor(g) {
  const ts = g.tools; const n = ts.length; const a0 = ts[0].args || {};
  const rel = (p) => { const r = resolvePath(p, ts[0].cwd); const c = (ts[0].cwd || '').replace(/\/+$/, '') + '/'; return r && r.startsWith(c) ? r.slice(c.length) : homeAbbr(p, dsh.home); };
  let icon = S.box, body = '';
  switch (g.cat) {
    case 'history-search': icon = S.search; body = `<b>已检索历史对话</b> ${esc(ts.map(t => t.args.query || '').join('、'))}`; break;
    case 'history-read': icon = S.hchat; body = `<b>已查看原始对话</b>${n > 1 ? ` · ${n} 处记录` : ''}`; break;
    case 'read': icon = S.file; body = n === 1 ? `<b>已读取</b> ${esc(rel(a0.file_path || a0.path || a0.pattern || ''))}` : `<b>已读取 ${n} 个文件</b>`; break;
    case 'readbash': { const r = ts.filter((t) => category(t) === 'read').length; icon = S.file; body = `<b>已读取 ${r} 个文件</b>，运行了 ${n - r} 条命令`; break; }
    case 'bash': icon = S.term; body = n === 1 ? `<b>运行了命令</b> ${esc(a0.command || a0.cmd || '')}` : `<b>运行了 ${n} 条命令</b>`; break;
    case 'search': icon = S.search; body = `<b>已搜索</b> ${esc([...new Set(ts.map((t) => t.args.pattern || t.args.query || t.args.q || ''))].filter(Boolean).join('、'))}`; break;
    case 'edit': {
      icon = S.edit; let plus = 0, minus = 0; const files = [];
      for (const t of ts) { const a = t.args || {}; const f = a.file_path || a.path; if (f && !files.includes(rel(f))) files.push(rel(f)); const lc = (x) => (x ? String(x).split('\n').length : 0); if (t.name === 'write') plus += lc(a.content); else { plus += lc(a.new_string); minus += lc(a.old_string); for (const e of a.edits || []) { plus += lc(e.new_string); minus += lc(e.old_string); } } }
      body = `<b>已编辑</b> ${esc(files.join('、'))} <span style="color:#2f9e5b">+${plus}</span> <span style="color:#d4493f">−${minus}</span>`; break;
    }
    case 'image': icon = S.img; body = `<b>已查看 ${n} 张图像</b>`; break;
    case 'web': icon = S.web; body = `<b>已浏览</b> ${esc(ts.map((t) => t.args.url || '').join('、'))}`; break;
    case 'todo': { icon = S.list; const todos = ts[n - 1].args.todos || []; body = `<b>更新了待办</b> ${todos.filter((t) => t.status === 'completed').length}/${todos.length}`; break; }
    case 'agent': icon = S.agent; body = `<b>子代理</b> ${esc(a0.description || a0.prompt?.slice(0, 60) || '')}`; break;
    default: body = `<b>调用了</b> ${esc([...new Set(ts.map((t) => t.name))].join('、'))}`;
  }
  const pend = ts.some((t) => t.pending); const err = ts.some((t) => t.result?.isError);
  const asked = ts.some((t) => t.approval?.state === 'asked');
  const running = H.sel && dsh.running.has(H.sel);
  const tail = asked ? S.clock : pend && running ? '<span class="spin" style="width:11px;height:11px;flex:none"></span>' : `<span class="rt">${S.right}</span>`;
  const open = H.openTools.has(g.key);
  let html = `<div class="tool${err ? ' err' : ''}${open ? ' open' : ''}" role="button" tabindex="0" aria-expanded="${open}" data-tk="${esc(g.key)}" data-scroll-key="${esc('tool:' + g.key)}">${icon}<span class="tx">${body}</span> ${tail}</div>`;
  if (g.cat === 'image') html += `<div class="thumbs">${ts.map((t) => { const p = t.name === 'phone_plugins_preview' ? previewArtifact(t.result?.text) : resolvePath(t.args.file_path || t.args.path || '', t.cwd); return p ? `<img src="${esc(fileUrl(expandHome(p)))}" data-art="${esc(expandHome(p))}" loading="lazy" alt="">` : ''; }).join('')}</div>`;
  if (open) html += `<div class="tool-steps">${ts.map(detailText).join('')}</div>`;
  return html;
}
const expandHome = (p) => (p && p.startsWith('~/') && dsh.home ? dsh.home + p.slice(1) : p);
function detailText(t) {
  const a = t.args || {}; let head;
  if (a.command) head = `$ ${a.command}`;
  else if (a.file_path) head = `${t.name} ${a.file_path}`;
  else head = `${t.name} ${JSON.stringify(a).slice(0, 400)}`;
  const r = t.result ? (t.result.text || '').slice(0, 2000) + ((t.result.text || '').length > 2000 ? '\n…' : '') : (t.approval?.state === 'asked' ? '（等待批准）' : '（运行中）');
  const key = 'result:' + t.id;
  const expanded = H.disclosures.get(key) ?? !!t.result?.isError;
  return `<div class="tool-step"><div class="step-title">${esc(t.args?.description || t.name)}</div><div class="step-command">${esc(head)}</div><details class="result-fold" data-disclosure="${esc(key)}"${expanded ? ' open' : ''}><summary>${t.result?.isError ? '执行失败 · 查看详情' : t.result ? '查看执行结果' : '等待执行结果'}</summary><pre>${esc(r)}</pre></details></div>`;
}
function artHtml(it) {
  const p = expandHome(it.path); const name = basename(p);
  if (H.sizes[p] === undefined) { H.sizes[p] = null; native.http('dsh', 'HEAD', '/api/file?path=' + encodeURIComponent(p)).then((r) => { const n = +(r.headers?.['content-length'] || 0); H.sizes[p] = n || 0; renderMsgsSoon(); }).catch(() => {}); }
  const size = H.sizes[p] ? ' · ' + fmtBytes(H.sizes[p]) : '';
  const hint = '点击打开';
  const video = /\.(mp4|webm|mov|m4v)$/i.test(name);
  return `<div class="art" data-scroll-key="${esc('art:' + p)}"><button type="button" class="art-open" data-art="${esc(p)}"><span class="ic">${video ? '▶' : S.file}</span><span class="art-info"><b>${video ? '视频' : '产物'} ${esc(name)}</b><small>${hint}${size}${it.desc ? ' · ' + esc(it.desc) : ''}</small></span></button><button type="button" class="btn art-save" data-art-save="${esc(p)}" aria-label="保存 ${esc(name)}">保存</button></div>`;
}
function renderMsgs(forceBottom = false, { prepend = false, beginScroll = true } = {}) {
  const m = el?.querySelector('.msgs'); if (!m) return;
  if (forceBottom) chatScroll?.enter(H.sel || '');
  if (beginScroll) chatScroll?.begin(H.sel || '');
  let html = '<div class="spacer"></div>';
  const seenQ = new Set();
  let hasActivity = false;
  const hasContent = H.conv && (H.conv.loaded || H.conv.events?.size || H.conv.live?.size || dsh.running.has(H.sel));
  if (!H.sel) {
    if (rt.embedded && (dsh.state !== 'online' || !rt.keySet)) html += rtInline();
    else if (!rt.embedded && (dsh.state === 'unpaired' || dsh.state === 'unauthorized' || dsh.state === 'offline')) html += runtimeCard();
    else html += `<div class="welcome"><b>当前聊天</b>${dsh.state === 'online' ? '文件统一保存在手机工作区，Agent 可直接创建、修改和删除，无需审批。' : '对话运行时就绪后即可开始对话。'}</div>`;
  } else if (H.conv?.error && !hasContent) {
    html += notice({ icon: S.warn, title: '暂时无法读取对话', body: esc(H.conv.error), acts: [['retryHistory', '重新加载', true]] });
  } else if (!hasContent) {
    html += `<div class="welcome">${dsh.state === 'online' || dsh.state === 'connecting' ? '加载中…' : rt.embedded ? '' : '未连接 dsh'}</div>`;
  } else {
    if (H.conv.error) html += notice({ icon: S.warn, title: '对话更新已中断', body: esc(H.conv.error), acts: [['retryHistory', '重新连接', true]] });
    if (H.historyMore) html += `<button type="button" class="btn history-more" data-act="older" data-scroll-key="history-more"${H.historyBusy ? ' disabled' : ''}>${H.historyBusy ? '正在加载…' : '加载更早记录'}</button>`;
    const items = H.conv.build({ running: dsh.running.has(H.sel) });
    hasActivity = H.conv.hasLiveOutput?.() || items.some(it => it.k === 'working' || (it.k === 'process' && it.running));
    const itemKey = (it, index) => it.key ? `i:${it.key}` : `${it.k}:${index}`;
    const renderItem = (it, index) => {
      const key = itemKey(it, index);
      let html = '';
      if (it.k === 'process') {
        const latest = it.running ? it.items.filter(x => x.k === 'tools').at(-1)?.tools.at(-1) : null;
        const disclosureKey = it.key + (it.running ? ':running' : ':complete');
        const expanded = H.disclosures.get(disclosureKey) ?? false;
        const activeLabel = latest && !latest.result ? (latest.approval?.state === 'asked' ? '等待授权' : String(latest.args?.description || ({read:'正在读取文件',bash:'正在执行命令',search:'正在搜索',edit:'正在编辑文件',agent:'子任务执行中'}[category(latest)] || '正在调用工具')).slice(0, 80)) : '';
        return `<details class="process-fold" data-disclosure="${esc(disclosureKey)}" data-scroll-key="${esc(key)}"${expanded ? ' open' : ''}><summary>${it.running ? '<span class="spin" style="width:12px;height:12px"></span>' : ''}<span>${esc(activeLabel || it.label)}</span><small>执行过程</small></summary><div class="process-content">${it.items.map((child, i) => renderItem(child, i)).join('')}</div></details>`;
      }
      if (it.k === 'user') html += `<div class="ubub${!it.text?.trim() && it.content?.length && it.content.every(block => block.type === 'image') ? ' image-only' : ''}" data-scroll-key="${esc(key)}">${linkText(it.text)}${userImagesHtml(it.content, { attachment: image => readDurableAttachment(H.sel, image) })}</div>`;
      else if (it.k === 'text') html += `<div class="mdw${it.live ? ' live' : ''}" data-scroll-key="${esc(key)}">${md(it.text)}</div>`;
      else if (it.k === 'think') html += `<details class="reasoning-fold" data-disclosure="${esc(H.sel + ':' + it.key)}" data-scroll-key="${esc(key)}"${H.disclosures.get(H.sel + ':' + it.key) ? ' open' : ''}><summary>${it.live ? '正在思考…' : '思考过程'}</summary><div class="think">${esc(it.text)}</div></details>`;
      else if (it.k === 'tools') html += lineFor(it);
      else if (it.k === 'div') html += `<div class="div" data-scroll-key="${esc(key)}">${esc(it.text)}</div>`;
      else if (it.k === 'art') html += artHtml(it);
      else if (it.k === 'note') html += `<div class="sysnote" data-scroll-key="${esc(key)}">${esc(it.text)}</div>`;
      else if (it.k === 'question') { const cid = it.tool?.id == null ? '' : String(it.tool.id); if (!cid || !seenQ.has(cid)) { if (cid) seenQ.add(cid); html += toolQuestionHtml(H.sel, it.tool); } }
      else if (it.k === 'working') html += `<div class="tool" data-scroll-key="${esc(key)}"><span class="spin" style="width:11px;height:11px"></span><b>正在处理…</b></div>`;
      return html;
    };
    html += items.map((it, index) => renderItem(it, index)).join('');

  }
  for (const p of H.pending.filter(x => x.sid === H.sel)) {
    const cancelUpload = p.sending && p.uploadText ? `<button type="button" class="btn" data-cancel-upload="${esc(p.rpcId)}">取消上传</button>` : '';
    const failure = p.error ? `<small class="pending-state">${p.echoed ? '发送确认失败' : '发送失败'}：${esc(p.error)} <button type="button" class="btn" data-resend="${esc(p.rpcId)}">重试</button> <button type="button" class="btn" data-discard="${esc(p.rpcId)}">放弃重试</button></small>` : '';
    if (!p.echoed) html += `<div class="ubub pending${!p.text?.trim() && p.content?.length && p.content.every(block => block.type === 'image') ? ' image-only' : ''}" data-scroll-key="${esc('pending:' + p.rpcId)}">${linkText(p.text)}${userImagesHtml(p.content)}${failure}${cancelUpload}</div>`;
    else if (failure) html += `<div class="sysnote" data-scroll-key="${esc('pending-error:' + p.rpcId)}">${failure}</div>`;
  }
  const pending = H.pending.findLast(p => p.sid === H.sel && !p.error);
  if (pending && !hasActivity) html += `<div class="tool send-status" role="status" data-scroll-key="pending-status:${esc(pending.rpcId)}"><span class="spin" style="width:11px;height:11px"></span><span>${esc(pending.uploadText || (pending.sending ? '正在发送…' : '正在等待处理…'))}</span></div>`;
  if (!H.sel && H.sessionCreating.has(draftKey())) html += `<div class="tool send-status" role="status" data-scroll-key="creating-status"><span class="spin" style="width:11px;height:11px"></span><span>正在发送…</span></div>`;
  if (H.sel) html += extraQuestionHtml(H.sel, seenQ, false);
  // 解包进度和连接事件会刷新消息区；保留追问输入和用户正在输入的 Key 控件。
  const questionCards = captureQuestionCards(m);
  const keyCardNode = m.querySelector('.rtx.key');
  const keyInput = keyCardNode?.querySelector('#dsk');
  const keyFocused = keyInput && document.activeElement === keyInput;
  const selection = keyFocused ? [keyInput.selectionStart, keyInput.selectionEnd] : null;
  m.innerHTML = html;
  restoreQuestionCards(m, questionCards);
  if (keyCardNode && m.querySelector('.rtx.key')) {
    m.querySelector('.rtx.key').replaceWith(keyCardNode);
    if (keyFocused) { keyInput.focus({ preventScroll: true }); keyInput.setSelectionRange(...selection); }
  }
  // 流式文本光标只加在最后一个块上
  const lv = m.querySelectorAll('.mdw.live'); lv.forEach((x, i) => { const last = x.lastElementChild; x.classList.remove('live'); if (i === lv.length - 1 && last) last.classList.add('live'); });
  m.querySelectorAll('code.fl').forEach((c) => (c.dataset.file = resolvePath(c.dataset.file, H.conv?.header?.cwd)));
  chatScroll?.end({ sid: H.sel || '', ready: !H.sel || !!H.conv?.loaded, prepend });
  renderInspector();
}

/* ============================================================ 审批 */
function approvalArguments(approval) {
  const request = approval?.request || {};
  const call = H.conv && [...H.conv.events.values()].find(e => e.type === 'tool/call' && e.data?.callId === request.callId);
  if (!call) return {text:'', complete:false};
  try {
    const args = typeof call.data.arguments === 'string' ? JSON.parse(call.data.arguments) : call.data.arguments;
    if (!args || typeof args !== 'object' || !Object.keys(args).length) return {text:'',complete:false};
    const text = JSON.stringify(args, null, 2);
    return {text:text.slice(0,20000),complete:text.length <= 20000};
  } catch { return {text:'',complete:false}; }
}
function renderApprovals() {
  const w = el?.querySelector('.apvw'); if (!w) return;
  const list = [...dsh.approvals.values()].filter((a) => a.agentId === H.sel);
  w.innerHTML = list.map((a) => {
    const r = a.request || {}; const why = r.displayReason?.zh || r.displayReason?.en || r.reason || '';
    const context = approvalArguments(a);
    H.approvalViews.set(a.eventId, context.text);
    const cmd = context.text;
    return `<div class="apv" style="margin:0 12px 6px"><b>${S.shield}对话运行时请求权限 · ${esc(r.toolName || '')}</b><div class="why">${esc(why)}</div>${cmd ? `<pre class="cmd">${esc(cmd)}</pre>` : ''}${!context.complete ? '<p class="why">完整操作参数尚不可用，请等待加载或在原始对话运行时查看。</p>' : ''}<div class="acts stk"><button type="button" class="btn" data-apv="${esc(a.eventId)}" data-ok="0">拒绝</button><button type="button" class="btn dk" data-apv="${esc(a.eventId)}" data-ok="1"${context.complete ? '' : ' disabled'}>允许</button></div></div>`;
  }).join('');
}

/* ============================================================ 输入区 */
function autosize(ta) { ta.style.height = 'auto'; ta.style.height = Math.min(140, ta.scrollHeight) + 'px'; }
function updateInputRow() {
  const row = el?.querySelector('.input .row'); if (!row) return;
  const running = !!(H.sel && dsh.running.has(H.sel));
  const stopping = running && H.stoppingSid === H.sel;
  const text = el.querySelector('.input textarea')?.value.trim();
  const hasContent = !!text || H.attach.length > 0;
  const reading = (H.imageReading.get(draftKey()) || 0) > 0;
  const creating = !H.sel && H.sessionCreating.has(draftKey());
  const needsKey = rt.embedded && !rt.keySet;
  const disabled = !H.bots || H.botError || needsKey || dsh.state !== 'online' || reading || creating || !hasContent;
  // 单一主操作：运行中且有草稿显示发送；运行中且没有草稿显示停止。两种情况不同时出现。
  const showSend = !running || hasContent;
  const showStop = running && !hasContent;
  const m = curModel();
  const ta = el.querySelector('.input textarea');
  if (ta) ta.placeholder = '发送消息…';
  row.innerHTML = `<button type="button" class="cbtn" data-act="plus" aria-label="添加附件"${reading || creating ? ' disabled' : ''}>${S.plus}</button>
<button type="button" class="chip mdl" data-act="model" aria-label="切换模型"><span class="full">${esc(modelLabel(m, false))}</span><span class="short">${esc(modelLabel(m, true))}</span> ${S.chev}</button>
<div style="flex:1"></div>${showStop ? `<button type="button" class="send stop" aria-label="停止回复" data-act="stop"${dsh.state !== 'online' || stopping ? ' disabled' : ''}>${S.stop}</button>` : ''}${showSend ? `<button type="button" class="send${disabled ? ' off' : ''}" aria-label="发送消息" data-act="send"${disabled ? ' disabled' : ''}>${S.up}</button>` : ''}`;
  const at = el.querySelector('.input .atts');
  at.innerHTML = H.attach.length ? `<div class="draft-attachments">${H.attach.map((a, i) => {
    const src = inlineImageSrc(a);
    const title = a.name || '附件';
    return `<div class="draft-attachment"><button type="button" class="draft-preview" data-draft-preview="${i}" aria-label="预览 ${esc(title)}">${src ? `<img src="${esc(src)}" alt="${esc(title)}">` : `<span class="draft-file-kind">${a.kind === 'video' ? '▶' : S.file}</span><span>${esc(title)}</span><small>${esc(fmtBytes(a.bytes || 0))}</small>`}</button><button type="button" class="draft-remove" data-rm="${i}" aria-label="移除 ${esc(title)}">${S.x}</button></div>`;
  }).join('')}</div>` : '';
}
/** 停止运行：单飞锁保证一次只发一个取消请求；重复点击不再重复取消，也不重发写操作。 */
async function stopRun(sid) {
  const target = sid || H.sel;
  if (!target) return;
  if (H.stoppingSid === target) return;
  H.stoppingSid = target;
  updateInputRow();
  let timer = null;
  try {
    await Promise.race([
      api.cancel(target),
      // 取消是写操作：超时只说明结果未知，不能自动重发。
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('停止请求超时，任务可能仍在运行，请稍后确认。')), 65000); }),
    ]);
    toast('已请求停止');
  } catch (x) {
    toast(x.message || '停止失败');
  } finally {
    if (timer) clearTimeout(timer);
    if (H.stoppingSid === target) H.stoppingSid = '';
    updateInputRow();
  }
}
async function send() {
  const ta = el.querySelector('.input textarea'); const text = ta.value.trim();
  const origin = draftKey(), originBot=H.botId;
  if ((!text && !H.attach.length) || (H.imageReading.get(origin) || 0) > 0 || (!H.sel && H.sessionCreating.has(draftKey()))) return;
  if (rt.embedded && !rt.keySet) { toast('请先配置 DeepSeek'); window.__pd_open_model?.('deepseek'); return; }
  if(!H.bots||H.botError){toast('请先重新读取机器人列表');return;}
  if (dsh.state !== 'online') { toast('对话尚未连接'); return; }
  const content = [...(text ? [{ type: 'text', text }] : []), ...H.attach.map(a => a.handle ? {type:'file', ...a} : { type:'image', mediaType:a.mediaType, data:a.data, name:a.name })];
  const attach = H.attach, wasNew = !H.sel;
  const model = H.draftModels.get(origin);
  H.attach = []; H.attachmentDrafts.set(origin, H.attach);
  ta.value = ''; autosize(ta); H.drafts[origin] = '';
  if (wasNew) { H.sessionCreating.add(origin); renderMsgs(); }
  updateInputRow();
  try {
    let sid = H.sel;
    if (wasNew) {
      const workspace=H.bots.bots.find(b=>b.id===originBot)?.workspace||`/BOT/${originBot}`;
      let w = dsh.workspaces.find(x => x.path === workspace);
      if (!w) { const r = await api.createWorkspace(workspace); w = r.workspace; }
      if(w?.path!==workspace)throw Error('工作区归属未确认，请重试');
      let pending=LS('pendingSession.'+originBot,null);
      if(!pending){pending={sessionId:'session-'+uuid()};SV('pendingSession.'+originBot,pending);}
      const r = await api.createSession({workspaceId:w.workspaceId,sessionId:pending.sessionId});
      if(r.sessionId!==pending.sessionId)throw Error('会话身份未确认');
      sid=r.sessionId;
      await botCall({op:'bind',id:originBot,sessionId:sid});
      if (model) await api.selectModel(sid, model.provider, model.model, model.reasoningEffort);
      H.draftModels.delete(origin);
      localStorage.removeItem('pd.h.pendingSession.'+originBot);
      if (!H.sel && H.botId === originBot && H.sessionCreating.has(origin)) {
        if (H.pickKey === origin) H.pickKey = sid;
        H.drafts[sid] = ta.value;
        H.attachmentDrafts.set(sid, H.attach);
        select(sid);
        H.drafts[origin] = ''; H.attachmentDrafts.delete(origin);
      }
    }
    const p = { sid, botId: originBot, rpcId: uuid(), content, mode: dsh.running.has(sid) ? 'steer' : 'queue', text, t: Date.now() };
    H.pending.push(p);
    if (H.sel === sid) renderMsgs(true);
    await deliverPending(p);
  } catch (e) {
    const current = (draftKey()) === origin;
    const newerText = current ? ta.value : H.drafts[origin] || '';
    H.drafts[origin] = text && newerText ? text + '\n' + newerText : text || newerText;
    if (current) { ta.value = H.drafts[origin]; autosize(ta); }
    const existing = H.attachmentDrafts.get(origin) || [];
    H.attachmentDrafts.set(origin, [...attach, ...existing]);
    if (current) H.attach = H.attachmentDrafts.get(origin);
    toast('发送失败：' + e.message); renderMsgs();
  } finally {
    if (wasNew) { H.sessionCreating.delete(origin); renderMsgs(); }
    updateInputRow();
  }
}
async function deliverPending(p) {
  if (p.sending) return;
  p.sending = true; p.error = ''; p.cancelled = false; p.uploadIds = [];p.uploadAttempt=(p.uploadAttempt || 0)+1;renderMsgsSoon();
  try {
    if (!p.wireContent) {
      const wire = [];
      p.uploadIds ||= [];
      for (const [index, block] of p.content.entries()) {
        if (block.type !== 'file') { wire.push(block); continue; }
        if (!block.receiptId) {
          const requestId = `${p.rpcId}:${p.uploadAttempt}:${index}`;
          if (!p.uploadIds.includes(requestId)) p.uploadIds.push(requestId);
          p.uploadText = `正在上传 ${block.name || '附件'}…`;renderMsgsSoon();
          const uploaded = await native.uploadAttachment({handle:block.handle, botId:p.botId, sessionId:p.sid, requestId});
          if (p.cancelled) throw Error('上传已取消');
          block.receiptId = uploaded.receiptId; block.attachment = uploaded.file;
        }
        wire.push({type:'file',receiptId:block.receiptId});
      }
      p.wireContent = wire;
    }
    if (p.cancelled) throw Error('上传已取消');
    p.uploadText = '';
    const result = await api.prompt(p.sid, p.wireContent, p.mode, p.rpcId);
    if (result?.accepted !== true) throw new Error('发送尚未确认，请重试');
    releasePendingFiles(p);
  } catch (e) {
    if (e.code === 'session/attachment-invalid' && e.details?.reason === 'FILE_NOT_STAGED') {
      p.wireContent = null;
      for (const block of p.content) if (block.handle) {
        delete block.receiptId;
        await native.forgetAttachmentReceipt({handle:block.handle,sessionId:p.sid,botId:p.botId});
      }
      p.error = '附件暂存凭据已失效，请重试上传';
    } else p.error = e.message || '请重试';
  }
  finally {
    p.sending = false;
    if (p.echoed && !p.error) H.pending = H.pending.filter(x => x !== p);
    if (H.sel === p.sid) renderMsgsSoon();
  }
}
function releasePendingFiles(p) {
  if (p.filesReleased) return;
  p.filesReleased = true;
  for (const block of p.content) if (block.handle) native.removeAttachment(block.handle).catch(()=>{});
}
async function chooseFileDraft() {
  const key = draftKey();
  H.imageReading.set(key, (H.imageReading.get(key) || 0) + 1);updateInputRow();
  try {
    const selected = await native.chooseFiles();
    const target = H.attachmentDrafts.get(key) || (draftKey() === key ? H.attach : []);
    H.attachmentDrafts.set(key,target);
    for (const file of selected || []) {
      if (target.length >= 6) { await native.removeAttachment(file.handle);toast('最多添加 6 个附件');continue; }
      target.push({type:'file',...file});
    }
    if (draftKey() === key) H.attach = target;
  } catch (error) { toast(error.message || '文件选择失败'); }
  finally {
    const remaining = (H.imageReading.get(key) || 1) - 1;
    if (remaining) H.imageReading.set(key,remaining);else H.imageReading.delete(key);
    updateInputRow();
  }
}
async function onPick(e) {
  const key = H.pickKey || draftKey(); H.pickKey = null;
  const files = [...e.target.files]; e.target.value = '';
  const target = H.attachmentDrafts.get(key) || ((draftKey()) === key ? H.attach : []);
  H.attachmentDrafts.set(key, target);
  H.imageReading.set(key, (H.imageReading.get(key) || 0) + 1);
  updateInputRow();
  try {
    for (const f of files) {
      if (target.length >= 6) { toast('最多添加 6 个附件'); break; }
      const mt = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(f.type) ? f.type : null;
      if (!mt) { toast('仅支持 png / jpeg / webp / gif'); continue; }
      if (f.size > 8 * 1024 * 1024) { toast('图片超过 8MB'); continue; }
      try {
        const data = await new Promise((resolve, reject) => {
          const fr = new FileReader();
          fr.onload = () => resolve(String(fr.result).split(',')[1]);
          fr.onerror = fr.onabort = () => reject(new Error('图片读取失败，请重新选择'));
          fr.readAsDataURL(f);
        });
        target.push({ mediaType: mt, data, name: f.name });
      } catch (error) { toast(error.message); }
    }
  } finally {
    const remaining = (H.imageReading.get(key) || 1) - 1;
    if (remaining) H.imageReading.set(key, remaining); else H.imageReading.delete(key);
    if ((draftKey()) === key) H.attach = target;
    updateInputRow();
  }
}

/* ============================================================ 菜单 */
function modelMenu(anchor) {
  const cat = dsh.catalog; const cur = curModel();
  if (!cat) { toast('模型列表加载中…'); return; }
  const items = [];
  for (const g of cat.groups || []) {
    items.push({ header: g.name || g.id });
    for (const m of g.models || []) items.push({ label: m.name || m.id, sub: '', on: cur && cur.model === m.id && cur.provider === g.id, act: () => setModel(g.id, m.id, effortFor(m, cur?.reasoningEffort)) });
  }
  const mdef = (cat.groups || []).flatMap((g) => (g.models || []).map((m) => ({ g, m }))).find((x) => x.m.id === cur?.model && x.g.id === cur?.provider);
  const efforts = mdef?.m.reasoning?.efforts || [];
  if (efforts.length) { items.push({ sep: true }, { header: '推理强度' }); for (const e of efforts) items.push({ label: EFFORT[e.id] || e.name, sub: '', on: cur?.reasoningEffort === e.id, act: () => setModel(mdef.g.id, mdef.m.id, e.id) }); }
  menu(anchor, items);
}
const effortFor = (m, want) => { const ef = m.reasoning?.efforts || []; return ef.some((e) => e.id === want) ? want : m.reasoning?.defaultEffort || ef[0]?.id; };
async function setModel(provider, model, reasoningEffort) {
  if (!H.sel) { H.draftModels.set(draftKey(),{ provider, model, reasoningEffort }); updateInputRow(); return; }
  try { await api.selectModel(H.sel, provider, model, reasoningEffort); const s = dsh.sessions.get(H.sel); if (s) { s.projections = s.projections || { values: {} }; s.projections.values.modelSelection = { ...(s.projections.values.modelSelection || {}), next: { provider, model, reasoningEffort } }; } updateInputRow(); } catch (e) { toast('切换模型失败：' + e.message); }
}
async function showBotSettings(id){
  try{
    const config=await native.bots({op:'settings',id});
    const bot=H.bots?.bots.find(b=>b.id===id);if(!bot)return;
    openBotSettingsSheet({sheet,config,bot,canCompact:!!activeSessionForBot(id),actions:{
      openPersona:()=>showCommonPersona(),
      save:async value=>{
        await native.bots({op:'saveSettings',id,instructions:value.instructions,memory:value.memory,enabledTools:value.enabledTools,compactionThreshold:value.compactionThreshold});
        if(value.name!==bot.name||value.avatar!==bot.avatar||value.fish!==bot.fish||value.color!==bot.color)await botCall({op:'rename',id,name:value.name,avatar:value.avatar,fish:value.fish,color:value.color});
        renderSide();renderHead();closeSheet();toast('已保存，从后续步骤生效');
      },
      setPinned:async pinned=>{await botCall({op:'pin',id,pinned});renderSide();},
      compact:async()=>{const sid=activeSessionForBot(id);if(!sid)throw Error('当前 Bot 没有可整理的会话');await api.command(sid,'/compact');toast('已请求整理上下文');},
      deleteBot:async()=>{
        const bound=sessionsForBot(H.bots,id,dsh.sessions).map(session=>session.sessionId);
        if(bound.some(sid=>dsh.running.has(sid)||isWaiting(sid)))throw Error('这个 Bot 仍在运行或等待回答，请结束后再删除');
        await botCall({op:'delete',id,confirmDelete:true});
        if(H.botId===id)selectBot('default');else renderSide();
        closeSheet();toast('Bot 已移除，工作区已归档');
      }
    }});
  }catch(e){toast('设置读取失败：'+e.message);}
}
async function showCommonPersona(){
  try{
    const config=await native.bots({op:'persona'});
    sheet({title:'共同人格',body:`<div class="bot-settings"><p>这份主人格由所有 Bot 共同继承。保存后，各 Bot 从后续步骤使用新版；自己的记忆和职责保持独立。</p><textarea data-shared-persona maxlength="16000" rows="14">${esc(config.text)}</textarea><p class="bot-settings-error" role="status"></p><button type="button" class="btn dk" data-save-persona>保存共同人格</button></div>`,onMount:body=>{
      body.querySelector('[data-save-persona]').onclick=async()=>{const button=body.querySelector('[data-save-persona]');button.disabled=true;try{await native.bots({op:'savePersona',text:body.querySelector('[data-shared-persona]').value});closeSheet();toast('共同人格已更新');}catch(e){body.querySelector('.bot-settings-error').textContent='保存失败：'+e.message;button.disabled=false;}};
    }});
  }catch(e){toast('共同人格读取失败：'+e.message);}
}
async function renameBot(id){
  const bot=H.bots?.bots.find(b=>b.id===id);if(!bot)return;
  const value=await promptSheet({title:'重命名 Bot',label:'名称',value:botName(bot),placeholder:'输入 Bot 名称',ok:'保存'});
  if(!value?.trim())return;
  try{await botCall({op:'rename',id,name:value.trim()});renderSide();renderHead();}catch(e){toast('重命名失败：'+e.message);}
}
async function setBotPin(id,pinned){
  try{await botCall({op:'pin',id,pinned});renderSide();}catch(e){toast('置顶操作失败：'+e.message);}
}
function botMenuFor(anchor,id){const b=H.bots?.bots.find(b=>b.id===id);if(b)menu(anchor,[{label:'Bot 设置',icon:S.edit,act:()=>showBotSettings(id)},{label:'重命名',icon:S.edit,act:()=>renameBot(id)},{label:b.pinned?'取消置顶':'置顶',icon:S.pin,act:()=>setBotPin(id,!b.pinned)}]);}
async function createBot(){
  if(H.creating||!H.bots)return;H.creating=true;
  try{
    let pending=LS('pendingBotIdentity',null);
    if(!pending){const identity=await promptBotCreation({sheet,closeSheet});if(!identity)return;pending={id:uuid(),...identity};SV('pendingBotIdentity',pending);}
    const epoch=H.navigationEpoch;
    await botCall({op:'create',...pending});localStorage.removeItem('pd.h.pendingBotIdentity');
    if(H.navigationEpoch===epoch)selectBot(pending.id);else renderSide();
  }catch(e){toast('创建未确认：'+e.message+'；再次添加将核对同一个 Bot');}finally{H.creating=false;updateInputRow();}
}
function moreMenu(anchor) {
  const bot=currentBot();
  const items = [{label:'Bot 设置',icon:S.edit,act:()=>showBotSettings(H.botId)},{label:'重命名 Bot',icon:S.edit,act:()=>renameBot(H.botId)},{label:bot?.pinned?'取消置顶 Bot':'置顶 Bot',icon:S.pin,act:()=>setBotPin(H.botId,!bot?.pinned)}];
  if (H.sel && dsh.running.has(H.sel)) items.push({ label: '停止运行', icon: S.stop.replace('#fff', '#c0392b'), danger: true, act: () => void stopRun(H.sel) });
  items.push({ sep: true });

  if (!rt.embedded) items.push({ label: '打开 DSHBox', icon: S.ext, act: () => native.openDshbox() });
  menu(anchor, items, { align: 'right' });
}
/* ============================================================ 事件 */
function onInput(e) {
  if (isQuestionTextarea(e.target)) { questionInput(e.target); return; }
  if (e.target.matches('textarea')) { H.drafts[draftKey()] = e.target.value; autosize(e.target); updateInputRow(); }
  else if (e.target.matches('.sfilter')) { H.filter = e.target.value; scheduleSearch(); }
  else if (e.target.matches('.addr input')) { /* 回车时导航 */ }
}
function onKey(e) {
  if (isQuestionTextarea(e.target)) return;
  if (e.target.matches('.sfilter') && e.key === 'Enter' && !e.isComposing) { e.preventDefault(); scheduleSearch(); return; }
  if (e.target.matches('.tool[data-tk]') && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); e.target.click(); return; }
  if (e.target.matches('textarea') && e.key === 'Enter' && !e.shiftKey && !e.isComposing && !('ontouchstart' in window)) { e.preventDefault(); send(); }

}
function onCtx(e) {
  const b=e.target.closest('[data-bot]');if(b){e.preventDefault();botMenuFor(b,b.dataset.bot);return;}
}
async function onClick(e) {
  const t = e.target;
  if (t.closest('button:disabled')) return;
  if (questionClick(e)) return;
  const resend = t.closest('[data-resend]');
  if (resend) { const p = H.pending.find((p) => p.sid === H.sel && p.rpcId === resend.dataset.resend); if (p) deliverPending(p); return; }
  const discard = t.closest('[data-discard]');
  if (discard) {
    const p=H.pending.find(value=>value.rpcId===discard.dataset.discard && value.sid===H.sel);
    if (p && !p.sending) {releasePendingFiles(p);H.pending=H.pending.filter(value=>value!==p);renderMsgs();}
    return;
  }
  const botMenu=t.closest('[data-bot-menu]');if(botMenu){botMenuFor(botMenu,botMenu.dataset.botMenu);return;}
  const bot=t.closest('[data-bot]');if(bot){selectBot(bot.dataset.bot);return;}
  const tk = t.closest('.tool[data-tk]'); if (tk) { const k = tk.dataset.tk; H.openTools.has(k) ? H.openTools.delete(k) : H.openTools.add(k); renderMsgs(); return; }
  const apv = t.closest('[data-apv]'); if (apv) {
    const allow = apv.dataset.ok === '1';
    if (allow) {
      const approval = dsh.approvals.get(apv.dataset.apv);
      const context = approvalArguments(approval);
      if (!context.complete || context.text !== H.approvalViews.get(apv.dataset.apv)) {
        renderApprovals(); toast('操作参数未完整显示或已更新，请重新查看'); return;
      }
    }
    decideApproval(apv.dataset.apv, allow).catch((x) => toast(x.message)); return;
  }
  const cancelUpload = t.closest('[data-cancel-upload]');
  if (cancelUpload) {
    const p = H.pending.find(value=>value.rpcId===cancelUpload.dataset.cancelUpload);
    if (p) { p.cancelled=true;for (const requestId of p.uploadIds || []) native.cancelAttachment(requestId).catch(()=>{}); }
    return;
  }
  const copyCode = t.closest('.code-copy'); if (copyCode) {
    const code = copyCode.closest('.code-block')?.querySelector('pre code');
    native.copy(code?.textContent || '').then(() => toast('已复制'), (x) => toast(x.message || '复制失败')); return;
  }
  const durableImage = t.closest('.chat-image[data-attachment-id]');
  if (durableImage) {
    if (durableImage.dataset.status === 'error') {
      readDurableAttachment(H.sel, { attachmentId: durableImage.dataset.attachmentId }, true);
      renderMsgs();
    } else if (durableImage.dataset.status === 'loaded') {
      const src = durableImage.querySelector('img')?.getAttribute('src') || '';
      if (isInlineImageSrc(src)) showImageSheet(src, durableImage.dataset.imageTitle || '图片');
    }
    return;
  }
  const imageRetry = t.closest('[data-image-retry]');
  if (imageRetry) { readDurableAttachment(H.sel,{attachmentId:imageRetry.dataset.imageRetry},true);renderMsgs();return; }
  const inlineImage = t.closest('.chat-image[data-image]');
  if (inlineImage) {
    const src = inlineImage.querySelector('img')?.getAttribute('src') || '';
    if (isInlineImageSrc(src)) showImageSheet(src, inlineImage.dataset.imageTitle || '图片');
    return;
  }
  const saveArt = t.closest('[data-art-save]');
  if (saveArt) { native.openContent({path:saveArt.dataset.artSave,botId:H.botId,sessionId:H.sel,download:true}).then(v=>toast(v.displayPath || '已保存')).catch(x=>toast(x.message));return; }
  const art = t.closest('[data-art]'); if (art) { if(H.inspector){H.inspector=false;SV('inspector',false);el.classList.remove('bot-detail-open');} openArtifact(art.dataset.art); return; }
  const fl = t.closest('code.fl'); if (fl) { openArtifact(expandHome(fl.dataset.file)); return; }
  const a = t.closest('a[data-href]'); if (a) { e.preventDefault(); const u = a.dataset.href; if (/^https?:/i.test(u)) openUrl(u); else openArtifact(expandHome(resolvePath(u, H.conv?.header?.cwd))); return; }
  const preview = t.closest('[data-draft-preview]');
  if (preview) {
    const item = H.attach[+preview.dataset.draftPreview];
    const src = inlineImageSrc(item);
    if (src) showImageSheet(src, item.name || '图片');
    else if (item?.handle) native.openContent({handle:item.handle, name:item.name, kind:item.kind, botId:H.botId, sessionId:H.sel}).catch(x=>toast(x.message));
    return;
  }
  const file = t.closest('[data-file-open], [data-file-save]');
  if (file) {
    const args = {sessionId:H.sel, botId:H.botId, ...(file.dataset.fileHandle ? {handle:file.dataset.fileHandle} : {attachmentId:file.dataset.attachmentId}), name:file.dataset.fileName, download:file.hasAttribute('data-file-save')};
    native.openContent(args).then(value=>{if(value?.displayPath)toast(`已保存：${value.displayPath}`);}).catch(x=>toast(x.message));
    return;
  }
  const rm = t.closest('[data-rm]'); if (rm) {
    const [removed] = H.attach.splice(+rm.dataset.rm, 1);
    if (removed?.handle) native.removeAttachment(removed.handle).catch(x=>toast(x.message));
    updateInputRow(); return;
  }
  const act = t.closest('[data-act]'); if (!act) return;
  const A = act.dataset.act;
  switch (A) {
    case 'older': await loadOlder(); break;
    case 'retryHistory': select(H.sel, { keep: true }); break;
    case 'retryBots': await refreshMode();break;
    case 'newBot': await createBot();break;
    case 'inspector': H.inspector=!H.inspector;SV('inspector',H.inspector);renderHead();syncOverlays();chatScroll?.refresh();break;
    case 'search': {
      H.searching = !H.searching;
      const input = el.querySelector('.sfilter'); input?.classList.toggle('hide', !H.searching);
      if (!H.searching) { H.filter = ''; if (input) input.value = ''; }
      scheduleSearch(); if (H.searching) input?.focus(); break;
    }
    case 'side':
      if (innerWidth < 600) { H.mobileChat = !H.mobileChat; el.classList.toggle('mobile-chat', H.mobileChat); }
      else { H.noside = !H.noside; SV('noside', H.noside); el.classList.toggle('noside',H.noside);chatScroll?.refresh();syncOverlays(); }
      break;
    case 'more': moreMenu(act); break;
    case 'model': modelMenu(act); break;
    case 'plus': menu(act, [
      {label:'图片',icon:S.img,act:()=>{H.pickKey=draftKey();document.getElementById('filepick').click();}},
      {label:'文件',icon:S.clip,act:()=>void chooseFileDraft()},
    ]); break;
    case 'send': send(); break;
    case 'stop': void stopRun(H.sel); break;
    case 'openDshbox': native.openDshbox().then((ok) => ok === false && toast('未安装 DSHBox')); break;
    case 'settings': await showBotSettings(H.botId); break;
    case 'modelsettings': window.__pd_open_model?.('deepseek'); break;
    case 'retry': RT.at = 0; reconnectNow(); startDsh(); break;
    case 'pluginExample': fillPluginExample(); break;
    case 'rtRetry': retryRt().catch((e) => toast(e.message)); renderMsgs(); renderBanner(); break;
    case 'paste': promptSheet({ title: '粘贴 DSHBox 启动链接', label: '启动链接或令牌', placeholder: 'http://127.0.0.1:3080/?token=…', ok: '配对', note: '在 DSHBox 里点「用外部浏览器打开」，选择「复制链接」或任意浏览器，再把带 ?token= 的地址粘贴到这里。' }).then((v) => v && pair(v)); break;
    case 'toNative': await native.setConfig({ harnessMode: 'native' }); await refreshMode(); startDsh(); break;
    default: break;
  }
}

function showImageSheet(src, title = '图片', source = null) {
  if (!isInlineImageSrc(src) && !fileFromUrl(src)) return;
  const saveSource = source || (isInlineImageSrc(src) ? {dataUrl:src,name:title} : null);
  sheet({title,full:true,body:`<img class="chat-image-view" src="${esc(src)}" alt="${esc(title)}">${saveSource ? '<button type="button" class="btn" id="save-chat-image">保存图片</button>' : ''}`});
  document.getElementById('save-chat-image')?.addEventListener('click',()=>native.openContent({...saveSource,download:true}).then(v=>toast(v.displayPath || '已保存')).catch(x=>toast(x.message)));
}


/* All document and site navigation goes through the shared native content surface. */
function openArtifact(path) {
  if (!path) return;
  path = path.startsWith('file://') ? path.slice(7) : path;
  if (/\.(png|jpe?g|webp|gif)$/i.test(path)) { showImageSheet(fileUrl(path), basename(path), {path,botId:H.botId,sessionId:H.sel}); return; }
  if (H.inspector) { H.inspector=false;SV('inspector',false);el.classList.remove('bot-detail-open'); }
  native.openContent({path,botId:H.botId,sessionId:H.sel,title:basename(path)}).catch(x=>toast(x.message));
}
function openUrl(url) {
  native.openContent({url,title:url}).catch(x=>toast(x.message));
}
export function syncOverlays() {
  if (!el) return;
  const vp = el.querySelector('.dshweb .vp');
  const r = vp ? vp.getBoundingClientRect() : null;
  native.dshWeb({visible:!!(vp && el.classList.contains('on') && H.mode==='web' && !document.getElementById('sheet').classList.contains('on')),rect:r?{x:r.left,y:r.top,w:r.width,h:r.height}:null});
}
window.addEventListener('resize',()=>requestAnimationFrame(syncOverlays));

/* ============================================================ 分栏拖拽（内屏） */
function applyWidths() {
  const side = el.querySelector('.side'), chat = el.querySelector('.chat');
  if (side) side.style.width = '';
  if (chat) chat.style.width = '';
  const w = LS('widths', null); if (!w || cover) return;
  if (side && Number.isFinite(w.side)) side.style.width = Math.min(35,Math.max(15,w.side)) + '%';
  if (chat && Number.isFinite(w.chat) && !el.classList.contains('nobrowser')) {
    const total=el.getBoundingClientRect().width;
    const sideWidth=side?.getBoundingClientRect().width || 0;
    const previewReserve=Math.min(280,total*.3);
    const maxChat=Math.max(180,total-sideWidth-previewReserve-18);
    chat.style.width=Math.min(total*w.chat/100,maxChat)+'px';
  }
}
let drag = null;
document.addEventListener('pointerdown', (e) => { const d = e.target.closest?.('#scr-harness [data-drag]'); if (!d || cover) return; drag = { kind: d.dataset.drag, x0: e.clientX, w0: (d.dataset.drag === 'side' ? el.querySelector('.side') : el.querySelector('.chat')).getBoundingClientRect().width }; d.setPointerCapture(e.pointerId); });
document.addEventListener('pointermove', (e) => {
  if (!drag) return; const total = el.getBoundingClientRect().width; const w = Math.max(140, drag.w0 + e.clientX - drag.x0); const pct = Math.min(drag.kind === 'side' ? 35 : 70, (w / total) * 100);
  (drag.kind === 'side' ? el.querySelector('.side') : el.querySelector('.chat')).style.width = pct + '%'; syncOverlays();
});
document.addEventListener('pointerup', () => { if (!drag) return; const total = el.getBoundingClientRect().width; SV('widths', { side: (el.querySelector('.side').getBoundingClientRect().width / total) * 100, chat: (el.querySelector('.chat').getBoundingClientRect().width / total) * 100 }); drag = null; });

/** 开发/截图钩子 */
export const dev = {selectBot,select:(sid)=>select(sid),openArtifact,openUrl,H};

/** 返回键：浏览器后退 > 关闭展开 > 交给上层 */
export function back() {
  if(H.inspector){H.inspector=false;SV('inspector',false);renderHead();syncOverlays();return true;}
  if(cover&&H.mobileChat){H.mobileChat=false;el.classList.remove('mobile-chat');return true;}

  if (cover && H.noside) { H.noside = false; SV('noside', false); build(); return true; }
  return false;
}
