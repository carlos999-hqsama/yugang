// 追问卡片：只消费 dsh 的 userQuestions 记录，不自行推断“已回答”。
// 草稿与提交状态仅在本页内存中，按会话 + 调用 + 问题隔离。
import { S } from './icons.js';
import { esc, md } from './util.js';
import { api, questionFor, questionsFor, matchQuestionCall } from './dsh.js';

const drafts = new Map(); // sid\0callId -> Map(qid -> {selected,custom,skipped})
const sends = new Map(); // sid\0callId -> {phase:'submitting'|'error'|'accepted',error,answers}
const views = new Map(); // sid\0callId -> {step, customOpen: Map(qid -> boolean)}
const BASE = (sid, callId) => String(sid) + '\u0000' + String(callId);

const multiOf = (q) => q?.multiSelect ?? q?.multi_select ?? false;
const questionsOf = (v) => Array.isArray(v?.questions) ? v.questions : [];
const optionsOf = (q) => Array.isArray(q?.options) ? q.options.filter((o) => o && typeof o.label === 'string') : [];
const qidOf = (q, i = 0) => String(q?.id ?? i);

function draftMap(sid, callId) {
  const k = BASE(sid, callId);
  let m = drafts.get(k);
  if (!m) { m = new Map(); drafts.set(k, m); }
  return m;
}
function draftFor(sid, callId, qid) {
  const m = draftMap(sid, callId);
  let d = m.get(String(qid));
  if (!d) { d = { selected: [], custom: '', skipped: false }; m.set(String(qid), d); }
  return d;
}
function sendFor(sid, callId) {
  const k = BASE(sid, callId);
  let s = sends.get(k);
  if (!s) { s = { phase: '', error: '', answers: null }; sends.set(k, s); }
  return s;
}
function viewFor(sid, callId) {
  const k = BASE(sid, callId);
  let v = views.get(k);
  if (!v) { v = { step: 0, customOpen: new Map() }; views.set(k, v); }
  return v;
}
function currentStep(view, count) {
  if (!count) return 0;
  view.step = Math.max(0, Math.min(view.step || 0, count - 1));
  return view.step;
}
function customExpanded(view, qid, hasOptions, draft) {
  if (!hasOptions) return true;
  if (view.customOpen.has(qid)) return view.customOpen.get(qid);
  return !!String(draft?.custom || '').trim();
}
function resolved(d) { return !!d?.skipped || (Array.isArray(d?.selected) && d.selected.length > 0) || !!String(d?.custom || '').trim(); }

function stable(v) {
  if (Array.isArray(v)) return v.map(stable);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v).sort()) {
      const x = stable(v[k]);
      if (x !== undefined) out[k] = x;
    }
    return out;
  }
  return v;
}
const markOf = (v) => JSON.stringify(stable(v));

function badgeText(rec) {
  if (rec.state === 'submitted') return '确认中';
  if (rec.state === 'settled') return '只读';
  if (rec.state === 'closed') return '已结束';
  if (rec.state === 'continued') return '可稍后回答';
  return '等待回答';
}

function questionHeading(q, index, total, readOnly = false) {
  const type = optionsOf(q).length ? multiOf(q) ? '可多选' : '单选' : '填写回答';
  return `<div class="qmeta"><span>第 ${index + 1} / ${total} 题</span>${readOnly ? '' : `<span>${type}</span>`}</div>
${q.header ? `<div class="qheading">${esc(q.header)}</div>` : ''}
<div class="qq">${esc(q.question || '')}</div>
${q.detail ? `<div class="qdetail">${md(q.detail)}</div>` : ''}`;
}

function answerBlock(q, ans) {
  const selected = Array.isArray(ans?.selected) ? ans.selected : [];
  const custom = typeof ans?.custom === 'string' ? ans.custom : '';
  if (!ans) return `<div class="qmissing">未找到回答记录</div>`;
  if (!selected.length && !custom) return `<div class="qskipped">已跳过本题</div>`;
  return `<div class="qanswer">${selected.length ? `<div class="qansline"><span class="qanslabel">选择</span><span class="qanschips">${selected.map((x) => `<span class="qanschip">${esc(x)}</span>`).join('')}</span></div>` : ''}${custom ? `<div class="qansline"><span class="qanslabel">补充</span><div class="qcustext">${esc(custom)}</div></div>` : ''}</div>`;
}

