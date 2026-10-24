import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm,mkdir,symlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {applyBotWorkspace,BOT_PROMPT_SECTION_ORDER,projectBotAssembly,toolAllowed} from '../../android/tools/runtime/overlay/opt/pd/bot-workspace.mjs';
import {applyPluginTools} from '../../android/tools/runtime/overlay/opt/pd/plugin-tools.mjs';
import {applySkillDisclosure} from '../../android/tools/runtime/overlay/opt/pd/skill-disclosure.mjs';
import {ownsSession,ownHistoryQuery} from '../../android/tools/runtime/overlay/opt/pd/bot-context.mjs';
import {archiveProjection,saveArchive} from '../../android/tools/runtime/overlay/opt/pd/bot-archives.mjs';
const overlay=new URL('../../android/tools/runtime/overlay/opt/pd/',import.meta.url);
const vendor=process.env.PD_DSH_VENDOR_ROOT;
const {renderPrompt}=await import(pathToFileURL(vendor+'/node_modules/@deepseek-ai/dsh-system-prompt/lib/index.js'));
const a={id:'a',workspace:'/BOT/a',instructions:'只做甲工作 {{literal}}',memory:'甲的独有记忆',defaultPersona:'默认人格：短句直接。',sharedPersona:'共同人格一',learnedPersona:'共同学习表达：表达直接。',enabledTools:['files','skills','memory','conversation-history'],sessionIds:['old-a'],excludedSessionIds:['old-b']};
const b={...a,id:'b',workspace:'/BOT/b',instructions:'乙职责',memory:'乙的记忆',sessionIds:['old-b'],excludedSessionIds:['old-a']};
const base=()=>({sections:[{name:'agent:voice',text:'old personality'},{name:'base',text:'base'},{name:'agent:capabilities',text:'STATIC_ALL_CAPABILITIES'},{name:'tool:app-settings',text:'APP_MANUAL_SHOULD_FOLLOW_BOT_SWITCH'}],contexts:[],variables:{},tools:['read','bash','job_output','job_list','job_kill','skill','memory_recall','history_search','phone_plugins_list','app_settings_get'].map(name=>({name}))});
test('prompt modules replace all persona layers, role and memory without accumulating old sections',()=>{
 const first=projectBotAssembly(base(),{...a,sharedPersona:'PERSONA_OLD',learnedPersona:'LEARNED_OLD',instructions:'ROLE_OLD',memory:'MEMORY_OLD'});
 const next=projectBotAssembly(first,{...a,sharedPersona:'PERSONA_CURRENT',learnedPersona:'LEARNED_CURRENT',instructions:'ROLE_CURRENT',memory:'MEMORY_CURRENT'});
 const text=renderPrompt(next);
 for(const old of ['PERSONA_OLD','LEARNED_OLD','ROLE_OLD','MEMORY_OLD'])assert.ok(!text.includes(old));
 for(const current of ['PERSONA_CURRENT','LEARNED_CURRENT','ROLE_CURRENT','MEMORY_CURRENT'])assert.ok(text.includes(current));
 for(const name of ['bot:identity','bot:instructions','bot:memory','bot:default-persona','bot:persona','bot:learned-persona','bot:style'])assert.equal(next.sections.filter(s=>s.name===name).length,1);
 const style=next.sections.find(s=>s.name==='bot:style').text;
 assert.ok(style.includes('首次工具调用前')&&style.includes('实质进展')&&style.includes('最终只给实际结果'));
 assert.ok(!style.includes('一到三句')&&!style.includes('默认精简回答')&&!style.includes('用户要求详细'));
 const persona=next.sections.find(s=>s.name==='bot:persona').text;
 const defaults=next.sections.find(s=>s.name==='bot:default-persona').text;
 const learned=next.sections.find(s=>s.name==='bot:learned-persona').text;
 assert.ok(persona.includes('最高表达优先级')&&!persona.includes('默认人格：'));
 assert.ok(defaults.includes('最低表达层')&&defaults.includes(a.defaultPersona));
 assert.ok(learned.includes('低于主人明确手动要求、高于默认人格'));
 assert.deepEqual(
   Object.fromEntries([['bot:default-persona',BOT_PROMPT_SECTION_ORDER.defaultPersona],['bot:learned-persona',BOT_PROMPT_SECTION_ORDER.learnedPersona],['bot:persona',BOT_PROMPT_SECTION_ORDER.manualPersona],['bot:style',BOT_PROMPT_SECTION_ORDER.style]]),
   Object.fromEntries(['bot:default-persona','bot:learned-persona','bot:persona','bot:style'].map(name=>[name,next.sections.find(section=>section.name===name).order])),
 );
 const emptyManual=projectBotAssembly(base(),{...a,sharedPersona:'',learnedPersona:'共同学习表达：语气温暖。'});
 assert.ok(!emptyManual.sections.some(section=>section.name==='bot:persona'));
 assert.equal(emptyManual.sections.filter(section=>section.name==='bot:default-persona').length,1);
 assert.ok(emptyManual.sections.find(section=>section.name==='bot:learned-persona').text.includes('语气温暖'));
 assert.equal(a.sharedPersona,'共同人格一');
});
test('same personas, independent instructions and memories, literal braces render with actual SDK',()=>{
 const pa=renderPrompt(projectBotAssembly(base(),a)),pb=renderPrompt(projectBotAssembly(base(),b));
 assert.ok(pa.includes('共同人格一')&&pb.includes('共同人格一'));
 assert.ok((pa.match(/默认人格：短句直接。/g)||[]).length===1&&(pb.match(/默认人格：短句直接。/g)||[]).length===1);
 assert.ok(pa.includes('共同学习表达：表达直接。')&&pb.includes('共同学习表达：表达直接。'));
 assert.ok(pa.includes(a.memory)&&!pa.includes(b.memory));assert.ok(pb.includes(b.memory)&&!pb.includes(a.memory));
 assert.ok(pa.includes('{{literal}}'));assert.ok(!pa.includes('old personality'));
 for(const name of ['bash','job_output','job_list','job_kill','app_settings_get','phone_plugins_list'])assert.equal(toolAllowed(a,name),false,name);
 assert.equal(toolAllowed(a,'history_read'),true);
 assert.equal(toolAllowed(a,'read'),true);
});
test('late skill injection cannot re-enable disabled tools, next step refreshes shared persona and learning, old durable header unchanged',async()=>{
 const hooks=new Map();let current={...a};
 const ctx={on:(name,fn)=>hooks.set(name,[...(hooks.get(name)||[]),fn]),skills:{get:async name=>({name,provider:'filesystem',resourceBase:{kind:'directory',path:'/opt/pd/skills/'+name}})}};
 applyBotWorkspace(ctx,async()=>current);applySkillDisclosure(ctx);
 const run=(name,args,finish)=>{const list=hooks.get(name)||[];const next=i=>i<list.length?list[i](...args,()=>next(i+1)):finish();return next(0)};
 const agent={session:{id:'old-a',header:Object.freeze({cwd:'/BOT/a'}),log:[]}};
 const assemble=()=>run('system-prompt/assemble',[base(),{scope:agent}],async()=>base());
 let out=await assemble();assert.equal(agent.session[Symbol.for('pd.bot.workspace')],a.workspace);assert.equal(agent.session.header.cwd,'/BOT/a');
 await run('agent/pre-step',[{agent,signal:new AbortController().signal}],async()=>({kind:'enter',messages:[{role:'user',source:{kind:'skill-invocation',name:'app-management'},content:[{type:'text',text:'<skill_content name="app-management">\n<skill_resources>\nBase directory for this skill: /opt/pd/skills/app-management\n'}]}]}));
 assert.ok(!out.tools.some(t=>t.name==='app_settings_get'));
 assert.ok(!out.sections.some(s=>s.name==='tool:app-settings'));
 assert.ok(!out.sections.find(s=>s.name==='agent:capabilities').text.includes('app-management'));
 assert.equal((await run('tools/pre-execute',[{name:'app_settings_get',agent}],async()=>({kind:'allow'}))).kind,'deny');
 current={...a,enabledTools:[...a.enabledTools,'app-management']};out=await assemble();assert.ok(out.tools.some(t=>t.name==='app_settings_get'));assert.ok(out.sections.some(s=>s.name==='tool:app-settings'));assert.ok(out.sections.find(s=>s.name==='agent:capabilities').text.includes('app-management'));
 current={...a,sharedPersona:'共同人格二',learnedPersona:'共同学习表达：语气温暖。'};out=await assemble();
 assert.ok(renderPrompt(out).includes('共同人格二')&&renderPrompt(out).includes('共同学习表达：语气温暖。'));
 assert.equal((await run('tools/pre-execute',[{name:'bash',agent}],async()=>({kind:'allow'}))).kind,'deny');
 current={...a,enabledTools:[]};assert.equal((await run('tools/pre-execute',[{name:'read',agent}],async()=>({kind:'allow'}))).kind,'deny');
});
test('mapped old and child conversations are scoped; traversal and other Bot rejected before querying text',async()=>{
 assert.ok(ownsSession(a,'old-a','/BOT/a'));assert.ok(ownsSession(a,'child','/BOT/a/sub'));
 assert.ok(!ownsSession(a,'old-b','/BOT/a'));assert.ok(!ownsSession(a,'other','/BOT/a/../b'));
 const records=[{header:{id:'old-a',cwd:'/BOT/a'}},{header:{id:'old-b',cwd:'/BOT/a'}},{header:{id:'child',cwd:'/BOT/a'}},{header:{id:'other',cwd:'/BOT/b'}}];let reads=0;
 const query={listSessions:async()=>records,filterSessions:async f=>records.filter(r=>f[0].values.includes(r.header.id)),readEvent:async()=>{reads++;return 'ok'},searchEvents:async()=>{reads++;return 'ok'}};
 const scoped=await ownHistoryQuery(query,{agent:{session:{id:'old-a',header:{cwd:'/BOT/a'}}}},async()=>a);
 assert.deepEqual((await scoped.listSessions()).map(r=>r.header.id),['old-a','child']);
 await assert.rejects(scoped.readEvent({sessionId:'old-b'}),/不属于/);await assert.rejects(scoped.searchEvents({sessionId:'other'}),/不属于/);assert.equal(reads,0);
 assert.equal(await scoped.readEvent({sessionId:'old-a'}),'ok');
});
test('readable archives persist per Bot and exclude private reasoning and tool payload; originals untouched',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'pd-archives-'));
 try{
 const session={id:'session/synthetic'},events=[{type:'user/message',seq:1,time:2,data:{source:{kind:'user'},content:[{type:'text',text:'hello'},{type:'image',data:'image-secret'}]}},{type:'assistant/message',seq:3,time:3,data:{message:{content:[{type:'text',text:'done'},{type:'reasoning',text:'private-reasoning'}]}}},{type:'tool/result',seq:4,data:{text:'private-tool'}},{type:'user/message',seq:5,data:{source:{kind:'runtime-context'},content:[{type:'text',text:'private-context'}]}}];
 const before=JSON.stringify(events);await saveArchive({...a,workspace:dir},session,events);
 const saved=await readFile(join(dir,'archives','session%2Fsynthetic.json'),'utf8');assert.equal(JSON.stringify(events),before);
 assert.deepEqual(JSON.parse(saved),archiveProjection(a.id,session,events));assert.ok(!saved.includes('private')&&!saved.includes('image-secret'));
 await saveArchive({...a,workspace:dir},session,[...events,{type:'user/message',seq:5,data:{source:{kind:'user'},content:[{type:'text',text:'again'}]}}]);
 assert.equal(JSON.parse(await readFile(join(dir,'archives','session%2Fsynthetic.json'),'utf8')).messages.length,3);
 }finally{await rm(dir,{recursive:true,force:true})}
});
test('actual session-management definitions only list owned rows and reject cross-Bot deletion before confirmation',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'pd-bot-tools-'));
 try{
  for(const name of ['app-sessions.mjs','session-recycle.mjs','bot-workspace.mjs','bot-context.mjs','skill-disclosure.mjs'])await writeFile(join(dir,name),await readFile(new URL(name,overlay)));
 await symlink(vendor,join(dir,'dsh'),'dir');
 await writeFile(join(dir,'app-tools.mjs'),'export function requireAppWrite(){}');
 await writeFile(join(dir,'bot-context.mjs'),`export {ownsSession} from ${JSON.stringify(new URL('bot-context.mjs',overlay).href)};export async function requestBotContext(){return ${JSON.stringify(a)}}`);
 const definitions=new Map(),headers=[{id:'old-a',cwd:'/BOT/a'},{id:'old-b',cwd:'/BOT/a'},{id:'child',cwd:'/BOT/a'}];
 const ctx={tools:{register:t=>definitions.set(t.name,t)},systemPrompt:{section(){}},workspaceRegistry:{list:()=>[],archivedSessionIds:[],pinnedSessionIds:[]},sessionController:{list:async()=>({items:headers.map(h=>({sessionId:h.id,cwd:h.cwd,agentAvailable:true}))})},sessionPersistence:{root:join(dir,'sessions'),stat:async id=>({header:headers.find(h=>h.id===id)}),list:async()=>headers.map(header=>({header}))},sessionQuery:{listSessions:async()=>headers.map(header=>({header}))},sessions:{get:()=>({})},get:()=>undefined};
 const {apply}=await import(pathToFileURL(join(dir,'app-sessions.mjs')));apply(ctx);
 const exec={agent:{session:{id:'old-a',header:{cwd:'/BOT/a'}}},signal:new AbortController().signal};
 const listed=await definitions.get('app_sessions_list').execute({},exec);assert.deepEqual(listed.items.map(r=>r.id),['old-a','child']);
 await assert.rejects(definitions.get('app_sessions_delete').execute({ids:['old-b']},exec),/只能管理/);
 }finally{await rm(dir,{recursive:true,force:true})}
});
test('archive plugin starts backfill as a lifecycle effect and refreshes after real turn-end hook',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'pd-bot-backfill-'));
 try{
 const bot={...a,workspace:dir};let events=[{type:'user/message',seq:0,data:{source:{kind:'user'},content:[{type:'text',text:'old synthetic'}]}}];
 const stub='data:text/javascript;base64,'+Buffer.from(`export async function requestBotContext(){return ${JSON.stringify(bot)}}`).toString('base64');
 const source=(await readFile(new URL('bot-archives.mjs',overlay),'utf8')).replace("'./bot-context.mjs'",JSON.stringify(stub));
 const plugin=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
 let dispose;const hooks=new Map();
 plugin.apply({on:(name,fn)=>hooks.set(name,fn),effect:fn=>dispose=fn(),logger:{warn:()=>assert.fail('archive failed')},sessionQuery:{listSessions:async()=>[{header:{id:'one',cwd:'/BOT/a'}}],readSession:async()=>({session:{id:'one',cwd:'/BOT/a'},events})}});
 const saved=join(dir,'archives','one.json');
 const until=async check=>{for(let i=0;i<100;i++){try{if(await check())return}catch{}await new Promise(r=>setTimeout(r,10))}assert.fail('archive was not produced')};
 await until(async()=>JSON.parse(await readFile(saved,'utf8')).messages.length===1);
 events=[...events,{type:'assistant/message',seq:1,data:{message:{content:[{type:'text',text:'new synthetic'}]}}}];
 hooks.get('session/event')({id:'one'},{type:'turn/end'});
 await until(async()=>JSON.parse(await readFile(saved,'utf8')).messages.length===2);dispose();
 }finally{await rm(dir,{recursive:true,force:true})}
});

