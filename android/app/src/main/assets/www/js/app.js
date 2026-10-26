// 应用外壳：内外屏均使用左侧导航，外屏缩短标签；底部留给聊天输入，折叠时不重建页面。
import { S, STATUS } from './icons.js';
import {fishSvg} from './bot-fish.js';
import { $, $$, esc } from './util.js';
import { native, on, isAndroid } from './native.js';
import { closeMenu, closeSheet, toast } from './ui.js';
import { startDsh, dsh, onDsh } from './dsh.js';
import { initRt, rt } from './rt.js';
import * as harness from './harness.js';
import * as settings from './settings.js';
import * as pluginGuide from './plugin-guide.js';

const ALL_TABS = [
  { id: 'harness', label: '对话', short: '对话', icon: S.hchat, mod: harness },
  { id: 'plugin-guide', label: '插件', icon: S.box, mod: pluginGuide },
  { id: 'settings', label: '设置', icon: S.gear, mod: settings },
];
let TABS = ALL_TABS.filter((t) => t.id !== 'plugin-guide');
const mountedTabs = new Set();
let pluginListSeq = 0;
let pendingPluginBot = false;
if (isAndroid) document.body.classList.add('native-platform');
const params = new URLSearchParams(location.search);
let tab = ['harness','settings'].includes(params.get('tab') || localStorage.getItem('pd.tab')) ? (params.get('tab') || localStorage.getItem('pd.tab')) : 'harness';
let layout = null;

function applyLayout() {
  const inner = innerWidth >= 600;
  const next = inner ? 'inner' : 'cover';
  if (next === layout) return false;
  layout = next;
  document.body.classList.toggle('inner', inner);
  document.body.classList.toggle('cover', !inner);
  return true;
}
function renderNav() {
  const ri = (t) => `<div class="ri${t.id === tab ? ' on' : ''}" role="button" tabindex="0" aria-label="${t.label}" title="${t.label}" data-tab="${t.id}">${t.icon}<span class="nav-full">${t.label}</span><span class="nav-short">${t.short || t.label}</span></div>`;
  $('#rail').innerHTML = `<div class="logo" role="button" tabindex="0" aria-label="回到对话" data-tab="harness"><div>${fishSvg('whale')}</div><span>鱼缸</span></div>${TABS.filter(t => t.id !== 'settings').map(ri).join('')}<div style="flex:1"></div>${ri(TABS.find(t => t.id === 'settings'))}<div class="av" data-tab="settings">我</div>`;
  $('#tbar').innerHTML = TABS.map(ri).join('');
  badge();
}
function badge() {
  // 待审批的 Harness 请求，用小红点提示
  const hz = dsh.approvals.size + [...dsh.questions.values()].filter(q => q.state === 'open' || q.state === 'continued').length;
  for (const [id, n] of [['harness', hz]]) {
    $$(`.ri[data-tab="${id}"]`).forEach((r) => { r.style.position = 'relative'; let d = r.querySelector('.nd'); if (n && !d) { d = document.createElement('i'); d.className = 'nd'; d.style.cssText = 'position:absolute;top:4px;right:14px;width:7px;height:7px;border-radius:50%;background:#d4493f;border:1.5px solid #efeff1'; r.appendChild(d); } if (!n && d) d.remove(); });
  }
}
function go(id) {
  const route = String(id || '');
  const openPluginBot = /^harness\?(?:.*&)?plugin=create(?:&|$)/.test(route);
  if (openPluginBot) id = 'harness';
  if (!TABS.some((t) => t.id === id)) { native.call('pluginNavigate',{id}).catch(e=>toast(e.message)); return; }
  const prev = tab; tab = id; localStorage.setItem('pd.tab', id);
  for (const t of TABS) $('#scr-' + t.id).classList.toggle('on', t.id === id);
  renderNav();
  // 启动读取尚未完成时只切换外壳；页面模块还没 mount，不能回调它们的 onShow。
  if (booting) {
    pendingPluginBot = openPluginBot;
    placeBootNotice();
    return;
  }
  const p = TABS.find((t) => t.id === prev); if (p && prev !== id && p.mod.onHide) p.mod.onHide();
  const m = TABS.find((t) => t.id === id).mod; if (m.onShow) m.onShow(); else if (m.render) m.render();
  harness.syncOverlays();
  if (openPluginBot) harness.openPluginOnboarding();
}
window.__pd_go = go;
window.__pd_plugin_surface = () => {
  TABS.find(t=>t.id===tab)?.mod.onHide?.();
  for (const t of TABS) $('#scr-' + t.id)?.classList.remove('on');
  harness.syncOverlays();
};
window.__pd_back = () => {
  if (closeMenu()) return true;
  if (closeSheet()) { harness.syncOverlays(); return true; }
  if (tab === 'settings' && settings.back()) return true;
  if (tab === 'harness' && harness.back()) return true;
  if (tab !== 'harness') { go('harness'); return true; }
  return false;
};
on('insets', (v) => { const r = document.documentElement.style; for (const k of ['top', 'bottom', 'left', 'right']) r.setProperty('--sa' + k[0], (v[k] || 0) + 'px'); harness.syncOverlays(); });
on('pairLink', (v) => settings.pair(v.input));
on('navigate', (v) => go(v.tab));
on('pluginsChanged', () => { void syncPluginGuide(); });

onDsh((w) => { if (w === 'approvals' || w === 'questions') { badge(); if (dsh.approvals.size && tab !== 'harness') toast('对话有待批准的操作'); } });

