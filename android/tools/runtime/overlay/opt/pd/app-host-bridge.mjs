// Native plugin/preview channel: abstract Unix socket, same Android UID only.
//
// PD_HOST_RUNTIME_SOCKET carries only an abstract socket name—never a credential. The socket
// factory is injectable so tests can exercise framing, cancellation and size limits offline.
import { createConnection } from 'node:net';

export const MAX_REQUEST_BYTES = 8 * 1024 * 1024;
export const MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
export const SOCKET_TIMEOUT_MS = 80_000;

export function resolveHostRuntimeTarget(options = {}) {
  if (options.socketPath) return options.socketPath;
  const name = options.socketName !== undefined ? options.socketName : process.env.PD_HOST_RUNTIME_SOCKET;
  return name ? '\0' + name : '';
}

function defaultConnect(target) {
  return createConnection({ path: target });
}

/**
 * @param {{ socketName?: string, socketPath?: string, connect?: (target: string) => object }} [options]
 */
export function createHostRuntimeClient(options = {}) {
  const connect = typeof options.connect === 'function' ? options.connect : defaultConnect;

  function request(action, payload, signal) {
    const target = resolveHostRuntimeTarget(options);
    if (!target) return Promise.reject(new Error('宿主插件通道未就绪'));
    if (signal?.aborted) return Promise.reject(new Error('操作已取消'));
    const body = JSON.stringify({ action, ...(payload ?? {}) }) + '\n';
    if (Buffer.byteLength(body) > MAX_REQUEST_BYTES) return Promise.reject(new Error('宿主插件请求过大'));
    return new Promise((resolve, reject) => {
      let socket;
      try { socket = connect(target); }
      catch { reject(new Error('宿主插件通道连接失败，请稍后重试')); return; }
      if (!socket) { reject(new Error('宿主插件通道连接失败，请稍后重试')); return; }
      let settled = false;
      let buffer = Buffer.alloc(0);
      const finish = (error, value) => {
        if (settled) return;
        settled = true;
        signal?.removeEventListener('abort', abort);
        socket.destroy();
        error ? reject(error) : resolve(value);
      };
      const abort = () => finish(new Error('操作已取消'));
      signal?.addEventListener('abort', abort, { once: true });
      socket.setTimeout(SOCKET_TIMEOUT_MS, () => finish(new Error('宿主插件请求超时，请稍后重试')));
      socket.on('connect', () => socket.write(body));
      socket.on('data', chunk => {
        if (settled) return;
        buffer = Buffer.concat([buffer, chunk]);
        if (buffer.length > MAX_RESPONSE_BYTES) return finish(new Error('宿主插件响应过大'));
        const end = buffer.indexOf(10);
        if (end < 0) return;
        let reply;
        try { reply = JSON.parse(buffer.subarray(0, end).toString('utf8')); }
        catch { return finish(new Error('宿主插件响应无效')); }
        if (reply && typeof reply.error === 'string') return finish(new Error(reply.error));
        finish(null, reply && typeof reply === 'object' ? reply.value : undefined);
      });
      socket.on('error', () => finish(new Error('宿主插件通道连接失败，请稍后重试')));
      socket.on('end', () => { if (!settled) finish(new Error('宿主插件通道提前关闭')); });
      if (signal?.aborted) abort();
    });
  }

  return { request };
}

export const hostRuntimeClient = createHostRuntimeClient();
export default { createHostRuntimeClient, hostRuntimeClient, resolveHostRuntimeTarget };
