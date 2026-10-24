// A small per-turn reporting contract, separate from tone and tool manuals.
export const REPORTING_CONTRACT = `整理、总结或汇报时，先保真，再分析：
- 事实记录以用户指定的材料和最新更正为准。数量、状态原词、报告原因、验证进度分别保留；没说的写未知，数量矛盾就指出，不凑数。不把前文无关假设当本次事实。
- 引述状态原词，不替用户重新裁定标签。执行结果、失败原因、是否复测、是否证明功能有缺陷是不同维度，可以并列解释，不能互相替换。失败有外部原因仍保留原报告的失败，待复测不抹掉之前的结果；反之，用户只说未执行也不能升级成失败。
- 先清楚给出原始记录，再按需另说你的分析和验证计划；分析不得改写记录或把推断说成已验证。正文与结尾速览保持同一组结果，不能只剩验证进度而省掉原状态。普通闲聊无需套汇报格式。`;
export const SOURCE_CONTEXT = 'agent:source-fidelity';
export function applySourceFidelity(ctx) {
  ctx.on('system-prompt/assemble', async (_assembly, context, next) => {
    const assembly = await next();
    const log = context?.scope?.session?.log;
    if (!Array.isArray(log)) return assembly;
    const boundary = log.findLast(event => event.type === 'turn/start' || event.type === 'turn/end');
    const contexts = assembly.contexts.filter(entry => entry.name !== SOURCE_CONTEXT);
    const turn = boundary?.data?.turn;
    if (boundary?.type !== 'turn/start' || !Number.isSafeInteger(turn)) {
      return contexts.length === assembly.contexts.length ? assembly : { ...assembly, contexts };
    }
    // SDK RuntimeContextProjection publishes only changed snapshots. The existing
    // canonical turn id makes this reminder current once per turn, not every step.
    return { ...assembly, contexts: [...contexts, {
      name: SOURCE_CONTEXT, order: 900,
      text: `本轮汇报约定（会话轮次 ${turn}）：\n${REPORTING_CONTRACT}`,
    }] };
  });
}
