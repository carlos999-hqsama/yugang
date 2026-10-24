import test from 'node:test';
import assert from 'node:assert/strict';
import {readDefaultModel,saveDefaultModel} from '../../android/app/src/main/assets/www/js/model-settings.js';
function fixture({conflict=false,staleCatalog=false}={}) {
 let selection={provider:'deepseek-official',model:'deepseek-flash',reasoningEffort:'high'},revision=3;const writes=[];
 const models=[{id:'deepseek-flash',reasoning:{efforts:[{id:'high'}],defaultEffort:'high'}},{id:'deepseek-pro'}];
 const call=async(method,args)=>{
  if(method==='settings/describe')return {writable:true,namespaces:[{ns:'agent-default-model',revision,value:{...selection}}]};
  if(method==='session/modelCatalog')return {default:{...selection,...(staleCatalog?{model:'deepseek-flash'}:{})},groups:[{id:'deepseek-official',models}]};
  if(method==='settings/mutate'){
   writes.push(args);if(conflict)throw Error('settings/conflict');assert.equal(args.expectedRevision,revision);
   for(const op of args.ops)if(op.op==='set')selection[op.path[0]]=op.value;else delete selection[op.path[0]];
   revision++;return {value:{...selection},revision};
  }
  throw Error('unexpected '+method);
 };return {call,writes};
}
test('model defaults persist through the existing revision-checked settings API and are confirmed by the actual catalog',async()=>{
 const f=fixture();const state=await readDefaultModel(f.call);const next=await saveDefaultModel(f.call,state,'deepseek-pro');
 assert.equal(next.catalog.default.model,'deepseek-pro');assert.equal(f.writes.length,1);
 assert.deepEqual(f.writes[0].ops,[{op:'set',path:['model'],value:'deepseek-pro'},{op:'unset',path:['reasoningEffort']}]);
 assert.equal(next.view.value.provider,'deepseek-official');
});
test('an unregistered model cannot mutate defaults',async()=>{const f=fixture();await assert.rejects(saveDefaultModel(f.call,await readDefaultModel(f.call),'made-up-model'),/未提供/);assert.equal(f.writes.length,0);});
test('a stale settings editor is refused without silently replaying its write',async()=>{const f=fixture({conflict:true});await assert.rejects(saveDefaultModel(f.call,await readDefaultModel(f.call),'deepseek-pro'),/conflict/);assert.equal(f.writes.length,1);});
test('saved profile metadata alone cannot report success when the runtime catalog did not apply the selection',async()=>{const f=fixture({staleCatalog:true});await assert.rejects(saveDefaultModel(f.call,await readDefaultModel(f.call),'deepseek-pro'),/尚未确认/);});
