/**
 * 对话页（Harness）交互回归：单一发送/停止、停止单飞、按钮语义。
 * 用真实 www/js/harness.js + 合成 DOM + 假 dsh/原生桥执行。
 */
import test from 'node:test';
import {webcrypto} from 'node:crypto';
import assert from 'node:assert/strict';
import { createDocument, El } from './dom.mjs';
import { loadModule, synthetic, makeStorage } from './loader.mjs';

function setupDom() {
  const doc = createDocument();
  const root = new El('div', doc);
  root.className = 'scr on';
  doc.body.appendChild(root);
  const mk = (tag, id) => { const el = new El(tag, doc); if (id) el.setAttribute('id', id); doc.body.appendChild(el); return el; };
  mk('input', 'filepick');
  mk('div', 'menu');
  mk('div', 'menu-mask');
  const sheet = mk('div', 'sheet');
  const mask = new El('div', doc); mask.className = 'mask'; sheet.appendChild(mask);
  sheet.appendChild(new El('div', doc));
  sheet.querySelector('.pane');
  const pane = new El('div', doc); pane.className = 'pane'; sheet.appendChild(pane);
  mk('div', 'toast');
  return { doc, root };
}

async function boot({ running = false, storage = {}, registry = {defaultBotId:'default',bots:[{id:'default',name:'Bot',pinned:true,sessionIds:[]}]} } = {}) {
  const { doc, root } = setupDom();
  const store = makeStorage(storage);
  const calls = { cancel: 0, cancelArgs: [], api: [], approvals: [], bots: [], browser: [], copy: [], voice: 0 };
  const listeners = new Set(); const follows = [];
  const dshState = {
    state: 'online',
    error: '',
    home: '/root',
    clientId: 'c1',
    sessions: new Map(),
    workspaces: [{ workspaceId: 'w1', path: '/root/projects/demo', title: 'demo', sessionIds: [] }],
    pinned: [],
    archived: new Set(),
    running: new Set(running ? ['sid-1'] : []),
    approvals: new Map(),
    questions: new Map(),
    catalog: null,
    presets: { options: [{ value: 'read-only' }] },
  };
  const api = {
    cancel: async (sid) => { calls.cancel += 1; calls.cancelArgs.push(sid); return { ok: true }; },
    selectModel: async () => ({}),
    setPermission: async () => ({}),
    prompt: async () => ({ accepted: true }),
    command: async () => ({}),
  };
  const native = {
    call: async (method,args) => { if(!['agentVoiceScope','agentVoiceFrame'].includes(method))throw Error('unexpected host call: '+method);return true; },
    on: () => () => {},
    bots: async input => {
      calls.bots.push(input);const b=registry.bots.find(b=>b.id===input.id);
      if(input.op==='create'&&!b)registry.bots.push({id:input.id,name:input.name,pinned:true,sessionIds:[]});
      if(input.op==='rename')b.name=input.name;
      if(input.op==='pin')b.pinned=input.pinned;
      if(input.op==='bind'){if(!b.sessionIds.includes(input.sessionId))b.sessionIds.push(input.sessionId);b.selectedSessionId=input.sessionId;}
      if(input.op==='select')b.selectedSessionId=input.sessionId;
      if(input.op==='settings')return {workspace:b.workspace||'/bots/'+input.id,instructions:b.instructions||'',memory:b.memory||'',enabledTools:b.enabledTools||['conversation-history']};
      if(input.op==='saveSettings'){b.instructions=input.instructions;b.memory=input.memory;b.enabledTools=input.enabledTools;}
      if(input.op==='persona')return {text:'共同人格测试'};
      if(input.op==='savePersona')registry.persona=input.text;
      return JSON.parse(JSON.stringify(registry));
    },
    getConfig: async () => ({ harnessMode: 'native', dshMode: 'embedded' }),
    setConfig: async () => ({}),
    http: async () => ({ status: 200, text: '{}' }),
    copy: async text => { calls.copy.push(text); return true; },
    toast: async () => true,
    openExternal: async () => true,
    openDshbox: async () => true,
    browser: async state => {calls.browser.push(state);return true;},
    dshWeb: async () => true,
    voice: async () => { calls.voice += 1; return { text: '' }; },
    fileUrl: (p) => p,
    fileFromUrl: () => null,
  };
  const window = {
    innerWidth: 1200,
    innerHeight: 900,
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  const sandbox = {
    crypto: webcrypto,
    innerWidth:1200,innerHeight:900,
    document: doc,
    window,
    localStorage: store,
    navigator: { userAgent: 'node' },
    location: { origin: 'http://localhost', href: 'http://localhost/' },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    requestAnimationFrame: (fn) => setTimeout(() => fn(Date.now()), 0),
    ResizeObserver: class { observe() {} disconnect() {} unobserve() {} },
    FileReader: class { readAsDataURL() {} },
    Image: class { set src(v) { this._src = v; } get src() { return this._src; } },
    URL,
    Event: class { constructor(type) { this.type = type; } },
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;

  const { ns } = await loadModule('harness.js', {
    stubs: {
      './dsh.js': (ctx) => synthetic(ctx, {
        dsh: dshState,
        onDsh: fn => {listeners.add(fn);return () => listeners.delete(fn);},
        api,
        follow: (sid,cb) => {follows.push({sid,cb});return {cancel(){}};},
        decideApproval: async (id,allow) => {calls.approvals.push({id,allow});},
        startDsh: () => {},
        reconnectNow: () => {},
        questionFor: () => null,
        questionsFor: () => [],
        matchQuestionCall: () => null,
      }),
      './native.js': (ctx) => synthetic(ctx, { native, on: native.on, fileUrl: native.fileUrl, fileFromUrl: native.fileFromUrl, isAndroid: false }),
      './settings.js': (ctx) => synthetic(ctx, { pair: async () => {} }),
      './rt.js': (ctx) => synthetic(ctx, { rt: { state: 'ready', embedded: false, keySet: true }, onRt: () => () => {}, rtLine: () => '', retryRt: async () => {} }),
    },
    globals: sandbox,
  });
  await ns.mount(root);
  return { ns, root, doc, calls, dshState, follows, api, native, storage: store, emit:what=>{for(const fn of listeners)fn(what);} };
}

const row = (root) => root.querySelector('.input .row');
const sendBtn = (root) => row(root).querySelector('[data-act="send"]');
const stopBtn = (root) => row(root).querySelector('[data-act="stop"]');

async function seedAssistantMessages(app, count = 6) {
  const records = Array.from({ length: count }, (_, i) => ({
    type: 'event',
    event: { seq: i + 1, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: `第${i + 1}段` }] } } },
  }));
  app.follows.at(-1).cb({ type: 'snapshot', header: {}, records });
  await tick();
}

