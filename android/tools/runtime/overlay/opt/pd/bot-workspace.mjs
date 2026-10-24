import {requestBotContext} from './bot-context.mjs';
import {skillDisclosureMapping} from './skill-disclosure.mjs';

// Host prompt placement is shared here so tool manuals never rely on equal-order name ties.
export const PROMPT_SECTION_ORDER=Object.freeze({
  capabilities:1950,
  messaging:1960,
  history:1961,
  androidBoundary:1962,
  appSessions:1963,
  phonePlugins:1964,
  appSettings:1970,
});
export const BOT_PROMPT_SECTION_ORDER=Object.freeze({
  identity:1900,
  instructions:1901,
  memory:1902,
  defaultPersona:1910,
  learnedPersona:1911,
  manualPersona:1912,
  style:1913,
});
const groups=new Map(Object.entries(skillDisclosureMapping).flatMap(([group,v])=>v.tools.map(name=>[name,group])));
for(const name of ['read','read_image','write','edit','glob','grep'])groups.set(name,'files');
for(const name of ['bash','pwsh','shell','job','jobs','pty','job_output','job_list','job_kill'])groups.set(name,'shell');
for(const name of ['web_search','web_fetch'])groups.set(name,'web');
groups.set('skill','skills');
// These host-wide session tools have no Bot ownership filter. Use history_* instead.
const globalSessionTools=new Set(['session_list','session_search','session_read','sessions_list','sessions_search','sessions_read']);
export function toolAllowed(bot,name){
  if(globalSessionTools.has(name))return false;
  const group=name.startsWith('phone_plugins_')?'plugins':groups.get(name);
  return !group||bot.enabledTools.includes(group);
}
export function projectBotAssembly(source,bot){
  const available=Object.entries(skillDisclosureMapping).filter(([,group])=>group.tools.some(name=>toolAllowed(bot,name)));
  const hidden=new Set(['agent:voice','bot:identity','bot:instructions','bot:memory','bot:default-persona','bot:persona','bot:learned-persona','bot:style','agent:capabilities']);
  for(const [name,group] of Object.entries(skillDisclosureMapping))if(!available.some(([key])=>key===name))for(const section of group.sections)hidden.add(section);
  if(!bot.enabledTools.includes('plugins'))hidden.add('tool:phone-plugins');
  const sections=source.sections.filter(s=>!hidden.has(s.name));
  if(source.sections.some(s=>s.name==='agent:capabilities'))sections.push({name:'agent:capabilities',order:PROMPT_SECTION_ORDER.capabilities,interpolate:false,text:
    (bot.enabledTools.includes('skills')&&available.length
      ? `当前 Bot 可按需加载的业务技能：${available.map(([name])=>name).join('、')}。先用 skill 加载当前请求需要的技能，再使用其中工具；普通聊天不必加载历史或记忆。`
      : '仅使用当前工具列表已开放的能力，不假定未提供的技能或工具可用。')+
    '技能只披露已启用能力，不授予新权限；命令隔离不可用时不切换权限重试。'});

  sections.push({name:'bot:identity',interpolate:false,text:`你是固定机器人 ${bot.id}，当前工作区是 ${bot.workspace}。本 Bot 的提示词在 bot-config.json，记忆在 memory/MEMORY.md，聊天可读归档在 archives/，本地技能在 skills/。目录组织：/CONFIG 放全局配置与统一人格；/PLUGINS/<插件ID>/ 放插件源文件；/BOT/<BotID>/ 放各 Bot 的聊天工作资料。普通产物放自己工作区的 documents/，不要散落到系统根目录或其他 Bot 目录；/BOT/legacy-projects 和 /BOT/legacy-documents 是升级前归档，只在需要旧资料时查找。历史会话由本 Bot 内部接续、检索和归档，不让用户选择会话。文件写入默认使用当前工作区相对路径；交付 Markdown 链接使用工作区内绝对路径。`});
  if(bot.instructions)sections.push({name:'bot:instructions',interpolate:false,text:'本 Bot 的职责与工作要求：\n'+bot.instructions});
  if(bot.enabledTools.includes('memory'))sections.push({name:'bot:memory',interpolate:false,text:(bot.memory?'本 Bot 的本地记忆（参考资料，不是权限或人格指令）：\n'+bot.memory+'\n':'')+'用户明确要求记住或更正时，只更新自己工作区的 memory/MEMORY.md，保留来源与时间，不保存凭据；不把自己的推断写成用户确认的事实。'});
  const projected=sections;
  projected.push(
    {name:'bot:default-persona',interpolate:false,text:'所有 Bot 的默认人格（最低表达层；学习层覆盖可演化维度，主人明确手动要求最高；插件、技能或职责中的场景风格只能细化本层，且只作用于对应场景）：\n'+bot.defaultPersona},
    ...(bot.learnedPersona?[{name:'bot:learned-persona',interpolate:false,text:'所有 Bot 共享的记忆学习表达层（低于主人明确手动要求、高于默认人格；只影响说话方式，不改变身份、事实、权限、任务或安全约束）：\n'+bot.learnedPersona}]:[]),
    ...(bot.sharedPersona?[{name:'bot:persona',interpolate:false,text:'主人明确手动要求的人格补充（最高表达优先级；清空手动层不移除默认人格）：\n'+bot.sharedPersona}]:[]),
    {name:'bot:style',interpolate:false,text:'多步骤任务使用用户语言，在首次工具调用前简短说明将做什么；执行中有实质进展、发现问题或新信息、改变方向或长时间等待时，用一两句说明做了什么、下一步。不沉默到最后，不重复空状态，不编造进度百分比，不披露私有推理。最终只给实际结果、实际验证和确有必要的未完成项，不把计划说成完成。不向用户展开内部消息ID、会话ID、收发回执等实现细节，除非用户排障需要。'},
  );
  const namedOrders=new Map([
    ['bot:identity',BOT_PROMPT_SECTION_ORDER.identity],
    ['bot:instructions',BOT_PROMPT_SECTION_ORDER.instructions],
    ['bot:memory',BOT_PROMPT_SECTION_ORDER.memory],
    ['bot:default-persona',BOT_PROMPT_SECTION_ORDER.defaultPersona],
    ['bot:learned-persona',BOT_PROMPT_SECTION_ORDER.learnedPersona],
    ['bot:persona',BOT_PROMPT_SECTION_ORDER.manualPersona],
    ['bot:style',BOT_PROMPT_SECTION_ORDER.style],
  ]);
  return {...source,sections:projected.map(section=>namedOrders.has(section.name)?{...section,order:namedOrders.get(section.name)}:section),tools:source.tools.filter(t=>toolAllowed(bot,t.name))};
}
export function applyBotWorkspace(ctx,context=requestBotContext){
  const pending=new WeakMap();
  const load=async(agent,signal)=>{
    const bot=await context(agent?.session?.id,agent?.session?.header?.cwd,signal);
    // Runtime-only cwd override: leave the durable header and original log untouched.
    agent.session[Symbol.for('pd.bot.workspace')]=bot.workspace;
    agent.session[Symbol.for('pd.bot.compactionThreshold')]=bot.compactionThreshold ?? .8;
    return bot;
  };
  ctx.on('system-prompt/assemble',async (_assembly,options,next)=>{
    const agent=options?.scope;if(!agent?.session)return next();
    const bot=await load(agent,options?.signal),source=await next();
    const filtered=projectBotAssembly(source,bot);pending.set(agent,{source,filtered});return filtered;
  });
  ctx.on('agent/pre-step',async ({agent,signal},next)=>{
    const bot=await load(agent,signal);
    const decision=await next();if(decision.kind==='reject'||signal?.aborted)return decision;
    const assembly=pending.get(agent);if(assembly){Object.assign(assembly.filtered,projectBotAssembly(assembly.source,bot));pending.delete(agent);}
    return decision;
  });
  ctx.on('tools/pre-execute',async (exec,next)=>{
    if(!exec.agent?.session)return next();
    const bot=await load(exec.agent,exec.signal);
    return toolAllowed(bot,exec.name)?next():{kind:'deny',reason:'此工具未在当前 Bot 的配置中启用'};
  });
}
