// Local synthetic preview of the actual App. No credentials, model calls or upstream requests.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const vendor=process.env.PD_DSH_VENDOR_ROOT||path.join(root,'.rtbuild/dl/dsh');
const require=createRequire(path.join(vendor,'package.json'));
const {WebSocketServer}=require('ws');
const www=path.join(root,'android/app/src/main/assets/www');
const port=+(process.env.PD_BOT_PREVIEW_PORT||18773);
const names=['我的助手','插件 Bot','Bot 3','随手记','资料整理'];
const rows=new Map(names.map((title,i)=>[String(i+1),{sessionId:String(i+1),cwd:'/workspace',updatedAt:Date.now()-i*3600000,blank:false,running:false,projections:{values:{title}}}]));
const botTools=['files','shell','web','skills','plugins','memory','app-management','conversation-history'];
const avatarShapes=['circle','rounded-square','diamond','pentagon','hexagon','squircle','pebble','bean','clover','flower','droplet','capsule'];
let bots={defaultBotId:'1',bots:names.map((name,i)=>({id:String(i+1),name,avatar:avatarShapes[i%avatarShapes.length],fish:['whale','swordfish','goldfish','angelfish','koi'][i],pinned:i<3,workspace:'/bots/'+(i+1),sessionIds:[String(i+1)],selectedSessionId:String(i+1),instructions:'',memory:'',enabledTools:[...botTools],compactionThreshold:.8}))};
let persona={text:'你是本机个人助手，保持直接、可靠。'};
const streams=new Map();const logs=new Map();const calls=[];
const cfg={harnessMode:'native',dshMode:'external',dshPaired:true,dshUrl:'http://127.0.0.1:3090',deepseekKeySet:true,appVersion:'synthetic-bot-ui-preview'};
const workspaces=[{workspaceId:'phone',path:'/workspace',title:'手机工作区',sessionIds:[...rows.keys()]}];
function record(seq,type,data){return {type:'event',event:{seq,time:Date.now()+seq,type,data}};}
for(const [id]of rows){logs.set(id,[record(1,'user/message',{id:'user-'+id,source:{kind:'user'},content:[{type:'text',text:id==='1'?'把额度监控做成插件，保留现在的数据来源。':id==='4'?'上次的想法已经记下了':'资料已整理，可以继续补充。'}]}),record(2,'turn/start',{}),record(3,'assistant/message',{message:{content:[{type:'text',text:'我先核对数据接口，再生成插件界面。完成后会独立检查结果。'}]}}),record(4,'assistant/message',{message:{content:[{type:'tool-call',id:'write-'+id,name:'write',arguments:JSON.stringify({file_path:'/workspace/plugin-preview.html',content:'synthetic preview',description:'生成插件界面'})}]}}),record(5,'tool/result',{message:{toolCallId:'write-'+id,content:[{type:'text',text:'合成文件已写入'}]}}),record(6,'assistant/message',{message:{content:[{type:'text',text:'插件界面已生成。你可以打开右侧产物查看。这里是本地合成演示，不调用真实模型。'}]}}),record(7,'turn/end',{reason:{kind:'completed'}})]);}
function item(socket,streamId,value){socket.send(JSON.stringify({type:'item',streamId,value}));}
function broadcast(endpoint,value){for(const [socket,map]of streams)for(const[id,entry]of map)if(entry.endpoint===endpoint)item(socket,id,value);}
function workspaceChanged(){broadcast('workspace/follow',{type:'baseline',value:{items:workspaces.map(w=>({...w,sessionIds:[...w.sessionIds]})),pinnedSessionIds:[],archivedSessionIds:[]}});}
function nativeBots(input={}){
 const b=bots.bots.find(b=>b.id===input.id);
 switch(input.op){
 case 'create':if(!b)bots.bots.push({id:input.id,name:input.name,avatar:avatarShapes.includes(input.avatar)?input.avatar:'circle',fish:input.fish||'goldfish',color:input.color||'ink',pinned:true,workspace:'/bots/'+input.id,sessionIds:[],selectedSessionId:null,instructions:'',memory:'',enabledTools:[...botTools],compactionThreshold:.8});break;
 case 'rename':if(b){b.name=input.name;b.avatar=avatarShapes.includes(input.avatar)?input.avatar:'circle';if(input.fish)b.fish=input.fish;if(input.color)b.color=input.color;}break;
 case 'pin':if(b)b.pinned=!!input.pinned;break;
 case 'bind':if(b){b.sessionIds=[...new Set([...b.sessionIds,input.sessionId])];b.selectedSessionId=input.sessionId;}break;
 case 'select':if(b)b.selectedSessionId=input.sessionId;break;
 case 'settings':if(!b)throw Error('Bot 不存在');return {workspace:b.workspace,instructions:b.instructions,memory:b.memory,enabledTools:[...b.enabledTools]};
 case 'saveSettings':if(b){b.instructions=input.instructions||'';b.memory=input.memory||'';b.enabledTools=input.enabledTools||[];if(input.compactionThreshold!==undefined)b.compactionThreshold=input.compactionThreshold;}break;
 case 'delete':if(!b)throw Error('Bot 不存在');if(input.confirmDelete!==true)throw Error('必须确认删除 Bot');bots.bots=bots.bots.filter(x=>x.id!==b.id);break;
 case 'persona':return {...persona};
 case 'savePersona':persona={text:input.text||''};break;
 }
 return JSON.parse(JSON.stringify(bots));
}
function result(method,args){
 const r=args.request||{};calls.push({method,sessionId:r.sessionId});
 switch(method){
 case 'session/list':return {items:[...rows.values()]};
 case 'session/modelCatalog':return {groups:[{id:'synthetic',name:'合成',models:[{id:'fixture',name:'合成模型'}]}],default:{provider:'synthetic',model:'fixture'}};
 case 'permissionPresets/catalog':return {options:[{value:'danger-full-access'}]};
 case 'workspace/create':{let w=workspaces.find(w=>w.path===r.path);if(!w){w={workspaceId:'bot-'+(workspaces.length+1),path:r.path,title:r.path,sessionIds:[]};workspaces.push(w);workspaceChanged();}return {workspace:{...w,sessionIds:[...w.sessionIds]}};}
 case 'session/create':if(!rows.has(r.sessionId)){rows.set(r.sessionId,{sessionId:r.sessionId,cwd:'/workspace',updatedAt:Date.now(),blank:true,projections:{values:{title:'新 Bot'}}});logs.set(r.sessionId,[]);workspaceChanged();}return {sessionId:r.sessionId};
 case 'session/page':return {records:logs.get(r.address?.sessionId)||[],hasMore:false};
 case 'session/cancel':return {cancelled:true};
 default:return {};
 }
}
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'};
const send=(res,value)=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(value));};
const server=http.createServer(async(req,res)=>{
 const u=new URL(req.url,'http://local');
 if(u.pathname==='/__fixture/state')return send(res,{bots:JSON.parse(JSON.stringify(bots)),calls});
 if(u.pathname==='/fixture-init.js'){res.writeHead(200,{'Content-Type':'text/javascript'});res.end("if(!localStorage.getItem('pd.h.sel'))localStorage.setItem('pd.h.sel','\"1\"');if(!localStorage.getItem('pd.h.botId'))localStorage.setItem('pd.h.botId','\"1\"');if(!localStorage.getItem('pd.h.inspector'))localStorage.setItem('pd.h.inspector','true');");return;}
 if(req.method==='POST'){
 let body='';for await(const chunk of req)body+=chunk;let d;try{d=JSON.parse(body);}catch{send(res,{error:'synthetic invalid request'});return;}
 if(u.pathname==='/__dev/call'){let value=true;switch(d.method){case 'getConfig':value=cfg;break;case 'rtStatus':value={state:'ready',bundled:true};break;case 'bots':value=nativeBots(d.args||{});break;case 'plugins':value={plugins:[]};break;}return send(res,{value});}
 if(u.pathname==='/__dev/http'){
  if(d.target!=='dsh')return send(res,{value:{status:503,text:'{"detail":"isolated preview"}',headers:{}}});
  if(d.method==='HEAD')return send(res,{value:{status:200,text:'',headers:{'content-length':'120'}}});
  const p=JSON.parse(d.body||'{}');return send(res,{value:{status:200,headers:{},text:JSON.stringify({result:{ok:true,value:result(p.method,p.payload?.args||{})}})}});
 }
 }
 if(u.pathname.startsWith('/__dev/dshfile')){res.writeHead(200,{'Content-Type':'text/html'});res.end('<!doctype html><meta charset="utf-8"><h1>插件界面 · 合成产物</h1><p>这是隔离预览，没有调用真实模型或服务。</p>');return;}
 const filename=path.resolve(www,u.pathname==='/'?'index.html':'.'+u.pathname);
 if(filename.startsWith(www+path.sep)&&fs.existsSync(filename)&&fs.statSync(filename).isFile()){
  res.writeHead(200,{'Content-Type':mime[path.extname(filename)]||'application/octet-stream','Cache-Control':'no-store'});
  if(filename.endsWith('/index.html'))res.end(fs.readFileSync(filename,'utf8').replace('<script type="module"','<script src="/fixture-init.js"></script><script type="module"'));else fs.createReadStream(filename).pipe(res);return;
 }
 res.writeHead(404);res.end('isolated preview');
});
const wss=new WebSocketServer({server,path:'/__dev/ws'});
wss.on('connection',socket=>{
 streams.set(socket,new Map());socket.on('close',()=>streams.delete(socket));
 socket.on('message',buffer=>{const f=JSON.parse(String(buffer));const map=streams.get(socket);
 if(f.type==='cancel'){map.delete(f.streamId);return;}if(f.type!=='open')return;map.set(f.streamId,f);
 if(f.endpoint==='workspace/follow')item(socket,f.streamId,{type:'baseline',value:{items:workspaces.map(w=>({...w,sessionIds:[...w.sessionIds]})),pinnedSessionIds:[],archivedSessionIds:[]}});
 if(f.endpoint==='session/follow'){const id=f.payload.args.request.address.sessionId,records=logs.get(id)||[];item(socket,f.streamId,{type:'snapshot',header:{cwd:'/workspace'},records,cursor:records.at(-1)?.event.seq||0,hasMore:false});}
 });
});
server.listen(port,'127.0.0.1',()=>console.log('Synthetic Bot UI preview: http://127.0.0.1:'+port));
