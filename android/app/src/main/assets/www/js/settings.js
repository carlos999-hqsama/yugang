// 精简设置：通用 / 连接 / 关于。宽屏左菜单右子页，窄屏分类菜单可返回；保留草稿、焦点和滚动。
import { native, on, isAndroid } from './native.js';
import { S } from './icons.js';
import { esc, ago } from './util.js';
import { toast, promptSheet } from './ui.js';
import { dsh, startDsh, onDsh, call as dshCall } from './dsh.js';
import {readDefaultModel, saveDefaultModel} from './model-settings.js';
import { rt, onRt, rtLine, retryRt, refreshCfg, saveKey } from './rt.js';

const TABS = [
  { id: 'general', label: '通用', icon: S.gear },
  { id: 'connection', label: '连接', icon: S.server },
  { id: 'about', label: '关于', icon: S.info },
];
const WIDE_MQ = '(min-width: 600px)';
const RT_DOT = { ready: 'g', installing: 'b', starting: 'b', error: 'r', missing: 'r', stopped: 'gr', idle: 'gr' };
const DSH_STATE = {
  idle: ['gr', '未启用'],
  unpaired: ['o', '未配对'],
  connecting: ['b', '连接中…'],
  online: ['g', '已连接'],
  offline: ['r', '无法连接'],
  unauthorized: ['r', '登录失效'],
};

let el, cfg = {}, dshTest = null, diag = null, cfgError = '';
let cfgLoaded = false, cfgLoading = false, loadSeq = 0, loadInFlight = null;
let modelTest = null;
let modelSettings = null, modelSettingsError = '', modelSettingsLoading = null, modelSettingsSaving = false;
let verifiedModels = [], keyTesting = false, keyValidationEpoch = 0;
let pluginModelDailyLimitFeedback = null;
let activeTab = 'general';
const folds = new Map();
const drafts = new Map();
const dirtyIds = new Set();
const busyActions = new Set();
const scrollTops = new Map();
let composing = false, queuedRender = false;
let narrowMenu = false, renderedTab = null, navScrollTop = 0, pendingFocus = null, wideMedia = null;
let pendingOpenTab = null;

/** 读取设置有截止时间：桥接无响应时页面要显示失败并可重试，而不是空白。 */
function withDeadline(request, ms, message) {
  const pending = Promise.resolve(request);
  if (typeof setTimeout !== 'function') return pending;
  let timer = null;
  return new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error(message)), ms);
    pending.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
  });
}

function errorText(e) {
  return String((e && e.message) || e || '操作失败')
    .replace(/([?&]token=)[^&\s]+/gi, '$1…')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, 'sk-…')
    .replace(/Harness/g, '对话运行时');
}
const isExternal = () => cfg.dshMode === 'external';
const rtText = () => (rt.state === 'ready' ? (dsh.state === 'online' ? '运行中' : '运行中 · 连接中…') : rtLine());
const runtimeText = () => rtText().replace(/Harness/g, '对话运行时');
const runtimeDot = () => (rt.state === 'ready' && dsh.state !== 'online' ? 'b' : RT_DOT[rt.state] || 'gr');
const foldOpen = (key) => (folds.has(key) ? folds.get(key) : false);

function isWideLayout() {
  if (wideMedia) return !!wideMedia.matches;
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') return window.matchMedia(WIDE_MQ).matches;
  return typeof window === 'undefined' ? true : (window.innerWidth || 0) >= 600;
}

function syncViewMode() {
  const shell = el?.querySelector('.st-shell');
  if (!shell) return;
  const wide = isWideLayout();
  shell.classList.toggle('st-menu-view', !wide && narrowMenu);
  shell.classList.toggle('st-subpage-view', !wide && !narrowMenu);
  shell.setAttribute('data-layout', wide ? 'wide' : 'narrow');
}

function tabMeta(id) {
  return TABS.find((t) => t.id === id) || TABS[0];
}

function focusForTab(id) {
  return isWideLayout() ? { kind: 'tab', id } : { kind: 'back' };
}

function panelFor(id) {
  if (id === 'connection') return connectionPanel();
  if (id === 'about') return aboutPanel();
  return generalPanel();
}

function categoryNav() {
  return `<aside class="st-navwrap">
    <nav class="st-tabs" aria-label="设置分类">
      ${TABS.map((t) => {
        const on = t.id === activeTab;
        return `<button type="button" class="st-tab${on ? ' on' : ''}" id="st-tab-${t.id}" data-settings-tab="${t.id}" aria-label="${t.label}"${on ? ' aria-current="page"' : ''}>
          <span class="st-tab-icon" aria-hidden="true">${t.icon}</span>
          <span class="st-tab-label">${t.label}</span>
          <span class="st-tab-chevron" aria-hidden="true">${S.fwd}</span>
        </button>`;
      }).join('')}
    </nav>
  </aside>`;
}

function stateLine(dot, text, extra = '') {
  return `<span class="st-state"><i class="st-dot ${dot}"></i><span>${esc(text)}</span>${extra ? `<small>${esc(extra)}</small>` : ''}</span>`;
}

function statusIcon(feedback) {
  return feedback?.tone === 'good' ? S.check : feedback?.tone === 'bad' ? S.warn : S.info;
}

