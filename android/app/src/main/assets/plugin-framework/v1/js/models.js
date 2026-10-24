// Core DeepSeek configuration sheet. Other model providers are plugin concerns.
import { native } from './native.js';
import { saveKey, refreshCfg, rt, onRt } from './rt.js';
import { S } from './icons.js';
import { esc } from './util.js';

let root, visible = false, loading = false, busy = false;
let epoch = 0, cfg = {}, feedback = null, previousFocus = null, loadError = '';
let draftKey = '';
const drafts = new Map();

function withDeadline(request, ms, message) {
  const pending = Promise.resolve(request);
  if (typeof setTimeout !== 'function') return pending;
  let timer = null;
  return new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error(message)), ms);
    pending.then(
      value => { clearTimeout(timer); resolve(value); },
      error => { clearTimeout(timer); reject(error); },
    );
  });
}

function errorText(error) {
  return String(error?.message || error || '操作失败').replace(/\bsk-[A-Za-z0-9_-]{8,}/g, 'sk-…');
}

function focusCanReturn(node) {
  if (!node || !node.isConnected) return false;
  if (typeof node.closest !== 'function') return true;
  if (node.closest('[hidden]')) return false;
  const screen = node.closest('.scr');
  return !screen || !screen.classList || screen.classList.contains('on');
}

function clearInputs() {
  draftKey = '';
  drafts.clear();
  root?.querySelectorAll('input[type=password]').forEach(node => { node.value = ''; });
}

function focusTrap(event) {
  if (!visible) return;
  if (event.key === 'Escape') {
    if (document.getElementById('sheet')?.classList.contains('on')) return;
    event.preventDefault(); closeModel(); return;
  }
  if (event.key !== 'Tab') return;
  const nodes = [...root.querySelectorAll('button:not([disabled]),input:not([disabled]),[tabindex="0"]')]
    .filter(node => node.tabIndex >= 0);
  if (!nodes.length) return;
  const first = nodes[0], last = nodes[nodes.length - 1];
  if (event.shiftKey && (document.activeElement === first || !root.contains(document.activeElement))) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus();
  }
}

function ensureRoot() {
  if (root) return;
  root = document.createElement('div');
  root.id = 'model-editor';
  root.hidden = true;
  document.body.appendChild(root);
  root.addEventListener('input', event => {
    if (event.target.id === 'model-key') draftKey = event.target.value;
  });
  root.addEventListener('click', event => {
    const button = event.target.closest('[data-model-action]');
    if (!button) return;
    const action = button.dataset.modelAction;
    if (action === 'close') closeModel();
    if (busy || loading) return;
    if (action === 'save') void saveModel();
    if (action === 'reload') void reloadModel();
    if (action === 'clear') void clearModelKey();
    if (action === 'confirm-clear') void clearModelKey(true);
  });
  document.addEventListener('keydown', focusTrap);
}

function runtimeStatus() {
  if (!cfg.deepseekKeySet) return '配置后即可开始对话';
  if (cfg.dshMode === 'external') return '当前使用外部对话连接';
  if (rt.state === 'ready') return '对话服务已就绪';
  if (rt.state === 'error') return '对话服务启动失败，可在设置中重试';
  if (rt.state === 'stopped') return '对话服务已停止';
  return '对话服务正在准备';
}

function note(tone, text, confirm = '') {
  return `<div class="md-note ${tone}" role="status">${tone === 'good' ? S.check : tone === 'bad' ? S.warn : S.info}<span>${esc(text)}</span>${confirm ? `<button type="button" class="md-text-btn" data-model-action="${confirm}">确认清除</button>` : ''}</div>`;
}

