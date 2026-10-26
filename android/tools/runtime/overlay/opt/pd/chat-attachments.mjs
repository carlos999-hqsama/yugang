import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';

const CHAT_ATTACHMENT_PATH = '/api/yugang/session-file';

const MAX_FILE_BYTES = 256 * 1024 * 1024;
const ATTACHMENT_ID = /^sha256:[0-9a-f]{64}$/;
const BASE_HEADERS = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};
const MEDIA_TYPES = {
  txt: 'text/plain; charset=utf-8',
  text: 'text/plain; charset=utf-8',
  log: 'text/plain; charset=utf-8',
  md: 'text/plain; charset=utf-8',
  markdown: 'text/plain; charset=utf-8',
  csv: 'text/csv; charset=utf-8',
  json: 'application/json; charset=utf-8',
  jsonl: 'application/x-ndjson; charset=utf-8',
  ndjson: 'application/x-ndjson; charset=utf-8',
  xml: 'application/xml; charset=utf-8',
  yaml: 'application/yaml; charset=utf-8',
  yml: 'application/yaml; charset=utf-8',
  html: 'text/html; charset=utf-8',
  htm: 'text/html; charset=utf-8',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  avif: 'image/avif',
  bmp: 'image/bmp',
  svg: 'image/svg+xml',
  ico: 'image/x-icon',
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  odt: 'application/vnd.oasis.opendocument.text',
  ods: 'application/vnd.oasis.opendocument.spreadsheet',
  odp: 'application/vnd.oasis.opendocument.presentation',
  zip: 'application/zip',
  mp4: 'video/mp4',
  webm: 'video/webm',
  mov: 'video/quicktime',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  m4a: 'audio/mp4',
  aac: 'audio/aac',
  ogg: 'audio/ogg',
  flac: 'audio/flac',
  opus: 'audio/ogg',
};

function errorResponse(request, status, text, headers = {}) {
  return new Response(request.method === 'HEAD' ? null : text, {
    status,
    headers: { ...BASE_HEADERS, ...headers },
  });
}

function abortError() {
  return new DOMException('The request was aborted.', 'AbortError');
}

function mediaType(name) {
  const match = /[^.]\.([a-z0-9]+)$/i.exec(name);
  const extension = match?.[1]?.toLowerCase();
  return extension !== undefined && Object.hasOwn(MEDIA_TYPES, extension)
    ? MEDIA_TYPES[extension]
    : 'application/octet-stream';
}

