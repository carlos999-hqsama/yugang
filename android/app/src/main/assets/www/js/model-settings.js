// Reuse the Harness-owned live settings and persistence, not a second model preference.
export async function readDefaultModel(call) {
  const [settings, catalog] = await Promise.all([call('settings/describe'), call('session/modelCatalog')]);
  const view = settings.namespaces?.find(n => n.ns === 'agent-default-model');
  if (!view || typeof view.value?.provider !== 'string' || typeof view.value?.model !== 'string') throw Error('默认模型配置尚未就绪，请重试。');
  return {view, catalog, writable: settings.writable === true};
}
export async function saveDefaultModel(call, state, raw) {
  const model = String(raw || '').trim();
  if (!model || model.length > 200 || /\s|[\x00-\x1f]/.test(model)) throw Error('请填写有效的模型 ID。');
  if (!state?.writable) throw Error('当前运行时不允许修改默认模型。');
  const current = state.view.value;
  const definition = state.catalog?.groups?.find(g => g.id === current.provider)?.models?.find(m => m.id === model);
  if (!definition) throw Error('当前服务未提供这个模型 ID，请从可用列表选择。');
  const efforts = definition.reasoning?.efforts || [];
  const effort = efforts.some(e => e.id === current.reasoningEffort) ? current.reasoningEffort : definition.reasoning?.defaultEffort;
  await call('settings/mutate', {ns:state.view.ns, expectedRevision:state.view.revision, ops:[
    {op:'set', path:['model'], value:model},
    effort ? {op:'set', path:['reasoningEffort'], value:effort} : {op:'unset', path:['reasoningEffort']},
  ]});
  const saved = await readDefaultModel(call);
  if (saved.view.value.provider !== current.provider || saved.view.value.model !== model || saved.catalog.default?.model !== model || saved.catalog.default?.provider !== current.provider) throw Error('默认模型尚未确认生效，请重新读取配置。');
  return saved;
}