function installChatLayout() {
  const rect = Object.getOwnPropertyDescriptor(El.prototype, 'getBoundingClientRect');
  const scrollHeight = Object.getOwnPropertyDescriptor(El.prototype, 'scrollHeight');
  const clientHeight = Object.getOwnPropertyDescriptor(El.prototype, 'clientHeight');
  Object.defineProperty(El.prototype, 'getBoundingClientRect', { ...rect, value() {
    if (this.classList?.contains('msgs')) return { top: 0, left: 0, right: 600, bottom: 100, width: 600, height: 100 };
    const key = this.dataset?.scrollKey;
    if (typeof key === 'string' && key.startsWith('text:')) {
      const index = Number(key.slice(5)); const box = this.closest('.msgs'); const top = index * 100 - (box?.scrollTop || 0);
      return { top, left: 0, right: 600, bottom: top + 40, width: 600, height: 40 };
    }
    return rect.value.call(this);
  } });
  Object.defineProperty(El.prototype, 'scrollHeight', { ...scrollHeight, get() { return this.classList?.contains('msgs') ? Math.max(200, this.querySelectorAll('[data-scroll-key]').length * 100) : 200; } });
  Object.defineProperty(El.prototype, 'clientHeight', { ...clientHeight, get() { return this.classList?.contains('msgs') ? 100 : 100; } });
  return () => {
    Object.defineProperty(El.prototype, 'getBoundingClientRect', rect);
    Object.defineProperty(El.prototype, 'scrollHeight', scrollHeight);
    Object.defineProperty(El.prototype, 'clientHeight', clientHeight);
  };
}

test('运行中且没有草稿时只有停止按钮', async () => {
  const app = await boot({ running: true, storage: { 'pd.h.sel': '"sid-1"' } });
  assert.equal(app.ns.dev.H.sel, 'sid-1', '应当选中运行中的会话');
  assert.ok(stopBtn(app.root), '运行中应显示停止按钮');
  assert.ok(!sendBtn(app.root), '没有草稿时不能同时出现发送按钮');
});

test('输入行只移除麦克风语音入口，保留添加和模型操作', async () => {
  const app = await boot({ storage: { 'pd.h.sel': '"sid-1"' } });
  const acts = [...row(app.root).querySelectorAll('button')].map(button => button.dataset.act);
  assert.ok(!acts.includes('mic'), '输入行不应再显示麦克风按钮');
  assert.ok(acts.includes('plus'), '添加图片和操作入口应保留');
  assert.ok(acts.includes('model'), '模型入口应保留');
  assert.equal(app.calls.voice, 0, '渲染输入行不应触发共享语音接口');
});

test('围栏代码块右上角复制按钮提交原文且不打开菜单', async () => {
  const app = await boot({ storage: { 'pd.h.sel': '"sid-1"' } });
  const source = 'const answer = 42;\n  第二行保留缩进';
  const markdown = ['说明', '```js', source, '```', '结尾 `inline`'].join('\n');
  app.follows.at(-1).cb({ type: 'snapshot', header: {}, records: [
    { type: 'event', event: { seq: 1, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: markdown }] } } } },
  ] });
  await tick();
  const button = app.root.querySelector('.msgs .code-block .code-copy');
  assert.ok(button, '围栏代码块应有右上角复制按钮');
  assert.equal(app.root.querySelectorAll('.msgs .code-copy').length, 1, '行内代码不应添加复制按钮');
  assert.equal(button.closest('.code-block').querySelector('pre code').textContent, source);
  button.dispatchEvent('click', { target: button });
  await tick();
  assert.deepEqual(app.calls.copy, [source], '应复制围栏内原文而不是 Markdown 或渲染文本');
  assert.ok(!app.doc.getElementById('menu').classList.contains('on'), '复制不应打开新菜单');
});

