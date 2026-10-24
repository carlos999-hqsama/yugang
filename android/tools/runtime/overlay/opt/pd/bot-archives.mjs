import {mkdir,lstat,writeFile,rename,unlink} from 'node:fs/promises';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {requestBotContext} from './bot-context.mjs';

export function archiveProjection(botId,session,events){
  return {botId,sessionId:session.id,messages:events.flatMap(event=>{
    const role=event.type==='user/message'?'user':event.type==='assistant/message'?'assistant':null;
    if(!role||(role==='user'&&event.data?.source?.kind!=='user'))return[];
    const content=role==='user'?event.data?.content:event.data?.message?.content;
    const text=(content||[]).filter(c=>c.type==='text'&&typeof c.text==='string').map(c=>c.text).join('\n');
    return text?[{seq:event.seq,time:event.time,role,text}]:[];
  })};
}
export async function saveArchive(bot,session,events){
  const dir=join(bot.workspace,'archives');await mkdir(dir,{recursive:true});
  if((await lstat(dir)).isSymbolicLink())throw Error('归档目录不能是符号链接');
  const file=join(dir,encodeURIComponent(session.id)+'.json');
  const data=JSON.stringify(archiveProjection(bot.id,session,events));
  if(Buffer.byteLength(data)>32*1024*1024)throw Error('会话过大，原始记录仍保留');
  const temp=file+'.'+randomUUID()+'.tmp';
  try{await writeFile(temp,data,{flag:'wx',mode:0o600});await rename(temp,file);}finally{await unlink(temp).catch(()=>{});}
}
export const name='bot-archives';
export const inject=['sessionQuery'];
export function apply(ctx){
  let stopped=false;const pending=new Map();
  const exportOne=(id)=>{
    const previous=pending.get(id)||Promise.resolve();
    const next=previous.catch(()=>{}).then(async()=>{
      if(stopped)return;
      const loaded=await ctx.sessionQuery.readSession(id),header=loaded.session;
      if(typeof header.cwd!=='string')return;
      const bot=await requestBotContext(id,header.cwd);
      if(!stopped)await saveArchive(bot,header,loaded.events);
    }).catch(()=>{ctx.logger?.warn('Bot 会话归档副本未更新；DSH 原始记录保留');});
    pending.set(id,next);void next.finally(()=>{if(pending.get(id)===next)pending.delete(id);});return next;
  };
  ctx.on('session/event',(session,event)=>{if(event.type==='turn/end')void exportOne(session.id);});
  ctx.effect(()=>{void(async()=>{
    try{for(const record of await ctx.sessionQuery.listSessions()){if(stopped)break;if(record.header.cwd)await exportOne(record.header.id);}}
    catch{ctx.logger?.warn('Bot 旧会话归档未完成；DSH 原始记录保留');}
  })();return()=>{stopped=true;};});
}
export default {name,inject,apply};
