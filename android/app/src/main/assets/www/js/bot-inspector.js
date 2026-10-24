// A read-only projection of the current local Agent log; completion never implies acceptance.
import {esc,basename} from './util.js';
import {S} from './icons.js';
import {Conv} from './conv.js';
const flatten=items=>items.flatMap(it=>it.k==='process'?flatten(it.items):it.k==='tools'?it.tools.map(t=>({k:'tool',...t})):it);
export function inspectorSnapshot(conv,running){
  const events=conv?[...conv.events.values()].sort((a,b)=>(a.seq||0)-(b.seq||0)):[];
  const start=events.filter(e=>e.type==='turn/start').at(-1);
  let current=conv;
  if(start){
    current=new Conv(conv.sid);
    current.header=conv.header;
    current.events=new Map(events.filter(e=>e.seq>=start.seq).map(e=>[e.seq,e]));
    current.live=conv.live;
  }
  const items=current?flatten(current.build({running})):[];
  const tools=items.filter(it=>it.k==='tool'&&it.name!=='present');
  const artifacts=[...new Set(items.filter(it=>it.k==='art').map(it=>it.path))];
  for(const it of items.filter(it=>it.k==='tool'&&it.name==='present'&&it.result&&!it.result.isError))for(const file of it.args.files||[])if(file.path&&!artifacts.includes(file.path))artifacts.push(file.path);
  const todo=[...tools].reverse().find(t=>['todo','todo_write'].includes(t.name)&&Array.isArray(t.args?.todos));
  const steps=todo?todo.args.todos.map(t=>({text:t.content||t.title||'待办',state:t.status==='completed'?'done':t.status==='in_progress'?'running':'pending'})):
    tools.slice(-12).map(t=>({text:t.args?.description||t.name,state:t.result?t.result.isError?'failed':'done':running?'running':'pending'}));
  const lastTurn=events.filter(e=>e.type==='turn/end'&&(!start||e.seq>=start.seq)).at(-1);
  const status=running?'运行中':!conv?.loaded?'等待会话':lastTurn?.data.reason?.kind==='completed'?'本轮已结束':lastTurn?'已停止':start?'本轮未结束':'尚未开始';
  return {status,steps,artifacts};
}
export function inspectorHtml(snapshot,title){
  const s=snapshot;
  return `<header class="bot-detail-head"><b>当前任务</b><button type="button" class="cbtn" data-act="inspector" aria-label="收起任务详情">${S.x}</button></header><div class="bot-detail-scroll"><h2>${esc(title)}</h2><span class="bot-detail-status${s.status==='运行中'?' running':''}">${esc(s.status)}</span><section><h3>${S.list}执行过程</h3>${s.steps.length?`<ol class="bot-step-list">${s.steps.map(step=>`<li class="${step.state}"><i></i><span>${esc(step.text)}</span><small>${{done:'已返回',failed:'未成功',running:'进行中',pending:'待处理'}[step.state]}</small></li>`).join('')}</ol>`:'<p class="bot-detail-muted">当前聊天产生执行记录后显示</p>'}</section><section><h3>${S.box}产物</h3>${s.artifacts.length?s.artifacts.map(path=>`<button class="bot-artifact" type="button" data-art="${esc(path)}">${S.file}<span>${esc(basename(path))}</span>${S.right}</button>`).join(''):'<p class="bot-detail-muted">尚未记录产物</p>'}</section></div>${s.status==='运行中'?'<footer><button type="button" class="bot-detail-stop" data-act="stop">停止任务</button></footer>':''}`;
}
