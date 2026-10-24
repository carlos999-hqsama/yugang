/**
 * 启动反例：真实 app.js + rt.js，注入没有回调的原生 getConfig。
 * 锁定：外壳/导航可见、启动读取有界、失败有重试、不把读取失败当空配置，也不在配置未知时自动重启。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createDocument, El } from './dom.mjs';
import { loadModule, synthetic, makeStorage } from './loader.mjs';

function createClock(start = 1750000000000) {
  let now = start;
  let seq = 0;
  const timers = new Map();
  const schedule = (fn, ms, repeat) => {
    const id = ++seq;
    const delay = Math.max(0, Number(ms) || 0);
    timers.set(id, { fn, at: now + delay, interval: repeat ? Math.max(1, delay) : null });
    return id;
  };
  const clock = {
    now: () => now,
    setTimeout: (fn, ms) => schedule(fn, ms, false),
    clearTimeout: (id) => { timers.delete(id); },
    setInterval: (fn, ms) => schedule(fn, ms, true),
    clearInterval: (id) => { timers.delete(id); },
    async advance(ms) {
      const target = now + ms;
      for (;;) {
        let pick = null;
        for (const [id, t] of timers) {
          if (t.at > target) continue;
          if (!pick || t.at < pick.t.at || (t.at === pick.t.at && id < pick.id)) pick = { id, t };
        }
        if (!pick) break;
        now = pick.t.at;
        if (pick.t.interval) pick.t.at = now + pick.t.interval;
        else timers.delete(pick.id);
        try { pick.t.fn(); } catch (e) { console.error('[clock] timer error', e); }
        await flush();
      }
      now = target;
      await flush();
    },
  };
  return clock;
}

async function flush(times = 4) {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve));
}

function setupDom() {
  const doc = createDocument();
  const mk = (tag, id, cls = '') => {
    const el = new El(tag, doc);
    if (id) el.setAttribute('id', id);
    if (cls) el.className = cls;
    doc.body.appendChild(el);
    return el;
  };
  return {
    doc,
    rail: mk('div', 'rail'),
    tbar: mk('nav', 'tbar'),
    screens: ['harness', 'settings'].map((id) => mk('section', 'scr-' + id, 'scr')),
  };
}

/** 启动真实的 app.js，只把页面模块和原生桥替换成可观察的桩；rt.js 是真实实现。 */
async function start({ getConfig, rtStatus = async () => ({ state: 'ready' }), plugins = async () => ({plugins:[]}) }) {
  const { doc, rail, tbar, screens } = setupDom();
  const clock = createClock();
  const channels = new Map();
  const events = { getConfig: 0, setConfig: [], rtRestart: 0, rtStatus: 0, startDsh: 0, ready: 0 };
  const mountCounts = new Map();
  const dshSubs = new Set();
  const dsh = { state: 'idle', approvals: new Map(), questions: new Map(), sessions: new Map(), workspaces: [] };
  const on = (channel, fn) => {
    if (!channels.has(channel)) channels.set(channel, new Set());
    channels.get(channel).add(fn);
    return () => channels.get(channel).delete(fn);
  };
  const native = {
    getConfig: () => { events.getConfig += 1; return getConfig(); },
    setConfig: async (patch) => { events.setConfig.push(patch); return true; },
    rtStatus: () => { events.rtStatus += 1; return rtStatus(); },
    rtRestart: async () => { events.rtRestart += 1; return true; },
    ready: async () => { events.ready += 1; },
    call: async (method) => method === 'plugins' ? plugins() : true,
    dshPair: async () => true,
    dshUnpair: async () => true,
    openDshbox: async () => true,
    backgroundSettings: async () => {},
    rtDiag: async () => ({}),
  };
  const onDsh = (fn) => { dshSubs.add(fn); return () => dshSubs.delete(fn); };
  const emitDsh = (what) => { for (const fn of dshSubs) fn(what); };
  const startDsh = () => { events.startDsh += 1; };
  const S = new Proxy({}, { get: () => '<svg></svg>' });
  const page = (id) => (ctx) => synthetic(ctx, {
    mount: () => { mountCounts.set(id, (mountCounts.get(id) || 0) + 1); },
    onShow: () => {}, onHide: () => {}, render: () => {}, onLayout: () => {},
    syncOverlays: () => {}, back: () => false, pair: async () => {},
  });
  const stubs = {
    './icons.js': (ctx) => synthetic(ctx, { S, STATUS: '<div class="status"></div>' }),
    './util.js': (ctx) => synthetic(ctx, {
      esc: value => String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;'),
      $: (sel) => doc.querySelector(sel),
      $$: (sel) => doc.querySelectorAll(sel),
    }),
    './native.js': (ctx) => synthetic(ctx, { native, on, isAndroid: true, fileUrl: (p) => p, fileFromUrl: () => null }),
    './ui.js': (ctx) => synthetic(ctx, { closeMenu: () => false, closeSheet: () => false, toast: () => {} }),
    './dsh.js': (ctx) => synthetic(ctx, { dsh, startDsh, pauseDsh: () => {}, onDsh }),
    './harness.js': page('harness'),
    './settings.js': page('settings'),
  };
  const sandbox = {
    document: doc,
    window: { addEventListener: () => {}, removeEventListener: () => {}, matchMedia: () => ({ matches: true, addEventListener() {} }) },
    localStorage: makeStorage(),
    location: { search: '', href: 'http://localhost/', origin: 'http://localhost' },
    URLSearchParams,
    innerWidth: 1200,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    setInterval: clock.setInterval,
    clearInterval: clock.clearInterval,
    console,
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;
  await loadModule('app.js', { stubs, globals: sandbox });
  return { doc, rail, tbar, screens, clock, events, mountCounts, dsh, emitDsh, emit: (name) => { for(const fn of channels.get(name)||[]) fn({}); } };
}

test('getConfig 永不回调：外壳/导航立即可见，超时后显示错误与重试，且不重启运行时', async () => {
  const env = await start({ getConfig: () => new Promise(() => {}) });
  await flush(4);

  assert.match(env.rail.innerHTML, /data-tab="harness"/, '导航应先于配置读取显示');
  assert.match(env.tbar.innerHTML, /data-tab="settings"/, '底部导航也应显示');
  assert.match(env.screens[0].innerHTML, /正在读取本机配置/, '启动中必须有明确状态');
  assert.equal(env.events.getConfig, 1, '启动只发起一次配置读取');
  assert.equal(env.events.setConfig.length, 0, '读取失败不得写配置');

  await env.clock.advance(9000);
  await flush(6);

  const active = env.doc.querySelector('#scr-harness');
  assert.match(active.innerHTML, /读取启动配置失败|读取配置超时/, '超时后必须显示可理解的错误');
  assert.ok(env.doc.querySelector('[data-boot-retry]'), '必须提供重试入口');
  assert.equal(env.events.rtRestart, 0, '配置未知时不能自动重启运行时');

  // 即便此时收到 dsh unauthorized，也不能因“配置未知”触发自动重启。
  env.dsh.state = 'unauthorized';
  env.emitDsh('state');
  await flush(3);
  assert.equal(env.events.rtRestart, 0, '无配置时 dsh 失权也不得自动重启');
});

test('启动失败后点击重试：重新有界读取并只 mount/启动一次', async () => {
  let calls = 0;
  const env = await start({
    getConfig: () => {
      calls += 1;
      return calls === 1 ? new Promise(() => {}) : Promise.resolve({ dshMode: 'embedded', deepseekKeySet: true });
    },
  });
  await env.clock.advance(9000);
  await flush(6);
  const retry = env.doc.querySelector('[data-boot-retry]');
  assert.ok(retry, '首次失败应显示重试');
  retry.dispatchEvent('click', { target: retry });
  await flush(8);

  assert.equal(env.events.getConfig, 2, '重试只重新读取一次配置');
  assert.ok(!env.doc.querySelector('[data-boot-status]'), '成功后启动提示应消失');
  for (const id of ['harness', 'settings']) {
    assert.equal(env.mountCounts.get(id), 1, `${id} 只应 mount 一次`);
  }
  assert.equal(env.events.setConfig.length, 0, '启动流程不写配置');

  // 已成功读到配置后，已有的失权自动重启保护仍然保留。
  env.dsh.state = 'unauthorized';
  env.emitDsh('state');
  await flush(3);
  assert.equal(env.events.rtRestart, 1, '配置就绪且运行时失权时仍应自动重启一次');
});

test('运行状态桥接挂起也有界：稍后仍能 mount 页面并启动后台连接', async () => {
  const env = await start({
    getConfig: async () => ({ dshMode: 'embedded', deepseekKeySet: true }),
    rtStatus: () => new Promise(() => {}),
  });
  await flush(3);
  assert.match(env.screens[0].innerHTML, /正在读取本机配置/, '状态读取期间先显示启动外壳');
  await env.clock.advance(7000);
  await flush(8);
  assert.equal(env.events.rtStatus, 1, '运行状态只读取一次');
  assert.equal(env.mountCounts.get('harness'), 1, '状态超时不应永久挡住页面 mount');
  assert.equal(env.events.getConfig, 1, '不应因状态超时重复读取配置');
});


test('插件启停事件不在宿主挂载任何业务页面', async () => {
  const env=await start({getConfig:async()=>({dshMode:'embedded'})});
  await flush(8);env.emit('pluginsChanged');await flush(8);
  assert.equal(env.mountCounts.get('harness'),1);
  assert.equal(env.mountCounts.size, 2, '宿主只挂载对话与设置');
});

test('插件注册桥接挂起不阻塞核心聊天启动',async()=>{
 const env=await start({getConfig:async()=>({dshMode:'embedded'}),plugins:()=>new Promise(()=>{})});
 await flush(6);await env.clock.advance(9000);await flush(6);
 assert.equal(env.mountCounts.get('harness'),1);
 assert.ok(!env.doc.querySelector('[data-boot-retry]'));
});
