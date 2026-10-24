// Trusted phone-agent code. Node / same Android UID, not a hostile-code sandbox.
// No model keys, cookies or tokens are injected. Native capabilities return public data.
import { pathToFileURL } from 'node:url';
import { hostRuntimeClient } from './app-host-bridge.mjs';

export async function execute(request, capability = (id, name, args, revision, invocationId) =>
  hostRuntimeClient.request('plugin_capability', { id, capability: name, arguments: args, revision, invocationId })) {
  const {bundle, action, inputs = {}, state = {}} = request;
  const id = bundle.manifest.id;
  if (bundle.manifest.kind !== 'native' || !bundle.code) throw Error('Plugin has no executable module');
  const module = await import('data:text/javascript;base64,' + Buffer.from(bundle.code).toString('base64'));
  if (typeof module.run !== 'function') throw Error('Plugin must export run');
  const sdk = Object.freeze({ call(name, args = {}) {
    return capability(id, name, args, bundle._revision, request.invocationId);
  }});
  const result = await module.run({action, inputs, state}, sdk);
  if (!result || typeof result !== 'object' || !result.view) throw Error('Plugin must return a view');
  const encoded = JSON.stringify(result);
  if (Buffer.byteLength(encoded) > 131072) throw Error('Plugin result too large');
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const write = process.stdout.write.bind(process.stdout);
  // Keep a single JSON response channel, with plugin console output sent to bounded stderr.
  for (const name of ['log', 'info', 'warn', 'error', 'debug']) console[name] = (...args) => process.stderr.write(args.map(String).join(' ') + '\n');
  try {
    let input = '';
    for await (const chunk of process.stdin) { input += chunk; if (Buffer.byteLength(input) > 200000) throw Error('Input too large'); }
    const value = await execute(JSON.parse(input));
    write(JSON.stringify({value}));
  } catch (error) { write(JSON.stringify({error: 'plugin_failed', detail: String(error?.message || '执行失败').slice(0,400)})); process.exitCode = 1; }
}
