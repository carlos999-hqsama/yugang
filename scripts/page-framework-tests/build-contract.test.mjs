import assert from 'node:assert/strict';
import {test} from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const frameworkRoot = path.join(repository, 'android/app/src/main/assets/plugin-framework/v1');

function writePlugin(root) {
  const web = path.join(root, 'demo-plugin', 'web');
  fs.mkdirSync(web, {recursive: true});
  fs.writeFileSync(path.join(web, 'plugin.json'), JSON.stringify({
    manifest: {apiVersion: 1, frameworkVersion: 1, id: 'demo-plugin', title: 'Demo', version: '1.0.0', kind: 'native', capabilities: []},
    view: {type: 'page', entry: 'main.js', styles: ['main.css']},
  }));
  fs.writeFileSync(path.join(web, 'main.js'), `import {theme} from '/framework/v1/js/theme.js';\nimport {value} from './value.js';\nexport const ready = () => [theme.colorBg, value];\n`);
  fs.writeFileSync(path.join(web, 'value.js'), 'export const value = 42;\n');
  fs.writeFileSync(path.join(web, 'main.css'), "@import url('/framework/v1/css/theme.css');\n.main{color:var(--ui-text)}\n");
  fs.writeFileSync(path.join(root, 'demo-plugin', 'main.mjs'), 'export async function run(){return {view:{type:"text",text:"ok"}}}\n');
}

test('generic builder emits a standalone framework page package', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-plugin-build-'));
  const output = path.join(temporary, 'out');
  try {
    writePlugin(temporary);
    const run = spawnSync(process.execPath, [
      path.join(repository, 'scripts/build-page-plugins.mjs'),
      path.join(temporary),
      '--out', output,
    ], {encoding: 'utf8'});
    assert.equal(run.status, 0, run.stderr);
    const manifest = JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'), 'utf8'));
    assert.deepEqual(manifest, {frameworkVersion: 1, packages: [{id: 'demo-plugin', version: '1.0.0'}]});
    assert.equal(fs.existsSync(path.join(output, 'builtin.json')), false);

    const bundle = JSON.parse(fs.readFileSync(path.join(output, 'demo-plugin.json'), 'utf8'));
    assert.equal(bundle.manifest.kind, 'native');
    assert.equal(bundle.manifest.frameworkVersion, 1);
    assert.equal(bundle.manifest.entry, undefined);
    assert.equal(bundle.manifest.onOpen, undefined);
    assert.equal(bundle.view.type, 'page');
    assert.ok(Object.hasOwn(bundle.resources, bundle.view.entry));
    assert.equal(bundle.html, undefined);
    assert.equal(bundle.webFiles, undefined);
    assert.equal(Object.keys(bundle.resources).some(key => key.endsWith('.html')), false);
    assert.match(bundle.code, /export\s+async\s+function\s+run\s*\(/);

    const commonNames = new Set(['util.js', 'icons.js', 'native.js', 'ui.js', 'chat.js', 'chat-scroll.js', 'dsh.js', 'rt.js', 'models.js', 'design.css', 'theme.css', 'components.css', 'overlay.css', 'base.css', 'app.css', 'unified.css', 'settings.css']);
    for (const [key, source] of Object.entries(bundle.resources)) {
      assert.equal(commonNames.has(path.basename(key)), false, `duplicated common ${key}`);
      assert.equal(source.includes('/assets/www'), false, `${key} references host www`);
      assert.equal(/\bapp\.js\b/.test(source), false, `${key} references app.js`);
    }
  } finally {
    fs.rmSync(temporary, {recursive: true, force: true});
  }
});

test('entry imports and framework references close inside allowed roots', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-plugin-import-'));
  const output = path.join(temporary, 'out');
  try {
    writePlugin(temporary);
    const run = spawnSync(process.execPath, [path.join(repository, 'scripts/build-page-plugins.mjs'), temporary, '--out', output], {encoding: 'utf8'});
    assert.equal(run.status, 0, run.stderr);
    const bundle = JSON.parse(fs.readFileSync(path.join(output, 'demo-plugin.json'), 'utf8'));
    const visit = (key, seen = new Set()) => {
      if (seen.has(key)) return;
      seen.add(key);
      const source = bundle.resources[key];
      const patterns = [/\bfrom\s*['"]([^'"]+)['"]/g, /\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/g];
      for (const pattern of patterns) {
        for (const match of source.matchAll(pattern)) {
          const specifier = match[1];
          if (specifier.startsWith('/framework/v1/')) {
            const file = path.join(frameworkRoot, specifier.slice('/framework/v1/'.length));
            assert.ok(fs.existsSync(file), `missing shared ${specifier}`);
          } else if (specifier.startsWith('./')) {
            const target = path.posix.normalize(path.posix.join(path.posix.dirname(key), specifier));
            assert.equal(target.startsWith('../'), false, 'package escaped');
            assert.ok(Object.hasOwn(bundle.resources, target), `missing ${target}`);
            visit(target, seen);
          } else {
            assert.fail(`invalid import ${specifier}`);
          }
        }
      }
    };
    visit(bundle.view.entry);
    assert.equal(fs.existsSync(path.join(repository, 'android/app/src/main/assets/plugins/builtin.json')), false);
    assert.ok(fs.existsSync(path.join(frameworkRoot, 'page.js')));
    assert.ok(fs.existsSync(path.join(frameworkRoot, 'base.css')));
  } finally {
    fs.rmSync(temporary, {recursive: true, force: true});
  }
});
