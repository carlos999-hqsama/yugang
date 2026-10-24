import test from 'node:test';import assert from 'node:assert/strict';
function setup({delayConsent=false}={}){
 const window=new EventTarget(),document=new EventTarget(),calls=[];document.visibilityState='visible';let approve;
 window.PDNative={call(method,id,json){const a=JSON.parse(json);calls.push({method,...a});let value;
  if(a.op==='subscribe'){if(delayConsent){approve=()=>window.__pd_cb(id,JSON.stringify({value:{id:a.requestId,clock:'elapsedRealtimeMs'}}));return}value={id:a.requestId,clock:'elapsedRealtimeMs'}}
  else if(a.op==='poll')value={events:[{type:'token',text:'合成字',index:0,at:100},{type:'sentence',text:'合成字。',at:120},{type:'end',at:150}]};else value={ok:true};
  queueMicrotask(()=>window.__pd_cb(id,JSON.stringify({value})));
 }};globalThis.window=window;globalThis.document=document;
 return {window,document,calls,approve:()=>approve()};
}
test('voice callbacks use native events and cancel on page hide',async()=>{
 const f=setup(),events=[];const{native}=await import('../../android/app/src/main/assets/plugin-framework/v1/js/native.js?voice=first');
 const h=native.voice({op:'subscribe',onToken:e=>events.push(e),onSentence:e=>events.push(e),onEnd:e=>events.push(e)});await h.ready;await new Promise(r=>setImmediate(r));
 assert.deepEqual(events.map(e=>e.at),[100,120,150]);assert.equal(f.calls[0].method,'pluginVoice');f.window.dispatchEvent(new Event('pd-plugin-hidden'));await new Promise(r=>setImmediate(r));assert.equal(f.calls.at(-1).op,'unsubscribe');
 const n=f.calls.length;await new Promise(r=>setTimeout(r,100));assert.equal(f.calls.length,n);
});
test('hiding while consent is pending cannot create a late live subscription',async()=>{
 const f=setup({delayConsent:true});const{native}=await import('../../android/app/src/main/assets/plugin-framework/v1/js/native.js?voice=pending');
 const h=native.voice({op:'subscribe'});f.window.dispatchEvent(new Event('pd-plugin-hidden'));f.approve();await h.ready;await new Promise(r=>setImmediate(r));assert.deepEqual(f.calls.map(c=>c.op),['subscribe','unsubscribe']);
});
