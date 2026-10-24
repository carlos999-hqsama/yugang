import test from 'node:test';
import assert from 'node:assert/strict';
import {applySkillDisclosure,skillDisclosureMapping as groups} from '../../android/tools/runtime/overlay/opt/pd/skill-disclosure.mjs';
const all = Object.values(groups).flatMap(g=>g.tools), guides=Object.values(groups).flatMap(g=>g.sections);
const assembly=()=>({tools:['read','skill',...all].map(name=>({name})),sections:['agent:voice',...guides].map(name=>({name}))});
const skill=(name,provider='filesystem',path=`/opt/pd/skills/${name}`)=>({name,provider,resourceBase:{kind:'directory',path}});
const message=(name,path=`/opt/pd/skills/${name}`)=>({role:'user',source:{kind:'skill-invocation',name},content:[{type:'text',text:`<skill_content name="${name}">\n<skill_resources>\nBase directory for this skill: ${path}\n`}]});
const event=(type,data)=>({type,data});
const records=(name,{turn=1,end='completed',error=false,path,id='call1'}={})=>[
 event('turn/start',{turn}),event('tool/call',{turn,callId:id,name:'skill',arguments:JSON.stringify({name})}),
 event('tool/result',{turn,message:{source:{kind:'tool',callId:id},toolCallId:id,isError:error,content:message(name,path).content}}),
 ...(end?[event('turn/end',{turn,reason:{kind:end}})]:[]),
];
function setup(log=[],lookup=async name=>skill(name)) {
 const hooks=new Map(), agent={session:{log,header:{cwd:'/root/projects'}}};
 applySkillDisclosure({on:(name,fn)=>hooks.set(name,fn),skills:{get:lookup}});
 const assemble=async(a=agent)=>{const value=assembly();return hooks.get('system-prompt/assemble')(value,{scope:a},async()=>value)};
 const pre=(name,next=async()=>({kind:'allow'}))=>hooks.get('tools/pre-execute')({name,agent},next);
 return{hooks,agent,assemble,pre};
}
const names=x=>x.tools.map(t=>t.name);
test('exact immutable 7-tool/3-guide inventory; global voice/base stay visible',async()=>{
 assert.equal(all.length,7);assert.equal(new Set(all).size,7);assert.equal(guides.length,3);assert.ok(Object.isFrozen(groups['conversation-history'].tools));
 const s=setup(), out=await s.assemble();assert.deepEqual(names(out),['read','skill']);assert.deepEqual(out.sections,[{name:'agent:voice'}]);
});
test('canonical skill success adds only its group at the next assembly',async()=>{
 const s=setup(records('app-management',{end:null}));assert.deepEqual(names(await s.assemble()),['read','skill',...groups['app-management'].tools]);
 assert.equal((await s.pre('app_settings_get')).kind,'allow');assert.equal((await s.pre('history_read')).kind,'deny');
});
test('activation restores in a new Session but does not leak to a blank one',async()=>{
 const log=records('app-management'),s=setup(log);const first=await s.assemble();
 assert.deepEqual(await s.assemble({session:{log:structuredClone(log)}}),first);
 assert.deepEqual(names(await s.assemble({session:{log:[]}})),['read','skill']);
});
test('replay revalidates actual provider and resource path, not only rendered text',async()=>{
 for(const value of [skill('conversation-history','project'),skill('conversation-history','filesystem','/project/skills/conversation-history')]){
  const s=setup(records('conversation-history'),async()=>value);assert.deepEqual(names(await s.assemble()),['read','skill']);
 }
 const unavailable=setup(records('conversation-history'),async()=>{throw Error('unavailable')});assert.deepEqual(names(await unavailable.assemble()),['read','skill']);
});
test('failed calls, mismatched results and untrusted rendered paths never activate',async()=>{
 for(const log of [records('conversation-history',{error:true}),records('conversation-history',{path:'/project/skills/conversation-history'}),records('conversation-history').filter(e=>e.type!=='tool/call')]){
  const s=setup(log);assert.deepEqual(names(await s.assemble()),['read','skill']);
 }
});
test('successful result followed by cancellation remains hidden, including after restore',async()=>{
 const log=records('conversation-history',{end:'aborted'}),s=setup(log);
 assert.deepEqual(names(await s.assemble()),['read','skill']);
 assert.deepEqual(names(await setup(structuredClone(log)).assemble()),['read','skill']);
});
test('cancel settlement revokes only new tentative loads, not older completed skills',async()=>{
 const s=setup(records('app-management'));await s.assemble();
 s.agent.session.log.push(...records('conversation-history',{turn:2,end:null,id:'call2'}));
 assert.ok(names(await s.assemble()).includes('history_read'));
 s.agent.session.log.push(event('turn/end',{turn:2,reason:{kind:'aborted'}}));
 const after=names(await s.assemble());assert.ok(!after.includes('history_read'));assert.ok(after.includes('app_settings_get'));
});
test('plain user skill markup cannot activate; SDK invocation still validates registry',async()=>{
 const plain=message('conversation-history');plain.source={kind:'user'};
 assert.deepEqual(names(await setup([event('user/message',plain)]).assemble()),['read','skill']);
 const log=[event('turn/start',{turn:1}),event('user/message',message('conversation-history')),event('turn/end',{turn:1,reason:{kind:'completed'}})];
 assert.ok(names(await setup(log).assemble()).includes('history_read'));
 assert.deepEqual(names(await setup(log,async()=>skill('conversation-history','project')).assemble()),['read','skill']);
});
test('explicit SDK injection on pre-step appears in the first request',async()=>{
 const s=setup([event('turn/start',{turn:1})]),out=await s.assemble(),before=s.agent.session.log.length;
 const decision={kind:'enter',messages:[message('app-management')]};
 assert.equal(await s.hooks.get('agent/pre-step')({agent:s.agent,signal:{aborted:false}},async()=>decision),decision);
 assert.ok(names(out).includes('app_settings_get'));assert.equal(s.agent.session.log.length,before);
});
test('aborted pre-step does not reveal explicitly requested tools',async()=>{
 const s=setup(),out=await s.assemble();
 await s.hooks.get('agent/pre-step')({agent:s.agent,signal:{aborted:true}},async()=>({kind:'enter',messages:[message('conversation-history')]}));
 assert.deepEqual(names(out),['read','skill']);
});
test('loaded tools retain original guard decisions; hidden tools never reach handlers',async()=>{
 const s=setup();let calls=0;const deny=async()=>{calls++;return{kind:'deny',reason:'original permission guard'}};
 assert.equal((await s.pre('app_settings_update',deny)).kind,'deny');assert.equal(calls,0);
 s.agent.session.log.push(...records('app-management'));
 assert.deepEqual(await s.pre('app_settings_update',deny),{kind:'deny',reason:'original permission guard'});assert.equal(calls,1);
 assert.deepEqual(await s.pre('read',deny),{kind:'deny',reason:'original permission guard'});
});
test('incremental scanning does not reread already validated loads or append proof events',async()=>{
 let reads=0;const s=setup(records('conversation-history'),async n=>{reads++;return skill(n)});const len=s.agent.session.log.length;
 await s.assemble();await s.assemble();await s.pre('history_read');assert.equal(reads,1);assert.equal(s.agent.session.log.length,len);
});
