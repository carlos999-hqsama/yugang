import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import {
  applyChatAttachments,
  createChatAttachmentHandler,
  createWorkspaceFileHandler,
} from '../../android/tools/runtime/overlay/opt/pd/chat-attachments.mjs';

const ID = `sha256:${'a'.repeat(64)}`;
const OTHER_ID = `sha256:${'b'.repeat(64)}`;
const ROUTE = '/api/yugang/session-file';

function message(ref, type = 'user/message') {
  return { type, data: { content: [{ type: 'file', attachment: ref }] } };
}

function fixture(options = {}) {
  const ref = options.ref ?? { attachmentId: ID, name: 'note.txt', bytes: 3 };
  const events = options.events ?? [message(ref)];
  const chunks = options.chunks ?? [Buffer.from('abc')];
  const reads = [];
  const hostPaths = [];
  const inspections = [];
  const attachments = {
    readFileStream(actualRef, signal) {
      reads.push({ ref: actualRef, signal });
      if (options.readFileStream) return options.readFileStream(actualRef, signal);
      return (async function* () {
        for (const chunk of chunks) yield chunk;
      })();
    },
  };
  if (Object.hasOwn(options, 'fileHostPath')) {
    attachments.fileHostPath = actualRef => {
      hostPaths.push(actualRef);
      return options.fileHostPath(actualRef);
    };
  }
  const sessionController = {
    async inspect(sessionId, signal) {
      inspections.push({ sessionId, signal });
      if (options.inspect) return options.inspect(sessionId, signal);
      return { events: sessionId === 'session-a' ? events : [] };
    },
  };
  const handler = createChatAttachmentHandler({ sessionController, attachments });
  const request = ({
    sessionId = 'session-a', attachmentId = ref.attachmentId, name = ref.name,
    method = 'GET', range, signal, query,
  } = {}) => {
    const url = new URL(`http://localhost${ROUTE}`);
    if (sessionId !== null) url.searchParams.set('sessionId', sessionId);
    if (attachmentId !== null) url.searchParams.set('attachmentId', attachmentId);
    if (name !== null) url.searchParams.set('name', name);
    url.searchParams.set('bytes', '1'); // Client byte metadata is never authoritative.
    query?.(url.searchParams);
    return new Request(url, {
      method,
      signal,
      headers: range === undefined ? {} : { Range: range },
    });
  };
  return { ref, handler, request, reads, hostPaths, inspections };
}

test('a persisted user file authorizes streaming and supplies all response metadata', async () => {
  const ref = { attachmentId: ID, name: '报告 #1.txt', bytes: 11 };
  const f = fixture({ ref, chunks: [Buffer.from('hello '), Buffer.from('world')] });
  const response = await f.handler(f.request());
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'hello world');
  assert.equal(response.headers.get('Content-Type'), 'text/plain; charset=utf-8');
  assert.equal(response.headers.get('Content-Length'), '11');
  assert.equal(response.headers.get('Content-Disposition'),
    "inline; filename*=UTF-8''%E6%8A%A5%E5%91%8A%20%231.txt");
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(response.headers.get('X-Content-Type-Options'), 'nosniff');
  assert.equal(response.headers.get('Accept-Ranges'), 'bytes');
  assert.equal(f.inspections[0].sessionId, 'session-a');
  assert.equal(f.reads.length, 1);
  assert.strictEqual(f.reads[0].ref, ref);
});

