import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
async function load(t,{android=true}={}) {
 const directory=await mkdtemp(join(tmpdir(),'yugang-core-bridge-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 await writeFile(join(directory,'package.json'),'{"type":"module"}');
 await writeFile(join(directory,'util.js'),'export const uuid=()=>"fixture-id";');
 await writeFile(join(directory,'native.js'),await readFile('android/app/src/main/assets/www/js/native.js','utf8'));
 const events=new EventTarget(),calls=[],timers=new Set();
 const page={addEventListener:events.addEventListener.bind(events),removeEventListener:events.removeEventListener.bind(events),dispatchEvent:events.dispatchEvent.bind(events)};
 if(android)page.PDNative={call(method,id,args){calls.push({method,id,args:JSON.parse(args)})}};
 const previous={window:globalThis.window,location:globalThis.location,fetch:globalThis.fetch,setTimeout:globalThis.setTimeout,clearTimeout:globalThis.clearTimeout};
 globalThis.window=page;globalThis.location={origin:'http://fixture.local'};
 globalThis.setTimeout=(callback,delay)=>{const timer=previous.setTimeout(callback,delay);timers.add(timer);return timer};
 globalThis.clearTimeout=timer=>{timers.delete(timer);previous.clearTimeout(timer)};
 globalThis.fetch=async(url,request)=>{calls.push({url,args:JSON.parse(request.body)});return{json:async()=>({value:{configured:false}})}};
 t.after(()=>{page.dispatchEvent(new Event('pagehide'));for(const timer of timers)previous.clearTimeout(timer);Object.assign(globalThis,previous)});
 const {native,isAndroid,on}=await import(pathToFileURL(join(directory,'native.js')).href);
 return {page,native,isAndroid,on,calls,timers};
}
test('core startup getConfig reaches the real native transport and clears its deadline',async t=>{
 const x=await load(t);assert.equal(x.isAndroid,true);const result=x.native.getConfig();
 assert.equal(x.calls[0].method,'getConfig');assert.equal(x.timers.size,1);
 x.page.__pd_cb(x.calls[0].id,JSON.stringify({value:{configured:false}}));
 assert.deepEqual(await result,{configured:false});assert.equal(x.timers.size,0);
});
test('native request IDs are distinct across actual core calls',async t=>{
 const x=await load(t);const a=x.native.getConfig(),b=x.native.capabilities();
 assert.notEqual(x.calls[0].id,x.calls[1].id);
 x.page.__pd_cb(x.calls[0].id,{value:1});x.page.__pd_cb(x.calls[1].id,{value:2});assert.deepEqual(await Promise.all([a,b]),[1,2]);
});
test('core rejects malformed replies instead of abandoning the startup promise',async t=>{
 const x=await load(t);const p=x.native.getConfig();const rejected=assert.rejects(p,e=>e.code==='invalid_response');
 assert.doesNotThrow(()=>x.page.__pd_cb(x.calls[0].id,'{broken'));await rejected;assert.equal(x.timers.size,0);
});
test('core cleanup rejects outstanding calls and ignores late replies',async t=>{
 const x=await load(t);const p=x.native.getConfig();const rejected=assert.rejects(p,e=>e.code==='cancelled');
 x.page.dispatchEvent(new Event('pagehide'));await rejected;x.page.__pd_cb(x.calls[0].id,{value:0});assert.equal(x.timers.size,0);
});
test('core desktop startup preserves the existing development transport',async t=>{
 const x=await load(t,{android:false});assert.equal(x.isAndroid,false);assert.deepEqual(await x.native.getConfig(),{configured:false});
 assert.equal(x.calls[0].url,'/__dev/call');assert.equal(x.calls[0].args.method,'getConfig');
});

test('malformed push does not break later valid event delivery',async t=>{
 const x=await load(t);const received=[];x.on('runtime',v=>received.push(v));
 assert.doesNotThrow(()=>x.page.__pd_ev('runtime','{broken'));x.page.__pd_ev('runtime',{ready:true});assert.deepEqual(received,[{ready:true}]);
});
test('business errors settle the core call and release its timer',async t=>{
 const x=await load(t);const p=x.native.getConfig();const rejected=assert.rejects(p,e=>e.code==='fixture_error');
 x.page.__pd_cb(x.calls[0].id,{error:'synthetic failure',code:'fixture_error'});await rejected;assert.equal(x.timers.size,0);
});
test('core pending budget rejects excess calls and releases all on page close',async t=>{
 const x=await load(t);const waiting=Array.from({length:64},()=>x.native.getConfig().catch(e=>e.code));
 await assert.rejects(x.native.getConfig(),e=>e.code==='busy');x.page.dispatchEvent(new Event('pagehide'));
 assert.deepEqual(new Set(await Promise.all(waiting)),new Set(['cancelled']));assert.equal(x.timers.size,0);
});