function feedbackNote(feedback) {
  if (!feedback?.text) return '';
  const tone = feedback.tone === 'good' || feedback.tone === 'bad' ? feedback.tone : 'info';
  return `<div class="st-note ${tone}" role="status">${statusIcon(feedback)}<span>${esc(feedback.text)}</span></div>`;
}

function switchControl(action, checked, label, disabled = false) {
  return `<button type="button" class="st-switch" data-act="${action}" role="switch" aria-checked="${checked}" aria-label="${esc(label)}"${disabled ? ' disabled aria-disabled="true"' : ''}><span class="st-switch-rail" aria-hidden="true"></span></button>`;
}

function fieldValue(id, fallback = '') {
  return dirtyIds.has(id) && drafts.has(id) ? drafts.get(id) : fallback;
}

function isHttpUrl(value) {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.hostname;
  } catch (_) {
    return false;
  }
}

function parsePluginModelDailyLimit(value) {
  const raw = String(value ?? '').trim();
  if (!/^[1-9]\d*$/.test(raw)) throw Error('插件模型每日预算上限必须是大于 0 的整数。');
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed)) throw Error('插件模型每日预算上限超出可精确保存的正整数范围。');
  return parsed;
}

function pluginModelDailyLimitCard() {
  const id = 'pluginModelDailyHardLimitTokens';
  const value = fieldValue(id, cfg[id] ?? 200000);
  return `<article class="st-card">
    <div class="st-card-head"><span class="st-card-icon">${S.box}</span><div class="st-card-title"><h2>插件</h2><p>添加、移除和启停插件</p></div></div>
    <div class="st-field">
      <div class="st-field-head"><label for="${id}">插件模型每日 token 上限</label><span>正整数</span></div>
      <input id="${id}" type="number" inputmode="numeric" min="1" step="1" value="${esc(value)}" autocomplete="off" aria-describedby="pluginModelDailyLimitHelp">
      <p class="st-help" id="pluginModelDailyLimitHelp">宿主本地预算上限，不代表账号额度。调低不会删除插件原始预算，恢复上限后可继续使用。</p>
    </div>
    ${feedbackNote(pluginModelDailyLimitFeedback)}
    <div class="st-actions">
      <button type="button" class="st-btn primary" data-act="savePluginModelDailyLimit">${S.check}保存上限</button>
      <button type="button" class="st-btn" data-act="pluginManager">管理插件</button>
    </div>
  </article>`;
}

function backgroundCard() {
  if (!isAndroid) {
    return `<div class="st-callout">${S.info}<span>后台运行与系统通知仅在 Android App 中可用。</span></div>`;
  }
  const battery = !!cfg.batteryExempt;
  const keepAwake = !!cfg.keepAwakeWhileRunning;
  return `<article class="st-card">
    <div class="st-card-head">
      <span class="st-card-icon">${S.monitor}</span>
      <div class="st-card-title"><h2>运行与后台</h2></div>
    </div>
    <div class="st-list">
      <div class="st-row">
        <div class="st-row-copy"><b>任务期间保持唤醒</b><small>锁屏时继续执行，会增加耗电</small></div>
        ${switchControl('toggleTaskWake', keepAwake, '任务期间保持唤醒')}
      </div>
      <div class="st-line"></div>
      <div class="st-row">
        <div class="st-row-copy"><b>电池优化</b><small>打开系统应用设置查看后台策略</small></div>
        <button type="button" class="st-row-action" data-act="backgroundBattery" aria-label="打开系统电池优化设置"><span>${battery ? '已豁免' : '去设置'}</span>${S.fwd}</button>
      </div>
    </div>
  </article>`;
}

function notificationCard() {
  const notificationsAllowed = cfg.backgroundNotifications !== false;
  return `<article class="st-card">
    <div class="st-card-head">
      <span class="st-card-icon">${S.bell}</span>
      <div class="st-card-title"><h2>通知</h2></div>
    </div>
    <div class="st-list">
      ${isAndroid ? `<div class="st-row">
        <div class="st-row-copy"><b>系统通知</b><small>显示运行状态，并提供停止或重启入口</small></div>
        <button type="button" class="st-row-action" data-act="backgroundNotifications" aria-label="打开系统通知设置"><span>${notificationsAllowed ? '已允许' : '去开启'}</span>${S.fwd}</button>
      </div>
      <div class="st-row">
        <div class="st-row-copy"><b>读取其他 App 的通知</b><small>仅在系统授权后，向已订阅的插件提供通知</small></div>
        <button type="button" class="st-row-action" data-act="notificationAccess" aria-label="打开通知使用权设置"><span>管理</span>${S.fwd}</button>
      </div>
      ` : ''}
    </div>
    <div class="st-subsection">
      <div class="st-field">
        <div class="st-field-head"><label for="ntfyUrl">外部 ntfy 地址（可选）</label></div>
        <input id="ntfyUrl" type="url" inputmode="url" value="${esc(fieldValue('ntfyUrl', cfg.ntfyUrl || ''))}" placeholder="https://ntfy.example.com/yugang" autocomplete="off" spellcheck="false" aria-describedby="ntfyHelp">
        <p class="st-help" id="ntfyHelp">这里只保存并打开该链接；本 App 不推送通知、不自动订阅，订阅请在外部 ntfy 应用或网页中自行管理。</p>
      </div>
      <div class="st-actions">
        <button type="button" class="st-btn primary" data-act="saveNtfy">${S.check}保存地址</button>
        ${cfg.ntfyUrl ? `<button type="button" class="st-btn" data-act="openNtfy">${S.ext}在 ntfy 中打开</button>` : ''}
      </div>
    </div>
  </article>`;
}

