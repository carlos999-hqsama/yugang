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
  const calls = { cancel: 0, cancelArgs: [], api: [], approvals: [], bots: [], browser: [], copy: [], voice: 0, content: [], uploads: [], removed: [] };
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
    attachment: async () => { throw Error('测试未提供会话附件'); },
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
    openContent: async args => {calls.content.push(args);return {opened:true,closed:true};},
    chooseFiles: async () => [],
    uploadAttachment: async args => {calls.uploads.push(args);return {receiptId:'receipt-1',file:{attachmentId:'sha256:'+'a'.repeat(64),name:'report.pdf',bytes:4}};},
    removeAttachment: async handle => {calls.removed.push(handle);return {removed:true};},
    cancelAttachment: async () => ({cancelled:true}),
    dshWeb: async () => true,
    voice: async () => { calls.voice += 1; return { text: '' }; },
    fileUrl: (p) => p,
    fileFromUrl: url => url?.startsWith('/BOT/') ? url : null,
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
    if (typeof key === 'string' && this.closest?.('.msgs')) {
      const box = this.closest('.msgs');
      const index = box.querySelectorAll('[data-scroll-key]').indexOf(this);
      const top = index * 100 - (box?.scrollTop || 0);
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


test('打开统一内容页保留阅读位置、草稿、选区且不重建消息',async()=>{
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
   assert.equal(app.calls.content.length,2,'每次显式打开交给统一宿主');
   assert.equal(app.root.querySelector('.br'),null,'主页面不再创建旧预览侧栏');
   assert.equal(reopened.scrollTop,180,'宽度变化后应按原锚点恢复');
   const follow=app.follows.at(-1).cb;
   follow({type:'assistant-stream',frame:{type:'start',attemptId:'after-upscroll'}});
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-upscroll',chunk:{type:'block-start',index:0,blockType:'text'}}});await tick();
   follow({type:'assistant-stream',frame:{type:'chunk',attemptId:'after-upscroll',chunk:{type:'text-delta',index:0,text:'上滑后的新输出'}}});await tick();
   assert.equal(reopened.scrollTop,180,'用户已上滑时新输出不应强制跟随到底部');
 }finally{restoreLayout();}
});

test('打开统一内容页后底部阅读继续跟随新输出',async()=>{
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
 ta.value='默认草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.H.attach=[{name:'default.png',mediaType:'image/png',data:'ZGVmYXVsdA=='}];
 app.ns.dev.selectBot('writer');assert.equal(ta.value,'');assert.equal(app.ns.dev.H.attach.length,0);
 ta.value='写作草稿';ta.dispatchEvent('input',{target:ta});app.ns.dev.selectBot('default');
 assert.equal(ta.value,'默认草稿');assert.equal(app.ns.dev.H.attach[0].name,'default.png');
 assert.equal(app.root.querySelector('.input .atts img').getAttribute('src'),'data:image/png;base64,ZGVmYXVsdA==');
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


test('窄屏打开产物使用统一宿主并保留草稿和消息容器',async()=>{
 const app=await boot({storage:{'pd.h.inspector':'true','pd.h.sel':'"sid-1"'}});
 app.doc.body.classList.add('cover');app.ns.onLayout();
 const ta=app.root.querySelector('.input textarea'),msgs=app.root.querySelector('.msgs');
 ta.value='未发送草稿';ta.focus();ta.setSelectionRange(1,3);
 app.ns.dev.openArtifact('/BOT/default/documents/check.txt');await tick();
 assert.equal(app.ns.dev.H.inspector,false);
 assert.equal(app.root.classList.contains('bot-detail-open'),false);
 assert.equal(app.root.querySelector('.input textarea'),ta);assert.equal(ta.value,'未发送草稿');
 assert.equal(ta.selectionStart,1);assert.equal(ta.selectionEnd,3);assert.equal(app.root.querySelector('.msgs'),msgs);
 assert.deepEqual(app.calls.content.map(x=>x.path),['/BOT/default/documents/check.txt']);
 assert.equal(app.calls.content[0].sessionId,'sid-1');assert.equal(app.calls.content[0].botId,'default');
 assert.equal(app.root.querySelector('.br'),null);
});

test('布局同步不自动打开旧浏览器或重新授予文件权限',async()=>{
 const app=await boot({storage:{'pd.h.tabs':'[{"id":"old","url":"https://old.invalid"}]'}});
 app.ns.syncOverlays();app.ns.onLayout();await tick();
 assert.equal(app.calls.content.length,0);assert.equal(app.calls.browser.length,0);
 app.ns.dev.openUrl('https://example.test/article');await tick();
 assert.equal(app.calls.content.length,1);assert.equal(app.calls.content[0].url,'https://example.test/article');
 app.ns.syncOverlays();assert.equal(app.calls.content.length,1);
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
 assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),'/BOT/default/documents/page top.png');
 assert.equal(app.calls.content.length,0);
});

