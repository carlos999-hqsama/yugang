import {esc} from './util.js';
import {S} from './icons.js';
import {fishSvg,normalizeBotFish} from './bot-fish.js';
export const botFish=bot=>normalizeBotFish(bot?.fish || (bot?.id==='default'?'whale':bot?.id==='00000000-0000-4000-8000-000000000001'?'swordfish':'goldfish'));
export const botName=bot=>bot?.name||'Bot';
export const BOT_AVATAR_SHAPES=Object.freeze(['circle','rounded-square','diamond','pentagon','hexagon','squircle','pebble','bean','clover','flower','droplet','capsule']);
export const BOT_AVATAR_LABELS=Object.freeze({circle:'圆形','rounded-square':'圆角方形',diamond:'菱形',pentagon:'五边形',hexagon:'六边形',squircle:'超椭圆',pebble:'卵石',bean:'豆形',clover:'四叶',flower:'花形',droplet:'水滴',capsule:'胶囊'});
export const BOT_COLORS=Object.freeze({ink:'#212121',brown:'#916239',red:'#D93648',orange:'#D86216',amber:'#B97908',green:'#168A5B',teal:'#148C8B',blue:'#2879DF',violet:'#8955DE',pink:'#C04494',stone:'#76767A',slate:'#444B56'});
export const BOT_COLOR_LABELS=Object.freeze({ink:'黑色',brown:'棕色',red:'红色',orange:'橙色',amber:'琥珀色',green:'绿色',teal:'青色',blue:'蓝色',violet:'紫色',pink:'粉色',stone:'灰色',slate:'深灰色'});
export function normalizeBotColor(value){return Object.hasOwn(BOT_COLORS,value)?value:'ink';}
export function normalizeBotAvatar(value){return BOT_AVATAR_SHAPES.includes(value)?value:'circle';}
export function ownerOfSession(registry,sid,session){
  const mapped=registry?.bots.find(b=>b.sessionIds.includes(sid));
  if(mapped)return mapped.id;
  const byWorkspace=registry?.bots.find(b=>b.workspace&&(session?.cwd===b.workspace||session?.cwd?.startsWith(b.workspace+'/')));
  if(byWorkspace)return byWorkspace.id;
  if(/^\/BOT(?:\/|$)/.test(session?.cwd||''))return null;
  return registry?.defaultBotId;
}
export function sessionsForBot(registry,botId,sessions){
  return [...sessions.values()].filter(s=>ownerOfSession(registry,s.sessionId,s)===botId)
    .sort((a,b)=>(b.updatedAt||0)-(a.updatedAt||0));
}
const avatar=(bot,large)=>`<span class="bot-avatar shape-${normalizeBotAvatar(bot?.avatar)}${large?' large':''} tone-${normalizeBotColor(bot?.color)}" aria-hidden="true">${fishSvg(botFish(bot))}</span>`;
export function botListHtml({registry,selected,query='',running=new Set(),waiting=()=>false,sessions=new Map()}){
  const rows=(registry?.bots||[]).filter(b=>!query||botName(b).toLowerCase().includes(query.toLowerCase()));
  if(!rows.length)return '<div class="bot-empty">'+(query?'没有找到这个 Bot':'点击上方 + 创建 Bot')+'</div>';
  const state=b=>{const all=sessionsForBot(registry,b.id,sessions);return all.some(s=>running.has(s.sessionId))?'<span class="bot-indicator running" aria-label="运行中"></span>':all.some(s=>waiting(s.sessionId))?'<span class="bot-indicator waiting" aria-label="等待处理"></span>':'';};
  const menu=b=>`<button type="button" class="bot-more" data-bot-menu="${esc(b.id)}" aria-label="${esc(botName(b))}的操作">${S.more}</button>`;
  const pins=rows.filter(b=>b.pinned),rest=rows.filter(b=>!b.pinned);
  let html=pins.length?`<div class="bot-pinned">${pins.map(b=>`<div class="bot-pin${b.id===selected?' act':''}"><button type="button" class="bot-pin-open" data-bot="${esc(b.id)}" aria-pressed="${b.id===selected}">${avatar(b,true)}${state(b)}<span class="bot-pin-name">${esc(botName(b))}</span></button>${menu(b)}</div>`).join('')}</div>`:'';
  if(rest.length)html+=`<div class="bot-robots">${rest.map(b=>`<div class="bot-robot${b.id===selected?' act':''}"><button type="button" class="bot-robot-open" data-bot="${esc(b.id)}" aria-pressed="${b.id===selected}">${avatar(b,false)}<b>${esc(botName(b))}</b>${state(b)}</button>${menu(b)}</div>`).join('')}</div>`;
  return html;
}