function generalAdvanced() {
  const embedded = !isExternal();
  const nativeMode = cfg.harnessMode !== 'web';
  return `<details class="st-fold" data-fold="general-advanced"${foldOpen('general-advanced') ? ' open' : ''}>
    <summary>
      <span class="st-fold-icon">${S.gear}</span>
      <span class="st-fold-copy"><b>高级</b><small>工作区${embedded ? '与运行诊断' : ''}</small></span>
      <span class="st-chevron" aria-hidden="true"></span>
    </summary>
    <div class="st-fold-body">
      <div class="st-setting-copy"><b>手机工作区</b><small>固定目录 · 完全访问 · 无需审批</small></div>
      ${embedded ? `<div class="st-line"></div>
      <div class="st-row">
        <div class="st-row-copy"><b>内置运行时</b><small>${esc(runtimeText())}</small></div>
      </div>
      <div class="st-actions">
        <button type="button" class="st-btn" data-act="rtDiag">${S.term}查看诊断</button>
        <button type="button" class="st-btn" data-act="rtRestart">${S.reload}重启运行时</button>
        ${diag?.log ? `<button type="button" class="st-btn" data-act="copyLog">${S.copy}复制日志</button>` : ''}
      </div>
      ${diag ? `<dl class="st-diag">
        <dt>状态</dt><dd>${esc(diag.state || '—')}${diag.detail ? ' · ' + esc(diag.detail.split('\n')[0]) : ''}</dd>
        <dt>dsh</dt><dd>${esc(diag.manifest?.dsh || '—')}</dd>
        <dt>Node.js</dt><dd>${esc(diag.manifest?.node || '—')}</dd>
        <dt>地址</dt><dd>127.0.0.1:${esc(diag.port || '')}（仅本机）</dd>
      </dl>${diag.log ? `<pre class="st-log">${esc(diag.log.split('\n').slice(-60).join('\n'))}</pre>` : ''}` : ''}` : ''}
    </div>
  </details>`;
}

async function loadModelSettings() {
  if (modelSettingsLoading) return modelSettingsLoading;
  if (dsh.state !== 'online') return;
  modelSettingsLoading = (async () => {
    try { modelSettings = await withDeadline(readDefaultModel(dshCall), 10000, '读取默认模型超时，请重试。'); modelSettingsError = ''; }
    catch (e) { modelSettingsError = errorText(e); }
    finally { modelSettingsLoading = null; render(); }
  })();
  return modelSettingsLoading;
}
function modelIdField() {
  const current = modelSettings?.view.value;
  const supported = modelSettings?.catalog?.groups?.find(g => g.id === current?.provider)?.models || [];
  const models = verifiedModels.filter(m => supported.some(s => s.id === m.id));
  const canChoose = verifiedModels.length > 0 && models.length > 0 && !keyTesting;
  return `<div class="st-field">
    <div class="st-field-head"><label for="defaultModelId">默认模型 ID</label><span>${esc(current?.provider || '对话运行时')}</span></div>
    <select id="defaultModelId" aria-label="默认模型 ID" ${!canChoose || modelSettingsSaving ? 'disabled' : ''}>
      ${!canChoose ? '<option value="">先验证 Key 并获取模型清单</option>' : models.map(m => `<option value="${esc(m.id)}" ${fieldValue('defaultModelId', current?.model || models[0]?.id) === m.id ? 'selected' : ''}>${esc(m.id)}</option>`).join('')}
    </select>
    <p class="st-help">新会话使用此默认模型；已有会话保留明确选择的模型，也可在对话输入框切换。</p>
    ${modelSettingsError ? `<p class="st-help" role="alert">${esc(modelSettingsError)}</p>` : ''}
    <div class="st-actions"><button type="button" class="st-btn" data-act="saveDefaultModel" ${!canChoose || !modelSettings?.writable || modelSettingsSaving ? 'disabled' : ''}>${modelSettingsSaving ? '正在保存…' : '保存模型'}</button><button type="button" class="st-btn" data-act="reloadModelSettings" ${modelSettingsSaving ? 'disabled' : ''}>重新读取</button></div>
  </div>`;
}

function modelCard() {
  const configured = !!cfg.deepseekKeySet;
  const external = isExternal();
  const state = !configured ? '未配置' : external ? '本机已保存 · 外部运行时使用其自身 Key' : rt.state === 'ready' ? '已配置 · 运行中' : '已配置';
  return `<article class="st-card">
    <div class="st-card-head">
      <span class="st-card-icon">${S.gear}</span>
      <div class="st-card-title"><h2>模型</h2><p>DeepSeek API 与内置对话</p></div>
      <span class="st-card-state">${stateLine(configured ? (rt.state === 'ready' || external ? 'g' : 'gr') : 'o', state)}</span>
    </div>
    ${feedbackNote(modelTest)}
    <div class="st-field">
      <div class="st-field-head"><label for="deepseekKey">DeepSeek API Key</label><span>${configured ? '已设置 · 留空保持不变' : '未设置'}</span></div>
      <input id="deepseekKey" type="password" value="${esc(fieldValue('deepseekKey', ''))}" placeholder="${configured ? '留空表示不修改' : 'sk-…'}" autocomplete="new-password" spellcheck="false" aria-describedby="deepseekKeyHelp" ${keyTesting || modelSettingsSaving ? 'disabled' : ''}>
      <p class="st-help" id="deepseekKeyHelp">Key 保存在应用私有加密存储，只用于本机对话运行时和宿主模型接口，不回显给页面。</p>
    </div>
    <div class="st-actions">
      <button type="button" class="st-btn primary" data-act="saveModelKey" ${keyTesting || modelSettingsSaving ? 'disabled' : ''}>${S.check}${keyTesting ? '正在验证…' : '验证 Key 并获取模型'}</button>
      ${configured ? `<button type="button" class="st-btn danger" data-act="${modelTest?.confirm ? 'clearModelKeyConfirmed' : 'clearModelKey'}" ${keyTesting || modelSettingsSaving ? 'disabled' : ''}>${S.trash}${modelTest?.confirm ? '确认清除' : '清除 Key'}</button>` : ''}
    </div>
    ${modelIdField()}
  </article>`;
}