export function renderModel() {
  if (!root || !visible) return;
  const disabled = loading || busy;
  const keyState = cfg.deepseekKeySet ? '已配置 · 留空保持不变' : '未配置';
  root.innerHTML = `<button class="md-mask" data-model-action="close" aria-label="关闭模型配置" tabindex="-1"></button>
<aside class="md-panel" role="dialog" aria-modal="true" aria-labelledby="model-title">
  <header class="md-head">
    <div class="md-brand">D</div>
    <div><h2 id="model-title">DeepSeek 模型</h2><p>${esc(runtimeStatus())}</p></div>
    <button type="button" class="md-icon" data-model-action="close" aria-label="关闭模型配置">${S.x}</button>
  </header>
  <div class="md-body">
    ${loadError ? note('bad', loadError) : ''}
    ${feedback?.text ? note(feedback.tone || 'info', feedback.text, feedback.confirm || '') : ''}
    <div class="md-section-title">API Key<span>${esc(keyState)}</span></div>
    <label class="md-field"><span>DeepSeek API Key</span><input id="model-key" type="password" value="${esc(draftKey)}" placeholder="${cfg.deepseekKeySet ? '已设置 · 留空表示不修改' : 'sk-…'}" autocomplete="off" spellcheck="false" ${disabled ? 'disabled' : ''}></label>
    <p class="md-help">Key 仅保存在 Android Keystore 加密的私有配置中，用于启动本地对话运行时和宿主模型接口；不会返回给插件页面。</p>
    ${cfg.dshMode === 'external' ? '<p class="md-help">当前使用外部 dsh。Key 仍由宿主保存；外部运行时不会自动接收它。</p>' : ''}
  </div>
  <footer class="md-footer">
    <button type="button" class="md-save" data-model-action="save" ${disabled || loading ? 'disabled' : ''}>${busy ? '保存中…' : '保存'}</button>
    ${cfg.deepseekKeySet ? `<button type="button" class="md-clear" data-model-action="${feedback?.confirm ? 'confirm-clear' : 'clear'}" ${disabled ? 'disabled' : ''}>清除 Key</button>` : ''}
    <small>配置失败时保留原值</small>
  </footer>
</aside>`;
}

export async function openModel() {
  ensureRoot();
  previousFocus = document.activeElement;
  clearInputs();
  visible = true;
  root.hidden = false;
  feedback = null;
  await reloadModel();
}

async function reloadModel() {
  if (!visible) return;
  const current = ++epoch;
  loading = true; loadError = '';
  renderModel();
  root.querySelector('.md-head button')?.focus();
  try {
    const value = await withDeadline(native.getConfig(), 10000, '读取模型配置超时，请重试。');
    if (!visible || epoch !== current) return;
    cfg = value;
    clearInputs();
  } catch (error) {
    if (visible && epoch === current) loadError = errorText(error);
  } finally {
    if (visible && epoch === current) {
      loading = false;
      renderModel();
      root.querySelector('.md-head button')?.focus();
    }
  }
}

export function closeModel() {
  if (!visible) return false;
  visible = false;
  epoch++;
  loading = false;
  clearInputs();
  root.hidden = true;
  root.innerHTML = '';
  if (focusCanReturn(previousFocus)) previousFocus.focus({ preventScroll: true });
  previousFocus = null;
  feedback = null;
  loadError = '';
  return true;
}

export function modelOpen() { return visible; }

export async function saveModel() {
  if (busy || loading) return false;
  const current = epoch;
  const key = draftKey.trim();
  if (!key && !cfg.deepseekKeySet) {
    feedback = { text: '请先填写 DeepSeek API Key。', tone: 'bad' };
    renderModel();
    return false;
  }
  if (key && (key.length > 8192 || /\s/.test(key))) {
    feedback = { text: 'Key 不能包含空格或换行。', tone: 'bad' };
    renderModel();
    return false;
  }
  busy = true;
  feedback = null;
  renderModel();
  try {
    if (key) {
      if (cfg.dshMode === 'external') await native.setConfig({ deepseekKey: key });
      else await saveKey(key);
      draftKey = '';
    }
    cfg = await refreshCfg();
    if (visible && epoch === current) feedback = { text: key ? 'Key 已保存。' : 'Key 保持不变。', tone: 'good' };
    return true;
  } catch (error) {
    if (visible && epoch === current) feedback = { text: '保存失败：' + errorText(error), tone: 'bad' };
    return false;
  } finally {
    busy = false;
    if (visible) renderModel();
  }
}

export async function clearModelKey(confirm = false) {
  if (busy || loading || !cfg.deepseekKeySet) return false;
  if (!confirm) {
    feedback = { text: '清除后内置对话与宿主模型接口都需要重新配置。', tone: 'bad', confirm: 'confirm-clear' };
    renderModel();
    return false;
  }
  const current = epoch;
  busy = true;
  feedback = null;
  renderModel();
  try {
    await native.setConfig({ deepseekKey: '' });
    cfg = await refreshCfg();
    rt.keySet = false;
    clearInputs();
    if (visible && epoch === current) feedback = { text: 'Key 已清除。', tone: 'good' };
    return true;
  } catch (error) {
    if (visible && epoch === current) feedback = { text: '清除失败：' + errorText(error), tone: 'bad' };
    return false;
  } finally {
    busy = false;
    if (visible) renderModel();
  }
}

let watchingRt = false;
export function watchModelRuntime() {
  if (watchingRt) return;
  watchingRt = true;
  onRt(() => { if (visible) renderModel(); });
}
