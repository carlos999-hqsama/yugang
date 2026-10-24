import { defineTool } from './dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { posix } from 'node:path';
import { hostRuntimeClient } from './app-host-bridge.mjs';
import { PROMPT_SECTION_ORDER } from './bot-workspace.mjs';

const output = {schema:{type:'object', additionalProperties:true, properties:{}},
  render:(_args,value)=>[{type:'text',text:JSON.stringify(value,null,2)}]};
const pluginPath=value=>{const path=posix.normalize(String(value||''));if(!path.startsWith('/PLUGINS/')||path.split('/').length<4)throw Error('文件必须位于 /PLUGINS/<插件ID>/');return path;};
export function applyPluginTools(ctx) {
  ctx.systemPrompt.section({name:'tool:phone-plugins',order:PROMPT_SECTION_ORDER.phonePlugins,text:
    'Use phone_plugins_* for plugin management. Plugin source and packages belong in /PLUGINS/<id>; ordinary artifacts belong in the current Bot workspace. For plugin work with the app-plugin skill, first read its host-contract.md: the host-maintained contract routes to references only when needed. Do not read every reference up front. If this Bot has no app-plugin skill, use the tools for requested management; full plugin development belongs in the plugin Bot. Never read another Bot workspace to obtain its skill. Use draft preview while iterating; after an actual install, verify the installed version separately. Report only real results and distinguish draft preview from installed/device acceptance.'});
  const register=(name,description,parameters,action,write=false)=>ctx.tools.register(defineTool({name,description,parameters,output,
    execute:async(args,exec)=>{
      if(write && !exec.agent?.session) throw Error('插件操作需要当前手机对话');
      let request={action,...args};
      if(action==='install') {
        if(Boolean(args.packageJson)===Boolean(args.packagePath))throw Error('packageJson 与 packagePath 必须选择一个');
        let json=args.packageJson;
        if(args.packagePath){const path=pluginPath(args.packagePath);if((await stat(path)).size>2*1024*1024)throw Error('插件包超过2MiB');json=await readFile(path,'utf8');}
        if(Buffer.byteLength(json)>2*1024*1024)throw Error('插件包超过2MiB');request={action,bundle:JSON.parse(json)};
      }
      const result=await hostRuntimeClient.request('plugin_manage',{request},exec.signal);
      if(action==='read'&&result.resources){
        if(args.exportPath){const path=pluginPath(args.exportPath);await mkdir(posix.dirname(path),{recursive:true});await writeFile(path,JSON.stringify(result,null,2));return {manifest:result.manifest,exportPath:path,resourcePaths:Object.keys(result.resources)};}
        const {resources,...metadata}=result;return {...metadata,resourcePaths:Object.keys(resources),hint:'用 exportPath 导出完整插件包后编辑资源'};
      }
      return result;
    }}));
  register('phone_plugins_list','查看手机插件、启用状态、完整管理权与可回退状态。',{},'list');
  register('phone_plugins_read','读取插件界面与逻辑，作为修改的起点，不读取凭据。',{id:{type:'string',required:true},exportPath:{type:'string',description:'可选：将完整页面插件导出到 /PLUGINS/<id>/package.json'}},'read');
  register('phone_plugins_install','注册或更新本机受信任插件。界面与逻辑全部属于插件包；Agent 对自建插件拥有完整管理权，无逐项授权。失败不改变已安装版本。',{packageJson:{type:'string',description:'完整插件包 JSON 字符串，最多2MiB，与packagePath二选一'},packagePath:{type:'string',description:'已写好的 /PLUGINS/<id>/package.json，与packageJson二选一'}},'install',true);
  register('phone_plugins_enable','启用或停用插件。',{id:{type:'string',required:true},enabled:{type:'boolean',required:true}},'enable',true);
  register('phone_plugins_rollback','将插件切回上一份已保存版本。',{id:{type:'string',required:true}},'rollback',true);
  register('phone_plugins_call','从核心对话调用已启用插件动作，返回真实回执；超时不自动重发。',{id:{type:'string',required:true},actionName:{type:'string',required:true},inputsJson:{type:'string',description:'插件输入 JSON 对象字符串'}},'invoke',true);
  register('phone_plugins_remove','移除可选插件及其安装态；检查 removed、cleanupPending、warning 回执，待清理不等于全部清理完成。保留源码和 Bot 工作区。',{id:{type:'string',required:true}},'remove',true);

  const previewSummary=value=>{
    if(!value||typeof value!=='object')return JSON.stringify(value);
    const result=value.result;
    const lines=[];
    lines.push(`预览插件：${value.plugin||result.plugin||'（未打开）'}${value.state?'｜状态：'+value.state:''}${typeof result.appResumed==='boolean'?'｜应用前台：'+(result.appResumed?'是':'否'):''}`);
    if(value.session)lines.push(`会话：${value.session}`);
    if(result.source)lines.push(`来源：${result.source==='draft'?'草稿':'已安装'}`);
    const network=result.network||result.preview?.network;
    if(network?.mode)lines.push(`网络：${network.mode}${network.delayMs?`（${network.delayMs}ms）`:''}`);
    if(typeof result.idleRemainingMs==='number')lines.push(`剩余空闲：${Math.max(0,result.idleRemainingMs)}ms｜剩余总时：${Math.max(0,result.maxRemainingMs)}ms`);
    if(value.state==='closed')lines.push(`关闭原因：${result.reason||value.reason||'未记录'}${result.closedAt?`｜关闭时间：${new Date(result.closedAt).toISOString()}`:''}`);
    if(value.ok===false||result.ok===false)lines.push(`操作：${value.ok===false||result.ok===false?'失败':'成功'}`);
    if(result.capture)lines.push(`截图：${result.capture}${result.captureReason?'（'+result.captureReason+'）':''}`);
    if(value.artifact)lines.push(`截图文件：${value.artifact}`);
    if(value.imageError)lines.push('图像无法交给模型：'+value.imageError);
    if(result.errors&&result.errors.length)lines.push(`页面错误 ${result.errors.length} 条：`+result.errors.slice(0,5).map(e=>`${e.kind}: ${e.message}`).join(' | '));
    else if(result.errors)lines.push('页面错误：0 条');
    if(typeof result.text==='string'&&result.text)lines.push(`页面文本：${result.text}${result.truncated?`（截取 ${result.textOffset}+${result.textLimit}/${result.textLength}，nextOffset=${result.nextOffset}）`:''}`);
    if(typeof result.html==='string'&&result.html)lines.push(`DOM 片段（${result.html.length} 字符）：`+result.html.slice(0,600));
    const error=value.error||result.error;
    if(error)lines.push('错误：'+error);
    return lines.join('\n');
  };
  const previewOutput={schema:{type:'object',additionalProperties:true,properties:{
    session:{type:'string'},plugin:{type:'string'},state:{type:'string',enum:['opening','ready','closed']},
    ok:{type:'boolean'},result:{type:'object',additionalProperties:true,properties:{}},artifact:{type:'string'},
    image:{type:'object',additionalProperties:true,properties:{}},imageError:{type:'string'}
  }},
    render:(_args,value)=>{
      const blocks=[{type:'text',text:previewSummary(value)}];
      if(value?.image) blocks.push({type:'image',attachment:value.image});
      return blocks;
    }};
  ctx.tools.register(defineTool({name:'phone_plugins_preview',
    description:'在独立离屏会话里预览已安装插件或 packageJson/packagePath 草稿并自查真实渲染：open 打开，snapshot 按 selector/offset/limit 分页读取 DOM、控制台错误与真实 PNG，click/input/scroll/reload 操作，clearStorage 只清当前预览，close/status 查看状态。预览不切换前台页面、不改动安装状态，也不允许安装、启用、移除或外部跳转。',
    parameters:{op:{type:'string',required:true,description:'open | snapshot | click | input | scroll | reload | clearStorage | close | status'},
      id:{type:'string',description:'已安装插件 ID，open 时与 packageJson/packagePath 二选一'},
      packageJson:{type:'string',description:'完整插件包 JSON 草稿字符串，最多2MiB，open 时与 id 二选一'},
      packagePath:{type:'string',description:'/PLUGINS/<插件ID>/package.json，运行时读取后作为草稿预览，open 时与 id 二选一'},
      allowAudio:{type:'boolean',description:'仅open：允许当前独立预览播放网页音频，默认false；不授予浏览器userActivation，也不改变前台页面'},
      network:{type:'object',description:'open 的网络模式',additionalProperties:false,properties:{
        mode:{type:'string',enum:['online','offline','slow'],description:'默认 online'},
        delayMs:{type:'number',description:'仅 slow，1-10000，默认2000'}
      }},
      selector:{type:'string',description:'CSS 选择器，snapshot/click/input/scroll 使用'},
      offset:{type:'number',description:'snapshot 可见文本偏移，0-1000000，默认0'},
      limit:{type:'number',description:'snapshot 可见文本长度，1-20000，默认2000'},
      text:{type:'string',description:'input 写入的文本'},
      y:{type:'number',description:'scroll 的滚动距离（像素）'},
      width:{type:'number',description:'预览视口宽度（CSS 像素，240-1024）'},
      height:{type:'number',description:'预览视口高度（CSS 像素，320-1600）'}},
    output:previewOutput,
    execute:async(args,exec)=>{
      if(!exec.agent?.session)throw Error('预览操作需要当前手机对话');
      const request={op:String(args.op||'status')};
      if(String(args.op)==='open'){
        let packageJson=args.packageJson;
        const hasPackage=Boolean(args.packageJson||args.packagePath);
        if(Boolean(args.id)===hasPackage)throw Error('open 需要在 id 和 packageJson/packagePath 之间选择一个');
        if(args.packagePath){
          const path=pluginPath(args.packagePath);
          if((await stat(path)).size>2*1024*1024)throw Error('插件包超过2MiB');
          packageJson=await readFile(path,'utf8');
        }
        if(packageJson){
          if(Buffer.byteLength(String(packageJson))>2*1024*1024)throw Error('插件包超过2MiB');
          JSON.parse(String(packageJson));
          request.packageJson=String(packageJson);
        }
      }
      if(args.allowAudio!==undefined){if(typeof args.allowAudio!=='boolean')throw Error('allowAudio须为布尔值');request.allowAudio=args.allowAudio;}
      if(args.id)request.id=String(args.id);
      if(args.network!=null)request.network=args.network;
      if(args.selector)request.selector=String(args.selector);
      if(args.offset!=null)request.offset=Number(args.offset);
      if(args.limit!=null)request.limit=Number(args.limit);
      if(args.text!=null)request.text=String(args.text);
      if(args.y!=null)request.y=Number(args.y);
      if(args.width)request.width=Number(args.width);
      if(args.height)request.height=Number(args.height);
      const result=await hostRuntimeClient.request('plugin_preview',{request},exec.signal);
      const workspace=exec.agent.session[Symbol.for('pd.bot.workspace')];
      const payload=result.result;
      const png=payload&&payload.png;
      if(workspace&&payload.capture==='ok'&&png&&typeof png.base64==='string'&&png.base64){
        const dir=posix.join(workspace,'documents');
        await mkdir(dir,{recursive:true});
        const file=posix.join(dir,`plugin-preview-${Date.now()}.png`);
        await writeFile(file,Buffer.from(png.base64,'base64'));
        result.artifact=file;
        const attachments=ctx.get?.('attachments');
        try {
          if(!attachments)throw Error('附件服务尚未就绪，可用 read_image 读取截图文件');
          result.image=await attachments.saveImage({data:Buffer.from(png.base64,'base64'),mediaType:'image/png',name:posix.basename(file)});
        } catch(error) { result.imageError=String(error.message||error); }
        // Durable image references, never megabytes of raw base64 in session/tool history.
        const {base64,...metadata}=png;
        payload.png=metadata;
      }
      if(png?.base64 && payload.capture!=='ok') {
        result.imageError='截图未就绪：'+(payload.captureReason||payload.capture||'未知');
        const {base64,...metadata}=png; payload.png=metadata;
      }
      return result;
    }}));
}