function generalPanel() {
  return `<section class="st-panel" id="st-panel-general" role="region" aria-labelledby="st-tab-general">
    ${modelCard()}
    ${backgroundCard()}
    ${notificationCard()}
    ${pluginModelDailyLimitCard()}
    ${generalAdvanced()}
  </section>`;
}

function connectionPanel() {
  const external = isExternal();
  const [dd, dt] = DSH_STATE[dsh.state] || DSH_STATE.idle;
  const dshStatus = dsh.state === 'idle' ? '尚未连接' : dt;
  const paired = cfg.dshPaired ? `已配对${cfg.dshPairedAt ? ' · ' + ago(cfg.dshPairedAt) : ''}` : '未配对';
  return `<section class="st-panel" id="st-panel-connection" role="region" aria-labelledby="st-tab-connection">
    <details class="st-fold" data-fold="dsh-advanced"${foldOpen('dsh-advanced') ? ' open' : ''}>
      <summary>
        <span class="st-fold-icon">${S.link}</span>
        <span class="st-fold-copy"><b>高级</b><small>外部 dsh · ${external ? dshStatus : '未启用'}</small></span>
        <span class="st-chevron" aria-hidden="true"></span>
      </summary>
      <div class="st-fold-body">
        <div class="st-setting-row">
          <div class="st-setting-copy"><b>运行方式</b><small>内置运行时，或连接本机 DSHBox / Termux</small></div>
          <div class="st-seg" role="group" aria-label="dsh 运行方式">
            <button type="button" data-dmode="embedded" aria-pressed="${!external}">内置</button>
            <button type="button" data-dmode="external" aria-pressed="${external}">外部</button>
          </div>
        </div>
        <div class="st-line"></div>
        ${external ? `<div class="st-state-line">${stateLine(dd, dshStatus, paired)}</div>
        <div class="st-field">
          <div class="st-field-head"><label for="dshUrl">外部 dsh 地址</label><span>仅限 127.0.0.1 / localhost</span></div>
          <input id="dshUrl" type="url" inputmode="url" value="${esc(fieldValue('dshUrl', cfg.extDshUrl || 'http://127.0.0.1:3080'))}" placeholder="http://127.0.0.1:3080" autocomplete="off" spellcheck="false">
        </div>
        ${feedbackNote(dshTest)}
        <p class="st-help">修改地址会取消已有配对；在 DSHBox 里选择「鱼缸」，或粘贴启动链接重新配对。</p>
        <div class="st-actions">
          <button type="button" class="st-btn" data-act="paste">${S.link}粘贴启动链接</button>
          <button type="button" class="st-btn primary" data-act="saveDsh">${S.check}保存地址</button>
          ${cfg.dshboxInstalled ? `<button type="button" class="st-btn" data-act="openDshbox">${S.ext}打开 DSHBox</button>` : ''}
          ${cfg.dshPaired ? `<button type="button" class="st-btn danger" data-act="unpair">${S.trash}${dshTest?.confirm === 'unpair' ? '确认取消配对' : '取消配对'}</button>${dshTest?.confirm === 'unpair' ? `<button type="button" class="st-btn" data-act="cancelConfirm">取消</button>` : ''}` : ''}
        </div>` : `<div class="st-state-line">${stateLine(runtimeDot(), runtimeText())}</div>
        <p class="st-help">仅监听本机回环地址，不需要安装其它应用。</p>`}
      </div>
    </details>
  </section>`;
}

function loadingCard() {
  return `<article class="st-card" role="status" aria-live="polite">
    <div class="st-card-head">
      <span class="st-card-icon">${S.gear}</span>
      <div class="st-card-title"><h2>正在读取设置…</h2><p>请稍候</p></div>
    </div>
    <p class="st-help">正在读取本机配置。</p>
  </article>`;
}

function aboutPanel() {
  const runtime = isExternal() ? '外部 dsh' : '内置运行时';
  const version = cfg.appVersion ? 'v' + cfg.appVersion : isAndroid ? '—' : '浏览器预览';
  return `<section class="st-panel" id="st-panel-about" role="region" aria-labelledby="st-tab-about">
    <article class="st-card">
      <div class="st-card-head">
        <span class="st-card-icon">${S.info}</span>
        <div class="st-card-title"><h2>鱼缸</h2><p>版本与运行环境</p></div>
      </div>
      <dl class="st-info-list">
        <div><dt>版本</dt><dd>${esc(version)}</dd></div>
        <div><dt>对话运行时</dt><dd>${runtime}</dd></div>
      </dl>
      <div class="st-actions"><button type="button" class="st-btn" data-act="licenses">开源许可证</button></div>
      <p class="st-footnote">dsh 为预览版；内置运行时仅监听本机。凭据只保存在应用私有存储，页面不回显。</p>
    </article>
  </section>`;
}