test('插件自查截图在执行过程中可点击预览',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,build:()=>[
 {k:'tools',key:'preview',cat:'image',tools:[{id:'preview-1',name:'phone_plugins_preview',args:{op:'snapshot'},result:{text:'截图文件：/BOT/default/documents/plugin-preview-123.png'},cwd:'/BOT/default'}]}]};
 app.emit('running');await new Promise(r=>setTimeout(r,25));
 const image=app.root.querySelector('img[data-art]');assert.ok(image);
 image.dispatchEvent('click',{target:image});await tick();assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),'/BOT/default/documents/plugin-preview-123.png');
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
 assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),path);
 links[1].dispatchEvent('click',{target:links[1]});await tick();
 assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),'/BOT/default/documents/other image.png');
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

test('用户图片待发送回显重试和历史加载都保留缩略图且不重复',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 const blocks=[
   {type:'image',mediaType:'image/png',data:'aW1hZ2Ux',name:'first <png>.png'},
   {type:'image',mediaType:'image/webp',data:'aW1hZ2Uy',name:'second.webp'}
 ];
 const prompts=[];let failFirst;app.api.prompt=(_sid,content,_mode,id)=>{prompts.push({content,id});return new Promise((resolve,reject)=>{failFirst=()=>reject(Error('确认连接中断'))});};
 const ta=app.root.querySelector('.input textarea');ta.value='看这两张图';ta.dispatchEvent('input',{target:ta});
 app.ns.dev.H.attach=blocks.map(block=>({...block}));sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.equal(app.root.querySelectorAll('.ubub.pending .user-images .chat-image').length,2);
 assert.equal(JSON.stringify(prompts[0].content),JSON.stringify([{type:'text',text:'看这两张图'},...blocks]));
 const rpcId=app.ns.dev.H.pending[0].rpcId;
 app.follows.at(-1).cb({type:'event',event:{type:'user/message',seq:1,time:1,data:{source:{kind:'user',rpcId},content:prompts[0].content}}});
 failFirst();await tick();
 assert.equal(app.root.querySelectorAll('.ubub .user-images .chat-image').length,2,'回显后不能叠加待发送缩略图');
 app.api.prompt=async(_sid,content,_mode,id)=>{prompts.push({content,id});return {accepted:true}};
 app.root.querySelector('[data-resend]').dispatchEvent('click',{target:app.root.querySelector('[data-resend]')});await tick();
 assert.equal(prompts.length,2);assert.equal(prompts[0].id,prompts[1].id);assert.equal(app.root.querySelectorAll('.ubub .user-images .chat-image').length,2);
 const inline=app.root.querySelector('.chat-image[data-image]');inline.dispatchEvent('click',{target:inline});await tick();
 assert.ok(app.doc.getElementById('sheet').classList.contains('on'));
 assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),'data:image/png;base64,aW1hZ2Ux');
 assert.equal(app.calls.content.length,0);
});

