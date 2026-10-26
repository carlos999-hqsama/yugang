// 通用工具：转义、时间格式化、极简 Markdown 渲染
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
export const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0; return (c === 'x' ? r : (r & 3) | 8).toString(16);
}));
export const pad = (n) => String(n).padStart(2, '0');
export const nowS = () => Date.now() / 1000;

/** 时:分 */
export function hm(ts) { const d = new Date(ts * 1000); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; }
export function hms(ts) { const d = new Date(ts * 1000); return `${hm(ts)}:${pad(d.getSeconds())}`; }
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 1000;
const WD = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
/** 侧栏日期：今天 HH:MM / 昨天 / 周X（一周内）/ M/D */
export function sideDate(ts) {
  const t0 = startOfDay(new Date());
  if (ts >= t0) return hm(ts);
  if (ts >= t0 - 86400) return '昨天';
  if (ts >= t0 - 6 * 86400) return WD[new Date(ts * 1000).getDay()];
  const d = new Date(ts * 1000); return `${d.getMonth() + 1}/${d.getDate()}`;
}
/** “更新于”：刚刚 / N 秒前 / N 分钟前 / 今天 HH:MM / 昨天 HH:MM / M/D HH:MM */
export function ago(ts) {
  if (!ts) return '—';
  const s = nowS() - ts;
  if (s < 30) return '刚刚';
  if (s < 60) return `${Math.round(s)} 秒前`;
  if (s < 3600) return `${Math.round(s / 60)} 分钟前`;
  const t0 = startOfDay(new Date());
  if (ts >= t0) return `今天 ${hm(ts)}`;
  if (ts >= t0 - 86400) return `昨天 ${hm(ts)}`;
  const d = new Date(ts * 1000); return `${d.getMonth() + 1}/${d.getDate()} ${hm(ts)}`;
}
export function agoShort(ts) { // 4 秒前 / 6 分 / 1时12分
  const s = Math.max(0, nowS() - ts);
  if (s < 60) return `${Math.round(s)} 秒前`;
  return dur(s) + '前';
}
/** 时长：23分 / 1时12分 / 3天6时 */
export function dur(sec, { spaced = false } = {}) {
  sec = Math.max(0, Math.round(sec));
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  const sp = spaced ? ' ' : '';
  if (d) return h ? `${d}${sp}天${sp}${h}${sp}时` : `${d}${sp}天`;
  if (h) return `${h}${sp}时${pad(m)}${sp}分`.replace(/时0(\d)分/, spaced ? '时 0$1 分' : '时0$1分');
  if (m) return `${m}${sp}分`;
  return `${sec}${sp}秒`;
}
export function untilReset(ts) { if (!ts) return ''; const s = ts - nowS(); return s <= 0 ? '已重置' : `${dur(s)}后重置`; }
export function workDur(sec) { // 12分钟 30秒
  sec = Math.max(0, Math.round(sec)); const m = Math.floor(sec / 60), s = sec % 60;
  if (m >= 60) return `${Math.floor(m / 60)}小时 ${m % 60}分钟`;
  return m ? `${m}分钟 ${s}秒` : `${s}秒`;
}
export function fmtNum(v) {
  if (v == null || isNaN(v)) return '—';
  if (Math.abs(v) >= 1e6) return (v / 1e6).toFixed(2).replace(/\.?0+$/, '') + 'M';
  if (Math.abs(v) >= 1e5) return Math.round(v / 1e3) + 'K';
  return Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 });
}
export function fmtBytes(n) {
  if (n == null) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
export const shortId = (id) => (id && id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id || '');
export const basename = (p) => String(p || '').replace(/\/+$/, '').split('/').pop();
export const homeAbbr = (p, home) => (home && p && p.startsWith(home) ? '~' + p.slice(home.length) : p);

export function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

/** 极简、安全的 Markdown → HTML（先转义再标记），够用于对话气泡 */
const FILE_RE = /^(?:\.{0,2}\/|~\/)?[\w.@~-]+(?:\/[\w.@~-]+)*\.(?:html?|md|png|jpe?g|gif|svg|webp|pdf|txt|json|csv)$/i;
export function isFileLike(s) { return FILE_RE.test(s); }
function trimUrlPunctuation(raw) {
  let url=raw.replace(/[。，、；！？,.!?;:）】]+$/u,'');
  while (/[)\]]$/.test(url)) {
    const close=url.at(-1),open=close===')'?'(': '[';
    if ([...url].filter(c=>c===close).length <= [...url].filter(c=>c===open).length) break;
    url=url.slice(0,-1);
  }
  return url;
}
function inline(s, options = {}) {
  const codes = [], links = [];
  s = s.replace(/`([^`\n]+)`/g, (_, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
  // Parse destinations before HTML escaping, including Markdown's <path with spaces> form.
  s = s.replace(/(!?)\[([^\]\n]*)\]\((?:<([^>\n]+)>|([^\s)]+))\)/g, (raw, imageMark, label, angled, plain) => {
    const target = angled || plain;
    if (/[\u0000\u0001]/.test(target)) return raw;
    label = label.replace(/\u0000(\d+)\u0000/g, (_, i) => codes[+i] || '');
    let html;
    if (imageMark) {
      const image = options.image?.(target);
      if (!image) return raw;
      html = `<button type="button" class="chat-image" data-art="${esc(image.path)}" aria-label="查看图片：${esc(label || '图片')}"><img src="${esc(image.url)}" alt="${esc(label || '图片')}" loading="lazy"><span class="chat-image-failure" hidden>图片读取失败，点击重试查看</span></button>`;
    } else {
      if (!/^(?:https?:\/\/|\/(?!\/)|\.\.?\/)/i.test(target) && !/^[^:\n]+\.(?:html?|md|png|jpe?g|gif|svg|webp|pdf|txt|json|csv)(?:#[^\n]*)?$/i.test(target)) return raw;
      html = `<a href="${esc(target)}" data-href="${esc(target)}">${esc(label)}</a>`;
    }
    links.push(html);
    return `\u0001${links.length - 1}\u0001`;
  });
  // Isolate URLs before escaping, without linkifying code or already-parsed destinations.
  s = s.replace(/https?:\/\/[^\s<>\u0000\u0001]+/gi, raw => {
    const url = trimUrlPunctuation(raw);
    links.push(`<a href="${esc(url)}" data-href="${esc(url)}">${esc(url)}</a>`);
    return `\u0001${links.length - 1}\u0001${raw.slice(url.length)}`;
  });
  s = esc(s)
    .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<i>$2</i>');
  s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => {
    const c = codes[+i];
    return isFileLike(c) ? `<code class="fl" data-file="${esc(c)}">${esc(c)}</code>` : `<code>${esc(c)}</code>`;
  });
  // Restore generated HTML last so code placeholders cannot be expanded inside attributes.
  return s.replace(/\u0001(\d+)\u0001/g, (_, i) => links[+i] || '');
}

/** User text stays literal while HTTP(S) links retain keyboard and touch semantics. */
export function linkText(text) {
  return String(text || '').split(/(https?:\/\/[^\s<>]+)/gi).map(part => {
    if (!/^https?:\/\//i.test(part)) return esc(part);
    const url = trimUrlPunctuation(part);
    return `<a href="${esc(url)}" data-href="${esc(url)}">${esc(url)}</a>${esc(part.slice(url.length))}`;
  }).join('');
}
export function md(src, options = {}) {
  const lines = String(src || '').replace(/[\r\u0000\u0001]/g, '').split('\n');
  let out = '', i = 0;
  const para = [];
  const flush = () => { if (para.length) { out += `<p>${para.map(value => inline(value, options)).join('<br>')}</p>`; para.length = 0; } };
  while (i < lines.length) {
    const l = lines[i];
    let m;
    if ((m = l.match(/^\s*```(\w*)/))) {
      flush(); const buf = []; i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) buf.push(lines[i++]);
      i++;
      const raw = buf.join('\n');
      const control = options.code?.(raw) || '';
      out += control ? `<div class="code-block">${control}<pre><code>${esc(raw)}</code></pre></div>` : `<pre><code>${esc(raw)}</code></pre>`; continue;
    }
    if ((m = l.match(/^(#{1,6})\s+(.*)/))) { flush(); out += `<h${Math.min(4, m[1].length)}>${inline(m[2], options)}</h${Math.min(4, m[1].length)}>`; i++; continue; }
    if (/^\s*([-*+]|\d+[.)])\s+/.test(l)) {
      flush(); const ordered = /^\s*\d/.test(l); let items = '';
      while (i < lines.length && /^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
        let t = lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, ''); i++;
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) t += ' ' + lines[i++].trim();
        items += `<li>${inline(t.replace(/^\[( |x)\]\s*/i, (_, x) => (x.trim() ? '☑ ' : '☐ ')), options)}</li>`;
      }
      out += ordered ? `<ol>${items}</ol>` : `<ul>${items}</ul>`; continue;
    }
    if (/^\s*\|.*\|\s*$/.test(l) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      flush(); const row = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
      const head = row(l); i += 2; let body = '';
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) body += `<tr>${row(lines[i++]).map((c) => `<td>${inline(c, options)}</td>`).join('')}</tr>`;
      out += `<table><tr>${head.map((c) => `<th>${inline(c, options)}</th>`).join('')}</tr>${body}</table>`; continue;
    }
    if (/^>\s?/.test(l)) { flush(); const buf = []; while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, '')); out += `<blockquote>${md(buf.join('\n'), options)}</blockquote>`; continue; }
    if (!l.trim()) { flush(); i++; continue; }
    para.push(l); i++;
  }
  flush();
  return out;
}
