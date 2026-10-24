const group = (tools, sections) => Object.freeze({ tools: Object.freeze(tools), sections: Object.freeze(sections) });
export const skillDisclosureMapping = Object.freeze({
  'app-management': group(['app_settings_get','app_settings_update','app_sessions_list','app_sessions_delete','app_sessions_restore'], ['tool:app-settings','tool:app-sessions']),
  'conversation-history': group(['history_search','history_read'], ['tool:history']),
});
const owners = new Map(Object.entries(skillDisclosureMapping).flatMap(([name,g]) => g.tools.map(t => [t,name])));
const known = name => typeof name === 'string' && Object.hasOwn(skillDisclosureMapping,name);
const prefix = name => `<skill_content name="${name}">\n<skill_resources>\nBase directory for this skill: /opt/pd/skills/${name}\n`;
const rendered = (message,name) => message?.content?.[0]?.type === 'text' && typeof message.content[0].text === 'string' && message.content[0].text.startsWith(prefix(name));
const trusted = (skill,name) => skill?.name === name && skill.provider === 'filesystem' && skill.resourceBase?.kind === 'directory' && skill.resourceBase.path === `/opt/pd/skills/${name}`;
const skillName = args => { try { const name = (typeof args === 'string' ? JSON.parse(args) : args)?.name; return known(name) ? name : undefined; } catch { return undefined; } };

export function applySkillDisclosure(ctx) {
  const states = new WeakMap(), assemblies = new WeakMap();
  const stateFor = session => {
    if (!states.has(session)) states.set(session,{ loaded:new Set(), tentative:new Map(), pending:new Map(), cursor:0, turn:undefined });
    return states.get(session);
  };
  // Registry metadata is authoritative on replay too. Rendered text alone is not provenance.
  const valid = async (name,agent,signal) => {
    if (signal?.aborted) return false;
    try {
      const skill = await ctx.skills.get(name,{scope:agent,cwd:agent.session.header?.cwd,signal});
      return !signal?.aborted && trusted(skill,name);
    } catch { return false; }
  };
  const admit = (state,turn,name) => {
    if (!state.tentative.has(turn)) state.tentative.set(turn,new Set());
    state.tentative.get(turn).add(name);
  };
  const invocation = async (state,message,agent,signal) => {
    const name = message?.role === 'user' && message.source?.kind === 'skill-invocation' ? message.source.name : undefined;
    if (known(name) && rendered(message,name) && await valid(name,agent,signal)) admit(state,state.turn,name);
  };
  const hydrate = async (agent,signal) => {
    const session = agent?.session;
    if (!Array.isArray(session?.log)) return;
    const state = stateFor(session);
    for (; state.cursor < session.log.length; state.cursor++) {
      const {type,data} = session.log[state.cursor];
      if (type === 'turn/start') state.turn = data.turn;
      else if (type === 'tool/call' && data.name === 'skill') {
        const name = skillName(data.arguments);
        if (name && typeof data.callId === 'string') state.pending.set(data.callId,{name,turn:data.turn});
      } else if (type === 'tool/result') {
        const message = data.message, id = message?.toolCallId, call = state.pending.get(id);
        if (!call || message.source?.kind !== 'tool' || message.source.callId !== id) continue;
        state.pending.delete(id);
        if (message.isError === false && rendered(message,call.name) && await valid(call.name,agent,signal)) admit(state,call.turn,call.name);
      } else if (type === 'user/message') await invocation(state,data,agent,signal);
      else if (type === 'turn/end') {
        if (data.reason?.kind === 'completed') for (const name of state.tentative.get(data.turn) ?? []) state.loaded.add(name);
        state.tentative.delete(data.turn);
        for (const [id,call] of state.pending) if (call.turn === data.turn) state.pending.delete(id);
      }
    }
    return state;
  };
  const visible = state => new Set([...state.loaded,...[...state.tentative.values()].flatMap(names => [...names])]);
  const filter = async (assembly,agent,signal) => {
    const state = await hydrate(agent,signal);
    if (!state) return assembly;
    const loaded = visible(state), hidden = new Set();
    for (const [name,g] of Object.entries(skillDisclosureMapping)) if (!loaded.has(name)) for (const section of g.sections) hidden.add(section);
    return {...assembly,tools:assembly.tools.filter(t => !owners.has(t.name) || loaded.has(owners.get(t.name))),sections:assembly.sections.filter(s => !hidden.has(s.name))};
  };
  ctx.on('system-prompt/assemble',async (assembly,context,next) => {
    const source = await next(), filtered = await filter(source,context?.scope,context?.signal);
    if (context?.scope?.session) assemblies.set(context.scope,{source,filtered});
    return filtered;
  });
  // App plugin precedes SDK tool-skill. Unwind after its explicit invocation loader,
  // before AgentLoop.step renders this same assembly. No custom log records are needed.
  ctx.on('agent/pre-step',async ({agent,signal},next) => {
    const decision = await next();
    if (decision.kind === 'reject' || signal?.aborted) return decision;
    const pending = assemblies.get(agent), state = await hydrate(agent,signal);
    if (!pending || !state) return decision;
    for (const message of decision.messages ?? []) await invocation(state,message,agent,signal);
    Object.assign(pending.filtered,await filter(pending.source,agent,signal));
    assemblies.delete(agent);
    return decision;
  });
  ctx.on('tools/pre-execute',async (exec,next) => {
    const name = owners.get(exec?.name);
    if (!name) return next();
    const state = await hydrate(exec.agent,exec.signal);
    if (!exec.signal?.aborted && state && visible(state).has(name)) return next();
    return {kind:'deny',reason:`tool "${exec.name}" is unavailable until skill "${name}" is loaded`};
  });
}
