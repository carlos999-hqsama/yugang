import {posix} from 'node:path';
import { createConnection } from 'node:net';

/** Metadata only, over the existing same-UID native channel. No credentials. */
function nativeBotRequest(request, signal) {
  if (signal?.aborted) return Promise.reject(Error('操作已取消'));
  const socketName = process.env.PD_APP_SOCKET;
  if (!socketName) return Promise.reject(Error('Bot 配置通道未就绪'));
  return new Promise((resolve,reject) => {
    const socket=createConnection({path:'\0'+socketName});let done=false,buffer=Buffer.alloc(0);
    const finish=(error,value)=>{if(done)return;done=true;signal?.removeEventListener('abort',abort);socket.destroy();error?reject(error):resolve(value);};
    const abort=()=>finish(Error('操作已取消'));
    signal?.addEventListener('abort',abort,{once:true});
    socket.setTimeout(5000,()=>finish(Error('Bot 配置读取超时')));
    socket.on('connect',()=>socket.write(JSON.stringify(request)+'\n'));
    socket.on('data',chunk=>{
      buffer=Buffer.concat([buffer,chunk]);if(buffer.length>512*1024)return finish(Error('Bot 配置响应过大'));
      const end=buffer.indexOf(10);if(end<0)return;
      try {const r=JSON.parse(buffer.subarray(0,end));if(r.error)throw Error(r.error);
        finish(null,r.value);
      }catch(e){finish(e);}
    });
    socket.on('error',()=>finish(Error('Bot 配置通道连接失败')));
    socket.on('end',()=>{if(!done)finish(Error('Bot 配置通道提前关闭'));});
    if(signal?.aborted)abort();
  });
}

export async function requestBotContext(sessionId, cwd, signal) {
  if (typeof sessionId !== 'string' || !sessionId || typeof cwd !== 'string' || !cwd) throw Error('Bot 会话缺少归属');
  const v=await nativeBotRequest({action:'bot-context',sessionId,cwd},signal);
  if(!v||typeof v.id!=='string'||!/^\/BOT\/(default|[0-9a-f-]{36})$/i.test(v.workspace)||!Array.isArray(v.enabledTools)||typeof v.defaultPersona!=='string'||!v.defaultPersona||typeof v.sharedPersona!=='string'||typeof v.instructions!=='string'||typeof v.memory!=='string')throw Error('Bot 配置响应无效');
  const hasLearnedPersona=v.learnedPersona!==undefined||v.learnedPersonaRevision!==undefined;
  if(hasLearnedPersona&&(typeof v.learnedPersona!=='string'||!v.learnedPersona||typeof v.learnedPersonaRevision!=='string'||!v.learnedPersonaRevision||v.learnedPersonaRevision.length>128||/[\u0000-\u001f\u007f]/.test(v.learnedPersonaRevision)))throw Error('Bot 学习人格响应无效');
  return v;
}

export async function requestBotRoute(session, targetId, targetSessionId, signal, admission) {
  if(!session?.id||!session.header?.cwd)throw Error('Bot 会话缺少归属');
  const request={action:'bot-route',sessionId:session.id,cwd:session.header.cwd};
  if(targetId!==undefined)request.targetId=targetId;
  if(targetSessionId!==undefined)request.targetSessionId=targetSessionId;
  if(admission){request.admission=admission.op;request.deliveryId=admission.messageId;}
  if(Buffer.byteLength(JSON.stringify(request))>8192)throw Error('Bot 路由请求过长');
  const result=await nativeBotRequest(request,signal);
  if(typeof result?.sender?.id!=='string'||typeof result.sender.name!=='string')throw Error('Bot 路由响应无效');
  if(!Array.isArray(result.sender.sessionIds)||result.sender.sessionIds.some(id=>typeof id!=='string'))throw Error('Bot 会话目录无效');
  if(targetId===undefined&&(!Array.isArray(result.bots)||result.bots.some(b=>typeof b?.id!=='string'||typeof b.name!=='string')))throw Error('Bot 目录无效');
  if(targetId!==undefined&&(!result.target||result.target.id!==targetId||typeof result.target.name!=='string'||result.target.workspace!==`/BOT/${targetId}`||typeof result.target.sessionId!=='string'))throw Error('Bot 目标响应无效');
  return result;
}

const inside=(path,root)=>typeof path==='string'&&path.startsWith('/')&&(posix.normalize(path)===root||posix.normalize(path).startsWith(root+'/'));
export function ownsSession(bot, sessionId, cwd) {
  if(bot.excludedSessionIds?.includes(sessionId))return false;
  if(bot.sessionIds?.includes(sessionId))return true;
  return inside(cwd,bot.workspace);
}

/** A view over the existing query service; no copied chat database. */
export async function ownHistoryQuery(query, exec, context=requestBotContext) {
  const session=exec?.agent?.session;
  const bot=await context(session?.id,session?.header?.cwd,exec?.signal);
  const allowed=r=>ownsSession(bot,r.header.id,r.header.cwd);
  const check=async id=>{const records=await query.filterSessions([{kind:'id',values:[id]}],exec?.signal);if(!records.some(allowed))throw Error('该会话不属于当前 Bot');};
  return {
    listSessions:async signal=>(await query.listSessions(signal)).filter(allowed),
    filterSessions:async(filters,signal)=>(await query.filterSessions(filters,signal)).filter(allowed),
    searchSessions:(...args)=>query.searchSessions(...args),
    searchEvents:async(request,...rest)=>{await check(request.sessionId);return query.searchEvents(request,...rest);},
    readEvent:async(request,...rest)=>{await check(request.sessionId);return query.readEvent(request,...rest);},
  };
}
