import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
async function load(t){
 const directory=await mkdtemp(join(tmpdir(),'yugang-host-sdk-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 await writeFile(join(directory,'package.json'),'{"type":"module"}');await writeFile(join(directory,'util.js'),'export const uuid=()=>"test-id";');
 await writeFile(join(directory,'native.js'),await readFile('android/app/src/main/assets/plugin-framework/v1/js/native.js','utf8'));
 const events=new EventTarget(),calls=[];const page={PDNative:{call(method,id,args){calls.push({method,id,args:JSON.parse(args)});}},addEventListener:events.addEventListener.bind(events),removeEventListener:events.removeEventListener.bind(events),dispatchEvent:events.dispatchEvent.bind(events)};globalThis.window=page;
 const {native}=await import(pathToFileURL(join(directory,'native.js')).href);t.after(()=>{page.dispatchEvent(new Event('pagehide'));if(globalThis.window===page)delete globalThis.window;});
 return {page,native,calls};
}
test('generic services route through the host with capability identity',async t=>{const x=await load(t);const p=x.native.files({op:'stat',handle:'opaque'});assert.deepEqual(x.calls[0].args,{capability:'plugin.files',arguments:{op:'stat',handle:'opaque'}});x.page.__pd_cb(x.calls[0].id,JSON.stringify({value:{size:12}}));assert.equal((await p).size,12);});
test('unloading rejects outstanding ordinary calls and ignores late results',async t=>{const x=await load(t);const p=x.native.capabilities();const rejected=assert.rejects(p,e=>e.code==='cancelled');x.page.dispatchEvent(new Event('pd-plugin-dispose'));await rejected;x.page.__pd_cb(x.calls[0].id,JSON.stringify({value:{}}));});
test('malformed bridge replies reject the owning promise',async t=>{const x=await load(t);const p=x.native.capabilities();const rejected=assert.rejects(p,e=>e.code==='invalid_response');x.page.__pd_cb(x.calls[0].id,'{invalid');await rejected;});
test('outstanding calls are bounded and released together',async t=>{const x=await load(t);const waiting=Array.from({length:64},()=>x.native.capabilities().catch(e=>e.code));await assert.rejects(x.native.capabilities(),e=>e.code==='busy');x.page.dispatchEvent(new Event('pagehide'));assert.deepEqual(new Set(await Promise.all(waiting)),new Set(['cancelled']));});
