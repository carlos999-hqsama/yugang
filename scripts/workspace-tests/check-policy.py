"""Apply phone transforms to actual pinned public modules, then exercise their JS APIs."""
import importlib.util, os, pathlib, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('fixed_workspace',root/'android/tools/runtime/fixed_workspace.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
vendor=pathlib.Path(os.environ['PD_DSH_VENDOR_ROOT'])
names=['dsh-compaction-basic','dsh-sandbox-policy','dsh-user-approval','dsh-permission-presets','dsh-api-workspace-controller','dsh-api-session-controller','dsh-skill-filesystem','dsh-tool-skill','dsh-agent-instructions','dsh-agent-loop','dsh-subagent','dsh-tool-bash','dsh-tool-fs','dsh-tool-fs-search','dsh-tools','dsh-tool-present']
with tempfile.TemporaryDirectory(prefix='pd-fixed-workspace-') as temp:
    target=pathlib.Path(temp);(target/'node_modules').symlink_to(vendor/'node_modules',target_is_directory=True)
    for name in names:
        rel=f'node_modules/@deepseek-ai/{name}/lib/index.js'
        source=vendor.joinpath(rel).read_text();patched=module.patch_vendor(rel,source)
        assert patched != source
        if name == 'dsh-compaction-basic': patched += '\nexport {phoneBotCompactionPolicy,resolveConfig,resolveCompactSpec};\n'
        if name == 'dsh-api-workspace-controller': patched += '\nexport {WorkspaceCommands};\n'
        if name == 'dsh-api-session-controller': patched += '\nexport {SessionCommandController};\n'
        if name == 'dsh-tool-bash': patched += '\nexport {resolveWorkdir};\n'
        if name == 'dsh-tool-fs': patched += '\nexport {sessionCwd};\n'
        if name == 'dsh-tool-fs-search': patched += '\nexport function setTestRgPath(p) { rgPathPromise = Promise.resolve(p); }\n'
        path=target/(name+'.mjs');path.write_text(patched)
        subprocess.run(['node','--check',str(path)],check=True,capture_output=True)
        try:module.patch_vendor(rel,source.replace('class','class_changed',1).replace('this.defaultMode = config.mode;','changed'))
        except ValueError:pass
    test='''
import assert from 'node:assert/strict';
import {phoneBotCompactionPolicy,resolveConfig,resolveCompactSpec} from './dsh-compaction-basic.mjs';
import {SandboxPolicyService,setSandboxMode} from './dsh-sandbox-policy.mjs';
import {ApprovalService,setApprovalPolicy} from './dsh-user-approval.mjs';
import {PermissionPresetService} from './dsh-permission-presets.mjs';
import {WorkspaceCommands,WorkspaceController} from './dsh-api-workspace-controller.mjs';
import {SessionCommandController} from './dsh-api-session-controller.mjs';
import {FileSystemSkillProvider} from './dsh-skill-filesystem.mjs';
import {resolveWorkdir} from './dsh-tool-bash.mjs';
import {sessionCwd} from './dsh-tool-fs.mjs';
import {runRipgrep,setTestRgPath} from './dsh-tool-fs-search.mjs';
import {apply as applyPresent} from './dsh-tool-present.mjs';
import {validateJsonSchemaValue} from './dsh-tools.mjs';
const compactConfig=resolveConfig({headroomTokens:100,maxTokens:100});
const targetModel={provider:'test',model:'synthetic'};
const ca={[Symbol.for('pd.bot.compactionThreshold')]:.5},cb={[Symbol.for('pd.bot.compactionThreshold')]:.9};
assert.equal(resolveCompactSpec(phoneBotCompactionPolicy(compactConfig,targetModel,ca),10000,100).thresholdTokens,5000);
assert.equal(resolveCompactSpec(phoneBotCompactionPolicy(compactConfig,targetModel,cb),10000,100).thresholdTokens,9000);
assert.equal(phoneBotCompactionPolicy(compactConfig,targetModel,{}).thresholdRatio,.8);
assert.equal(compactConfig.thresholdRatio,.8);
const session={id:'synthetic',header:{cwd:'/outside'},append(){throw Error('must not mutate')}};
const policy=SandboxPolicyService.prototype.resolve.call({defaultMode:'read-only',workspaceRoot:'/outside',overrideOf:()=> 'read-only'},{session,mode:'workspace-write'});
assert.deepEqual(policy,{mode:'danger-full-access',workspaceRoot:'/outside',sessionId:'synthetic'});
session[Symbol.for('pd.bot.workspace')]='/BOT/default';
assert.equal(SandboxPolicyService.prototype.resolve.call({defaultMode:'read-only',workspaceRoot:'/outside'},{session}).workspaceRoot,'/BOT/default');
assert.equal(session.header.cwd,'/outside');
assert.equal(ApprovalService.prototype.effectivePolicy.call({config:{policy:'ask'},overrideOf:()=> 'ask'},session),'never');
assert.throws(()=>setSandboxMode(session,'read-only'),/fixed/);
assert.throws(()=>setApprovalPolicy(session,'ask'),/fixed/);
assert.throws(()=>PermissionPresetService.prototype.apply.call({},session,'read-only',()=>{}),/fixed/);
assert.deepEqual(Object.getOwnPropertyDescriptor(PermissionPresetService.prototype,'names').get.call({presets:{'read-only':{}}}),['danger-full-access']);
let seen;
const registry={resolveByPath:async p=>{seen=p;return {id:'fixed',path:p,title:'workspace',sessionIds:[]}}, initializeDefault:async callback=>{seen=await callback();return {id:'fixed',path:seen,title:'workspace',sessionIds:[]}}};
assert.throws(()=>new WorkspaceCommands({workspaceRegistry:registry}).create({path:'/outside'}),/Unknown/);
await new WorkspaceCommands({workspaceRegistry:registry}).create({path:'/BOT/default'});assert.equal(seen,'/BOT/default');
await WorkspaceController.prototype.initializeDefault.call({ctx:{workspaceRegistry:registry},config:{}},new AbortController().signal);assert.equal(seen,'/BOT/default');
const commands=new SessionCommandController({workspaceRegistry:{get:()=>undefined}}, {ensureSession:async(id,cwd)=>{seen=cwd;return {session:{}}},presetForSession:()=>undefined},'/outside');
await assert.rejects(commands.create({cwd:'/outside'}),/Unknown/);
await commands.create({cwd:'/BOT/11111111-1111-4111-8111-111111111111'});assert.equal(seen,'/BOT/11111111-1111-4111-8111-111111111111');
await commands.create({cwd:'/BOT/11111111-1111-4111-8111-111111111111'});assert.equal(seen,'/BOT/11111111-1111-4111-8111-111111111111');
await assert.rejects(commands.create({cwd:'/workspace'}),/Unknown/);
await assert.rejects(commands.create({cwd:'/bots/default'}),/Unknown/);
await assert.rejects(commands.create({cwd:'/BOT/default/../other'}),/Unknown/);
const provider={includeDefaultRoots:true,customSkillDirs:[],dshHome:'/shared/dsh',agentsHome:'/shared/agents',bundledSkillDir:'/bundled'};
for(const cwd of ['/BOT/11111111-1111-4111-8111-111111111111']) {
 const roots=await FileSystemSkillProvider.prototype.roots.call(provider,cwd);
 assert.ok(roots.some(r=>r.path===cwd+'/skills'));
 assert.ok(roots.some(r=>r.source==='bundled'));
 assert.ok(!roots.some(r=>r.source.startsWith('user-')));
}
assert.ok((await FileSystemSkillProvider.prototype.roots.call(provider,'/BOT/default')).some(r=>r.source==='user-dsh'));
const oldHeader={cwd:'/root/projects'};
const oldExec={agent:{session:{header:oldHeader,[Symbol.for('pd.bot.workspace')]:'/BOT/default'}},signal:new AbortController().signal};
assert.equal(resolveWorkdir(undefined,oldExec,undefined),'/BOT/default');
assert.equal(resolveWorkdir('documents',oldExec,undefined),'/BOT/default/documents');
assert.equal(sessionCwd(oldExec),'/BOT/default');
let searchCwd;setTestRgPath("/synthetic-rg");
await assert.rejects(()=>runRipgrep({subprocess:{spawn:req=>{searchCwd=req.cwd;throw Error('synthetic-stop');}}},oldExec,'glob',[],1024,100,1024));
assert.equal(searchCwd,'/BOT/default');
assert.equal(oldHeader.cwd,'/root/projects');
let presentTool,readCwd;
applyPresent({tools:{register:t=>{presentTool=t}},on:()=>{},sessionProjections:{stateOf:()=>({openTurnStartSeq:1,lastTurn:1})},fs:{lstat:async(p,o)=>{readCwd=o.cwd;return {type:'file'}},resolve:async(p,o)=>({targetKey:o.cwd+'/'+p,displayPath:o.cwd+'/'+p}),stat:async()=>({type:'file'})}},{maxFiles:4});
const delivered=await presentTool.execute({files:[{path:'documents/report.html'}]},oldExec);
assert.equal(readCwd,'/BOT/default');assert.equal(delivered.files[0].path,'/BOT/default/documents/report.html');
assert.deepEqual(validateJsonSchemaValue(presentTool.output.schema,delivered,'value'),[]);

console.log('Real pinned policy: explicit overrides, old events, permission commands and Bot working directories validated');
'''
    (target/'check.mjs').write_text(test)
    subprocess.run(['node',str(target/'check.mjs')],check=True)
print('15 real DSH module transforms parsed and validated')
