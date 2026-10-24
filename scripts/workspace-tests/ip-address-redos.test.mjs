import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';

const npmRoot = process.env.PD_NPM_ROOT;
assert.ok(npmRoot, 'PD_NPM_ROOT must point to npm extracted from the packaged runtime');
const requireFromNpm = createRequire(npmRoot + '/package.json');
const requireFromSocks = createRequire(requireFromNpm.resolve('socks/package.json'));
const parser = requireFromSocks.resolve('ip-address');

test('npm IPv6 parser rejects the advisory input without blocking its caller', () => {
  const code = `
const {Address6}=require(process.argv[1]);
console.log('INPUT_READY');
try { new Address6('2001:db8::1' + '%'.repeat(100000) + '\\n'); }
catch { console.log('INVALID_REJECTED'); }
`;
  const run = spawnSync(process.execPath, ['-e', code, parser], {
    encoding: 'utf8', timeout: 2000, env: {PATH: process.env.PATH},
  });
  assert.match(run.stdout || '', /INPUT_READY/);
  assert.ifError(run.error);
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /INVALID_REJECTED/);
});

test('the bundled SOCKS consumer resolves valid IPv4 and IPv6 addresses', () => {
  const {Address4, Address6} = requireFromSocks('ip-address');
  assert.equal(new Address4('192.0.2.1').correctForm(), '192.0.2.1');
  assert.equal(new Address6('2001:db8::1').correctForm(), '2001:db8::1');
  assert.equal(new Address6('fe80::1%eth0').zone, '%eth0');
  assert.equal(typeof requireFromSocks('socks').SocksClient.createConnection, 'function');
});
