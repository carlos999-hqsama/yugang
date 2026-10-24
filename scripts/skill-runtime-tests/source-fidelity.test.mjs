import test from 'node:test';import assert from 'node:assert/strict';
import {applySourceFidelity,SOURCE_CONTEXT,REPORTING_CONTRACT} from '../../android/tools/runtime/overlay/opt/pd/source-fidelity.mjs';
import {pathToFileURL} from 'node:url';
const vendor=process.env.PD_DSH_VENDOR_ROOT||new URL('../../.rtbuild/dl/dsh',import.meta.url).pathname;
const {renderContextSections}=await import(pathToFileURL(vendor+'/node_modules/@deepseek-ai/dsh-system-prompt/lib/index.js').href);
function setup(){const hooks=new Map();applySourceFidelity({on:(key,fn)=>hooks.set(key,fn)});return hooks.get('system-prompt/assemble')}
const assembly=()=>({sections:[{name:'voice'}],contexts:[{name:'policy',text:'Existing permission',order:110}],tools:[{name:'read'}],variables:{}});
test('request assembly preserves tools, system rules, input and existing runtime policy',async()=>{
 const hook=setup(),a=assembly(),before=structuredClone(a),log=[{type:'turn/start',data:{turn:1}}];
 const result=await hook(a,{scope:{session:{log}}},async()=>a);
 assert.deepEqual(a,before);assert.equal(result.tools,a.tools);assert.equal(result.sections,a.sections);assert.equal(result.contexts[0],a.contexts[0]);assert.equal(result.contexts.at(-1).name,SOURCE_CONTEXT);
 assert.equal(log.length,1);const rendered=renderContextSections(result);assert.equal(rendered.length,2);assert.ok(rendered[1].text.includes(REPORTING_CONTRACT));
});
test('unchanged steps retain the same reminder; canonical next turn creates one fresh snapshot',async()=>{
 const hook=setup(),a=assembly(),log=[{type:'turn/start',data:{turn:8}}],ctx={scope:{session:{log}}};
 const first=await hook(a,ctx,async()=>a),same=await hook(a,ctx,async()=>a);assert.deepEqual(first,same);assert.ok(first.contexts.at(-1).text.includes('轮次 8'));
 log.push({type:'turn/end',data:{turn:8,reason:{kind:'completed'}}},{type:'turn/start',data:{turn:9}});
 const next=await hook(a,ctx,async()=>a);assert.notEqual(next.contexts.at(-1).text,first.contexts.at(-1).text);assert.equal(next.contexts.length,2);assert.ok(next.contexts.at(-1).text.includes('轮次 9'));
});
test('no Session or no running turn does not add a model reminder',async()=>{
 const hook=setup(),a=assembly();assert.equal(await hook(a,{},async()=>a),a);assert.equal(await hook(a,{scope:{session:{log:[]}}},async()=>a),a);
});

test('completed turns do not provide a stale current-turn contract; own context is replaced by name',async()=>{
 const hook=setup(),a=assembly(),log=[{type:'turn/start',data:{turn:4}}],ctx={scope:{session:{log}}};
 const out=await hook(a,ctx,async()=>a);
 const repeated=await hook(out,ctx,async()=>out);assert.equal(repeated.contexts.filter(s=>s.name===SOURCE_CONTEXT).length,1);
 log.push({type:'turn/end',data:{turn:4,reason:{kind:'completed'}}});
 assert.equal(await hook(a,ctx,async()=>a),a);const ended=await hook(out,ctx,async()=>out);assert.deepEqual(ended.contexts,a.contexts);
});
