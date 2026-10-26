/**
 * 极简合成 DOM：只为在 Node 里真实执行 www 源码（不改动源码本身）。
 * 覆盖本轮交互修复需要的 API：innerHTML 解析、选择器、类名、dataset、
 * 事件（捕获+冒泡）、焦点、hidden/disabled/tabIndex/value。
 */

const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
const kebab = (name) => String(name).replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

class TextNode {
  constructor(text, doc) {
    this.nodeType = 3;
    this.text = String(text);
    this.ownerDocument = doc;
    this.parentNode = null;
    this.childNodes = [];
  }
  get textContent() { return this.text; }
  set textContent(v) { this.text = String(v); }
}

class El {
  constructor(tag, doc) {
    this.nodeType = 1;
    this.tagName = String(tag).toLowerCase();
    this.ownerDocument = doc;
    this.childNodes = [];
    this.parentNode = null;
    this.attributes = new Map();
    this.style = {};
    this.listeners = new Map();
    this.isConnected = true;
    this.hidden = false;
    this._value = '';
    this._tabIndex = undefined;
    const el = this;
    this.dataset = new Proxy({}, {
      get(_t, prop) {
        if (typeof prop !== 'string') return undefined;
        const v = el.getAttribute('data-' + kebab(prop));
        return v === null ? undefined : v;
      },
      set(_t, prop, val) {
        if (typeof prop !== 'string') return false;
        el.setAttribute('data-' + kebab(prop), String(val));
        return true;
      },
      has(_t, prop) { return typeof prop === 'string' && el.attributes.has('data-' + kebab(prop)); },
    });
  }

  get children() { return this.childNodes.filter((n) => n.nodeType === 1); }
  get className() { return this.attributes.get('class') || ''; }
  set className(v) { this.attributes.set('class', String(v)); }
  get id() { return this.attributes.get('id') || ''; }
  set id(v) { this.setAttribute('id', v); }
  get disabled() { return this.attributes.has('disabled'); }
  set disabled(v) { if (v) this.setAttribute('disabled', ''); else this.attributes.delete('disabled'); }
  get checked() { return this._checked === undefined ? this.attributes.has('checked') : this._checked; }
  set checked(value) { this._checked = !!value; }
  get type() { return this.attributes.get('type') || ''; }
  set type(v) { this.setAttribute('type', v); }
  get value() { return this._valueDirty || this.tagName !== 'textarea' ? this._value : this.textContent; }
  set value(v) { this._valueDirty = true; this._value = String(v ?? ''); }
  get title() { return this.attributes.get('title') || ''; }
  set title(v) { this.setAttribute('title', v); }
  get placeholder() { return this.attributes.get('placeholder') || ''; }
  set placeholder(v) { this.setAttribute('placeholder', v); }
  get tabIndex() {
    if (this._tabIndex !== undefined) return this._tabIndex;
    const tag = this.tagName;
    if (tag === 'button' || tag === 'input' || tag === 'select' || tag === 'textarea') return 0;
    if (tag === 'a' && this.attributes.has('href')) return 0;
    const attr = this.attributes.get('tabindex');
    return attr === undefined ? -1 : Number(attr);
  }
  set tabIndex(v) { this._tabIndex = Number(v); this.setAttribute('tabindex', String(v)); }
  get offsetWidth() { return 120; }
  get offsetHeight() { return 32; }
  get scrollHeight() { return 200; }
  get clientHeight() { return 100; }
  get scrollTop() { return this._scrollTop || 0; }
  set scrollTop(v) { this._scrollTop = Number(v) || 0; }

  get classList() {
    const el = this;
    const list = () => el.className.split(/\s+/).filter(Boolean);
    return {
      add(...names) { const cur = list(); for (const n of names) if (!cur.includes(n)) cur.push(n); el.className = cur.join(' '); },
      remove(...names) { el.className = list().filter((c) => !names.includes(c)).join(' '); },
      contains(name) { return list().includes(name); },
      toggle(name, force) {
        const has = list().includes(name);
        const next = force === undefined ? !has : !!force;
        if (next) el.classList.add(name); else el.classList.remove(name);
        return next;
      },
    };
  }

  get textContent() { return this.childNodes.map((n) => n.textContent).join(''); }
  set textContent(v) {
    this.childNodes = [];
    if (v !== '' && v != null) this.appendChild(new TextNode(String(v), this.ownerDocument));
  }
  get innerHTML() { return this.childNodes.map(serialize).join(''); }
  set innerHTML(html) {
    this.childNodes = [];
    for (const n of parseHTML(String(html ?? ''), this.ownerDocument)) this.appendChild(n);
  }

