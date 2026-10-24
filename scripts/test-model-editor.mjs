// Offline integration checks of the actual core DeepSeek editor; credentials are synthetic.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const src = fs.readFileSync(new URL('../android/app/src/main/assets/plugin-framework/v1/js/models.js', import.meta.url), 'utf8')
  .replace(/^import .*;\n/gm, '').replace(/^export /gm, '');

function harness({external = false, fail = false} = {}) {
  const writes = [];
  const input = {id: 'model-key', value: '', tabIndex: 0, focus() {}, isConnected: true};
  const button = {tabIndex: 0, focus() {}, isConnected: true};
  const root = {
    hidden: true,
    html: '',
    listeners: {},
    addEventListener(name, fn) { this.listeners[name] = fn; },
    set innerHTML(value) {
      this.html = value;
      const match = value.match(/id="model-key"[^>]*value="([^"]*)"/);
      input.value = match?.[1] || '';
    },
    get innerHTML() { return this.html; },
    querySelector(selector) { return selector.includes('model-key') ? input : button; },
    querySelectorAll(selector) { return selector.includes('password') ? [input] : [button, input]; },
    contains() { return true; },
  };
  let liveKeySet = true;
  const config = () => ({deepseekKeySet: liveKeySet, dshMode: external ? 'external' : 'embedded'});
  const native = {
    getConfig: async () => config(),
    setConfig: async patch => {
      writes.push(patch);
      if (fail) throw new Error('synthetic save failure');
      if ('deepseekKey' in patch) liveKeySet = !!patch.deepseekKey;
      return config();
    },
  };
  const context = vm.createContext({
    console, Promise, Map,
    document: {body: {appendChild() {}}, activeElement: button, createElement: () => root, addEventListener() {}},
    native,
    saveKey: async key => {
      writes.push({deepseekKey: key, restart: true});
      if (fail) throw new Error('synthetic save failure');
      liveKeySet = !!key;
    },
    refreshCfg: native.getConfig,
    rt: {state: 'ready', keySet: liveKeySet},
    onRt: () => {},
    S: new Proxy({}, {get: (_, key) => `<svg data-icon="${String(key)}"></svg>`}),
    esc: value => String(value ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;'),
  });
  vm.runInContext(src + '\nglobalThis.editor={openModel,closeModel,saveModel,clearModelKey,renderModel,modelOpen,getFeedback:()=>feedback};', context);
  return {editor: context.editor, root, input, writes};
}

{
  const h = harness();
  await h.editor.openModel();
  assert.equal(h.editor.modelOpen(), true);
  assert.match(h.root.html, /DeepSeek 模型/);
  assert.equal(await h.editor.saveModel(), true, 'blank input preserves an existing key');
  assert.equal(h.writes.length, 0);
  h.input.value = 'SYNTHETIC_KEY';
  h.root.listeners.input({target: h.input});
  assert.equal(await h.editor.saveModel(), true);
  assert.equal(h.writes[0].restart, true, 'embedded runtime uses the restart-aware helper');
  assert.doesNotMatch(h.root.html, /SYNTHETIC_KEY/);
  assert.equal(h.editor.closeModel(), true);
  assert.equal(h.root.hidden, true);
}
{
  const h = harness({external: true});
  await h.editor.openModel();
  h.input.value = 'SYNTHETIC_EXTERNAL';
  h.root.listeners.input({target: h.input});
  assert.equal(await h.editor.saveModel(), true);
  assert.equal(h.writes[0].deepseekKey, 'SYNTHETIC_EXTERNAL');
  assert.equal('restart' in h.writes[0], false, 'external runtime is not restarted');
}
{
  const h = harness();
  await h.editor.openModel();
  assert.equal(await h.editor.clearModelKey(), false, 'clear requires visible confirmation');
  assert.equal(await h.editor.clearModelKey(true), true);
  assert.equal(h.writes[0].deepseekKey, '');
  assert.match(h.editor.getFeedback().text, /Key 已清除/);
}
{
  const h = harness({fail: true});
  await h.editor.openModel();
  h.input.value = 'SYNTHETIC_FAILURE';
  h.root.listeners.input({target: h.input});
  assert.equal(await h.editor.saveModel(), false);
  assert.match(h.editor.getFeedback().text, /保存失败/);
  assert.doesNotMatch(h.editor.getFeedback().text, /SYNTHETIC_FAILURE/);
}

const settings = fs.readFileSync(new URL('../android/app/src/main/assets/www/js/settings.js', import.meta.url), 'utf8');
assert.ok(settings.includes('id="deepseekKey"'), 'core settings expose the real model configuration');
assert.ok(!/localQuota|providerQuota/.test(settings), 'provider quota configuration stays out of core settings');
console.log('Core DeepSeek editor: read, blank preservation, embedded/external save, clearing, failures and redaction passed');