test('durable历史图片按会话附件异步显示并缓存，恶意文件名只作转义文本',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});const follow=app.follows.at(-1).cb;
 const ref=(tail,name)=>({attachmentId:'sha256:'+tail.repeat(64),mediaType:'image/png',width:2,height:2,bytes:3,name});
 const first=ref('a','first.png'),second=ref('b','<img src=x onerror=alert(1)>.png');
 const calls=[],waiting=[];
 app.api.attachment=(sid,id)=>{calls.push({sid,id});return new Promise(resolve=>waiting.push(()=>resolve({attachment:{attachmentId:id,mediaType:'image/png'},data:id.startsWith('sha256:b')?'ZGF0YQ==':'YWJj'})));};
 follow({type:'snapshot',header:{},records:[{type:'event',event:{seq:1,type:'user/message',data:{source:{kind:'user'},content:[
   {type:'text',text:'看这两张图'},{type:'image',attachment:first},{type:'image',attachment:second},
   {type:'file',attachment:{name:'<img src=x onerror=alert(1)>.txt'}}
 ]}}}]});
 assert.equal(app.root.querySelectorAll('.chat-image[data-status="loading"]').length,2);
 for(const release of waiting)release();await tick();
 assert.equal(app.root.querySelectorAll('.chat-image[data-status="loaded"] img').length,2);
 assert.equal(app.root.querySelectorAll('img').length,2,'恶意文件名不能注入新的 img 节点');
 follow({type:'snapshot',header:{},records:[{type:'event',event:{seq:2,type:'user/message',data:{source:{kind:'user'},content:[{type:'image',attachment:first}]}}}]});
 await tick();assert.equal(calls.length,2);assert.equal(app.root.querySelectorAll('.chat-image img').length,1);
 const image=app.root.querySelector('.chat-image[data-status="loaded"]');image.dispatchEvent('click',{target:image});await tick();
 assert.ok(app.doc.getElementById('sheet').classList.contains('on'));assert.equal(app.doc.querySelector('#sheet .chat-image-view').getAttribute('src'),'data:image/png;base64,YWJj');
 const img=image.querySelector('img');img.hidden=false;img.dispatchEvent('error',{target:img});
 assert.equal(image.dataset.status,'error');assert.equal(img.hidden,true);
});

test('durable图片读取失败可点击重试且错误消息不注入HTML',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});const attachment={attachmentId:'sha256:'+'c'.repeat(64),mediaType:'image/webp',name:'broken.webp'};
 let fail=true;const calls=[];
 app.api.attachment=async()=>{calls.push(1);if(fail)throw Error('<b>附件服务失败</b>');return {attachment:{...attachment},data:'YWJj'};};
 app.follows.at(-1).cb({type:'snapshot',header:{},records:[{type:'event',event:{seq:1,type:'user/message',data:{source:{kind:'user'},content:[{type:'image',attachment}]}}}]});
 await tick();
 let button=app.root.querySelector('.chat-image[data-attachment-id]');
 assert.equal(button.dataset.status,'error');assert.equal(button.querySelector('img'),null);
 assert.match(button.innerHTML,/&lt;b&gt;附件服务失败/);assert.doesNotMatch(button.innerHTML,/<b>/);
 fail=false;button.dispatchEvent('click',{target:button});await tick();
 assert.equal(calls.length,2);button=app.root.querySelector('.chat-image[data-attachment-id]');
 assert.equal(button.dataset.status,'loaded');
 assert.equal(app.root.querySelector('.chat-image img').getAttribute('src'),'data:image/webp;base64,YWJj');
});

test('切换会话清理附件缓存，旧请求完成不会串到当前会话',async()=>{
 const registry={defaultBotId:'default',bots:[{id:'default',name:'Bot',pinned:true,sessionIds:['sid-a','sid-b'],selectedSessionId:'sid-a'}]};
 const app=await boot({storage:{'pd.h.sel':'"sid-a"'},registry});
 for(const sid of ['sid-a','sid-b'])app.dshState.sessions.set(sid,{sessionId:sid,projections:{values:{title:sid}},updatedAt:1});
 const id='sha256:'+'d'.repeat(64),waiting=[];
 app.api.attachment=sid=>new Promise(resolve=>waiting.push({sid,resolve}));
 app.follows.at(-1).cb({type:'snapshot',header:{},records:[{type:'event',event:{seq:1,type:'user/message',data:{source:{kind:'user'},content:[{type:'image',attachment:{attachmentId:id,mediaType:'image/png'}}]}}}]});
 assert.equal(app.follows.at(-1).sid,'sid-a');
 app.ns.dev.select('sid-b');assert.equal(app.follows.at(-1).sid,'sid-b');
 app.follows.at(-1).cb({type:'snapshot',header:{},records:[{type:'event',event:{seq:1,type:'user/message',data:{source:{kind:'user'},content:[{type:'image',attachment:{attachmentId:id,mediaType:'image/png'}}]}}}]});
 waiting.find(x=>x.sid==='sid-a').resolve({attachment:{attachmentId:id,mediaType:'image/png'},data:'b2xk'});
 await tick();assert.equal(app.root.querySelector('.chat-image').dataset.status,'loading','旧会话结果不能绘制到当前会话');
 waiting.find(x=>x.sid==='sid-b').resolve({attachment:{attachmentId:id,mediaType:'image/png'},data:'bmV3'});
 await tick();
 assert.equal(app.root.querySelector('.chat-image img').getAttribute('src'),'data:image/png;base64,bmV3');
});

