// Test the actual filesystem code extracted from the built runtime, not a copy of the patch.
import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';import vm from 'node:vm';import assert from 'node:assert/strict';import {randomUUID} from 'node:crypto';
const source=await fs.readFile(process.argv[2],'utf8');
const begin=source.indexOf('async function writeFileAtomic(absolutePath, content, mode, signal, internals = {}, createIfAbsent) {');
const end=source.indexOf('\n/**',begin);assert(begin>=0&&end>begin);
class FsError extends Error{constructor(m,code){super(m);this.code=code;}}
const env={PD_FS_LOCAL_NO_HARDLINK_CREATE:'1'};
const scope={...fs,...path,FsError,randomUUID,process:{env,platform:'linux',pid:process.pid},throwIfAborted(s){if(s?.aborted)throw new FsError('aborted','FS_ABORTED')},isENOENT:e=>e.code==='ENOENT',isAbortError:e=>e.name==='AbortError',errorMessage:e=>e.message,removeStagingDirOrThrow:async(d,e)=>{await fs.rm(d,{recursive:true,force:true});throw e;},throwGuardedCreateFailure:async e=>{throw e;},copyFileDaclWin32:()=>{},replaceFileWin32:()=>{throw Error('unexpected Windows call')}};
vm.createContext(scope);vm.runInContext(source.slice(begin,end)+';this.publish=writeFileAtomic;',scope);
const dir=await fs.mkdtemp(path.join(os.tmpdir(),'pd-publication-'));
try{
 const fresh=path.join(dir,'new.txt');
 await assert.rejects(scope.publish(fresh,'new',undefined,undefined,{}, {displayPath:fresh}),e=>e.code==='FS_IO_ERROR');
 assert.deepEqual(await fs.readdir(dir),[],'unsupported Android create must fail before any file/staging side effect');
 const existing=path.join(dir,'existing.txt');await fs.writeFile(existing,'old');
 await scope.publish(existing,'updated',0o600);assert.equal(await fs.readFile(existing,'utf8'),'updated');
 assert.deepEqual(await fs.readdir(dir),['existing.txt'],'existing-file replacement must clean its staging');
 delete env.PD_FS_LOCAL_NO_HARDLINK_CREATE;
 await scope.publish(fresh,'ordinary POSIX create',undefined,undefined,{}, {displayPath:fresh});assert.equal(await fs.readFile(fresh,'utf8'),'ordinary POSIX create');
 const version=source.slice(source.indexOf('\tversionAfterWrite(after, target) {'),source.indexOf('\n};\n//#endregion',source.indexOf('\tversionAfterWrite(after, target) {')));
 const check=vm.runInContext('({'+version+'})',scope);
 assert.equal(check.versionAfterWrite({version:'observed'},{}),'observed');
 assert.throws(()=>check.versionAfterWrite(null,{displayPath:'synthetic.txt'}),e=>e.code==='FS_IO_ERROR');
 console.log('Actual runtime publication: Android no-side-effect rejection, existing-file atomic update, POSIX create and missing-publication failure passed');
}finally{await fs.rm(dir,{recursive:true,force:true});}
