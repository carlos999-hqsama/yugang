import assert from 'node:assert/strict';
import {test} from 'node:test';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {installDom} from './dom.mjs';

const pageUrl = pathToFileURL(path.resolve('android/app/src/main/assets/plugin-framework/v1/page.js')).href;
const componentsUrl = pathToFileURL(path.resolve('android/app/src/main/assets/plugin-framework/v1/js/components.js')).href;

test('page framework owns lifecycle, layout, data scheduling and navigation', async () => {
  const dom = installDom({width: 601});
  const {boot, getPage} = await import(pageUrl);
  const events = [];
  globalThis.__pdSyntheticEvents = events;
  const entrySource = `
    import {definePage} from ${JSON.stringify(pageUrl)};
    export default definePage({
      data: {count: 0},
      init() { globalThis.__pdSyntheticEvents.push('init'); },
      mount(ctx) { ctx.root.dataset.mounted = 'yes'; globalThis.__pdSyntheticEvents.push('mount'); },
      render(ctx) { globalThis.__pdSyntheticEvents.push('render:' + ctx.data.count); ctx.root.dataset.count = String(ctx.data.count); },
      start() { globalThis.__pdSyntheticEvents.push('start'); },
      stop() { globalThis.__pdSyntheticEvents.push('stop'); },
      onShow() { globalThis.__pdSyntheticEvents.push('show'); },
      onHide() { globalThis.__pdSyntheticEvents.push('hide'); },
      onResize() { globalThis.__pdSyntheticEvents.push('resize'); },
      onUnload() { globalThis.__pdSyntheticEvents.push('unload'); },
      back() { return true; },
    });
  `;
  const entry = `data:text/javascript,${encodeURIComponent(entrySource)}`;
  const returned = await boot({entry, styles: [], pluginId: 'synthetic'});
  assert.equal(returned, getPage()?.context);
  assert.deepEqual(events.slice(0, 4), ['init', 'mount', 'start', 'show']);
  assert.equal(dom.document.getElementById('plugin-root'), returned.root);
  assert.equal(dom.body.classList.contains('inner'), true);

  const page = getPage();
  page.setData({count: 1});
  page.setData({count: 2});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.count, 2);
  assert.equal(page.context.data.count, 2);
  assert.equal(returned.root.dataset.count, '2');
  assert.deepEqual(events.filter(value => value.startsWith('render:')), ['render:2']);

  assert.equal(await dom.window.__pd_plugin_lifecycle(false), true);
  assert.equal(events.at(-2), 'hide');
  assert.equal(events.at(-1), 'stop');
  assert.equal(await dom.window.__pd_plugin_lifecycle(true), true);
  assert.equal(events.at(-2), 'start');
  assert.equal(events.at(-1), 'show');
  assert.equal(dom.window.__pd_back(), true);

  dom.window.innerWidth = 599;
  for (const handler of dom.window.listeners.get('resize')) handler();
  assert.equal(dom.body.classList.contains('cover'), true);
  assert.equal(events.at(-1), 'resize');

  await dom.window.__pd_go('settings');
  assert.equal(dom.nativeCalls.at(-1).method, 'pluginNavigate');
  assert.deepEqual(dom.nativeCalls.at(-1).args, {id: 'settings'});

  await dom.window.__pd_plugin_unload();
  assert.equal(events.at(-1), 'unload');
  assert.equal(getPage(), null);
});

test('shared components return DOM and dispatch callbacks', async () => {
  installDom({width: 700});
  const {Button, Card, List, Text, Input} = await import(componentsUrl);
  let clicked = 0;
  const button = Button({text: 'OK', variant: 'primary', onClick: () => clicked++});
  button.dispatchEvent({type: 'click'});
  assert.equal(clicked, 1);
  assert.equal(button.className, 'pf-button primary');

  const card = Card({title: '标题', subtitle: '说明', children: [Text('内容')]});
  assert.equal(card.className, 'pf-card');
  assert.equal(card.children.length, 2);

  let selected;
  const list = List({items: [{title: 'A', subtitle: 'B'}], onSelect: value => selected = value.title});
  list.children[0].dispatchEvent({type: 'click'});
  assert.equal(selected, 'A');

  let value = '';
  const input = Input({value: 'x', onInput: next => value = next});
  input.dispatchEvent({type: 'input', target: {value: 'y'}});
  assert.equal(value, 'y');
});

test('hide during asynchronous startup prevents stale show and stops before restart', async () => {
  const dom = installDom({width: 700});
  const {boot, getPage} = await import(pageUrl);
  const events = [];
  let release;
  globalThis.__pfRace = {events, start: () => new Promise(resolve => {release = resolve;})};
  const entry = `data:text/javascript,${encodeURIComponent(`
    import {definePage} from ${JSON.stringify(pageUrl)};
    export default definePage({
      start() { globalThis.__pfRace.events.push('start'); return globalThis.__pfRace.start(); },
      onShow() { globalThis.__pfRace.events.push('show'); },
      onHide() { globalThis.__pfRace.events.push('hide'); },
      stop() { globalThis.__pfRace.events.push('stop'); },
    });
  `)}`;
  const booted = boot({entry, pluginId: 'race'});
  while (!release) await new Promise(resolve => setImmediate(resolve));
  const page = getPage();
  const hidden = page.hide();
  release();
  await booted;
  await hidden;
  assert.deepEqual(events, ['start', 'hide', 'stop']);
  assert.equal(page.visible, false);
  globalThis.__pfRace.start = async () => {};
  await page.show();
  assert.deepEqual(events.slice(-2), ['start', 'show']);
  assert.equal(dom.document.getElementById('plugin-root').classList.contains('page-surface'), true);
  await dom.window.__pd_plugin_unload();
});
