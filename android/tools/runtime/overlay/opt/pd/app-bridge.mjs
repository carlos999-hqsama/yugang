// Native settings channel: abstract Unix socket, same Android UID only.
import { createConnection } from 'node:net';

export function publicSettings(value) {
  if (!value || typeof value.keepAwakeWhileRunning !== 'boolean') throw new Error('App 返回的设置格式无效');
  return { keepAwakeWhileRunning: value.keepAwakeWhileRunning };
}

export function requestApp(action, patch, signal) {
  const name = process.env.PD_APP_SOCKET;
  if (!name) return Promise.reject(new Error('App 设置通道未就绪，请使用内置对话运行时'));
  if (signal?.aborted) return Promise.reject(new Error('操作已取消'));
  const body = JSON.stringify(action === 'get' ? { action } : { action, patch }) + '\n';
  if (Buffer.byteLength(body) > 8192) return Promise.reject(new Error('App 设置请求过长'));
  return new Promise((resolve, reject) => {
    let socket;
    try { socket = createConnection({ path: '\0' + name }); }
    catch { reject(new Error('App 设置通道连接失败，请稍后重试')); return; }
    let settled = false, buffer = Buffer.alloc(0);
    const finish = (error, value) => {
      if (settled) return;
      settled = true; signal?.removeEventListener('abort', abort); socket.destroy();
      error ? reject(error) : resolve(value);
    };
    const abort = () => finish(new Error('操作已取消'));
    signal?.addEventListener('abort', abort, { once: true });
    socket.setTimeout(5000, () => finish(new Error('App 设置请求超时，请先查询当前值再决定是否重试')));
    socket.on('connect', () => socket.write(body));
    socket.on('data', chunk => {
      if (settled) return;
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > 8192) return finish(new Error('App 设置响应过长'));
      const end = buffer.indexOf(10); if (end < 0) return;
      try {
        const reply = JSON.parse(buffer.subarray(0, end).toString('utf8'));
        if (typeof reply.error === 'string') return finish(new Error(reply.error));
        finish(null, publicSettings(reply.value));
      } catch { finish(new Error('App 设置响应无效')); }
    });
    socket.on('error', () => finish(new Error('App 设置通道连接失败，请稍后重试')));
    socket.on('end', () => { if (!settled) finish(new Error('App 设置通道提前关闭')); });
    if (signal?.aborted) abort();
  });
}