test('运行中输入草稿后只剩发送按钮，清空后恢复停止', async () => {
  const app = await boot({ running: true, storage: { 'pd.h.sel': '"sid-1"' } });
  const ta = app.root.querySelector('.input textarea');
  ta.value = '继续';
  ta.dispatchEvent('input', { target: ta });
  assert.ok(sendBtn(app.root), '有草稿时应显示发送按钮');
  assert.ok(!stopBtn(app.root), '有草稿时不能同时显示停止按钮');
  assert.equal(sendBtn(app.root).disabled, false, '有草稿时发送按钮可点');

  ta.value = '   ';
  ta.dispatchEvent('input', { target: ta });
  assert.ok(stopBtn(app.root), '清空草稿后应恢复停止按钮');
  assert.ok(!sendBtn(app.root), '清空草稿后不能显示发送按钮');
});

test('停止是单飞的：连点停止只发一次取消请求', async () => {
  const app = await boot({ running: true, storage: { 'pd.h.sel': '"sid-1"' } });
  const stop = stopBtn(app.root);
  stop.dispatchEvent('click', { target: stop });
  stop.dispatchEvent('click', { target: stop });
  await new Promise((r) => setTimeout(r, 5));
  assert.equal(app.calls.cancel, 1, `连点停止只应发出 1 个取消请求，实际 ${app.calls.cancel}`);
  assert.deepEqual(app.calls.cancelArgs, ['sid-1']);
});

test('权限请求必须显示完整操作参数；更新后不可批准旧内容', async () => {
  const app = await boot({storage:{'pd.h.sel':'"sid-1"'}});
  app.dshState.approvals.set('a1',{eventId:'a1',agentId:'sid-1',request:{toolName:'bash',callId:'c1'}});
  app.ns.dev.H.conv={sid:'sid-1',loaded:true,events:new Map(),build:()=>[]};
  app.emit('approvals');
  let allow = app.root.querySelector('[data-ok="1"]');
  assert.equal(allow.disabled,true);
  allow.dispatchEvent('click',{target:allow});
  assert.equal(app.calls.approvals.length,0);
  const event={type:'tool/call',data:{callId:'c1',arguments:JSON.stringify({command:'echo one',cwd:'/project'})}};
  app.ns.dev.H.conv.events.set('c1',event); app.emit('approvals');
  allow = app.root.querySelector('[data-ok="1"]');
  assert.equal(allow.disabled,false);
  assert.match(app.root.querySelector('.apv .cmd').textContent,/echo one/);
  event.data.arguments=JSON.stringify({command:'echo two'});
  allow.dispatchEvent('click',{target:allow});
  assert.equal(app.calls.approvals.length,0,'显示与当前参数不同必须重新查看');
  allow = app.root.querySelector('[data-ok="1"]');
  allow.dispatchEvent('click',{target:allow});
  await new Promise(r=>setTimeout(r,0));
  assert.equal(app.calls.approvals.length,1);
});

test('follow快照迟到的工具参数自动启用审批，不依赖另一次审批事件', async () => {
  const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
  app.dshState.approvals.set('a1',{eventId:'a1',agentId:'sid-1',request:{toolName:'bash',callId:'c1'}});app.emit('approvals');
  assert.equal(app.root.querySelector('[data-ok="1"]').disabled,true);
  app.follows.at(-1).cb({type:'snapshot',header:{},cursor:1,records:[{type:'event',event:{seq:1,type:'tool/call',data:{callId:'c1',toolName:'bash',arguments:JSON.stringify({command:'echo snapshot'})}}}]});
  assert.equal(app.root.querySelector('[data-ok="1"]').disabled,false);
  assert.match(app.root.querySelector('.apv .cmd').textContent,/echo snapshot/);
});


test('手机对话没有权限选择、项目切换或添加任意目录入口',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 assert.equal(app.root.querySelector('[data-act="perm"]'),null);
 assert.equal(app.root.querySelector('[data-act="addws"]'),null);
 assert.equal(app.root.querySelector('[data-act="pickws"]'),null);
});


test('同一 Bot 内切换会话保留输入节点、各自草稿与附件，不向其他会话发送',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"a"'}}),ta=app.root.querySelector('.input textarea');
 ta.value='a 的草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.H.attach=[{name:'a.png',data:'synthetic'}];
 app.ns.dev.select('b');assert.equal(app.root.querySelector('.input textarea'),ta);assert.equal(ta.value,'');
 ta.value='b 的草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.select('a');
 assert.equal(ta.value,'a 的草稿');assert.equal(app.ns.dev.H.attach[0].name,'a.png');
 app.ns.dev.select('b');assert.equal(ta.value,'b 的草稿');assert.equal(app.calls.api.length,0);
});
test('折叠重排和展开任务详情不重建消息、输入或浏览器容器',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"a"'}}),ta=app.root.querySelector('.input textarea'),msgs=app.root.querySelector('.msgs'),browser=app.root.querySelector('.bview');
 ta.value='不要丢';ta.focus();ta.setSelectionRange(1,2);
 const toggle=app.root.querySelector('[data-act="inspector"]');toggle.dispatchEvent('click',{target:toggle});
 app.doc.body.classList.add('cover');app.ns.onLayout();app.doc.body.classList.remove('cover');app.ns.onLayout();
 assert.equal(app.root.querySelector('.input textarea'),ta);assert.equal(app.root.querySelector('.msgs'),msgs);assert.equal(app.root.querySelector('.bview'),browser);
 assert.equal(ta.value,'不要丢');assert.equal(ta.selectionStart,1);assert.equal(ta.selectionEnd,2);
});