function encodedFilename(name) {
  return encodeURIComponent(name.toWellFormed()).replace(/[!'()*]/g, character =>
    `%${character.charCodeAt(0).toString(16).toUpperCase()}`);
}

function fileHeaders(ref, length) {
  const type = mediaType(ref.name);
  const headers = {
    ...BASE_HEADERS,
    'Content-Type': type,
    'Content-Disposition': `inline; filename*=UTF-8''${encodedFilename(ref.name)}`,
    'Accept-Ranges': 'bytes',
    'Content-Length': String(length),
  };
  // Do not let an inline active document acquire the authenticated API origin's privileges.
  if (type.startsWith('text/html') || type === 'image/svg+xml') {
    headers['Content-Security-Policy'] = "sandbox; default-src 'none'";
  }
  return headers;
}

function authorizedFile(events, attachmentId, name) {
  if (!Array.isArray(events)) return null;
  for (const event of events) {
    if (event?.type !== 'user/message' || !Array.isArray(event.data?.content)) continue;
    for (const block of event.data.content) {
      if (block?.type !== 'file') continue;
      const ref = block.attachment;
      if (ref?.attachmentId !== attachmentId || ref?.name !== name) continue;
      if (!Number.isSafeInteger(ref.bytes) || ref.bytes < 0) return null;
      return ref;
    }
  }
  return null;
}

// undefined means no Range; null means an invalid, multiple, or unsatisfiable Range.
function parseByteRange(value, size) {
  if (value === null) return undefined;
  const match = /^bytes=(\d*)-(\d*)$/i.exec(value.trim());
  if (match === null || (match[1] === '' && match[2] === '') || size === 0) return null;
  try {
    const total = BigInt(size);
    if (match[1] === '') {
      const suffix = BigInt(match[2]);
      if (suffix <= 0n) return null;
      const start = suffix >= total ? 0n : total - suffix;
      return { start: Number(start), end: size - 1 };
    }
    const start = BigInt(match[1]);
    if (start >= total) return null;
    let end = match[2] === '' ? total - 1n : BigInt(match[2]);
    if (end < start) return null;
    if (end >= total) end = total - 1n;
    return { start: Number(start), end: Number(end) };
  } catch {
    return null;
  }
}

function asBytes(value) {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  throw new Error('Invalid attachment chunk.');
}

/**
 * Adapt a bounded async chunk source without prefetching or collecting it.
 * Both request abort and consumer cancellation close the actual source iterator.
 */
function attachmentBody(openSource, requestSignal) {
  if (requestSignal?.aborted) throw abortError();
  const readAbort = new AbortController();
  const source = openSource(readAbort.signal);
  if (source === null || typeof source?.[Symbol.asyncIterator] !== 'function') {
    throw new Error('Attachment provider did not return a chunk stream.');
  }
  const iterator = source[Symbol.asyncIterator]();
  let streamController;
  let finished = false;
  let closePromise;
  const closeSource = () => {
    closePromise ??= (async () => {
      try { await iterator.return?.(); } catch { /* the response has already ended */ }
    })();
    return closePromise;
  };
  const removeAbort = () => requestSignal?.removeEventListener('abort', onAbort);
  const failBody = error => {
    if (finished) return;
    finished = true;
    removeAbort();
    streamController.error(error);
  };
  const onAbort = () => {
    readAbort.abort();
    failBody(abortError());
    void closeSource();
  };
  const body = new ReadableStream({
    start(controller) {
      streamController = controller;
    },
    async pull(controller) {
      if (finished) return;
      try {
        const next = await iterator.next();
        if (finished) return;
        if (next.done) {
          finished = true;
          removeAbort();
          controller.close();
        } else {
          controller.enqueue(asBytes(next.value));
        }
      } catch {
        if (finished) return;
        readAbort.abort();
        failBody(requestSignal?.aborted ? abortError() : new Error('File attachment read failed.'));
        void closeSource();
      }
    },
    async cancel() {
      finished = true;
      removeAbort();
      readAbort.abort();
      await closeSource();
    },
  }, { highWaterMark: 0 });
  requestSignal?.addEventListener('abort', onAbort, { once: true });
  if (requestSignal?.aborted) onAbort();
  return body;
}

// A non-host-backed provider can still honor a range; the local provider uses
// the bounded filesystem range above instead of reading and discarding a prefix.
async function* providerRange(attachments, ref, range, signal) {
  const wanted = range.end - range.start + 1;
  let offset = 0;
  let emitted = 0;
  for await (const chunk of attachments.readFileStream(ref, signal)) {
    const bytes = asBytes(chunk);
    const start = offset;
    offset += bytes.byteLength;
    if (offset <= range.start) continue;
    const from = Math.max(0, range.start - start);
    const to = Math.min(bytes.byteLength, from + wanted - emitted);
    if (to > from) {
      yield bytes.subarray(from, to);
      emitted += to - from;
    }
    if (emitted === wanted) return;
  }
  if (offset !== ref.bytes || emitted !== wanted) throw new Error('File attachment length changed.');
}

/** Create the authenticated GET/HEAD session attachment handler. */
export function createChatAttachmentHandler({ sessionController, attachments }) {
  return async function chatAttachmentHandler(request) {
    const fail = (status, text, headers) => errorResponse(request, status, text, headers);
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return fail(405, 'method not allowed', { Allow: 'GET, HEAD' });
    }
    if (request.signal?.aborted) return fail(499, 'request aborted');

    let url;
    try { url = new URL(request.url); } catch { return fail(400, 'invalid request'); }
    const sessionValues = url.searchParams.getAll('sessionId');
    const idValues = url.searchParams.getAll('attachmentId');
    const nameValues = url.searchParams.getAll('name');
    if (sessionValues.length !== 1 || idValues.length !== 1 || nameValues.length !== 1 ||
        sessionValues[0] === '' || !ATTACHMENT_ID.test(idValues[0]) || nameValues[0] === '') {
      return fail(400, 'invalid attachment query');
    }

    let inspected;
    try {
      inspected = await sessionController.inspect(sessionValues[0], request.signal);
    } catch {
      return request.signal?.aborted ? fail(499, 'request aborted') : fail(404, 'file not found');
    }
    if (request.signal?.aborted) return fail(499, 'request aborted');
    const ref = authorizedFile(inspected?.events, idValues[0], nameValues[0]);
    if (ref === null) return fail(403, 'file not authorized');
    if (ref.bytes > MAX_FILE_BYTES) return fail(413, 'file exceeds byte limit');

    const range = parseByteRange(request.headers.get('range'), ref.bytes);
    if (range === null) {
      return new Response(null, {
        status: 416,
        headers: {
          ...fileHeaders(ref, 0),
          'Content-Range': `bytes */${ref.bytes}`,
        },
      });
    }

    const length = range === undefined ? ref.bytes : range.end - range.start + 1;
    const headers = fileHeaders(ref, length);
    if (range !== undefined) headers['Content-Range'] = `bytes ${range.start}-${range.end}/${ref.bytes}`;

    try {
      let openSource;
      if (range !== undefined && typeof attachments.fileHostPath === 'function') {
        const hostPath = attachments.fileHostPath(ref);
        if (hostPath !== undefined && hostPath !== null) {
          if (typeof hostPath !== 'string' || !isAbsolute(hostPath)) return fail(404, 'file not found');
          const info = await stat(hostPath);
          if (!info.isFile() || info.size !== ref.bytes) return fail(404, 'file not found');
          if (request.method === 'HEAD') return new Response(null, { status: 206, headers });
          openSource = signal => createReadStream(hostPath, {
            start: range.start,
            end: range.end,
            highWaterMark: 64 * 1024,
            signal,
          });
        }
      }
      if (request.signal?.aborted) return fail(499, 'request aborted');
      if (request.method === 'HEAD') {
        return new Response(null, { status: range === undefined ? 200 : 206, headers });
      }
      openSource ??= range === undefined
        ? signal => attachments.readFileStream(ref, signal)
        : signal => providerRange(attachments, ref, range, signal);
      const body = attachmentBody(openSource, request.signal);
      return new Response(body, { status: range === undefined ? 200 : 206, headers });
    } catch {
      return request.signal?.aborted ? fail(499, 'request aborted') : fail(404, 'file not found');
    }
  };
}

