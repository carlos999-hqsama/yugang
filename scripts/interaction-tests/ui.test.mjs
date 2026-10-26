/**
 * 弹层与菜单的键盘/焦点回归：菜单项可聚焦、方向键移动、Escape 关闭并归还焦点；
 * 弹层是模态对话框，Escape 可关，关闭后焦点回到触发控件。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createDocument, El } from './dom.mjs';
import { loadModule, synthetic } from './loader.mjs';

async function boot() {
  const doc = createDocument();
  const sheet = new El('div', doc);
  sheet.setAttribute('id', 'sheet');
  const mask = new El('div', doc); mask.className = 'mask'; sheet.appendChild(mask);
  const pane = new El('div', doc); pane.className = 'pane'; sheet.appendChild(pane);
  doc.body.appendChild(sheet);
  for (const id of ['menu', 'menu-mask', 'toast']) {
    const el = new El('div', doc); el.setAttribute('id', id); doc.body.appendChild(el);
  }
  const sandbox = {
    document: doc,
    window: { innerWidth: 1200, innerHeight: 900, addEventListener() {}, removeEventListener() {} },
    innerWidth: 1200,
    innerHeight: 900,
    console,
    setTimeout,
    clearTimeout,
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;
  const { ns } = await loadModule('ui.js', {
    stubs: { './native.js': (ctx) => synthetic(ctx, { on: () => () => {} }) },
    globals: sandbox,
  });
  return { ns, doc };
}

const key = (el, k) => el.dispatchEvent('keydown', { key: k, target: el, preventDefault() { this.defaultPrevented = true; } });

test('菜单项是可聚焦按钮，打开后有焦点，方向键可移动', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  doc.body.appendChild(anchor);
  anchor.focus();
  const done = [];
  ns.menu(anchor, [
    { label: '第一项', act: () => done.push('a') },
    { label: '第二项', act: () => done.push('b') },
  ]);
  const menu = doc.getElementById('menu');
  const items = menu.querySelectorAll('.mi');
  assert.equal(items.length, 2);
  for (const item of items) {
    assert.equal(item.tagName, 'button', '菜单项必须是原生按钮');
    assert.equal(item.getAttribute('role'), 'menuitem');
  }
  assert.equal(doc.activeElement, items[0], '打开后焦点应落在第一项');
  key(items[0], 'ArrowDown');
  assert.equal(doc.activeElement, items[1], '方向键应移动焦点');
  key(items[1], 'ArrowUp');
  assert.equal(doc.activeElement, items[0], '方向键应能回退');
});

test('Escape 关闭菜单并把焦点还给触发控件', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  doc.body.appendChild(anchor);
  anchor.focus();
  ns.menu(anchor, [{ label: '第一项', act: () => {} }]);
  const menu = doc.getElementById('menu');
  key(menu.querySelector('.mi'), 'Escape');
  assert.equal(menu.classList.contains('on'), false, 'Escape 必须关闭菜单');
  assert.equal(doc.activeElement, anchor, '关闭后焦点应回到触发控件');
});

test('菜单点击仍然执行对应动作', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  doc.body.appendChild(anchor);
  const done = [];
  ns.menu(anchor, [{ label: '第一项', act: () => done.push('a') }, { label: '第二项', act: () => done.push('b') }]);
  const items = doc.getElementById('menu').querySelectorAll('.mi');
  items[1].dispatchEvent('click', { target: items[1] });
  assert.deepEqual(done, ['b']);
  assert.equal(doc.getElementById('menu').classList.contains('on'), false, '执行后菜单关闭');
});

test('弹层是模态对话框，Escape 可关闭，关闭后焦点回到触发控件', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  doc.body.appendChild(anchor);
  anchor.focus();
  const pane = ns.sheet({ title: '测试弹层', body: '<button type="button" data-x>关闭</button>' });
  assert.equal(pane.getAttribute('role'), 'dialog');
  assert.equal(pane.getAttribute('aria-modal'), 'true');
  assert.ok(pane.contains(doc.activeElement), '打开后焦点应在弹层内');
  key(doc.activeElement, 'Escape');
  assert.equal(ns.sheetOpen(), false, 'Escape 必须关闭弹层');
  assert.equal(doc.activeElement, anchor, '关闭后焦点应回到触发控件');
});

test('弹层内 Tab 不会跑到弹层外面', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  doc.body.appendChild(anchor);
  anchor.focus();
  const pane = ns.sheet({ title: '弹层', body: '<button type="button" id="first">一</button><button type="button" id="last">二</button>' });
  const first = pane.querySelector('#first');
  const last = pane.querySelector('#last');
  first.focus();
  key(last, 'Tab');
  assert.equal(doc.activeElement, first, '在最后一个可聚焦元素上按 Tab 应回到第一个');
});

test('弹层内打开菜单时，Escape 由菜单处理且不连带关闭弹层', async () => {
  const { ns, doc } = await boot();
  const anchor = new El('button', doc);
  const pane = ns.sheet({ title: '弹层', body: '<button type="button" id="inside">弹层按钮</button>' });
  pane.appendChild(anchor);
  anchor.focus();
  ns.menu(anchor, [{ label: '第一项', act: () => {} }, { label: '第二项', act: () => {} }]);
  const menu = doc.getElementById('menu');
  const item = menu.querySelector('.mi');
  key(item, 'Escape');
  assert.equal(menu.classList.contains('on'), false, '菜单应处理 Escape');
  assert.equal(ns.sheetOpen(), true, '弹层不能被菜单的 Escape 连带关闭');
  assert.equal(doc.activeElement, anchor, '菜单关闭后焦点回到弹层内触发控件');
});