test('打开产物侧栏重建和变宽路径都保留阅读位置、草稿和选区',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"','pd.h.widths':'{"side":20,"chat":70}'}});
 await seedAssistantMessages(app);
 const ta=app.root.querySelector('.input textarea');ta.value='打开侧栏前的草稿';ta.focus();ta.setSelectionRange(3,5);
 const restoreLayout=installChatLayout();
 try{
   const msgs=app.root.querySelector('.msgs');
   msgs.scrollTop=250;msgs.dispatchEvent('scroll',{target:msgs});
   app.ns.dev.openArtifact('/BOT/default/out/report.html');await tick();
   const reopened=app.root.querySelector('.msgs');
   assert.equal(reopened.scrollTop,250,'首次打开会重建消息容器，应恢复原阅读锚点');
   const keptTa=app.root.querySelector('.input textarea');
   assert.equal(keptTa.value,'打开侧栏前的草稿');
   assert.equal(keptTa.selectionStart,3);assert.equal(keptTa.selectionEnd,5);

   reopened.scrollTop=180;reopened.dispatchEvent('scroll',{target:reopened});
   app.storage.setItem('pd.h.widths',JSON.stringify({side:25,chat:60}));
   app.ns.dev.openArtifact('/BOT/default/out/second.html');await tick();
   assert.equal(app.root.querySelector('.msgs'),reopened,'已有侧栏只变宽，不应重建消息容器');
   assert.equal(app.root.querySelector('.chat').style.width,'72px','测试应覆盖实际宽度变化路径');
   assert.equal(reopened.scrollTop,180,'宽度变化后应按原锚点恢复');
   const follow=app.follows.at(-1).cb;
   follow({type:'assistant-stream',frame:{type:'start',attemptId:'after-upscroll'}});
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-upscroll',chunk:{type:'block-start',index:0,blockType:'text'}}});await tick();
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-upscroll',chunk:{type:'text-delta',index:0,text:'上滑后的新输出'}}});await tick();
   assert.equal(reopened.scrollTop,180,'用户已上滑时新输出不应强制跟随到底部');
 }finally{restoreLayout();}
});

test('打开产物侧栏后底部阅读继续跟随新输出',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 await seedAssistantMessages(app);
 const restoreLayout=installChatLayout();
 try{
   const msgs=app.root.querySelector('.msgs');
   msgs.scrollTop=msgs.scrollHeight-msgs.clientHeight;msgs.dispatchEvent('scroll',{target:msgs});
   app.ns.dev.openArtifact('/BOT/default/out/report.html');await tick();
   const opened=app.root.querySelector('.msgs');
   assert.equal(opened.scrollTop,500,'打开侧栏时底部阅读不应跳顶');
   const follow=app.follows.at(-1).cb;
   follow({type:'assistant-stream',frame:{type:'start',attemptId:'after-open'}});
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-open',chunk:{type:'block-start',index:0,blockType:'text'}}});await tick();
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-open',chunk:{type:'text-delta',index:0,text:'新输出'}}});await tick();
   assert.equal(opened.scrollTop,600,'底部跟随应继续跟随侧栏打开后的流式输出');
 }finally{restoreLayout();}
});


test('切页再显示不重建或丢掉聊天草稿',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"a"'}}),ta=app.root.querySelector('.input textarea');
 ta.value='保留切页草稿';app.ns.onHide();app.ns.onShow();assert.equal(app.root.querySelector('.input textarea'),ta);assert.equal(ta.value,'保留切页草稿');
});

const twoBots=()=>({defaultBotId:'default',bots:[{id:'default',name:'旧记录',pinned:true,sessionIds:[]},{id:'writer',name:'写作',pinned:false,sessionIds:[]}]});
const tick=()=>new Promise(r=>setTimeout(r,15));
test('固定 Bot 的空白会话草稿和附件也彼此隔离',async()=>{
 const app=await boot({registry:twoBots()}),ta=app.root.querySelector('.input textarea');
 ta.value='默认草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.H.attach=[{name:'default.png'}];
 app.ns.dev.selectBot('writer');assert.equal(ta.value,'');assert.equal(app.ns.dev.H.attach.length,0);
 ta.value='写作草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.selectBot('default');
 assert.equal(ta.value,'默认草稿');assert.equal(app.ns.dev.H.attach[0].name,'default.png');
 app.ns.dev.selectBot('writer');assert.equal(ta.value,'写作草稿');assert.equal(app.calls.api.length,0);
});
test('新会话创建在途切换 Bot，绑定和发送仍属于发起机器人',async()=>{
 const app=await boot({registry:twoBots()});app.dshState.workspaces=[{workspaceId:'w',path:'/BOT/writer'}];
 let done;app.api.createSession=req=>new Promise(r=>{done=()=>r({sessionId:req.sessionId});});
 const prompts=[];app.api.prompt=async sid=>{prompts.push(sid);return {accepted:true};};
 app.ns.dev.selectBot('writer');const ta=app.root.querySelector('.input textarea');ta.value='发给写作';ta.dispatchEvent('input',{target:ta});
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();app.ns.dev.selectBot('default');done();await tick();
 const bind=app.calls.bots.find(a=>a.op==='bind');assert.equal(bind.id,'writer');assert.equal(prompts[0],bind.sessionId);
 assert.equal(app.ns.dev.H.botId,'default');assert.equal(app.ns.dev.H.sel,null);assert.equal(ta.value,'');
});
test('Bot 界面只提供机器人和当前聊天，不暴露历史或会话管理入口',async()=>{
 const registry=twoBots();registry.bots[1].sessionIds=['writer-chat'];registry.bots[1].selectedSessionId='writer-chat';
 const app=await boot({registry});
 for(const id of ['old-a','old-b','writer-chat'])app.dshState.sessions.set(id,{sessionId:id,projections:{values:{title:id}},updatedAt:10});
 app.ns.onShow();assert.equal(app.root.querySelectorAll('[data-bot]').length,2);assert.doesNotMatch(app.root.querySelector('.side .list').textContent,/old-a|old-b|writer-chat/);
 app.ns.dev.selectBot('writer');assert.equal(app.ns.dev.H.sel,'writer-chat');
 assert.equal(app.root.querySelector('[data-act="botSessions"]'),null);
 assert.equal(app.doc.querySelector('.bot-history-search'),null);
 assert.equal(app.doc.querySelectorAll('[data-history-session]').length,0);
 const more=app.root.querySelector('[data-act="more"]');more.dispatchEvent('click',{target:more});
 const menuText=app.doc.querySelector('#menu').textContent;
 for(const label of ['新会话','查看会话','重命名会话','复制会话 ID','归档会话','归档对话'])assert.ok(!menuText.includes(label),`不应出现会话入口：${label}`);
 for(const label of ['Bot 设置','重命名 Bot','置顶 Bot'])assert.ok(menuText.includes(label),`应保留 Bot 入口：${label}`);
});

