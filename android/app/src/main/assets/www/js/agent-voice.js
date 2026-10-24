// Only allowlisted live reply frames reach the native consent boundary; no history is replayed.
import {native} from './native.js';
export function voiceScope(botId,sessionId){
  native.call('agentVoiceScope',{botId,sessionId:sessionId||''}).catch(()=>{});
}
export function voiceFrame(botId,sessionId,frame){
  if(!sessionId||!frame?.attemptId)return;
  let value;
  if(frame.type==='start'||frame.type==='end')value={type:frame.type,attemptId:frame.attemptId};
  else if(frame.type==='chunk'&&frame.chunk?.type==='text-delta')value={type:'text',attemptId:frame.attemptId,text:frame.chunk.text||frame.chunk.delta||''};
  if(value)native.call('agentVoiceFrame',{botId,sessionId,frame:value}).catch(()=>{});
}
export function voiceInterrupted(botId,sessionId){if(sessionId)native.call('agentVoiceFrame',{botId,sessionId,frame:{type:'error'}}).catch(()=>{});}
