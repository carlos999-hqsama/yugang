/**
 * 在 Node VM 里真实执行 www 源码（源码零改动）：
 * 依赖图按需加载，可以给某个 import 路径注入合成模块（假的原生桥 / 假 dsh）。
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

export const WWW = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../android/app/src/main/assets/www/js');


export function makeStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k) => (map.has(String(k)) ? map.get(String(k)) : null),
    setItem: (k, v) => { map.set(String(k), String(v)); },
    removeItem: (k) => { map.delete(String(k)); },
    clear: () => map.clear(),
    get length() { return map.size; },
  };
}

/**
 * @param {string} entry 相对 WWW 的入口文件名，例如 'harness.js'
 * @param {object} options
 *   stubs: { './dsh.js': (context) => vm.SyntheticModule }
 *   globals: 注入 sandbox 的全局对象
 */
export async function loadModule(entry, { stubs = {}, globals = {}, document } = {}) {
  const context = vm.createContext({ console, ...globals });
  const cache = new Map();
  const build = (file) => {
    if (cache.has(file)) return cache.get(file);
    const promise = (async () => {
      const source = fs.readFileSync(file, 'utf8');
      const mod = new vm.SourceTextModule(source, { context, identifier: path.basename(file) });
      await mod.link(async (specifier) => {
        const stubKey=specifier.replace("/framework/v1/js/","./");
        if (stubs[stubKey]) return stubs[stubKey](context);
        const dep = specifier.startsWith("/framework/v1/js/") ? path.join(FRAMEWORK,specifier.slice("/framework/v1/js/".length)) : path.resolve(path.dirname(file), specifier);
        if (!fs.existsSync(dep)) throw new Error(`缺少依赖：${specifier}（来自 ${path.basename(file)}）`);
        return build(dep);
      });
      return mod;
    })();
    cache.set(file, promise);
    return promise;
  };
  const mod = await build(path.join(WWW, entry));
  await mod.evaluate();
  return { ns: mod.namespace, context };
}

/** 生成一个合成模块（注入假的依赖）。 */
export function synthetic(context, exports) {
  return new vm.SyntheticModule(Object.keys(exports), function init() {
    for (const [key, value] of Object.entries(exports)) this.setExport(key, value);
  }, { context });
}