test('pending inline图片回显为durable附件后不重复且立即读取',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 let accept;const prompts=[];app.api.prompt=(_sid,content,_mode,id)=>{prompts.push({content,id});return new Promise(resolve=>{accept=()=>resolve({accepted:true})});};
 const attachmentCalls=[];app.api.attachment=async(_sid,id)=>{attachmentCalls.push(id);return {attachment:{attachmentId:id,mediaType:'image/jpeg'},data:'ZWNobw=='};};
 const ta=app.root.querySelector('.input textarea');app.ns.dev.H.attach=[{type:'image',mediaType:'image/png',data:'aW1hZ2U=',name:'pending.png'}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.equal(app.root.querySelectorAll('.ubub.pending .chat-image img').length,1);
 const rpcId=app.ns.dev.H.pending[0].rpcId,id='sha256:'+'e'.repeat(64);
 app.follows.at(-1).cb({type:'event',event:{type:'user/message',seq:1,time:1,data:{source:{kind:'user',rpcId},content:[{type:'image',attachment:{attachmentId:id,mediaType:'image/jpeg',name:'echo.jpg'}}]}}});
 await tick();accept();await tick();
 assert.equal(app.root.querySelectorAll('.ubub .chat-image').length,1);assert.equal(JSON.stringify(attachmentCalls),JSON.stringify([id]));
 assert.equal(app.root.querySelector('.chat-image img').getAttribute('src'),'data:image/jpeg;base64,ZWNobw==');
 assert.equal(app.ns.dev.H.pending.length,0);
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


test('六张草稿图片都有独立预览和移除按钮，点击预览不删除',async()=>{
 const app=await boot();app.ns.dev.H.attach=Array.from({length:6},(_,i)=>({mediaType:'image/png',data:'YWJj',name:`图${i}.png`}));
 const ta=app.root.querySelector('.input textarea');ta.dispatchEvent('input',{target:ta});
 const previews=app.root.querySelectorAll('[data-draft-preview]');assert.equal(previews.length,6);
 assert.equal(app.root.querySelectorAll('button[data-rm]').length,6);assert.equal(app.root.querySelector('img[data-rm]'),null);
 previews[5].dispatchEvent('click',{target:previews[5]});await tick();
 assert.equal(app.ns.dev.H.attach.length,6);assert.ok(app.doc.querySelector('#sheet .chat-image-view'));
 const remove=app.root.querySelector('button[data-rm="5"]');remove.dispatchEvent('click',{target:remove});
 assert.equal(app.ns.dev.H.attach.length,5);assert.equal(app.root.querySelectorAll('[data-draft-preview]').length,5);
});

test('图片发送回显后保持原缩略图，直到所属会话的规范化图片读取完成',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 let release;app.api.attachment=()=>new Promise(resolve=>{release=resolve;});
 app.ns.dev.H.attach=[{mediaType:'image/png',data:'b3JpZ2luYWw=',name:'原图.png'}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 const rpcId=app.ns.dev.H.pending[0].rpcId,id='sha256:'+'f'.repeat(64);
 app.follows.at(-1).cb({type:'event',event:{type:'user/message',seq:1,data:{source:{kind:'user',rpcId},content:[{type:'image',attachment:{attachmentId:id,mediaType:'image/jpeg',name:'规范化.jpg'}}]}}});await tick();
 assert.equal(app.root.querySelectorAll('.chat-image').length,1);
 assert.equal(app.root.querySelector('.chat-image img').getAttribute('src'),'data:image/png;base64,b3JpZ2luYWw=');
 assert.equal(app.root.querySelector('.chat-image[data-status="loading"]'),null);
 release({attachment:{attachmentId:id,mediaType:'image/jpeg'},data:'bm9ybWFsaXplZA=='});await tick();
 assert.equal(app.root.querySelector('.chat-image img').getAttribute('src'),'data:image/jpeg;base64,bm9ybWFsaXplZA==');
});

test('文件先流式上传取得receipt，再发送；消息确认失败重试不重新上传或更换消息身份',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 const calls=[];let rejectFirst;app.api.prompt=(sid,content,mode,id)=>{calls.push({sid,content,id});return new Promise((resolve,reject)=>{rejectFirst=reject;});};
 app.ns.dev.H.attach=[{type:'file',handle:'staged-1',name:'report.pdf',mediaType:'application/pdf',bytes:4}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.equal(app.calls.uploads.length,1);assert.equal(app.calls.uploads[0].sessionId,'sid-1');
 assert.equal(JSON.stringify(calls[0].content),JSON.stringify([{type:'file',receiptId:'receipt-1'}]));
 assert.ok(app.root.querySelector('[data-file-open]'));rejectFirst(Error('发送未确认'));await tick();
 app.api.prompt=async(sid,content,mode,id)=>{calls.push({sid,content,id});return {accepted:true};};
 const retry=app.root.querySelector('[data-resend]');retry.dispatchEvent('click',{target:retry});await tick();
 assert.equal(app.calls.uploads.length,1);assert.equal(calls[0].id,calls[1].id);assert.deepEqual(app.calls.removed,['staged-1']);
});

test('用户和Bot公网链接有真实href且走统一入口，危险协议和代码仍不执行',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,header:{cwd:'/BOT/default'},build:()=>[
 {k:'user',text:'请看 https://example.test/?a=1&b=2。 **不要变粗**',content:[],rpcId:'user-1'},
 {k:'text',text:'HTTPS://example.test/path\n\n[危险](javascript:alert)\n\n`https://code.invalid/`'}]};
 app.emit('running');await tick();
 const user=app.root.querySelector('.ubub a[data-href]');assert.equal(user.getAttribute('href'),'https://example.test/?a=1&b=2');
 assert.equal(app.root.querySelector('.ubub b'),null);user.dispatchEvent('click',{target:user});await tick();
 assert.equal(app.calls.content.at(-1).url,'https://example.test/?a=1&b=2');
 assert.equal(app.root.querySelectorAll('.mdw a[data-href]').length,1);
 const bot=app.root.querySelector('.mdw a[data-href]');assert.equal(bot.getAttribute('href'),'HTTPS://example.test/path');
 bot.dispatchEvent('click',{target:bot});await tick();assert.equal(app.calls.content.at(-1).url,'HTTPS://example.test/path');
});