test('forged IDs, names, sessions, and non-user content never open attachment storage', async () => {
  const f = fixture();
  for (const request of [
    f.request({ attachmentId: OTHER_ID }),
    f.request({ name: '../../etc/passwd' }),
    f.request({ sessionId: 'session-b' }),
  ]) {
    const response = await f.handler(request);
    assert.equal(response.status, 403);
    assert.equal(await response.text(), 'file not authorized');
  }
  assert.equal(f.reads.length, 0);

  for (const events of [
    [message(f.ref, 'assistant/message')],
    [{ type: 'user/message', data: { content: [{ type: 'image', attachment: f.ref }] } }],
    [{ type: 'user/message', data: { message: { content: [{ type: 'file', attachment: f.ref }] } } }],
  ]) {
    const denied = fixture({ ref: f.ref, events });
    assert.equal((await denied.handler(denied.request())).status, 403);
    assert.equal(denied.reads.length, 0);
  }

  const before = f.inspections.length;
  for (const request of [
    f.request({ attachmentId: null }),
    f.request({ attachmentId: 'not-an-attachment' }),
    f.request({ name: '' }),
    f.request({ query: query => query.append('name', f.ref.name) }),
  ]) {
    assert.equal((await f.handler(request)).status, 400);
  }
  assert.equal(f.inspections.length, before);
  const rejectedMethod = await f.handler(f.request({ method: 'POST' }));
  assert.equal(rejectedMethod.status, 405);
  assert.equal(rejectedMethod.headers.get('Allow'), 'GET, HEAD');
  assert.equal(f.inspections.length, before);
});

test('session and provider failures are concise and do not disclose internal text', async () => {
  const f = fixture({
    inspect: async sessionId => {
      throw new Error(`private failure for ${sessionId} at /private/session.json`);
    },
  });
  const response = await f.handler(f.request());
  assert.equal(response.status, 404);
  assert.equal(await response.text(), 'file not found');
  assert.equal(f.reads.length, 0);
});

test('HEAD returns authorized metadata without opening a body or provider stream', async () => {
  const f = fixture({
    ref: { attachmentId: ID, name: 'note.txt', bytes: 3 },
    fileHostPath: () => assert.fail('HEAD must not resolve a host path'),
  });
  const response = await f.handler(f.request({ method: 'HEAD' }));
  assert.equal(response.status, 200);
  assert.equal(response.body, null);
  assert.equal(response.headers.get('Content-Length'), '3');
  assert.equal(response.headers.get('Content-Type'), 'text/plain; charset=utf-8');
  assert.equal(f.reads.length, 0);
  assert.equal(f.hostPaths.length, 0);
});

test('binary data remains byte-exact and unknown or misleading extensions stay octet-stream', async () => {
  const bytes = Uint8Array.of(0, 255, 1, 128, 42);
  for (const name of ['payload.constructor', 'payload.txt.exe', '.txt']) {
    const f = fixture({
      ref: { attachmentId: ID, name, bytes: bytes.byteLength },
      chunks: [bytes],
    });
    const response = await f.handler(f.request());
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Content-Type'), 'application/octet-stream');
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
  }
});

test('strict extension mappings cover documents, media, and audio without reads on HEAD', async () => {
  const cases = new Map(Object.entries({
    'a.json': 'application/json; charset=utf-8',
    'a.csv': 'text/csv; charset=utf-8',
    'a.PNG': 'image/png',
    'a.pdf': 'application/pdf',
    'a.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'a.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'a.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'a.zip': 'application/zip',
    'a.mp4': 'video/mp4',
    'a.webm': 'video/webm',
    'a.mov': 'video/quicktime',
    'a.mp3': 'audio/mpeg',
    'a.flac': 'audio/flac',
  }));
  for (const [name, type] of cases) {
    const f = fixture({ ref: { attachmentId: ID, name, bytes: 0 }, chunks: [] });
    const response = await f.handler(f.request({ method: 'HEAD' }));
    assert.equal(response.status, 200, name);
    assert.equal(response.headers.get('Content-Type'), type, name);
    assert.equal(f.reads.length, 0, name);
  }
});

test('the 256 MiB boundary is enforced before any attachment read', async () => {
  const f = fixture({ ref: { attachmentId: ID, name: 'huge.zip', bytes: 256 * 1024 * 1024 + 1 } });
  const response = await f.handler(f.request());
  assert.equal(response.status, 413);
  assert.equal(await response.text(), 'file exceeds byte limit');
  assert.equal(f.reads.length, 0);
});

test('full GET prefers the official provider stream even when a host path exists', async () => {
  const f = fixture({ fileHostPath: () => assert.fail('full GET must not use a host path') });
  const response = await f.handler(f.request());
  assert.equal(await response.text(), 'abc');
  assert.equal(f.reads.length, 1);
  assert.equal(f.hostPaths.length, 0);
});