test('Bot 设置保留内部会话检索和共同人格按钮',async()=>{
 const app=await boot({registry:twoBots()});
 const more=app.root.querySelector('[data-act="more"]');more.dispatchEvent('click',{target:more});
 const settings=app.doc.querySelectorAll('#menu .mi').find(n=>n.textContent==='Bot 设置');
 app.doc.querySelector('#menu').onclick({target:settings});
 await tick();
 const botSheet=app.doc.querySelector('#sheet .sb');
 assert.match(botSheet.textContent,/会话检索/);
 const persona=botSheet.querySelector('[data-common-persona]');
 assert.ok(persona);persona.onclick();await tick();
 assert.match(app.doc.querySelector('#sheet .sb').textContent,/共同人格/);
 assert.equal(app.doc.querySelector('[data-shared-persona]').value,'共同人格测试');
});

test('添加Bot在途切到其他Bot，完成后不抢回当前选择',async()=>{
 const app=await boot({registry:twoBots()}),original=app.native.bots;let done,created;
 app.native.bots=input=>input.op==='create'?new Promise(resolve=>{created=input.id;done=async()=>resolve(await original(input));}):original(input);
 const add=app.root.querySelector('[data-act=newBot]');add.dispatchEvent('click',{target:add});await tick();
 app.doc.querySelector('[data-bot-create-name]').value='新机器人';app.doc.querySelector('[data-create-bot]').onclick();await tick();
 app.ns.dev.selectBot('writer');await done();await tick();assert.equal(app.ns.dev.H.botId,'writer');assert.ok(app.ns.dev.H.bots.bots.some(b=>b.id===created));
});
test('两个Bot的新会话模型草稿互不串用',async()=>{
 const app=await boot({registry:twoBots()});app.dshState.catalog={default:{provider:'p',model:'Y'},groups:[{id:'p',models:[{id:'X',name:'X'},{id:'Y',name:'Y'}]}]};app.emit('catalog');
 const choose=app.root.querySelector('[data-act=model]');choose.dispatchEvent('click',{target:choose});
 const x=app.doc.querySelectorAll('#menu .mi').find(n=>n.textContent==='X');app.doc.querySelector('#menu').onclick({target:x});await tick();
 assert.match(app.root.querySelector('.mdl').textContent,/X/);app.ns.dev.selectBot('writer');assert.match(app.root.querySelector('.mdl').textContent,/Y/);
 app.ns.dev.selectBot('default');assert.match(app.root.querySelector('.mdl').textContent,/X/);
});
test('前端不调用正文搜索，会话检索仍保留为Bot内部工具',async()=>{
 const registry=twoBots();registry.bots[1].sessionIds=['writer-chat'];const app=await boot({registry});
 app.dshState.sessions.set('writer-chat',{sessionId:'writer-chat',projections:{values:{title:'Writer'}}});
 let called=0;app.api.searchSessions=async()=>{called++;return {items:[]}};
 const search=app.root.querySelector('[data-act="search"]');search.dispatchEvent('click',{target:search});
 const input=app.root.querySelector('.sfilter');input.value='写作';input.dispatchEvent('input',{target:input});
 await new Promise(r=>setTimeout(r,390));assert.equal(called,0);assert.equal(app.doc.querySelectorAll('[data-history-session]').length,0);
 assert.match(app.root.querySelector('.side .list').textContent,/写作/);
});


