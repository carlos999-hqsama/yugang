import test from 'node:test';
import assert from 'node:assert/strict';
import {loadModule,synthetic} from './loader.mjs';

test('历史图片通过所属会话附件接口读取，不推导本地文件路径',async()=>{
 const calls=[];
 const attachment={attachmentId:'sha256:'+'a'.repeat(64),mediaType:'image/png',width:1,height:1,bytes:3};
 const {ns}=await loadModule('dsh.js',{stubs:{
  './native.js':c=>synthetic(c,{native:{http:async(...args)=>{calls.push(args);return {status:200,text:JSON.stringify({result:{ok:true,value:{attachment,data:'YWJj'}}})};}},ws:()=>{}}),
  './util.js':c=>synthetic(c,{uuid:()=> 'image-read-request'}),
 }});
 const result=await ns.api.attachment('session-image',attachment.attachmentId);
 assert.equal(result.attachment.attachmentId,attachment.attachmentId);
 assert.equal(result.data,'YWJj');
 assert.equal(calls.length,1);
 assert.equal(calls[0][2],'/api/session/attachment');
 assert.equal(JSON.stringify(calls[0][3].payload.args),JSON.stringify({request:{sessionId:'session-image',attachmentId:attachment.attachmentId}}));
});