test('host-backed single ranges cover bounded, open, suffix, oversized, and HEAD requests', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'chat-attachments-range-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const data = Buffer.from('0123456789abcdefghijklmnopqrstuvwxyz');
  const path = join(directory, 'provider-object');
  await writeFile(path, data);
  const ref = { attachmentId: ID, name: 'clip.mp4', bytes: data.byteLength };
  const f = fixture({ ref, fileHostPath: () => path });

  for (const [range, expected, contentRange] of [
    ['bytes=2-5', '2345', 'bytes 2-5/36'],
    ['bytes=24-', 'opqrstuvwxyz', 'bytes 24-35/36'],
    ['bytes=-4', 'wxyz', 'bytes 32-35/36'],
    ['bytes=-999', data.toString(), 'bytes 0-35/36'],
    ['bytes=24-999', 'opqrstuvwxyz', 'bytes 24-35/36'],
    ['BYTES=0-1', '01', 'bytes 0-1/36'],
  ]) {
    const response = await f.handler(f.request({ range }));
    assert.equal(response.status, 206, range);
    assert.equal(await response.text(), expected, range);
    assert.equal(response.headers.get('Content-Range'), contentRange, range);
    assert.equal(response.headers.get('Content-Length'), String(Buffer.byteLength(expected)), range);
    assert.equal(response.headers.get('Content-Type'), 'video/mp4', range);
  }
  assert.equal(f.reads.length, 0);
  assert.ok(f.hostPaths.every(actualRef => actualRef === ref));

  const head = await f.handler(f.request({ method: 'HEAD', range: 'bytes=2-5' }));
  assert.equal(head.status, 206);
  assert.equal(head.body, null);
  assert.equal(head.headers.get('Content-Range'), 'bytes 2-5/36');
  assert.equal(head.headers.get('Content-Length'), '4');
  assert.equal(f.reads.length, 0);
});

test('invalid, multiple, and unsatisfiable ranges return 416 without touching storage', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'chat-attachments-416-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const data = Buffer.from('0123456789');
  const path = join(directory, 'provider-object');
  await writeFile(path, data);
  const ref = { attachmentId: ID, name: 'clip.webm', bytes: data.byteLength };
  const f = fixture({ ref, fileHostPath: () => path });

  for (const range of [
    'bytes=10-', 'bytes=-0', 'bytes=9-2', 'bytes=0-1,4-5', 'items=0-1',
    'bytes=abc', 'bytes=-', 'bytes=0-1,',
  ]) {
    const response = await f.handler(f.request({ range }));
    assert.equal(response.status, 416, range);
    assert.equal(response.headers.get('Content-Range'), 'bytes */10', range);
    assert.equal(response.headers.get('Content-Length'), '0', range);
    assert.equal((await response.arrayBuffer()).byteLength, 0, range);
  }
  assert.equal(f.hostPaths.length, 0);
  assert.equal(f.reads.length, 0);

  const empty = fixture({ ref: { attachmentId: ID, name: 'empty.mp4', bytes: 0 }, chunks: [] });
  const emptyRange = await empty.handler(empty.request({ range: 'bytes=0-' }));
  assert.equal(emptyRange.status, 416);
  assert.equal(emptyRange.headers.get('Content-Range'), 'bytes */0');
});

test('host range reads reject missing or length-mismatched files without exposing paths', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'chat-attachments-stat-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const ref = { attachmentId: ID, name: 'clip.mov', bytes: 4 };
  for (const path of [join(directory, 'missing'), join(directory, 'short')]) {
    if (path.endsWith('short')) await writeFile(path, 'abc');
    const f = fixture({ ref, fileHostPath: () => path });
    const response = await f.handler(f.request({ range: 'bytes=0-1' }));
    assert.equal(response.status, 404);
    const text = await response.text();
    assert.equal(text, 'file not found');
    assert.ok(!text.includes(directory));
    assert.equal(f.reads.length, 0);
  }
});

