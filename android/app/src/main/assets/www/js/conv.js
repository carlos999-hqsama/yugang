// 会话模型：把 dsh session/follow 的 snapshot / event / assistant-stream 帧整理成可渲染的条目序列。
const IMG_RE = /\.(png|jpe?g|gif|webp|svg|bmp)$/i;
const ART_RE = /\.(html?|md|svg|pdf|png|jpe?g|webp|gif|csv|json|txt)$/i;
const PREVIEW_RE = /\.(html?|svg|pdf|md)$/i;

export function parseArgs(a) { if (a && typeof a === 'object') return a; try { return JSON.parse(a || '{}'); } catch { return { _raw: String(a || '') }; } }
export function resolvePath(p, cwd) {
  if (!p) return p;
  if (p.startsWith('/')) return p;
  if (p.startsWith('~/')) return p; // 由调用方用 home 展开
  return (cwd || '').replace(/\/+$/, '') + '/' + p.replace(/^\.\//, '');
}
function textOf(content) { return (content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n'); }
function visibleUserText(data) {
  const text=textOf(data.content),prefix='<!-- yugang-bot-message:';
  if(!String(data.source?.rpcId||'').startsWith('yugang-bot:')||!text.startsWith(prefix))return text;
  const end=text.indexOf(' -->\n');if(end<0)return text;
  try{const meta=JSON.parse(text.slice(prefix.length,end));if(data.source.rpcId==='yugang-bot:'+meta.id&&/^bm-[a-f0-9]{32}$/.test(meta.id))return text.slice(end+5);}catch{}
  return text;
}

export class Conv {
  constructor(sessionId) { this.sid = sessionId; this.reset(); }
  reset() { this.events = new Map(); this.live = new Map(); this.header = null; this.cursor = 0; this.loaded = false; this.oldest = null; this.version = 0; }
  snapshot(v) {
    this.reset();
    this.header = v.header || null; this.cursor = v.cursor || 0;
    for (const r of v.records || []) if (r.type === 'event' && r.event) this.add(r.event);
    this.loaded = true; this.version++;
  }
  prepend(records) { for (const r of records || []) if (r.type === 'event' && r.event) this.add(r.event); this.version++; }
  add(e) { if (typeof e.seq !== 'number') return; this.events.set(e.seq, e); if (this.oldest == null || e.seq < this.oldest) this.oldest = e.seq; if (e.seq > this.cursor) this.cursor = e.seq; this.version++; }
  frame(f) {
    if (!f || !f.attemptId) return;
    if (f.type === 'start') { this.live.set(f.attemptId, { blocks: [], turn: f.turn, step: f.step }); }
    else if (f.type === 'chunk') {
      let a = this.live.get(f.attemptId); if (!a) { a = { blocks: [] }; this.live.set(f.attemptId, a); }
      const c = f.chunk || {};
      if (c.type === 'block-start') a.blocks[c.index] = { type: c.blockType || 'text', text: '', args: '' };
      else if (c.type === 'text-delta' || c.type === 'thinking-delta' || c.type === 'reasoning-delta') { const b = a.blocks[c.index] || (a.blocks[c.index] = { type: c.type === 'text-delta' ? 'text' : 'thinking', text: '' }); b.text += c.text || c.delta || ''; }
      else if (c.type === 'tool-call-delta') { const b = a.blocks[c.index] || (a.blocks[c.index] = { type: 'tool-call', args: '' }); b.type = 'tool-call'; if (c.id) b.id = c.id; if (c.name) b.name = c.name; b.args = (b.args || '') + (c.argumentsDelta || ''); }
      else if (c.type === 'block-end' && c.block) { a.blocks[c.index] = { ...a.blocks[c.index], ...c.block, done: true }; }
    } else if (f.type === 'end') { this.live.delete(f.attemptId); }
    this.version++;
  }

  hasLiveOutput() {
    return [...this.live.values()].some(a => a.blocks.some(b => b &&
      ((['text', 'thinking', 'reasoning'].includes(b.type) && b.text) || (b.type === 'tool-call' && b.name))));
  }

  /** 生成渲染条目 */
  build({ running = false } = {}) {
    const evs = [...this.events.values()].sort((a, b) => a.seq - b.seq);
    const cwd = this.header?.cwd || '';
    const results = new Map(), approvals = new Map(), calls = new Map();
    for (const e of evs) {
      const d = e.data || {};
      if (e.type === 'tool/result') { const m = d.message || {}; results.set(m.toolCallId || m.source?.callId, { text: textOf(m.content), isError: !!m.isError, content: m.content }); }
      else if (e.type === 'approval/asked') approvals.set(d.callId, { state: 'asked', ...d });
      else if (e.type === 'approval/decided') { for (const [k, v] of approvals) if (v.id === d.id) approvals.set(k, { ...v, state: d.outcome }); }
      else if (e.type === 'tool/call') calls.set(d.callId, d);
    }
    const items = [];
    let ended = false;
    let turn = null;
    const pushTool = (call) => {
      const r = results.get(call.id);
      const ap = approvals.get(call.id);
      items.push({ k: 'tool', id: call.id, name: call.name, args: parseArgs(call.arguments), result: r, approval: ap, pending: !r, cwd });
      if (turn) { turn.hadTools = true; turn.lastWorkIdx = items.length; }
    };
    for (const e of evs) {
      const d = e.data || {};
      switch (e.type) {
        case 'turn/start': ended = false; turn = { key: `process:${this.sid}:${e.seq}`, startIdx: items.length, start: e.time, hadTools: false, files: [], presented: new Set() }; break;
        case 'user/message': {
          const src = d.source || {};
          if (src.kind !== 'user') break; // 运行时上下文等系统注入的 user 消息不显示
          ended = false;
          const text = visibleUserText(d);
          const images = (d.content || []).filter((c) => c.type === 'image').length;
          const files = (d.content || []).filter((c) => c.type === 'file').map((c) => c.name || c.path || '附件');
          items.push({ k: 'user', text, images, files, id: d.id, rpcId: src.rpcId, seq: e.seq });
          break;
        }
        case 'assistant/message': {
          for (const [blockIndex, b] of (d.message?.content || []).entries()) {
            if (b.type === 'text' && b.text?.trim()) items.push({ k: 'text', text: b.text, cwd, seq: e.seq });
            else if ((b.type === 'thinking' || b.type === 'reasoning') && (b.text || b.thinking)) items.push({ k: 'think', text: b.text || b.thinking, key: `think:${e.seq}:${blockIndex}` });
            else if (b.type === 'tool-call') {
              const a = parseArgs(b.arguments);
              pushTool(b);
              if (turn) {
                if (b.name === 'present') for (const f of a.files || []) turn.presented.add(resolvePath(f.path, cwd));
                if ((b.name === 'write' || b.name === 'edit') && a.file_path && PREVIEW_RE.test(a.file_path)) turn.files.push(resolvePath(a.file_path, cwd));
              }
            }
          }
          break;
        }
        case 'turn/end': {
          ended = true;
          if (turn) {
            markProcess(items, turn, e.time);
            // 产物卡片统一放在本轮末尾（设计稿：总结文字之后）
            const pres = []; for (let i = items.length - 1; i >= turn.startIdx; i--) if (items[i].k === 'tool' && items[i].name === 'present' && items[i].result && !items[i].result.isError) pres.unshift(...items.splice(i, 1));
            items.push(...pres);
            const extra = [...new Set(turn.files)].filter((p) => !turn.presented.has(p));
            for (const p of extra) items.push({ k: 'art', path: p, cwd });
          }
          const kind = d.reason?.kind;
          if (kind && kind !== 'completed') items.push({ k: 'note', text: kind === 'cancelled' || kind === 'interrupted' || kind === 'aborted' ? '已停止' : `本轮结束：${d.reason?.message || kind}` });
          turn = null;
          break;
        }
        case 'permission/preset': if (items.some((x) => x.k === 'user')) items.push({ k: 'note', text: `权限已切换为「${permLabel(d.preset)}」` }); break;
        case 'model/selection': break;
        default: break;
      }
    }
    // 正在流式输出的回复
    for (const [attemptId, a] of this.live) {
      for (const [blockIndex, b] of a.blocks.entries()) {
        if (!b) continue;
        if (b.type === 'text' && b.text) items.push({ k: 'text', text: b.text, live: !b.done, cwd });
        else if (['thinking', 'reasoning'].includes(b.type) && b.text) items.push({ k: 'think', text: b.text, live: !b.done, key: `think:${attemptId}:${blockIndex}` });
        else if (b.type === 'tool-call' && b.name && !calls.has(b.id)) items.push({ k: 'tool', id: b.id, name: b.name, args: parseArgs(b.args), pending: true, streaming: true, cwd });
      }
    }
    if (running && (!ended || this.live.size > 0) && !this.hasLiveOutput()) items.push({ k: 'working' });
    if (turn) markProcess(items, turn);
    return foldProcess(groupTools(items));
  }
}

function fmtWork(sec) { sec = Math.max(0, Math.round(sec)); const m = Math.floor(sec / 60), s = sec % 60; if (m >= 60) return `${Math.floor(m / 60)}小时 ${m % 60}分钟`; return m ? `${m}分钟 ${s}秒` : `${s}秒`; }
export const PERM = { 'read-only': '仅可查看', 'workspace-write': '工作区可写', 'danger-full-access': '完全访问' };
export const permLabel = (p) => PERM[p] || p || '默认权限';

/* -------------------------------------------- 工具调用分组：连续调用合并成设计稿里的摘要行 */
export function category(t) {
  const n = t.name, a = t.args || {};
  if (n === 'history_search') return 'history-search';
  if (n === 'history_read') return 'history-read';
  if (n === 'read') return IMG_RE.test(a.file_path || '') ? 'image' : 'read';
  if (n === 'view_image' || n === 'read_image' || (n === 'phone_plugins_preview' && a.op === 'snapshot')) return 'image';
  if (n === 'glob' || n === 'ls' || n === 'list_directory') return 'read';
  if (n === 'grep' || n === 'web_search' || n === 'session_search' || n === 'search_sessions') return 'search';
  if (n === 'edit' || n === 'write' || n === 'str_replace_editor' || n === 'apply_patch' || n === 'multi_edit') return 'edit';
  if (n === 'bash' || n === 'pwsh' || n === 'shell' || n === 'terminal') return 'bash';
  if (n === 'web_fetch') return 'web';
  if (n === 'present') return 'present';
  if (n === 'ask_user_question') return 'question';
  if (n === 'todo_write') return 'todo';
  if (n === 'subagent' || n === 'spawn_subagent') return 'agent';
  return 'other';
}
function groupTools(items) {
  const out = [];
  let run = [];
  const flush = () => {
    if (!run.length) return;
    // 同类相邻合并；read 与 bash 相邻时合并成“已读取 N 个文件，运行了 M 条命令”
    let cur = null;
    for (const t of run) {
      const c = category(t);
      if (c === 'present') { out.push(...lines(cur)); cur = null; for (const f of t.args.files || []) out.push({ k: 'art', path: resolvePath(f.path, t.cwd), desc: f.description, cwd: t.cwd }); continue; }
      if (c === 'question') { out.push(...lines(cur)); cur = null; out.push({ k: 'question', tool: t }); continue; }
      const mergeable = cur && (cur.cat === c || (cur.cat === 'readbash' && (c === 'read' || c === 'bash')) || ((cur.cat === 'read' && c === 'bash') || (cur.cat === 'bash' && c === 'read' && cur.tools.length > 1)));
      if (mergeable && !['agent', 'other', 'todo'].includes(c)) { if (cur.cat !== c) cur.cat = 'readbash'; cur.tools.push(t); }
      else { out.push(...lines(cur)); cur = { cat: c, tools: [t] }; }
    }
    out.push(...lines(cur));
    run = [];
  };
  for (const it of items) { if (it.k === 'tool') run.push(it); else { flush(); out.push(it); } }
  flush();
  return out;
}
function lines(g) { return g ? [{ k: 'tools', cat: g.cat, tools: g.tools, key: g.tools.map((t) => t.id).join(','), process: g.tools[0].process }] : []; }

// 每轮过程与正文分离；正在流式输出的文字始终留在正文区。
function markProcess(items, turn, end) {
  const process = { key: turn.key, running: end == null, label: end == null ? '正在处理…' : `已处理 ${fmtWork((end - turn.start) / 1000)}` };
  for (let i = turn.startIdx; i < items.length; i++) {
    const it = items[i];
    if (it.k === 'think' || it.k === 'working' || (it.k === 'tool' && !['question', 'present'].includes(category(it))) || (end != null && it.k === 'text' && i < (turn.lastWorkIdx || 0))) it.process = process;
  }
}
function foldProcess(items) {
  const out = [];
  let group = null, turnKey = '';
  for (const it of items) {
    // Feedback and user interruptions are timeline boundaries. Never move a
    // later tool or the current working indicator into an earlier segment.
    if (!it.process) { out.push(it); group = null; continue; }
    if (!group || turnKey !== it.process.key) {
      turnKey = it.process.key;
      const first = it.k === 'tools' ? it.tools[0].id : it.key || it.id || (it.seq != null ? `${it.k}:${it.seq}` : it.k);
      group = { k: 'process', ...it.process, key: `${turnKey}:${first}`, items: [] };
      out.push(group);
    }
    group.items.push(it);
  }
  // Only the trailing segment is currently executing. Earlier tool phases
  // stay in place as completed disclosures while the turn itself continues.
  for (const item of out) {
    if (item.k === 'process' && item.running && item !== out.at(-1)) {
      item.running = false;
      item.label = '已执行';
    }
  }
  return out;
}

/** Host preview tool saves a Bot-owned image; only that explicit result line is a preview. */
export function previewArtifact(text) {
  const path = String(text || '').match(/^截图文件：([^\r\n]+)$/m)?.[1];
  return path && /^\/BOT\/[A-Za-z0-9_-]+\/documents\/plugin-preview-[0-9]+\.png$/.test(path) ? path : null;
}