/**
 * 读取配置：同一时刻只保留一个请求，旧 generation/旧页面的迟到结果一律丢弃，
 * 避免覆盖更新后的配置或在输入中的草稿。失败保留已有配置，只显示错误与原地重试。
 */
export function load({ force = false } = {}) {
  if (loadInFlight && !force) return loadInFlight;
  const seq = ++loadSeq;
  const scope = el;
  if (!cfgLoaded) cfgError = '';
  cfgLoading = true;
  render();
  let pending;
  pending = (async () => {
    try {
      const next = await withDeadline(native.getConfig(), 10000, '读取设置超时，请重试。');
      if (seq !== loadSeq || scope !== el) return;
      cfg = next || {};
      cfgLoaded = true;
      cfgError = '';
    } catch (e) {
      if (seq !== loadSeq || scope !== el) return;
      cfgError = errorText(e);
    } finally {
      if (seq === loadSeq && scope === el) {
        cfgLoading = false;
        if (loadInFlight === pending) loadInFlight = null;
        render();
      }
    }
  })();
  loadInFlight = pending;
  return pending;
}

export function render() {
  if (!el) return;
  if (composing) { queuedRender = true; return; }

  const scroller = el.querySelector('.st-scroll');
  const oldNav = el.querySelector('.st-tabs');
  if (oldNav) navScrollTop = oldNav.scrollTop;
  const currentScroll = scroller ? scroller.scrollTop : 0;
  if (renderedTab) scrollTops.set(renderedTab, currentScroll);
  const nextScroll = renderedTab === activeTab ? currentScroll : (scrollTops.get(activeTab) || 0);

  const active = document.activeElement;
  const focus = el.contains(active) ? focusTarget(active) : null;
  for (const input of el.querySelectorAll('input[id],select[id]')) if (dirtyIds.has(input.id)) drafts.set(input.id, input.value);
  const keepInputs = [...el.querySelectorAll('input[id],select[id]')].filter((i) => dirtyIds.has(i.id) || focus?.kind === 'input' && focus.id === i.id);

  const meta = tabMeta(activeTab);
  el.innerHTML = `<div class="st-shell${narrowMenu ? ' st-menu-view' : ''}">
    <header class="st-head"><div class="st-head-inner"><h1>设置</h1></div></header>
    <div class="st-layout">
      ${categoryNav()}
      <div class="st-main">
        <div class="st-pane-head">
          <button type="button" class="st-back" data-settings-back aria-label="返回设置分类">${S.back}<span>设置</span></button>
          <span class="st-pane-title">${esc(meta.label)}</span>
        </div>
        <div class="st-scroll"><div class="st-content">
          ${cfgError ? `<div class="st-note bad" role="alert">${statusIcon({ tone: 'bad' })}<span>读取设置失败：${esc(cfgError)}</span><button type="button" class="st-btn" data-act="reloadSettings">重试</button></div>` : ''}
          ${!cfgLoaded ? (cfgLoading ? loadingCard() : '') : panelFor(activeTab)}
        </div></div>
      </div>
    </div>
  </div>`;
  for (const button of el.querySelectorAll('[data-act]')) {
    if (busyActions.has(button.dataset.act)) button.disabled = true;
  }
  syncViewMode();

  for (const old of keepInputs) {
    const next = el.querySelector('#' + old.id);
    if (!next) continue;
    old.placeholder = next.placeholder;
    old.disabled = next.disabled;
    if (!dirtyIds.has(old.id)) old.value = next.value;
    if (typeof next.replaceWith === 'function') next.replaceWith(old);
    else if (next.parentNode) {
      // 合成 DOM（回归测试）没有 replaceWith：按原位置替换，保留输入节点与选区。
      const parent = next.parentNode;
      const index = parent.childNodes.indexOf(next);
      parent.removeChild(next);
      if (index >= 0) parent.childNodes.splice(index, 0, old);
      old.parentNode = parent;
    }
  }
  for (const d of el.querySelectorAll('details[data-fold]')) d.addEventListener('toggle', () => folds.set(d.dataset.fold, d.open));

  const nextNav = el.querySelector('.st-tabs');
  if (nextNav) nextNav.scrollTop = navScrollTop;
  const nextScroller = el.querySelector('.st-scroll');
  if (nextScroller) nextScroller.scrollTop = nextScroll;

  const focusAfter = pendingFocus;
  pendingFocus = null;
  if (focusAfter?.kind === 'tab') el.querySelector('#st-tab-' + focusAfter.id)?.focus({ preventScroll: true });
  else if (focusAfter?.kind === 'back') el.querySelector('[data-settings-back]')?.focus({ preventScroll: true });
  else if (focusAfter?.kind === 'node') el.querySelector(focusAfter.selector)?.focus({ preventScroll: true });
  else restoreFocus(focus);
  renderedTab = activeTab;
}