  appendChild(node) {
    if (!node) return node;
    if (node.parentNode) node.parentNode.removeChild(node);
    node.parentNode = this;
    this.childNodes.push(node);
    this.syncConnected();
    return node;
  }
  append(...nodes) { for (const n of nodes) this.appendChild(typeof n === 'string' ? new TextNode(n, this.ownerDocument) : n); }
  removeChild(node) {
    const i = this.childNodes.indexOf(node);
    if (i >= 0) { this.childNodes.splice(i, 1); node.parentNode = null; }
    return node;
  }
  replaceWith(node) {
    const parent = this.parentNode;
    if (!parent || node === this) return;
    if (node.parentNode) node.parentNode.removeChild(node);
    const index = parent.childNodes.indexOf(this);
    parent.childNodes.splice(index, 1, node);
    this.parentNode = null;
    this.isConnected = false;
    this.syncConnected();
    node.parentNode = parent;
    parent.syncConnected();
  }
  remove() { if (this.parentNode && typeof this.parentNode.removeChild === 'function') this.parentNode.removeChild(this); }
  syncConnected() {
    for (const child of this.childNodes) {
      if (child.nodeType !== 1) continue;
      child.isConnected = this.isConnected;
      child.syncConnected();
    }
  }
  contains(node) {
    let cur = node;
    while (cur) { if (cur === this) return true; cur = cur.parentNode; }
    return false;
  }

  setAttribute(key, value) { this.attributes.set(String(key), String(value)); }
  getAttribute(key) { return this.attributes.has(key) ? this.attributes.get(key) : null; }
  removeAttribute(key) { this.attributes.delete(key); }
  hasAttribute(key) { return this.attributes.has(key); }

  addEventListener(type, fn, capture) {
    if (!this.listeners.has(type)) this.listeners.set(type, { capture: [], bubble: [] });
    this.listeners.get(type)[capture ? 'capture' : 'bubble'].push(fn);
  }
  removeEventListener(type, fn) {
    const rec = this.listeners.get(type);
    if (!rec) return;
    rec.bubble = rec.bubble.filter((f) => f !== fn);
    rec.capture = rec.capture.filter((f) => f !== fn);
  }
  dispatchEvent(type, event = {}) {
    const ev = {
      type,
      target: this,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this._stopped = true; },
      stopImmediatePropagation() { this._stopped = true; this._immediate = true; },
      ...event,
    };
    const path = [];
    let node = this;
    while (node) { path.push(node); node = node.parentNode; }
    for (const n of path) {
      const rec = n.listeners.get(type);
      if (rec) for (const fn of [...rec.capture]) { if (ev._stopped) return true; fn.call(n, ev); }
    }
    for (const n of path.reverse()) {
      const rec = n.listeners.get(type);
      if (rec) for (const fn of [...rec.bubble]) { if (ev._stopped) return true; fn.call(n, ev); }
      const inline = n['on' + type];
      if (typeof inline === 'function') { if (ev._stopped) return true; inline.call(n, ev); }
    }
    return !ev.defaultPrevented;
  }
  click() { return this.dispatchEvent('click', { target: this }); }
  focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }
  blur() { if (this.ownerDocument && this.ownerDocument.activeElement === this) this.ownerDocument.activeElement = null; }
  getBoundingClientRect() { return { top: 0, left: 0, right: 120, bottom: 32, width: 120, height: 32 }; }
  select() { /* noop */ }
  setSelectionRange(start,end,direction="none") { this.selectionStart=start;this.selectionEnd=end;this.selectionDirection=direction; }
  appendData() { /* noop */ }

  querySelector(sel) { return queryAll(this, sel)[0] || null; }
  querySelectorAll(sel) { return queryAll(this, sel); }
  closest(sel) {
    let cur = this;
    while (cur && cur.nodeType === 1) { if (matchesSelector(cur, sel)) return cur; cur = cur.parentNode; }
    return null;
  }
  matches(sel) { return matchesSelector(this, sel); }
}

function serialize(node) {
  if (node.nodeType === 3) return node.text;
  const attrs = [...node.attributes].map(([k, v]) => ` ${k}="${String(v).replace(/"/g, '&quot;')}"`).join('');
  const tag = node.tagName.toLowerCase();
  if (VOID_TAGS.has(tag)) return `<${tag}${attrs}>`;
  return `<${tag}${attrs}>${node.childNodes.map(serialize).join('')}</${tag}>`;
}