test('a provider without host paths still serves a bounded range and closes its prefix stream', async () => {
  let finalized = 0;
  const f = fixture({
    ref: { attachmentId: ID, name: 'clip.webm', bytes: 10 },
    readFileStream: () => (async function* () {
      try {
        yield Buffer.from('012');
        yield Buffer.from('3456');
        yield Buffer.from('789');
      } finally {
        finalized += 1;
      }
    })(),
  });
  const response = await f.handler(f.request({ range: 'bytes=3-6' }));
  assert.equal(response.status, 206);
  assert.equal(await response.text(), '3456');
  assert.equal(response.headers.get('Content-Range'), 'bytes 3-6/10');
  assert.equal(response.headers.get('Content-Length'), '4');
  assert.equal(finalized, 1);

  const head = await f.handler(f.request({ method: 'HEAD', range: 'bytes=-2' }));
  assert.equal(head.status, 206);
  assert.equal(head.body, null);
  assert.equal(f.reads.length, 1);
});

test('request abort errors the body and actually finalizes the provider iterator', { timeout: 2000 }, async () => {
  const requestAbort = new AbortController();
  let markFinalized;
  const finalized = new Promise(resolve => { markFinalized = resolve; });
  const f = fixture({
    ref: { attachmentId: ID, name: 'cancel.bin', bytes: 2 },
    readFileStream: () => (async function* () {
      try {
        yield Uint8Array.of(1);
        yield Uint8Array.of(2);
      } finally {
        markFinalized();
      }
    })(),
  });
  const response = await f.handler(f.request({ signal: requestAbort.signal }));
  const reader = response.body.getReader();
  assert.deepEqual(await reader.read(), { done: false, value: Uint8Array.of(1) });
  requestAbort.abort();
  await finalized;
  assert.equal(f.reads[0].signal.aborted, true);
  await assert.rejects(reader.read(), error => error?.name === 'AbortError');
});

test('consumer cancellation aborts the read signal and closes the provider iterator', { timeout: 2000 }, async () => {
  let finalized = 0;
  const f = fixture({
    ref: { attachmentId: ID, name: 'cancel.bin', bytes: 2 },
    readFileStream: () => (async function* () {
      try {
        yield Uint8Array.of(1);
        yield Uint8Array.of(2);
      } finally {
        finalized += 1;
      }
    })(),
  });
  const response = await f.handler(f.request());
  const reader = response.body.getReader();
  await reader.read();
  await reader.cancel();
  assert.equal(finalized, 1);
  assert.equal(f.reads[0].signal.aborted, true);
});

test('chunk pulls follow downstream demand instead of aggregating the attachment', async () => {
  let pulls = 0;
  let finalized = 0;
  const f = fixture({
    ref: { attachmentId: ID, name: 'backpressure.bin', bytes: 50 },
    readFileStream: () => (async function* () {
      try {
        for (let index = 0; index < 50; index += 1) {
          pulls += 1;
          yield Uint8Array.of(index);
        }
      } finally {
        finalized += 1;
      }
    })(),
  });
  const response = await f.handler(f.request());
  await delay(10);
  assert.equal(pulls, 0);
  const reader = response.body.getReader();
  assert.deepEqual(await reader.read(), { done: false, value: Uint8Array.of(0) });
  await delay(10);
  assert.equal(pulls, 1);
  assert.deepEqual(await reader.read(), { done: false, value: Uint8Array.of(1) });
  assert.equal(pulls, 2);
  await reader.cancel();
  assert.equal(finalized, 1);
  assert.equal(pulls, 2);
});

test('stream failures do not pass provider paths or original errors to the consumer', async () => {
  const f = fixture({
    readFileStream: () => (async function* () {
      throw new Error('ENOENT at /private/attachments/secret');
    })(),
  });
  const response = await f.handler(f.request());
  await assert.rejects(response.body.getReader().read(), error => {
    assert.equal(error.message, 'File attachment read failed.');
    assert.ok(!error.message.includes('/private'));
    return true;
  });
});

