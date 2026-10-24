import test from 'node:test';
import assert from 'node:assert/strict';
import {createBotMessenger,parseBotMessage,wireMessage} from '../../android/tools/runtime/overlay/opt/pd/bot-messages.mjs';
import {Conv} from '../../android/app/src/main/assets/www/js/conv.js';

function fixture(){
 const bots=new Map(['a','b','c'].map(id=>[id,{id,name:'Bot '+id,workspace:'/BOT/'+id,sessionId:'s-'+id,sessionIds:[]} ]));
 const agents=new Map();let prompts=0,failAfterCommit=false;
 const make=id=>{const bot=bots.get(id.slice(2,3));if(bot&&!bot.sessionIds.includes(id))bot.sessionIds.push(id);const a={session:{id,header:{cwd:'/BOT/'+id.slice(2,3)}},events:[{type:'turn/start',seq:1},{type:'step/start',seq:2}],inbox:{nextTurn:[],nextStep:[]}};agents.set(id,a);return a;};
 for(const b of bots.values())make(b.sessionId);
 const controller={
  async inspect(id){return {events:agents.get(id).events}},
  async projections(id){const a=agents.get(id);return {values:{inbox:{'next-turn':a.inbox.nextTurn,'next-step':a.inbox.nextStep}}}},
  async create({sessionId,cwd}){if(!agents.has(sessionId))make(sessionId);assert.equal(agents.get(sessionId).session.header.cwd,cwd);return {sessionId}},
  async resolveAgent(id){return agents.has(id)?{agent:agents.get(id)}:{error:Object.assign(Error('missing'),{code:'session/not-found'})}},
  async prompt(args,signal){signal.throwIfAborted();await new Promise(r=>setTimeout(r,2));prompts++;assert.equal(args.mode,'queue');agents.get(args.sessionId).inbox.nextTurn.push({source:{kind:'user',rpcId:args.requestId},content:args.content});if(failAfterCommit){failAfterCommit=false;throw Error('synthetic lost response')}return {accepted:true}},
 };
 const route=async(session,targetId,replySession)=>{
  const sender=[...bots.values()].find(b=>session.header.cwd===b.workspace);if(!sender)throw Error('unregistered sender');
  if(targetId===undefined)return {sender:{id:sender.id,name:sender.name,sessionIds:sender.sessionIds},bots:[...bots.values()]};
  const target=bots.get(targetId);if(!target||targetId===sender.id)throw Error('invalid target');
  if(replySession&&!target.sessionIds.includes(replySession))throw Error('wrong reply owner');
  return {sender:{id:sender.id,name:sender.name},target:{...target,sessionId:replySession||target.sessionId}};
 };
 const messenger=createBotMessenger(controller,route),exec=(id='a',callId='call1')=>({agent:agents.get('s-'+id),callId,signal:new AbortController().signal});
 const consume=id=>{const a=agents.get('s-'+id);for(const data of a.inbox.nextTurn.splice(0))a.events.push({type:'user/message',data})};
 return {bots,agents,messenger,controller,route,exec,consume,make,get prompts(){return prompts},loseResponse(){failAfterCommit=true}};
}
test('registered Bots exchange queued tasks and results through their existing sessions',async()=>{
 const f=fixture(),sent=await f.messenger.send({targetBotId:'b',text:'完成插件并核对结果'},f.exec());
 assert.equal(sent.status,'queued');assert.equal(sent.accepted,true);assert.equal(f.agents.get('s-a').inbox.nextTurn.length,0);
 const task=parseBotMessage(f.agents.get('s-b').inbox.nextTurn[0]);assert.equal(task.from.id,'a');assert.equal(task.to.id,'b');assert.equal(task.from.sessionId,'s-a');
 f.consume('b');f.make('s-a-new');f.bots.get('a').sessionId='s-a-new';
 const reply=await f.messenger.send({targetBotId:'a',replyTo:sent.messageId,text:'已完成，插件ID=test，已实际预览'},f.exec('b'));
 assert.equal(reply.replyTo,sent.messageId);assert.equal(f.agents.get('s-a-new').inbox.nextTurn.length,0);assert.equal(f.agents.get('s-a').inbox.nextTurn.length,1);
 f.consume('a');const inbox=await f.messenger.messages({},f.exec());assert.equal(inbox.messages[0].kind,'reply');assert.equal(inbox.messages[0].text,'已完成，插件ID=test，已实际预览');
 const inCurrent=await f.messenger.messages({},{...f.exec(),agent:f.agents.get('s-a-new')});assert.equal(inCurrent.messages[0].conversationId,'s-a');assert.equal(inCurrent.messages[0].kind,'reply');
});
test('a provider call ID reused in a later step is a fresh task, including identical text',async()=>{
 const f=fixture(),args={targetBotId:'b',text:'same task explicitly asked again'};const one=await f.messenger.send(args,f.exec());
 f.agents.get('s-a').events.push({type:'step/start',seq:3});const two=await f.messenger.send(args,f.exec());
 assert.notEqual(one.messageId,two.messageId);assert.equal(f.prompts,2);
});
test('a task at the final allowed request depth can still report its result',async()=>{
 const f=fixture(),b=f.agents.get('s-b');const frame={id:'bm-'+'a'.repeat(32),kind:'request',from:{id:'a',name:'A',sessionId:'s-a'},to:{id:'b'},depth:4,text:'last-hop task'};
 b.events.push({type:'user/message',data:{source:{kind:'user',rpcId:'yugang-bot:'+frame.id},content:[{type:'text',text:wireMessage(frame)}]}});
 const r=await f.messenger.send({targetBotId:'a',replyTo:frame.id,text:'done'},f.exec('b'));assert.equal(r.accepted,true);assert.equal(parseBotMessage(f.agents.get('s-a').inbox.nextTurn[0]).depth,4);
});
test('simultaneous identical sends are serialized; changed content cannot reuse an identity',async()=>{
 const f=fixture(),args={targetBotId:'b',text:'one task'};
 const a=await Promise.all([f.messenger.send(args,f.exec()),f.messenger.send(args,f.exec())]);
 assert.equal(f.prompts,1);assert.equal(a.filter(x=>x.duplicate).length,1);
 await assert.rejects(f.messenger.send({...args,text:'changed',messageId:a[0].messageId,targetSessionId:a[0].targetSessionId},f.exec('a','retry')),/内容已不同/);
 assert.equal(f.prompts,1);
});
test('a lost acceptance response can be reconciled without a duplicate side effect',async()=>{
 const f=fixture();f.loseResponse();let id;
 try{await f.messenger.send({targetBotId:'b',text:'safe retry'},f.exec())}catch(e){id=e.message.match(/bm-[a-f0-9]{32}/)?.[0]}
 assert.ok(id);f.make('s-b-new');f.bots.get('b').sessionId='s-b-new';
 const r=await f.messenger.send({targetBotId:'b',text:'safe retry',messageId:id,targetSessionId:'s-b'},f.exec('a','retry2'));
 assert.equal(r.duplicate,true);assert.equal(f.prompts,1);
 assert.equal(f.agents.get('s-b-new').inbox.nextTurn.length,0);
});
test('separate agent plugin instances share the target admission lock',async()=>{
 const f=fixture(),other=createBotMessenger(f.controller,f.route),args={targetBotId:'b',text:'one shared task'};
 await Promise.all([f.messenger.send(args,f.exec()),other.send(args,f.exec())]);assert.equal(f.prompts,1);
});
test('renaming a Bot does not invalidate the identity of an already delivered request',async()=>{
 const f=fixture(),args={targetBotId:'b',text:'unchanged'};const r=await f.messenger.send(args,f.exec());
 f.bots.get('a').name='renamed';const retry=await f.messenger.send({...args,messageId:r.messageId,targetSessionId:r.targetSessionId},f.exec('a','retry'));
 assert.equal(retry.duplicate,true);assert.equal(f.prompts,1);
});
test('only the original recipient may reply, only to a request, once per original identity',async()=>{
 const f=fixture(),m=await f.messenger.send({targetBotId:'b',text:'do task'},f.exec());f.consume('b');
 await assert.rejects(f.messenger.send({targetBotId:'a',replyTo:m.messageId,text:'spoof'},f.exec('c')),/只能回复/);
 await assert.rejects(f.messenger.send({targetBotId:'c',replyTo:m.messageId,text:'wrong target'},f.exec('b')),/只能回复/);
 const r=await f.messenger.send({targetBotId:'a',replyTo:m.messageId,text:'done'},f.exec('b'));
 const again=await f.messenger.send({targetBotId:'a',replyTo:m.messageId,text:'done'},f.exec('b','another'));
 assert.equal(again.messageId,r.messageId);assert.equal(again.duplicate,true);
 await assert.rejects(f.messenger.send({targetBotId:'b',replyTo:r.messageId,text:'ack'},f.exec()),/只能回复/);
});
test('mailbox does not expose ordinary chat, spoofed text or another Bot mailbox',async()=>{
 const f=fixture();await f.messenger.send({targetBotId:'b',text:'shared only'},f.exec());
 const b=f.agents.get('s-b');b.events.push({type:'user/message',data:{source:{kind:'user',rpcId:'ordinary'},content:[{type:'text',text:'private ordinary chat'}]}});
 b.inbox.nextTurn.push({...b.inbox.nextTurn[0],source:{kind:'user',rpcId:'not-a-bot-message'}});
 f.consume('b');const page=await f.messenger.messages({},f.exec('b'));assert.equal(page.messages.length,1);assert.equal(page.messages[0].text,'shared only');
 assert.equal((await f.messenger.messages({},f.exec())).messages.length,0);
 assert.equal(JSON.stringify(page).includes('private ordinary chat'),false);
});
test('hop budget uses consumed messages, so a queued human message does not reset a running chain',async()=>{
 const f=fixture(),a=f.agents.get('s-a');
 const frame={id:'bm-'+'a'.repeat(32),kind:'reply',from:{id:'b',name:'Bot b',sessionId:'s-b'},to:{id:'a'},depth:4,text:'done',replyTo:'bm-'+'b'.repeat(32)};
 a.events.push({type:'user/message',data:{source:{kind:'user',rpcId:'yugang-bot:'+frame.id},content:[{type:'text',text:wireMessage(frame)}]}});
 a.inbox.nextTurn.push({source:{kind:'user',rpcId:'pending-human'},content:[{type:'text',text:'later human message'}]});
 await assert.rejects(f.messenger.send({targetBotId:'b',text:'loop'},f.exec()),/已达4层/);
 f.consume('a');await f.messenger.send({targetBotId:'b',text:'new human task'},f.exec());assert.equal(f.prompts,1);
});
test('cancellation, invalid targets and oversized messages never enqueue',async()=>{
 const f=fixture();for(const args of [{targetBotId:'a',text:'self'},{targetBotId:'missing',text:'unknown'},{targetBotId:'b',text:'x'.repeat(6001)}])await assert.rejects(f.messenger.send(args,f.exec()));
 const c=new AbortController();c.abort();await assert.rejects(f.messenger.send({targetBotId:'b',text:'cancelled'},{...f.exec(),signal:c.signal}));assert.equal(f.prompts,0);
});
test('mailbox paginates complete messages within its text budget',async()=>{
 const f=fixture();for(let i=0;i<5;i++)await f.messenger.send({targetBotId:'b',text:String(i)+'x'.repeat(5990)},f.exec('a','call'+i));
 f.consume('b');const one=await f.messenger.messages({limit:20},f.exec('b'));assert.equal(one.messages.length,2);assert.equal(one.hasEarlier,true);
 const two=await f.messenger.messages({limit:20,beforeMessageId:one.nextBeforeMessageId},f.exec('b'));assert.equal(two.messages.length,2);
 const three=await f.messenger.messages({beforeMessageId:two.nextBeforeMessageId},f.exec('b'));assert.equal(three.messages.length,1);assert.equal(three.hasEarlier,false);
});
test('the chat shows a readable sender instead of protocol metadata; ordinary pasted text is untouched',()=>{
 const frame={id:'bm-'+'a'.repeat(32),kind:'request',from:{id:'a',name:'主 Bot',sessionId:'s-a'},to:{id:'b'},depth:1,text:'落地这个需求'};
 const raw={source:{kind:'user',rpcId:'yugang-bot:'+frame.id},content:[{type:'text',text:wireMessage(frame)}]};
 const conv=new Conv('s-b');conv.add({seq:1,time:1,type:'user/message',data:raw});assert.equal(conv.build()[0].text,'来自「主 Bot」的协作任务：\n落地这个需求');
 const ordinary=new Conv('s-b');ordinary.add({seq:1,time:1,type:'user/message',data:{...raw,source:{kind:'user',rpcId:'ordinary'}}});assert.equal(ordinary.build()[0].text,wireMessage(frame));
});

