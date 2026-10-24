import assert from 'node:assert/strict';
import {test} from 'node:test';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const nativeUrl = pathToFileURL(path.resolve('android/app/src/main/assets/plugin-framework/v1/js/native.js')).href;

// One bridge for the whole file: the module captures window.PDNative when it loads.
const calls = [];
let handler = () => ({ok: true});
globalThis.window = {PDNative: {call(method, id, argsJson) {
  calls.push({method, args: JSON.parse(argsJson)});
  const value = handler(method, JSON.parse(argsJson)) ?? {ok: true};
  setImmediate(() => globalThis.window.__pd_cb(id, JSON.stringify(value?.__error || {value})));
}}};
const {native} = await import(nativeUrl);

function respondWith(fn) {
  calls.length = 0;
  handler = fn;
}

test('page network requests go through the host bridge with bounded arguments', async () => {
  respondWith((method, args) => args.action === 'fetch'
    ? {status: 200, ok: true, contentType: 'application/json', bytes: 7, text: '{"a":1}'}
    : {ok: true});
  const data = await native.fetchJson('https://example.com/api', {timeoutMs: 8000});
  assert.deepEqual(data, {a: 1});
  assert.equal(calls[0].method, 'plugins');
  assert.equal(calls[0].args.action, 'fetch');
  assert.equal(calls[0].args.url, 'https://example.com/api');
  assert.equal(calls[0].args.method, 'GET');
  assert.equal(calls[0].args.timeoutMs, 8000);
  // Nothing that was not requested is sent, so an explicit null never becomes a body.
  assert.equal('body' in calls[0].args, false);
  assert.equal('headers' in calls[0].args, false);
  assert.ok(calls[0].args.requestId);
});

test('a remote image becomes a data url the page can use', async () => {
  respondWith((method, args) => args.action === 'fetch'
    ? {status: 200, ok: true, contentType: 'image/png', bytes: 3, base64: 'aGk='}
    : {ok: true});
  const src = await native.fetchImage('https://cdn.example.com/a.png');
  assert.equal(src, 'data:image/png;base64,aGk=');
  assert.equal(calls[0].args.responseType, 'base64');
});

test('a pending request can be cancelled through the host', async () => {
  respondWith(() => ({ok: true}));
  const pending = native.fetch('https://example.com/slow');
  await pending.cancel();
  const cancel = calls.find(call => call.args.action === 'fetchCancel');
  assert.ok(cancel);
  assert.equal(cancel.args.requestId, calls[0].args.requestId);
});

test('fetchStream uses the bounded native polling bridge and preserves callbacks', async () => {
  let polls = 0;
  respondWith((method, args) => {
    if (method === 'fetchStreamStart') return {requestId: args.requestId};
    if (method === 'fetchStreamPoll' && ++polls === 1) {
      return {events: [
        {kind:'chunk', first:true, base64:'aGk=', chunkBytes:2, bytesSoFar:2, status:200, contentType:'text/plain', finalUrl:'https://example.com/final', firstChunkMs:12},
        {kind:'chunk', first:false, base64:'IQ==', chunkBytes:1, bytesSoFar:3, status:200, contentType:'text/plain', finalUrl:'https://example.com/final', firstChunkMs:12},
      ], done:false};
    }
    return {events: [
      {kind:'done', emptyBody:false, bytesSoFar:3, status:200, contentType:'text/plain', finalUrl:'https://example.com/final', firstChunkMs:12},
    ], done:true};
  });
  const first = [];
  const chunks = [];
  const done = [];
  native.fetchStream('https://example.com/start', {
    timeoutMs: 9000,
    maxBytes: 4096,
    onFirstChunk: value => first.push(value),
    onChunk: value => chunks.push(value),
    onDone: value => done.push(value),
  });
  await new Promise(resolve => {
    const check = () => done.length ? resolve() : setTimeout(check);
    check();
  });
  assert.equal(calls[0].method, 'fetchStreamStart');
  assert.equal(calls[0].args.url, 'https://example.com/start');
  assert.equal(calls[0].args.timeoutMs, 9000);
  assert.equal(calls[0].args.maxBytes, 4096);
  assert.equal(first.length, 1);
  assert.equal(first[0].firstChunkMs, 12);
  assert.deepEqual(chunks.map(value => value.base64), ['aGk=', 'IQ==']);
  assert.equal(done[0].status, 200);
  assert.equal(done[0].finalUrl, 'https://example.com/final');
  assert.equal(done[0].emptyBody, false);
});

test('fetchStream handles empty bodies and can cancel its handle before the start ack', async () => {
  respondWith(method => method === 'fetchStreamStart'
    ? {requestId: 'empty-stream'}
    : method === 'fetchStreamCancel'
      ? {cancelled: true}
      : {events: [{kind:'done', emptyBody:true, bytesSoFar:0, status:204, contentType:'', finalUrl:'https://example.com/empty', firstChunkMs:null}], done:true});
  const done = [];
  native.fetchStream('https://example.com/empty', {onDone: value => done.push(value)});
  await new Promise(resolve => {
    const check = () => done.length ? resolve() : setTimeout(check);
    check();
  });
  assert.equal(done[0].emptyBody, true);
  assert.equal(done[0].bytesSoFar, 0);

  const handle = native.fetchStream('https://example.com/slow');
  const slowRequestId = calls.at(-1).args.requestId;
  handle.cancel();
  assert.deepEqual(
    calls
      .filter(call => call.args.requestId === slowRequestId)
      .map(call => call.method),
    ['fetchStreamStart', 'fetchStreamCancel'],
  );
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(
    calls.some(call => call.args.requestId === slowRequestId && call.method === 'fetchStreamPoll'),
    false,
  );
  assert.equal(
    calls.filter(call => call.args.requestId === slowRequestId && call.method === 'fetchStreamCancel').length,
    2,
  );
});


test('fetchJson HTTP errors retain status, final URL and retry metadata',async()=>{
 respondWith(()=>({ok:false,status:503,finalUrl:'https://example.com/final',code:'http_error',timeout:false,cancelled:false,retryable:true,text:'private body'}));
 await assert.rejects(native.fetchJson('https://example.com/start'),e=>e.status===503&&e.finalUrl==='https://example.com/final'&&e.code==='http_error'&&e.retryable&&!e.message.includes('private body'));
});
test('bridge transport errors retain timeout metadata without message guessing',async()=>{
 respondWith(()=>({__error:{error:'请求超时',code:'timeout',timeout:true,cancelled:false,status:0,finalUrl:'https://example.com/slow',retryable:true}}));
 await assert.rejects(native.fetch('https://example.com/slow'),e=>e.timeout===true&&e.status===0&&e.code==='timeout'&&e.finalUrl.endsWith('/slow'));
});
test('invalid JSON keeps response location and differs from a network failure',async()=>{
 respondWith(()=>({ok:true,status:200,finalUrl:'https://example.com/html',text:'<html>not json</html>'}));
 await assert.rejects(native.fetchJson('https://example.com/html'),e=>e.code==='invalid_response'&&e.status===200&&e.finalUrl.endsWith('/html')&&!e.timeout);
});