test('connection registration is shared until the last Agent context is disposed', () => {
  const registrations = [];
  let disposalCount = 0;
  const connection = {
    fetch: {
      register(route) {
        registrations.push(route);
        let active = true;
        return () => {
          if (!active) return;
          active = false;
          disposalCount += 1;
        };
      },
    },
  };
  const contexts = [];
  const makeContext = () => {
    const cleanups = [];
    const ctx = {
      connection,
      sessionController: {},
      attachments: {},
      effect(setup) { cleanups.push(setup()); },
    };
    contexts.push(ctx);
    return () => { for (const cleanup of cleanups.splice(0)) cleanup(); };
  };
  const disposeFirst = makeContext();
  applyChatAttachments(contexts[0]);
  const disposeSecond = makeContext();
  applyChatAttachments(contexts[1]);

  assert.equal(registrations.length, 2);
  assert.equal(registrations[0].path, ROUTE);
  assert.deepEqual(registrations[0].methods, ['GET', 'HEAD']);
  assert.equal(typeof registrations[0].fetch, 'function');
  disposeFirst();
  assert.equal(disposalCount, 0);
  disposeFirst();
  assert.equal(disposalCount, 0);
  disposeSecond();
  assert.equal(disposalCount, 2);

  const disposeThird = makeContext();
  applyChatAttachments(contexts[2]);
  assert.equal(registrations.length, 4);
  disposeThird();
  assert.equal(disposalCount, 4);
});

test('generated video exceeds image limits and supports seeking through the real pinned filesystem provider', {
  skip: !process.env.PD_DSH_VENDOR_ROOT,
}, async () => {
  const {pathToFileURL}=await import('node:url');
  const {mkdir,truncate,symlink,realpath}=await import('node:fs/promises');
  const {LocalFileSystem}=await import(pathToFileURL(join(process.env.PD_DSH_VENDOR_ROOT,'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js')));
  const temp=await mkdtemp(join(tmpdir(),'yg-generated-video-'));
  try {
    const root=join(temp,'bot');await mkdir(root);
    const file=join(root,'视频 #1.mp4');await writeFile(file,'');await truncate(file,12*1024*1024);
    const {open}=await import('node:fs/promises');const fd=await open(file,'r+');await fd.write(Buffer.from('VIDEO'),0,5,10*1024*1024);await fd.close();
    const provider=Object.create(LocalFileSystem.prototype);provider.config={cwd:temp};
    const fs={
      resolve:(p,opts)=>provider.resolve(p.replace('/BOT/default',root),opts),
      contains:provider.contains.bind(provider),stat:provider.stat.bind(provider),processPath:provider.processPath.bind(provider),
    };
    const handler=createWorkspaceFileHandler({fs});
    const request=(p='/BOT/default/视频 #1.mp4',method='GET',range)=>new Request('http://localhost/api/yugang/workspace-file?path='+encodeURIComponent(p),{method,headers:range?{Range:range}:{}});
    const head=await handler(request(undefined,'HEAD'));
    assert.equal(head.status,200);assert.equal(head.headers.get('Content-Length'),String(12*1024*1024));
    const chunk=await handler(request(undefined,'GET',`bytes=${10*1024*1024}-${10*1024*1024+4}`));
    assert.equal(chunk.status,206);assert.equal(chunk.headers.get('Content-Type'),'video/mp4');assert.equal(await chunk.text(),'VIDEO');
    const suffix=await handler(request(undefined,'GET','bytes=-5'));assert.equal(suffix.status,206);assert.equal((await suffix.arrayBuffer()).byteLength,5);
    assert.equal((await handler(request(undefined,'GET','bytes=99999999-'))).status,416);
    assert.equal((await handler(request('/BOT/default/../secret'))).status,400);
    const outside=join(temp,'private.txt');await writeFile(outside,'private');await symlink(outside,join(root,'escape.txt'));
    assert.equal((await handler(request('/BOT/default/escape.txt'))).status,403);
    assert.equal((await handler(request('/CONFIG/key'))).status,400);
  } finally { await rm(temp,{recursive:true,force:true}); }
});