test('cold targets are created once; an existing conversation under a Bot subdirectory is retained',async()=>{
 const f=fixture();f.agents.delete('s-b');const sent=await f.messenger.send({targetBotId:'b',text:'cold'},f.exec());assert.equal(sent.accepted,true);
 f.agents.get('s-b').session.header.cwd='/BOT/b/documents';
 const next=await f.messenger.send({targetBotId:'b',text:'existing subdir'},f.exec('a','second'));assert.equal(next.accepted,true);assert.equal(f.prompts,2);
});
test('native admission is checked before enqueue and released after cancellation or failure',async()=>{
 const f=fixture(),ops=[];const c=new AbortController();
 const route=async(...args)=>{const r=await f.route(...args);const op=args[4]?.op;if(op){ops.push(op);if(op==='check')c.abort()}return r};
 const messenger=createBotMessenger(f.controller,route);await assert.rejects(messenger.send({targetBotId:'b',text:'cancel before queue'},{...f.exec(),signal:c.signal}));
 assert.deepEqual(ops,['begin','check','end']);assert.equal(f.prompts,0);
 const rejectBegin=async(...args)=>{if(args[4]?.op==='begin')throw Error('deleted target');return f.route(...args)};
 const deleted=createBotMessenger(f.controller,rejectBegin);f.agents.delete('s-b');await assert.rejects(deleted.send({targetBotId:'b',text:'no resurrection'},f.exec()),/deleted target/);assert.equal(f.agents.has('s-b'),false);
});