function focusTarget(node) {
  if (!node || !el?.contains(node)) return null;
  const base = { kind: 'other', id: node.id || '', start: null, end: null };
  if (node.matches('input[id]')) {
    let start = null, end = null;
    try { start = node.selectionStart; end = node.selectionEnd; } catch { /* 某些输入类型不支持选区 */ }
    return { kind: 'input', id: node.id, start, end };
  }
  for (const attr of ['data-act', 'data-settings-tab', 'data-settings-go', 'data-settings-back', 'data-mode', 'data-dmode']) {
    if (node.hasAttribute(attr)) return { ...base, attr, value: node.getAttribute(attr) };
  }
  if (node.tagName === 'SUMMARY') {
    const d = node.closest('details[data-fold]');
    if (d) return { ...base, fold: d.dataset.fold };
  }
  return null;
}

function restoreFocus(target) {
  if (!target) return;
  let node = null;
  if (target.kind === 'input') node = el.querySelector('#' + target.id);
  else if (target.attr) node = [...el.querySelectorAll(`[${target.attr}]`)].find((n) => n.getAttribute(target.attr) === target.value);
  else if (target.fold) node = el.querySelector(`details[data-fold="${target.fold}"] > summary`);
  if (!node) return;
  node.focus({ preventScroll: true });
  if (target.start !== null && target.end !== null && node.matches('input')) {
    try { node.setSelectionRange(target.start, target.end); } catch { /* 忽略不支持的选区 */ }
  }
}

function activateTab(id) {
  if (!el || !TABS.some((t) => t.id === id)) return;
  const wide = isWideLayout();
  if (id === activeTab) {
    if (!wide && narrowMenu) { narrowMenu = false; pendingFocus = { kind: 'back' }; render(); }
    return;
  }
  activeTab = id;
  narrowMenu = false;
  pendingFocus = wide ? { kind: 'tab', id } : { kind: 'back' };
  render();
}

function showMenu() {
  if (!el || isWideLayout()) return;
  narrowMenu = true;
  pendingFocus = { kind: 'tab', id: activeTab };
  render();
}

function clearDraft(ids, clearNodes = []) {
  for (const id of ids) {
    dirtyIds.delete(id);
    drafts.delete(id);
    if (clearNodes.includes(id)) { const node = el.querySelector('#' + id); if (node) node.value = ''; }
  }
}

function normalizeTab(id) {
  if (id === 'chat' || id === 'notifications') return 'general';
  return TABS.some((t) => t.id === id) ? id : null;
}

function openModelSettings() {
  if (!el) return;
  activeTab = 'general';
  narrowMenu = false;
  folds.set('general-advanced', false);
  pendingFocus = { kind: 'node', selector: '#deepseekKey' };
  if (!rootElement()?.classList.contains('on')) window.__pd_go?.('settings');
  else render();
}
if (typeof window !== 'undefined') window.__pd_open_model = openModelSettings;

function rootElement() { return document.getElementById('scr-settings'); }