function parseAttrs(el, source) {
  const re = /([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g;
  let m;
  while ((m = re.exec(source || ''))) {
    const value = m[2] !== undefined ? m[2] : m[3] !== undefined ? m[3] : m[4] !== undefined ? m[4] : '';
    // HTML parses attribute entities before exposing href/dataset, just like real WebView.
    el.setAttribute(m[1], value.replace(/&(amp|quot|apos|lt|gt|#39);/g, (_, entity) => ({amp:'&',quot:'"',apos:"'",lt:'<',gt:'>','#39':"'"}[entity])));
  }
}

function parseHTML(html, doc) {
  const out = [];
  const stack = [];
  const tagRe = /<(\/)?([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/)?>/g;
  let last = 0;
  let m;
  while ((m = tagRe.exec(html))) {
    const text = html.slice(last, m.index).replace(/<[^>]*>/g, '');
    if (text.trim()) {
      const node = new TextNode(text, doc);
      if (stack.length) stack[stack.length - 1].appendChild(node); else out.push(node);
    }
    last = tagRe.lastIndex;
    const closing = m[1];
    const tag = m[2].toLowerCase();
    if (closing) {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].tagName === tag) { stack.length = i; break; }
      }
      continue;
    }
    const el = new El(tag, doc);
    parseAttrs(el, m[3] || '');
    if (stack.length) stack[stack.length - 1].appendChild(el); else out.push(el);
    if (!m[4] && !VOID_TAGS.has(tag)) stack.push(el);
  }
  const tail = html.slice(last).replace(/<[^>]*>/g, '');
  if (tail.trim()) {
    const node = new TextNode(tail, doc);
    if (stack.length) stack[stack.length - 1].appendChild(node); else out.push(node);
  }
  return out;
}

function matchCompound(el, part) {
  const tokens = part.match(/\[[^\]]+\]|[.#]?[\w-]+/g) || [];
  if (!tokens.length) return false;
  for (const token of tokens) {
    if (token.startsWith('.')) {
      if (!el.classList.contains(token.slice(1))) return false;
    } else if (token.startsWith('#')) {
      if (el.id !== token.slice(1)) return false;
    } else if (token.startsWith('[')) {
      const m = /^\[([\w-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]$/.exec(token);
      if (!m) return false;
      if (!el.attributes.has(m[1])) return false;
      const value = m[2] !== undefined ? m[2] : m[3] !== undefined ? m[3] : m[4];
      if (value !== undefined && el.getAttribute(m[1]) !== value) return false;
    } else if (el.tagName !== token.toLowerCase()) {
      return false;
    }
  }
  return true;
}

function matchParts(el, parts) {
  if (!matchCompound(el, parts[parts.length - 1])) return false;
  let ancestor = el.parentNode;
  for (let i = parts.length - 2; i >= 0; i--) {
    let found = false;
    while (ancestor && ancestor.nodeType === 1) {
      if (matchCompound(ancestor, parts[i])) { found = true; ancestor = ancestor.parentNode; break; }
      ancestor = ancestor.parentNode;
    }
    if (!found) return false;
  }
  return true;
}

function matchesSelector(el, selector) {
  return String(selector).split(',').map((s) => s.trim()).filter(Boolean)
    .some((group) => matchParts(el, group.split(/\s+/)));
}

function descendants(root) {
  const out = [];
  const walk = (node) => {
    for (const child of node.childNodes) {
      if (child.nodeType !== 1) continue;
      out.push(child);
      walk(child);
    }
  };
  walk(root);
  return out;
}

function queryAll(root, selector) {
  return descendants(root).filter((el) => matchesSelector(el, selector));
}

/** 创建一个最小 document（含 body、可见性、activeElement、getElementById）。 */
export function createDocument() {
  const doc = {
    nodeType: 9,
    visibilityState: 'visible',
    activeElement: null,
    listeners: new Map(),
    createElement: (tag) => new El(tag, doc),
    createTextNode: (text) => new TextNode(text, doc),
    querySelector: (sel) => queryAll(doc.body, sel)[0] || null,
    querySelectorAll: (sel) => queryAll(doc.body, sel),
    getElementById: (id) => queryAll(doc.body, `#${id}`)[0] || null,
    addEventListener(type, fn, capture) {
      if (!doc.listeners.has(type)) doc.listeners.set(type, { capture: [], bubble: [] });
      doc.listeners.get(type)[capture ? 'capture' : 'bubble'].push(fn);
    },
    removeEventListener(type, fn) {
      const rec = doc.listeners.get(type);
      if (!rec) return;
      rec.bubble = rec.bubble.filter((f) => f !== fn);
      rec.capture = rec.capture.filter((f) => f !== fn);
    },
    dispatchEvent(type, event = {}) {
      const rec = doc.listeners.get(type);
      if (!rec) return false;
      for (const fn of [...rec.capture]) fn({ type, ...event });
      for (const fn of [...rec.bubble]) fn({ type, ...event });
      return true;
    },
  };
  doc.body = new El('body', doc);
  doc.body.parentNode = doc; // 让冒泡能到达 document
  return doc;
}

export { El, TextNode, matchesSelector, queryAll };
