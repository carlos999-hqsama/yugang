import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const nativeSource = fileURLToPath(new URL('../../main/assets/plugin-framework/v1/js/native.js', import.meta.url));

async function loadNative(t, {preview = true} = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'yugang-preview-native-'));
  t.after(() => rm(directory, {recursive:true, force:true}));
  await writeFile(join(directory, 'package.json'), '{"type":"module"}');
  await writeFile(join(directory, 'util.js'), 'let id=0; export function uuid(){return `stream-${++id}`;}\n');
  await writeFile(join(directory, 'native.js'), await readFile(nativeSource, 'utf8'));
  const calls = [];
  const window = {PDNative:{
    call(method, id, argsJson) { calls.push({method, id, args:JSON.parse(argsJson)}); },
    ...(preview ? {isPreview:() => true} : {}),
  }};
  globalThis.window = window;
  t.after(() => { if (globalThis.window === window) delete globalThis.window; });
  const module = await import(pathToFileURL(join(directory, 'native.js')).href);
  return {
    ...module,
    calls,
    reply(call, payload) { window.__pd_cb(call.id, JSON.stringify(payload)); },
    emit(channel, payload) { window.__pd_ev(channel, JSON.stringify(payload)); },
  };
}

const previewFailure = {
  error:'预览不会打开真实网站',
  code:'preview_browser_disabled',
  reason:'preview',
  expected:true,
  retryable:false,
};

test('native preview context comes from the host bridge, never the origin', async t => {
  const preview = await loadNative(t, {preview:true});
  assert.equal(preview.isPreview, true);
  assert.equal(preview.native.isPreview, true);
  const foreground = await loadNative(t, {preview:false});
  assert.equal(foreground.isPreview, false);
  assert.equal(foreground.native.isPreview, false);
});

test('native.browser resolves an expected preview refusal without opening anything else', async t => {
  const context = await loadNative(t);
  const pending = context.native.browser({url:'https://example.invalid/', title:'Expected refusal'});
  assert.equal(context.calls.length, 1);
  assert.equal(context.calls[0].method, 'siteBrowserOpen');
  context.reply(context.calls[0], previewFailure);
  assert.deepEqual(await pending, {
    opened:false,
    closed:false,
    reason:'preview',
    code:'preview_browser_disabled',
  });
  assert.equal(context.calls.length, 1, 'no fallback may enter the real browser bridge');
});

test('native.browser still rejects real browser failures', async t => {
  const context = await loadNative(t);
  const pending = context.native.browser({url:'https://example.invalid/'});
  const rejection = assert.rejects(pending, error => {
    assert.equal(error.message, '网站未打开');
    assert.equal(error.code, 'browser_unavailable');
    return true;
  });
  context.reply(context.calls[0], {error:'网站未打开', code:'browser_unavailable'});
  await rejection;
});

test('generic policy refusals are catchable and the next tool call still succeeds', async t => {
  const context = await loadNative(t);
  const pending = context.native.notify({op:'request'});
  const rejection = assert.rejects(pending, error => {
    assert.ok(error instanceof Error);
    assert.equal(error.code, 'preview_policy_denied');
    assert.equal(error.reason, 'preview');
    assert.equal(error.expected, true);
    assert.equal(error.retryable, false);
    return true;
  });
  context.reply(context.calls[0], {...previewFailure, code:'preview_policy_denied'});
  await rejection;

  const next = context.native.getConfig();
  assert.equal(context.calls[1].method, 'getConfig');
  context.reply(context.calls[1], {value:{configured:true}});
  assert.deepEqual(await next, {configured:true});
});

test('a refused SSE ends only its own listener and exposes its structured event', async t => {
  const context = await loadNative(t);
  const firstStates = [];
  const firstEvents = [];
  const secondStates = [];
  const secondEvents = [];
  context.sse('first', '/first', {
    onState:(...args) => firstStates.push(args),
    onEvent:(...args) => firstEvents.push(args),
  });
  context.sse('second', '/second', {
    onState:(...args) => secondStates.push(args),
    onEvent:(...args) => secondEvents.push(args),
  });
  const [first, second] = context.calls;
  context.emit(`sse:${first.id}`, {...previewFailure, code:'preview_policy_denied', kind:'error'});
  context.emit(`sse:${first.id}`, {kind:'event', event:'late', data:'ignored'});
  context.emit(`sse:${second.id}`, {kind:'event', event:'alive', data:{ok:true}});

  assert.equal(firstStates.length, 1);
  assert.deepEqual(firstStates[0].slice(0, 2), ['error', previewFailure.error]);
  assert.equal(firstStates[0][2].code, 'preview_policy_denied');
  assert.equal(firstStates[0][2].reason, 'preview');
  assert.deepEqual(firstEvents, []);
  assert.deepEqual(secondStates, []);
  assert.deepEqual(secondEvents, [['alive', {ok:true}]]);
  assert.equal(context.calls.length, 2, 'a refusal must not close an unrelated stream');
});

test('a refused WebSocket closes only its own listener with policy metadata', async t => {
  const context = await loadNative(t);
  const firstClosed = [];
  const secondMessages = [];
  context.ws('first', '/first', {onClose:(...args) => firstClosed.push(args)});
  context.ws('second', '/second', {onMessage:data => secondMessages.push(data)});
  const [first, second] = context.calls;
  context.emit(`ws:${first.id}`, {
    kind:'close', code:1008, reason:'预览不允许调用宿主能力 wsOpen',
    errorCode:'preview_policy_denied', previewReason:'preview', expected:true,
  });
  context.emit(`ws:${first.id}`, {kind:'message', data:'ignored'});
  context.emit(`ws:${second.id}`, {kind:'open'});
  context.emit(`ws:${second.id}`, {kind:'message', data:'alive'});

  assert.equal(firstClosed.length, 1);
  assert.equal(firstClosed[0][0], 1008);
  assert.equal(firstClosed[0][2].errorCode, 'preview_policy_denied');
  assert.equal(firstClosed[0][2].previewReason, 'preview');
  assert.deepEqual(secondMessages, ['alive']);
});

test('fetchStreamStart refusal reaches only that stream error callback', async t => {
  const context = await loadNative(t);
  const errors = [];
  context.native.fetchStream('https://example.invalid/', {onError:error => errors.push(error)});
  assert.equal(context.calls[0].method, 'fetchStreamStart');
  context.reply(context.calls[0], {...previewFailure, code:'preview_policy_denied'});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 'preview_policy_denied');
  assert.equal(errors[0].reason, 'preview');
  assert.equal(errors[0].expected, true);
  assert.equal(context.calls.length, 1, 'a denied stream must not start polling or cancel another stream');
});