async function act(a) {
  if (busyActions.has(a)) return;
  busyActions.add(a);
  render();
  const val = (id) => el.querySelector('#' + id)?.value?.trim() || '';
  try {
    if (a === 'reloadModelSettings') { await loadModelSettings(); return; }
    if (a === 'saveDefaultModel') {
      if (modelSettingsSaving || keyTesting) return;
      if (!verifiedModels.some(m => m.id === val('defaultModelId'))) throw Error('请先验证 Key 并选择返回的模型。');
      const model = val('defaultModelId');
      modelSettingsSaving = true; render();
      try {
        modelSettings = await saveDefaultModel(dshCall, modelSettings, model);
        clearDraft(['defaultModelId'], ['defaultModelId']);
        dsh.catalog = modelSettings.catalog;
        modelSettingsError = ''; modelTest = {text:'默认模型已保存，新会话将使用 '+modelSettings.view.value.model+'。',tone:'good'};
        toast('默认模型已保存');
      } finally { modelSettingsSaving = false; render(); }
      return;
    }
    if (a === 'saveModelKey') {
      if (keyTesting || modelSettingsSaving) return;
      const key = val('deepseekKey');
      if (key && (key.length > 8192 || /\s/.test(key))) throw Error('Key 不能包含空格或换行。');
      if (!key && !cfg.deepseekKeySet) throw Error('请先填写 DeepSeek API Key。');
      const epoch = ++keyValidationEpoch;
      keyTesting = true; verifiedModels = []; render();
      try {
        const result = await native.deepseekModels(key);
        if (epoch !== keyValidationEpoch) throw Error('Key 已变化，请重新验证。');
        if (result?.verified !== true || !Array.isArray(result.models) || !result.models.length) throw Error('服务未返回可用模型，请重试。');
        if (key) {
          if (cfg.dshMode === 'external') await native.setConfig({deepseekKey:key});
          else await saveKey(key);
        }
        clearDraft(['deepseekKey'], ['deepseekKey']);
        cfg = await refreshCfg();
        verifiedModels = result.models;
        modelTest = {text:'Key 已验证，可从下方清单选择模型。',tone:'good'};
        await loadModelSettings();
        toast('Key 已连通，模型清单已获取');
      } finally { keyTesting = false; render(); }
    } else if (a === 'clearModelKey') {
      modelTest = { text: '清除后内置对话和宿主模型接口都需要重新配置。', tone: 'bad', confirm: true };
      render();
    } else if (a === 'clearModelKeyConfirmed') {
      keyValidationEpoch++; verifiedModels = [];
      await native.setConfig({ deepseekKey: '' });
      clearDraft(['deepseekKey'], ['deepseekKey']);
      cfg = await refreshCfg();
      modelTest = { text: 'Key 已清除。', tone: 'info' };
      render(); toast('模型配置已清除');
    } else if (a === 'cancelConfirm') {
      if (dshTest?.confirm) dshTest = { ...dshTest, confirm: '' };
      render();
    } else if (a === 'reloadSettings') {
      await load();
    } else if (a === 'openDshbox') {
      const ok = await native.openDshbox();
      if (ok === false) toast('未安装 DSHBox（com.dshbox.app）');
    } else if (a === 'paste') {
      const v = await promptSheet({ title: '粘贴 DSHBox 启动链接', label: '启动链接或令牌', placeholder: 'http://127.0.0.1:3080/?token=…', ok: '配对', note: '在 DSHBox 里选择「鱼缸」，或粘贴它提供的本机启动链接。' });
      if (v) await pair(v);
    } else if (a === 'saveDsh') {
      const u = val('dshUrl').replace(/\/+$/, '');
      if (!/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(u)) {
        dshTest = { text: '只允许本机地址，例如 http://127.0.0.1:3080。', tone: 'bad' };
        render(); toast(dshTest.text); return;
      }
      await native.setConfig({ dshUrl: u });
      clearDraft(['dshUrl']);
      cfg = await native.getConfig();
      dshTest = { text: '地址已保存。', tone: 'good' };
      folds.set('dsh-advanced', true);
      toast('已保存'); startDsh(); render();
    } else if (a === 'unpair') {
      if (dshTest?.confirm !== 'unpair') {
        dshTest = { text: '取消配对后需要重新粘贴启动链接才能继续。', tone: 'bad', confirm: 'unpair' };
        render(); return;
      }
      await native.dshUnpair();
      cfg = await native.getConfig();
      dshTest = { text: '已取消配对，需要时请重新粘贴启动链接。', tone: 'info' };
      folds.set('dsh-advanced', true);
      startDsh(); render(); toast('已取消配对');
    } else if (a === 'rtRestart') {
      await retryRt(); toast('正在重启对话运行时');
    } else if (a === 'notificationAccess') {
      await native.call('notificationAccess');
    } else if (a === 'backgroundNotifications') {
      await native.backgroundSettings('notifications');
    } else if (a === 'backgroundBattery') {
      await native.backgroundSettings('battery');
    } else if (a === 'toggleTaskWake') {
      await native.setConfig({ keepAwakeWhileRunning: !cfg.keepAwakeWhileRunning });
      cfg = await native.getConfig(); render();
      toast(cfg.keepAwakeWhileRunning ? '已开启任务期间保活' : '已关闭任务期间保活');
    } else if (a === 'savePluginModelDailyLimit') {
      let limit;
      try {
        limit = parsePluginModelDailyLimit(val('pluginModelDailyHardLimitTokens'));
      } catch (e) {
        pluginModelDailyLimitFeedback = { text: errorText(e), tone: 'bad' };
        render(); toast(pluginModelDailyLimitFeedback.text); return;
      }
      await native.setConfig({ pluginModelDailyHardLimitTokens: limit });
      clearDraft(['pluginModelDailyHardLimitTokens']);
      cfg = await native.getConfig();
      pluginModelDailyLimitFeedback = { text: '插件模型每日预算上限已保存。', tone: 'good' };
      render(); toast('插件模型每日预算上限已保存');
    } else if (a === 'licenses') {
      await native.call('licenses');
    } else if (a === 'pluginManager') {
      await native.call('pluginManager');
    } else if (a === 'rtDiag') {
      diag = await native.rtDiag();
      folds.set('general-advanced', true);
      render();
    } else if (a === 'copyLog') {
      await native.copy(diag?.log || ''); toast('已复制');
    } else if (a === 'saveNtfy') {
      const url = val('ntfyUrl');
      if (url && !isHttpUrl(url)) throw Error('外部 ntfy 地址必须是有效的 http(s) URL。');
      await native.setConfig({ ntfyUrl: url });
      clearDraft(['ntfyUrl']);
      cfg = await native.getConfig(); render(); toast('链接已保存');
    } else if (a === 'openNtfy') {
      if (cfg.ntfyUrl) await native.openContent({url:cfg.ntfyUrl,title:"通知服务"});

    }
  } catch (e) {
    const text = errorText(e);
    if (['saveModelKey', 'clearModelKey', 'clearModelKeyConfirmed', 'saveDefaultModel'].includes(a)) { modelTest = { text, tone: 'bad' }; render(); }
    else if (a === 'saveDsh' || a === 'unpair') { dshTest = { text, tone: 'bad' }; folds.set('dsh-advanced', true); render(); }
    else if (a === 'savePluginModelDailyLimit') { pluginModelDailyLimitFeedback = { text, tone: 'bad' }; render(); }
    toast(text);
  } finally {
    busyActions.delete(a);
    render();
  }
}

export async function pair(input) {
  folds.set('dsh-advanced', true);
  dshTest = { text: '正在配对…', tone: 'info' };
  render();
  try {
    await native.dshPair(input);
    await load({ force: true });
    dshTest = { text: '配对成功。', tone: 'good' };
    toast('DSHBox 配对成功');
    startDsh();
  } catch (e) {
    const text = errorText(e);
    dshTest = { text: '配对失败：' + text, tone: 'bad' };
    toast('配对失败：' + text);
  }
  render();
}

