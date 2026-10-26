import { native } from './js/native.js';
import { closeMenu, closeSheet } from './js/ui.js';
import { closeModel } from './js/models.js';
import { watchModelRuntime } from './js/models.js';
watchModelRuntime();
import { Button, Card, List, Text } from './js/components.js';
import { theme } from './js/theme.js';

const pageMarker = Symbol('pd.page');
let current = null;
let layout = '';
let resizeBound = false;

export function definePage(options = {}) {
  if (typeof options !== 'object' || Array.isArray(options)) throw new Error('definePage 参数必须是对象');
  const page = {...options, [pageMarker]: true};
  if (page.data !== undefined && (typeof page.data !== 'object' || page.data === null || Array.isArray(page.data))) {
    throw new Error('page.data 必须是对象');
  }
  for (const key of ['init', 'mount', 'render', 'start', 'stop', 'onShow', 'onHide', 'onResize', 'onUnload', 'back']) {
    if (page[key] !== undefined && typeof page[key] !== 'function') throw new Error(`页面钩子 ${key} 必须是函数`);
  }
  return page;
}

function isPage(value) {
  return !!value && typeof value === 'object' && value[pageMarker] === true;
}

function ensureChrome(pluginId) {
  const body = document.body;
  body.classList.add('pd-plugin-page');
  body.classList.toggle('native-platform', Boolean(window.PDNative));

  let root = document.getElementById('plugin-root');
  if (!root) {
    root = document.createElement('main');
    root.id = 'plugin-root';
    root.className = 'page-surface';
    body.prepend(root);
  }
  root.classList.add('page-surface');
  root.dataset.pluginId = pluginId || '';

  for (const id of ['shade', 'menu', 'sheet', 'toast']) {
    let element = document.getElementById(id);
    if (!element) {
      element = document.createElement('div');
      element.id = id;
      body.append(element);
    }
  }
  const sheet = document.getElementById('sheet');
  if (!sheet.querySelector('.mask')) {
    const mask = document.createElement('div');
    mask.className = 'mask';
    sheet.prepend(mask);
  }
  if (!sheet.querySelector('.pane')) {
    const pane = document.createElement('div');
    pane.className = 'pane';
    sheet.append(pane);
  }
  return root;
}

function applyLayout(force = false) {
  const inner = window.innerWidth >= 600;
  const next = inner ? 'inner' : 'cover';
  if (!force && next === layout) return false;
  layout = next;
  document.body.classList.toggle('inner', inner);
  document.body.classList.toggle('cover', !inner);
  return true;
}

function showError(error) {
  const root = document.getElementById('plugin-root');
  if (!root) return;
  const detail = String(error?.message || error || '页面启动失败');
  root.replaceChildren();
  const card = Card({
    title: '页面启动失败',
    subtitle: '插件页面未能完成初始化',
    children: [
      Text(detail, {variant: 'muted'}),
      Button({
        text: '重试',
        variant: 'primary',
        onClick: () => window.location.reload(),
      }),
    ],
  });
  card.classList.add('pf-error');
  root.append(card);
}

function defaultRender(context) {
  const data = context.data;
  context.root.replaceChildren();
  const page = document.createElement('div');
  page.className = 'pf-page';
  page.append(Card({
    title: data.title || '',
    subtitle: data.subtitle || '',
    children: [
      Array.isArray(data.items) ? List({items: data.items, onSelect: data.onSelect}) : Text(data.body || '', {variant: 'muted'}),
    ],
  }));
  context.root.append(page);
}

class PageRuntime {
  constructor(definition, root, pluginId) {
    this.definition = definition;
    this.pluginId = pluginId;
    this.data = {...definition.data || {}};
    this.visible = false;
    this.mounted = false;
    this.initialized = false;
    this.renderScheduled = false;
    this.renderGeneration = 0;
    this.transition = Promise.resolve();
    this.visibilityGeneration = 0;
    this.running = false;
    this.root = root;
    this.context = {
      pluginId,
      root,
      theme,
      data: this.data,
      page: this,
      setData: this.setData.bind(this),
      render: this.render.bind(this),
      navigate: id => native.call('pluginNavigate', {id}),
      toast: text => native.toast(text),
    };
  }

  async initialize() {
    if (this.initialized) return;
    this.initialized = true;
    await this.definition.init?.(this.context);
  }

  async mount() {
    if (this.mounted) return;
    await this.initialize();
    if (this.definition.mount) await this.definition.mount(this.context);
    else this.render();
    this.mounted = true;
  }