if (!isAndroid) window.__pd = { dsh, go: (t) => go(t), harness };
let booting = true, mounted = false, started = false, retrying = false, bootNotice = null;

async function syncPluginGuide() {
  // Android 左侧导航由 Native PluginShell 持有；Web 侧不重复挂载同一入口。
  if (isAndroid) return;
  const seq = ++pluginListSeq;
  let empty = false;
  try {
    const list = await native.call('plugins', { action: 'list' });
    empty = !list?.plugins?.length;
  } catch (e) {
    // 首次读取失败时不臆测空设备，避免给已装插件用户重复引导。
    return;
  }
  if (seq !== pluginListSeq) return;
  setPluginGuide(empty);
}

function setPluginGuide(empty) {
  const hasGuide = TABS.some((t) => t.id === 'plugin-guide');
  if (hasGuide === empty) return;
  let leftGuide = false;
  if (empty) {
    TABS = [...ALL_TABS];
  } else {
    if (tab === 'plugin-guide') {
      leftGuide = true;
      TABS.find((t) => t.id === tab)?.mod.onHide?.();
      tab = 'harness';
      localStorage.setItem('pd.tab', tab);
    }
    TABS = ALL_TABS.filter((t) => t.id !== 'plugin-guide');
  }
  if (mounted) {
    const guide = TABS.find((t) => t.id === 'plugin-guide');
    if (guide && !mountedTabs.has(guide.id)) {
      guide.mod.mount($('#scr-' + guide.id));
      mountedTabs.add(guide.id);
    }
    renderNav();
    for (const t of ALL_TABS) $('#scr-' + t.id).classList.toggle('on', t.id === tab);
    if (leftGuide) TABS.find((t) => t.id === tab)?.mod.onShow?.();
    harness.syncOverlays();
  }
}

function showBootNotice(message, failed = false) {
  if (!bootNotice) {
    bootNotice = document.createElement('div');
    bootNotice.id = 'pd-boot-status';
    bootNotice.setAttribute('aria-live', 'polite');
    bootNotice.style.cssText = 'padding:28px 24px;color:#8a8a90;font-size:14px;line-height:1.8;max-width:560px';
    bootNotice.addEventListener('click', (e) => {
      if (e.target.closest('[data-boot-retry]')) retryBoot();
    });
  }
  bootNotice.setAttribute('role', failed ? 'alert' : 'status');
  bootNotice.innerHTML = `<p style="margin:0 0 6px;color:#1d1d1f;font-size:16px">${failed ? '读取启动配置失败' : '正在启动…'}</p><p style="margin:0">${esc(message)}</p>${failed ? '<button type="button" class="btn dk" data-boot-retry style="margin-top:14px">重试</button>' : ''}`;
  placeBootNotice();
}

function placeBootNotice() {
  if (!bootNotice) return;
  const scr = $('#scr-' + tab) || $('#scr-harness');
  if (booting) for (const t of TABS) $('#scr-' + t.id)?.classList.toggle('on', t.id === tab);
  if (scr && bootNotice.parentNode !== scr) scr.appendChild(bootNotice);
}

function clearBootNotice() {
  if (bootNotice && bootNotice.parentNode) bootNotice.parentNode.removeChild(bootNotice);
  bootNotice = null;
}

/** 配置读取成功后只 mount/启动一次；失败重试走同一入口，避免重复挂载和重复后台任务。 */

async function startApp() {
  if (!mounted) {
    clearBootNotice();
    for (const t of TABS) { t.mod.mount($('#scr-' + t.id)); mountedTabs.add(t.id); }
    mounted = true;
    booting = false;
    go(tab);
    if (pendingPluginBot) {
      pendingPluginBot = false;
      harness.openPluginOnboarding();
    }
    window.addEventListener('resize', () => {
      if (applyLayout()) { closeMenu(); renderNav(); }
      // 同为内屏的横竖切换也会重排正文/分栏，不能只在600px模式切换时通知。
      for (const t of TABS) t.mod.onLayout && t.mod.onLayout();
      harness.syncOverlays();
    });
  }
  booting = false;
  if (started) return;
  started = true;
  await native.ready();
  startDsh();
  void syncPluginGuide();
}

async function retryBoot() {
  if (retrying) return;
  retrying = true;
  showBootNotice('正在重新读取配置…');
  try {
    await initRt();
    retrying = false;
    await startApp();
  } catch (e) {
    retrying = false;
    showBootNotice(rt.configError || String(e && e.message || '读取配置超时，请重试。'), true);
  }
}

async function boot() {
  for (const nav of [$('#rail'), $('#tbar')]) {
    const activate = (e) => {
      const item = e.target.closest('[data-tab]');
      if (item && nav.contains(item)) go(item.dataset.tab);
    };
    nav.addEventListener('click', activate);
    nav.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(e); }
    });
  }
  if (params.get('frame') === '1') { document.body.classList.add('frame'); $('#fstatus').outerHTML = STATUS.replace('class="status"', 'class="status dev-frame" id="fstatus"'); }
  applyLayout();
  // 先画外壳和导航：配置桥接没有回调时也不会整页空白。
  renderNav();
  showBootNotice('正在读取本机配置…');
  try {
    // initRt 内部有界读取配置/运行状态；超时会明确失败，便于这里显示错误和重试。
    await initRt();
  } catch (e) {
    showBootNotice(rt.configError || String(e && e.message || '读取配置超时，请重试。'), true);
    return;
  }
  try { await startApp(); } catch (e) { showBootNotice(e.message || '插件启动失败，可重试', true); }
}
boot();
