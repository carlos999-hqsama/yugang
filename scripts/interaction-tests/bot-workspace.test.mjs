import test from 'node:test';
import assert from 'node:assert/strict';
import {loadModule,synthetic} from './loader.mjs';
test('actual DSH API carries Bot directory to workspace creation',async()=>{
 const calls=[];
 const {ns}=await loadModule('dsh.js',{stubs:{'./native.js':c=>synthetic(c,{native:{http:async(...args)=>{calls.push(args);return {status:200,text:JSON.stringify({result:{ok:true,value:{workspace:{}}}})}}},ws:()=>{}}),'./util.js':c=>synthetic(c,{uuid:()=> 'synthetic-request'})}});
 await ns.api.createWorkspace('/BOT/11111111-1111-4111-8111-111111111111');
 assert.equal(calls[0][3].payload.args.request.path,'/BOT/11111111-1111-4111-8111-111111111111');
 await ns.api.createWorkspace();assert.equal(calls[1][3].payload.args.request.path,'/BOT/default');
});
test('unmapped child is visible inside its Bot; pinning does not duplicate a robot',async()=>{
 const {ns}=await loadModule('bot-list.js',{stubs:{'./util.js':c=>synthetic(c,{esc:x=>String(x)}),'./icons.js':c=>synthetic(c,{S:{more:'menu'}})}});
 const registry={defaultBotId:'default',bots:[{id:'default',workspace:'/BOT/default',sessionIds:[],name:'默认',pinned:false},{id:'a',workspace:'/BOT/a',sessionIds:['old-a'],name:'甲',pinned:true}]};
 const sessions=new Map([['child',{sessionId:'child',cwd:'/BOT/a',updatedAt:20}],['old-a',{sessionId:'old-a',cwd:'/BOT/default',updatedAt:10}],['old',{sessionId:'old',cwd:'/BOT/default'}]]);
 assert.equal(ns.ownerOfSession(registry,'child',sessions.get('child')),'a');
 assert.deepEqual(Array.from(ns.sessionsForBot(registry,'a',sessions),s=>s.sessionId),['child','old-a']);
 const html=ns.botListHtml({registry,selected:'a',sessions});assert.equal((html.match(/data-bot="a"/g)||[]).length,1);assert.ok(!html.includes('data-bot="child"'));
});

test('retired lowercase directory does not claim another Bot',async()=>{
 const {ns}=await loadModule('bot-list.js',{stubs:{'./util.js':c=>synthetic(c,{esc:x=>String(x)}),'./icons.js':c=>synthetic(c,{S:{more:'menu'}})}});
 const registry={defaultBotId:'default',bots:[{id:'default',workspace:'/BOT/default',sessionIds:[]},{id:'a',workspace:'/BOT/a',sessionIds:[]}]};
 assert.equal(ns.ownerOfSession(registry,'child',{cwd:'/bots/a/project'}),'default');
});
