export class TestElement {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.id = '';
    this.className = '';
    this.dataset = {};
    this.style = {};
    this.attributes = new Map();
    this.listeners = new Map();
    this.classSet = new Set();
    this.classList = {
      add: (...names) => names.forEach(name => this.classSet.add(name)),
      remove: (...names) => names.forEach(name => this.classSet.delete(name)),
      toggle: (name, force) => {
        const next = force === undefined ? !this.classSet.has(name) : force;
        next ? this.classSet.add(name) : this.classSet.delete(name);
        return next;
      },
      contains: name => this.classSet.has(name),
    };
    this._textContent = '';
  }

  get textContent() { return this._textContent; }
  set textContent(value) {
    this._textContent = String(value ?? '');
    this.children = [];
  }

  append(...nodes) {
    for (const node of nodes) {
      if (!(node instanceof TestElement)) {
        const text = new TestElement('#text');
        text.textContent = String(node);
        node = text;
      }
      node.parentNode = this;
      this.children.push(node);
    }
  }

  prepend(...nodes) {
    for (const node of nodes.reverse()) {
      if (!(node instanceof TestElement)) {
        const text = new TestElement('#text');
        text.textContent = String(node);
        node = text;
      }
      node.parentNode = this;
      this.children.unshift(node);
    }
  }

  replaceChildren(...nodes) {
    this.children = [];
    this.append(...nodes);
  }

  remove() { this.parentNode?.children.splice(this.parentNode.children.indexOf(this), 1); }

  appendChild(node) {
    this.append(node);
    return node;
  }

  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) ?? ''; }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
  }
  removeEventListener(type, handler) { this.listeners.get(type)?.delete(handler); }
  dispatchEvent(event) {
    event.target ||= this;
    for (const handler of this.listeners.get(event.type) || []) handler(event);
    return true;
  }
  focus() { this.focused = true; }
  querySelector(selector) {
    if (!selector.startsWith('#')) return null;
    const wanted = selector.slice(1);
    const visit = node => {
      if (node.id === wanted) return node;
      for (const child of node.children) {
        const found = visit(child);
        if (found) return found;
      }
      return null;
    };
    return visit(this);
  }
  querySelectorAll(selector) {
    const result = [];
    const visit = node => {
      if (selector.startsWith('#') ? node.id === selector.slice(1) : node.classList?.contains(selector.slice(1))) result.push(node);
      node.children.forEach(visit);
    };
    this.children.forEach(visit);
    return result;
  }
}

export function installDom({width = 601} = {}) {
  const body = new TestElement('body');
  const head = new TestElement('head');
  const elementsById = new Map();
  const windowListeners = new Map();
  const nativeCalls = [];
  let sequence = 0;
  const document = {
    body,
    head,
    createElement: tag => new TestElement(tag),
    getElementById: id => elementsById.get(id) || null,
    querySelector: selector => (selector === 'body' ? body : body.querySelector(selector)),
    addEventListener() {},
  };
  const observe = element => {
    let current = element.id ? element : null;
    if (current) elementsById.set(current.id, current);
    for (const child of element.children) observe(child);
  };
  const originalAppend = TestElement.prototype.append;
  TestElement.prototype.append = function(...nodes) {
    originalAppend.call(this, ...nodes);
    observe(this);
  };
  const fakeWindow = {
    PDNative: {
      call(method, id, argsJson) {
        nativeCalls.push({method, args: JSON.parse(argsJson)});
        queueMicrotask(() => fakeWindow.__pd_cb(id, JSON.stringify({value: {ok: true}})));
      },
    },
    innerWidth: width,
    listeners: windowListeners,
    addEventListener(type, handler) {
      if (!windowListeners.has(type)) windowListeners.set(type, new Set());
      windowListeners.get(type).add(handler);
    },
    removeEventListener(type, handler) { windowListeners.get(type)?.delete(handler); },
    dispatchEvent(event) { for(const handler of windowListeners.get(event.type)||[])handler(event); return true; },
    location: {reload() { nativeCalls.push({method: 'location.reload'}); }},
  };
  globalThis.window = fakeWindow;
  globalThis.document = document;
  return {body, head, document, window: fakeWindow, nativeCalls};
}
