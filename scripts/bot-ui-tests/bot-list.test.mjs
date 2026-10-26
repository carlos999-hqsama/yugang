import test from 'node:test';
import assert from 'node:assert/strict';
import {loadModule,synthetic} from '../interaction-tests/loader.mjs';
import {createDocument} from '../interaction-tests/dom.mjs';
const {ns}=await loadModule('bot-list.js');
const bot=(id,name,pinned=false,sessionIds=[],avatar='circle')=>({id,name,pinned,sessionIds,avatar});
test('置顶只呈现固定机器人一次，旧会话不成为机器人行',()=>{
 const registry={defaultBotId:'a',bots:[bot('a','默认',true),bot('b','写作',true,['session-x']),bot('c','开发')]};
 const sessions=new Map(['old-1','old-2','session-x'].map(sessionId=>[sessionId,{sessionId}]));
 const html=ns.botListHtml({registry,sessions,selected:'a'}),doc=createDocument();doc.body.innerHTML=html;
 assert.equal(doc.querySelectorAll('.bot-pin').length,2);assert.equal(doc.querySelectorAll('.bot-robot').length,1);
 assert.equal(doc.querySelectorAll('[data-bot]').length,3);assert.equal(doc.querySelectorAll('.bot-conversation').length,0);
 assert.doesNotMatch(html,/old-1|old-2|session-x|data-sid|data-preview-sid/);
});
test('机器人名称安全转义，不再读取聊天预览填充Bot列表',()=>{
 const html=ns.botListHtml({registry:{defaultBotId:'a',bots:[bot('a','<img onerror=evil>')]}});
 assert.ok(html.includes('&lt;img'));assert.doesNotMatch(html,/<img|<script|bot-preview/);
});
test('Bot列表呈现用户选择的单色头像形状而不是颜色色调',()=>{
 const registry={defaultBotId:'default',bots:[
  bot('default','默认',true,[],'circle'),
  bot('11111111-1111-4111-8111-111111111111','方形',false,[],'rounded-square'),
  bot('22222222-2222-4222-8222-222222222222','六边',false,[],'hexagon'),
  bot('33333333-3333-4333-8333-333333333333','旧数据',false,[],'unknown')
 ]};
 const html=ns.botListHtml({registry,sessions:new Map(),selected:'default'});
 assert.equal(ns.normalizeBotAvatar('unknown'),'circle');
 assert.ok(html.includes('shape-circle')&&html.includes('shape-rounded-square')&&html.includes('shape-hexagon'));
 assert.doesNotMatch(html,/tone-[012]|#[0-9a-f]{3,6}/i);
});
test('未绑定旧会话属于默认Bot，已绑定会话只属于自己的Bot',()=>{
 const registry={defaultBotId:'a',bots:[bot('a','默认'),bot('b','写作',false,['new'])]};
 const sessions=new Map(['old','new'].map(sessionId=>[sessionId,{sessionId}]));
 assert.equal(ns.ownerOfSession(registry,'new'),'b');assert.equal(ns.ownerOfSession(registry,'old'),'a');
 assert.deepEqual(Array.from(ns.sessionsForBot(registry,'a',sessions),s=>s.sessionId),['old']);
 assert.deepEqual(Array.from(ns.sessionsForBot(registry,'b',sessions),s=>s.sessionId),['new']);
});
const {ns:detail}=await loadModule('bot-inspector.js');
test('completed turn and failed tool never fabricate independent acceptance',()=>{
 const conv={loaded:true,events:new Map([[1,{type:'turn/end',data:{reason:{kind:'completed'}}}]]),build:()=>[{k:'process',items:[{k:'tools',tools:[{name:'read',args:{description:'读取文件'},result:{isError:true}}]}]}]};
 const state=detail.inspectorSnapshot(conv,false);assert.equal(state.status,'本轮已结束');assert.equal(state.steps[0].state,'failed');assert.equal('acceptance' in state,false);
 const html=detail.inspectorHtml(state,'bot');assert.doesNotMatch(html,/验收|历史会话|data-history-session/);
});

test('backfilled older events do not replace the current turn status',()=>{
 const conv={loaded:true,events:new Map([[10,{seq:10,type:'turn/end',data:{reason:{kind:'completed'}}}],[2,{seq:2,type:'turn/end',data:{reason:{kind:'cancelled'}}}]]),build:()=>[]};
 assert.equal(detail.inspectorSnapshot(conv,false).status,'本轮已结束');
});

const {ns:convModule}=await loadModule('conv.js');
test('current task does not inherit prior-turn todos or artifacts',()=>{
 const conv=new convModule.Conv('a');conv.loaded=true;conv.header={cwd:'/workspace'};
 for(const event of [
  {seq:1,type:'turn/start',data:{}},
  {seq:2,type:'assistant/message',data:{message:{content:[{type:'tool-call',id:'old',name:'todo_write',arguments:JSON.stringify({todos:[{content:'旧任务',status:'completed'}]})} ]}}},
  {seq:3,type:'turn/end',data:{reason:{kind:'completed'}}},
  {seq:4,type:'turn/start',data:{}},
  {seq:5,type:'assistant/message',data:{message:{content:[{type:'tool-call',id:'new',name:'read',arguments:JSON.stringify({file_path:'/workspace/new.txt',description:'本轮读取'})}]}}}
 ])conv.add(event);
 const s=detail.inspectorSnapshot(conv,true);
 assert.equal(detail.inspectorSnapshot(conv,false).status,'本轮未结束');
 assert.equal(s.steps.length,1);assert.equal(s.steps[0].text,'本轮读取');assert.equal(s.steps[0].state,'running');assert.equal(s.status,'运行中');
});

const settingsMenus=[];
const {ns:settings}=await loadModule('bot-settings.js',{globals:{document:createDocument()},stubs:{'./ui.js':context=>synthetic(context,{menu:(_anchor,items)=>settingsMenus.push(items),toast:()=>{}})}});
const toolConfig=()=>({instructions:'写作职责',memory:'kept memory',enabledTools:['files','web'],workspace:'/BOT/x',compactionThreshold:.67});
test('Bot设置提供十二种形状和真实可持久化的上下文阈值控制',()=>{
 const html=settings.botSettingsHtml(toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'diamond',pinned:false},{canCompact:true});
 const doc=createDocument();doc.body.innerHTML=html;
 doc.querySelector('[data-bot-name]').value='写作';
 assert.equal(doc.querySelectorAll('[data-bot-avatar]').length,12);
 assert.equal(doc.querySelectorAll('.bot-avatar-choice small').length,0);
 assert.deepEqual([...doc.querySelectorAll('[data-bot-avatar]')].map(n=>n.dataset.botAvatar),['circle','rounded-square','diamond','pentagon','hexagon','squircle','pebble','bean','clover','flower','droplet','capsule']);
 assert.equal(doc.querySelector('[data-bot-name]').value,'写作');
 assert.equal(doc.querySelector('[data-bot-instructions]').value,'写作职责');
 assert.equal(doc.querySelector('[data-bot-compact]').disabled,false);
 const slider=doc.querySelector('[data-bot-compaction]');
 assert.equal(slider.getAttribute('min'),'50');assert.equal(slider.getAttribute('max'),'90');assert.equal(slider.getAttribute('step'),'1');
 assert.equal(slider.getAttribute('value'),'67');assert.equal(doc.querySelector('[data-compaction-output]').textContent,'67%');
 assert.equal(settings.botCompactionPercent(undefined),80);
 assert.ok(html.includes('实际整理可能更早发生'));
 assert.ok(doc.querySelector('.bot-settings-profile [data-bot-avatar-preview]'));
 assert.ok(doc.querySelector('[data-bot-rename]'));
 assert.doesNotMatch(html,/data-history-session|bot-preview|阈值.*<input|<input[^>]+threshold/i);
});
test('受保护身份不能删除，保存仍传回原有记忆且不暴露历史会话',()=>{
 const html=settings.botSettingsHtml(toolConfig(),{id:'default',name:'主 Bot',avatar:'circle',pinned:true});
 const doc=createDocument();doc.body.innerHTML=`<div class="pane"><button data-bot-actions></button>${html}</div>`;
 settings.bindBotSettings(doc.querySelector('.bot-settings'),toolConfig(),{id:'default',name:'主 Bot',pinned:true},{});
 doc.querySelector('[data-bot-actions]').click();
 assert.deepEqual(Array.from(settingsMenus.at(-1),item=>item.label),['取消置顶']);
 assert.equal(doc.querySelector('[data-bot-delete]'),null);
 assert.ok(settings.PROTECTED_BOT_IDS.has('00000000-0000-4000-8000-000000000001'));
 assert.deepEqual(Object.keys(settings.BOT_TOOL_LABELS),['files','shell','web','skills','plugins','memory','app-management','conversation-history']);
 assert.ok(!html.includes('data-bot-memory'));
});
test('设置保存收集名称头像职责和工具，删除需要两次明确点击',async()=>{
 const html=settings.botSettingsHtml(toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'circle',pinned:false},{canCompact:true});
 const doc=createDocument();doc.body.innerHTML=`<div class="pane"><button data-bot-actions></button>${html}</div>`;
 const saved=[];let deleted=0;
 settings.bindBotSettings(doc.querySelector('.bot-settings'),toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'circle',pinned:false},{
   save:async value=>saved.push(value),
   deleteBot:async()=>{deleted++}
 });
 doc.querySelector('[data-bot-name]').value=' 写作 Bot ';
 doc.querySelector('[data-bot-instructions]').value='更新职责';
 doc.querySelector('[data-bot-compaction]').value='75';
 doc.querySelector('[data-bot-tool="files"]').checked=true;
 doc.querySelector('[data-bot-tool="web"]').checked=false;
 doc.querySelector('[data-save-bot]').click();
 await new Promise(resolve=>setTimeout(resolve,0));
 assert.equal(JSON.stringify(saved),JSON.stringify([{name:'写作 Bot',avatar:'circle',fish:'goldfish',color:'ink',instructions:'更新职责',enabledTools:['files'],compactionThreshold:.75,memory:'kept memory'}]));

 doc.querySelector('[data-bot-actions]').click();
 await settingsMenus.at(-1).find(item=>item.label==='删除 Bot').act();
 assert.equal(deleted,0);
 assert.equal(settingsMenus.at(-1).find(item=>item.label==='确认删除 Bot')?.danger,true);
 await settingsMenus.at(-1).find(item=>item.label==='确认删除 Bot').act();
 await new Promise(resolve=>setTimeout(resolve,0));
 assert.equal(deleted,1);
});
test('Bot名称点开内联修改，回车结束并保存新名称',async()=>{
 const html=settings.botSettingsHtml(toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'circle',pinned:false});
 const doc=createDocument();doc.body.innerHTML=html;
 const saved=[];settings.bindBotSettings(doc.body,toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'circle',pinned:false},{save:async value=>saved.push(value)});
 const rename=doc.querySelector('[data-bot-rename]'),input=doc.querySelector('[data-bot-name]');
 rename.click();
 assert.equal(rename.hidden,true);assert.equal(input.hidden,false);assert.equal(doc.activeElement,input);
 input.value=' 写作助手 ';
 input.dispatchEvent('keydown',{key:'Enter',target:input,preventDefault(){}});
 input.dispatchEvent('blur',{target:input});
 assert.equal(input.hidden,true);assert.equal(rename.hidden,false);
 assert.equal(doc.querySelector('[data-bot-name-label]').textContent,'写作助手');
 doc.querySelector('[data-save-bot]').click();
 await new Promise(resolve=>setTimeout(resolve,0));
 assert.equal(saved.at(-1).name,'写作助手');
});
test('Bot操作菜单可连续打开，不受上一次点击或双击防护影响',()=>{
 const html=settings.botSettingsHtml(toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',avatar:'circle',pinned:false});
 const doc=createDocument();doc.body.innerHTML=`<div class="pane"><button data-bot-actions></button>${html}</div>`;
 settings.bindBotSettings(doc.querySelector('.bot-settings'),toolConfig(),{id:'11111111-1111-4111-8111-111111111111',name:'写作',pinned:false},{});
 const menusBefore=settingsMenus.length;
 for(let i=0;i<3;i++)doc.querySelector('[data-bot-actions]').click();
 assert.equal(settingsMenus.length-menusBefore,3);
 for(const items of settingsMenus.slice(-3))assert.deepEqual(Array.from(items,item=>item.label),['置顶','删除 Bot']);
});
test('创建表单提供十二种形状并返回所选身份',async()=>{
 const doc=createDocument();let mounted=null,closed=0;
 const pending=settings.promptBotCreation({
   sheet:({onMount})=>{mounted=doc.createElement('div');doc.body.appendChild(mounted);mounted.innerHTML=settings.botCreationHtml();onMount(mounted);},
   closeSheet:()=>{closed++;}
 });
 mounted.querySelector('[data-bot-create-name]').value='新 Bot';
 const choices=mounted.querySelectorAll('[data-bot-avatar]');
 choices.forEach(node=>{node.checked=false;});
 choices[5].checked=true;
 mounted.querySelector('[data-create-bot]').click();
 const identity=await pending;
 assert.equal(closed,1);
 assert.equal(JSON.stringify(identity),JSON.stringify({name:'新 Bot',avatar:'squircle',fish:'goldfish',color:'ink'}));
});


test('形状与鱼类独立选择并组合预览，保存两个选择',async()=>{
 const doc=createDocument();let body;const result=settings.promptBotCreation({sheet:config=>{doc.body.innerHTML=config.body;body=doc.body;config.onMount(body)},closeSheet:()=>{}});
 body.querySelector('[data-bot-create-name]').value='剑鱼助手';
 for(const n of body.querySelectorAll('[data-bot-avatar]'))n.checked=n.dataset.botAvatar==='rounded-square';
 for(const n of body.querySelectorAll('[data-bot-fish]'))n.checked=n.dataset.botFish==='swordfish';
 body.dispatchEvent('change',{target:body.querySelector('[data-bot-fish="swordfish"]')});
 assert.ok(body.querySelector('[data-bot-avatar-preview] .shape-rounded-square .bot-fish'));
 for(const input of body.querySelectorAll('[data-bot-avatar]'))assert.equal(input.closest('.bot-avatar-choice').querySelector('.bot-fish').getAttribute('data-fish'),'swordfish');
 assert.equal(body.querySelectorAll('[data-bot-fish]').length,8);
 body.querySelector('[data-create-bot]').click();
 assert.equal(JSON.stringify(await result),JSON.stringify({name:'剑鱼助手',avatar:'rounded-square',fish:'swordfish',color:'ink'}));
});
test('八种鱼使用本地生图图集，裁切与单色处理不引入远端或用户内容',async()=>{
 const {ns:fish}=await loadModule('bot-fish.js');
 assert.equal(fish.BOT_FISH_TYPES.length,8);
 const svgs=fish.BOT_FISH_TYPES.map(k=>fish.fishSvg(k));assert.equal(new Set(svgs).size,8);
 for(const svg of svgs){assert.match(svg,/viewBox="0 0 48 48"/);assert.match(svg,/href="img\/fish-atlas\.png"/);assert.doesNotMatch(svg,/<script|https?:\/\/[^w]|onerror|foreignObject/)}
 assert.match(fish.fishSvg('<img onerror=evil>'),/data-fish="whale"/);assert.doesNotMatch(fish.fishSvg('<img onerror=evil>'),/evil|onerror/);
});

test('选择顺序为鱼形状颜色且预览和保存独立调色板',async()=>{
 const doc=createDocument();let body;const result=settings.promptBotCreation({sheet:opts=>{doc.body.innerHTML=opts.body;body=doc.body;opts.onMount(body);},closeSheet:()=>{}});
 const steps=body.querySelectorAll('.bot-avatar-step').map(n=>n.textContent);
 assert.equal(JSON.stringify(steps),JSON.stringify(['1. 选择鱼类','2. 选择形状','3. 选择颜色']));
 assert.equal(body.querySelectorAll('[data-bot-color]').length,12);
 assert.equal(body.querySelectorAll('.bot-avatar-choice small').length,0);
 body.querySelector('[data-bot-create-name]').value='蓝色鱼';
 for(const n of body.querySelectorAll('[data-bot-color]'))n.checked=n.dataset.botColor==='blue';
 body.dispatchEvent('change',{target:body.querySelector('[data-bot-color="blue"]')});
 assert.ok(body.querySelector('[data-bot-avatar-preview] .tone-blue'));
 body.querySelector('[data-create-bot]').click();
 const value=await result;assert.equal(value.color,'blue');assert.equal(value.fish,'goldfish');assert.equal(value.avatar,'circle');
});

test('running Bot preserves feedback, tool activity, user interruption, feedback, then current work',()=>{
 const conv=new convModule.Conv('chronology');
 let seq=0;const add=(type,data)=>conv.add({seq:++seq,time:seq*1000,type,data});
 const text=t=>add('assistant/message',{message:{content:[{type:'text',text:t}]}});
 const tool=id=>add('assistant/message',{message:{content:[{type:'tool-call',id,name:'read',arguments:'{"file_path":"/BOT/test/documents/check.txt"}'}]}});
 add('turn/start',{});text('初始反馈');tool('read-one');
 add('tool/result',{message:{toolCallId:'read-one',content:[{type:'text',text:'ok'}]}});
 text('第二段反馈');add('user/message',{id:'interrupt',source:{kind:'user'},content:[{type:'text',text:'用户插话'}]});
 text('回应插话');tool('read-two');add('tool/result',{message:{toolCallId:'read-two',content:[{type:'text',text:'ok'}]}});
 text('第三段反馈');
 const live=conv.build({running:true});
 assert.deepEqual(Array.from(live,i=>i.k==='text'?i.text:i.k==='user'?i.text:i.k),['初始反馈','process','第二段反馈','用户插话','回应插话','process','第三段反馈','process']);
 const processes=live.filter(i=>i.k==='process');assert.equal(new Set(processes.map(i=>i.key)).size,3);
 assert.deepEqual(Array.from(processes,i=>i.running),[false,false,true]);
 assert.equal(processes[0].items[0].tools[0].id,'read-one');assert.equal(processes[1].items[0].tools[0].id,'read-two');assert.equal(processes[2].items[0].k,'working');
 tool('read-three');add('tool/result',{message:{toolCallId:'read-three',content:[{type:'text',text:'ok'}]}});
 text('最终结论');add('turn/end',{reason:{kind:'completed'}});
 const done=conv.build({running:false});
 assert.equal(done.filter(i=>i.k==='process').length,2);
 assert.deepEqual(Array.from(done,i=>i.k==='user'?i.text:i.k==='text'?i.text:i.k),['process','用户插话','process','最终结论']);
 assert.equal(done.at(-1).text,'最终结论');assert.ok(done.filter(i=>i.k==='process').every(i=>!i.running));
});
test('appending another tool preserves the current phase disclosure and reading key',()=>{
 const conv=new convModule.Conv('phase-key');let seq=0;
 const add=(type,data)=>conv.add({seq:++seq,time:seq*1000,type,data});
 add('turn/start',{});add('assistant/message',{message:{content:[{type:'text',text:'开始读取'}]}});
 const tool=id=>add('assistant/message',{message:{content:[{type:'tool-call',id,name:'read',arguments:'{}'}]}});
 tool('first');const key=conv.build({running:true}).find(i=>i.k==='process').key;
 tool('second');assert.equal(conv.build({running:true}).find(i=>i.k==='process').key,key);
});

for(const [name,args] of [['present',{files:[{path:'documents/report.html'}]}],['read',{file_path:'documents/check.png'}]])test(`final ${name} work folds its preceding feedback while preserving final answer and delivery`,()=>{
 const conv=new convModule.Conv('delivery-final');let seq=0;
 const add=(type,data)=>conv.add({seq:++seq,time:seq*1000,type,data});
 const say=text=>add('assistant/message',{message:{content:[{type:'text',text}]}});
 add('turn/start',{});say('现在核对图片并交付文件。');
 add('assistant/message',{message:{content:[{type:'tool-call',id:'delivery',name,arguments:JSON.stringify(args)}]}});
 add('tool/result',{message:{toolCallId:'delivery',content:[{type:'text',text:'ok'}]}});
 const running=conv.build({running:true});
 assert.ok(running.some(i=>i.k==='text'&&i.text==='现在核对图片并交付文件。'));
 say('最终结论：已完成。');add('turn/end',{reason:{kind:'completed'}});
 const done=conv.build({running:false});
 assert.deepEqual(Array.from(done.filter(i=>i.k==='text'),i=>i.text),['最终结论：已完成。']);
 assert.ok(done.some(i=>i.k==='process'&&i.items.some(child=>child.k==='text'&&child.text==='现在核对图片并交付文件。')));
 if(name==='present')assert.ok(done.some(i=>i.k==='art'&&i.path.endsWith('documents/report.html')));
 else assert.ok(done.some(i=>i.k==='process'&&i.items.some(child=>child.k==='tools'&&child.cat==='image')));
});

test('deleted Bot sessions remain unowned and are never auto-selected as the default Bot',()=>{
 const registry={defaultBotId:'default',bots:[{...bot('default','主 Bot',true,['current-default']),workspace:'/BOT/default'}]};
 const sessions=new Map([['orphan',{sessionId:'orphan',cwd:'/BOT/deleted-bot',updatedAt:100}],['current-default',{sessionId:'current-default',cwd:'/BOT/default',updatedAt:1}]]);
 assert.equal(ns.ownerOfSession(registry,'orphan',sessions.get('orphan')),null);
 assert.deepEqual(Array.from(ns.sessionsForBot(registry,'default',sessions),s=>s.sessionId),['current-default']);
 assert.equal(ns.ownerOfSession(registry,'child',{cwd:'/BOT/default/child'}),'default');
});
