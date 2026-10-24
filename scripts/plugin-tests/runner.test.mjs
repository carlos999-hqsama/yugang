import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {execute} from '../../android/tools/runtime/overlay/opt/pd/plugin-runner.mjs';
const bundle={manifest:{apiVersion:1,id:'runner-fixture',kind:'native',capabilities:[]},view:{type:'text',text:'Synthetic runner fixture'},
 code:"export async function run({inputs,state}){const count=(state.count||0)+1;return {state:{count},view:{type:'column',children:[{type:'title',text:'Fixture'},{type:'text',text:inputs.message||'echo'},{type:'text',text:'累计执行 '+count}]}}}"};
test('installed bundle executes real code and native-view/state result changes',async()=>{
 const result=await execute({bundle,action:'echo',inputs:{message:'on-phone'},state:{count:3}});
 assert.equal(result.state.count,4);assert.equal(result.view.children[1].text,'on-phone');
 const updated=structuredClone(bundle);updated.code=updated.code.replace('累计执行','更新后执行');
 const next=await execute({bundle:updated,action:'echo',state:result.state});
 assert.equal(next.state.count,5);assert.match(next.view.children[2].text,/更新后执行/);
});
test('owner plugin may call supported capabilities without per-capability grants',async()=>{
 const b=structuredClone(bundle);b.code="export async function run(_,sdk){const r=await sdk.call('app.call',{method:'fetch',args:{url:'https://example.test'}});return {view:{type:'text',text:r.status}}}";
 const result=await execute({bundle:b,action:'send'},(id,name)=>{assert.equal(name,'app.call');return {status:'accepted'}});
 assert.equal(result.view.text,'accepted');
});
test('SDK routes declared capability with plugin identity',async()=>{
 const b=structuredClone(bundle);b.manifest.capabilities=['plugin.media'];b.code="export async function run(_,sdk){const data=await sdk.call('plugin.media',{threadId:'synthetic-thread-000',op:'clear'});return {view:{type:'text',text:data.ok?'cleared':'failed'}}}";
 let called;
 const r=await execute({bundle:b,action:'read'},(id,name,args)=>{called={id,name,args};return {ok:true}});
 assert.deepEqual(called,{id:'runner-fixture',name:'plugin.media',args:{threadId:'synthetic-thread-000',op:'clear'}});assert.equal(r.view.text,'cleared');
});
test('Node CLI keeps console output separate from JSON response',()=>{
 const b=structuredClone(bundle);b.code="export async function run(){console.log('diagnostic');return {view:{type:'text',text:'ok'}}}";
 const child=spawnSync(process.execPath,['android/tools/runtime/overlay/opt/pd/plugin-runner.mjs'],{input:JSON.stringify({bundle:b,action:'go'}),encoding:'utf8'});
 assert.equal(child.status,0);assert.equal(JSON.parse(child.stdout).value.view.text,'ok');assert.match(child.stderr,/diagnostic/);
});
test('invalid module and oversized result fail',async()=>{
 const b=structuredClone(bundle);b.code='export const missing=true';await assert.rejects(execute({bundle:b}),/export run/);
 b.code="export async function run(){return {view:{type:'text',text:'x'.repeat(140000)}}}";await assert.rejects(execute({bundle:b}),/too large/);
});

test('APK source has no bundled business plugins',async()=>{
 const files=await fs.readdir(new URL('../../android/app/src/main/assets/plugins/',import.meta.url)).catch(e=>{if(e.code==='ENOENT')return [];throw e;});
 assert.equal(files.filter(x=>x.endsWith('.json')).length,0);
});
