import test from 'node:test';
import assert from 'node:assert/strict';
import {loadModule,synthetic} from './loader.mjs';
test('live voice forwarding preserves order before native consent filtering, plain reply text only, with no snapshots or tool/reasoning data',async()=>{
 const calls=[];
 const {ns}=await loadModule('agent-voice.js',{stubs:{'./native.js':c=>synthetic(c,{native:{call:async(...args)=>{calls.push(args);return true}}})}});
 ns.voiceScope('a','sa');ns.voiceFrame('a','sa',{type:'start',attemptId:'r'});assert.equal(calls.length,2);

 for(const frame of [{type:'start',attemptId:'r'},{type:'chunk',attemptId:'r',chunk:{type:'reasoning-delta',text:'private reasoning'}},{type:'chunk',attemptId:'r',chunk:{type:'tool-call-delta',argumentsDelta:'private arguments'}},{type:'chunk',attemptId:'r',chunk:{type:'text-delta',text:'visible reply'}},{type:'end',attemptId:'r'}])ns.voiceFrame('a','sa',frame);
 assert.deepEqual(calls.slice(2).map(c=>c[1].frame.type),['start','text','end']);assert.equal(JSON.stringify(calls).includes('private'),false);
 ns.voiceScope('b','sb');ns.voiceFrame('b','sb',{type:'chunk',attemptId:'new',chunk:{type:'text-delta',text:'no automatic subscription'}});assert.equal(calls.length,7);
});