/** Generated Bot files use the composed provider's resolved process path, never image /api/file. */
export function createWorkspaceFileHandler({ fs }) {
  return async request => {
    const fail = (status, message, headers) => errorResponse(request, status, message, headers);
    if (!['GET', 'HEAD'].includes(request.method)) return fail(405, 'method not allowed', {Allow:'GET, HEAD'});
    try {
      request.signal.throwIfAborted();
      const values = new URL(request.url).searchParams.getAll('path');
      const path = values[0];
      const match = typeof path === 'string' && /^\/BOT\/(default|[0-9a-f-]{36})\/(.+)$/.exec(path);
      if (values.length !== 1 || !match || /[\\\x00-\x1f]/.test(path) || path.split('/').some(p => p === '.' || p === '..')) return fail(400, 'invalid workspace path');
      const root = await fs.resolve(`/BOT/${match[1]}`, {signal:request.signal});
      const target = await fs.resolve(path, {signal:request.signal});
      if (!fs.contains(root, target)) return fail(403, 'file outside workspace');
      const info = await fs.stat(target, request.signal);
      if (info?.type !== 'file' || !Number.isSafeInteger(info.size) || info.size < 0) return fail(404, 'file not found');
      if (info.size > MAX_FILE_BYTES) return fail(413, 'file exceeds byte limit');
      const range = parseByteRange(request.headers.get('range'), info.size);
      if (range === null) return fail(416, 'range not satisfiable', {'Content-Range':`bytes */${info.size}`});
      const length = range ? range.end-range.start+1 : info.size;
      const headers = fileHeaders({name:path.split('/').pop()}, length);
      if (range) headers['Content-Range'] = `bytes ${range.start}-${range.end}/${info.size}`;
      const status = range ? 206 : 200;
      if (request.method === 'HEAD') return new Response(null, {status,headers});
      const processPath = fs.processPath(target);
      if (typeof processPath !== 'string' || !isAbsolute(processPath)) return fail(404, 'file not found');
      const body = attachmentBody(signal => createReadStream(processPath, {
        ...(range ? {start:range.start,end:range.end} : {}), highWaterMark:64*1024,signal,
      }), request.signal);
      return new Response(body, {status,headers});
    } catch { return request.signal.aborted ? fail(499,'request aborted') : fail(404,'file not found'); }
  };
}

// app-tools is instantiated per Agent, while the connection owns the route.
// Reference counting keeps one registration until the last applying context exits.
const connectionRoutes = new WeakMap();

export function applyChatAttachments(ctx) {
  const { connection } = ctx;
  ctx.effect(() => {
    let route = connectionRoutes.get(connection);
    if (route === undefined) {
      const disposeFiles = connection.fetch.register({
        path: CHAT_ATTACHMENT_PATH, methods: ['GET','HEAD'], fetch: createChatAttachmentHandler(ctx),
      });
      let disposeWorkspace;
      try {
        disposeWorkspace = connection.fetch.register({
          path:'/api/yugang/workspace-file', methods:['GET','HEAD'], fetch:createWorkspaceFileHandler(ctx),
        });
      } catch (error) { disposeFiles?.();throw error; }
      route = {users:0, dispose() { disposeWorkspace?.();disposeFiles?.(); }};
      connectionRoutes.set(connection, route);
    }
    route.users += 1;
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      route.users -= 1;
      if (route.users !== 0) return;
      connectionRoutes.delete(connection);
      route.dispose?.();
    };
  }, 'chat-attachments: session file route');
}

// Host-level registration also serves cold history before any Bot Agent is activated.
export const name = 'chat-attachments';
export const inject = ['connection', 'sessionController', 'attachments', 'fs'];
export const apply = applyChatAttachments;
export default {name, inject, apply};
