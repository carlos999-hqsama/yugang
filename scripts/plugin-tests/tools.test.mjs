import {test} from 'node:test';
import assert from 'node:assert/strict';
import {applyPluginTools} from '../../android/tools/runtime/overlay/opt/pd/plugin-tools.mjs';
import {hostRuntimeClient} from '../../android/tools/runtime/overlay/opt/pd/app-host-bridge.mjs';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

test('core conversation can manage and invoke own plugin without a second permission gate',async()=>{
 const tools=new Map();const calls=[];const original=hostRuntimeClient.request;
 hostRuntimeClient.request=async(...args)=>{calls.push(args);return {ok:true};};
 try{
  applyPluginTools({systemPrompt:{section(){}},tools:{register(t){tools.set(t.name,t)}},get(){throw Error('must not ask for another permission gate')}});
  assert.equal(tools.size,8);
  const exec={agent:{session:{}},signal:undefined};
  await tools.get('phone_plugins_install').execute({packageJson:JSON.stringify({manifest:{id:'my-plugin'}})},exec);
  await tools.get('phone_plugins_call').execute({id:'my-plugin',actionName:'refresh',inputsJson:'{"topic":"selected"}'},exec);
  assert.equal(calls[0][0],'plugin_manage');assert.equal(calls[0][1].request.action,'install');
  assert.equal(calls[1][1].request.action,'invoke');assert.equal(calls[1][1].request.id,'my-plugin');
  assert.deepEqual(JSON.parse(calls[1][1].request.inputsJson),{topic:'selected'});
 }finally{hostRuntimeClient.request=original;}
});

test('preview tool talks to the dedicated preview channel and keeps the screenshot deliverable',async()=>{
 const tools=new Map();const calls=[];const original=hostRuntimeClient.request;
 const png=Buffer.from('fake-png-bytes').toString('base64');
 hostRuntimeClient.request=async(...args)=>{calls.push(args);return {ok:true,session:'s1',plugin:'demo-plugin',state:'ready',result:{capture:'ok',png:{mediaType:'image/png',bytes:13,base64:png},errors:[]}};};
 const workspace=await mkdtemp(join(tmpdir(),'pd-preview-'));
 try{
  applyPluginTools({systemPrompt:{section(){}},tools:{register(t){tools.set(t.name,t)}},get(name){assert.equal(name,'attachments');return {saveImage:async input=>{assert.equal(input.data.toString(),'fake-png-bytes');return {attachmentId:'synthetic-image',mediaType:'image/png',bytes:14,width:10,height:10}}}}});
  const exec={agent:{session:{[Symbol.for('pd.bot.workspace')]:workspace}},signal:undefined};
  const result=await tools.get('phone_plugins_preview').execute({op:'click',id:'demo-plugin',selector:'#refresh'},exec);
  assert.equal(calls[0][0],'plugin_preview');
  assert.equal(calls[0][1].request.op,'click');
  assert.equal(calls[0][1].request.selector,'#refresh');
  assert.equal(calls[0][1].request.id,'demo-plugin');
  // The screenshot stays inside the tool result so the model receives an image block.
  assert.equal(result.result.png.base64,undefined);assert.equal(result.image.attachmentId,'synthetic-image');
  // The screenshot is also persisted as a bot artifact, not only as a path in the text.
  assert.ok(result.artifact.startsWith(join(workspace,'documents')));
  assert.equal((await readFile(result.artifact)).toString('utf8'),'fake-png-bytes');
  const blocks=tools.get('phone_plugins_preview').output.render({},result);
  assert.ok(blocks.some(b=>b.type==='image'&&b.attachment?.mediaType==='image/png'&&b.attachment.attachmentId==='synthetic-image'));
  assert.ok(blocks[0].text.includes('demo-plugin'));
 }finally{hostRuntimeClient.request=original;await rm(workspace,{recursive:true,force:true});}
});


test('flat frames are reported without creating a misleading image attachment',async()=>{
 const tools=new Map();const original=hostRuntimeClient.request;
 hostRuntimeClient.request=async()=>({ok:true,plugin:'test',state:'ready',result:{capture:'flat',captureReason:'no rendered frame',png:{base64:'aA=='}}});
 try {
  applyPluginTools({systemPrompt:{section(){}},tools:{register:t=>tools.set(t.name,t)},get(){throw Error('must not store flat image')}});
  const result=await tools.get('phone_plugins_preview').execute({op:'snapshot'},{agent:{session:{[Symbol.for('pd.bot.workspace')]:'/BOT/synthetic'}},signal:undefined});
  assert.equal(result.image,undefined);assert.equal(result.artifact,undefined);assert.equal(result.result.png.base64,undefined);
  assert.match(result.imageError,/no rendered frame/);
 }finally{hostRuntimeClient.request=original;}
});


test('preview rendered text preserves requested pages beyond 900 characters',async()=>{
 const tools=new Map();const original=hostRuntimeClient.request;const text='可读文本'.repeat(500);
 hostRuntimeClient.request=async()=>({ok:true,session:'s',plugin:'draft-demo',state:'ready',result:{text,textOffset:25000,textLimit:2000,textLength:50000,truncated:true,nextOffset:27000}});
 try{
  applyPluginTools({systemPrompt:{section(){}},tools:{register:t=>tools.set(t.name,t)}});
  const tool=tools.get('phone_plugins_preview');const result=await tool.execute({op:'snapshot',selector:'#article',offset:25000,limit:2000},{agent:{session:{}},signal:undefined});
  const summary=tool.output.render({},result)[0].text;assert.ok(summary.includes(text));assert.ok(summary.includes('nextOffset=27000'));assert.ok(!summary.includes('未打开'));
 }finally{hostRuntimeClient.request=original;}
});