test('取消附件上传后即使上传稍后成功也不发送消息，仍可主动重试',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 let complete;const cancelled=[];let prompts=0;
 app.native.uploadAttachment=()=>new Promise(resolve=>{complete=resolve;});
 app.native.cancelAttachment=async id=>{cancelled.push(id);return {cancelled:true};};
 app.api.prompt=async()=>{prompts++;return {accepted:true};};
 app.ns.dev.H.attach=[{type:'file',handle:'cancel-me',name:'video.mp4',kind:'video',bytes:32}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 const stop=app.root.querySelector('[data-cancel-upload]');assert.ok(stop);stop.dispatchEvent('click',{target:stop});
 complete({receiptId:'late',file:{attachmentId:'sha256:'+'a'.repeat(64),name:'video.mp4',bytes:32}});await tick();
 assert.equal(prompts,0);assert.equal(cancelled.length,1);assert.ok(app.root.querySelector('[data-resend]'));
});

test('运行时遗失暂存凭据时清除缓存，用户重试会重新上传而不自动重复发送',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 const forgotten=[];app.native.forgetAttachmentReceipt=async args=>{forgotten.push(args);return true;};
 let prompts=0;app.api.prompt=async()=>{prompts++;throw Object.assign(Error('lost'),{code:'session/attachment-invalid',details:{reason:'FILE_NOT_STAGED'}});};
 app.ns.dev.H.attach=[{type:'file',handle:'recover-me',name:'report.pdf',bytes:4}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 assert.equal(prompts,1);assert.equal(forgotten[0].handle,'recover-me');assert.equal(forgotten[0].sessionId,'sid-1');
 app.api.prompt=async()=>{prompts++;return {accepted:true};};
 const retry=app.root.querySelector('[data-resend]');retry.dispatchEvent('click',{target:retry});await tick();
 assert.equal(app.calls.uploads.length,2);assert.equal(prompts,2);assert.deepEqual(app.calls.removed,['recover-me']);
});

