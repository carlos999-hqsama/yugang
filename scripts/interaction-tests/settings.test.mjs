/**
 * 设置页回归：首次读取失败不空白（有错误与重试）、高风险清除需要两步确认。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createDocument, El } from './dom.mjs';
import { loadModule, synthetic, makeStorage } from './loader.mjs';

/** 设置页测试使用等价虚拟时钟，避免依赖业务插件回归。 */
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

async function flush(times = 3) {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve));
}

function setupDom() {
  const doc = createDocument();
  const root = new El('div', doc);
  root.className = 'scr on';
  doc.body.appendChild(root);
  return { doc, root };
}

async function boot({ cfg = {}, getConfig, discoverModels, setConfig } = {}) {
  const { doc, root } = setupDom();
  const clock = createClock();
  const state = {
    cfg: {
      appVersion: 'core-test', deepseekKeySet: true,
      dshMode: 'external', extDshUrl: 'http://127.0.0.1:3080', dshPaired: true,
      dshboxInstalled: true, harnessMode: 'native', keepAwakeWhileRunning: true,
      backgroundNotifications: true, ntfyUrl: '', ...cfg,
    },
    setConfig: [],
    events: [],
    toasts: [],
    channels: new Map(),
  };
  const on = (channel, fn) => {
    if (!state.channels.has(channel)) state.channels.set(channel, new Set());
    state.channels.get(channel).add(fn);
    return () => state.channels.get(channel).delete(fn);
  };
  state.emit = (channel, value) => { for (const fn of state.channels.get(channel) || []) fn(value); };
  const native = {
    on,
    isAndroid: true,
    getConfig: getConfig || (async () => ({ ...state.cfg })),
    setConfig: setConfig || (async (patch) => { state.events.push("save-key"); state.setConfig.push(patch); Object.assign(state.cfg, patch); return true; }),
    deepseekModels: async key => { state.events.push("verify-key"); return discoverModels ? discoverModels(key) : {verified:true,models:[{id:"deepseek-flash"},{id:"deepseek-v4-pro"}]}; },
    rtDiag: async () => ({ state: 'ready', log: '' }),
    copy: async () => true,
    openDshbox: async () => true,
    dshPair: async () => true,
    dshUnpair: async () => { state.unpaired = (state.unpaired || 0) + 1; return true; },
    backgroundSettings: async () => {},
    openExternal: async () => {},
  };
  const sandbox = {
    document: doc,
    window: { matchMedia: () => ({ matches: true, addEventListener() {} }), addEventListener() {}, __pd_open_model() {} },
    URL,
    localStorage: makeStorage(),
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    setInterval: clock.setInterval,
    clearInterval: clock.clearInterval,
    console,
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;
  const { ns } = await loadModule('settings.js', {
    stubs: {
      './native.js': (ctx) => synthetic(ctx, { native, on, isAndroid: true }),
      './ui.js': (ctx) => synthetic(ctx, { toast: (t) => state.toasts.push(String(t)), promptSheet: async () => '' }),
      './dsh.js': (ctx) => synthetic(ctx, { call: async method => { if(method === 'settings/describe')return {writable:true,namespaces:[{ns:'agent-default-model',revision:1,value:{provider:'deepseek-official',model:'deepseek-flash'}}]}; if(method === 'session/modelCatalog')return {default:{provider:'deepseek-official',model:'deepseek-flash'},groups:[{id:'deepseek-official',models:[{id:'deepseek-flash'},{id:'deepseek-v4-pro'}]}]}; throw Error('unexpected model method'); }, dsh: { state: 'online' }, startDsh: () => {}, onDsh: () => () => {} }),
      './rt.js': (ctx) => synthetic(ctx, { rt: { state: 'ready' }, onRt: () => () => {}, rtLine: () => '', retryRt: async () => {}, refreshCfg: async () => ({ ...state.cfg }), saveKey: async key => { state.setConfig.push({ deepseekKey: key }); state.cfg.deepseekKeySet = !!key; } }),
    },
    globals: sandbox,
  });
  ns.mount(root);
  return { ns, root, state, clock };
}

const clickAct = (root, act) => {
  const btn = root.querySelector(`[data-act="${act}"]`);
  assert.ok(btn, `找不到动作按钮：${act}`);
  btn.dispatchEvent('click', { target: btn });
  return btn;
};

test('首次读取配置失败时页面不空白：有错误说明和重试入口', async () => {
  const { ns, root, clock, state } = await boot({ getConfig: () => new Promise(() => {}) });
  const pending = ns.load();
  await clock.advance(11000);
  await flush(6);
  await pending;
  const html = root.innerHTML;
  assert.match(html, /读取设置失败/, '必须显示读取失败');
  assert.ok(root.querySelector('[data-act="reloadSettings"]'), '必须提供手动重试');
  assert.ok(root.querySelector('.st-shell'), '页面骨架不能空白');
  assert.equal(state.setConfig.length, 0, '读取失败不得写配置');
});

test('读取失败后重试成功，错误提示消失', async () => {
  let mode = 'hang';
  const { ns, root, clock } = await boot({ getConfig: () => (mode === 'hang' ? new Promise(() => {}) : Promise.resolve({ appVersion: '1.1.14', deepseekKeySet: true })) });
  const pending = ns.load();
  await clock.advance(11000);
  await flush(6);
  await pending;
  assert.match(root.innerHTML, /读取设置失败/, '第一次读取应当失败');
  mode = 'ok';
  clickAct(root, 'reloadSettings');
  await flush(8);
  assert.ok(!/读取设置失败/.test(root.innerHTML), '读取成功后不应残留错误');
});

test('系统设置保留模型与插件管理，不展示业务连接表单', async () => {
  const { ns, root } = await boot();
  await ns.load();
  assert.ok(root.querySelector('[data-act="pluginManager"]'));
  assert.ok(root.querySelector('#deepseekKey'), '核心模型配置保留在设置页');
  const tab = root.querySelector('[data-settings-tab="connection"]');
  tab.dispatchEvent('click', { target: tab });
  await flush(2);
  assert.equal(root.querySelector('#businessUrl'), null);
  assert.equal(root.querySelector('#businessToken'), null);
  assert.ok(root.querySelector('#dshUrl'), '对话运行时的全局设置继续保留');
});

test('取消配对需要两步确认', async () => {
  const { ns, root, state } = await boot();
  await ns.load();
  const tab = root.querySelector('[data-settings-tab="connection"]');
  tab.dispatchEvent('click', { target: tab });
  await flush(2);

  clickAct(root, 'unpair');
  await flush(4);
  assert.equal(state.unpaired || 0, 0, '第一次点击不能真的取消配对');
  assert.match(root.innerHTML, /确认取消配对/, '必须显示二次确认');

  clickAct(root, 'unpair');
  await flush(6);
  assert.equal(state.unpaired, 1, '确认后才取消配对');
});

test('令牌字段不再暴露 PD_TOKEN / .env 内部细节', async () => {
  const { ns, root } = await boot();
  await ns.load();
  const tab = root.querySelector('[data-settings-tab="connection"]');
  tab.dispatchEvent('click', { target: tab });
  await flush(2);
  assert.ok(!/PD_TOKEN|\.env/.test(root.innerHTML), '主界面不应出现内部配置名');
  assert.doesNotMatch(root.innerHTML, /访问令牌/, '插件令牌在插件内配置');
});


test('初始读取挂起时立即可见骨架与读取状态，而不是空白等待', async () => {
  const { ns, root } = await boot({ getConfig: () => new Promise(() => {}) });
  await flush(4);
  assert.ok(root.querySelector('.st-shell'), '请求未返回时就要画出页面外壳');
  assert.match(root.innerHTML, /正在读取设置|正在从本机读取配置/, '必须显示清楚的初始加载状态');
  assert.equal(ns.load(), ns.load(), '并发读取应复用同一个在途请求');
});

test('mount 与 onShow 并发只发一次读取请求', async () => {
  let calls = 0;
  const { ns } = await boot({ getConfig: () => { calls += 1; return new Promise(() => {}); } });
  const mountedLoad = ns.load();
  const shownLoad = ns.onShow();
  await flush(4);
  assert.equal(calls, 1, 'mount/onShow 并发读取不能重复请求');
  assert.equal(calls, 1, 'onShow 不应发第二次请求');
});

test('离开后重进：迟到的旧读取不能覆盖新配置', async () => {
  const pending = [];
  const { ns, root } = await boot({ getConfig: () => new Promise((resolve) => pending.push(resolve)) });
  await flush(3);
  assert.equal(pending.length, 1, 'mount 开始第一次读取');
  ns.onHide();
  ns.onShow();
  await flush(3);
  assert.equal(pending.length, 2, '重进时开始新的读取');

  pending[1]({ appVersion: '1.1.14', deepseekKeySet: true, dshMode: 'external', extDshUrl: 'http://127.0.0.1:3081' });
  await flush(6);
  pending[0]({ appVersion: '1.1.14', deepseekKeySet: true, dshMode: 'external', extDshUrl: 'http://127.0.0.1:3080' });
  await flush(6);

  const tab = root.querySelector('[data-settings-tab="connection"]');
  tab.dispatchEvent('click', { target: tab });
  await flush(3);
  assert.equal(root.querySelector('#dshUrl')?.getAttribute('value'), 'http://127.0.0.1:3081', '旧读取返回后不得覆盖新配置');
});

test('刷新配置时保留正在输入的草稿', async () => {
  let calls = 0;
  const deferred = [];
  const base = { appVersion: '1.1.14', deepseekKeySet: true, dshMode: 'external', extDshUrl: 'http://127.0.0.1:3080' };
  const { ns, root } = await boot({
    getConfig: () => {
      calls += 1;
      if (calls === 1) return Promise.resolve({ ...base, extDshUrl: 'http://127.0.0.1:3080' });
      return new Promise((resolve) => deferred.push(resolve));
    },
  });
  await flush(5);
  const tab = root.querySelector('[data-settings-tab="connection"]');
  tab.dispatchEvent('click', { target: tab });
  await flush(3);
  const input = root.querySelector('#dshUrl');
  input.value = 'http://127.0.0.1:3082';
  input.dispatchEvent('input', { target: input });

  const refresh = ns.load({ force: true });
  await flush(3);
  deferred[0]({ ...base, extDshUrl: 'http://127.0.0.1:3081' });
  await refresh;
  await flush(4);
  assert.equal(root.querySelector('#dshUrl')?.value, 'http://127.0.0.1:3082', '刷新不能覆盖正在输入的草稿');
});

test('paired 通知使用同一有界读取：挂起时显示错误与重试，不重复请求', async () => {
  let calls = 0;
  const { root, clock, state } = await boot({ getConfig: () => { calls += 1; return new Promise(() => {}); } });
  await flush(3);
  assert.equal(calls, 1, 'mount 已有一次在途读取');
  state.emit('paired', { ok: true });
  await flush(3);
  assert.equal(calls, 2, '配对通知作废旧读取，只发起一次新的有界读取');

  await clock.advance(11000);
  await flush(6);
  assert.match(root.innerHTML, /读取设置失败/, 'paired 触发的读取也必须有界失败并显示重试');
  assert.ok(root.querySelector('[data-act="reloadSettings"]'));
  assert.equal(calls, 2, '超时后不自动重发请求');
});


test('先验证 Key，返回模型后才能选择，失败不会覆盖现有 Key', async () => {
  const {ns,root,state}=await boot({cfg:{deepseekKeySet:true},discoverModels:async()=>{throw Error('Key 无效');}});
  await ns.load(); await flush(8);
  assert.equal(root.querySelector('#defaultModelId').disabled,true);
  const key=root.querySelector('#deepseekKey');key.value='invalid-fixture';key.dispatchEvent('input',{target:key});
  clickAct(root,'saveModelKey'); await flush(10);
  assert.deepEqual(state.events,['verify-key']); assert.equal(state.setConfig.length,0);
  assert.equal(root.querySelector('#defaultModelId').disabled,true);assert.match(root.innerHTML,/Key 无效/);
});

test('连通后返回模型清单，保存 Key 在验证之后，重新修改 Key 立即禁用旧清单', async () => {
  const {ns,root,state}=await boot({cfg:{deepseekKeySet:false}});await ns.load();await flush(8);
  const key=root.querySelector('#deepseekKey');key.value='test-fixture';key.dispatchEvent('input',{target:key});
  clickAct(root,'saveModelKey');await flush(12);
  assert.equal(state.events[0],'verify-key');assert.equal(state.setConfig[0].deepseekKey,'test-fixture');
  assert.equal(root.querySelector('#defaultModelId').disabled,false);assert.match(root.innerHTML,/deepseek-v4-pro/);
  const changed=root.querySelector('#deepseekKey');changed.value='changed-fixture';changed.dispatchEvent('input',{target:changed});
  assert.equal(root.querySelector('#defaultModelId').disabled,true);assert.equal(root.querySelector('[data-act="saveDefaultModel"]').disabled,true);
});

test('后台开关请求未返回时不能基于旧配置重复提交', async () => {
  let release;
  let calls = 0;
  const gate = new Promise(resolve => { release = resolve; });
  const { ns, root } = await boot({ setConfig: async () => { calls += 1; await gate; return true; } });
  await ns.load();
  const toggle = root.querySelector('[data-act="toggleTaskWake"]');
  toggle.dispatchEvent('click', { target: toggle });
  toggle.dispatchEvent('click', { target: toggle });
  await flush(3);
  const submitted = root.querySelectorAll('[data-act="toggleTaskWake"][disabled]').length;
  release();
  await flush(8);
  assert.equal(calls, 1, '连点只能提交一次');
  assert.equal(submitted, 1, '请求中的开关应当呈不可重复提交状态');
});

test('外部 ntfy 地址只保存有效的 http(s) URL，失败保留草稿', async () => {
  const { ns, root, state } = await boot({ cfg: { ntfyUrl: 'https://ntfy.example.com/saved' } });
  await ns.load();
  const input = root.querySelector('#ntfyUrl');
  input.value = 'not-a-url';
  input.dispatchEvent('input', { target: input });
  root.querySelector('[data-act="saveNtfy"]').dispatchEvent('click', { target: root.querySelector('[data-act="saveNtfy"]') });
  await flush(6);
  assert.equal(state.setConfig.length, 0, '无效地址不能写入配置');
  assert.equal(root.querySelector('#ntfyUrl')?.value, 'not-a-url', '校验失败不能清空草稿');
  input.value = 'https://ntfy.example.com/topic';
  input.dispatchEvent('input', { target: input });
  const save = root.querySelector('[data-act="saveNtfy"]');
  save.dispatchEvent('click', { target: save });
  await flush(6);
  assert.equal(state.setConfig.length, 1, '有效 http(s) URL 仍可保存');
  assert.equal(state.setConfig[0].ntfyUrl, 'https://ntfy.example.com/topic');
});
test('appSettings 事件复用有界读取刷新开关状态，不覆盖正在输入的草稿', async () => {
  const { ns, root, state } = await boot();
  await ns.load(); await flush(4);
  assert.equal(root.querySelector('[data-act="toggleTaskWake"]').getAttribute('aria-checked'), 'true');
  const input = root.querySelector('#ntfyUrl');
  input.value = 'https://ntfy.example.com/draft';
  input.dispatchEvent('input', { target: input });

  state.cfg.keepAwakeWhileRunning = false;
  state.emit('appSettings', { keepAwakeWhileRunning: false });
  await flush(6);
  assert.equal(root.querySelector('[data-act="toggleTaskWake"]').getAttribute('aria-checked'), 'false', 'Agent 改动后开关状态要随事件刷新');
  assert.equal(root.querySelector('#ntfyUrl')?.value, 'https://ntfy.example.com/draft', '事件刷新不能覆盖正在输入的草稿');
});

test('appSettings 事件触发的读取同样有界，挂起时显示错误与重试', async () => {
  let calls = 0;
  const { root, clock, state } = await boot({
    getConfig: () => { calls += 1; if (calls === 1) return Promise.resolve({ appVersion: 'core-test' }); return new Promise(() => {}); },
  });
  await flush(3);
  assert.equal(calls, 1, 'mount 已有一次读取');
  state.emit('appSettings', { keepAwakeWhileRunning: true });
  await flush(3);
  assert.equal(calls, 2, '事件作废旧读取，只发起一次新的有界读取');

  await clock.advance(11000);
  await flush(6);
  assert.match(root.innerHTML, /读取设置失败/, '事件触发的读取也必须有界失败并显示重试');
  assert.ok(root.querySelector('[data-act="reloadSettings"]'));
  assert.equal(calls, 2, '超时后不自动重发请求');
});

test('外部运行时只说明本机 Key 已保存，不声称外部模型已配置', async () => {
  const { ns, root } = await boot({ cfg: { dshMode: 'external', deepseekKeySet: true } });
  await ns.load(); await flush(6);
  assert.match(root.innerHTML, /本机已保存 · 外部运行时使用其自身 Key/);
  assert.doesNotMatch(root.innerHTML, /已配置 · 外部运行时/);
});

test('ntfy 文案说明只保存与打开链接，不推送也不自动订阅', async () => {
  const { ns, root } = await boot();
  await ns.load(); await flush(4);
  assert.match(root.innerHTML, /只保存并打开该链接；本 App 不推送通知、不自动订阅/);
});
