#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frameworkRoot = path.join(repository, 'android/app/src/main/assets/plugin-framework/v1');
const frameworkPrefix = '/framework/v1/';
const maxPackageBytes = 2 * 1024 * 1024;
const maxCodeBytes = 64 * 1024;

function argument(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index < 0) return fallback;
  const value = process.argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`${name} needs a value`);
  return value;
}

const positional = process.argv.slice(2).filter(value => !value.startsWith('--'));
const sourceRoot = path.resolve(repository, positional[0] || 'plugins');
const outputRoot = path.resolve(repository, argument('--out', 'outputs/plugins'));
const fail = message => { throw new Error(message); };
const bytes = value => Buffer.byteLength(value);
const normalizeResource = (from, specifier) => path.posix.normalize(path.posix.join(path.posix.dirname(from), specifier));

function walkFiles(directory, prefix = '') {
  const files = [];
  for (const entry of fs.readdirSync(directory, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const key = `${prefix}${entry.name}`;
    if (entry.isDirectory()) files.push(...walkFiles(path.join(directory, entry.name), `${key}/`));
    else if (entry.isFile()) files.push({key, file: path.join(directory, entry.name)});
    else fail(`不支持的文件类型: ${key}`);
  }
  return files;
}

function moduleSpecifiers(source) {
  const patterns = [
    /\bfrom\s*(?:'([^']+)'|"([^"]+)")/g,
    /\bimport\s*\(\s*(?:'([^']+)'|"([^"]+)")\s*\)/g,
    /(?:^|\n)\s*import\s*(?:'([^']+)'|"([^"]+)")/g,
  ];
  return patterns.flatMap(pattern => [...source.matchAll(pattern)].map(match => match[1] || match[2]));
}

function cssUrls(source) {
  const patterns = [
    /@import\s+(?:url\()?\s*(?:'([^']+)'|"([^"]+)"|([^)'"\s]+))/gi,
    /url\(\s*(?:'([^']+)'|"([^"]+)"|([^)'"\s]+))/gi,
  ];
  return patterns.flatMap(pattern => [...source.matchAll(pattern)].map(match => match[1] || match[2] || match[3]).filter(Boolean));
}

function safeFrameworkPath(url) {
  if (!url.startsWith(frameworkPrefix)) return null;
  const relative = path.normalize(decodeURIComponent(url.slice(frameworkPrefix.length)));
  if (relative.startsWith('..') || path.isAbsolute(relative)) fail(`共享框架路径越界: ${url}`);
  const file = path.join(frameworkRoot, relative);
  if (!file.startsWith(frameworkRoot) || !fs.statSync(file, {throwIfNoEntry: false})?.isFile()) fail(`共享框架文件不存在: ${url}`);
  return {relative, file};
}

function collectResources(pluginId, webRoot) {
  const resources = {};
  for (const {key, file} of walkFiles(webRoot)) {
    if (key === 'plugin.json' || key === 'action-build.json') continue;
    if (!/\.(?:js|css|svg|json)$/.test(key) || key.endsWith('.html')) fail(`${pluginId}: 插件资源只允许 js/css/svg/json，发现 ${key}`);
    resources[key] = fs.readFileSync(file, 'utf8');
  }
  return resources;
}

function validatePageContract(pluginId, metadata, resources) {
  const view = metadata.view;
  const manifest = metadata.manifest;
  if (manifest.id !== pluginId) fail(`${pluginId}: manifest.id 不匹配`);
  if (manifest.apiVersion !== 1) fail(`${pluginId}: apiVersion 必须为 1`);
  if (manifest.kind !== 'native') fail(`${pluginId}: kind 必须为 native`);
  if (manifest.frameworkVersion !== 1) fail(`${pluginId}: frameworkVersion 必须为 1`);
  if ('entry' in manifest || 'onOpen' in manifest) fail(`${pluginId}: manifest 不能包含 entry/onOpen`);
  if (view?.type !== 'page') fail(`${pluginId}: view.type 必须为 page`);
  if (typeof view.entry !== 'string' || !Object.hasOwn(resources, view.entry)) fail(`${pluginId}: view.entry 不在 resources 中`);
  if (!Array.isArray(view.styles) || !view.styles.length) fail(`${pluginId}: view.styles 必须是非空数组`);
  const seen = new Set();
  for (const style of view.styles) {
    if (typeof style !== 'string' || seen.has(style) || !Object.hasOwn(resources, style)) fail(`${pluginId}: 插件样式缺失或重复 ${style}`);
    seen.add(style);
  }
  return {manifest, view};
}

