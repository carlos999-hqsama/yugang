// 弹出菜单 / 底部弹层 / 提示
import { esc, $ } from './util.js';
import { S } from './icons.js';
import { on } from './native.js';

let toastT = null;
export function toast(text) {
  const t = $('#toast'); t.textContent = text; t.classList.add('on');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2200);
}
on('toast', (v) => toast(v.text));

/* ---------------- 菜单：items = [{label, sub, on, icon, act, danger} | {header} | {sep:true}] */
let menuOnClose = null;
let menuAnchor = null;
let menuPrevFocus = null;

function menuEntries() { return [...document.querySelectorAll('#menu .mi')]; }

/** 菜单打开后键盘可用：上下移动、Home/End、Tab 留在菜单内、Escape 关闭并归还焦点。 */
function onMenuKey(e) {
  const m = $('#menu');
  if (!m.classList.contains('on')) return;
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeMenu(); return; }
  const items = menuEntries();
  if (!items.length) return;
  const cur = items.indexOf(document.activeElement);
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    const next = e.key === 'ArrowDown' ? cur + 1 : cur - 1;
    items[(next + items.length) % items.length].focus({ preventScroll: true });
    return;
  }
  if (e.key === 'Home') { e.preventDefault(); items[0].focus({ preventScroll: true }); return; }
  if (e.key === 'End') { e.preventDefault(); items[items.length - 1].focus({ preventScroll: true }); return; }
  if (e.key === 'Tab') {
    e.preventDefault();
    const next = cur + (e.shiftKey ? -1 : 1);
    items[(next + items.length) % items.length].focus({ preventScroll: true });
  }
}

export function menu(anchor, items, { align = 'left', onClose } = {}) {
  const m = $('#menu'), mask = $('#menu-mask');
  m.innerHTML = items.map((it, i) => it.sep ? '<div class="msep"></div>' : it.header ? `<div class="mh">${esc(it.header)}</div>`
    : `<button type="button" class="mi${it.on ? ' on' : ''}" role="menuitem" data-i="${i}" style="${it.danger ? 'color:#c0392b' : ''}">${it.icon || ''}<span>${esc(it.label)}</span>${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</button>`).join('');
  m.classList.add('on'); mask.classList.add('on');
  m.setAttribute('role', 'menu');
  const r = anchor.getBoundingClientRect(), mw = m.offsetWidth, mh = m.offsetHeight;
  let x = align === 'right' ? r.right - mw : r.left, y = r.bottom + 6;
  if (y + mh > innerHeight - 8) y = Math.max(8, r.top - mh - 6);
  x = Math.max(8, Math.min(x, innerWidth - mw - 8));
  m.style.left = x + 'px'; m.style.top = y + 'px';
  menuOnClose = onClose || null;
  menuAnchor = anchor || null;
  menuPrevFocus = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
  m.onclick = (e) => { const el = e.target.closest('.mi'); if (!el) return; const it = items[+el.dataset.i]; closeMenu(); it.act && it.act(); };
  mask.onclick = closeMenu;
  m.onkeydown = onMenuKey;
  if (anchor && anchor.setAttribute) anchor.setAttribute('aria-expanded', 'true');
  const first = m.querySelector('.mi');
  if (first) first.focus({ preventScroll: true });
}
export function closeMenu() {
  const m = $('#menu'); if (!m.classList.contains('on')) return false;
  m.classList.remove('on');
  m.removeAttribute('role');
  $('#menu-mask').classList.remove('on');
  if (menuAnchor && menuAnchor.removeAttribute) menuAnchor.removeAttribute('aria-expanded');
  const anchor = menuAnchor; menuAnchor = null;
  const back = menuPrevFocus; menuPrevFocus = null;
  const f = menuOnClose; menuOnClose = null;
  if (f) f();
  if (back && back.isConnected && typeof back.focus === 'function') back.focus({ preventScroll: true });
  return true;
}

/* ---------------- 弹层 */
let sheetClose = null;
let sheetPrevFocus = null;

function sheetFocusables(pane) {
  if (!pane) return [];
  return [...pane.querySelectorAll('a[href],button,input,select,textarea,[tabindex]')]
    .filter((n) => !n.disabled && n.getAttribute('tabindex') !== '-1' && n.tabIndex >= 0);
}