  setData(patch) {
    if (patch === null || typeof patch !== 'object' || Array.isArray(patch)) {
      throw new Error('setData 参数必须是对象');
    }
    this.data = {...this.data, ...patch};
    this.context.data = this.data;
    if (!this.renderScheduled) {
      this.renderScheduled = true;
      queueMicrotask(() => {
        this.renderScheduled = false;
        if (this.mounted) this.render();
      });
    }
    return this.data;
  }

  render() {
    if (!this.mounted && this.definition.mount) return false;
    const generation = ++this.renderGeneration;
    const result = this.definition.render ? this.definition.render(this.context) : defaultRender(this.context);
    return generation === this.renderGeneration && result !== false;
  }

  show() {
    if (this.visible) return this.transition.then(() => false);
    this.visible = true;
    const generation = ++this.visibilityGeneration;
    this.transition = this.transition.catch(() => {}).then(async () => {
      if (!this.visible || generation !== this.visibilityGeneration) return false;
      this.running = true;
      await this.definition.start?.(this.context);
      if (!this.visible || generation !== this.visibilityGeneration) return false;
      await this.definition.onShow?.(this.context);
      return true;
    });
    return this.transition;
  }

  hide() {
    if (!this.visible) return this.transition.then(() => false);
    this.visible = false;
    window.dispatchEvent(new Event('pd-plugin-hidden'));
    ++this.visibilityGeneration;
    this.transition = this.transition.catch(() => {}).then(async () => {
      if (!this.running) return false;
      try { await this.definition.onHide?.(this.context); }
      finally {
        this.running = false;
        await this.definition.stop?.(this.context);
      }
      return true;
    });
    return this.transition;
  }

  async unload() {
    window.dispatchEvent(new Event('pd-plugin-dispose'));
    await this.hide();
    await this.definition.onUnload?.(this.context);
    this.mounted = false;
    this.initialized = false;
    this.renderGeneration++;
    this.root.replaceChildren();
  }

  back() {
    if (this.definition.closeModel?.() === true) return true;
    if (closeModel()) return true;
    if (closeMenu()) return true;
    if (closeSheet()) return true;
    return this.definition.back?.(this.context) === true;
  }
}

function loadStyles(urls = []) {
  return Promise.all(urls.map(url => new Promise((resolve, reject) => {
    const previous = document.querySelector(`link[data-pf-style="${cssEscape(url)}"]`);
    if (previous) return resolve();
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = url;
    link.dataset.pfStyle = url;
    link.onload = () => resolve();
    link.onerror = () => reject(new Error(`样式加载失败: ${url}`));
    document.head.append(link);
  })));
}

function cssEscape(value) {
  return String(value).replace(/["\\]/g, '\\$&');
}

async function showCurrent() {
  if (!current) return false;
  try { return await current.show(); }
  catch (error) {
    showError(error);
    return false;
  }
}

async function hideCurrent() {
  if (!current) return false;
  try { return await current.hide(); }
  catch (error) {
    showError(error);
    return false;
  }
}

export async function boot({entry, styles = [], pluginId = ''} = {}) {
  if (typeof entry !== 'string' || !entry) throw new Error('boot 缺少 entry');
  if (current) await current.unload();
  const root = ensureChrome(pluginId);
  applyLayout(true);
  if (!resizeBound) {
    resizeBound = true;
    window.addEventListener('resize', () => {
      if (applyLayout()) closeMenu();
      current?.definition.onResize?.(current.context);
    });
  }

  try {
    await loadStyles(styles);
    const module = await import(entry);
    const definition = module.default;
    if (!isPage(definition)) throw new Error('页面入口必须导出 definePage(...) 的 default 定义');
    current = new PageRuntime(definition, root, pluginId);
    window.__pd_go = id => current?.context.navigate(id).catch(error => {
      console.error('pluginNavigate failed', error);
      return false;
    });
    window.__pd_back = () => current?.back() === true;
    window.__pd_plugin_lifecycle = visible => {
      window.__pd_plugin_visible = visible === true || visible === 'true';
      return window.__pd_plugin_visible ? showCurrent() : hideCurrent();
    };
    window.__pd_plugin_unload = async () => {
      await current?.unload();
      current = null;
    };
    await current.mount();
    await native.ready();
    if (window.__pd_plugin_visible !== false) await showCurrent();
    return current.context;
  } catch (error) {
    current = null;
    showError(error);
    throw error;
  }
}

export function getPage() {
  return current;
}

export {theme};