export function mount(root) {
  el = root;
  wideMedia = typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(WIDE_MQ) : null;
  narrowMenu = !isWideLayout();
  if (wideMedia && typeof wideMedia.addEventListener === 'function') {
    wideMedia.addEventListener('change', () => syncViewMode());
  }
  root.addEventListener('click', (e) => {
    const back = e.target.closest('[data-settings-back]');
    if (back) { showMenu(); return; }
    const tab = e.target.closest('[data-settings-tab]');
    if (tab) { activateTab(tab.dataset.settingsTab); return; }
    const go = e.target.closest('[data-settings-go]');
    if (go) { activateTab(go.dataset.settingsGo); return; }
    const m = e.target.closest('[data-mode]');
    if (m) {
      if (m.getAttribute('aria-pressed') === 'true') return;
      native.setConfig({ harnessMode: m.dataset.mode }).then(async () => {
        cfg = await native.getConfig();
        folds.set('general-advanced', true);
        render(); startDsh(); window.__pd_harnessMode && window.__pd_harnessMode();
        toast('对话界面已切换');
      }).catch((e) => toast(errorText(e)));
      return;
    }
    const dm = e.target.closest('[data-dmode]');
    if (dm) {
      if (dm.getAttribute('aria-pressed') === 'true') return;
      native.setConfig({ dshMode: dm.dataset.dmode }).then(async () => {
        cfg = await refreshCfg();
        folds.set('dsh-advanced', true);
        render(); startDsh(); window.__pd_harnessMode && window.__pd_harnessMode();
        toast(cfg.dshMode === 'external' ? '已切换到外部 dsh' : '已切换到内置运行时');
      }).catch((e) => toast(errorText(e)));
      return;
    }
    const b = e.target.closest('[data-act]');
    if (b) act(b.dataset.act);
  });
  root.addEventListener('input', (e) => {
    const input = e.target.closest('input[id],select[id]');
    if (!input) return;
    dirtyIds.add(input.id); drafts.set(input.id, input.value);
    if(input.id === 'deepseekKey') { keyValidationEpoch++; verifiedModels = []; const select=el.querySelector('#defaultModelId'); if(select)select.disabled=true; const save=el.querySelector('[data-act=saveDefaultModel]'); if(save)save.disabled=true; }
  });
  root.addEventListener('compositionstart', () => { composing = true; });
  root.addEventListener('compositionend', () => {
    composing = false;
    if (queuedRender) { queuedRender = false; render(); }
  });
  root.addEventListener('keydown', (e) => {
    const current = e.target.closest('[data-settings-tab]');
    if (!current || !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const i = TABS.findIndex((t) => t.id === current.dataset.settingsTab);
    const step = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : -1;
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? TABS.length - 1 : (i + step + TABS.length) % TABS.length;
    el.querySelector(`#st-tab-${TABS[next].id}`)?.focus();
  });
  if (typeof window !== 'undefined' && window.addEventListener) {
    window.addEventListener('pd:settings-open', (e) => {
      const id = e.detail?.tab;
      const target = normalizeTab(id);
      if (!target) return;
      if (root.classList.contains('on')) {
        activeTab = target;
        narrowMenu = false;
        pendingFocus = focusForTab(target);
        render();
        return;
      }
      pendingOpenTab = target; activeTab = target; narrowMenu = false;
    });
  }
  on('resume', () => { if (root.classList.contains('on')) load().catch((e) => toast(errorText(e))); });
  // Agent 经 AppControl 改动公开设置后由原生转发；复用同一有界读取，不直接信任事件负载。
  on('appSettings', () => { if (root.classList.contains('on')) load({ force: true }).catch((e) => toast(errorText(e))); });
  onDsh((w) => { if (w === 'state' && root.classList.contains('on')) { render(); if(dsh.state === 'online') loadModelSettings(); } });
  onRt(() => { if (root.classList.contains('on')) render(); });
  on('paired', async (v) => {
    folds.set('dsh-advanced', true);
    if (v.ok) { dshTest = { text: '已通过 DSHBox 自动配对。', tone: 'good' }; toast('DSHBox 配对成功'); startDsh(); }
    else { const text = errorText(v.error || '配对失败'); dshTest = { text: '配对失败：' + text, tone: 'bad' }; toast('配对失败：' + text); }
    // 与 mount/onShow 共用有界读取和 generation 守卫；配对后的新读会作废更早的迟到结果。
    await load({ force: true });
  });
  load().then(() => loadModelSettings()).catch((e) => toast(errorText(e)));
}

export const onShow = () => {
  if (pendingOpenTab) {
    activeTab = pendingOpenTab;
    pendingOpenTab = null;
    narrowMenu = false;
    pendingFocus = focusForTab(activeTab);
  } else if (!isWideLayout()) narrowMenu = true;
  // mount 与 onShow 同时发生时共用同一个在途读取；离开后重进则重新读取最新配置。
  return load().then(() => loadModelSettings()).catch((e) => toast(errorText(e)));
};

export function onHide() {
  // 让离开设置页时的迟到读取失效，避免它覆盖重进后读到的新配置或正在编辑的草稿。
  loadSeq += 1;
  loadInFlight = null;
  cfgLoading = false;
}

export function onLayout() {
  syncViewMode();
}

export function back() {
  if (!isWideLayout() && !narrowMenu) { showMenu(); return true; }
  return false;
}
