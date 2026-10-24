import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
const vendor=process.env.PD_DSH_VENDOR_ROOT;
assert.ok(vendor,'PD_DSH_VENDOR_ROOT must point to the pinned public dependency tree');
const requireFromOffice=createRequire(vendor+'/node_modules/@deepseek-ai/libreoffice-kit/package.json');
const modulePath=requireFromOffice.resolve('fflate');
test('Office ZIP parser rejects a missing ZIP64 extra field without blocking',()=>{
 const code=`
const {zipSync,unzipSync}=require(process.argv[1]);
const normal=unzipSync(zipSync({'ok.txt':Uint8Array.of(79,75)}));
if(normal['ok.txt'][0]!==79)throw new Error('control did not work');
const b=new Uint8Array(176),v=new DataView(b.buffer);
v.setUint32(0,0x04034b50,true);v.setUint16(26,1,true);b[30]=97;
v.setUint32(31,0x02014b50,true);v.setUint32(51,0xffffffff,true);v.setUint16(59,1,true);b[77]=97;
v.setUint32(78,0x06064b50,true);v.setUint32(110,1,true);v.setUint32(126,31,true);
v.setUint32(134,0x07064b50,true);v.setUint32(142,78,true);
v.setUint32(154,0x06054b50,true);v.setUint16(162,65535,true);v.setUint16(164,65535,true);v.setUint32(166,47,true);v.setUint32(170,0xffffffff,true);
console.log('ZIP64_INPUT_READY');
try{unzipSync(b);throw new Error('malformed ZIP accepted');}
catch(e){if(e.message==='malformed ZIP accepted')throw e;console.log('ZIP64_REJECTED');}
`;
 const run=spawnSync(process.execPath,['-e',code,modulePath],{encoding:'utf8',timeout:2000,env:{PATH:process.env.PATH}});
 assert.match(run.stdout||'',/ZIP64_INPUT_READY/,'the malformed input must be prepared after a valid ZIP control');
 assert.ifError(run.error);
 assert.equal(run.status,0,run.stderr);
 assert.match(run.stdout,/ZIP64_REJECTED/);
});