test('retired lowercase Bot cwd no longer implies ownership',()=>{
 const bot={id:'a',workspace:'/BOT/a',sessionIds:[],excludedSessionIds:[]};
 assert.equal(ownsSession(bot,'child','/bots/a/project'),false);
 assert.equal(ownsSession(bot,'other','/BOT/b/project'),false);
});

test('actual plugin guidance disappears with disabled tools and returns without overriding persona or memory',()=>{
 const sections=[];applyPluginTools({systemPrompt:{section:s=>sections.push(s)},tools:{register(){}}});
 const source=base();source.sections.push(...sections);
 const disabled=projectBotAssembly(source,a);assert.ok(!disabled.tools.some(t=>t.name.startsWith('phone_plugins_')));
 assert.ok(!renderPrompt(disabled).includes('This phone is a plugin platform'));
 const enabled=projectBotAssembly(source,{...a,enabledTools:[...a.enabledTools,'plugins']});
 assert.ok(enabled.tools.some(t=>t.name==='phone_plugins_list'));
 const rendered=renderPrompt(enabled);assert.ok(rendered.includes('host-contract.md')&&rendered.includes('Do not read every reference up front'));
 assert.ok(rendered.includes(a.sharedPersona)&&rendered.includes(a.memory)&&rendered.includes(a.instructions));
});