test('产物打开会关闭任务详情并复用已有预览标签，保留草稿', async () => {
  const app = await boot({storage:{'pd.h.inspector':'true'}});
  app.doc.body.classList.add('inner');
  app.ns.onLayout();
  const input=app.root.querySelector('.input textarea');input.value='未发送草稿';
  app.ns.dev.openArtifact('/BOT/default/documents/check.txt');
  await new Promise(r=>setTimeout(r,20));
  assert.equal(app.ns.dev.H.inspector,false);
  assert.equal(app.root.classList.contains('bot-detail-open'),false);
  assert.equal(app.root.classList.contains('nobrowser'),false);
  assert.equal(app.root.querySelector('.input textarea').value,'未发送草稿');
  app.ns.dev.H.inspector=true;
  app.root.classList.add('bot-detail-open');
  app.ns.dev.openArtifact('/BOT/default/documents/check.txt');
  assert.equal(app.ns.dev.H.tabs.length,1);
  assert.equal(app.root.classList.contains('bot-detail-open'),false);
  assert.equal(app.calls.browser.at(-1).active,app.ns.dev.H.active);
});

test('地址栏文件导航发出明确用户指令，普通布局同步不重复授予文件权限', async () => {
  const app=await boot();app.doc.body.classList.add('inner');app.ns.onLayout();
  app.ns.dev.openArtifact('/BOT/default/documents/check.txt');await tick();
  const input=app.root.querySelector('.addr input');input.value='/BOT/default/documents/next.html';
  app.root.dispatchEvent('keydown',{target:input,key:'Enter'});
  const commands=app.calls.browser.filter(value=>value.cmd==='navigate');
  assert.equal(commands.length,1);
  assert.match(commands[0].tabs.find(t=>t.id===commands[0].active).url,/\/next\.html$/);
  app.ns.syncOverlays();
  assert.equal(app.calls.browser.at(-1).cmd,undefined);
});

test('多组工具之间的阶段汇报留在正文，过程默认折叠，最终结论后汇报并入过程', async () => {
  const app=await boot({running:true,storage:{'pd.h.sel':'"sid-1"'}});
  const {ns:{Conv}}=await loadModule('conv.js');
  const conv=new Conv('sid-1');conv.snapshot({header:{cwd:'/BOT/default'},records:[]});app.ns.dev.H.conv=conv;
  let seq=0;
  const event=(type,data)=>conv.add({seq:++seq,type,time:1000+seq*100,data});
  const say=text=>event('assistant/message',{message:{content:[{type:'text',text}]}});
  const tool=id=>{event('assistant/message',{message:{content:[{type:'tool-call',id,name:'read',arguments:'{"file_path":"documents/test.txt"}'}]}});event('tool/result',{message:{toolCallId:id,content:[{type:'text',text:'synthetic file'}]}});};
  const paint=async()=>{app.emit('running');await new Promise(r=>setTimeout(r,25));};
  event('turn/start',{});say('先核对资料，再验证实现。');
  for(let i=0;i<3;i++)tool('group1-'+i);
  say('资料已核对，接下来验证边界。');await paint();
  assert.equal(app.root.querySelector('.process-fold').hasAttribute('open'),false);
  assert.ok(app.root.querySelector('.msgs').children.filter(e=>e.classList.contains('mdw')).some(e=>e.textContent==='资料已核对，接下来验证边界。'));
  assert.deepEqual(app.root.querySelector('.msgs').children.filter(e=>e.classList.contains('mdw')||e.classList.contains('process-fold')).map(e=>e.classList.contains('mdw')?e.textContent:'执行'),['先核对资料，再验证实现。','执行','资料已核对，接下来验证边界。','执行']);
  assert.equal(app.root.querySelectorAll('.process-fold').filter(e=>e.querySelector('summary .spin')).length,1,'只有最新执行段的摘要显示运行标记');
  for(let i=0;i<3;i++)tool('group2-'+i);
  say('边界验证完成，接下来整理结果。');await paint();
  assert.ok(app.root.querySelector('.msgs').children.filter(e=>e.classList.contains('mdw')).some(e=>e.textContent==='边界验证完成，接下来整理结果。'));
  for(let i=0;i<2;i++)tool('group3-'+i);
  say('最终结论：核对与验证已完成。');event('turn/end',{reason:{kind:'completed'}});
  app.dshState.running.clear();await paint();
  assert.equal(app.root.querySelector('.process-fold').hasAttribute('open'),false);
  assert.ok(app.root.querySelector('.process-content').textContent.includes('资料已核对，接下来验证边界。'));
  assert.ok(app.root.querySelector('.process-content').textContent.includes('边界验证完成，接下来整理结果。'));
  assert.deepEqual(app.root.querySelector('.msgs').children.filter(e=>e.classList.contains('mdw')).map(e=>e.textContent),['最终结论：核对与验证已完成。']);
});


test('Bot 本地图片 Markdown 变成可点击缩略图，代码示例不执行', async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,header:{cwd:'/BOT/default'},build:()=>[
 {k:'text',key:'picture',text:'![新版效果](</BOT/default/documents/page top.png>)\n\n`![代码](/BOT/default/documents/code.png)`\n\n![外部](https://example.invalid/track.png)'}]};
 app.emit('running');await new Promise(r=>setTimeout(r,25));
 const images=app.root.querySelectorAll('.chat-image');assert.equal(images.length,1);
 assert.equal(images[0].dataset.art,'/BOT/default/documents/page top.png');
 assert.match(images[0].innerHTML,/page top.png/);
 images[0].dispatchEvent('click',{target:images[0]});await tick();
 assert.equal(app.ns.dev.H.tabs.length,1);
});