test('失败消息可放弃重试并释放附件，不影响输入框的新草稿',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});app.follows.at(-1).cb({type:'snapshot',header:{},records:[]});
 app.api.prompt=async()=>{throw Error('未确认');};app.ns.dev.H.attach=[{type:'file',handle:'discard-file',name:'a.pdf',bytes:4}];
 sendBtn(app.root).dispatchEvent('click',{target:sendBtn(app.root)});await tick();
 const ta=app.root.querySelector('.input textarea');ta.value='新的草稿';ta.dispatchEvent('input',{target:ta});
 const drop=app.root.querySelector('[data-discard]');assert.ok(drop);drop.dispatchEvent('click',{target:drop});await tick();
 assert.equal(app.ns.dev.H.pending.length,0);assert.deepEqual(app.calls.removed,['discard-file']);assert.equal(ta.value,'新的草稿');
});

test('用户与Bot的裸链接保留配对括号，只移除句末多出的右括号',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});const url='https://en.wikipedia.org/wiki/Function_(mathematics)';
 app.ns.dev.H.conv={sid:'sid-1',loaded:true,version:1,header:{},build:()=>[
 {k:'user',text:`请看 ${url}。`,content:[],rpcId:'bracket-user'}, {k:'text',text:`参考（${url})。`} ]};
 app.emit('running');await tick();
 for(const a of app.root.querySelectorAll('a[data-href]'))assert.equal(a.getAttribute('href'),url);
 assert.equal(app.root.querySelectorAll('a[data-href]').length,2);
});


test('加载更早记录（前插）后保持原阅读锚点：同一消息 key 不变且像素补偿',async()=>{
 const app=await boot({storage:{'pd.h.sel':'"sid-1"'}});
 const records=Array.from({length:6},(_,i)=>({type:'event',event:{seq:i+1,type:'assistant/message',data:{message:{content:[{type:'text',text:`第${i+1}段`}]}}}}));
 app.follows.at(-1).cb({type:'snapshot',header:{},hasMore:true,cursor:6,records});
 await tick();
 const restoreLayout=installChatLayout();
 try{
   const msgs=app.root.querySelector('.msgs');
   const keyOf=text=>[...msgs.querySelectorAll('[data-scroll-key]')].find(n=>n.textContent.includes(text))?.dataset.scrollKey;
   const before3=keyOf('第3段'),before5=keyOf('第5段');
   assert.ok(before3&&before5,'应渲染出带滚动锚点的消息');
   msgs.scrollTop=250;msgs.dispatchEvent('scroll',{target:msgs}); // 锚定第4段（index 3，offset 50）
   app.api.page=async(sid,cursor,oldest)=>({hasMore:true,records:Array.from({length:5},(_,i)=>({type:'event',event:{seq:i-4,type:'assistant/message',data:{message:{content:[{type:'text',text:`上页${i+1}`}]}}}}))});
   const btn=msgs.querySelector('[data-act="older"]');
   assert.ok(btn,'有更早历史时应渲染加载入口');
   btn.dispatchEvent('click',{target:btn});
   await tick();
   assert.equal(msgs.querySelectorAll('[data-scroll-key]').length,12,'前插 5 条后应有 12 个锚点（含加载按钮）');
   assert.equal(msgs.scrollTop,750,'前插后应按高度差补偿，保持原阅读消息位置');
   assert.equal(keyOf('第3段'),before3,'同一逻辑消息的锚点 key 不能因前插而改变');
   assert.equal(keyOf('第5段'),before5);
   // 顶部场景：用户拉到最顶端触发加载，也不能跳到新内容的顶部
   msgs.scrollTop=0;msgs.dispatchEvent('scroll',{target:msgs});
   app.api.page=async()=>({hasMore:false,records:[]}); // 无更多：不再前插
   const top=msgs.scrollTop;
   app.emit('sessions'); // 触发一次并发刷新渲染
   await tick();
   assert.equal(msgs.scrollTop,top,'无前插的并发渲染不能移动阅读位置');
 }finally{restoreLayout();}
});