/** 弹层是模态对话框：Escape 关闭、Tab 留在弹层内、关闭后焦点回到触发控件。 */
function onSheetKey(e) {
  const sh = $('#sheet');
  if (!sh || !sh.classList.contains('on')) return;
  if (document.activeElement?.closest('#menu.on')) return;
  if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    closeSheet();
    return;
  }
  if (e.key !== 'Tab') return;
  const pane = sh.querySelector('.pane');
  const nodes = sheetFocusables(pane);
  if (!nodes.length) { e.preventDefault(); pane?.focus({ preventScroll: true }); return; }
  const first = nodes[0], last = nodes[nodes.length - 1];
  const active = document.activeElement;
  const inside = !!active && !!pane && pane.contains(active);
  if (e.shiftKey && (!inside || active === first)) { e.preventDefault(); last.focus({ preventScroll: true }); }
  else if (!e.shiftKey && (!inside || active === last)) { e.preventDefault(); first.focus({ preventScroll: true }); }
}

export function sheet({ title, body, full = false, onMount, onClose, headExtra = '' }) {
  const sh = $('#sheet'), pane = sh.querySelector('.pane');
  sheetPrevFocus = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
  pane.className = 'pane' + (full ? ' full' : '');
  pane.innerHTML = `<div class="sh">${full ? `<button type="button" class="x" data-x aria-label="返回">${S.back}</button>` : ''}<b>${esc(title || '')}</b>${headExtra}${full ? '' : `<button type="button" class="x" data-x aria-label="关闭">${S.x}</button>`}</div><div class="sb">${body || ''}</div>`;
  pane.setAttribute('role', 'dialog');
  pane.setAttribute('aria-modal', 'true');
  if (title) pane.setAttribute('aria-label', String(title));
  else pane.removeAttribute('aria-label');
  pane.tabIndex = -1;
  sh.classList.add('on');
  sheetClose = onClose || null;
  pane.querySelectorAll('[data-x]').forEach((x) => (x.onclick = closeSheet));
  sh.querySelector('.mask').onclick = closeSheet;
  document.addEventListener('keydown', onSheetKey, true);
  if (onMount) onMount(pane.querySelector('.sb'), pane);
  const focusables = sheetFocusables(pane);
  (focusables[0] || pane).focus({ preventScroll: true });
  return pane;
}
export function sheetOpen() { return $('#sheet').classList.contains('on'); }
export function closeSheet() {
  const sh = $('#sheet'); if (!sh.classList.contains('on')) return false;
  sh.classList.remove('on');
  sh.querySelector('.pane').innerHTML = '';
  document.removeEventListener('keydown', onSheetKey, true);
  const f = sheetClose; sheetClose = null;
  const back = sheetPrevFocus; sheetPrevFocus = null;
  if (f) f();
  if (back && back.isConnected && typeof back.focus === 'function') back.focus({ preventScroll: true });
  return true;
}

/** 简单输入框 */
export function promptSheet({ title, label, value = '', placeholder = '', ok = '确定', note = '' }) {
  return new Promise((resolve) => {
    let done = false;
    sheet({
      title,
      body: `<div class="prompt-body">
        <label class="prompt-label">${esc(label || '')}
        <input id="pv" class="prompt-input" type="text" value="${esc(value)}" placeholder="${esc(placeholder)}"></label>
        ${note ? `<div class="prompt-note">${note}</div>` : ''}
        <div class="acts stk end"><button type="button" class="btn" data-c>取消</button><button type="button" class="btn dk" data-ok>${esc(ok)}</button></div></div>`,
      onMount: (b) => {
        const i = b.querySelector('#pv'); setTimeout(() => i.focus(), 50);
        const fin = (v) => { if (done) return; done = true; closeSheet(); resolve(v); };
        b.querySelector('[data-ok]').onclick = () => fin(i.value.trim());
        b.querySelector('[data-c]').onclick = () => fin(null);
        i.onkeydown = (e) => { if (e.key === 'Enter') fin(i.value.trim()); };
      },
      onClose: () => { if (!done) { done = true; resolve(null); } },
    });
  });
}
