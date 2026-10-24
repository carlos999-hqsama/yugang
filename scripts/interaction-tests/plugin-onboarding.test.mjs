/** 首个插件引导：空态入口、固定 Bot 建议和真实安装记录驱动的退出条件。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { webcrypto } from 'node:crypto';
import { createDocument, El } from './dom.mjs';
import { loadModule, synthetic, makeStorage } from './loader.mjs';

const PLUGIN_BOT = '00000000-0000-4000-8000-000000000001';
const EXAMPLE = '创建一个能联网更新的 AI 新闻插件，并实际预览、测试后安装。';

function setupHarnessDom() {
  const doc = createDocument();
  const root = new El('div', doc);
  root.className = 'scr on';
  doc.body.appendChild(root);
  for (const [tag, id] of [['input', 'filepick'], ['div', 'menu'], ['div', 'menu-mask'], ['div', 'sheet'], ['div', 'toast']]) {
    const el = new El(tag, doc);
    el.setAttribute('id', id);
    doc.body.appendChild(el);
  }
  return { doc, root };
}

async function bootHarness({ deferBotList = false } = {}) {
  let releaseBots;
  const botGate = deferBotList ? new Promise(resolve => { releaseBots = resolve; }) : Promise.resolve();
  const { doc, root } = setupHarnessDom();
  const registry = {
    defaultBotId: 'default',
    bots: [
      { id: 'default', name: '日常', pinned: true, sessionIds: [] },
      { id: PLUGIN_BOT, name: '插件开发', pinned: true, sessionIds: [] },
    ],
  };
  const calls = { api: [], bots: [], toast: [] };
  const dsh = {
    state: 'online', error: '', home: '/root', clientId: 'c1',
    sessions: new Map(), workspaces: [], pinned: [], archived: new Set(), running: new Set(),
    approvals: new Map(), questions: new Map(), catalog: null,
    presets: { options: [{ value: 'read-only' }] },
  };
  const api = { prompt: async (...args) => { calls.api.push(args); return { accepted: true }; } };
  const listeners = new Set();
  const native = {
    call: async () => true,
    on: () => () => {},
    bots: async (input) => { calls.bots.push(input); await botGate; return JSON.parse(JSON.stringify(registry)); },
    getConfig: async () => ({ harnessMode: 'native', dshMode: 'embedded' }),
    setConfig: async () => ({}),
    browser: async () => true,
    dshWeb: async () => true,
    copy: async () => true,
    toast: async (text) => { calls.toast.push(text); return true; },
    openExternal: async () => true,
    openDshbox: async () => true,
    http: async () => ({ status: 200, text: '{}' }),
    fileUrl: (path) => path,
    fileFromUrl: () => null,
  };
  const window = {
    innerWidth: 1200, innerHeight: 900,
    addEventListener: () => {}, removeEventListener: () => {},
  };
  const sandbox = {
    document: doc, window, localStorage: makeStorage(), crypto: webcrypto,
    innerWidth: 1200, innerHeight: 900, navigator: { userAgent: 'node' },
    location: { origin: 'http://localhost', href: 'http://localhost/' },
    setTimeout, clearTimeout, setInterval, clearInterval,
    requestAnimationFrame: (fn) => setTimeout(() => fn(Date.now()), 0),
    ResizeObserver: class { observe() {} disconnect() {} unobserve() {} },
    FileReader: class { readAsDataURL() {} },
    URL, Event: class { constructor(type) { this.type = type; } },
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;
  const { ns } = await loadModule('harness.js', {
    stubs: {
      './dsh.js': (context) => synthetic(context, {
        dsh, api, follow: () => ({ cancel() {} }), onDsh: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
        decideApproval: async () => {}, startDsh: () => {}, reconnectNow: () => {},
        questionFor: () => null, questionsFor: () => [], matchQuestionCall: () => null,
      }),
      './native.js': (context) => synthetic(context, { native, on: native.on, isAndroid: false, fileUrl: native.fileUrl, fileFromUrl: native.fileFromUrl }),
      './settings.js': (context) => synthetic(context, { pair: async () => {} }),
      './rt.js': (context) => synthetic(context, { rt: { state: 'ready', embedded: false, keySet: true }, onRt: () => () => {}, rtLine: () => '', retryRt: async () => {} }),
    },
    globals: sandbox,
  });
  const mounting = ns.mount(root);
  if (!deferBotList) await mounting;
  return { ns, root, doc, calls, registry, mounting, releaseBots };
}

test('插件 Bot 建议只填空草稿，不自动发送且不覆盖已有文字', async () => {
  const app = await bootHarness();
  assert.equal(await app.ns.openPluginOnboarding(), true);
  assert.equal(app.ns.dev.H.botId, PLUGIN_BOT);
  assert.match(app.root.querySelector('.bn').textContent, /试试做一个新的插件，例如 AI 新闻/);

  const example = app.root.querySelector('[data-act="pluginExample"]');
  example.dispatchEvent('click', { target: example });
  const ta = app.root.querySelector('.input textarea');
  assert.equal(ta.value, EXAMPLE);
  assert.equal(app.doc.activeElement, ta);
  assert.equal(app.root.querySelector('[data-act="send"]').disabled, false, '示例只是草稿，用户可编辑后用既有链路发送');
  assert.equal(app.calls.api.length, 0, '点击示例绝不能直接发送');
  assert.ok(app.calls.bots.every((call) => call.op === 'list'), '打开建议或填示例不创建会话、不改模型');

  ta.value = '我想先做另一个插件';
  example.dispatchEvent('click', { target: example });
  assert.equal(ta.value, '我想先做另一个插件', '已有草稿不能被示例覆盖');
  assert.match(app.doc.querySelector('#toast').textContent, /已有草稿/);
  assert.equal(app.calls.api.length, 0);
});

async function bootApp(plugins) {
  const doc = createDocument();
  const mk = (tag, id, cls = '') => {
    const el = new El(tag, doc);
    el.setAttribute('id', id);
    el.className = cls;
    doc.body.appendChild(el);
    return el;
  };
  const screens = ['harness', 'plugin-guide', 'settings'].map((id) => mk('section', `scr-${id}`, 'scr'));
  const rail = mk('div', 'rail');
  mk('nav', 'tbar');
  const channels = new Map();
  const calls = { plugins: [], openBot: 0, harnessShow: 0, navigate: [] };
  let currentPlugins = plugins;
  const on = (channel, fn) => {
    if (!channels.has(channel)) channels.set(channel, new Set());
    channels.get(channel).add(fn);
    return () => channels.get(channel).delete(fn);
  };
  const harness = {
    mount() {}, render() {}, onShow() { calls.harnessShow += 1; }, onHide() {}, onLayout() {}, syncOverlays() {}, openPluginOnboarding() { calls.openBot += 1; return true; },
  };
  const native = {
    getConfig: async () => ({ dshMode: 'embedded', deepseekKeySet: true }),
    rtStatus: async () => ({ state: 'ready' }),
    ready: async () => {},
    call: async (method, args) => {
      if (method === 'plugins') {
        const result = { plugins: currentPlugins };
        calls.plugins.push({ ...args, __result: result });
        return result;
      }
      if (method === 'pluginNavigate') { calls.navigate.push(args); return true; }
      return true;
    },
  };
  const window = { innerWidth: 1200, innerHeight: 900, addEventListener: () => {}, removeEventListener: () => {} };
  const sandbox = {
    document: doc, window, localStorage: makeStorage(), location: { search: '', origin: 'http://localhost', href: 'http://localhost/' },
    innerWidth: 1200, innerHeight: 900, navigator: { userAgent: 'node' },
    setTimeout, clearTimeout, setInterval, clearInterval,
    requestAnimationFrame: (fn) => setTimeout(() => fn(Date.now()), 0),
    ResizeObserver: class { observe() {} disconnect() {} unobserve() {} },
  };
  sandbox.window.document = doc;
  sandbox.URLSearchParams = URLSearchParams;
  sandbox.globalThis = sandbox;
  const S = new Proxy({}, { get: () => '<svg></svg>' });
  const { ns } = await loadModule('app.js', {
    stubs: {
      './icons.js': (context) => synthetic(context, { S, STATUS: '<div class="status"></div>' }),
      './util.js': (context) => synthetic(context, {
        esc: (value) => String(value), $: (selector) => doc.querySelector(selector), $$: (selector) => doc.querySelectorAll(selector),
      }),
      './native.js': (context) => synthetic(context, { native, on, isAndroid: false, fileUrl: (path) => path, fileFromUrl: () => null }),
      './ui.js': (context) => synthetic(context, { closeMenu: () => false, closeSheet: () => false, toast: () => {} }),
      './dsh.js': (context) => synthetic(context, { dsh: { state: 'online', approvals: new Map(), questions: new Map(), sessions: new Map(), workspaces: [] }, startDsh: () => {}, onDsh: () => () => {} }),
      './rt.js': (context) => synthetic(context, { rt: {}, initRt: async () => {}, onRt: () => () => {} }),
      './harness.js': (context) => synthetic(context, harness),
      './settings.js': (context) => synthetic(context, { mount() {}, render() {}, onShow() {}, onHide() {}, back: () => false, pair: async () => {} }),
    },
    globals: sandbox,
  });
  const emit = (channel, value = {}) => { for (const fn of channels.get(channel) || []) fn(value); };
  const flush = async (times = 8) => { for (let i = 0; i < times; i += 1) await new Promise((resolve) => setImmediate(resolve)); };
  await flush();
  return { ns, doc, rail, screens, calls, emit, flush, setPlugins: (next) => { currentPlugins = next; } };
}

test('Web 空态只挂载一个插件入口，真实安装后原地撤掉并回到对话', async () => {
  const app = await bootApp([]);
  assert.equal(app.calls.plugins.length, 1);
  assert.equal(app.calls.plugins[0].action, 'list');
  assert.match(app.rail.innerHTML, /data-tab="plugin-guide"/);
  assert.match(app.screens[1].textContent, /还没有创建插件，快来试试吧/);

  const guide = app.rail.querySelector('[data-tab="plugin-guide"]');
  guide.dispatchEvent('click', { target: guide });
  const create = app.screens[1].querySelector('[data-plugin-create]');
  create.dispatchEvent('click', { target: create });
  assert.equal(app.calls.openBot, 1, '创建入口应打开固定插件 Bot');
  assert.ok(app.screens[0].classList.contains('on'));

  guide.dispatchEvent('click', { target: guide });
  assert.ok(app.screens[1].classList.contains('on'));
  app.setPlugins([{ id: 'news', enabled: true }]);
  app.emit('pluginsChanged');
  await app.flush();
  assert.equal(app.rail.querySelector('[data-tab="plugin-guide"]') === null, true, '有真实安装记录时撤掉引导');
  assert.ok(app.screens[0].classList.contains('on'), '正在看引导时安装后安全回到对话');
  assert.equal(app.screens[1].classList.contains('on'), false, '移除的引导不能继续盖在当前对话上');
});

test('停用仍是已安装，移除全部后空引导才恢复', async () => {
  const app = await bootApp([{ id: 'legacy', enabled: false }]);
  assert.equal(app.rail.querySelector('[data-tab="plugin-guide"]') === null, true, '停用不能恢复首次插件引导');

  app.setPlugins([]);
  app.emit('pluginsChanged');
  await app.flush();
  assert.ok(app.rail.querySelector('[data-tab="plugin-guide"]'), 'store 清空后空引导恢复');
});

test('Native 空态按安装记录而非启用状态判断，并在安装后打开首个真实插件', () => {
  const source = fs.readFileSync(new URL('../../android/app/src/main/java/com/personaldashboard/app/PluginShell.kt', import.meta.url), 'utf8');
  assert.match(source, /plugins\.length\(\) == 0/, '引导条件必须看 store 的真实安装记录');
  assert.match(source, /还没有创建插件，快来试试吧/);
  assert.match(source, /wasOnboarding && plugins\.length\(\) > 0/, '安装事件要从空引导切到真实插件');
  assert.match(source, /showCore\("harness\?plugin=create"\)/, '手机入口必须通过核心回调打开固定 Bot');
  const refresh = source.match(/fun refresh\(\)(?<code>[\s\S]*?)(?=\n    private fun onboardingView)/)?.groups?.code || '';
  assert.match(refresh, /plugins\.length\(\) == 0/, '空态由安装记录驱动');
  assert.doesNotMatch(refresh, /getBoolean\("enabled"\)[\s\S]*plugins\.length\(\) == 0/, '停用不能恢复空引导');
});

// 设置里的管理入口必须在空安装表时仍保留导入能力，不能被教学页取代。
test('Native 插件管理空态保留添加包入口，引导独立居中', () => {
 const src=fs.readFileSync(new URL('../../android/app/src/main/java/com/personaldashboard/app/PluginShell.kt',import.meta.url),'utf8');
 const manager=src.slice(src.indexOf('private fun manage()'),src.indexOf('private fun showOnboarding()'));
 assert.match(manager,/onAdd = \{ activity.choosePluginPackage\(\) \}/);
 assert.doesNotMatch(manager,/showOnboarding\(/);
 assert.match(src,/panel.isFillViewport = true[\s\S]*panel.addView\(onboardingView\(\)/);
});

test('创建路由等 Bot 列表完成后再选择，切走时不抢回页面',async()=>{
 const app=await bootHarness({deferBotList:true});
 const route=app.ns.openPluginOnboarding();
 assert.equal(app.ns.dev.H.botId,'default');
 app.releaseBots();
 assert.equal(await route,true);
 assert.equal(app.ns.dev.H.botId,PLUGIN_BOT);
 const hidden=await bootHarness({deferBotList:true});
 const stale=hidden.ns.openPluginOnboarding();
 hidden.ns.onHide();hidden.releaseBots();
 assert.equal(await stale,false);
 assert.equal(hidden.ns.dev.H.botId,'default');
});