test('插件自查截图在执行过程中可点击预览',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,build:()=>[
 {k:'tools',key:'preview',cat:'image',tools:[{id:'preview-1',name:'phone_plugins_preview',args:{op:'snapshot'},result:{text:'截图文件：/BOT/default/documents/plugin-preview-123.png'},cwd:'/BOT/default'}]}]};
 app.emit('running');await new Promise(r=>setTimeout(r,25));
 const image=app.root.querySelector('img[data-art]');assert.ok(image);
 image.dispatchEvent('click',{target:image});await tick();assert.equal(app.ns.dev.H.tabs.length,1);
});


test('Bot 尖括号本地截图链接可点击打开，含空格文件名按原路径交给预览', async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 const path='/BOT/default/documents/窄屏 #1?.png';
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,header:{cwd:'/BOT/default'},build:()=>[
 {k:'text',key:'screenshot-link',text:'400×720 复测：[窄屏截图](<'+path+'>).\n\n[相对截图](<documents/other image.png>)'}]};
 app.emit('running');await new Promise(r=>setTimeout(r,25));
 const links=app.root.querySelectorAll('.mdw a[data-href]');assert.equal(links.length,2);
 assert.equal(links[0].textContent,'窄屏截图');assert.equal(links[0].dataset.href,path);
 links[0].dispatchEvent('click',{target:links[0]});await tick();
 assert.equal(app.ns.dev.H.tabs.length,1);assert.equal(app.ns.dev.H.tabs[0].url,path);
 links[1].dispatchEvent('click',{target:links[1]});await tick();
 assert.equal(app.ns.dev.H.tabs[1].url,'/BOT/default/documents/other image.png');
});

test('消息发送到首段输出前持续显示轻量状态，输出后交回原有过程',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.follows.at(-1).cb({type:'snapshot',header:{cwd:'/BOT/default'},records:[]});
 let accept;app.api.prompt=()=>new Promise(resolve=>{accept=()=>resolve({accepted:true});});
 const ta=app.root.querySelector('.input textarea');ta.value='等待首段反馈';ta.dispatchEvent('input',{target:ta});
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.match(app.root.querySelector('.send-status').textContent,/正在发送/);
 accept();await tick();assert.match(app.root.querySelector('.send-status').textContent,/正在等待处理/);
 const rpcId=app.ns.dev.H.pending[0].rpcId;
 app.dshState.running.add('sid-1');app.emit('running');
 const follow=app.follows.at(-1).cb;
 follow({type:'event',event:{type:'turn/start',seq:1,time:1,data:{}}});
 follow({type:'event',event:{type:'user/message',seq:2,time:2,data:{source:{kind:'user',rpcId},content:[{type:'text',text:'等待首段反馈'}]}}});
 follow({type:'assistant-stream',frame:{type:'start',attemptId:'first-token'}});
 follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'first-token',chunk:{type:'block-start',index:0,blockType:'text'}}});await tick();
 assert.equal(app.root.querySelector('.send-status'),null,'不能与运行状态重复');
 assert.equal(app.root.querySelectorAll('.process-fold').filter(e=>e.querySelector('summary .spin')).length,1,'流已启动但没有token仍要显示一条可见运行摘要');
 assert.equal(app.root.querySelector('.process-fold').hasAttribute('open'),false);
 follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'first-token',chunk:{type:'text-delta',index:0,text:'先核对资料。'}}});await tick();
 assert.match(app.root.querySelector('.mdw').textContent,/先核对资料/);
 assert.equal(app.root.querySelectorAll('.msgs .spin').length,0,'首段输出替换等待占位');
});

test('发送失败移除等待条并保留可重试错误',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 app.api.prompt=async()=>{throw Error('测试连接失败')};
 const ta=app.root.querySelector('.input textarea');ta.value='失败测试';ta.dispatchEvent('input',{target:ta});
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.equal(app.root.querySelector('.send-status'),null);
 assert.match(app.root.querySelector('.pending-state').textContent,/发送失败.*测试连接失败/);
 assert.ok(app.root.querySelector('[data-resend]'));
});

test('新Bot创建内部会话期间立即反馈，切换Bot不串等待状态',async()=>{
 const app=await boot({registry:twoBots()});app.dshState.workspaces=[{workspaceId:'w',path:'/BOT/writer'}];
 let finish;app.api.createSession=req=>new Promise(resolve=>{finish=()=>resolve({sessionId:req.sessionId});});
 app.ns.dev.selectBot('writer');const ta=app.root.querySelector('.input textarea');ta.value='新聊天';ta.dispatchEvent('input',{target:ta});
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.match(app.root.querySelector('.send-status').textContent,/正在发送/);
 app.ns.dev.selectBot('default');assert.equal(app.root.querySelector('.send-status'),null);
 finish();await tick();assert.equal(app.root.querySelector('.send-status'),null);
});

test('历史快照尚未返回也立即显示发送反馈',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.api.prompt=async()=>({accepted:true});
 const ta=app.root.querySelector('.input textarea');ta.value='不要等历史加载';ta.dispatchEvent('input',{target:ta});
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.match(app.root.querySelector('.send-status').textContent,/正在等待处理/);
 assert.match(app.root.querySelector('.ubub.pending').textContent,/不要等历史加载/);
});

test('running 先于历史快照时也显示运行状态和首段输出',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.dshState.running.add('sid-1');app.emit('running');await tick();
 assert.ok(app.root.querySelector('.msgs .spin'));
 const follow=app.follows.at(-1).cb;
 follow({type:'assistant-stream',frame:{type:'start',attemptId:'early'}});
 follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'early',chunk:{type:'text-delta',index:0,text:'历史尚在读取，先开始核对。'}}});await tick();
 assert.match(app.root.querySelector('.mdw').textContent,/先开始核对/);
});