function readOnlyQuestion(q, i, answers) {
  const ans = answers.get(qidOf(q, i));
  return `<section class="qitem qread" data-qid="${esc(qidOf(q, i))}"><div class="qreadtop"><span class="qreadidx">${i + 1}</span><div class="qreadbody">${q.header ? `<div class="qreadh">${esc(q.header)}</div>` : ''}<div class="qreadq">${esc(q.question || '')}</div>${answerBlock(q, ans)}</div></div></section>`;
}

function optionHtml(q, o, qid, draft, locked) {
  const on = (draft.selected || []).includes(o.label);
  return `<button type="button" class="qopt${on ? ' on' : ''}" data-qact="opt" data-qid="${esc(qid)}" data-qopt="${esc(o.label)}" aria-pressed="${on ? 'true' : 'false'}"${locked ? ' disabled' : ''}><i class="qdot"></i><span class="qoptmain"><b>${esc(o.label)}</b>${o.description ? `<small>${esc(o.description)}</small>` : ''}</span></button>`;
}

function formQuestion(q, i, total, sid, callId, canForm, view, actions = '') {
  const qid = qidOf(q, i);
  const d = draftFor(sid, callId, qid);
  const send = sendFor(sid, callId);
  const locked = !canForm || send.phase === 'submitting' || send.phase === 'accepted';
  const dis = locked || d.skipped;
  const opts = optionsOf(q);
  const multi = multiOf(q);
  const expanded = customExpanded(view, qid, opts.length > 0, d);
  const customAction = multi ? '补充回答' : '填写其他回答';
  const closeAction = multi ? '收起补充' : '收起填写';
  const customHint = opts.length ? multi ? '可补充自己的回答' : '填写选项之外的回答' : '填写你的回答';
  const placeholder = opts.length ? multi ? '补充一些说明…' : '填写其他回答…' : '填写回答…';
  const custom = `<label class="qcustom"${expanded ? '' : ' hidden'}><input type="text" aria-label="${customHint}" data-qreply data-qid="${esc(qid)}" value="${esc(d.custom || '')}" placeholder="${placeholder}" autocomplete="off" autocapitalize="sentences" enterkeyhint="done"${dis ? ' disabled' : ''}></label>`;
  const skip = `<button type="button" class="btn qskip" data-qact="skip"${locked ? ' disabled' : ''}>${d.skipped ? '撤销跳过' : '跳过本题'}</button>`;
  const customToggle = opts.length ? `<button type="button" class="btn qcustomtoggle" data-qact="custom" aria-expanded="${expanded ? 'true' : 'false'}"${dis ? ' disabled' : ''}>${expanded ? closeAction : customAction}</button>` : '';
  return `<section class="qitem${d.skipped ? ' skipped' : ''}" data-qid="${esc(qid)}" data-qmulti="${multi ? '1' : '0'}"${i === view.step ? '' : ' hidden'}>
${questionHeading(q, i, total)}
${opts.length ? `<div class="qopts">${opts.map((o) => optionHtml(q, o, qid, d, dis)).join('')}</div>` : ''}
${opts.length ? `<div class="qitembar">${customToggle}${skip}${actions}</div>${custom}` : `<div class="qitembar qwritebar">${custom}${skip}${actions}</div>`}
<div class="qskipnote" data-qskipnote${d.skipped ? '' : ' hidden'}>已跳过本题，提交时不会带上本题答案。</div>
</section>`;
}

