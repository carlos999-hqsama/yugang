import test from 'node:test';
import assert from 'node:assert/strict';
import {loadModule} from './loader.mjs';
test('本地产物文件名中的空格、问号和井号按路径往返，不变成查询或片段',async()=>{
 const {ns}=await loadModule('native.js',{globals:{window:{PDNative:{}},location:{origin:'https://fixture.invalid'}}});
 const path='/BOT/default/documents/示例 #1?.png';const url=ns.fileUrl(path);
 assert.equal(new URL(url).search,'');assert.equal(new URL(url).hash,'');
 assert.equal(ns.fileFromUrl(url),path);assert.equal(ns.fileFromUrl(ns.FILE_BASE+'evil/test.png'),null);
 assert.equal(ns.fileFromUrl(ns.FILE_BASE+'/%ZZ'),null);
});


test('Markdown 链接与图片中的尖括号不泄漏成原文，也不执行危险链接或代码',async()=>{
 const {ns}=await loadModule('util.js');
 const path='/BOT/test/documents/窄屏 #1?.png';
 const html=ns.md('[窄屏截图](<'+path+'>)\n\n![预览](<'+path+'>)\n\n`[代码](<'+path+'>)`\n\n[危险](javascript:alert)\n\n[转义](<documents/a" onmouseover="bad.txt>)',{image:p=>({path:p,url:'https://fixture.invalid/image'})});
 assert.ok(html.includes('data-href="'+path+'"'));assert.ok(html.includes('class="chat-image"'));
 assert.ok(html.includes('<code>[代码](&lt;'));assert.ok(!html.includes('data-href="javascript:'));
 assert.ok(!html.includes(' onmouseover="bad'));assert.ok(html.includes('&quot; onmouseover=&quot;'));
});


test('链接目的地中的代码占位符不进入生成的 HTML 属性',async()=>{
 const {ns}=await loadModule('util.js');
 const html=ns.md('[文件](/BOT/test/documents/`a.txt`)');
 assert.ok(!html.includes('data-href='));assert.ok(html.includes('<code'));
});