test('回显早于发送确认且确认失败时保留同一请求重试，不重复用户消息',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 let fail;const ids=[];app.api.prompt=(_sid,_content,_mode,id)=>{ids.push(id);return new Promise((_,reject)=>{fail=()=>reject(Error('确认连接中断'))})};
 const ta=app.root.querySelector('.input textarea');ta.value='已回显消息';ta.dispatchEvent('input',{target:ta});sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 const rpcId=app.ns.dev.H.pending[0].rpcId;
 app.follows.at(-1).cb({type:'event',event:{type:'user/message',seq:1,time:1,data:{source:{kind:'user',rpcId},content:[{type:'text',text:'已回显消息'}]}}});fail();await tick();
 assert.equal(app.root.querySelectorAll('.ubub').length,1);assert.match(app.root.querySelector('.pending-state').textContent,/发送确认失败/);
 app.api.prompt=async(_s,_c,_m,id)=>{ids.push(id);return {accepted:true}};
 const retry=app.root.querySelector('[data-resend]');retry.dispatchEvent('click',{target:retry});await tick();
 assert.deepEqual(ids,[rpcId,rpcId]);assert.equal(app.root.querySelector('.pending-state'),null);assert.equal(app.ns.dev.H.pending.length,0);
});

test('新会话创建期间切走再切回发起Bot会接回完成的会话',async()=>{
 const app=await boot({registry:twoBots()});app.dshState.workspaces=[{workspaceId:'w',path:'/BOT/writer'}];let finish;
 app.api.createSession=req=>new Promise(resolve=>{finish=()=>resolve({sessionId:req.sessionId})});
 app.ns.dev.selectBot('writer');const ta=app.root.querySelector('.input textarea');ta.value='回到写作';ta.dispatchEvent('input',{target:ta});sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 app.ns.dev.selectBot('default');app.ns.dev.selectBot('writer');finish();await tick();
 assert.equal(app.ns.dev.H.sel,app.calls.bots.find(x=>x.op==='bind').sessionId);
});

test('一个Bot创建内部会话不会禁用其他Bot的发送',async()=>{
 const app=await boot({registry:twoBots()});app.dshState.workspaces=[{workspaceId:'w',path:'/BOT/writer'}];let finish;
 app.api.createSession=req=>new Promise(resolve=>{finish=()=>resolve({sessionId:req.sessionId})});
 app.ns.dev.selectBot('writer');const ta=app.root.querySelector('.input textarea');ta.value='写作';ta.dispatchEvent('input',{target:ta});sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 app.ns.dev.selectBot('default');ta.value='另一个Bot';ta.dispatchEvent('input',{target:ta});assert.equal(sendBtn(app.root).disabled,false);finish();await tick();
});

test('真正turn结束后即使running元数据迟到也不重现运行状态',async()=>{
 const app=await boot({running:true,storage:{'pd.h.sel':'"sid-1"'}});
 app.follows.at(-1).cb({type:'snapshot',header:{},records:[
 {type:'event',event:{type:'turn/start',seq:1,time:1,data:{}}},
 {type:'event',event:{type:'assistant/message',seq:2,time:2,data:{message:{content:[{type:'text',text:'已完成。'}]}}}},
 {type:'event',event:{type:'turn/end',seq:3,time:3,data:{reason:{kind:'completed'}}}},
 ]});await tick();assert.equal(app.root.querySelector('.msgs .spin'),null);assert.match(app.root.querySelector('.mdw').textContent,/已完成/);
});

test('上一轮已结束，新流早于回显和turn事件时仍保留一条等待反馈',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});const follow=app.follows.at(-1).cb;
 follow({type:'snapshot',header:{},records:[{type:'event',event:{type:'turn/end',seq:1,time:1,data:{reason:{kind:'completed'}}}}]});
 app.api.prompt=async()=>({accepted:true});const ta=app.root.querySelector('.input textarea');ta.value='开始下一轮';ta.dispatchEvent('input',{target:ta});sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 app.dshState.running.add('sid-1');app.emit('running');await tick();assert.ok(app.root.querySelector('.send-status'),'没有实际运行条时不能只凭running隐藏发送反馈');
 follow({type:'assistant-stream',frame:{type:'start',attemptId:'next'}});follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'next',chunk:{type:'block-start',index:0,blockType:'text'}}});await tick();
 assert.ok(app.root.querySelector('.msgs .tool .spin'));assert.equal(app.root.querySelector('.send-status'),null);
});

test('首段输出早于running元数据时立即替换发送等待条',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});const follow=app.follows.at(-1).cb;follow({type:'snapshot',header:{},records:[]});
 app.api.prompt=async()=>({accepted:true});const ta=app.root.querySelector('.input textarea');ta.value='先有输出';ta.dispatchEvent('input',{target:ta});sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 follow({type:'assistant-stream',frame:{type:'start',attemptId:'early-output'}});follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'early-output',chunk:{type:'text-delta',index:0,text:'已经开始核对。'}}});await tick();
 assert.match(app.root.querySelector('.mdw').textContent,/已经开始核对/);assert.equal(app.root.querySelector('.send-status'),null);
});