function hintFor(sid, callId, canForm, qids, step) {
  const send = sendFor(sid, callId);
  if (send.phase === 'accepted') return '回答已提交，正在等待运行时确认';
  if (send.phase === 'submitting') return '正在提交回答…';
  if (!canForm) return '等待运行时允许作答…';
  if (!qids.length) return '题目内容暂不可用';
  const total = qids.length;
  const at = Math.max(0, Math.min(step || 0, total - 1));
  const d = draftFor(sid, callId, qids[at]);
  if (d.skipped) {
    if (at < total - 1) return '已跳过本题，可以继续';
  } else if (!resolved(d)) {
    return at < total - 1 ? '选择或填写后继续，也可以跳过本题' : '选择、填写或跳过后提交回答';
  }
  if (at < total - 1) {
    const laterMissing = qids.slice(at + 1).filter((qid) => !resolved(draftFor(sid, callId, qid))).length;
    return laterMissing ? `后面还有 ${laterMissing} 题，继续作答` : '后面的题已处理，可以继续';
  }
  const missing = qids.filter((qid) => !resolved(draftFor(sid, callId, qid))).length;
  if (missing) return `还有 ${missing} 题需处理`;
  return send.phase === 'error' ? '修改后可重试提交' : '确认无误后提交回答';
}

function footHint(card) {
  const items = [...card.querySelectorAll('.qitem:not(.qread)')];
  const view = viewFor(card.dataset.sid, card.dataset.call);
  const step = currentStep(view, items.length);
  return hintFor(card.dataset.sid, card.dataset.call, card.dataset.qcan === '1', items.map((item) => item.dataset.qid), step);
}

function submitLabel(card) {
  const send = sendFor(card.dataset.sid, card.dataset.call);
  if (send.phase === 'submitting') return '提交中…';
  if (send.phase === 'accepted') return '已提交';
  if (send.phase === 'error') return '重试提交';
  return '发送';
}

