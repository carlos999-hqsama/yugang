/**
 * 交互回归测试入口：node scripts/interaction-tests/run.mjs
 * 需要 vm.SourceTextModule（Node 自带，但需 --experimental-vm-modules）。
 */
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

if (typeof vm.SourceTextModule !== 'function') {
  const result = spawnSync(process.execPath, ['--experimental-vm-modules', '--disable-warning=ExperimentalWarning', path.join(here, 'run.mjs'), ...process.argv.slice(2)], {
    stdio: 'inherit',
  });
  process.exit(result.status === null ? 1 : result.status);
}

const FILES = [
  './attachment-transport.test.mjs',
  './harness.test.mjs',
  './agent-voice.test.mjs',
  './file-links.test.mjs',
  './bot-workspace.test.mjs',
  './startup.test.mjs',
  './plugin-onboarding.test.mjs',
  './settings.test.mjs',
  './model-settings.test.mjs',
  './ui.test.mjs',
  './static.test.mjs',
];

for (const file of FILES) {
  console.log(`\n=== ${file} ===`);
  await import(file);
}
