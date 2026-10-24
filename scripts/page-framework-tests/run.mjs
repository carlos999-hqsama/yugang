import {spawnSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const tests = ['framework.test.mjs', 'native-network.test.mjs', 'build-contract.test.mjs'];
for (const test of tests) {
  const run = spawnSync(process.execPath, ['--test', path.join(root, test)], {stdio: 'inherit'});
  if (run.status) process.exit(run.status);
}
console.log('page framework synthetic tests: PASS');
