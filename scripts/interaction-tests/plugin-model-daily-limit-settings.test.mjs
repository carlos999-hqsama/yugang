import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const sourceUrl = new URL('../../android/app/src/main/assets/www/js/settings.js', import.meta.url);

async function boot(initialConfig) {
  let currentConfig = {...initialConfig};
  const patches = [];
  const toasts = [];
  const native = {
    getConfig: async () => ({...currentConfig}),
    setConfig: async (patch) => {
      patches.push({...patch});
      currentConfig = {...currentConfig, ...patch};
    },
  };
  const deps = {
    native,
    on: () => {},
    isAndroid: false,
    S: new Proxy({}, {get: () => ''}),
    esc: (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'),
    ago: String,
    toast: (message) => toasts.push(message),
    promptSheet: async () => '',
    nas: {state: 'idle'},
    onNas: () => {},
    dsh: {state: 'idle'},
    startDsh: () => {},
    onDsh: () => {},
    rt: {state: 'idle'},
    onRt: () => {},
    rtLine: () => '',
    retryRt: async () => {},
    refreshCfg: native.getConfig,
  };
  const context = vm.createContext({
    __deps: deps,
    console,
    URL,
    setTimeout,
    clearTimeout,
  });
  // Execute the real settings source with only its external native/UI dependencies stubbed.
  // A composing input keeps render() side-effect free; behavior under test remains real.
  const imports = 'const {native,on,isAndroid,S,esc,ago,toast,promptSheet,nas,onNas,dsh,startDsh,onDsh,rt,onRt,rtLine,retryRt,refreshCfg}=globalThis.__deps;\n';
  const testApi = `\nglobalThis.__api = {
    generalPanel, load, act,
    boot(config) {
      cfg = config; cfgLoaded = true; cfgLoading = false; cfgError = '';
      activeTab = 'general'; pluginModelDailyLimitFeedback = null;
      dirtyIds.clear(); drafts.clear(); busyActions.clear(); composing = true;
      const input = {value: String(cfg.pluginModelDailyHardLimitTokens ?? '')};
      el = {querySelector(selector) { return selector === '#pluginModelDailyHardLimitTokens' ? input : null; }};
      return input;
    },
    feedback() { return pluginModelDailyLimitFeedback; },
    config() { return cfg; },
  };`;
  const transformed = readFileSync(sourceUrl, 'utf8')
    .replace(/^import .*;\n/gm, '')
    .replace(/^export\s+/gm, '');
  new vm.Script(imports + transformed + testApi, {filename: sourceUrl.pathname}).runInContext(context);
  const input = context.__api.boot({...currentConfig});
  return {api: context.__api, input, patches, toasts, config: () => currentConfig};
}

const save = 'savePluginModelDailyLimit';
const fieldId = 'pluginModelDailyHardLimitTokens';

test('加载只显示默认/已配置上限制，保存仅提交单字段且允许跨 200000 增减', async () => {
  const x = await boot({});
  await x.api.load();
  assert.equal(x.patches.length, 0, '加载不得自动保存默认值');
  const html = x.api.generalPanel();
  assert.equal(html.match(new RegExp(`id="${fieldId}"`, 'g')).length, 1);
  const input = html.match(new RegExp(`<input id="${fieldId}"[^>]+>`))?.[0] || '';
  assert.match(input, /type="number"/);
  assert.match(input, /min="1"/);
  assert.match(input, /step="1"/);
  assert.match(input, /value="200000"/);
  assert.doesNotMatch(input, /\smax=/, '不得添加固定 200k 或其他 HTML 最大值');

  x.input.value = '300000';
  await x.api.act(save);
  assert.deepEqual(JSON.parse(JSON.stringify(x.patches)), [{pluginModelDailyHardLimitTokens: 300000}]);
  assert.equal(x.config().pluginModelDailyHardLimitTokens, 300000);
  assert.match(x.api.generalPanel(), /value="300000"/);

  x.input.value = '150000';
  await x.api.act(save);
  assert.deepEqual(JSON.parse(JSON.stringify(x.patches)), [
    {pluginModelDailyHardLimitTokens: 300000},
    {pluginModelDailyHardLimitTokens: 150000},
  ]);
  assert.equal(x.config().pluginModelDailyHardLimitTokens, 150000);
  assert.match(x.api.generalPanel(), /value="150000"/);
});

test('空值、零、负数、小数、科学计数法及不精确整数均明确拒绝且不改配置', async () => {
  const x = await boot({pluginModelDailyHardLimitTokens: 200000});
  await x.api.load();
  for (const value of ['', '0', '-1', '1.5', '1e6', '9007199254740992']) {
    x.input.value = value;
    await x.api.act(save);
    assert.equal(x.patches.length, 0, `非法值不得提交：${value}`);
    assert.equal(x.config().pluginModelDailyHardLimitTokens, 200000);
    const feedback = x.api.feedback();
    assert.equal(feedback.tone, 'bad');
    assert.ok(feedback.text.length > 0);
    assert.ok(x.api.generalPanel().includes(feedback.text), '页面内必须显示明确错误');
  }
});