/** 用同一张卡片内的草稿刷新当前步骤、按钮与是否可提交，不替换输入节点。 */
function syncCard(card) {
  if (!card) return;
  const sid = card.dataset.sid;
  const callId = card.dataset.call;
  const send = sendFor(sid, callId);
  const canForm = card.dataset.qcan === '1';
  const busy = send.phase === 'submitting' || send.phase === 'accepted';
  const view = viewFor(sid, callId);
  const items = [...card.querySelectorAll('.qitem:not(.qread)')];
  const step = currentStep(view, items.length);
  const nav = card.querySelector('.qnav');
  const row = items[step]?.querySelector('.qitembar');
  if (nav && row && nav.parentNode !== row) row.appendChild(nav);
  let missing = 0;
  items.forEach((item, index) => {
    const d = draftFor(sid, callId, item.dataset.qid);
    const lockControls = !canForm || busy || d.skipped;
    const hasOptions = !!item.querySelector('.qopt');
    const expanded = customExpanded(view, item.dataset.qid, hasOptions, d);
    item.hidden = index !== step;
    item.classList.toggle('skipped', !!d.skipped);
    item.querySelectorAll('.qopt').forEach((b) => {
      b.disabled = lockControls;
      const on = (d.selected || []).includes(b.dataset.qopt);
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    const input = item.querySelector('input[data-qreply],textarea[data-qreply]');
    if (input) input.disabled = lockControls;
    const custom = item.querySelector('.qcustom');
    if (custom) custom.hidden = !expanded;
    const toggle = item.querySelector('[data-qact="custom"]');
    if (toggle) {
      const multi = item.dataset.qmulti === '1';
      toggle.textContent = expanded ? (multi ? '收起补充' : '收起填写') : (multi ? '补充回答' : '填写其他回答');
      toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
      toggle.disabled = lockControls;
    }
    const skip = item.querySelector('[data-qact="skip"]');
    if (skip) { skip.disabled = !canForm || busy; skip.textContent = d.skipped ? '撤销跳过' : '跳过本题'; }
    const note = item.querySelector('[data-qskipnote]');
    if (note) note.hidden = !d.skipped;
    if (!resolved(d)) missing++;
  });
  const err = card.querySelector('[data-qerr]');
  if (err) {
    err.hidden = send.phase !== 'error';
    err.textContent = send.phase === 'error' ? send.error : '';
  }
  const ok = card.querySelector('[data-qaccepted]');
  if (ok) ok.hidden = send.phase !== 'accepted';
  const hint = card.querySelector('[data-qhint]');
  if (hint) hint.textContent = footHint(card);
  const currentDraft = items.length ? draftFor(sid, callId, items[step]?.dataset.qid) : null;
  const prev = card.querySelector('[data-qact="prev"]');
  if (prev) { prev.hidden = step <= 0 || !items.length; prev.disabled = busy || !items.length; }
  const next = card.querySelector('[data-qact="next"]');
  if (next) {
    next.hidden = !items.length || step >= items.length - 1;
    next.disabled = !canForm || busy || !resolved(currentDraft);
  }
  const submit = card.querySelector('[data-qact="submit"]');
  if (submit) {
    submit.textContent = submitLabel(card);
    submit.hidden = !items.length || step !== items.length - 1;
    submit.disabled = !canForm || busy || missing > 0;
    submit.classList.toggle('dis', submit.disabled);
  }
}

export function questionCardHtml(rec, agentId = rec?.agentId, callId = rec?.callId) {
  if (!rec) return '';
  const sid = String(agentId ?? '');
  const cid = String(callId ?? '');
  const qs = questionsOf(rec);
  const view = viewFor(sid, cid);
  const step = currentStep(view, qs.length);
  const qids = qs.map((q, i) => qidOf(q, i));
  const send = sendFor(sid, cid);
  const ownSend = send.phase === 'submitting' || send.phase === 'accepted';
  const shownAnswers = Array.isArray(rec.answers) && rec.answers.length ? rec.answers : rec.state === 'submitted' && ownSend && Array.isArray(send.answers) ? send.answers : [];
  const answers = new Map(shownAnswers.map((a) => [String(a?.id), a]));
  const terminal = ['submitted', 'settled', 'closed'].includes(rec.state);
  const formState = rec.state === 'open' || rec.state === 'continued';
  const canForm = formState && rec.canAnswer === true;
  const missing = qids.filter((qid) => !resolved(draftFor(sid, cid, qid))).length;
  const blocked = !qs.length || !canForm || missing > 0 || send.phase === 'submitting' || send.phase === 'accepted';
  const mark = markOf({ state: rec.state, canAnswer: !!rec.canAnswer, questions: qs, answers: rec.answers || [] });
  const answered = qids.filter((qid) => answers.has(qid)).length;
  const head = rec.state === 'submitted' ? '回答已提交'
    : terminal ? (qs.length && answered === qs.length ? '已回答的问题' : answered ? '部分回答记录' : '没有收到回答')
    : qs.length === 1 ? '有个问题想问你' : '有几个问题想问你';
  const count = qs.length ? `${qs.length} 题` : '';
  const waiting = !terminal && !canForm;
  const atLast = qs.length > 0 && step === qs.length - 1;
  const currentDraft = qids.length ? draftFor(sid, cid, qids[step]) : null;
  const nextBlocked = !canForm || !resolved(currentDraft) || send.phase === 'submitting' || send.phase === 'accepted';
  const submitText = send.phase === 'submitting' ? '提交中…' : send.phase === 'accepted' ? '已提交' : send.phase === 'error' ? '重试提交' : '发送';
  const actions = `<div class="qnav"><button type="button" class="btn qnavbtn qprev" data-qact="prev" aria-label="上一题" title="上一题"${step <= 0 ? ' hidden' : ''}${send.phase === 'submitting' || send.phase === 'accepted' ? ' disabled' : ''}>${S.back}</button><button type="button" class="btn qnavbtn qnext" data-qact="next"${atLast ? ' hidden' : ''}${nextBlocked ? ' disabled' : ''}>下一题</button><button type="button" class="btn dk qsubmit qnavbtn${blocked ? ' dis' : ''}" data-qact="submit"${atLast ? '' : ' hidden'}${blocked ? ' disabled' : ''}>${submitText}</button></div>`;
  let body;
  if (terminal) {
    body = answered && qs.length ? qs.map((q, i) => readOnlyQuestion(q, i, answers)).join('') : `<div class="qnote">${rec.state === 'submitted' ? '回答已提交，正在等待运行时记录确认。' : '问题关闭时没有收到回答。'}</div>`;
  } else {
    body = qs.length ? qs.map((q, i) => formQuestion(q, i, qs.length, sid, cid, canForm, view, i === step ? actions : '')).join('') : '<div class="qnote">题目内容暂不可用。</div>';
  }
  const accepted = !terminal && send.phase === 'accepted';
  const footer = !terminal && qs.length ? `<div class="qfoot"><span class="qhint" data-qhint>${esc(hintFor(sid, cid, canForm, qids, step))}</span></div>` : '';
  return `<div class="qcard ${terminal ? 'qdone' : canForm ? 'qopen' : 'qwaiting'}" data-sid="${esc(sid)}" data-call="${esc(cid)}" data-qcan="${canForm ? '1' : '0'}" data-qstate="${esc(mark)}">
<div class="qhead"><b>${S.q}<span>${head}</span></b>${count ? `<span class="qcount">${count}</span>` : ''}<span class="qbadge">${badgeText(rec)}</span></div>
${waiting ? `<div class="qwait">${S.clock}<span>这个问题正在恢复，稍后可以继续回答。</span></div>` : ''}
${body}
${terminal ? '' : `<div class="qerr" data-qerr${send.phase === 'error' ? '' : ' hidden'}>${esc(send.error)}</div><div class="qaccepted" data-qaccepted${accepted ? '' : ' hidden'}>回答已提交，正在等待运行时确认。</div>${footer}`}
</div>`;
}

function resultOf(tool) {
  const raw = typeof tool?.result?.text === 'string' ? tool.result.text : '';
  if (!raw) return { raw: '', answers: null, pending: false };
  try {
    const v = JSON.parse(raw);
    return { raw, answers: Array.isArray(v?.answers) ? v.answers : null, pending: v?.pending === true };
  } catch { return { raw, answers: null, pending: false }; }
}

function previewOptions(q) {
  const opts = optionsOf(q);
  return opts.length ? `<div class="qpreviewopts">${opts.map((o) => `<div class="qpreviewopt"><b>${esc(o.label)}</b>${o.description ? `<small>${esc(o.description)}</small>` : ''}</div>`).join('')}</div>` : '';
}

function untrackedToolCard(agentId, tool) {
  const cid = String(tool?.id ?? '');
  const qs = questionsOf(tool?.args);
  const res = resultOf(tool);
  const answers = new Map((res.answers || []).map((a) => [String(a?.id), a]));
  const answered = qs.filter((q, i) => answers.has(qidOf(q, i))).length;
  const hasAnswers = answered > 0;
  const title = hasAnswers ? (qs.length && answered === qs.length ? '已回答的问题' : '部分回答记录') : res.pending ? '问题等待回答' : '问题记录';
  const mark = markOf({ tool: { id: cid, args: tool?.args || {}, result: tool?.result?.text || '', pending: !!tool?.pending } });
  const body = hasAnswers ? qs.map((q, i) => readOnlyQuestion(q, i, answers)).join('')
    : qs.map((q, i) => `<section class="qitem qread" data-qid="${esc(qidOf(q, i))}">${questionHeading(q, i, qs.length, true)}${previewOptions(q)}</section>`).join('');
  const note = hasAnswers ? '' : res.pending
    ? '运行时记录显示这个问题仍可能可答，但当前没有可提交的问题状态；请在问题状态同步后重试。'
    : '当前没有这个问题的实时或投影状态，不能在界面里提交回答。';
  return `<div class="qcard qdone quntracked" data-sid="${esc(agentId)}" data-call="${esc(cid)}" data-qcan="0" data-qstate="${esc(mark)}">
<div class="qhead"><b>${S.q}<span>${title}</span></b>${qs.length ? `<span class="qcount">${qs.length} 题</span>` : ''}<span class="qbadge">${hasAnswers ? '只读' : '不可提交'}</span></div>
${body}${note ? `<div class="qnote">${note}</div>` : ''}${!hasAnswers && res.raw ? `<div class="qraw">${esc(res.raw.slice(0, 800))}</div>` : ''}
</div>`;
}

/** 会话中的工具行或投影记录对应的追问卡；同一 callId 只交给调用方渲染一次。 */
export function toolQuestionHtml(agentId, tool) {
  const cid = String(tool?.id ?? '');
  if (cid) {
    const rec = matchQuestionCall(agentId, cid, questionsOf(tool?.args));
    if (rec) {
      // 已结算投影只带 callId/answers；有对应工具记录时补回题目正文。
      let merged = questionsOf(rec).length ? rec : { ...rec, questions: questionsOf(tool?.args) };
      const result = resultOf(tool);
      // Blocking legacy calls have no durable projection; their real tool answer is authoritative.
      if (result.answers && !tool?.result?.isError) merged = { ...merged, state: 'settled', answers: result.answers, canAnswer: false };
      return questionCardHtml(merged, agentId, cid);
    }
  }
  return untrackedToolCard(agentId, tool);
}

export function extraQuestionHtml(agentId, seen, includeSettled = true) {
  const used = new Set([...seen].map(String));
  const out = [];
  for (const rec of questionsFor(agentId) || []) {
    const cid = String(rec?.callId ?? '');
    if (!cid || used.has(cid)) continue;
    used.add(cid);
    if (['settled', 'closed'].includes(rec.state) && (!includeSettled || !questionsOf(rec).length)) continue;
    out.push(questionCardHtml(rec, agentId, cid));
  }
  return out.join('');
}

export function hasOpenQuestion(agentId) {
  return (questionsFor(agentId) || []).some((r) => r && (r.state === 'open' || r.state === 'continued'));
}

/** 只复用服务端状态未变的节点，避免流式刷新时打断追问输入框的焦点和选区。 */
export function captureQuestionCards(root) {
  const saved = new Map();
  if (!root) return saved;
  for (const card of root.querySelectorAll('.qcard[data-sid][data-call]')) {
    const active = card.contains(document.activeElement) ? document.activeElement : null;
    saved.set(BASE(card.dataset.sid, card.dataset.call), {
      node: card, state: card.dataset.qstate || '', active,
      start: active && typeof active.selectionStart === 'number' ? active.selectionStart : null,
      end: active && typeof active.selectionEnd === 'number' ? active.selectionEnd : null,
      dir: active?.selectionDirection || undefined,
    });
  }
  return saved;
}

export function restoreQuestionCards(root, saved) {
  if (!root || !saved?.size) return;
  for (const fresh of [...root.querySelectorAll('.qcard[data-sid][data-call]')]) {
    const old = saved.get(BASE(fresh.dataset.sid, fresh.dataset.call));
    if (!old) continue;
    if (old.state !== (fresh.dataset.qstate || '')) {
      // A live question becoming a late reply must not interrupt an answer being typed.
      if (old.active?.matches?.('[data-qreply]')) {
        const next = [...fresh.querySelectorAll('[data-qreply]')].find((t) => t.dataset.qid === old.active.dataset.qid && !t.disabled);
        if (next) { next.focus({ preventScroll: true }); next.setSelectionRange(old.start ?? 0, old.end ?? old.start ?? 0); }
      }
      continue;
    }
    fresh.replaceWith(old.node);
    if (old.active && old.node.contains(old.active)) {
      try { old.active.focus({ preventScroll: true }); }
      catch { try { old.active.focus(); } catch { /* 节点已不可编辑时保留其余界面 */ } }
      try {
        if (old.start != null) {
          if (old.dir) old.active.setSelectionRange(old.start, old.end ?? old.start, old.dir);
          else old.active.setSelectionRange(old.start, old.end ?? old.start);
        }
      } catch { /* 非文本节点没有选区 */ }
    }
  }
}

export function isQuestionTextarea(target) {
  return !!target?.matches?.('input[data-qreply],textarea[data-qreply]');
}

export function questionInput(target) {
  if (!isQuestionTextarea(target)) return false;
  const card = target.closest('.qcard[data-sid][data-call]');
  const item = target.closest('.qitem[data-qid]');
  if (!card || !item) return true;
  const d = draftFor(card.dataset.sid, card.dataset.call, item.dataset.qid);
  d.custom = target.value;
  if (item.dataset.qmulti !== '1' && d.custom.length) {
    d.selected = [];
    item.querySelectorAll('.qopt').forEach((b) => { b.classList.remove('on'); b.setAttribute('aria-pressed', 'false'); });
  }
  syncCard(card);
  return true;
}

function setError(card, message) {
  const send = sendFor(card.dataset.sid, card.dataset.call);
  send.phase = 'error';
  send.error = message || '提交失败，请重试';
  syncCard(card);
}

async function submitCard(card) {
  const sid = card.dataset.sid;
  const callId = card.dataset.call;
  const send = sendFor(sid, callId);
  if (send.phase === 'submitting' || send.phase === 'accepted') return;
  let rec;
  try { rec = questionFor(sid, callId); }
  catch (e) { setError(card, '读取问题状态失败：' + (e?.message || e)); return; }
  if (!rec || rec.canAnswer !== true || !['open', 'continued'].includes(rec.state)) {
    card.dataset.qcan = '0';
    setError(card, '这个问题当前不能提交，请等待运行时更新状态。');
    return;
  }
  const qs = questionsOf(rec);
  const missing = qs.filter((q, i) => !resolved(draftFor(sid, callId, qidOf(q, i))));
  if (missing.length) {
    setError(card, `还有 ${missing.length} 题未作答或明确跳过。`);
    return;
  }
  const answers = qs.map((q, i) => {
    const id = q.id;
    const d = draftFor(sid, callId, qidOf(q, i));
    if (d.skipped) return { id, selected: [] };
    const selected = Array.isArray(d.selected) ? [...d.selected] : [];
    const custom = String(d.custom || '').trim();
    if (multiOf(q)) return { id, selected, ...(custom ? { custom } : {}) };
    return custom ? { id, selected: [], custom } : { id, selected: selected.slice(0, 1) };
  });
  send.phase = 'submitting';
  send.error = '';
  send.answers = answers;
  syncCard(card);
  try {
    const ok = await api.answerQuestion(sid, callId, answers);
    if (ok !== true) throw new Error('运行时没有接受这条回答，请重试');
    send.phase = 'accepted';
    syncCard(card);
  } catch (e) {
    send.phase = 'error';
    send.error = '提交失败：' + (e?.message || e || '请重试');
    syncCard(card);
  }
}

export function questionClick(event) {
  const btn = event.target.closest?.('[data-qact]');
  if (!btn) return false;
  const card = btn.closest('.qcard[data-sid][data-call]');
  if (!card) return false;
  event.preventDefault();
  if (btn.disabled) return true;
  const sid = card.dataset.sid;
  const callId = card.dataset.call;
  const send = sendFor(sid, callId);
  if (send.phase === 'submitting' || send.phase === 'accepted') return true;
  const action = btn.dataset.qact;
  const item = btn.closest('.qitem[data-qid]');
  const view = viewFor(sid, callId);
  if (action === 'opt' && item) {
    const d = draftFor(sid, callId, item.dataset.qid);
    if (item.dataset.qmulti === '1') {
      const at = d.selected.indexOf(btn.dataset.qopt);
      at >= 0 ? d.selected.splice(at, 1) : d.selected.push(btn.dataset.qopt);
    } else {
      d.selected = [btn.dataset.qopt];
      d.custom = '';
      view.customOpen.set(item.dataset.qid, false);
      const input = item.querySelector('input[data-qreply],textarea[data-qreply]');
      if (input) input.value = '';
    }
    syncCard(card);
  } else if (action === 'custom' && item) {
    const d = draftFor(sid, callId, item.dataset.qid);
    const hasOptions = !!item.querySelector('.qopt');
    view.customOpen.set(item.dataset.qid, !customExpanded(view, item.dataset.qid, hasOptions, d));
    syncCard(card);
  } else if (action === 'skip' && item) {
    const d = draftFor(sid, callId, item.dataset.qid);
    d.skipped = !d.skipped;
    syncCard(card);
  } else if (action === 'next') {
    const items = [...card.querySelectorAll('.qitem:not(.qread)')];
    const step = currentStep(view, items.length);
    const d = items.length ? draftFor(sid, callId, items[step].dataset.qid) : null;
    if (items.length && resolved(d)) { view.step = Math.min(step + 1, items.length - 1); syncCard(card); }
  } else if (action === 'prev') {
    const items = [...card.querySelectorAll('.qitem:not(.qread)')];
    const step = currentStep(view, items.length);
    view.step = Math.max(0, step - 1);
    syncCard(card);
  } else if (action === 'submit') {
    void submitCard(card);
  }
  return true;
}