function validateImports(pluginId, entry, resources) {
  const local = new Set([entry]);
  const shared = new Set();
  const edges = new Set();
  const queue = [{kind: 'resource', key: entry}];
  while (queue.length) {
    const item = queue.shift();
    const from = item.kind === 'resource' ? item.key : frameworkPrefix + item.key;
    const source = item.kind === 'resource' ? resources[item.key] : fs.readFileSync(item.file, 'utf8');
    for (const specifier of moduleSpecifiers(source)) {
      if (/^[a-z][a-z0-9+.-]*:/i.test(specifier) || specifier.startsWith('//')) fail(`${pluginId}: 不允许外部模块 ${specifier}`);
      if (specifier.startsWith('/')) {
        const target = safeFrameworkPath(specifier);
        if (!target) fail(`${pluginId}: 绝对导入只能指向 /framework/v1/ ${specifier}`);
        const edge = `${from} -> ${specifier}`;
        if (edges.has(edge)) continue;
        edges.add(edge);
        if (!shared.has(target.relative)) {
          shared.add(target.relative);
          queue.push({kind: 'framework', key: target.relative, file: target.file});
        }
      } else if (specifier.startsWith('./')) {
        const target = normalizeResource(item.kind === 'resource' ? item.key : `/${item.key}`, specifier);
        failIfInvalidLocal(pluginId, target, resources, local, queue);
      } else fail(`${pluginId}: 不支持裸导入 ${specifier}`);
    }
    if (item.kind === 'resource' && item.key.endsWith('.css')) {
      for (const url of cssUrls(source)) {
        if (!url.startsWith(frameworkPrefix)) continue;
        safeFrameworkPath(url);
      }
    }
  }
}

function failIfInvalidLocal(pluginId, target, resources, local, queue) {
  if (target.startsWith('../') || target === '..' || target.startsWith('/')) fail(`${pluginId}: 本地模块越界 ${target}`);
  if (!Object.hasOwn(resources, target)) fail(`${pluginId}: 缺少本地模块 ${target}`);
  if (!local.has(target)) {
    local.add(target);
    queue.push({kind: 'resource', key: target});
  }
}

function buildPlugin(source) {
  const webRoot = path.join(source, 'web');
  const metadataFile = path.join(webRoot, 'plugin.json');
  if (!fs.statSync(webRoot, {throwIfNoEntry: false})?.isDirectory() || !fs.statSync(metadataFile, {throwIfNoEntry: false})?.isFile()) return null;
  const metadata = JSON.parse(fs.readFileSync(metadataFile, 'utf8'));
  const pluginId = metadata.manifest?.id || fail(`${source}: plugin.json 缺少 manifest.id`);
  const resources = collectResources(pluginId, webRoot);
  const {manifest, view} = validatePageContract(pluginId, metadata, resources);
  validateImports(pluginId, view.entry, resources);
  const actionFile = path.join(source, 'main.mjs');
  const bundle = {manifest, view, resources};
  if (fs.statSync(actionFile, {throwIfNoEntry: false})?.isFile()) {
    const code = fs.readFileSync(actionFile, 'utf8');
    if (bytes(code) > maxCodeBytes) fail(`${pluginId}: action-build 超过 64 KiB`);
    if (!/export\s+async\s+function\s+run\s*\(/.test(code)) fail(`${pluginId}: action-build 缺少 run 导出`);
    bundle.code = code;
  }
  const encoded = JSON.stringify(bundle);
  if (bytes(encoded) > maxPackageBytes) fail(`${pluginId}: 插件包超过 2 MiB`);
  return {pluginId, bundle};
}

if (!fs.statSync(sourceRoot, {throwIfNoEntry: false})?.isDirectory()) {
  throw new Error(`插件源码目录不存在: ${path.relative(repository, sourceRoot)}`);
}
fs.mkdirSync(outputRoot, {recursive: true});
const packages = [];
for (const entry of fs.readdirSync(sourceRoot, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
  if (!entry.isDirectory() || entry.name.startsWith('.') || entry.name === 'node_modules') continue;
  const result = buildPlugin(path.join(sourceRoot, entry.name));
  if (!result) continue;
  fs.writeFileSync(path.join(outputRoot, `${result.pluginId}.json`), JSON.stringify(result.bundle));
  packages.push({id: result.pluginId, version: result.bundle.manifest.version});
}
fs.writeFileSync(path.join(outputRoot, 'manifest.json'), JSON.stringify({frameworkVersion: 1, packages}, null, 2) + '\n');
console.log(JSON.stringify({output: path.relative(repository, outputRoot), packages: packages.map(item => item.id)}, null, 2));