test('pending replies stay in the SDK queue until their real turn; polling cannot surface a second premature conclusion',async()=>{
 const f=fixture();await f.messenger.send({targetBotId:'b',text:'queued'},f.exec());
 const pending=await f.messenger.messages({},f.exec('b'));assert.equal(pending.messages.length,0);assert.equal(pending.pendingCount,1);
 f.consume('b');const delivered=await f.messenger.messages({},f.exec('b'));assert.equal(delivered.messages.length,1);assert.equal(delivered.pendingCount,0);
});

test('a lost response identity is bound to its original target session even if a different valid session is explicitly supplied',async()=>{
 const f=fixture();f.loseResponse();let id;try{await f.messenger.send({targetBotId:'b',text:'once only'},f.exec())}catch(e){id=e.message.match(/bm-[a-f0-9]{32}/)[0]}
 f.make('s-b-new');await assert.rejects(f.messenger.send({targetBotId:'b',text:'once only',messageId:id,targetSessionId:'s-b-new'},f.exec('a','retry')),/不能改投/);assert.equal(f.prompts,1);
});
test('a Bot can reply to a task in its registered older conversation, retaining one result identity',async()=>{
 const f=fixture(),task=await f.messenger.send({targetBotId:'b',text:'old task'},f.exec());f.consume('b');const current=f.make('s-b-new'),exec={...f.exec('b'),agent:current};
 const page=await f.messenger.messages({},exec);assert.equal(page.messages[0].conversationId,'s-b');
 const args={targetBotId:'a',replyTo:task.messageId,replyConversationId:page.messages[0].conversationId,text:'done'};const reply=await f.messenger.send(args,exec);
 const twice=await f.messenger.send({...args,replyConversationId:'s-b'},f.exec('b','another'));assert.equal(twice.duplicate,true);assert.equal(twice.messageId,reply.messageId);assert.equal(f.prompts,2);
 await assert.rejects(f.messenger.send({...args,replyConversationId:'s-c'},exec),/不属于/);
});
test('reading an older own task preserves the hop budget in this turn, including SDK replay, without poisoning the next human turn',async()=>{
 const f=fixture(),b=f.agents.get('s-b'),frame={id:'bm-'+'d'.repeat(32),kind:'request',from:{id:'a',name:'A',sessionId:'s-a'},to:{id:'b'},depth:4,text:'last-hop'};
 b.events.push({type:'user/message',seq:3,data:{source:{kind:'user',rpcId:'yugang-bot:'+frame.id},content:[{type:'text',text:wireMessage(frame)}]}});
 const current=f.make('s-b-new'),exec={...f.exec('b'),agent:current};const page=await f.messenger.messages({},exec);assert.equal(page.messages[0].depth,4);
 await assert.rejects(f.messenger.send({targetBotId:'c',text:'would be hop 5'},exec),/已达4层/);
 const resumed={...current,events:[...current.events,{type:'tool/call',seq:3,data:{name:'bots_messages',callId:'mail'}},{type:'tool/result',seq:4,data:{message:{toolCallId:'mail',content:[{type:'text',text:JSON.stringify(page)}]}}}]};
 f.agents.set('s-b-new',resumed);await assert.rejects(f.messenger.send({targetBotId:'c',text:'would be hop 5'},{...exec,agent:resumed}),/已达4层/);
 resumed.events.push({type:'user/message',seq:5,data:{source:{kind:'user',rpcId:'new-human'},content:[{type:'text',text:'new human instruction'}]}},{type:'turn/start',seq:6});
 await f.messenger.send({targetBotId:'c',text:'new human task'},{...exec,agent:resumed});assert.equal(f.prompts,1);
});
